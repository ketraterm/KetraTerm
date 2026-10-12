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

import io.github.ketraterm.ui.swing.api.SwingTerminal
import io.github.ketraterm.ui.swing.host.SwingTerminalSearchAction
import io.github.ketraterm.ui.swing.host.SwingTerminalSearchBar
import java.awt.Component
import java.awt.Container
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import javax.swing.JComponent
import javax.swing.JToggleButton
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import kotlin.test.*

class TerminalSearchShortcutsTest {
    @Test
    fun customBindingsDriveBothExecutionAndHintsWithoutInstallingDefaults() {
        SwingUtilities.invokeAndWait {
            val terminal = SwingTerminal()
            val bar = SwingTerminalSearchBar(terminal)
            val stroke = KeyStroke.getKeyStroke("ctrl F8")
            val bindings = mutableMapOf(stroke to SwingTerminalSearchAction.TOGGLE_REGEX)
            try {
                TerminalSearchShortcuts.install(bar, bindings)
                bindings.clear()
                bar.open()
                val inputMap = bar.component.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                assertNull(inputMap.get(KeyStroke.getKeyStroke("alt R")))
                assertNull(inputMap.get(KeyStroke.getKeyStroke("ESCAPE")))
                val action = bar.component.actionMap.get(inputMap.get(stroke))
                assertNotNull(action)
                val regex = descendants(bar.component).filterIsInstance<JToggleButton>().single { it.text == ".*" }
                val controlLabel = InputEvent.getModifiersExText(InputEvent.CTRL_DOWN_MASK)
                assertTrue(regex.toolTipText.contains("$controlLabel+F8"), regex.toolTipText)
                assertFalse(regex.isSelected)
                action.actionPerformed(ActionEvent(bar.component, ActionEvent.ACTION_PERFORMED, ""))
                assertTrue(regex.isSelected)
                bar.close()
                assertFalse(action.isEnabled)
            } finally {
                bar.close()
                terminal.dispose()
            }
        }
    }

    @Test
    fun standaloneInstallsNavigationCloseAndOptionBindingsOnlyWithinTheBar() {
        SwingUtilities.invokeAndWait {
            val terminal = SwingTerminal()
            val bar = SwingTerminalSearchBar(terminal)
            try {
                TerminalSearchShortcuts.install(bar)
                val inputMap = bar.component.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                val expected =
                    mapOf(
                        "ENTER" to SwingTerminalSearchAction.NEXT_MATCH,
                        "shift ENTER" to SwingTerminalSearchAction.PREVIOUS_MATCH,
                        "ESCAPE" to SwingTerminalSearchAction.CLOSE,
                        "alt C" to SwingTerminalSearchAction.TOGGLE_MATCH_CASE,
                        "alt W" to SwingTerminalSearchAction.TOGGLE_WHOLE_WORD,
                        "alt R" to SwingTerminalSearchAction.TOGGLE_REGEX,
                    )
                for ((stroke, action) in expected) assertEquals(action, inputMap.get(KeyStroke.getKeyStroke(stroke)))
                bar.open()
                bar.component.actionMap
                    .get(SwingTerminalSearchAction.CLOSE)
                    .actionPerformed(ActionEvent(bar.component, 0, ""))
                assertFalse(bar.isOpen())
            } finally {
                bar.close()
                terminal.dispose()
            }
        }
    }

    private fun descendants(c: Component): List<Component> =
        listOf(c) + if (c is Container) c.components.flatMap(::descendants) else emptyList()
}
