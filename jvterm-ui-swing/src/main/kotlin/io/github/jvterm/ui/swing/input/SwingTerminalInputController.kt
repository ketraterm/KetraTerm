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
package io.github.jvterm.ui.swing.input

import io.github.jvterm.ui.swing.settings.TerminalClipboardAction
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent

/**
 * Swing keyboard/focus routing for terminal input and UI shortcuts.
 */
internal class SwingTerminalInputController(
    private val host: SwingTerminalInputHost,
) {
    private val keyMapper = SwingKeyMapper()

    val keyListener =
        object : KeyAdapter() {
            override fun keyPressed(event: KeyEvent) {
                host.updateHyperlinkActivationHover(event.isControlDown)
                host.resetCursorBlink(forceRepaint = true)
                if (handleSearchShortcut(event)) return
                if (handleClipboardShortcut(event)) return

                val keyEvent = keyMapper.keyPressed(event) ?: return
                host.session?.encodeKey(keyEvent)
                event.consume()
            }

            override fun keyReleased(event: KeyEvent) {
                host.updateHyperlinkActivationHover(event.isControlDown)
            }

            override fun keyTyped(event: KeyEvent) {
                host.resetCursorBlink(forceRepaint = true)
                val keyEvent = keyMapper.keyTyped(event) ?: return
                host.session?.encodeKey(keyEvent)
                event.consume()
            }
        }

    val focusListener =
        object : FocusAdapter() {
            override fun focusGained(event: FocusEvent) {
                host.setTerminalFocused(true)
                host.resetCursorBlink(forceRepaint = false)
                host.repaintCursorState()
            }

            override fun focusLost(event: FocusEvent) {
                host.setTerminalFocused(false)
                host.repaintCursorState()
            }
        }

    private fun handleClipboardShortcut(event: KeyEvent): Boolean {
        val handled =
            when (host.settings.clipboardShortcuts.actionFor(event.keyCode, event.modifiersEx)) {
                TerminalClipboardAction.COPY -> host.copySelectionToClipboard()
                TerminalClipboardAction.PASTE -> host.pasteClipboardText()
                TerminalClipboardAction.NONE -> false
            }

        if (!handled) return false
        event.consume()
        return true
    }

    private fun handleSearchShortcut(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.VK_F) return false
        if (!event.isShiftDown) return false
        if (!event.isControlDown && !event.isMetaDown) return false
        host.openSearch()
        event.consume()
        return true
    }
}
