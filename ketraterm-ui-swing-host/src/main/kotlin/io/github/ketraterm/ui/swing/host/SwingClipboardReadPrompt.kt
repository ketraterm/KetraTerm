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
package io.github.ketraterm.ui.swing.host

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.swing.SwingUtilities
import kotlin.coroutines.resume

/**
 * Cancellable read consent owned by one terminal pane. All operations belong to
 * the EDT. The product presents its standard dialog and returns a handle that
 * closes it. No clipboard content is passed to the dialog.
 *
 * Cancellation, expiry and pane disposal close the dialog. Blocking persists
 * for this pane's lifetime, including later requests admitted with Allow.
 * @param showDialog opens product UI, delivers a decision on the EDT, and returns its disposal handle.
 */
class SwingClipboardReadPrompt(
    private val showDialog: (request: SwingDialogRequest, decide: (Int?) -> Unit) -> AutoCloseable,
) : AutoCloseable {
    /** Choices shared by both products' standard clipboard dialogs. */
    enum class Decision(
        val label: String,
    ) {
        ALLOW_ONCE("Allow once"),
        DENY("Deny"),
        BLOCK("Block for this terminal"),
    }

    private var pending: CancellableContinuation<Boolean>? = null
    private var blocked = false
    private var closed = false

    /** Whether this pane has been blocked or closed. */
    val isBlocked: Boolean
        get() {
            check(SwingUtilities.isEventDispatchThread())
            return blocked || closed
        }

    /** Suspends for one decision; concurrent requests cannot replace the current dialog. */
    suspend fun request(message: String): Boolean {
        check(SwingUtilities.isEventDispatchThread())
        if (isBlocked || pending != null) return false
        var dialog: AutoCloseable? = null
        try {
            return suspendCancellableCoroutine { continuation ->
                pending = continuation
                dialog =
                    showDialog(
                        SwingDialogRequest(
                            SwingClipboardPrompts.TITLE,
                            message,
                            SwingDialogRequest.Severity.WARNING,
                            Decision.entries.map { it.label },
                            defaultOption = Decision.DENY.ordinal,
                        ),
                    ) { option ->
                        check(SwingUtilities.isEventDispatchThread())
                        if (pending === continuation && continuation.isActive) {
                            val decision = Decision.entries.getOrNull(option ?: -1) ?: Decision.DENY
                            if (decision == Decision.BLOCK) blocked = true
                            continuation.resume(decision == Decision.ALLOW_ONCE)
                        }
                    }
            }
        } finally {
            pending = null
            dialog?.close()
        }
    }

    /** Denies a pending request, returning whether terminal Escape was consumed. */
    fun dismiss(): Boolean {
        check(SwingUtilities.isEventDispatchThread())
        val continuation = pending ?: return false
        if (continuation.isActive) continuation.resume(false)
        return true
    }

    /** Cancels consent when the owning pane is disposed. */
    override fun close() {
        check(SwingUtilities.isEventDispatchThread())
        closed = true
        pending?.cancel(CancellationException("Terminal pane closed"))
    }
}
