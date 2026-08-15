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
 * Bounded snapshot of the shell command line currently known to the session.
 *
 * The snapshot is derived from OSC 133 prompt markers and the synchronized
 * render-frame state owned by [TerminalSession]. It represents command text
 * that is safe for host suggestion providers to inspect without scanning
 * persistent history or maintaining their own shadow input buffer.
 *
 * @property commandText visible command-line text captured from the end of the
 * active prompt.
 * @property cursorOffset UTF-16 cursor offset within [commandText].
 * @property cursorColumn zero-based terminal-grid cursor column to use as a
 * popup anchor.
 * @property cursorRow zero-based terminal-grid cursor row to use as a popup
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
