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
package io.github.ketraterm.ui.swing.input

import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.ui.swing.settings.SwingSettings
import io.github.ketraterm.ui.swing.settings.TerminalClipboardShortcuts
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.awt.Canvas
import java.awt.event.FocusEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent

class SwingTerminalInputControllerTest {
    private val source = Canvas()

    @Nested
    inner class FocusRouting {
        @Test
        fun `focus events update focus and cursor repaint state`() {
            val host = RecordingInputHost()
            val controller = SwingTerminalInputController(host)

            controller.focusListener.focusGained(FocusEvent(source, FocusEvent.FOCUS_GAINED))
            controller.focusListener.focusLost(FocusEvent(source, FocusEvent.FOCUS_LOST))

            assertFalse(host.focused)
            assertEquals(listOf(false), host.cursorBlinkResets)
            assertEquals(2, host.cursorRepaints)
        }
    }

    @Nested
    inner class SearchShortcuts {
        @Test
        fun `ctrl shift f opens search and consumes event`() {
            val host = RecordingInputHost()
            val controller = SwingTerminalInputController(host)
            val event =
                keyPressed(
                    keyCode = KeyEvent.VK_F,
                    modifiers = InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK,
                )

            controller.keyListener.keyPressed(event)

            assertEquals(1, host.openSearchCount)
            assertTrue(event.isConsumed)
            assertEquals(listOf(true), host.cursorBlinkResets)
        }

        @Test
        fun `meta shift f opens search for platform menu shortcut hosts`() {
            val host = RecordingInputHost()
            val controller = SwingTerminalInputController(host)
            val event =
                keyPressed(
                    keyCode = KeyEvent.VK_F,
                    modifiers = InputEvent.META_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK,
                )

            controller.keyListener.keyPressed(event)

            assertEquals(1, host.openSearchCount)
            assertTrue(event.isConsumed)
        }

        @Test
        fun `ctrl f without shift does not open search`() {
            val host = RecordingInputHost()
            val controller = SwingTerminalInputController(host)

            controller.keyListener.keyPressed(
                keyPressed(
                    keyCode = KeyEvent.VK_F,
                    modifiers = InputEvent.CTRL_DOWN_MASK,
                ),
            )

            assertEquals(0, host.openSearchCount)
        }
    }

    @Nested
    inner class ClipboardShortcuts {
        @Test
        fun `configured copy shortcut is handled before terminal key encoding`() {
            val host =
                RecordingInputHost(
                    settings = SwingSettings(clipboardShortcuts = TerminalClipboardShortcuts.windows()),
                )
            val controller = SwingTerminalInputController(host)
            val event = keyPressed(keyCode = KeyEvent.VK_C, modifiers = InputEvent.CTRL_DOWN_MASK)

            controller.keyListener.keyPressed(event)

            assertEquals(1, host.copyCount)
            assertEquals(0, host.pasteCount)
            assertTrue(event.isConsumed)
        }

        @Test
        fun `configured paste shortcut is handled before terminal key encoding`() {
            val host =
                RecordingInputHost(
                    settings = SwingSettings(clipboardShortcuts = TerminalClipboardShortcuts.windows()),
                )
            val controller = SwingTerminalInputController(host)
            val event = keyPressed(keyCode = KeyEvent.VK_V, modifiers = InputEvent.CTRL_DOWN_MASK)

            controller.keyListener.keyPressed(event)

            assertEquals(0, host.copyCount)
            assertEquals(1, host.pasteCount)
            assertTrue(event.isConsumed)
        }

        @Test
        fun `failed clipboard copy does not claim shortcut as handled`() {
            val host =
                RecordingInputHost(
                    settings = SwingSettings(clipboardShortcuts = TerminalClipboardShortcuts.windows()),
                    copyResult = false,
                )
            val controller = SwingTerminalInputController(host)
            val event = keyPressed(keyCode = KeyEvent.VK_C, modifiers = InputEvent.CTRL_DOWN_MASK)

            controller.keyListener.keyPressed(event)

            assertEquals(1, host.copyCount)
            assertEquals(0, host.pasteCount)
        }
    }

