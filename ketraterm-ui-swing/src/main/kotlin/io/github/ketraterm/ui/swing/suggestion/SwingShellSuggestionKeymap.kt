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

import java.awt.event.InputEvent
import java.awt.event.KeyEvent

private const val RELEVANT_MODIFIERS =
    InputEvent.SHIFT_DOWN_MASK or
        InputEvent.CTRL_DOWN_MASK or
        InputEvent.META_DOWN_MASK or
        InputEvent.ALT_DOWN_MASK or
        InputEvent.ALT_GRAPH_DOWN_MASK

/** Semantic action understood by the reusable shell-suggestion controller. */
enum class SwingShellSuggestionAction {
    /** Selects the next visible suggestion. */
    SELECT_NEXT,

    /** Selects the previous visible suggestion. */
    SELECT_PREVIOUS,

    /** Selects the first visible suggestion. */
    SELECT_FIRST,

    /** Selects the last visible suggestion. */
    SELECT_LAST,

    /** Moves selection toward later suggestions by one visible page. */
    SELECT_NEXT_PAGE,

    /** Moves selection toward earlier suggestions by one visible page. */
    SELECT_PREVIOUS_PAGE,

    /** Accepts the selected suggestion. */
    ACCEPT,

    /** Explicitly dismisses the selected suggestion. */
    DISMISS,
}

/**
 * Host-owned mapping from Swing key presses to semantic suggestion actions.
 *
 * The reusable popup owns action execution and state. Hosts own bindings so a
 * standalone application can use conventional Swing defaults while an IDE can
 * honor its active, user-configurable keymap.
 */
fun interface SwingShellSuggestionKeymap {
    /**
     * Resolves one key press while the suggestion popup is visible.
     *
     * @param event Swing key-pressed event.
     * @return semantic action, or `null` when the key is not claimed.
     */
    fun actionFor(event: KeyEvent): SwingShellSuggestionAction?

    companion object {
        /** Conventional terminal-popup bindings used by standalone Swing hosts. */
        @JvmField
        val STANDARD: SwingShellSuggestionKeymap =
            SwingShellSuggestionKeymap { event ->
                val modifiers = event.modifiersEx and RELEVANT_MODIFIERS
                if (event.keyCode == KeyEvent.VK_TAB && modifiers == InputEvent.SHIFT_DOWN_MASK) {
                    return@SwingShellSuggestionKeymap SwingShellSuggestionAction.SELECT_PREVIOUS
                }
                if (modifiers != 0) return@SwingShellSuggestionKeymap null
                when (event.keyCode) {
                    KeyEvent.VK_DOWN -> SwingShellSuggestionAction.SELECT_NEXT
                    KeyEvent.VK_UP -> SwingShellSuggestionAction.SELECT_PREVIOUS
                    KeyEvent.VK_HOME -> SwingShellSuggestionAction.SELECT_FIRST
                    KeyEvent.VK_END -> SwingShellSuggestionAction.SELECT_LAST
                    KeyEvent.VK_PAGE_DOWN -> SwingShellSuggestionAction.SELECT_NEXT_PAGE
                    KeyEvent.VK_PAGE_UP -> SwingShellSuggestionAction.SELECT_PREVIOUS_PAGE
                    KeyEvent.VK_ENTER, KeyEvent.VK_TAB -> SwingShellSuggestionAction.ACCEPT
                    KeyEvent.VK_ESCAPE -> SwingShellSuggestionAction.DISMISS
                    else -> null
                }
            }
    }
}
