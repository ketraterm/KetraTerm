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
    viewFactory: SwingShellSuggestionViewFactory = SwingShellSuggestionViewFactory.DEFAULT,
) {
    private var suggestions: List<SwingShellSuggestion> = emptyList()
    private var selectedIndex: Int = NO_SELECTION
    private var viewportStartIndex: Int = 0
    private var request: SwingShellSuggestionRequest = SwingShellSuggestionRequest.EMPTY

    private val view: SwingShellSuggestionView =
        viewFactory.create(
            object : SwingShellSuggestionViewListener {
                override fun onSuggestionHovered(index: Int) {
                    select(viewportStartIndex + index)
                }

                override fun onSuggestionClicked(index: Int) {
                    select(viewportStartIndex + index)
                    acceptSelected()
                }
            },
        )

    val popup get() = view.component

    fun show(
        request: SwingShellSuggestionRequest,
        suggestions: List<SwingShellSuggestion>,
        selectedIndex: Int,
    ): Boolean {
        if (suggestions.isEmpty()) {
            hide()
            return false
        }
        val selectedOutcome =
            this.suggestions
                .getOrNull(this.selectedIndex)
                ?.outcomeKey()
                ?.takeIf { this.request == request }
        this.suggestions = suggestions.toList()
        this.request = request
        this.selectedIndex =
            selectedIndex.takeIf { it in this.suggestions.indices }
                ?: selectedOutcome
                    ?.let { outcome -> this.suggestions.indexOfFirst { it.outcomeKey() == outcome } }
                    ?.takeIf { it >= 0 }
                ?: NO_SELECTION
        updateViewport()
        view.component.isVisible = true
        host.revalidate()
        host.repaint()
        return true
    }

    fun hide(): Boolean {
        if (!view.component.isVisible && suggestions.isEmpty()) return false
        suggestions = emptyList()
        selectedIndex = NO_SELECTION
        viewportStartIndex = 0
        request = SwingShellSuggestionRequest.EMPTY
        view.update(emptyList(), NO_SELECTION)
        view.component.isVisible = false
        host.revalidate()
        host.repaint()
        return true
    }

    fun close() {
        hide()
        view.close()
    }

    fun handleKeyPressed(event: KeyEvent): Boolean {
        if (!view.component.isVisible || suggestions.isEmpty()) return false
        val action = host.suggestionKeymap.actionFor(event) ?: return false
        val enterAcceptanceDisabled =
            action == SwingShellSuggestionAction.ACCEPT_SELECTED &&
                event.keyCode == KeyEvent.VK_ENTER &&
                !host.settings.acceptSelectedSuggestionWithEnter
        val handled = !enterAcceptanceDisabled && handleAction(action)
        if (handled) event.consume()
        return handled
    }

    private fun handleAction(action: SwingShellSuggestionAction): Boolean =
        when (action) {
            SwingShellSuggestionAction.SELECT_NEXT -> selectRelative(1)
            SwingShellSuggestionAction.SELECT_PREVIOUS -> selectRelative(-1)
            SwingShellSuggestionAction.SELECT_FIRST -> select(0)
            SwingShellSuggestionAction.SELECT_LAST -> select(suggestions.lastIndex)
            SwingShellSuggestionAction.SELECT_NEXT_PAGE -> selectRelative(POPUP_MAX_VISIBLE_ROWS)
            SwingShellSuggestionAction.SELECT_PREVIOUS_PAGE -> selectRelative(-POPUP_MAX_VISIBLE_ROWS)
            SwingShellSuggestionAction.ACCEPT -> selectFirstOrAccept()
            SwingShellSuggestionAction.ACCEPT_SELECTED -> acceptSelected()
            SwingShellSuggestionAction.DISMISS -> dismissSelected()
        }

    fun state(): SwingShellSuggestionState =
        if (!view.component.isVisible || suggestions.isEmpty()) {
            SwingShellSuggestionState.EMPTY
        } else {
            SwingShellSuggestionState(
                visible = true,
                count = suggestions.size,
                selectedIndex = selectedIndex,
                anchorColumn = request.anchorColumn,
                anchorRow = request.anchorRow,
                selectedSuggestion = suggestions.getOrNull(selectedIndex),
            )
        }

    private fun selectRelative(delta: Int): Boolean {
        if (suggestions.isEmpty()) return false
        val current =
            when {
                selectedIndex in suggestions.indices -> selectedIndex
                delta > 0 -> -1
                else -> suggestions.size
            }
        val next = (current + delta).coerceIn(0, suggestions.lastIndex)
        return select(next)
    }

    private fun selectFirstOrAccept(): Boolean =
        if (selectedIndex in suggestions.indices) {
            acceptSelected()
        } else {
            select(0)
        }

    private fun select(index: Int): Boolean {
        if (index !in suggestions.indices) return false
        if (selectedIndex == index) return true
        selectedIndex = index
        updateViewport()
        host.repaint()
        return true
    }

    private fun acceptSelected(): Boolean {
        if (selectedIndex !in suggestions.indices) return false
        val suggestion = suggestions[selectedIndex]
        val index = selectedIndex
        val acceptedRequest = request
        hide()
        host.invalidateSuggestions()
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
        host.invalidateSuggestions()
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

    private fun updateViewport() {
        viewportStartIndex =
            when {
                suggestions.size <= POPUP_MAX_VISIBLE_ROWS -> 0
                selectedIndex < 0 -> viewportStartIndex.coerceIn(0, suggestions.size - POPUP_MAX_VISIBLE_ROWS)
                selectedIndex < viewportStartIndex -> selectedIndex
                selectedIndex >= viewportStartIndex + POPUP_MAX_VISIBLE_ROWS ->
                    selectedIndex - POPUP_MAX_VISIBLE_ROWS + 1
                else -> viewportStartIndex
            }
        val viewportEnd = minOf(suggestions.size, viewportStartIndex + POPUP_MAX_VISIBLE_ROWS)
        val visible = suggestions.subList(viewportStartIndex, viewportEnd)
        val localSelection = selectedIndex.takeIf { it in viewportStartIndex until viewportEnd }?.minus(viewportStartIndex) ?: NO_SELECTION
        view.update(visible, localSelection)
        host.revalidate()
        host.repaint()
    }

    private fun SwingShellSuggestion.outcomeKey(): SuggestionOutcomeKey =
        SuggestionOutcomeKey(replacementStartOffset, replacementEndOffset, replacementText)

    private companion object {
        private const val NO_SELECTION = -1
    }

    private data class SuggestionOutcomeKey(
        val replacementStartOffset: Int,
        val replacementEndOffset: Int,
        val replacementText: String,
    )
}

internal interface SwingShellSuggestionHost {
    val settings: SwingSettings
    val suggestionKeymap: SwingShellSuggestionKeymap
    val suggestionHandler: SwingShellSuggestionHandler
    val suggestionFeedbackHandler: SwingShellSuggestionFeedbackHandler

    fun revalidate()

    fun repaint()

    fun requestFocusInWindow(): Boolean

    fun invalidateSuggestions()
}
