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
package io.github.jvterm.intellij.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.components.JBScrollBar
import io.github.jvterm.intellij.settings.JvTermIntellijSettings
import io.github.jvterm.ui.swing.api.SwingHostServices
import io.github.jvterm.ui.swing.api.SwingScrollbarAdapter
import io.github.jvterm.ui.swing.api.SwingTerminal
import io.github.jvterm.ui.swing.api.TerminalUiDispatcher
import io.github.jvterm.workspace.TerminalWorkspaceTab
import java.awt.Adjustable
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * IntelliJ-hosted pane that binds one workspace tab to one reusable terminal component.
 *
 * This class owns only IDE-side Swing assembly. Painting, selection, input
 * mapping, render-cache consumption, and session mutation remain in reusable
 * JvTerm modules.
 */
internal class JvTermTerminalPane private constructor(
    val terminal: SwingTerminal,
    val component: JPanel,
) {
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
    }

    /**
     * Unbinds the pane from its session before the containing IDE tab is disposed.
     */
    fun close() {
        terminal.unbind()
    }

    companion object {
        /**
         * Creates and binds a pane for [tab].
         *
         * @param tab workspace tab whose session should be rendered.
         * @return bound terminal pane.
         */
        fun create(tab: TerminalWorkspaceTab): JvTermTerminalPane {
            val scrollbar = JBScrollBar(Adjustable.VERTICAL)
            val scrollbarAdapter = SwingScrollbarAdapter(scrollbar)

            val terminal =
                SwingTerminal(
                    settingsProvider = { JvTermIntellijSettings.current() },
                    hostServices =
                        SwingHostServices(
                            viewportListener = scrollbarAdapter,
                            uiDispatcher = TerminalUiDispatcher { runnable ->
                                ApplicationManager.getApplication().invokeLater(runnable)
                            },
                        ),
                )
            scrollbarAdapter.attach(terminal)
            terminal.bind(tab.session)

            configureScrollbar(scrollbar)

            val component =
                JPanel(BorderLayout()).apply {
                    border = null
                    background = terminal.background
                    terminal.border = null
                    add(terminal, BorderLayout.CENTER)
                    add(scrollbar, BorderLayout.EAST)
            }

            tab.session.notifyRenderDirty()
            return JvTermTerminalPane(terminal, component)
        }

        private fun configureScrollbar(scrollbar: JBScrollBar) {
            scrollbar.unitIncrement = 1
            scrollbar.blockIncrement = 8
            scrollbar.isVisible = false
            scrollbar.isFocusable = false
            scrollbar.isOpaque = false
        }
    }
}
