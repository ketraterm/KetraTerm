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
package io.github.ketraterm.app.config

import io.github.ketraterm.ui.swing.suggestion.ExperimentalShellSuggestionsApi
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionAction
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionKeymap
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.KeyStroke

/** Typed standalone shortcuts. Text parsing and serialization belong to the configuration boundary. */
@OptIn(ExperimentalShellSuggestionsApi::class)
internal data class SuggestionKeyBindings(
    val popupAccept: Set<KeyStroke> = setOf(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0), KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0)),
    val inlineAccept: Set<KeyStroke> = setOf(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0)),
    val trigger: Set<KeyStroke> = setOf(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, InputEvent.CTRL_DOWN_MASK)),
) {
    init {
        require(listOf(popupAccept, inlineAccept, trigger).all { keys -> keys.all(::isValidShortcut) }) {
            "Suggestion shortcuts must be pressed keys"
        }
    }

    fun effectivePopupAccept(allowEnter: Boolean): Set<KeyStroke> =
        if (allowEnter) popupAccept else popupAccept.filterNot { it.keyCode == KeyEvent.VK_ENTER }.toSet()

    fun popupAction(event: KeyEvent): SwingShellSuggestionAction? =
        when (KeyStroke.getKeyStrokeForEvent(event)) {
            in popupAccept -> SwingShellSuggestionAction.ACCEPT_SELECTED
            else ->
                SwingShellSuggestionKeymap.STANDARD.actionFor(event)?.takeUnless {
                    it == SwingShellSuggestionAction.ACCEPT || it == SwingShellSuggestionAction.ACCEPT_SELECTED
                }
        }

    fun inlineAction(event: KeyEvent): SwingShellSuggestionAction? =
        if (KeyStroke.getKeyStrokeForEvent(event) in inlineAccept) {
            SwingShellSuggestionAction.ACCEPT_SELECTED
        } else {
            SwingShellSuggestionKeymap.INLINE_STANDARD.actionFor(event)?.takeIf { it == SwingShellSuggestionAction.DISMISS }
        }

    fun triggers(event: KeyEvent): Boolean = KeyStroke.getKeyStrokeForEvent(event) in trigger

    companion object {
        fun isValidShortcut(key: KeyStroke): Boolean = key.keyCode != KeyEvent.VK_UNDEFINED && !key.isOnKeyRelease
    }
}
