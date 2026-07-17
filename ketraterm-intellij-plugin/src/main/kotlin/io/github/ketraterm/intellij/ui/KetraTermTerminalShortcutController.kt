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
package io.github.ketraterm.intellij.ui

import com.intellij.openapi.keymap.KeymapManager
import io.github.ketraterm.intellij.settings.KetraTermIntellijSettings
import io.github.ketraterm.ui.swing.host.SwingTerminalHostAction
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.KeyStroke
import javax.swing.SwingUtilities

/**
 * IntelliJ-hosted input policy hooks for one terminal pane.
 *
 * Keyboard shortcuts are registered as plugin actions in `plugin.xml`. This
 * controller keeps mouse-only IDE policy and a pre-encoding guard so matching
 * key events are not sent to the shell if they reach the Swing terminal before
 * the IntelliJ action system handles them.
 */
internal class KetraTermTerminalShortcutController(
    private val pane: KetraTermTerminalPane,
) {
    private val mouseListener =
        object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) {
                if (event.isConsumed) return
                if (!SwingUtilities.isMiddleMouseButton(event)) return
                if (!KetraTermIntellijSettings.getInstance().pasteOnMiddleClick()) return
                if (!pane.terminal.pasteClipboardText()) return
                event.consume()
            }
        }

    init {
        pane.terminal.addMouseListener(mouseListener)
    }

    /**
     * Removes host input hooks installed for this pane.
     */
    fun dispose() {
        pane.terminal.removeMouseListener(mouseListener)
    }

    /**
     * Handles a key press before terminal input encoding.
     *
     * @param event Swing key event from the terminal component.
     * @return `true` when the key was a host shortcut and must not be sent to
     * the shell.
     */
    fun handleKeyPressed(event: KeyEvent): Boolean {
        val action = activeKeymapActionFor(event.keyCode, event.modifiersEx) ?: return false
        if (action == SwingTerminalHostAction.OPEN_SEARCH && !KetraTermIntellijSettings.getInstance().overrideIdeShortcuts()) {
            return false
        }
        if (!pane.isTerminalActionEnabled(action)) return false
        pane.performTerminalAction(action)
        return true
    }

    private fun activeKeymapActionFor(
        keyCode: Int,
        modifiersEx: Int,
    ): SwingTerminalHostAction? {
        val normalizedModifiers = modifiersEx and RELEVANT_MODIFIERS
        val keyStroke = KeyStroke.getKeyStroke(keyCode, normalizedModifiers)
        val actionIds = KeymapManager.getInstance().activeKeymap.getActionIds(keyStroke)
        for (actionId in actionIds) {
            when (actionId) {
                KetraTermTerminalActionIds.COPY_SELECTION -> return SwingTerminalHostAction.COPY_SELECTION
                KetraTermTerminalActionIds.PASTE_CLIPBOARD -> return SwingTerminalHostAction.PASTE_CLIPBOARD
                KetraTermTerminalActionIds.OPEN_SEARCH -> return SwingTerminalHostAction.OPEN_SEARCH
                KetraTermTerminalActionIds.SELECT_ALL -> return SwingTerminalHostAction.SELECT_ALL
                KetraTermTerminalActionIds.CLEAR_SCREEN -> return SwingTerminalHostAction.CLEAR_SCREEN
                KetraTermTerminalActionIds.SCROLL_PAGE_UP -> return SwingTerminalHostAction.SCROLL_PAGE_UP
                KetraTermTerminalActionIds.SCROLL_PAGE_DOWN -> return SwingTerminalHostAction.SCROLL_PAGE_DOWN
            }
        }
        return null
    }

    private companion object {
        private const val RELEVANT_MODIFIERS =
            InputEvent.SHIFT_DOWN_MASK or
                InputEvent.CTRL_DOWN_MASK or
                InputEvent.META_DOWN_MASK or
                InputEvent.ALT_DOWN_MASK or
                InputEvent.ALT_GRAPH_DOWN_MASK
    }
}
