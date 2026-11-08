/*
 * Copyright 2026 Gagik Sargsyan
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.ketraterm.session

import io.github.ketraterm.input.event.TerminalInputEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Session-owned rendezvous for an optional native shell-completion producer.
 * Producers announce a new prompt epoch with [ready] and deliver correlated replies with [respond].
 * These operations never send input, start jobs, or invoke consumer callbacks.
 * Consumers request completion through [TerminalSession.requestShellCompletions].
 */
public class TerminalShellCompletionChannel internal constructor(
    private val lock: Any,
) {
    private var epoch: String? = null
    private var closed = false
    private var nextRequestId = 0L
    private var requestInput: List<TerminalInputEvent> = emptyList()
    internal var pending: Pending? = null
        private set
    private var receipt: Receipt? = null
    private val mutableCommands = MutableStateFlow<TerminalShellCommandInventory?>(null)
    internal val commands = mutableCommands.asStateFlow()
    private var contextInvalidationRevision = 0L
    private val mutableContextInvalidations = MutableStateFlow(0L)
    internal val contextInvalidations = mutableContextInvalidations.asStateFlow()

    /**
     * Publishes a detached command inventory from the current shell environment.
     * Null means no installed inventory producer. Pending/failed producers publish
     * an explicit inventory status. It grants no editing authority or query hook.
     */
    public fun publishCommands(commands: TerminalShellCommandInventory?) {
        synchronized(lock) {
            if (closed) return
            mutableCommands.value = commands
        }
    }

    internal val isReady: Boolean get() = epoch != null

    /** Clears any active prompt epoch and invalidates cached completion receipts. */
    public fun clearPrompt() {
        synchronized(lock) {
            epoch = null
            receipt = null
            pending?.apply {
                responded = true
                response = null
            }
        }
    }

    /**
     * Installs a prompt-scoped epoch and its non-executing completion key chord.
     * Refreshing the same epoch invalidates confirmation but preserves its request sequence.
     * Calls after session closure are ignored.
     */
    public fun ready(
        epoch: String,
        requestInput: List<TerminalInputEvent>,
    ) {
        require(epoch.length in 1..128 && epoch.all { it.isLetterOrDigit() })
        require(requestInput.isNotEmpty() && requestInput.size <= 4)
        synchronized(lock) {
            if (closed) return
            if (this.epoch != epoch) nextRequestId = 0L
            this.epoch = epoch
            this.requestInput = requestInput.toList()
            receipt = null
            pending?.apply {
                responded = true
                response = null
            }
        }
    }

    /** Copies no borrowed parser storage; [result] must be a fully validated owned value, or a failed query. */
    public fun respond(
        epoch: String,
        requestId: Long,
        result: TerminalShellCompletionResult?,
    ) {
        synchronized(lock) {
            val current = pending ?: return
            if (this.epoch != epoch || current.requestId != requestId || current.responded) return
            current.response = result
            current.responded = true
        }
    }

    internal fun begin(
        expected: TerminalCommandEditContext,
        frameGeneration: Long,
    ): Pending? {
        if (closed || epoch == null || pending != null || nextRequestId == Long.MAX_VALUE) return null
        receipt = null
        return Pending(++nextRequestId, expected, requestInput, frameGeneration, this).also { pending = it }
    }

    /** Called under session mutation serialization; cancellation owns a waiter, never another caller's result. */
    internal fun join(
        expected: TerminalCommandEditContext,
        frameGeneration: Long,
    ): Pending? {
        val current = pending ?: return null
        if (current.waiters == 0 ||
            current.responded ||
            current.editInvalidated ||
            expected.inputRevision != current.expected.inputRevision + 1 ||
            expected.shellRevision != current.expected.shellRevision ||
            expected.prefixResetKeys != current.expected.prefixResetKeys ||
            expected.commandLine != current.expected.commandLine ||
            frameGeneration != current.frameGeneration
        ) {
            return null
        }
        current.waiters++
        return current
    }

    internal fun release(current: Pending) {
        synchronized(lock) {
            current.waiters--
        }
    }

    internal fun finish(
        inputRevision: Long,
        outputRevision: Long,
        shellRevision: Long,
        snapshot: TerminalShellCommandLineSnapshot?,
        frameGeneration: Long,
    ): Pending? {
        val current = pending?.takeIf { it.responded } ?: return null
        pending = null
        val result = current.response
        val expected = current.expected
        val matchesCurrentBuffer =
            !closed &&
                current.waiters > 0 &&
                result != null &&
                snapshot != null &&
                snapshot.commandText == expected.commandLine.commandText &&
                snapshot.cursorOffset == expected.commandLine.cursorOffset &&
                result.commandLine == snapshot.commandText &&
                result.cursorOffset == snapshot.cursorOffset
        if (matchesCurrentBuffer &&
            !current.editInvalidated &&
            inputRevision == expected.inputRevision + 1 &&
            frameGeneration == current.frameGeneration &&
            shellRevision == expected.shellRevision &&
            snapshot == expected.commandLine
        ) {
            receipt = Receipt(expected, inputRevision, outputRevision, result)
        } else {
            current.response = null
            if (matchesCurrentBuffer) {
                current.contextInvalidationRevision = markContextInvalidated()
            }
        }
        return current
    }

    internal fun permits(
        expected: TerminalCommandEditContext,
        inputRevision: Long,
        outputRevision: Long,
    ): Boolean {
        val completed = receipt ?: return false
        val capturedBeforeQuery =
            expected.inputRevision == completed.before.inputRevision && expected.outputRevision == completed.before.outputRevision
        val capturedAfterQuery = expected.inputRevision == completed.inputRevision && expected.outputRevision == completed.outputRevision
        return (capturedBeforeQuery || capturedAfterQuery) &&
            expected.shellRevision == completed.before.shellRevision &&
            expected.prefixResetKeys == completed.before.prefixResetKeys &&
            expected.commandLine == completed.before.commandLine &&
            completed.inputRevision == inputRevision &&
            completed.outputRevision == outputRevision
    }

    /** Reuses confirmation only while the freshly captured editor context still owns its exact receipt. */
    internal fun confirmedResult(expected: TerminalCommandEditContext): TerminalShellCompletionResult? {
        if (!expected.commandLine.requiresShellConfirmation) return null
        val completed = receipt ?: return null
        return completed.result.takeIf { permits(expected, expected.inputRevision, expected.outputRevision) }
    }

    internal fun close(): Pending? =
        synchronized(lock) {
            closed = true
            epoch = null
            mutableCommands.value = null
            receipt = null
            pending.also { pending = null }
        }

    internal fun invalidateEdit() {
        receipt = null
        pending?.editInvalidated = true
    }

    /** Reserves an invalidation under session serialization; publication must follow lock release. */
    internal fun markContextInvalidated(): Long {
        if (closed) return 0L
        check(contextInvalidationRevision != Long.MAX_VALUE) { "Completion context revisions exhausted" }
        return ++contextInvalidationRevision
    }

    internal fun publishContextInvalidation(revision: Long) {
        if (revision != 0L) mutableContextInvalidations.update { maxOf(it, revision) }
    }

    internal class Pending(
        val requestId: Long,
        val expected: TerminalCommandEditContext,
        val input: List<TerminalInputEvent>,
        val frameGeneration: Long,
        private val channel: TerminalShellCompletionChannel,
    ) {
        val deferred = CompletableDeferred<TerminalShellCompletionResult?>()
        var waiters = 1

        // Consumer cancellation does not mean that the shell has consumed its request.
        val retired = CompletableDeferred<Unit>()
        var responded = false
        var response: TerminalShellCompletionResult? = null
        var editInvalidated = false
        var contextInvalidationRevision = 0L

        // Resume consumers only after releasing session locks.
        fun complete() {
            channel.publishContextInvalidation(contextInvalidationRevision)
            deferred.complete(response)
            retired.complete(Unit)
        }
    }

    private class Receipt(
        val before: TerminalCommandEditContext,
        val inputRevision: Long,
        val outputRevision: Long,
        val result: TerminalShellCompletionResult,
    )
}
