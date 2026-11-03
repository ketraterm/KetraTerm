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

    /** Installs a prompt-scoped epoch and its non-executing completion key chord. */
    public fun ready(
        epoch: String,
        requestInput: List<TerminalInputEvent>,
    ) {
        require(epoch.length in 1..128 && epoch.all { it.isLetterOrDigit() })
        require(requestInput.isNotEmpty() && requestInput.size <= 4)
        synchronized(lock) {
            this.epoch = epoch
            this.requestInput = requestInput.toList()
            nextRequestId = 0L
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
        if (epoch == null || pending != null || nextRequestId == Long.MAX_VALUE) return null
        receipt = null
        return Pending(++nextRequestId, expected, requestInput, frameGeneration).also { pending = it }
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
        if (current.deferred.isActive &&
            result != null &&
            !current.editInvalidated &&
            inputRevision == expected.inputRevision + 1 &&
            frameGeneration == current.frameGeneration &&
            shellRevision == expected.shellRevision &&
            snapshot != null &&
            snapshot == expected.commandLine &&
            result.commandLine == snapshot.commandText &&
            result.cursorOffset == snapshot.cursorOffset
        ) {
            receipt = Receipt(expected, inputRevision, outputRevision)
        } else {
            current.response = null
        }
        return current
    }

    internal fun permits(
        expected: TerminalCommandEditContext,
        inputRevision: Long,
        outputRevision: Long,
    ): Boolean {
        val completed = receipt ?: return false
        return expected.inputRevision == completed.before.inputRevision &&
            expected.outputRevision == completed.before.outputRevision &&
            expected.shellRevision == completed.before.shellRevision &&
            expected.commandLine == completed.before.commandLine &&
            completed.inputRevision == inputRevision &&
            completed.outputRevision == outputRevision
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

    internal class Pending(
        val requestId: Long,
        val expected: TerminalCommandEditContext,
        val input: List<TerminalInputEvent>,
        val frameGeneration: Long,
    ) {
        val deferred = CompletableDeferred<TerminalShellCompletionResult?>()
        var responded = false
        var response: TerminalShellCompletionResult? = null
        var editInvalidated = false
    }

    private class Receipt(
        val before: TerminalCommandEditContext,
        val inputRevision: Long,
        val outputRevision: Long,
    )
}
