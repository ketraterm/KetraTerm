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

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollBar
import com.intellij.ui.components.JBTextField
import io.github.ketraterm.intellij.settings.KetraTermIntellijSettings
import io.github.ketraterm.ui.swing.api.SwingHostServices
import io.github.ketraterm.ui.swing.api.SwingScrollbarAdapter
import io.github.ketraterm.ui.swing.api.SwingTerminal
import io.github.ketraterm.ui.swing.api.TerminalUiDispatcher
import io.github.ketraterm.ui.swing.host.SwingTerminalSearchBar
import io.github.ketraterm.ui.swing.host.SwingTerminalSearchBarComponentFactory
import io.github.ketraterm.workspace.TerminalWorkspaceTab
import java.awt.Adjustable
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField

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
        ): KetraTermTerminalPane {
            val scrollbar = JBScrollBar(Adjustable.VERTICAL)
            val scrollbarAdapter = SwingScrollbarAdapter(scrollbar)
            val shortcutControllerRef = arrayOfNulls<KetraTermTerminalShortcutController>(1)
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
                        ),
                )
            scrollbarAdapter.attach(terminal)
            terminal.bind(tab.session)

            val component =
                JPanel(BorderLayout()).apply {
                    border = null
                    background = terminal.background
                    terminal.border = null
                    add(terminal, BorderLayout.CENTER)
                    add(scrollbar, BorderLayout.EAST)
                }

            val searchBar = SwingTerminalSearchBar(terminal, IntellijSearchBarComponentFactory)
            component.add(searchBar.component, BorderLayout.NORTH)

            tab.session.notifyRenderDirty()
            return KetraTermTerminalPane(tab, terminal, component, searchBar).also { pane ->
                pane.shortcutController = KetraTermTerminalShortcutController(pane)
                shortcutControllerRef[0] = pane.shortcutController
            }
        }

        private object IntellijSearchBarComponentFactory : SwingTerminalSearchBarComponentFactory {
            override fun textField(columns: Int): JTextField = JBTextField(columns)

            override fun label(text: String): JLabel = JBLabel(text)

            override fun button(text: String): JButton = JButton(text)

            override fun checkBox(text: String): JCheckBox = JCheckBox(text)
        }
    }
}
