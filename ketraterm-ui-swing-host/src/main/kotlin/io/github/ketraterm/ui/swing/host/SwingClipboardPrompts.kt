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

/** Shared clipboard consent wording; terminal names are treated as untrusted text. */
object SwingClipboardPrompts {
    /** Title used by both products for reads and writes. */
    const val TITLE: String = "Clipboard Access"

    /** Read consent never contains clipboard contents or a claimed process origin. */
    fun readQuestion(
        profileName: String,
        clipboardName: String = "clipboard",
    ): String {
        val terminalName = profileName.trim().ifBlank { "this terminal" }
        return "Allow an application in $terminalName to read your $clipboardName? " +
            "Only allow applications you trust. This request expires automatically."
    }

    /** Write consent describes clear/count semantics without disclosing the payload. */
    fun writeQuestion(
        profileName: String,
        text: String,
        clipboardName: String = "clipboard",
    ): String {
        val terminalName = profileName.trim().ifBlank { "this terminal" }
        if (text.isEmpty()) {
            return "Allow an application in $terminalName to clear the $clipboardName?"
        }
        val count = text.codePointCount(0, text.length)
        val characterCount = if (count == 1) "1 character" else "$count characters"
        return "Allow an application in $terminalName to write $characterCount to the $clipboardName?"
    }

    /** Shared write choices and safe initial action for both product presenters. */
    fun writeConfirmation(
        profileName: String,
        text: String,
        clipboardName: String = "clipboard",
    ): SwingDialogRequest =
        SwingDialogRequest(
            TITLE,
            writeQuestion(profileName, text, clipboardName),
            SwingDialogRequest.Severity.WARNING,
            listOf(SwingClipboardReadPrompt.Decision.ALLOW_ONCE.label, SwingClipboardReadPrompt.Decision.DENY.label),
        )
}
