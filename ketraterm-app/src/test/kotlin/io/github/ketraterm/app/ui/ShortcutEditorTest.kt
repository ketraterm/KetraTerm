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

import org.junit.jupiter.api.Test
import java.awt.event.FocusEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.util.concurrent.FutureTask
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShortcutEditorTest {
    @Test
    fun `captures Tab Enter arrows and modified keys as typed values without duplicates`() =
        onEdt {
            val editor = ShortcutEditor(emptySet(), "Accept")
            var changes = 0
            editor.addPropertyChangeListener("shortcuts") { changes++ }
            for (code in listOf(KeyEvent.VK_TAB, KeyEvent.VK_ENTER, KeyEvent.VK_RIGHT)) record(editor, code)
            record(editor, KeyEvent.VK_SPACE, InputEvent.CTRL_DOWN_MASK)
            record(editor, KeyEvent.VK_TAB)
            assertEquals(
                setOf(
                    KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0),
                    KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0),
                    KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0),
                    KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, InputEvent.CTRL_DOWN_MASK),
                ),
                editor.shortcuts,
            )
            assertEquals(4, changes)
            assertTrue(editor.components.filterIsInstance<JButton>().any { it.text == "→ ×" })
            assertFalse(editor.components.filterIsInstance<JButton>().any { "pressed" in it.text })
        }

    @Test
    fun `modifier presses wait for a key and Escape can be recorded`() =
        onEdt {
            val editor = ShortcutEditor(emptySet(), "Accept")
            val button = addButton(editor)
            button.doClick()
            assertFalse(button.focusTraversalKeysEnabled)
            press(button, KeyEvent.VK_CONTROL, InputEvent.CTRL_DOWN_MASK)
            press(button, KeyEvent.VK_SHIFT, InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK)
            assertTrue(editor.shortcuts.isEmpty())
            press(button, KeyEvent.VK_K, InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK)
            assertEquals(
                setOf(KeyStroke.getKeyStroke(KeyEvent.VK_K, InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK)),
                editor.shortcuts,
            )
            assertTrue(button.focusTraversalKeysEnabled)
            record(editor, KeyEvent.VK_ESCAPE)
            assertTrue(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0) in editor.shortcuts)
        }

    @Test
    fun `focus loss cancel and disabling stop recording without changing shortcuts`() =
        onEdt {
            val editor = ShortcutEditor(emptySet(), "Accept")
            val button = addButton(editor)
            button.doClick()
            button.focusListeners.forEach { it.focusLost(FocusEvent(button, FocusEvent.FOCUS_LOST)) }
            assertFalse(press(button, KeyEvent.VK_J).isConsumed)
            button.doClick()
            editor.components
                .filterIsInstance<JButton>()
                .single { it.text == "Cancel" }
                .doClick()
            assertFalse(press(button, KeyEvent.VK_J).isConsumed)
            button.doClick()
            editor.isEnabled = false
            assertTrue(editor.components.none { it.isEnabled })
            editor.isEnabled = true
            assertFalse(press(button, KeyEvent.VK_J).isConsumed)
            assertTrue(editor.shortcuts.isEmpty())
        }

    @Test
    fun `removal clears bindings and supplied sets are copied`() =
        onEdt {
            val original = mutableSetOf(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0))
            val editor = ShortcutEditor(original, "Accept")
            original.clear()
            assertEquals(1, editor.shortcuts.size)
            editor.components
                .filterIsInstance<JButton>()
                .single { it.text.endsWith(" ×") }
                .doClick()
            assertTrue(editor.shortcuts.isEmpty())
            assertTrue(editor.components.filterIsInstance<JLabel>().any { it.text == "Not set" })
            val replacement = mutableSetOf(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0))
            editor.shortcuts = replacement
            replacement.clear()
            assertEquals(1, editor.shortcuts.size)
            assertFailsWith<IllegalArgumentException> { editor.shortcuts = setOf(KeyStroke.getKeyStroke('x')) }
        }

    private fun record(
        editor: ShortcutEditor,
        code: Int,
        modifiers: Int = 0,
    ) {
        val button = addButton(editor)
        button.doClick()
        assertTrue(press(button, code, modifiers).isConsumed)
    }

    private fun addButton(editor: ShortcutEditor): JButton =
        editor.components.filterIsInstance<JButton>().single {
            it.toolTipText ==
                "Add shortcut"
        }

    private fun press(
        button: JButton,
        code: Int,
        modifiers: Int = 0,
    ): KeyEvent {
        val event = KeyEvent(button, KeyEvent.KEY_PRESSED, 0L, modifiers, code, KeyEvent.CHAR_UNDEFINED)
        button.keyListeners.forEach { it.keyPressed(event) }
        return event
    }

    private fun onEdt(action: () -> Unit) {
        val task = FutureTask(action)
        SwingUtilities.invokeAndWait(task)
        task.get()
    }
}
