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
package io.github.ketraterm.ui.swing.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.awt.event.InputEvent
import java.awt.event.KeyEvent

class SwingTerminalHostShortcutMapTest {
    @Test
    fun `windows defaults use ctrl clipboard shortcuts`() {
        val shortcuts = SwingTerminalHostShortcutMap.platformDefault("Windows 11")

        assertEquals(SwingTerminalHostAction.COPY_SELECTION, shortcuts.actionFor(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK))
        assertEquals(SwingTerminalHostAction.PASTE_CLIPBOARD, shortcuts.actionFor(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK))
        assertNull(shortcuts.actionFor(KeyEvent.VK_C, InputEvent.SHIFT_DOWN_MASK))
    }

    @Test
    fun `linux defaults use ctrl shift clipboard shortcuts`() {
        val shortcuts = SwingTerminalHostShortcutMap.platformDefault("Linux")
        val modifiers = InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK

        assertEquals(SwingTerminalHostAction.COPY_SELECTION, shortcuts.actionFor(KeyEvent.VK_C, modifiers))
        assertEquals(SwingTerminalHostAction.PASTE_CLIPBOARD, shortcuts.actionFor(KeyEvent.VK_V, modifiers))
        assertNull(shortcuts.actionFor(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK))
    }

    @Test
    fun `mac defaults use meta clipboard and search shortcuts`() {
        val shortcuts = SwingTerminalHostShortcutMap.platformDefault("macOS")

        assertEquals(SwingTerminalHostAction.COPY_SELECTION, shortcuts.actionFor(KeyEvent.VK_C, InputEvent.META_DOWN_MASK))
        assertEquals(
            SwingTerminalHostAction.OPEN_SEARCH,
            shortcuts.actionFor(KeyEvent.VK_F, InputEvent.META_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK),
        )
    }

    @Test
    fun `page scroll defaults use shift page keys`() {
        val shortcuts = SwingTerminalHostShortcutMap.platformDefault("Windows 11")

        assertEquals(SwingTerminalHostAction.SCROLL_PAGE_UP, shortcuts.actionFor(KeyEvent.VK_PAGE_UP, InputEvent.SHIFT_DOWN_MASK))
        assertEquals(SwingTerminalHostAction.SCROLL_PAGE_DOWN, shortcuts.actionFor(KeyEvent.VK_PAGE_DOWN, InputEvent.SHIFT_DOWN_MASK))
    }
}
