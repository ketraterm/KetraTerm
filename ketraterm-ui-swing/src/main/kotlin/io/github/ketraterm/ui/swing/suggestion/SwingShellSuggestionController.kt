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
    private var suggestions: List<SwingShellSuggestion> = emptyList()
    private var selectedIndex: Int = NO_SELECTION
    private var anchorColumn: Int = 0
    private var anchorRow: Int = 0

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
        suggestions: List<SwingShellSuggestion>,
        anchorColumn: Int,
        anchorRow: Int,
        selectedIndex: Int,
    ): Boolean {
        if (!host.settings.shellSuggestionsEnabled || suggestions.isEmpty()) {
            hide()
            return false
        }
        this.suggestions = suggestions
        this.anchorColumn = anchorColumn.coerceAtLeast(0)
        this.anchorRow = anchorRow.coerceAtLeast(0)
        this.selectedIndex = selectedIndex.coerceIn(0, suggestions.lastIndex)
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
        val handled =
            when (event.keyCode) {
                KeyEvent.VK_DOWN -> selectRelative(1)
                KeyEvent.VK_UP -> selectRelative(-1)
                KeyEvent.VK_HOME -> select(0)
                KeyEvent.VK_END -> select(suggestions.lastIndex)
                KeyEvent.VK_PAGE_DOWN -> selectRelative(PAGE_STEP)
                KeyEvent.VK_PAGE_UP -> selectRelative(-PAGE_STEP)
                KeyEvent.VK_ENTER, KeyEvent.VK_TAB -> acceptSelected()
                KeyEvent.VK_ESCAPE -> hide()
                else -> false
            }
        if (handled) event.consume()
        return handled
    }

    fun state(): SwingShellSuggestionState =
        if (!popup.isVisible || suggestions.isEmpty()) {
            SwingShellSuggestionState.EMPTY
        } else {
            SwingShellSuggestionState(
                visible = true,
                count = suggestions.size,
                selectedIndex = selectedIndex,
                anchorColumn = anchorColumn,
                anchorRow = anchorRow,
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
        hide()
        host.suggestionHandler.onSuggestionAccepted(suggestion, index)
        host.requestFocusInWindow()
        return true
    }

    private companion object {
        private const val NO_SELECTION = -1
        private const val PAGE_STEP = 5
    }
}

internal interface SwingShellSuggestionHost {
    val settings: SwingSettings
    val suggestionHandler: SwingShellSuggestionHandler

    fun revalidate()

    fun repaint()

    fun requestFocusInWindow(): Boolean
}
