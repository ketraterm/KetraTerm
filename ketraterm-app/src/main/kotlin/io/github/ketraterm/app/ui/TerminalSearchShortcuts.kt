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
package io.github.ketraterm.app.ui

import io.github.ketraterm.ui.swing.host.SwingTerminalSearchAction
import io.github.ketraterm.ui.swing.host.SwingTerminalSearchBar
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.KeyStroke

/** Standalone search bindings and their tooltip labels share the same host-owned map. */
internal object TerminalSearchShortcuts {
    private val defaults =
        mapOf(
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0) to SwingTerminalSearchAction.NEXT_MATCH,
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK) to SwingTerminalSearchAction.PREVIOUS_MATCH,
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0) to SwingTerminalSearchAction.CLOSE,
            KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.ALT_DOWN_MASK) to SwingTerminalSearchAction.TOGGLE_MATCH_CASE,
            KeyStroke.getKeyStroke(KeyEvent.VK_W, InputEvent.ALT_DOWN_MASK) to SwingTerminalSearchAction.TOGGLE_WHOLE_WORD,
            KeyStroke.getKeyStroke(KeyEvent.VK_R, InputEvent.ALT_DOWN_MASK) to SwingTerminalSearchAction.TOGGLE_REGEX,
        )

    fun install(
        bar: SwingTerminalSearchBar,
        bindings: Map<KeyStroke, SwingTerminalSearchAction> = defaults,
    ) {
        val shortcuts = bindings.toMap()
        val inputMap = bar.component.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
        for ((stroke, action) in shortcuts) {
            inputMap.put(stroke, action)
            bar.component.actionMap.put(
                action,
                object : AbstractAction() {
                    override fun isEnabled(): Boolean = bar.isOpen()

                    override fun actionPerformed(event: ActionEvent) {
                        bar.performAction(action)
                    }
                },
            )
        }
        bar.setShortcutTextProvider { action ->
            shortcuts.entries.firstOrNull { it.value == action }?.key?.let { stroke ->
                val modifiers = InputEvent.getModifiersExText(stroke.modifiers)
                listOf(modifiers, KeyEvent.getKeyText(stroke.keyCode)).filter { it.isNotEmpty() }.joinToString("+")
            }
        }
    }
}
