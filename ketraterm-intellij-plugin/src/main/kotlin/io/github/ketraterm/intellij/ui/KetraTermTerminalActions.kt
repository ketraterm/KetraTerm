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
import com.intellij.openapi.project.DumbAwareAction
import io.github.ketraterm.intellij.services.KetraTermProjectTerminalService
import io.github.ketraterm.ui.swing.host.SwingTerminalHostAction
import java.awt.KeyboardFocusManager

internal object KetraTermTerminalActionIds {
    const val COPY_SELECTION = "KetraTerm.Terminal.CopySelection"
    const val PASTE_CLIPBOARD = "KetraTerm.Terminal.PasteClipboard"
    const val OPEN_SEARCH = "KetraTerm.Terminal.OpenSearch"
    const val SCROLL_PAGE_UP = "KetraTerm.Terminal.ScrollPageUp"
    const val SCROLL_PAGE_DOWN = "KetraTerm.Terminal.ScrollPageDown"
}

/**
 * Base class for IntelliJ keymap actions that target the focused KetraTerm pane.
 *
 * Actions are registered in `plugin.xml` so users can inspect and customize
 * shortcuts through the IDE keymap. The focused pane still owns execution
 * semantics through [KetraTermTerminalPane.performTerminalAction].
 *
 * @param terminalAction host-owned terminal pane action to perform.
 */
abstract class KetraTermTerminalAction(
    private val terminalAction: SwingTerminalHostAction,
) : DumbAwareAction() {
    /**
     * Performs this action against the focused KetraTerm pane.
     *
     * @param event IntelliJ action event.
     */
    override fun actionPerformed(event: AnActionEvent) {
        val pane = terminalPane(event) ?: return
        if (!pane.isTerminalActionEnabled(terminalAction)) return
        pane.performTerminalAction(terminalAction)
    }

    /**
     * Enables this action only when a KetraTerm pane owns focus.
     *
     * @param event IntelliJ action event.
     */
    override fun update(event: AnActionEvent) {
        val pane = terminalPane(event)
        event.presentation.isEnabled = pane != null && pane.isTerminalActionEnabled(terminalAction)
    }

    /**
     * Declares that pane lookup and presentation updates run on the EDT.
     *
     * @return IntelliJ EDT update thread.
     */
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    private fun terminalPane(event: AnActionEvent): KetraTermTerminalPane? {
        val project = event.project ?: return null
        val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
        return KetraTermProjectTerminalService.getInstance(project).paneForComponent(focusOwner)
    }
}

/**
 * Copies the focused terminal selection to the IDE clipboard.
 */
class KetraTermCopySelectionAction : KetraTermTerminalAction(SwingTerminalHostAction.COPY_SELECTION)

/**
 * Pastes IDE clipboard text into the focused terminal session.
 */
class KetraTermPasteClipboardAction : KetraTermTerminalAction(SwingTerminalHostAction.PASTE_CLIPBOARD)

/**
 * Opens the focused terminal pane's search overlay.
 */
class KetraTermOpenSearchAction : KetraTermTerminalAction(SwingTerminalHostAction.OPEN_SEARCH)

/**
 * Scrolls the focused terminal one visible page away from the live viewport.
 */
class KetraTermScrollPageUpAction : KetraTermTerminalAction(SwingTerminalHostAction.SCROLL_PAGE_UP)

/**
 * Scrolls the focused terminal one visible page toward the live viewport.
 */
class KetraTermScrollPageDownAction : KetraTermTerminalAction(SwingTerminalHostAction.SCROLL_PAGE_DOWN)
