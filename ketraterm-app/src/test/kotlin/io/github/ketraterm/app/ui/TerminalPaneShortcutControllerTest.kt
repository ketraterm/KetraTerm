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

import io.github.ketraterm.ui.swing.host.SwingTerminalHostAction
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalPaneShortcutControllerTest {
    @Test
    fun `font zoom follows platform modifiers and preserves shell combinations`() {
        for (os in listOf("Windows 11", "Linux", "Mac OS X", "Darwin")) {
            val menu = if (os == "Mac OS X" || os == "Darwin") InputEvent.META_DOWN_MASK else InputEvent.CTRL_DOWN_MASK
            for (key in listOf(KeyEvent.VK_EQUALS, KeyEvent.VK_PLUS, KeyEvent.VK_ADD)) {
                assertEquals(1, terminalFontZoomChange(key, menu, os))
                assertEquals(1, terminalFontZoomChange(key, menu or InputEvent.SHIFT_DOWN_MASK, os))
            }
            for (key in listOf(KeyEvent.VK_MINUS, KeyEvent.VK_SUBTRACT)) {
                assertEquals(-1, terminalFontZoomChange(key, menu, os))
            }
            assertEquals(0, terminalFontZoomChange(KeyEvent.VK_0, menu, os))
            assertEquals(0, terminalFontZoomChange(KeyEvent.VK_NUMPAD0, menu, os))
            for (extra in listOf(InputEvent.ALT_DOWN_MASK, InputEvent.ALT_GRAPH_DOWN_MASK)) {
                assertEquals(null, terminalFontZoomChange(KeyEvent.VK_EQUALS, menu or extra, os))
            }
            assertEquals(null, terminalFontZoomChange(KeyEvent.VK_MINUS, menu or InputEvent.SHIFT_DOWN_MASK, os))
            assertEquals(null, terminalFontZoomChange(KeyEvent.VK_0, 0, os))
            assertEquals(null, terminalFontZoomChange(KeyEvent.VK_C, menu, os))
        }
    }

    @Test
    fun `disabled completion action leaves input unclaimed`() {
        val target = RecordingActionTarget(enabled = false)
        assertFalse(TerminalPaneActionRegistry.isEnabled(SwingTerminalHostAction.REQUEST_SUGGESTIONS, target))
        assertFalse(TerminalPaneActionRegistry.perform(SwingTerminalHostAction.REQUEST_SUGGESTIONS, target))
        assertEquals(0, target.requestSuggestionsCount)
        assertTrue(TerminalPaneActionRegistry.perform(SwingTerminalHostAction.OPEN_SEARCH, target))
    }

    @Test
    fun openSearchActionOpensHostSearchChrome() {
        val target = RecordingActionTarget()

        val performed = TerminalPaneActionRegistry.perform(SwingTerminalHostAction.OPEN_SEARCH, target)

        assertTrue(performed)
        assertEquals(1, target.openSearchCount)
        assertEquals(0, target.copyCount)
        assertEquals(0, target.pasteCount)
    }

    @Test
    fun requestSuggestionsActionUsesActiveShellCommand() {
        val target = RecordingActionTarget()

        val performed = TerminalPaneActionRegistry.perform(SwingTerminalHostAction.REQUEST_SUGGESTIONS, target)

        assertTrue(performed)
        assertEquals(1, target.requestSuggestionsCount)
    }

    @Test
    fun `installed provider without an editable shell context does not claim completion`() {
        val target = RecordingActionTarget(contextAvailable = false)
        assertTrue(TerminalPaneActionRegistry.isEnabled(SwingTerminalHostAction.REQUEST_SUGGESTIONS, target))
        assertFalse(TerminalPaneActionRegistry.perform(SwingTerminalHostAction.REQUEST_SUGGESTIONS, target))
        assertEquals(1, target.requestSuggestionsCount)
    }

    private class RecordingActionTarget(
        val enabled: Boolean = true,
        val contextAvailable: Boolean = true,
    ) : TerminalPaneActionTarget {
        var openSearchCount: Int = 0
            private set
        var copyCount: Int = 0
            private set
        var pasteCount: Int = 0
            private set
        var selectAllCount: Int = 0
            private set
        var clearScreenCount: Int = 0
            private set
        var requestSuggestionsCount: Int = 0
            private set

        override fun suggestionsEnabled(): Boolean = enabled

        override fun hasSelection(): Boolean = false

        override fun copySelectionToClipboard(): Boolean {
            copyCount++
            return true
        }

        override fun pasteClipboardText(): Boolean {
            pasteCount++
            return true
        }

        override fun selectAll(): Boolean {
            selectAllCount++
            return true
        }

        override fun clearScreen(): Boolean {
            clearScreenCount++
            return true
        }

        override fun requestShellSuggestions(): Boolean {
            requestSuggestionsCount++
            return contextAvailable
        }

        override fun openSearch() {
            openSearchCount++
        }

        override fun scrollPageUp() = Unit

        override fun scrollPageDown() = Unit
    }
}
