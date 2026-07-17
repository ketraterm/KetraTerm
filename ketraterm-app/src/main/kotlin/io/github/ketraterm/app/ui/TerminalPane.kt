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

import io.github.ketraterm.app.completion.StandaloneCompletionTriggerController
import io.github.ketraterm.app.config.KetraTermSettings
import io.github.ketraterm.completion.api.TerminalShellCapabilities
import io.github.ketraterm.completion.model.TerminalCommandSpec
import io.github.ketraterm.completion.model.TerminalCommandSpecs
import io.github.ketraterm.session.TerminalShellCommandLineSnapshot
import io.github.ketraterm.ui.swing.api.SwingHostServices
import io.github.ketraterm.ui.swing.api.SwingTerminal
import io.github.ketraterm.ui.swing.api.SwingTerminalContextMenuHandler
import io.github.ketraterm.ui.swing.api.SwingTerminalContextMenuRequest
import io.github.ketraterm.ui.swing.host.SwingTerminalOverlayPane
import io.github.ketraterm.ui.swing.host.SwingTerminalSearchBar
import io.github.ketraterm.ui.swing.suggestion.*
import io.github.ketraterm.workspace.TerminalWorkspaceTab
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import javax.swing.JPanel

/**
 * Owns one terminal pane in the standalone host.
 *
 * A pane binds a workspace tab's session to one reusable Swing terminal
 * component.
 */
