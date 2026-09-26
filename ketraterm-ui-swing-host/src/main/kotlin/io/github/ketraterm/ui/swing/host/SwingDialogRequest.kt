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

/**
 * Plain-text content and choices for a standard product dialog. Both presenters
 * return the chosen option index, or null for Escape, window close or disposal.
 * Options are copied so a caller cannot change their meaning while UI is open.
 *
 * @param defaultOption initially selected action; defaults to the last option,
 * conventionally Cancel/Deny. This is independent of dismissing the dialog.
 */
class SwingDialogRequest(
    val title: String,
    val message: String,
    val severity: Severity,
    options: List<String> = listOf("OK"),
    val defaultOption: Int = options.lastIndex,
) {
    /** Standard platform message icon. */
    enum class Severity { INFORMATION, WARNING, ERROR }

    /** Button labels in result-index order. */
    val options: List<String> = options.toList()

    init {
        require(title.isNotBlank()) { "Dialog title must not be blank" }
        require(this.options.isNotEmpty() && this.options.all { it.isNotBlank() }) { "Dialog options must not be empty or blank" }
        require(this.options.distinct().size == this.options.size) { "Dialog options must be distinct" }
        require(defaultOption in this.options.indices) { "Default option must identify a dialog action" }
    }

    /** Wrapped, escaped content for platform HTML labels; messages never supply markup. */
    fun htmlMessage(): String =
        buildString {
            append("<html><body style='width: 340px'>")
            for (char in message) {
                append(
                    when (char) {
                        '&' -> "&amp;"
                        '<' -> "&lt;"
                        '>' -> "&gt;"
                        '"' -> "&quot;"
                        '\'' -> "&#39;"
                        '\n' -> "<br>"
                        else -> char
                    },
                )
            }
            append("</body></html>")
        }
}
