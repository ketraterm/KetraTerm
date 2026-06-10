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
package com.gagik.terminal.standalone.ui

import com.gagik.terminal.standalone.config.StandaloneTerminalSettings
import com.gagik.terminal.ui.swing.api.TerminalSwingHostServices
import com.gagik.terminal.ui.swing.api.TerminalSwingTerminal
import com.gagik.terminal.workspace.TerminalWorkspaceTab
import java.awt.Adjustable
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.JScrollBar
import javax.swing.border.EmptyBorder

/**
 * Owns one terminal pane in the standalone host.
 *
 * A pane binds a workspace tab's session to one reusable Swing terminal
 * component and owns pane-local viewport chrome such as the scrollbar.
 */
internal class LatticeTerminalPane private constructor(
    val tab: TerminalWorkspaceTab,
    val terminal: TerminalSwingTerminal,
    val component: JPanel,
) {
    fun requestFocus() {
        terminal.requestFocusInWindow()
    }

    fun reloadSettings() {
        terminal.reloadSettings()
    }

    fun close() {
        terminal.unbind()
    }

    internal companion object {
        fun create(
            tab: TerminalWorkspaceTab,
            settings: StandaloneTerminalSettings,
        ): LatticeTerminalPane {
            val scrollbar = JScrollBar(Adjustable.VERTICAL)
            val scrollbarAdapter = TerminalScrollbarAdapter(scrollbar)
            val terminal =
                TerminalSwingTerminal(
                    settingsProvider = { settings.current() },
                    hostServices =
                        TerminalSwingHostServices(
                            viewportListener = scrollbarAdapter,
                        ),
                )
            scrollbarAdapter.attach(terminal)
            configureScrollbar(scrollbar)

            terminal.bind(tab.session)

            return LatticeTerminalPane(
                tab = tab,
                terminal = terminal,
                component = terminalPanel(terminal, scrollbar),
            ).also {
                tab.session.notifyRenderDirty()
            }
        }

        private fun terminalPanel(
            terminal: TerminalSwingTerminal,
            scrollbar: JScrollBar,
        ): JPanel =
            JPanel(BorderLayout()).apply {
                background = LatticeChrome.TERMINAL_BACKGROUND
                border = EmptyBorder(6, 10, 10, 10)
                terminal.border = null
                add(terminal, BorderLayout.CENTER)
                add(scrollbar, BorderLayout.EAST)
            }

        private fun configureScrollbar(scrollbar: JScrollBar) {
            scrollbar.unitIncrement = 1
            scrollbar.blockIncrement = 8
            scrollbar.preferredSize = LatticeChrome.SCROLLBAR_SIZE
            scrollbar.ui = LatticeScrollBarUi()
            scrollbar.isVisible = false
            scrollbar.isFocusable = false
        }
    }
}
