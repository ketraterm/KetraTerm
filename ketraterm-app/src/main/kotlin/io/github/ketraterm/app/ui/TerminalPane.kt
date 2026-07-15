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
import io.github.ketraterm.ui.swing.api.SwingHostServices
import io.github.ketraterm.ui.swing.api.SwingTerminal
import io.github.ketraterm.ui.swing.api.SwingTerminalContextMenuHandler
import io.github.ketraterm.ui.swing.api.SwingTerminalContextMenuRequest
import io.github.ketraterm.ui.swing.host.SwingTerminalOverlayPane
import io.github.ketraterm.ui.swing.host.SwingTerminalSearchBar
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionProvider
import io.github.ketraterm.workspace.TerminalWorkspaceTab
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
            onContextMenu: (TerminalPane, SwingTerminalContextMenuRequest) -> Unit,
        ): TerminalPane {
            val shortcutControllerRef = arrayOfNulls<TerminalPaneShortcutController>(1)
            val paneRef = arrayOfNulls<TerminalPane>(1)
            val terminal =
                SwingTerminal(
                    settingsProvider = { settings.current() },
                    hostServices =
                        SwingHostServices(
                            shellSuggestionProvider = suggestionProvider,
                            hostKeyHandler = { event -> shortcutControllerRef[0]?.handleKeyPressed(event) == true },
                            contextMenuHandler =
                                SwingTerminalContextMenuHandler { request ->
                                    val pane = paneRef[0] ?: return@SwingTerminalContextMenuHandler false
                                    onContextMenu(pane, request)
                                    true
                                },
                        ),
                )

            terminal.bind(tab.session)

            val searchBar = SwingTerminalSearchBar(terminal)
            val component = terminalPanel(terminal, searchBar)
            val pane =
                TerminalPane(
                    tab = tab,
                    terminal = terminal,
                    component = component,
                    settings = settings,
                    searchBar = searchBar,
                )
            pane.shortcutController = TerminalPaneShortcutController(pane, settings)
            shortcutControllerRef[0] = pane.shortcutController
            paneRef[0] = pane

            tab.session.requestRender(scrollbackOffset = 0)
            return pane
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
