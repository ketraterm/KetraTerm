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
import com.intellij.openapi.project.ProjectManager
import io.github.ketraterm.intellij.services.KetraTermProjectTerminalService
import io.github.ketraterm.ui.swing.host.SwingTerminalHostAction
import java.awt.KeyboardFocusManager
import javax.swing.JPopupMenu
import javax.swing.SwingUtilities
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener

internal object KetraTermTerminalActionIds {
    const val CONTEXT_MENU_PLACE = "KetraTerm.TerminalContextMenu"
    const val COPY_SELECTION = "KetraTerm.Terminal.CopySelection"
    const val PASTE_CLIPBOARD = "KetraTerm.Terminal.PasteClipboard"
    const val OPEN_SEARCH = "KetraTerm.Terminal.OpenSearch"
    const val SELECT_ALL = "KetraTerm.Terminal.SelectAll"
    const val CLEAR_SCREEN = "KetraTerm.Terminal.ClearScreen"
    const val NEW_TAB = "KetraTerm.Terminal.NewTab"
    const val CLOSE_TAB = "KetraTerm.Terminal.CloseTab"
    const val OPEN_TERMINAL_HERE = "KetraTerm.Terminal.OpenTerminalHere"
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
        if (!pane.isTerminalActionEnabled(terminalAction, fromContextMenu = event.isTerminalContextMenu())) return
        pane.performTerminalAction(terminalAction)
    }

    /**
     * Enables this action only when a KetraTerm pane owns focus.
     *
     * @param event IntelliJ action event.
     */
    override fun update(event: AnActionEvent) {
        val pane = terminalPane(event)
        event.presentation.isEnabled =
            pane != null && pane.isTerminalActionEnabled(terminalAction, fromContextMenu = event.isTerminalContextMenu())
    }

    /**
     * Declares that pane lookup and presentation updates run on the EDT.
     *
     * @return IntelliJ EDT update thread.
     */
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

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
 * Selects all retained text in the focused terminal.
 */
class KetraTermSelectAllAction : KetraTermTerminalAction(SwingTerminalHostAction.SELECT_ALL)

/**
 * Requests the foreground terminal program to clear/redraw the screen.
 */
class KetraTermClearScreenAction : KetraTermTerminalAction(SwingTerminalHostAction.CLEAR_SCREEN)

/**
 * Scrolls the focused terminal one visible page away from the live viewport.
 */
class KetraTermScrollPageUpAction : KetraTermTerminalAction(SwingTerminalHostAction.SCROLL_PAGE_UP)

/**
 * Scrolls the focused terminal one visible page toward the live viewport.
 */
class KetraTermScrollPageDownAction : KetraTermTerminalAction(SwingTerminalHostAction.SCROLL_PAGE_DOWN)

/**
 * Opens a new KetraTerm tab in the current tool window.
 */
internal class KetraTermNewTabAction : KetraTermPaneLifecycleAction() {
    override fun actionPerformed(
        pane: KetraTermTerminalPane,
        event: AnActionEvent,
    ) {
        pane.openNewTab()
    }
}

/**
 * Closes the focused KetraTerm tab.
 */
internal class KetraTermCloseTabAction : KetraTermPaneLifecycleAction() {
    override fun actionPerformed(
        pane: KetraTermTerminalPane,
        event: AnActionEvent,
    ) {
        pane.closePane()
    }
}

/**
 * Opens a new KetraTerm tab in the focused terminal's local directory.
 */
internal class KetraTermOpenTerminalHereAction : KetraTermPaneLifecycleAction() {
    override fun isEnabled(pane: KetraTermTerminalPane): Boolean = pane.canOpenTerminalHere()

    override fun actionPerformed(
        pane: KetraTermTerminalPane,
        event: AnActionEvent,
    ) {
        pane.openTerminalHere()
    }
}

internal abstract class KetraTermPaneLifecycleAction : DumbAwareAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val pane = terminalPane(event) ?: return
        if (!isEnabled(pane)) return
        actionPerformed(pane, event)
    }

    override fun update(event: AnActionEvent) {
        val pane = terminalPane(event)
        event.presentation.isEnabled = pane != null && isEnabled(pane)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    protected open fun isEnabled(pane: KetraTermTerminalPane): Boolean = true

    protected abstract fun actionPerformed(
        pane: KetraTermTerminalPane,
        event: AnActionEvent,
    )
}

private fun terminalPane(event: AnActionEvent): KetraTermTerminalPane? {
    if (event.isTerminalContextMenu()) {
        KetraTermTerminalPopupContext.currentPane()?.let { return it }
    }
    val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
    event.project
        ?.let { KetraTermProjectTerminalService.getInstance(it).paneForComponent(focusOwner) }
        ?.let { return it }
    for (project in ProjectManager.getInstance().openProjects) {
        KetraTermProjectTerminalService.getInstance(project).paneForComponent(focusOwner)?.let { return it }
    }
    return null
}

private fun AnActionEvent.isTerminalContextMenu(): Boolean = place == KetraTermTerminalActionIds.CONTEXT_MENU_PLACE

internal object KetraTermTerminalPopupContext {
    private var pane: KetraTermTerminalPane? = null

    fun currentPane(): KetraTermTerminalPane? = pane

    fun install(
        popup: JPopupMenu,
        pane: KetraTermTerminalPane,
    ) {
        this.pane = pane
        popup.addPopupMenuListener(
            object : PopupMenuListener {
                override fun popupMenuWillBecomeVisible(event: PopupMenuEvent) = Unit

                override fun popupMenuWillBecomeInvisible(event: PopupMenuEvent) {
                    SwingUtilities.invokeLater { clear(pane) }
                    popup.removePopupMenuListener(this)
                }

                override fun popupMenuCanceled(event: PopupMenuEvent) {
                    SwingUtilities.invokeLater { clear(pane) }
                    popup.removePopupMenuListener(this)
                }
            },
        )
    }

    private fun clear(expectedPane: KetraTermTerminalPane) {
        if (pane === expectedPane) {
            pane = null
        }
    }
}
