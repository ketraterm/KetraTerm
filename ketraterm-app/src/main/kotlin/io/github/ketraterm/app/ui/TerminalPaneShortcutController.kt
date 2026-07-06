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

import io.github.ketraterm.app.config.KetraTermSettings
import io.github.ketraterm.ui.swing.api.SwingTerminalHostAction
import io.github.ketraterm.ui.swing.api.SwingTerminalHostShortcutMap
import java.awt.event.ActionEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.SwingUtilities

/**
 * Standalone-host shortcut bindings for one terminal pane.
 *
 * The reusable `SwingTerminal` exposes terminal operations but does not decide
 * which keyboard or mouse gestures invoke application commands. This controller
 * keeps those bindings in the standalone host while leaving terminal keystrokes
 * to the reusable input encoder.
 */
internal class TerminalPaneShortcutController(
    private val pane: TerminalPane,
    private val settings: KetraTermSettings,
    private val actionRegistry: TerminalPaneActionRegistry = TerminalPaneActionRegistry,
    private val shortcutMap: SwingTerminalHostShortcutMap = SwingTerminalHostShortcutMap.platformDefault(),
) {
    private val mouseListener =
        object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) {
                if (event.isConsumed) return
                if (!SwingUtilities.isMiddleMouseButton(event)) return
                if (!settings.pasteOnMiddleClick) return
                if (!pane.terminal.pasteClipboardText()) return
                event.consume()
            }
        }

    init {
        installKeyBindings()
        pane.terminal.addMouseListener(mouseListener)
    }

    /**
     * Removes listeners installed by this controller.
     */
    fun dispose() {
        pane.terminal.removeMouseListener(mouseListener)
    }

    private fun installKeyBindings() {
        val inputMap = pane.terminal.getInputMap(JComponent.WHEN_FOCUSED)
        val actionMap = pane.terminal.actionMap
        shortcutMap.forEachShortcut { actionId, shortcut ->
            val bindingKey = "ketraterm.${actionId.name}"
            inputMap.put(shortcut.keyStroke(), bindingKey)
            actionMap.put(bindingKey, TerminalPaneSwingAction(actionId))
        }
    }

    private inner class TerminalPaneSwingAction(
        private val actionId: SwingTerminalHostAction,
    ) : AbstractAction() {
        override fun isEnabled(): Boolean = actionRegistry.isEnabled(actionId, pane)

        override fun actionPerformed(event: ActionEvent) {
            actionRegistry.perform(actionId, pane)
        }
    }
}

/**
 * Standalone terminal-pane action execution.
 */
internal object TerminalPaneActionRegistry {
    /**
     * Returns whether [action] can currently be executed for [pane].
     *
     * @param action terminal host action.
     * @param pane active terminal pane.
     * @return `true` when the action should claim its shortcut.
     */
    fun isEnabled(
        action: SwingTerminalHostAction,
        pane: TerminalPane,
    ): Boolean =
        when (action) {
            SwingTerminalHostAction.COPY_SELECTION -> pane.terminal.currentSelection() != null
            SwingTerminalHostAction.PASTE_CLIPBOARD,
            SwingTerminalHostAction.OPEN_SEARCH,
            SwingTerminalHostAction.SCROLL_PAGE_UP,
            SwingTerminalHostAction.SCROLL_PAGE_DOWN,
            -> true
        }

    /**
     * Executes [action] for [pane].
     *
     * @param action terminal host action.
     * @param pane target terminal pane.
     * @return `true` when the action performed useful work.
     */
    fun perform(
        action: SwingTerminalHostAction,
        pane: TerminalPane,
    ): Boolean =
        when (action) {
            SwingTerminalHostAction.COPY_SELECTION -> pane.terminal.copySelectionToClipboard()
            SwingTerminalHostAction.PASTE_CLIPBOARD -> pane.terminal.pasteClipboardText()
            SwingTerminalHostAction.OPEN_SEARCH -> {
                pane.openSearch()
                true
            }
            SwingTerminalHostAction.SCROLL_PAGE_UP -> {
                pane.terminal.scrollViewportBy(
                    pane.terminal
                        .visibleGridSize()
                        .height
                        .coerceAtLeast(1)
                        .toDouble(),
                )
                true
            }
            SwingTerminalHostAction.SCROLL_PAGE_DOWN -> {
                pane.terminal.scrollViewportBy(
                    -pane.terminal
                        .visibleGridSize()
                        .height
                        .coerceAtLeast(1)
                        .toDouble(),
                )
                true
            }
        }
}
