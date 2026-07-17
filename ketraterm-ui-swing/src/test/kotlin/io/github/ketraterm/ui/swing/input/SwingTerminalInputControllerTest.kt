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
            assertEquals(1, host.hideShellSuggestionsCount)
        }
    }

    @Nested
    inner class HostShortcuts {
        @Test
        fun `host handled key press is consumed before terminal input handling`() {
            val host = RecordingInputHost(hostKeyHandled = true)
            val controller = SwingTerminalInputController(host)
            val event =
                keyPressed(
                    keyCode = KeyEvent.VK_F,
                    modifiers = InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK,
                )

            controller.keyListener.keyPressed(event)

            assertEquals(1, host.hostKeyPressCount)
            assertEquals(0, host.shellSuggestionKeyPressCount)
            assertTrue(host.hyperlinkHoverUpdates.isEmpty())
            assertTrue(host.cursorBlinkResets.isEmpty())
            assertTrue(event.isConsumed)
        }
    }

    @Nested
    inner class ShellSuggestionShortcuts {
        @Test
        fun `visible suggestion popup handles navigation before terminal input`() {
            val host = RecordingInputHost(shellSuggestionKeyHandled = true)
            val controller = SwingTerminalInputController(host)
            val event = keyPressed(keyCode = KeyEvent.VK_DOWN, modifiers = 0)

            controller.keyListener.keyPressed(event)

            assertEquals(1, host.shellSuggestionKeyPressCount)
            assertTrue(event.isConsumed)
        }
    }

    @Nested
    inner class HyperlinkHover {
        @Test
        fun `key press and release publish control activation state`() {
            val host = RecordingInputHost()
            val controller = SwingTerminalInputController(host)
            val releaseEvent = keyReleased(keyCode = KeyEvent.VK_CONTROL, modifiers = 0)

            controller.keyListener.keyPressed(keyPressed(keyCode = KeyEvent.VK_CONTROL, modifiers = InputEvent.CTRL_DOWN_MASK))
            controller.keyListener.keyReleased(releaseEvent)

            assertEquals(listOf(true, false), host.hyperlinkHoverUpdates)
            assertTrue(releaseEvent.isConsumed)
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
        private val hostKeyHandled: Boolean = false,
        private val shellSuggestionKeyHandled: Boolean = false,
    ) : SwingTerminalInputHost {
        override val session: TerminalSession? = null
        val hyperlinkHoverUpdates = ArrayList<Boolean>()
        val cursorBlinkResets = ArrayList<Boolean>()
        var focused = false
        var cursorRepaints = 0
        var hostKeyPressCount = 0
        var shellSuggestionKeyPressCount = 0

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

        override fun handleHostKeyPressed(event: KeyEvent): Boolean {
            hostKeyPressCount++
            return hostKeyHandled
        }

        override fun handleShellSuggestionKeyPressed(event: KeyEvent): Boolean {
            shellSuggestionKeyPressCount++
            if (shellSuggestionKeyHandled) event.consume()
            return shellSuggestionKeyHandled
        }

        var hideShellSuggestionsCount = 0

        override fun hideShellSuggestions() {
            hideShellSuggestionsCount++
        }
    }
}
