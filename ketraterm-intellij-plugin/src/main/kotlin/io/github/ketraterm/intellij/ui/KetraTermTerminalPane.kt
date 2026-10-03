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
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollBar
import com.intellij.util.ui.UIUtil
import io.github.ketraterm.intellij.services.KetraTermCompletionService
import io.github.ketraterm.intellij.services.captureCleanupFailure
import io.github.ketraterm.intellij.settings.KetraTermIntellijSettings
import io.github.ketraterm.ui.swing.api.*
import io.github.ketraterm.ui.swing.host.*
import io.github.ketraterm.ui.swing.settings.TerminalClipboardHandler
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionHandler
import io.github.ketraterm.workspace.TerminalWorkspaceTab
import kotlinx.coroutines.CancellationException
import java.awt.Adjustable
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.UIManager

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
    private val project: Project,
    private val completionBinding: SwingCompletionBinding,
    val clipboardReadPrompt: SwingClipboardReadPrompt,
) {
    private var closed = false
    private var completionService: KetraTermCompletionService? = null
    private val completionChanged: () -> Unit = { reconcileCompletion() }
    private var shortcutController: KetraTermTerminalShortcutController? = null

    /**
     * Requests keyboard focus for the terminal component.
     */
    fun requestFocus() {
        terminal.requestFocusInWindow()
    }

    /**
     * Applies the latest IntelliJ settings to this terminal pane.
     */
    fun reloadSettings() {
        terminal.reloadSettings()
        component.background = terminal.background
        searchBar.refreshColors(searchColors())
        tab.session.setHostPolicy(KetraTermIntellijSettings.getInstance().createHostPolicy())
        reconcileCompletion()
    }

    /**
     * Opens the IDE-hosted search UI for this terminal pane.
     */
    fun openSearch() {
        searchBar.open()
    }

    private fun reconcileCompletion() {
        if (closed) return
        val settings = KetraTermIntellijSettings.getInstance().state
        if (!settings.smartSuggestionsEnabled) {
            completionBinding.update(null, false)
            completionService?.releaseResources(tab)
            completionService?.removeResourceListener(completionChanged)
            completionService = null
            return
        }
        val service =
            completionService ?: KetraTermCompletionService.getInstance().also {
                completionService = it
                it.addResourceListener(completionChanged)
            }
        completionBinding.update(service.resourcesFor(project, tab), settings.shellSuggestionsEnabled)
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
            SwingTerminalHostAction.REQUEST_SUGGESTIONS ->
                KetraTermIntellijSettings.getInstance().state.smartSuggestionsEnabled &&
                    completionBinding.isEnabled
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
            SwingTerminalHostAction.REQUEST_SUGGESTIONS -> {
                if (isTerminalActionEnabled(action)) {
                    terminal.requestActiveShellSuggestions()
                    true
                } else {
                    false
                }
            }
            SwingTerminalHostAction.OPEN_SEARCH -> {
                openSearch()
                true
            }
            SwingTerminalHostAction.SCROLL_PAGE_UP -> {
                terminal.scrollViewportBy(
                    terminal
                        .visibleGridSize()
                        .height
                        .coerceAtLeast(1)
                        .toDouble(),
                )
                true
            }
            SwingTerminalHostAction.SCROLL_PAGE_DOWN -> {
                terminal.scrollViewportBy(
                    -terminal
                        .visibleGridSize()
                        .height
                        .coerceAtLeast(1)
                        .toDouble(),
                )
                true
            }
        }

    /**
     * Opens a new default terminal tab in this pane's tool window.
     */
    fun openNewTab(): Boolean = hostActions.openNewTab()

    /**
     * Prompts for a custom tab title; a blank name restores the shell's automatic title.
     */
    fun renameTab() {
        val title =
            Messages.showInputDialog(
                project,
                "Tab name (leave empty for automatic):",
                "Rename Terminal Tab",
                null,
                tab.customTitle ?: tab.title,
                null,
            ) ?: return
        if (!closed) {
            tab.customTitle = title.trim().takeIf(String::isNotEmpty)
        }
    }

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
            val providerGroup = (hyperlink.providerAction as? IntellijTerminalHyperlinkAction)?.popupGroup(request.triggerEvent)
            if (providerGroup != null) group.add(providerGroup)
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
        group.addRegisteredAction(actionManager, KetraTermTerminalActionIds.RENAME_TAB)
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
        if (closed) return
        closed = true
        val service = completionService
        completionService = null
        val shortcuts = shortcutController
        shortcutController = null
        var failure: Throwable? = null
        failure = captureCleanupFailure(failure, clipboardReadPrompt::close)
        failure = captureCleanupFailure(failure) { service?.releaseResources(tab) }
        failure = captureCleanupFailure(failure) { service?.removeResourceListener(completionChanged) }
        failure = captureCleanupFailure(failure, completionBinding::close)
        failure = captureCleanupFailure(failure, searchBar::close)
        failure = captureCleanupFailure(failure) { shortcuts?.dispose() }
        failure = captureCleanupFailure(failure, terminal::dispose)
        failure?.let { throw it }
    }

    companion object {
        private fun searchColors(): SwingTerminalSearchColors =
            SwingTerminalSearchColors.create {
                val foreground = UIUtil.getLabelForeground()
                val background = UIUtil.getPanelBackground()
                val accent =
                    UIManager.getColor("Component.focusColor")
                        ?: JBColor(0x3574F0, 0x548AF7)
                it.panelBackground = background
                it.panelBorder = JBColor.border()
                it.foreground = foreground
                it.counterForeground = UIUtil.getContextHelpForeground()
                it.textFieldBackground = UIManager.getColor("TextField.background") ?: background
                it.textFieldBorder = JBColor.border()
                it.textFieldFocusBorder = accent
                it.textFieldPlaceholder = UIUtil.getContextHelpForeground()
                it.searchIconForeground = foreground
                it.buttonHoverBackground = UIManager.getColor("ActionButton.hoverBackground") ?: background
                it.buttonPressedBackground = UIManager.getColor("ActionButton.pressedBackground") ?: accent
                it.buttonSelectedBackground = UIUtil.getListSelectionBackground(true)
                it.buttonSelectedForeground = UIUtil.getListSelectionForeground(true)
            }

        private val LOG = Logger.getInstance(KetraTermTerminalPane::class.java)

        internal fun reportShellSuggestionFailure(failure: Exception) {
            if (failure is CancellationException) throw failure
            // Translate platform control flow at the coroutine boundary; it is not an operational failure.
            if (failure is ControlFlowException) {
                throw CancellationException("IDE shell suggestions cancelled").apply { initCause(failure) }
            }
            LOG.warn("Shell suggestion provider failed", failure)
        }

        /**
         * Creates and binds a pane for [tab].
         * Until return, this factory owns acquired UI resources and releases them
         * on failure. The caller retains ownership of the workspace session.
         *
         * @param tab workspace tab whose session should be rendered.
         * @return bound terminal pane.
         */
        fun create(
            project: Project,
            tab: TerminalWorkspaceTab,
            clipboard: TerminalClipboardHandler,
            hostActions: KetraTermTerminalPaneHostActions = KetraTermTerminalPaneHostActions.NONE,
        ): KetraTermTerminalPane {
            val completionBinding = SwingCompletionBinding(tab.session) { tab.currentWorkingDirectoryUri }
            val scrollbar = JBScrollBar(Adjustable.VERTICAL)
            val scrollbarAdapter = SwingScrollbarAdapter(scrollbar)
            val shortcutControllerRef = arrayOfNulls<KetraTermTerminalShortcutController>(1)
            val paneRef = arrayOfNulls<KetraTermTerminalPane>(1)
            val terminal =
                SwingTerminal(
                    settingsProvider = {
                        KetraTermIntellijSettings.current().copy { draft ->
                            draft.osc8HyperlinkPresentation = intellijOsc8HyperlinkPresentation()
                        }
                    },
                    hostServices =
                        SwingHostServices.create { draft ->
                            draft.clipboardHandler = clipboard
                            draft.hyperlinkDetector =
                                IntellijTerminalHyperlinkDetector(
                                    project,
                                    tab.profile.workingDirectory ?: java.nio.file.Path
                                        .of(System.getProperty("user.home")),
                                ) { lineId ->
                                    val state = tab.session.shellIntegrationState
                                    if (lineId > 0L) state.commandWorkingDirectoryUri(state.commandRecordIdAtLine(lineId)) else null
                                }
                            draft.viewportListener = scrollbarAdapter
                            draft.scrollbarOverlayEnabled = false
                            draft.shellSuggestionProvider = completionBinding.provider
                            draft.shellSuggestionHandler = SwingShellSuggestionHandler.createDefault(tab.session)
                            draft.shellSuggestionFeedbackHandler = completionBinding.feedbackHandler
                            draft.shellSuggestionKeymap = KetraTermShellSuggestionKeymap
                            draft.shellSuggestionViewFactory = IntellijCompletionListViewFactory
                            draft.uiDispatcher =
                                TerminalUiDispatcher { runnable ->
                                    ApplicationManager.getApplication().invokeLater(runnable)
                                }
                            draft.fontResolver = IntellijTerminalFontResolver
                            draft.hostKeyHandler = { event -> shortcutControllerRef[0]?.handleKeyPressed(event) == true }
                            draft.contextMenuHandler =
                                SwingTerminalContextMenuHandler { request ->
                                    paneRef[0]?.showContextMenu(request) == true
                                }
                        },
                ).apply {
                    setShellSuggestionFailureHandler { _, failure ->
                        reportShellSuggestionFailure(failure)
                    }
                }
            var searchBar: SwingTerminalSearchBar? = null
            var clipboardReadPrompt: SwingClipboardReadPrompt? = null
            var pane: KetraTermTerminalPane? = null
            try {
                scrollbarAdapter.attach(terminal)
                terminal.bind(tab.session)
                searchBar = SwingTerminalSearchBar(terminal).apply { refreshColors(searchColors()) }
                clipboardReadPrompt =
                    SwingClipboardReadPrompt { message, decide ->
                        IntellijMessageDialogs.showModeless(project, message, decide)
                    }
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
                val created =
                    KetraTermTerminalPane(
                        tab = tab,
                        terminal = terminal,
                        component = component,
                        searchBar = searchBar,
                        hostActions = hostActions,
                        project = project,
                        completionBinding = completionBinding,
                        clipboardReadPrompt = clipboardReadPrompt,
                    )
                pane = created
                created.shortcutController = KetraTermTerminalShortcutController(created)
                shortcutControllerRef[0] = created.shortcutController
                paneRef[0] = created
                completionBinding.attach(terminal)
                created.reconcileCompletion()
                return created
            } catch (failure: Throwable) {
                val created = pane
                if (created != null) {
                    captureCleanupFailure(failure, created::close)
                } else {
                    captureCleanupFailure(failure) { clipboardReadPrompt?.close() }
                    captureCleanupFailure(failure, completionBinding::close)
                    captureCleanupFailure(failure) { searchBar?.close() }
                    captureCleanupFailure(failure, terminal::dispose)
                }
                throw failure
            }
        }
    }
}

private fun DefaultActionGroup.addRegisteredAction(
    actionManager: ActionManager,
    actionId: String,
) {
    add(actionManager.getAction(actionId) ?: return)
}
