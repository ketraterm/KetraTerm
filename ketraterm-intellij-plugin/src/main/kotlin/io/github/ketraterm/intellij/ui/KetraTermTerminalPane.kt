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

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollBar
import io.github.ketraterm.intellij.services.IntellijCompletionSession
import io.github.ketraterm.intellij.services.KetraTermCompletionService
import io.github.ketraterm.intellij.settings.KetraTermIntellijSettings
import io.github.ketraterm.ui.swing.api.*
import io.github.ketraterm.ui.swing.host.SwingTerminalHostAction
import io.github.ketraterm.ui.swing.host.SwingTerminalOverlayPane
import io.github.ketraterm.ui.swing.host.SwingTerminalSearchBar
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionFeedbackHandler
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionHandler
import io.github.ketraterm.ui.swing.suggestion.commandTextAfterReplacement
import io.github.ketraterm.ui.swing.suggestion.replacementFor
import io.github.ketraterm.workspace.TerminalWorkspaceTab
import kotlinx.coroutines.*
import java.awt.Adjustable
import java.awt.BorderLayout
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
    private val completionSession: IntellijCompletionSession,
    private val completionTriggerController: IntellijCompletionTriggerController,
    private val completionScope: CoroutineScope,
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
    fun isTerminalActionEnabled(
        action: SwingTerminalHostAction,
        fromContextMenu: Boolean = false,
    ): Boolean =
        when (action) {
            SwingTerminalHostAction.COPY_SELECTION -> terminal.currentSelection() != null
            SwingTerminalHostAction.OPEN_SEARCH -> fromContextMenu || KetraTermIntellijSettings.getInstance().overrideIdeShortcuts()
            SwingTerminalHostAction.SELECT_ALL,
            SwingTerminalHostAction.CLEAR_SCREEN,
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
            SwingTerminalHostAction.SELECT_ALL -> terminal.selectAll()
            SwingTerminalHostAction.CLEAR_SCREEN -> terminal.clearScreen()
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
     * Opens a new default terminal tab in this pane's tool window.
     */
    fun openNewTab(): Boolean = hostActions.openNewTab()

    /**
     * Returns whether "Open Terminal Here" can run for this pane.
     */
    fun canOpenTerminalHere(): Boolean = hostActions.canOpenTerminalHere(tab)

    /**
     * Opens a new terminal rooted at this pane's current local OSC 7 directory.
     */
    fun openTerminalHere(): Boolean = hostActions.openTerminalHere(tab)

    /**
     * Closes this pane through the owning IntelliJ content manager.
     */
    fun closePane() = hostActions.closePane(tab)

    /**
     * Shows the IntelliJ-native context menu for this terminal pane.
     */
    fun showContextMenu(request: SwingTerminalContextMenuRequest): Boolean {
        val actionManager = ActionManager.getInstance()
        val group = DefaultActionGroup()
        val hyperlink = request.hyperlink
        if (hyperlink != null) {
            group.add(
                object : DumbAwareAction("Open Link") {
                    override fun actionPerformed(event: com.intellij.openapi.actionSystem.AnActionEvent) {
                        hyperlink.open()
                    }

                    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
                },
            )
            group.add(
                object : DumbAwareAction("Copy Link") {
                    override fun actionPerformed(event: com.intellij.openapi.actionSystem.AnActionEvent) {
                        hyperlink.copyUri()
                    }

                    override fun update(event: com.intellij.openapi.actionSystem.AnActionEvent) {
                        event.presentation.isEnabled = hyperlink.uri != null
                    }

                    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
                },
            )
            group.add(Separator.getInstance())
        }

        group.addRegisteredAction(actionManager, KetraTermTerminalActionIds.OPEN_SEARCH)
        group.addRegisteredAction(actionManager, KetraTermTerminalActionIds.NEW_TAB)
        group.addRegisteredAction(actionManager, KetraTermTerminalActionIds.CLOSE_TAB)
        group.add(Separator.getInstance())
        group.addRegisteredAction(actionManager, KetraTermTerminalActionIds.COPY_SELECTION)
        group.addRegisteredAction(actionManager, KetraTermTerminalActionIds.PASTE_CLIPBOARD)
        group.addRegisteredAction(actionManager, KetraTermTerminalActionIds.SELECT_ALL)
        group.add(Separator.getInstance())
        group.addRegisteredAction(actionManager, KetraTermTerminalActionIds.CLEAR_SCREEN)
        group.addRegisteredAction(actionManager, KetraTermTerminalActionIds.OPEN_TERMINAL_HERE)
        group.add(Separator.getInstance())
        group.addRegisteredAction(actionManager, KetraTermTerminalActionIds.SCROLL_PAGE_UP)
        group.addRegisteredAction(actionManager, KetraTermTerminalActionIds.SCROLL_PAGE_DOWN)

        val popup =
            actionManager
                .createActionPopupMenu(KetraTermTerminalActionIds.CONTEXT_MENU_PLACE, group)
                .component
        KetraTermTerminalPopupContext.install(popup, this)
        popup.show(request.terminal, request.x, request.y)
        return true
    }

    /**
     * Unbinds the pane from its session before the containing IDE tab is disposed.
     */
    fun close() {
        completionSession.onSourceChanged(null)
        completionTriggerController.cancelAndHide()
        completionScope.cancel()
        completionSession.close()
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
            val completionSession = KetraTermCompletionService.getInstance().openSession(project, tab)
            val completionScope =
                CoroutineScope(
                    SupervisorJob() +
                            Dispatchers.Default +
                            CoroutineName("intellij-completion-${tab.id}"),
                )
            return try {
                createBound(project, tab, hostActions, completionSession, completionScope)
            } catch (failure: Throwable) {
                completionScope.cancel()
                completionSession.close()
                throw failure
            }
        }

        private fun createBound(
            project: Project,
            tab: TerminalWorkspaceTab,
            hostActions: KetraTermTerminalPaneHostActions,
            completionSession: IntellijCompletionSession,
            completionScope: CoroutineScope,
        ): KetraTermTerminalPane {
            val scrollbar = JBScrollBar(Adjustable.VERTICAL)
            val scrollbarAdapter = SwingScrollbarAdapter(scrollbar)
            val shortcutControllerRef = arrayOfNulls<KetraTermTerminalShortcutController>(1)
            val paneRef = arrayOfNulls<KetraTermTerminalPane>(1)
            lateinit var terminalRef: SwingTerminal
            val completionTriggerController =
                IntellijCompletionTriggerController(
                    activeCommandLine = tab.session::activeShellCommandLine,
                    requestSuggestions = { snapshot -> terminalRef.requestShellSuggestionsForSnapshot(snapshot) },
                    hideSuggestions = { terminalRef.hideShellSuggestions() },
                    rankingContextKey = { tab.currentWorkingDirectoryUri },
                    suggestionsEnabled = { KetraTermIntellijSettings.current().shellSuggestionsEnabled },
                    scheduler =
                        CoroutineIntellijCompletionTriggerScheduler(completionScope) { action ->
                            ApplicationManager.getApplication().invokeLater(action)
                        },
                    commandSpecs = completionSession.commandSpecs,
                    shellCapabilities = completionSession.shellCapabilities,
                )
            val defaultSuggestionHandler = SwingShellSuggestionHandler.createDefault(tab.session)
            val suggestionHandler =
                SwingShellSuggestionHandler { acceptance ->
                    val request = acceptance.request
                    val replacement = acceptance.suggestion.replacementFor(request)
                    if (replacement != null) {
                        acceptance.suggestion.commandTextAfterReplacement(request)?.let { resultText ->
                            val resultCursor = replacement.startOffset + replacement.replacementText.length
                            completionTriggerController.suppressNextTriggerFor(resultText, resultCursor)
                        }
                    }
                    defaultSuggestionHandler.onSuggestionAccepted(acceptance)
                }
            val feedbackHandler =
                SwingShellSuggestionFeedbackHandler { feedback ->
                    completionTriggerController.invalidateLastRequest()
                    completionSession.feedbackHandler.onSuggestionFeedback(feedback)
                }
            val terminal =
                SwingTerminal(
                    settingsProvider = { KetraTermIntellijSettings.current() },
                    hostServices =
                        SwingHostServices(
                            clipboardHandler = IntellijTerminalClipboardHandler,
                            hyperlinkDetector = IntellijTerminalHyperlinkDetector(project),
                            viewportListener = scrollbarAdapter,
                            scrollbarOverlayEnabled = false,
                            shellSuggestionProvider = completionSession.provider,
                            shellSuggestionHandler = suggestionHandler,
                            shellSuggestionFeedbackHandler = feedbackHandler,
                            shellSuggestionKeymap = KetraTermShellSuggestionKeymap,
                            uiDispatcher = TerminalUiDispatcher { runnable ->
                                ApplicationManager.getApplication().invokeLater(runnable)
                            },
                            fontResolver = IntellijTerminalFontResolver,
                            hostKeyHandler = { event -> shortcutControllerRef[0]?.handleKeyPressed(event) == true },
                            contextMenuHandler =
                                SwingTerminalContextMenuHandler { request ->
                                    paneRef[0]?.showContextMenu(request) == true
                                },
                        ),
                )
            terminalRef = terminal
            scrollbarAdapter.attach(terminal)
            terminal.bind(tab.session)

            completionSession.onSourceChanged(completionTriggerController::sourceSnapshotChanged)
            completionScope.launch {
                tab.session.renderGeneration.collect {
                    completionTriggerController.scheduleRefresh()
                }
            }

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

            tab.session.requestRender(scrollbackOffset = 0)
            return KetraTermTerminalPane(
                tab = tab,
                terminal = terminal,
                component = component,
                searchBar = searchBar,
                hostActions = hostActions,
                completionSession = completionSession,
                completionTriggerController = completionTriggerController,
                completionScope = completionScope,
            ).also { pane ->
                pane.shortcutController = KetraTermTerminalShortcutController(pane)
                shortcutControllerRef[0] = pane.shortcutController
                paneRef[0] = pane
                terminal.addFocusListener(
                    object : java.awt.event.FocusAdapter() {
                        override fun focusLost(event: java.awt.event.FocusEvent) {
                            completionTriggerController.cancelAndHide()
                        }
                    },
                )
            }
        }

        private fun SwingTerminal.requestShellSuggestionsForSnapshot(
            snapshot: io.github.ketraterm.session.TerminalShellCommandLineSnapshot,
        ) {
            requestShellSuggestions(
                commandText = snapshot.commandText,
                cursorOffset = snapshot.cursorOffset,
                anchorColumn = snapshot.cursorColumn,
                anchorRow = snapshot.cursorRow,
            )
        }
    }
}

private fun DefaultActionGroup.addRegisteredAction(
    actionManager: ActionManager,
    actionId: String,
) {
    add(actionManager.getAction(actionId) ?: return)
}