    @Nested
    inner class ViewportShortcuts {
        @Test
        fun `shift page keys use whole-page row destinations`() {
            val host = RecordingInputHost()
            val controller = SwingTerminalInputController(host)
            val pageUp = keyPressed(KeyEvent.VK_PAGE_UP, InputEvent.SHIFT_DOWN_MASK)
            val pageDown = keyPressed(KeyEvent.VK_PAGE_DOWN, InputEvent.SHIFT_DOWN_MASK)

            controller.keyListener.keyPressed(pageUp)
            controller.keyListener.keyPressed(pageDown)

            assertEquals(listOf(24, -24), host.viewportScrollDeltas)
            assertTrue(pageUp.isConsumed)
            assertTrue(pageDown.isConsumed)
        }

        @Test
        fun `unmodified page key remains terminal input`() {
            val host = RecordingInputHost()
            val controller = SwingTerminalInputController(host)

            controller.keyListener.keyPressed(keyPressed(KeyEvent.VK_PAGE_UP, 0))

            assertTrue(host.viewportScrollDeltas.isEmpty())
        }
    }

    @Nested
    inner class HyperlinkHover {
        @Test
        fun `key press and release publish control activation state`() {
            val host = RecordingInputHost()
            val controller = SwingTerminalInputController(host)

            controller.keyListener.keyPressed(keyPressed(keyCode = KeyEvent.VK_CONTROL, modifiers = InputEvent.CTRL_DOWN_MASK))
            controller.keyListener.keyReleased(keyReleased(keyCode = KeyEvent.VK_CONTROL, modifiers = 0))

            assertEquals(listOf(true, false), host.hyperlinkHoverUpdates)
        }
    }

    private fun keyPressed(
        keyCode: Int,
        modifiers: Int,
    ): KeyEvent =
        KeyEvent(
            source,
            KeyEvent.KEY_PRESSED,
            System.currentTimeMillis(),
            modifiers,
            keyCode,
            KeyEvent.CHAR_UNDEFINED,
        )

    private fun keyReleased(
        keyCode: Int,
        modifiers: Int,
    ): KeyEvent =
        KeyEvent(
            source,
            KeyEvent.KEY_RELEASED,
            System.currentTimeMillis(),
            modifiers,
            keyCode,
            KeyEvent.CHAR_UNDEFINED,
        )

    private class RecordingInputHost(
        override val settings: SwingSettings = SwingSettings(),
        private val copyResult: Boolean = true,
        private val pasteResult: Boolean = true,
    ) : SwingTerminalInputHost {
        override val session: TerminalSession? = null
        val hyperlinkHoverUpdates = ArrayList<Boolean>()
        val cursorBlinkResets = ArrayList<Boolean>()
        val viewportScrollDeltas = ArrayList<Int>()
        var focused = false
        var cursorRepaints = 0
        var openSearchCount = 0
        var copyCount = 0
        var pasteCount = 0

        override fun visibleGridRows(): Int = 24

        override fun scrollViewportByRows(deltaRows: Int) {
            viewportScrollDeltas += deltaRows
        }

        override fun updateHyperlinkActivationHover(active: Boolean) {
            hyperlinkHoverUpdates += active
        }

        override fun resetCursorBlink(forceRepaint: Boolean) {
            cursorBlinkResets += forceRepaint
        }

        override fun setTerminalFocused(focused: Boolean) {
            this.focused = focused
        }

        override fun repaintCursorState() {
            cursorRepaints++
        }

        override fun openSearch() {
            openSearchCount++
        }

        override fun copySelectionToClipboard(): Boolean {
            copyCount++
            return copyResult
        }

        override fun pasteClipboardText(): Boolean {
            pasteCount++
            return pasteResult
        }
    }
}
