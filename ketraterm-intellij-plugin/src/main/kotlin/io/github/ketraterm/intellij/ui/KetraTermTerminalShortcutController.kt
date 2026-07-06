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

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.project.DumbAwareAction
import io.github.ketraterm.intellij.settings.KetraTermIntellijSettings
import io.github.ketraterm.ui.swing.api.SwingTerminalHostAction
import io.github.ketraterm.ui.swing.api.SwingTerminalHostShortcutMap
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities

/**
 * IntelliJ-hosted shortcut bindings for one terminal pane.
 *
 * Keyboard shortcuts are installed through IntelliJ actions scoped to the
 * terminal component. The plugin host owns the action policy while reusable
 * Swing terminal code remains focused on rendering, selection, terminal mouse
 * tracking, and terminal input encoding.
 */
internal class KetraTermTerminalShortcutController(
    private val pane: KetraTermTerminalPane,
    private val shortcutMap: SwingTerminalHostShortcutMap = SwingTerminalHostShortcutMap.platformDefault(),
) {
    private val actions = ArrayList<TerminalPaneAction>(SwingTerminalHostAction.entries.size)
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
        installActions()
        pane.terminal.addMouseListener(mouseListener)
    }

    /**
     * Removes host shortcut hooks installed for this pane.
     */
    fun dispose() {
        for (action in actions) {
            action.unregisterCustomShortcutSet(pane.terminal)
        }
        actions.clear()
        pane.terminal.removeMouseListener(mouseListener)
    }

    private fun installActions() {
        shortcutMap.forEachShortcut { actionId, shortcut ->
            val action = TerminalPaneAction(actionId)
            action.registerCustomShortcutSet(CustomShortcutSet(shortcut.keyStroke()), pane.terminal)
            actions += action
        }
    }

    private inner class TerminalPaneAction(
        private val actionId: SwingTerminalHostAction,
    ) : DumbAwareAction() {
        override fun actionPerformed(event: AnActionEvent) {
            performTerminalAction(actionId)
        }

        override fun update(event: AnActionEvent) {
            event.presentation.isEnabled = isTerminalActionEnabled(actionId)
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
    }

    private fun isTerminalActionEnabled(action: SwingTerminalHostAction): Boolean =
        when (action) {
            SwingTerminalHostAction.COPY_SELECTION -> pane.terminal.currentSelection() != null
            SwingTerminalHostAction.PASTE_CLIPBOARD,
            SwingTerminalHostAction.OPEN_SEARCH,
            SwingTerminalHostAction.SCROLL_PAGE_UP,
            SwingTerminalHostAction.SCROLL_PAGE_DOWN,
            -> true
        }

    private fun performTerminalAction(action: SwingTerminalHostAction): Boolean =
        when (action) {
            SwingTerminalHostAction.COPY_SELECTION -> pane.terminal.copySelectionToClipboard()
            SwingTerminalHostAction.PASTE_CLIPBOARD -> pane.terminal.pasteClipboardText()
            SwingTerminalHostAction.OPEN_SEARCH -> {
                pane.terminal.openSearch()
                true
            }
            SwingTerminalHostAction.SCROLL_PAGE_UP -> {
                pane.terminal.scrollViewportBy(pane.terminal.visibleGridSize().height.coerceAtLeast(1).toDouble())
                true
            }
            SwingTerminalHostAction.SCROLL_PAGE_DOWN -> {
                pane.terminal.scrollViewportBy(-pane.terminal.visibleGridSize().height.coerceAtLeast(1).toDouble())
                true
            }
        }
}
