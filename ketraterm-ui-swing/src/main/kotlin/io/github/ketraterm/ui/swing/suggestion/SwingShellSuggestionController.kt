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

import io.github.ketraterm.ui.swing.settings.SwingSettings
import java.awt.event.KeyEvent

internal class SwingShellSuggestionController(
    private val host: SwingShellSuggestionHost,
) {
    // TODO(completion-feedback): Distinguish passive popup closure reasons
    // such as typing, focus loss, provider refresh, and settings changes from
    // explicit Escape dismissal before feeding negative ranking signals.
    private var suggestions: List<SwingShellSuggestion> = emptyList()
    private var selectedIndex: Int = NO_SELECTION
    private var request: SwingShellSuggestionRequest = SwingShellSuggestionRequest.EMPTY

    val popup: SwingShellSuggestionPopup =
        SwingShellSuggestionPopup(
            object : SwingShellSuggestionPopupListener {
                override fun onSuggestionHovered(index: Int) {
                    select(index)
                }

                override fun onSuggestionClicked(index: Int) {
                    select(index)
                    acceptSelected()
                }
            },
        )

    fun show(
        request: SwingShellSuggestionRequest,
        suggestions: List<SwingShellSuggestion>,
        selectedIndex: Int,
    ): Boolean {
        if (!host.settings.shellSuggestionsEnabled || suggestions.isEmpty()) {
            hide()
            return false
        }
        this.suggestions = retainVisibleSuggestions(suggestions)
        this.request = request
        this.selectedIndex = selectedIndex.coerceIn(0, this.suggestions.lastIndex)
        popup.update(this.suggestions, this.selectedIndex)
        popup.isVisible = true
        host.revalidate()
        host.repaint()
        return true
    }

    fun hide(): Boolean {
        if (!popup.isVisible && suggestions.isEmpty()) return false
        suggestions = emptyList()
        selectedIndex = NO_SELECTION
        request = SwingShellSuggestionRequest.EMPTY
        popup.update(suggestions, selectedIndex)
        popup.isVisible = false
        host.revalidate()
        host.repaint()
        return true
    }

    fun reloadSettings() {
        if (!host.settings.shellSuggestionsEnabled) {
            hide()
        }
    }

    fun handleKeyPressed(event: KeyEvent): Boolean {
        if (!popup.isVisible || suggestions.isEmpty()) return false
        val action = host.suggestionKeymap.actionFor(event) ?: return false
        val handled = handleAction(action)
        if (handled) event.consume()
        return handled
    }

    private fun handleAction(action: SwingShellSuggestionAction): Boolean =
        when (action) {
            SwingShellSuggestionAction.SELECT_NEXT -> selectRelative(1)
            SwingShellSuggestionAction.SELECT_PREVIOUS -> selectRelative(-1)
            SwingShellSuggestionAction.SELECT_FIRST -> select(0)
            SwingShellSuggestionAction.SELECT_LAST -> select(suggestions.lastIndex)
            SwingShellSuggestionAction.SELECT_NEXT_PAGE -> selectRelative(PAGE_STEP)
            SwingShellSuggestionAction.SELECT_PREVIOUS_PAGE -> selectRelative(-PAGE_STEP)
            SwingShellSuggestionAction.ACCEPT -> acceptSelected()
            SwingShellSuggestionAction.DISMISS -> dismissSelected()
        }

    fun state(): SwingShellSuggestionState =
        if (!popup.isVisible || suggestions.isEmpty()) {
            SwingShellSuggestionState.EMPTY
        } else {
            SwingShellSuggestionState(
                visible = true,
                count = suggestions.size,
                selectedIndex = selectedIndex,
                anchorColumn = request.anchorColumn,
                anchorRow = request.anchorRow,
                selectedSuggestion = suggestions[selectedIndex],
            )
        }

    private fun selectRelative(delta: Int): Boolean {
        if (suggestions.isEmpty()) return false
        val current = if (selectedIndex in suggestions.indices) selectedIndex else 0
        val next = (current + delta).coerceIn(0, suggestions.lastIndex)
        return select(next)
    }

    private fun select(index: Int): Boolean {
        if (index !in suggestions.indices) return false
        if (selectedIndex == index) return true
        selectedIndex = index
        popup.update(suggestions, selectedIndex)
        host.repaint()
        return true
    }

    private fun acceptSelected(): Boolean {
        if (selectedIndex !in suggestions.indices) return false
        val suggestion = suggestions[selectedIndex]
        val index = selectedIndex
        val acceptedRequest = request
        hide()
        host.suggestionFeedbackHandler.onSuggestionFeedback(
            SwingShellSuggestionFeedback(
                kind = SwingShellSuggestionFeedbackKind.ACCEPTED,
                suggestion = suggestion,
                index = index,
                request = acceptedRequest,
            ),
        )
        host.suggestionHandler.onSuggestionAccepted(
            SwingShellSuggestionAcceptance(
                suggestion = suggestion,
                index = index,
                request = acceptedRequest,
            ),
        )
        host.requestFocusInWindow()
        return true
    }

    private fun dismissSelected(): Boolean {
        if (selectedIndex !in suggestions.indices) return hide()
        val suggestion = suggestions[selectedIndex]
        val index = selectedIndex
        val dismissedRequest = request
        hide()
        host.suggestionFeedbackHandler.onSuggestionFeedback(
            SwingShellSuggestionFeedback(
                kind = SwingShellSuggestionFeedbackKind.DISMISSED,
                suggestion = suggestion,
                index = index,
                request = dismissedRequest,
            ),
        )
        host.requestFocusInWindow()
        return true
    }

    private fun retainVisibleSuggestions(suggestions: List<SwingShellSuggestion>): List<SwingShellSuggestion> =
        if (suggestions.size <=
            MAX_RETAINED_SUGGESTIONS
        ) {
            suggestions.toList()
        } else {
            suggestions.subList(0, MAX_RETAINED_SUGGESTIONS).toList()
        }

    private companion object {
        private const val NO_SELECTION = -1
        private const val PAGE_STEP = 5
        private const val MAX_RETAINED_SUGGESTIONS = 8
    }
}

internal interface SwingShellSuggestionHost {
    val settings: SwingSettings
    val suggestionKeymap: SwingShellSuggestionKeymap
    val suggestionHandler: SwingShellSuggestionHandler
    val suggestionFeedbackHandler: SwingShellSuggestionFeedbackHandler

    fun revalidate()

    fun repaint()

    fun requestFocusInWindow(): Boolean
}
