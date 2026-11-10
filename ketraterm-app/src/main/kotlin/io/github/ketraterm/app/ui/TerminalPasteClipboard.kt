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
package io.github.ketraterm.app.ui

import io.github.ketraterm.app.appMessages
import io.github.ketraterm.ui.swing.host.SwingDialogRequest
import io.github.ketraterm.ui.swing.settings.TerminalClipboardHandler

/** Pane-owned review of explicit user paste; OSC clipboard access uses its separate permissions. */
internal class TerminalPasteClipboard(
    private val clipboard: TerminalClipboardHandler,
    private val confirm: (SwingDialogRequest) -> Boolean,
) : TerminalClipboardHandler by clipboard {
    private var prompting = false

    override fun readText(): String? = read(clipboard::readText)

    override fun readPrimarySelectionText(): String? = read(clipboard::readPrimarySelectionText)

    private fun read(load: () -> String?): String? {
        if (prompting) return null
        val text = load() ?: return null
        if (text.none { it != '\t' && (it.isISOControl() || it == '\u2028' || it == '\u2029') }) return text
        prompting = true
        try {
            val request =
                SwingDialogRequest(
                    title = appMessages.text("paste.review.title"),
                    message = appMessages.text("paste.review.message", preview(text)),
                    severity = SwingDialogRequest.Severity.WARNING,
                    options = listOf(appMessages.text("paste.review.confirm"), appMessages.text("button.cancel")),
                )
            return text.takeIf { confirm(request) }
        } finally {
            prompting = false
        }
    }

    private fun preview(text: String): String =
        buildString {
            var offset = 0
            var shown = 0
            while (offset < text.length && shown < 400) {
                val codepoint = text.codePointAt(offset)
                val escaped =
                    when {
                        codepoint == '\n'.code -> "\\n"
                        codepoint == '\r'.code -> "\\r"
                        Character.isISOControl(codepoint) ||
                            codepoint in 0x2028..0x202e ||
                            codepoint in 0x2066..0x2069 ||
                            codepoint in 0x200e..0x200f ->
                            "\\u" + codepoint.toString(16).padStart(4, '0')
                        else -> null
                    }
                if (length + (escaped?.length ?: Character.charCount(codepoint)) > 800) break
                if (escaped != null) append(escaped) else appendCodePoint(codepoint)
                offset += Character.charCount(codepoint)
                shown++
            }
            if (offset < text.length) append('…')
        }
}
