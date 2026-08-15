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
package io.github.ketraterm.input.event

/**
 * One logical terminal text replacement around the active cursor.
 *
 * Counts are terminal editor actions, not UTF-16 or code-point counts. The UI
 * that owns the edited text must translate its text model into the number of
 * Delete and Backspace actions required by the host editor.
 *
 * @property deleteAfterCursorCount Delete key presses emitted before any
 * Backspace presses.
 * @property deleteBeforeCursorCount Backspace key presses emitted after the
 * suffix has been deleted.
 * @property replacementText text pasted after both deletion phases; an empty
 * value represents deletion without insertion.
 */
data class TerminalTextReplacementEvent(
    val deleteAfterCursorCount: Int,
    val deleteBeforeCursorCount: Int,
    val replacementText: String,
) {
    init {
        require(deleteAfterCursorCount >= 0) {
            "deleteAfterCursorCount must be >= 0, was $deleteAfterCursorCount"
        }
        require(deleteBeforeCursorCount >= 0) {
            "deleteBeforeCursorCount must be >= 0, was $deleteBeforeCursorCount"
        }
    }
}
