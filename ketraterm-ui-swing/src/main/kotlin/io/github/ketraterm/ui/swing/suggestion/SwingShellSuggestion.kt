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
package io.github.ketraterm.ui.swing.suggestion

/**
 * Host-provided shell suggestion shown by the reusable Swing terminal popup.
 *
 * The Swing layer only presents and selects suggestions. It does not decide how
 * accepted text should replace the command line because shell editing semantics
 * belong to the host/provider that produced the suggestion.
 *
 * @property replacementText text the provider intends to insert or use for
 * command-line replacement after acceptance.
 * @property displayText primary text shown in the popup.
 * @property detail secondary text shown below [displayText], such as flags,
 * path context, or a short description.
 * @property source compact source label, such as `history`, `path`, or `git`.
 */
data class SwingShellSuggestion
    @JvmOverloads
    constructor(
        val replacementText: String,
        val displayText: String = replacementText,
        val detail: String = "",
        val source: String = "",
    ) {
        init {
            require(replacementText.isNotEmpty()) { "replacementText must not be empty" }
            require(displayText.isNotEmpty()) { "displayText must not be empty" }
        }
    }

/**
 * Snapshot of the currently visible shell suggestion popup state.
 *
 * @property visible whether the popup is currently visible.
 * @property count number of retained suggestions.
 * @property selectedIndex selected suggestion index, or `-1` when none.
 * @property anchorColumn terminal-grid column used as the popup anchor.
 * @property anchorRow terminal-grid row used as the popup anchor.
 * @property selectedSuggestion selected suggestion, or `null` when no
 * suggestion is selected.
 */
data class SwingShellSuggestionState(
    val visible: Boolean,
    val count: Int,
    val selectedIndex: Int,
    val anchorColumn: Int,
    val anchorRow: Int,
    val selectedSuggestion: SwingShellSuggestion?,
) {
    companion object {
        /**
         * Empty state used when the popup is hidden.
         */
        @JvmField
        val EMPTY: SwingShellSuggestionState =
            SwingShellSuggestionState(
                visible = false,
                count = 0,
                selectedIndex = -1,
                anchorColumn = 0,
                anchorRow = 0,
                selectedSuggestion = null,
            )
    }
}

/**
 * Host callback invoked when the user accepts a shell suggestion.
 */
fun interface SwingShellSuggestionHandler {
    /**
     * Handles an accepted suggestion.
     *
     * @param suggestion accepted suggestion.
     * @param index index of [suggestion] in the currently displayed list.
     */
    fun onSuggestionAccepted(
        suggestion: SwingShellSuggestion,
        index: Int,
    )

    companion object {
        /**
         * Handler that ignores accepted suggestions.
         */
        @JvmField
        val NONE: SwingShellSuggestionHandler = SwingShellSuggestionHandler { _, _ -> }
    }
}
