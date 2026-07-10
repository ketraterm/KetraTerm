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
import io.github.ketraterm.ui.swing.api.SwingScrollbarAdapter
import io.github.ketraterm.ui.swing.api.SwingTerminal
import io.github.ketraterm.ui.swing.suggestion.*
import io.github.ketraterm.workspace.TerminalWorkspaceTab
import java.awt.Adjustable
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.JScrollBar

/**
 * Owns one terminal pane in the standalone host.
 *
 * A pane binds a workspace tab's session to one reusable Swing terminal
 * component and owns pane-local viewport chrome such as the scrollbar.
 */
internal class TerminalPane private constructor(
    val tab: TerminalWorkspaceTab,
    val terminal: SwingTerminal,
    val component: JPanel,
    private val settings: KetraTermSettings,
    private val completionTriggerController: StandaloneCompletionTriggerController,
    private val completionDirtyListener: () -> Unit,
) {
    fun requestFocus() {
        terminal.requestFocusInWindow()
    }

    fun reloadSettings() {
        terminal.reloadSettings()
        component.background = terminal.background
        tab.session.setHostPolicy(settings.createHostPolicy(tab.profile.command))
        if (settings.shellSuggestionsEnabled) {
            completionTriggerController.scheduleRefresh()
        } else {
            completionTriggerController.cancelAndHide()
        }
    }

    fun close() {
        tab.session.removeDirtyListener(completionDirtyListener)
        completionTriggerController.cancelAndHide()
        terminal.unbind()
    }

    internal companion object {
        fun create(
            tab: TerminalWorkspaceTab,
            settings: KetraTermSettings,
            suggestionProvider: SwingShellSuggestionProvider = SwingShellSuggestionProvider.NONE,
            suggestionFeedbackHandler: SwingShellSuggestionFeedbackHandler = SwingShellSuggestionFeedbackHandler.NONE,
            commandSpecs: List<TerminalCommandSpec> = TerminalCommandSpecs.defaults(),
            shellCapabilities: TerminalShellCapabilities = TerminalShellCapabilities.PLAIN,
            onContextMenu: (TerminalPane, Int, Int) -> Unit,
        ): TerminalPane {
            val scrollbar = JScrollBar(Adjustable.VERTICAL)
            val scrollbarAdapter = SwingScrollbarAdapter(scrollbar)
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
                        val resultText = acceptance.suggestion.commandTextAfterReplacement(request)
                        if (resultText != null) {
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
                            viewportListener = scrollbarAdapter,
                            shellSuggestionProvider = suggestionProvider,
                            shellSuggestionHandler = shellSuggestionHandler,
                            shellSuggestionFeedbackHandler = wrappedFeedbackHandler,
                        ),
                )
            terminalRef = terminal

            scrollbarAdapter.attach(terminal)
            configureScrollbar(scrollbar)

            terminal.bind(tab.session)
            val completionDirtyListener = completionTriggerController::scheduleRefresh
            tab.session.addDirtyListener(completionDirtyListener)

            val pane =
                TerminalPane(
                    tab = tab,
                    terminal = terminal,
                    component = terminalPanel(terminal, scrollbar),
                    settings = settings,
                    completionTriggerController = completionTriggerController,
                    completionDirtyListener = completionDirtyListener,
                )

            terminal.addMouseListener(
                object : java.awt.event.MouseAdapter() {
                    override fun mousePressed(e: java.awt.event.MouseEvent) {
                        if (e.isPopupTrigger) {
                            onContextMenu(pane, e.x, e.y)
                        }
                    }

                    override fun mouseReleased(e: java.awt.event.MouseEvent) {
                        if (e.isPopupTrigger) {
                            onContextMenu(pane, e.x, e.y)
                        }
                    }
                },
            )
            terminal.addFocusListener(
                object : java.awt.event.FocusAdapter() {
                    override fun focusLost(e: java.awt.event.FocusEvent) {
                        completionTriggerController.cancelAndHide()
                    }
                },
            )

            tab.session.notifyRenderDirty()
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
            scrollbar: JScrollBar,
        ): JPanel =
            JPanel(BorderLayout()).apply {
                background = terminal.background
                border = null
                terminal.border = null
                add(terminal, BorderLayout.CENTER)
                add(scrollbar, BorderLayout.EAST)
            }

        private fun configureScrollbar(scrollbar: JScrollBar) {
            scrollbar.unitIncrement = 1
            scrollbar.blockIncrement = 8
            scrollbar.preferredSize = Chrome.scrollbarSize
            scrollbar.ui = ScrollBarUi()
            scrollbar.isVisible = false
            scrollbar.isFocusable = false
            scrollbar.isOpaque = false
        }
    }
}
