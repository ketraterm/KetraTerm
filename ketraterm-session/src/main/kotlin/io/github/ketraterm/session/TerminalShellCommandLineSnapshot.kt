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
 * The default session source extracts bounded text from OSC 133 prompt markers
 * and synchronized render frames, only when the cursor is at the visible end.
 * A host-owned shell editor can instead supply its complete logical command
 * text and an offset anywhere in that text through `shellCommandLineSource`.
 * Hosts own their source's text limits and publish updates when text, cursor,
 * anchor, or availability changes. Snapshots are consumed outside paint loops.
 *
 * @property commandText complete known command-line text, without the prompt.
 * @property cursorOffset UTF-16 cursor offset within [commandText].
 * @property cursorColumn zero-based live terminal-grid cursor column to use as a
 * popup anchor.
 * @property cursorRow zero-based live terminal-grid cursor row to use as a popup
 * anchor.
 */
data class TerminalShellCommandLineSnapshot(
    val commandText: String,
    val cursorOffset: Int,
    val cursorColumn: Int,
    val cursorRow: Int,
) {
    init {
        require(cursorOffset in 0..commandText.length) {
            "cursorOffset must be in 0..${commandText.length}, was $cursorOffset"
        }
        require(cursorColumn >= 0) { "cursorColumn must be >= 0, was $cursorColumn" }
        require(cursorRow >= 0) { "cursorRow must be >= 0, was $cursorRow" }
    }
}
