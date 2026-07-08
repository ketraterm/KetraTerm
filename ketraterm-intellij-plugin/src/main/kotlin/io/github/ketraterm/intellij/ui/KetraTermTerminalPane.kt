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

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollBar
import io.github.ketraterm.intellij.settings.KetraTermIntellijSettings
import io.github.ketraterm.ui.swing.api.*
import io.github.ketraterm.ui.swing.host.SwingTerminalHostAction
import io.github.ketraterm.ui.swing.host.SwingTerminalOverlayPane
import io.github.ketraterm.ui.swing.host.SwingTerminalSearchBar
import io.github.ketraterm.workspace.TerminalWorkspaceTab
import java.awt.Adjustable
import java.awt.BorderLayout
import javax.swing.Icon
import javax.swing.JPanel

/**
 * IntelliJ-hosted pane that binds one workspace tab to one reusable terminal component.
 *
 * This class owns only IDE-side Swing assembly. Painting, selection, input
 * mapping, render-cache consumption, and session mutation remain in reusable
 * KetraTerm modules.
 */
internal class KetraTermTerminalPane private constructor(
    val tab: TerminalWorkspaceTab,
    val terminal: SwingTerminal,
    val component: JPanel,
    private val searchBar: SwingTerminalSearchBar,
    private val hostActions: KetraTermTerminalPaneHostActions,
) {
    private var shortcutController: KetraTermTerminalShortcutController? = null

    /**
     * Requests keyboard focus for the terminal component.
     */
    fun requestFocus() {
        terminal.requestFocusInWindow()
    }

    /**
     * Rebuilds the terminal component from the latest IntelliJ settings.
     */
    fun reloadSettings() {
        terminal.reloadSettings()
        component.background = terminal.background
        searchBar.refreshColors()
        tab.session.setHostPolicy(KetraTermIntellijSettings.getInstance().createHostPolicy(tab.profile.command))
    }

    /**
     * Opens the IDE-hosted search UI for this terminal pane.
     */
    fun openSearch() {
        searchBar.open()
    }

    /**
     * Returns whether [action] can currently run for this pane.
     *
     * @param action host-owned terminal pane action.
     * @return `true` when the action should be enabled.
     */
    fun isTerminalActionEnabled(action: SwingTerminalHostAction): Boolean =
        when (action) {
            SwingTerminalHostAction.COPY_SELECTION -> terminal.currentSelection() != null
            SwingTerminalHostAction.OPEN_SEARCH -> KetraTermIntellijSettings.getInstance().overrideIdeShortcuts()
            SwingTerminalHostAction.PASTE_CLIPBOARD,
            SwingTerminalHostAction.SCROLL_PAGE_UP,
            SwingTerminalHostAction.SCROLL_PAGE_DOWN,
            -> true
        }

    /**
     * Performs [action] against this pane.
     *
     * @param action host-owned terminal pane action.
     * @return `true` when the action was handled by this pane.
     */
    fun performTerminalAction(action: SwingTerminalHostAction): Boolean =
        when (action) {
            SwingTerminalHostAction.COPY_SELECTION -> terminal.copySelectionToClipboard()
            SwingTerminalHostAction.PASTE_CLIPBOARD -> terminal.pasteClipboardText()
            SwingTerminalHostAction.OPEN_SEARCH -> {
                openSearch()
                true
            }
            SwingTerminalHostAction.SCROLL_PAGE_UP -> {
                terminal.scrollViewportBy(terminal.visibleGridSize().height.coerceAtLeast(1).toDouble())
                true
            }
            SwingTerminalHostAction.SCROLL_PAGE_DOWN -> {
                terminal.scrollViewportBy(-terminal.visibleGridSize().height.coerceAtLeast(1).toDouble())
                true
            }
        }

    /**
     * Shows the IDE-owned terminal context menu for a right-click request that
     * the reusable Swing terminal has decided belongs to terminal chrome.
     */
    fun showContextMenu(request: SwingTerminalContextMenuRequest): Boolean {
        val group = DefaultActionGroup()
        request.hyperlink?.let { link ->
            group.add(ContextMenuAction("Open Link") { link.open() })
            if (link.uri != null) {
                group.add(ContextMenuAction("Copy Link") { link.copyUri() })
            }
            group.add(Separator.getInstance())
        }

        group.add(terminalAction("Find", KetraTermTerminalActionIds.OPEN_SEARCH, enabled = true) { openSearch() })
        group.add(ContextMenuAction("New Tab", AllIcons.General.Add) { hostActions.openNewTab() })
        group.add(ContextMenuAction("Close Tab") { hostActions.closePane(tab) })
        group.add(Separator.getInstance())
        group.add(
            terminalAction(
                text = "Copy",
                registeredActionId = KetraTermTerminalActionIds.COPY_SELECTION,
                enabled = request.hasSelection(),
            ) {
                request.copySelection()
            },
        )
        group.add(
            terminalAction(
                text = "Paste",
                registeredActionId = KetraTermTerminalActionIds.PASTE_CLIPBOARD,
                enabled = true,
            ) {
                request.pasteClipboard()
            },
        )
        group.add(ContextMenuAction("Select All") { request.selectAll() })
        group.add(ContextMenuAction("Clear") { request.clearScreen() })
        group.add(Separator.getInstance())
        group.add(
            ContextMenuAction(
                text = "Open Terminal Here",
                enabled = hostActions.canOpenTerminalHere(tab),
            ) {
                hostActions.openTerminalHere(tab)
            },
        )

        val popup = ActionManager.getInstance().createActionPopupMenu(CONTEXT_MENU_PLACE, group)
        popup.component.show(request.terminal, request.x, request.y)
        return true
    }

    private fun terminalAction(
        text: String,
        registeredActionId: String,
        enabled: Boolean,
        perform: () -> Unit,
    ): AnAction {
        val action = ContextMenuAction(text = text, enabled = enabled, perform = perform)
        ActionManager.getInstance().getAction(registeredActionId)?.let(action::copyShortcutFrom)
        return action
    }

    /**
     * Unbinds the pane from its session before the containing IDE tab is disposed.
     */
    fun close() {
        searchBar.close()
        shortcutController?.dispose()
        shortcutController = null
        terminal.dispose()
    }

    companion object {
        /**
         * Creates and binds a pane for [tab].
         *
         * @param tab workspace tab whose session should be rendered.
         * @return bound terminal pane.
         */
        fun create(
            project: Project,
            tab: TerminalWorkspaceTab,
            hostActions: KetraTermTerminalPaneHostActions = KetraTermTerminalPaneHostActions.NONE,
        ): KetraTermTerminalPane {
            val scrollbar = JBScrollBar(Adjustable.VERTICAL)
            val scrollbarAdapter = SwingScrollbarAdapter(scrollbar)
            val shortcutControllerRef = arrayOfNulls<KetraTermTerminalShortcutController>(1)
            val paneRef = arrayOfNulls<KetraTermTerminalPane>(1)
            val terminal =
                SwingTerminal(
                    settingsProvider = { KetraTermIntellijSettings.current() },
                    hostServices =
                        SwingHostServices(
                            clipboardHandler = IntellijTerminalClipboardHandler,
                            hyperlinkDetector = IntellijTerminalHyperlinkDetector(project),
                            viewportListener = scrollbarAdapter,
                            scrollbarOverlayEnabled = false,
                            uiDispatcher = TerminalUiDispatcher { runnable ->
                                ApplicationManager.getApplication().invokeLater(runnable)
                            },
                            fontResolver = IntellijTerminalFontResolver,
                            hostKeyHandler = { event -> shortcutControllerRef[0]?.handleKeyPressed(event) == true },
                            contextMenuHandler = { request ->
                                paneRef[0]?.showContextMenu(request) == true
                            },
                        ),
                )
            scrollbarAdapter.attach(terminal)
            terminal.bind(tab.session)

            val searchBar = SwingTerminalSearchBar(terminal)
            val terminalArea = SwingTerminalOverlayPane(terminal, searchBar.component)
            val component =
                JPanel(BorderLayout()).apply {
                    border = null
                    background = terminal.background
                    terminal.border = null
                    add(terminalArea, BorderLayout.CENTER)
                    add(scrollbar, BorderLayout.EAST)
                }

            tab.session.notifyRenderDirty()
            return KetraTermTerminalPane(tab, terminal, component, searchBar, hostActions).also { pane ->
                pane.shortcutController = KetraTermTerminalShortcutController(pane)
                shortcutControllerRef[0] = pane.shortcutController
                paneRef[0] = pane
            }
        }

        private const val CONTEXT_MENU_PLACE = "KetraTerm.TerminalContextMenu"
    }
}

private class ContextMenuAction(
    text: String,
    icon: Icon? = null,
    private val enabled: Boolean = true,
    private val perform: () -> Unit,
) : DumbAwareAction(text, null, icon) {
    override fun actionPerformed(event: AnActionEvent) {
        if (enabled) perform()
    }

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = enabled
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}
