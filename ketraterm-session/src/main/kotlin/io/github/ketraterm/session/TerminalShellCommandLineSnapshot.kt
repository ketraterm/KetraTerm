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

/**
 * Immutable snapshot of the shell command line currently known to the session.
 *
 * The optional OSC integration extracts bounded text from prompt markers
 * and synchronized render frames. When visible text after the cursor could be
 * a shell prediction, it may expose the prompt-to-cursor prefix with
 * [requiresShellConfirmation]. Consumers must confirm that prefix against the
 * native editor before publishing suggestions or submitting edits.
 * A host-owned shell editor can instead supply its complete logical command
 * text and an offset anywhere in that text through [TerminalShellIntegrationFactory.host].
 * Hosts own their source's text limits and publish updates when text, cursor,
 * anchor, or availability changes. Snapshots are consumed outside paint loops.
 *
 * @property commandText command-line text without the prompt; a provisional
 * prefix when [requiresShellConfirmation] is true.
 * @property cursorOffset UTF-16 cursor offset within [commandText].
 * @property cursorColumn zero-based live terminal-grid cursor column to use as a
 * popup anchor.
 * @property cursorRow zero-based live terminal-grid cursor row to use as a popup
 * anchor.
 * @property requiresShellConfirmation whether the text needs an exact native
 * editor-buffer match through [TerminalSession.requestShellCompletions]. Until
 * that succeeds, conditional edit admission rejects this snapshot. Confirmation
 * expires on subsequent input, output, resize, or producer changes.
 */
public data class TerminalShellCommandLineSnapshot
    @JvmOverloads
    constructor(
        val commandText: String,
        val cursorOffset: Int,
        val cursorColumn: Int,
        val cursorRow: Int,
        val requiresShellConfirmation: Boolean = false,
    ) {
        init {
            require(cursorOffset in 0..commandText.length) {
                "cursorOffset must be in 0..${commandText.length}, was $cursorOffset"
            }
            require(cursorColumn >= 0) { "cursorColumn must be >= 0, was $cursorColumn" }
            require(cursorRow >= 0) { "cursorRow must be >= 0, was $cursorRow" }
        }

        /** Preserves the original JVM copy entry points without dropping shell-confirmation requirements. */
        public fun copy(
            commandText: String = this.commandText,
            cursorOffset: Int = this.cursorOffset,
            cursorColumn: Int = this.cursorColumn,
            cursorRow: Int = this.cursorRow,
        ): TerminalShellCommandLineSnapshot =
            TerminalShellCommandLineSnapshot(commandText, cursorOffset, cursorColumn, cursorRow, requiresShellConfirmation)
    }
