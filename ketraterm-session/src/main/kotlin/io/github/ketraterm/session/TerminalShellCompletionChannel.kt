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
import io.github.ketraterm.input.event.TerminalKeyEvent
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
    private var requestInput: ((Long) -> List<TerminalInputEvent>)? = null
    private var confirmationInput: ((Long) -> List<TerminalInputEvent>)? = null
    internal var pending: Pending? = null
        private set
    private var receipt: Receipt? = null
    private var confirmationRequiredFor: TerminalCommandEditContext? = null
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

    /** Clears any active prompt epoch and invalidates cached completion receipts. */
    public fun clearPrompt() {
        synchronized(lock) {
            epoch = null
            requestInput = null
            confirmationInput = null
            receipt = null
            confirmationRequiredFor = null
            pending?.apply {
                responded = true
                response = null
            }
        }
    }

    /**
     * Installs a prompt-scoped epoch and its non-executing request encoder.
     * The encoder must include the supplied positive request identifier in the shell request;
     * the producer must echo that identifier in [respond]. Identifiers are never reused during
     * this session, including across prompt epochs. Every announcement invalidates confirmation.
     *
     * [requestInput] runs synchronously under session serialization when a query is admitted.
     * It must be bounded, nonblocking, and non-reentrant, and return between one and 64 semantic
     * input events. The session detaches that list before reserving the query or sending input.
     * Calls after session closure are ignored.
     */
    public fun ready(
        epoch: String,
        requestInput: (Long) -> List<TerminalInputEvent>,
    ): Unit = ready(epoch, requestInput, null)

    /**
     * Installs a prompt with optional non-executing buffer-only [confirmationInput].
     * Both encoders follow [ready]'s request bounds and correlation contract. A null
     * confirmation encoder uses [requestInput] for buffer confirmation as well.
     * Buffer-only replies must not invoke candidate completers or mutate the editor.
     */
    public fun ready(
        epoch: String,
        requestInput: (Long) -> List<TerminalInputEvent>,
        confirmationInput: ((Long) -> List<TerminalInputEvent>)?,
    ) {
        require(epoch.length in 1..128 && epoch.all { it.isLetterOrDigit() })
        synchronized(lock) {
            if (closed) return
            this.epoch = epoch
            this.requestInput = requestInput
            this.confirmationInput = confirmationInput
            receipt = null
            confirmationRequiredFor = null
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
        confirmationOnly: Boolean = false,
    ): Pending? {
        if (closed || epoch == null || pending != null || nextRequestId == Long.MAX_VALUE) return null
        val confirmation = confirmationInput.takeIf { confirmationOnly }
        val encodeRequest = confirmation ?: requestInput ?: return null
        val requestId = nextRequestId + 1
        val input = encodeRequest(requestId)
        require(input.isNotEmpty() && input.size <= 64) { "Completion requests must contain between one and 64 input events" }
        val detachedInput = input.toList()
        val current = Pending(requestId, expected, detachedInput, includesCandidates = confirmation == null)
        nextRequestId = requestId
        receipt = null
        confirmationRequiredFor = expected
        pending = current
        return current
    }

    /** Called under session mutation serialization; cancellation owns a waiter, never another caller's result. */
    internal fun join(
        expected: TerminalCommandEditContext,
        confirmationOnly: Boolean = false,
    ): Pending? {
        val current = pending ?: return null
        if (current.waiters == 0 ||
            current.responded ||
            !confirmationOnly &&
            !current.includesCandidates ||
            !sameAuthority(expected, current.expected)
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
        geometryRevision: Long,
        shellRevision: Long,
        prefixResetKeys: List<TerminalKeyEvent>,
    ): Pending? {
        val current = pending?.takeIf { it.responded } ?: return null
        pending = null
        val result = current.response
        val expected = current.expected
        if (!closed &&
            current.waiters > 0 &&
            result != null &&
            inputRevision == expected.inputRevision &&
            geometryRevision == expected.geometryRevision &&
            shellRevision == expected.shellRevision &&
            prefixResetKeys == expected.prefixResetKeys &&
            result.commandLine == expected.commandLine.commandText &&
            result.cursorOffset == expected.commandLine.cursorOffset
        ) {
            receipt = Receipt(expected, result, current.includesCandidates)
        } else {
            current.response = null
        }
        return current
    }

    internal fun permits(expected: TerminalCommandEditContext): Boolean = receipt?.let { sameAuthority(expected, it.expected) } == true

    /** Custom completers may edit their buffer; this editor state needs a verified reply before admission. */
    internal fun requiresConfirmation(expected: TerminalCommandEditContext): Boolean =
        confirmationRequiredFor?.let { sameEditorState(expected, it) } == true

    /** A live native query keeps its original request viable while the terminal repaints. */
    internal fun isConfirming(expected: TerminalCommandEditContext): Boolean =
        pending?.let { it.waiters > 0 && sameAuthority(expected, it.expected) } == true

    /**
     * Candidate collection follows native confirmation even while its screen projection is unavailable.
     * The receipt belongs to the editor state; cancelling its first caller revokes only that caller's edit token.
     */
    internal fun confirmedResult(
        commandLine: String,
        cursorOffset: Int,
        inputRevision: Long,
        geometryRevision: Long,
        shellRevision: Long,
        prefixResetKeys: List<TerminalKeyEvent>,
    ): TerminalShellCompletionResult? {
        val completed = receipt ?: return null
        if (!completed.includesCandidates) return null
        val expected = completed.expected
        return completed.result.takeIf {
            expected.commandLine.requiresShellConfirmation &&
                expected.commandLine.commandText == commandLine &&
                expected.commandLine.cursorOffset == cursorOffset &&
                expected.inputRevision == inputRevision &&
                expected.geometryRevision == geometryRevision &&
                expected.shellRevision == shellRevision &&
                expected.prefixResetKeys == prefixResetKeys
        }
    }

    /** Reuses confirmation only while the editor context still owns its exact receipt. */
    internal fun confirmedResult(
        expected: TerminalCommandEditContext,
        confirmationOnly: Boolean = false,
    ): TerminalShellCompletionResult? {
        if (!expected.commandLine.requiresShellConfirmation) return null
        val completed = receipt ?: return null
        if (!confirmationOnly && !completed.includesCandidates) return null
        return completed.result.takeIf { sameAuthority(expected, completed.expected) }
    }

    internal fun close(): Pending? =
        synchronized(lock) {
            closed = true
            epoch = null
            requestInput = null
            confirmationInput = null
            mutableCommands.value = null
            receipt = null
            confirmationRequiredFor = null
            pending.also { pending = null }
        }

    private fun sameEditorState(
        first: TerminalCommandEditContext,
        second: TerminalCommandEditContext,
    ): Boolean =
        first.inputRevision == second.inputRevision &&
            first.geometryRevision == second.geometryRevision &&
            first.shellRevision == second.shellRevision &&
            first.prefixResetKeys == second.prefixResetKeys

    private fun sameAuthority(
        first: TerminalCommandEditContext,
        second: TerminalCommandEditContext,
    ): Boolean =
        sameEditorState(first, second) &&
            first.commandLine.commandText == second.commandLine.commandText &&
            first.commandLine.cursorOffset == second.commandLine.cursorOffset

    internal class Pending(
        val requestId: Long,
        val expected: TerminalCommandEditContext,
        val input: List<TerminalInputEvent>,
        val includesCandidates: Boolean,
    ) {
        val deferred = CompletableDeferred<TerminalShellCompletionResult?>()
        var waiters = 1

        // Consumer cancellation does not mean that the shell has consumed its request.
        val retired = CompletableDeferred<Unit>()
        var responded = false
        var response: TerminalShellCompletionResult? = null

        // Resume consumers only after releasing session locks.
        fun complete() {
            deferred.complete(response)
            retired.complete(Unit)
        }
    }

    private class Receipt(
        val expected: TerminalCommandEditContext,
        val result: TerminalShellCompletionResult,
        val includesCandidates: Boolean,
    )
}
