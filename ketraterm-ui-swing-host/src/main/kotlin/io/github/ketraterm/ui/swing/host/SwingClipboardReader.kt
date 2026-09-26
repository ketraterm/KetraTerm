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

import io.github.ketraterm.host.TerminalClipboardPermission
import io.github.ketraterm.host.TerminalClipboardReadRequest
import io.github.ketraterm.protocol.TerminalClipboardSelection
import io.github.ketraterm.session.TerminalClipboardReadResult
import io.github.ketraterm.ui.swing.settings.TerminalClipboardHandler
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities

/**
 * Window-owned clipboard reader: at most one consent prompt per window and one
 * native read across all instances. Busy requests complete without queuing.
 *
 * The host must enter the EDT after earlier posted writes and resolve the prompt
 * from the requesting session's pane. The session owns the request deadline and
 * cancellation. A blocked native call retains its slot until it actually returns.
 *
 * @param clipboard host clipboard implementation; called off the EDT.
 * @param ioDispatcher dispatcher for potentially blocking native clipboard access.
 */
class SwingClipboardReader(
    private val clipboard: TerminalClipboardHandler,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private var prompting = false

    /**
     * Reads with the admitted permission and the pane's persistent block choice.
     * Call on the EDT with this request's product-owned [message]; no clipboard
     * access occurs before consent. Cancellation and provider failures propagate.
     */
    suspend fun read(
        request: TerminalClipboardReadRequest,
        prompt: SwingClipboardReadPrompt,
        message: String,
    ): TerminalClipboardReadResult {
        check(SwingUtilities.isEventDispatchThread())
        currentCoroutineContext().ensureActive()
        if (prompt.isBlocked || request.permission == TerminalClipboardPermission.DENY) return TerminalClipboardReadResult.Denied
        if (request.permission == TerminalClipboardPermission.PROMPT) {
            if (prompting) return TerminalClipboardReadResult.Denied
            prompting = true
            try {
                if (!prompt.request(message)) return TerminalClipboardReadResult.Denied
            } finally {
                prompting = false
            }
        }
        if (prompt.isBlocked) return TerminalClipboardReadResult.Denied
        return withContext(ioDispatcher) { readSelection(request.selection) }
    }

    private suspend fun readSelection(selection: TerminalClipboardSelection): TerminalClipboardReadResult {
        check(!SwingUtilities.isEventDispatchThread())
        currentCoroutineContext().ensureActive()
        if (!nativeReadActive.compareAndSet(false, true)) return TerminalClipboardReadResult.Unavailable
        try {
            var clipboardTried = false
            for (selector in selection.value) {
                currentCoroutineContext().ensureActive()
                val text =
                    when (selector) {
                        'c', 's' -> {
                            if (clipboardTried) continue
                            clipboardTried = true
                            clipboard.readText()
                        }
                        'p' -> clipboard.readPrimarySelectionText()
                        else -> continue
                    }
                currentCoroutineContext().ensureActive()
                if (text != null) return TerminalClipboardReadResult.Text(text)
            }
            return TerminalClipboardReadResult.Unavailable
        } finally {
            nativeReadActive.set(false)
        }
    }

    private companion object {
        val nativeReadActive = AtomicBoolean()
    }
}
