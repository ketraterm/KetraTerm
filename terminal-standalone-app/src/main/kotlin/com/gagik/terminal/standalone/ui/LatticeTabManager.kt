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
import com.gagik.terminal.workspace.*
import java.awt.*
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
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
    private val defaultProfile: TerminalProfile,
) {
    private val panes = ArrayList<LatticeTerminalPane>(INITIAL_TAB_CAPACITY)
    private val workspace = TerminalWorkspace(StandaloneWorkspaceListener())

    val selectedPane: LatticeTerminalPane?
        get() = panes.find { it.tab.id == tabBar.selectedId() }

    private val isMac =
        System
            .getProperty("os.name")
            .orEmpty()
            .lowercase()
            .contains("mac")

    private val keyEventDispatcher =
        KeyEventDispatcher { event ->
            if (event.id == KeyEvent.KEY_PRESSED) {
                val keyCode = event.keyCode
                val modifiers = event.modifiersEx
                val menuShortcutMask = Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx
                val isMenuShortcut = (modifiers and menuShortcutMask) == menuShortcutMask
                val isCtrlPressed = (modifiers and InputEvent.CTRL_DOWN_MASK) == InputEvent.CTRL_DOWN_MASK
                val isShiftPressed = (modifiers and InputEvent.SHIFT_DOWN_MASK) == InputEvent.SHIFT_DOWN_MASK
                val hasAlt = (modifiers and InputEvent.ALT_DOWN_MASK) != 0

                // 1. Tab cycling: Ctrl + Tab / Ctrl + Shift + Tab (on all platforms)
                if (keyCode == KeyEvent.VK_TAB && isCtrlPressed && !hasAlt) {
                    if (isShiftPressed) {
                        switchToPrevTab()
                    } else {
                        switchToNextTab()
                    }
                    return@KeyEventDispatcher true
                }

                // 2. Open/Close tab:
                if (isMac) {
                    if (isMenuShortcut && !hasAlt && !isShiftPressed) {
                        when (keyCode) {
                            KeyEvent.VK_T -> {
                                openTab(defaultProfile)
                                return@KeyEventDispatcher true
                            }
                            KeyEvent.VK_W -> {
                                selectedPane?.let { closeTab(it.tab.id) }
                                return@KeyEventDispatcher true
                            }
                        }
                    }
                } else {
                    if (isCtrlPressed && isShiftPressed && !hasAlt) {
                        when (keyCode) {
                            KeyEvent.VK_T -> {
                                openTab(defaultProfile)
                                return@KeyEventDispatcher true
                            }
                            KeyEvent.VK_W -> {
                                selectedPane?.let { closeTab(it.tab.id) }
                                return@KeyEventDispatcher true
                            }
                        }
                    }
                }
            }
            false
        }

    init {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(keyEventDispatcher)
    }

    fun selectTab(id: String) {
        tabBar.selectTab(id)
        onTabSelected(id)
    }

    private fun switchToNextTab() {
        if (panes.size <= 1) return
        val currentId = tabBar.selectedId() ?: return
        val currentIndex = panes.indexOfFirst { it.tab.id == currentId }
        if (currentIndex != -1) {
            val nextIndex = (currentIndex + 1) % panes.size
            selectTab(panes[nextIndex].tab.id)
        }
    }

    private fun switchToPrevTab() {
        if (panes.size <= 1) return
        val currentId = tabBar.selectedId() ?: return
        val currentIndex = panes.indexOfFirst { it.tab.id == currentId }
        if (currentIndex != -1) {
            val prevIndex = (currentIndex - 1 + panes.size) % panes.size
            selectTab(panes[prevIndex].tab.id)
        }
    }

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
        tabBar.addTab(TabEntry(id = pane.tab.id, title = profile.displayName, profileKind = profile.kind))
        showPane(pane)
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
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(keyEventDispatcher)
        while (panes.isNotEmpty()) {
            closePaneAt(panes.lastIndex)
        }
        workspace.close()
    }

    /** Propagates a settings reload to all live panes and the workspace. */
    fun reloadAllPanes() {
        val snapshot = settings.current()
        LatticeChrome.applyPalette(snapshot.palette)
        frame.rootPane.putClientProperty("JRootPane.titleBarBackground", LatticeChrome.topBarBackground)
        frame.rootPane.putClientProperty("JRootPane.titleBarForeground", LatticeChrome.textPrimary)
        SwingUtilities.updateComponentTreeUI(frame)
        panes.forEach { it.reloadSettings() }
        tabContentPanel.background = LatticeChrome.terminalBackground
        workspace.applySettings(
            palette = snapshot.palette,
            treatAmbiguousAsWide = snapshot.treatAmbiguousAsWide,
        )
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
        updateFrameTitle()
        pane.requestFocus()
    }

    /**
     * Updates the custom color of the corresponding workspace tab.
     * Called by [LatticeTabBar] when a user picks a custom color.
     */
    fun onTabColorChanged(
        id: String,
        colorHex: String?,
    ) {
        val tab = panes.find { it.tab.id == id }?.tab ?: return
        tab.color = colorHex
    }

    /**
     * Updates the custom title of the corresponding workspace tab.
     * Called by [LatticeTabBar] when a user renames a tab.
     */
    fun onTabRenameRequested(
        id: String,
        newName: String?,
    ) {
        val tab = panes.find { it.tab.id == id }?.tab ?: return
        tab.customTitle = newName
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

    private fun updateTabColor(
        tabId: String,
        colorHex: String?,
    ) {
        val color = colorHex?.let { Color.decode(it) }
        tabBar.updateColor(tabId, color)
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

        override fun colorChanged(
            tab: TerminalWorkspaceTab,
            color: String?,
        ) {
            SwingUtilities.invokeLater {
                updateTabColor(tab.id, color)
            }
        }
    }

    private companion object {
        private const val INITIAL_TAB_CAPACITY = 4
    }
}
