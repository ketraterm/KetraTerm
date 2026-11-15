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

/**
 * Cancellable read consent owned by one terminal pane. All operations belong to
 * the EDT. The product presents its standard dialog and returns a handle that
 * closes it. No clipboard content is passed to the dialog.
 *
 * Cancellation and expiry clean up on the EDT, including for callers using other
 * coroutine dispatchers. Dismissal and pane disposal close the dialog before returning.
 * Blocking persists for this pane's lifetime, including later requests admitted with Allow.
 * @param showDialog opens product UI, delivers a decision on the EDT, and returns its disposal handle.
 * @param messages host message bundle; choices keep their stable decision indices.
 */
public class SwingClipboardReadPrompt(
    private val messages: SwingHostMessages,
    private val showDialog: (request: SwingDialogRequest, decide: (Int?) -> Unit) -> AutoCloseable,
) : AutoCloseable {
    /** Creates a prompt using the JVM display locale and bundled messages. */
    public constructor(
        showDialog: (request: SwingDialogRequest, decide: (Int?) -> Unit) -> AutoCloseable,
    ) : this(SwingHostMessages.forLocale(), showDialog)

    /** Choices shared by both products' standard clipboard dialogs. */
    public enum class Decision {
        ALLOW_ONCE,
        DENY,
        BLOCK,
        ;

        /** Label in the JVM display locale; localized presenters resolve their own bundle. */
        public val label: String get() = SwingHostMessages.forLocale().message(messageKey)

        internal val messageKey: String get() =
            when (this) {
                ALLOW_ONCE -> "clipboard.allowOnce"
                DENY -> "clipboard.deny"
                BLOCK -> "clipboard.block"
            }
    }

    private class PendingRequest(
        val continuation: CancellableContinuation<Boolean>,
    ) {
        var dialog: AutoCloseable? = null
        var finished = false
    }

    private var pending: PendingRequest? = null
    private var blocked = false
    private var closed = false

    /** Whether this pane has been blocked or closed. */
    public val isBlocked: Boolean
        get() {
            check(SwingUtilities.isEventDispatchThread())
            return blocked || closed
        }

    /** Suspends for one decision; concurrent requests cannot replace the current dialog. */
    public suspend fun request(message: String): Boolean {
        check(SwingUtilities.isEventDispatchThread())
        if (isBlocked || pending != null) return false
        var request: PendingRequest? = null
        try {
            return suspendCancellableCoroutine { continuation ->
                val current = PendingRequest(continuation)
                request = current
                pending = current
                val dialog =
                    showDialog(
                        SwingDialogRequest(
                            messages.message("clipboard.title"),
                            message,
                            SwingDialogRequest.Severity.WARNING,
                            Decision.entries.map { messages.message(it.messageKey) },
                            defaultOption = Decision.DENY.ordinal,
                        ),
                    ) { option ->
                        check(SwingUtilities.isEventDispatchThread())
                        if (pending === current && continuation.isActive) {
                            val decision = Decision.entries.getOrNull(option ?: -1) ?: Decision.DENY
                            if (decision == Decision.BLOCK) blocked = true
                            decide(current, decision == Decision.ALLOW_ONCE)
                        }
                    }
                // Presenters may decide or dispose the pane before returning their handle.
                if (current.finished) dialog.close() else current.dialog = dialog
            }
        } finally {
            request?.let { current ->
                if (SwingUtilities.isEventDispatchThread()) {
                    finish(current)
                } else {
                    // Cleanup must complete even when the requesting coroutine is cancelled.
                    suspendCancellableCoroutine { cleanup ->
                        SwingUtilities.invokeLater { cleanup.resumeWith(runCatching { finish(current) }) }
                    }
                }
            }
        }
    }

    /** Denies a pending request, returning whether terminal Escape was consumed. */
    public fun dismiss(): Boolean {
        check(SwingUtilities.isEventDispatchThread())
        val request = pending ?: return false
        decide(request, false)
        return true
    }

    /** Cancels consent when the owning pane is disposed. */
    override fun close() {
        check(SwingUtilities.isEventDispatchThread())
        closed = true
        val request = pending ?: return
        try {
            finish(request)
        } finally {
            request.continuation.cancel(CancellationException("Terminal pane closed"))
        }
    }

    private fun decide(
        request: PendingRequest,
        allowed: Boolean,
    ) {
        val result =
            runCatching {
                finish(request)
                allowed
            }
        if (request.continuation.isActive) {
            request.continuation.resumeWith(result)
        } else {
            result.getOrThrow()
        }
    }

    private fun finish(request: PendingRequest) {
        check(SwingUtilities.isEventDispatchThread())
        if (request.finished) return
        request.finished = true
        if (pending === request) pending = null
        val dialog = request.dialog
        request.dialog = null
        dialog?.close()
    }
}