internal class TerminalPane private constructor(
    val tab: TerminalWorkspaceTab,
    val terminal: SwingTerminal,
    val component: JPanel,
    private val settings: KetraTermSettings,
    private val completionTriggerController: StandaloneCompletionTriggerController,
    private val completionObservationJob: Job,
    private val searchBar: SwingTerminalSearchBar,
) : TerminalPaneActionTarget {
    private var shortcutController: TerminalPaneShortcutController? = null

    fun requestFocus() {
        terminal.requestFocusInWindow()
    }

    fun reloadSettings() {
        terminal.reloadSettings()
        component.background = terminal.background
        searchBar.refreshColors()
        tab.session.setHostPolicy(settings.createHostPolicy(tab.profile.command))
        if (settings.shellSuggestionsEnabled) {
            completionTriggerController.scheduleRefresh()
        } else {
            completionTriggerController.cancelAndHide()
        }
    }

    /** Re-evaluates the latest command after a background completion snapshot is published. */
    fun completionSourceSnapshotChanged() {
        completionTriggerController.sourceSnapshotChanged()
    }

    override fun hasSelection(): Boolean = terminal.currentSelection() != null

    override fun copySelectionToClipboard(): Boolean = terminal.copySelectionToClipboard()

    override fun pasteClipboardText(): Boolean = terminal.pasteClipboardText()

    override fun selectAll(): Boolean = terminal.selectAll()

    override fun clearScreen(): Boolean = terminal.clearScreen()

    override fun openSearch() {
        searchBar.open()
    }

    override fun scrollPageUp() {
        terminal.scrollViewportBy(
            terminal
                .visibleGridSize()
                .height
                .coerceAtLeast(1)
                .toDouble(),
        )
    }

    override fun scrollPageDown() {
        terminal.scrollViewportBy(
            -terminal
                .visibleGridSize()
                .height
                .coerceAtLeast(1)
                .toDouble(),
        )
    }

    fun close() {
        completionObservationJob.cancel()
        completionTriggerController.cancelAndHide()
        searchBar.close()
        shortcutController?.dispose()
        shortcutController = null
        terminal.dispose()
    }

    internal companion object {
        fun create(
            tab: TerminalWorkspaceTab,
            settings: KetraTermSettings,
            suggestionProvider: SwingShellSuggestionProvider = SwingShellSuggestionProvider.NONE,
            suggestionFeedbackHandler: SwingShellSuggestionFeedbackHandler = SwingShellSuggestionFeedbackHandler.NONE,
            commandSpecs: List<TerminalCommandSpec> = TerminalCommandSpecs.defaults(),
            shellCapabilities: TerminalShellCapabilities = TerminalShellCapabilities.PLAIN,
            onContextMenu: (TerminalPane, SwingTerminalContextMenuRequest) -> Unit,
        ): TerminalPane {
            val shortcutControllerRef = arrayOfNulls<TerminalPaneShortcutController>(1)
            val paneRef = arrayOfNulls<TerminalPane>(1)
            lateinit var terminalRef: SwingTerminal
            val completionTriggerController =
                StandaloneCompletionTriggerController(
                    activeCommandLine = tab.session::activeShellCommandLine,
                    requestSuggestions = { snapshot -> terminalRef.requestShellSuggestionsForSnapshot(snapshot) },
                    hideSuggestions = { terminalRef.hideShellSuggestions() },
                    rankingContextKey = { tab.currentWorkingDirectoryUri },
                    suggestionsEnabled = { settings.shellSuggestionsEnabled },
                    commandSpecs = commandSpecs,
                    shellCapabilities = shellCapabilities,
                )
            val defaultHandler = SwingShellSuggestionHandler.createDefault(tab.session)
            val shellSuggestionHandler =
                SwingShellSuggestionHandler { acceptance ->
                    val request = acceptance.request
                    val replacement = acceptance.suggestion.replacementFor(request)
                    if (replacement != null) {
                        acceptance.suggestion.commandTextAfterReplacement(request)?.let { resultText ->
                            val resultCursor = replacement.startOffset + replacement.replacementText.length
                            completionTriggerController.suppressNextTriggerFor(resultText, resultCursor)
                        }
                    }
                    defaultHandler.onSuggestionAccepted(acceptance)
                }
            val wrappedFeedbackHandler =
                SwingShellSuggestionFeedbackHandler { feedback ->
                    completionTriggerController.invalidateLastRequest()
                    suggestionFeedbackHandler.onSuggestionFeedback(feedback)
                }
            val terminal =
                SwingTerminal(
                    settingsProvider = { settings.current() },
                    hostServices =
                        SwingHostServices(
                            shellSuggestionProvider = suggestionProvider,
                            shellSuggestionHandler = shellSuggestionHandler,
                            shellSuggestionFeedbackHandler = wrappedFeedbackHandler,
                            hostKeyHandler = { event -> shortcutControllerRef[0]?.handleKeyPressed(event) == true },
                            contextMenuHandler =
                                SwingTerminalContextMenuHandler { request ->
                                    val pane = paneRef[0] ?: return@SwingTerminalContextMenuHandler false
                                    onContextMenu(pane, request)
                                    true
                                },
                        ),
                )
            terminalRef = terminal

            terminal.bind(tab.session)
            val completionObservationJob =
                CoroutineScope(Dispatchers.Default + CoroutineName("completion-${tab.id}"))
                    .launch {
                        tab.session.renderGeneration.collect {
                            completionTriggerController.scheduleRefresh()
                        }
                    }

            val searchBar = SwingTerminalSearchBar(terminal)
            val component = terminalPanel(terminal, searchBar)
            val pane =
                TerminalPane(
                    tab = tab,
                    terminal = terminal,
                    component = component,
                    settings = settings,
                    completionTriggerController = completionTriggerController,
                    completionObservationJob = completionObservationJob,
                    searchBar = searchBar,
                )
            pane.shortcutController = TerminalPaneShortcutController(pane, settings)
            shortcutControllerRef[0] = pane.shortcutController
            paneRef[0] = pane

            terminal.addFocusListener(
                object : java.awt.event.FocusAdapter() {
                    override fun focusLost(e: java.awt.event.FocusEvent) {
                        completionTriggerController.cancelAndHide()
                    }
                },
            )
            tab.session.requestRender(scrollbackOffset = 0)
            return pane
        }

        private fun SwingTerminal.requestShellSuggestionsForSnapshot(snapshot: TerminalShellCommandLineSnapshot) {
            requestShellSuggestions(
                commandText = snapshot.commandText,
                cursorOffset = snapshot.cursorOffset,
                anchorColumn = snapshot.cursorColumn,
                anchorRow = snapshot.cursorRow,
            )
        }

        private fun terminalPanel(
            terminal: SwingTerminal,
            searchBar: SwingTerminalSearchBar,
        ): JPanel =
            SwingTerminalOverlayPane(terminal, searchBar.component).apply {
                background = terminal.background
                border = null
                terminal.border = null
            }
    }
}
