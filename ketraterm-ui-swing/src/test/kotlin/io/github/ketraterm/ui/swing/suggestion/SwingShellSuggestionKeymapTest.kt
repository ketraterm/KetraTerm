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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.awt.Canvas
import java.awt.event.InputEvent
import java.awt.event.KeyEvent

class SwingShellSuggestionKeymapTest {
    private val source = Canvas()

    @Test
    fun `next word has no default binding and whole inline acceptance remains unchanged`() {
        for (modifiers in listOf(
            0,
            InputEvent.CTRL_DOWN_MASK,
            InputEvent.ALT_DOWN_MASK,
            InputEvent.SHIFT_DOWN_MASK,
            InputEvent.META_DOWN_MASK,
        )) {
            for (key in KeyEvent.VK_TAB..KeyEvent.VK_CONTEXT_MENU) {
                val event = KeyEvent(source, KeyEvent.KEY_PRESSED, 0L, modifiers, key, KeyEvent.CHAR_UNDEFINED)
                org.junit.jupiter.api.Assertions.assertNotEquals(
                    SwingShellSuggestionAction.ACCEPT_NEXT_WORD,
                    SwingShellSuggestionKeymap.INLINE_STANDARD.actionFor(event),
                )
                org.junit.jupiter.api.Assertions.assertNotEquals(
                    SwingShellSuggestionAction.ACCEPT_NEXT_WORD,
                    SwingShellSuggestionKeymap.STANDARD.actionFor(event),
                )
            }
        }
        for (key in listOf(KeyEvent.VK_TAB, KeyEvent.VK_RIGHT)) {
            assertEquals(
                SwingShellSuggestionAction.ACCEPT_SELECTED,
                SwingShellSuggestionKeymap.INLINE_STANDARD.actionFor(
                    KeyEvent(source, KeyEvent.KEY_PRESSED, 0L, 0, key, KeyEvent.CHAR_UNDEFINED),
                ),
            )
        }
    }

    @Test
    fun `standard keymap resolves conventional popup actions`() {
        assertEquals(SwingShellSuggestionAction.SELECT_NEXT, action(KeyEvent.VK_DOWN))
        assertEquals(SwingShellSuggestionAction.SELECT_PREVIOUS, action(KeyEvent.VK_UP))
        assertEquals(SwingShellSuggestionAction.ACCEPT_SELECTED, action(KeyEvent.VK_ENTER))
        assertEquals(SwingShellSuggestionAction.ACCEPT_SELECTED, action(KeyEvent.VK_TAB))
        assertEquals(SwingShellSuggestionAction.DISMISS, action(KeyEvent.VK_ESCAPE))
    }

    @Test
    fun `standard keymap maps shift tab but rejects modified acceptance`() {
        assertEquals(
            SwingShellSuggestionAction.SELECT_PREVIOUS,
            action(KeyEvent.VK_TAB, InputEvent.SHIFT_DOWN_MASK),
        )
        assertNull(action(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK))
        assertNull(action(KeyEvent.VK_TAB, InputEvent.ALT_GRAPH_DOWN_MASK))
    }

    private fun action(
        keyCode: Int,
        modifiers: Int = 0,
    ): SwingShellSuggestionAction? =
        SwingShellSuggestionKeymap.STANDARD.actionFor(
            KeyEvent(
                source,
                KeyEvent.KEY_PRESSED,
                System.currentTimeMillis(),
                modifiers,
                keyCode,
                KeyEvent.CHAR_UNDEFINED,
            ),
        )
}
