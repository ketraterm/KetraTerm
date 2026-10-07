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
public object SwingClipboardPrompts {
    /** English title retained for callers using the original constant; localized dialogs resolve `clipboard.title`. */
    public const val TITLE: String = "Clipboard Access"

    /** Read consent never contains clipboard contents or a claimed process origin. */
    public fun readQuestion(
        profileName: String,
        clipboardName: String = SwingHostMessages.forLocale().message("clipboard.clipboardName"),
    ): String = readQuestion(profileName, clipboardName, SwingHostMessages.forLocale())

    /** Localized read warning; a missing [clipboardName] uses the provider's host clipboard name. */
    public fun readQuestion(
        profileName: String,
        clipboardName: String? = null,
        messages: SwingHostMessages,
    ): String =
        messages.message(
            "clipboard.readQuestion",
            profileName.trim().ifBlank { messages.message("clipboard.terminalFallback") },
            clipboardName ?: messages.message("clipboard.clipboardName"),
        )

    /** Write consent describes clear/count semantics without disclosing the payload. */
    public fun writeQuestion(
        profileName: String,
        text: String,
        clipboardName: String = SwingHostMessages.forLocale().message("clipboard.clipboardName"),
    ): String = writeQuestion(profileName, text, clipboardName, SwingHostMessages.forLocale())

    /** Localized write warning; only a Unicode code-point count is passed to [messages]. */
    public fun writeQuestion(
        profileName: String,
        text: String,
        clipboardName: String? = null,
        messages: SwingHostMessages,
    ): String {
        val terminalName = profileName.trim().ifBlank { messages.message("clipboard.terminalFallback") }
        val clipboard = clipboardName ?: messages.message("clipboard.clipboardName")
        if (text.isEmpty()) {
            return messages.message("clipboard.clearQuestion", terminalName, clipboard)
        }
        val count = text.codePointCount(0, text.length)
        return messages.message("clipboard.writeQuestion", terminalName, clipboard, count)
    }

    /** Shared write choices and safe initial action for both product presenters. */
    public fun writeConfirmation(
        profileName: String,
        text: String,
        clipboardName: String = SwingHostMessages.forLocale().message("clipboard.clipboardName"),
    ): SwingDialogRequest = writeConfirmation(profileName, text, clipboardName, SwingHostMessages.forLocale())

    /** Localized write choices retain Allow/Deny indices and the safe initial Deny action. */
    public fun writeConfirmation(
        profileName: String,
        text: String,
        clipboardName: String? = null,
        messages: SwingHostMessages,
    ): SwingDialogRequest =
        SwingDialogRequest(
            messages.message("clipboard.title"),
            writeQuestion(profileName, text, clipboardName, messages),
            SwingDialogRequest.Severity.WARNING,
            listOf(messages.message("clipboard.allowOnce"), messages.message("clipboard.deny")),
        )
}
