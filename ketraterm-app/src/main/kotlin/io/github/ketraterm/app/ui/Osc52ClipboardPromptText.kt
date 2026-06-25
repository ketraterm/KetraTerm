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

import io.github.ketraterm.host.TerminalClipboardOrigin
import io.github.ketraterm.host.TerminalClipboardPromptEvent

internal object Osc52ClipboardPromptText {
    fun title(): String = "Clipboard Access"

    fun question(
        profileName: String,
        event: TerminalClipboardPromptEvent,
    ): String {
        val applicationName = profileName.trim().ifBlank { "this terminal" }
        if (event.text.isEmpty()) {
            return "Allow $applicationName to clear the clipboard?"
        }
        val count = event.text.codePointCount(0, event.text.length)
        return "Allow $applicationName to write ${count.formatCount("character")} to the clipboard?"
    }

    fun detail(event: TerminalClipboardPromptEvent): String =
        when (event.audit.origin) {
            TerminalClipboardOrigin.LOCAL -> "Local terminal session"
            TerminalClipboardOrigin.REMOTE -> "Remote terminal session"
        }

    fun plainMessage(
        profileName: String,
        event: TerminalClipboardPromptEvent,
    ): String = question(profileName, event) + "\n\n" + detail(event)

    fun htmlQuestion(
        profileName: String,
        event: TerminalClipboardPromptEvent,
    ): String = "<html><body style='width: 340px'><b>${escapeHtml(question(profileName, event))}</b></body></html>"

    fun htmlDetail(event: TerminalClipboardPromptEvent): String =
        "<html><body style='width: 340px'>${escapeHtml(detail(event))}</body></html>"

    private fun Int.formatCount(unit: String): String =
        if (this == 1) {
            "1 $unit"
        } else {
            "$this ${unit}s"
        }

    private fun escapeHtml(value: String): String {
        val builder = StringBuilder(value.length)
        for (ch in value) {
            when (ch) {
                '&' -> builder.append("&amp;")
                '<' -> builder.append("&lt;")
                '>' -> builder.append("&gt;")
                '"' -> builder.append("&quot;")
                '\'' -> builder.append("&#39;")
                else -> builder.append(ch)
            }
        }
        return builder.toString()
    }
}
