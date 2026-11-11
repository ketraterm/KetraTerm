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
import java.awt.event.KeyEvent
import javax.swing.KeyStroke

/** Standalone suggestion shortcuts. Comma-separated Swing key strokes; an empty value disables an action. */
@OptIn(ExperimentalShellSuggestionsApi::class)
internal data class SuggestionKeyBindings(
    val popupAccept: String = "TAB",
    val popupSelectedAccept: String = "ENTER",
    val inlineAccept: String = "TAB, RIGHT",
    val trigger: String = "ctrl SPACE",
) {
    private val popupKeys = parse(popupAccept)
    private val selectedKeys = parse(popupSelectedAccept)
    private val inlineKeys = parse(inlineAccept)
    private val triggerKeys = parse(trigger)

    fun popupAction(event: KeyEvent): SwingShellSuggestionAction? =
        when (KeyStroke.getKeyStrokeForEvent(event)) {
            in selectedKeys -> SwingShellSuggestionAction.ACCEPT_SELECTED
            in popupKeys -> SwingShellSuggestionAction.ACCEPT_SELECTED
            else ->
                SwingShellSuggestionKeymap.STANDARD.actionFor(event)?.takeUnless {
                    it == SwingShellSuggestionAction.ACCEPT || it == SwingShellSuggestionAction.ACCEPT_SELECTED
                }
        }

    fun inlineAction(event: KeyEvent): SwingShellSuggestionAction? =
        if (KeyStroke.getKeyStrokeForEvent(event) in inlineKeys) {
            SwingShellSuggestionAction.ACCEPT_SELECTED
        } else {
            SwingShellSuggestionKeymap.INLINE_STANDARD.actionFor(event)?.takeIf { it == SwingShellSuggestionAction.DISMISS }
        }

    fun triggers(event: KeyEvent): Boolean = KeyStroke.getKeyStrokeForEvent(event) in triggerKeys

    companion object {
        fun isValid(value: String): Boolean = runCatching { parse(value) }.isSuccess

        private fun parse(value: String): Set<KeyStroke> {
            if (value.isBlank()) return emptySet()
            return value
                .split(',')
                .map { text ->
                    val key = KeyStroke.getKeyStroke(text.trim())
                    require(key != null && key.keyCode != KeyEvent.VK_UNDEFINED && !key.isOnKeyRelease) {
                        "Expected a pressed Swing key stroke, for example TAB or ctrl SPACE"
                    }
                    key
                }.toSet()
        }
    }
}
