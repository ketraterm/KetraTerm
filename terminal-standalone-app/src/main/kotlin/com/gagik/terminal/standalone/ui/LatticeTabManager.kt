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
import com.gagik.terminal.workspace.TerminalProfile
import com.gagik.terminal.workspace.TerminalWorkspace
import com.gagik.terminal.workspace.TerminalWorkspaceListener
import com.gagik.terminal.workspace.TerminalWorkspaceOpenOptions
import com.gagik.terminal.workspace.TerminalWorkspaceTab
import java.awt.CardLayout
import javax.swing.JFrame
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Owns standalone terminal tabs and tab-scoped session lifecycle.
 *
 * The manager coordinates between [LatticeTabBar] (visual header), the
 * [tabContentPanel] (holds one [LatticeTerminalPane] per tab via [CardLayout]),
 * and the backing [TerminalWorkspace]. All session lifecycle decisions live
 * here; reusable UI components remain policy-free.
 */
internal class LatticeTabManager(
    private val frame: JFrame,
    val tabBar: LatticeTabBar,
    private val tabContentPanel: JPanel,
    private val settings: StandaloneTerminalSettings,
) {
    private val panes = ArrayList<LatticeTerminalPane>(INITIAL_TAB_CAPACITY)
    private val workspace = TerminalWorkspace(StandaloneWorkspaceListener())

    val selectedPane: LatticeTerminalPane?
        get() = panes.find { it.tab.id == tabBar.selectedId() }

    /**
     * Opens a new terminal tab backed by [profile].
     *
     * Returns `true` on success, `false` if the PTY failed to start
     * (an error dialog is shown to the user in that case).
     */
    fun openTab(profile: TerminalProfile): Boolean {
        val workspaceTab =
            try {
                workspace.openTab(
                    profile = profile,
                    options =
                        settings.current().let { snapshot ->
                            TerminalWorkspaceOpenOptions(
                                columns = snapshot.columns,
                                rows = snapshot.rows,
                                treatAmbiguousAsWide = snapshot.treatAmbiguousAsWide,
                            )
                        },
                )
            } catch (exception: Exception) {
                showStartError(profile, exception)
                return false
            }

        val pane = LatticeTerminalPane.create(workspaceTab, settings)
        panes += pane
        tabContentPanel.add(pane.component, pane.tab.id)
        tabBar.addTab(TabEntry(id = pane.tab.id, title = profile.displayName))
        showPane(pane)
        tabBar.activeTabBackground = pane.terminal.background
        updateFrameTitle()
        pane.requestFocus()
        return true
    }

    /** Closes the tab identified by [id]. No-op if the id is unknown. */
    fun closeTab(id: String) {
        val index = panes.indexOfFirst { it.tab.id == id }
        if (index >= 0) closePaneAt(index)
    }

    /** Closes every open tab and shuts down the workspace. */
    fun closeAllTabs() {
        while (panes.isNotEmpty()) {
            closePaneAt(panes.lastIndex)
        }
        workspace.close()
    }

    /** Propagates a settings reload to all live panes and the workspace. */
    fun reloadAllPanes() {
        val snapshot = settings.current()
        LatticeChrome.applyPalette(snapshot.palette)
        frame.rootPane.putClientProperty("JRootPane.titleBarBackground", LatticeChrome.TOP_BAR_BACKGROUND)
        frame.rootPane.putClientProperty("JRootPane.titleBarForeground", LatticeChrome.TEXT_PRIMARY)
        SwingUtilities.updateComponentTreeUI(frame)
        panes.forEach { it.reloadSettings() }
        workspace.applySettings(
            palette = snapshot.palette,
            treatAmbiguousAsWide = snapshot.treatAmbiguousAsWide,
        )
        selectedPane?.let {
            tabBar.activeTabBackground = it.terminal.background
        }
        tabBar.repaint()
    }

    /**
     * Switches the active content pane and workspace selection to [id].
     * Called by [LatticeTabBar] when the user clicks a different tab.
     */
    fun onTabSelected(id: String) {
        val pane = panes.find { it.tab.id == id } ?: return
        if (workspace.selectedTab()?.id != id) {
            workspace.selectTab(id)
        }
        showPane(pane)
        tabBar.activeTabBackground = pane.terminal.background
        updateFrameTitle()
        pane.requestFocus()
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private fun closePaneAt(index: Int) {
        val pane = panes.removeAt(index)
        tabBar.removeTab(pane.tab.id)
        tabContentPanel.remove(pane.component)
        pane.close()
        workspace.closeTab(pane.tab.id)
        updateFrameTitle()
        selectedPane?.let { showPane(it) }
        selectedPane?.requestFocus()
    }

    private fun showPane(pane: LatticeTerminalPane) {
        (tabContentPanel.layout as CardLayout).show(tabContentPanel, pane.tab.id)
    }

    private fun updateTabTitle(
        tabId: String,
        title: String,
    ) {
        tabBar.updateTitle(tabId, title)
        if (tabBar.selectedId() == tabId) {
            frame.title = title
        }
    }

    private fun updateFrameTitle() {
        frame.title = tabBar.selectedTitle() ?: LatticeChrome.APP_TITLE
    }

    private fun showStartError(
        profile: TerminalProfile,
        exception: Exception,
    ) {
        JOptionPane.showMessageDialog(
            frame,
            exception.message ?: exception.javaClass.name,
            "Unable to start ${profile.displayName}",
            JOptionPane.ERROR_MESSAGE,
        )
    }

    private inner class StandaloneWorkspaceListener : TerminalWorkspaceListener {
        override fun bell(tab: TerminalWorkspaceTab) {
            SwingUtilities.invokeLater {
                frame.toolkit.beep()
            }
        }

        override fun titleChanged(
            tab: TerminalWorkspaceTab,
            title: String,
        ) {
            SwingUtilities.invokeLater {
                updateTabTitle(tab.id, title)
            }
        }
    }

    private companion object {
        private const val INITIAL_TAB_CAPACITY = 4
    }
}
