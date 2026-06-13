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
package io.github.jvterm.ui.swing.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.awt.event.InputEvent
import java.awt.event.KeyEvent

class TerminalClipboardShortcutsTest {
    @Test
    fun `platform default uses windows clipboard shortcuts on Windows`() {
        val shortcuts = TerminalClipboardShortcuts.platformDefault("Windows 11")

        assertEquals(TerminalClipboardAction.COPY, shortcuts.actionFor(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK))
        assertEquals(TerminalClipboardAction.PASTE, shortcuts.actionFor(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK))
    }

    @Test
    fun `platform default uses terminal clipboard shortcuts on Linux`() {
        val shortcuts = TerminalClipboardShortcuts.platformDefault("Linux")
        val modifiers = InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK

        assertEquals(TerminalClipboardAction.COPY, shortcuts.actionFor(KeyEvent.VK_C, modifiers))
        assertEquals(TerminalClipboardAction.PASTE, shortcuts.actionFor(KeyEvent.VK_V, modifiers))
    }
}
