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
import javax.swing.*

/**
 * Owns standalone terminal tabs and tab-scoped session lifecycle.
 *
 * The manager coordinates between [LatticeTabBar] (visual header), the
 * [tabContentPanel] (holds one [LatticeTerminalPane] per tab via [CardLayout]),
 * and the backing [TerminalWorkspace]. All session lifecycle decisions live
 * here; reusable UI components remain policy-free.
 *
 * @property defaultProfileProvider supplier invoked each time a new default tab
 *   or split pane is opened. Queried at open time — not cached — so that changes
 *   to the configured shell take effect immediately on the next new tab.
 */
internal class LatticeTabManager(
    private val frame: JFrame,
    val tabBar: LatticeTabBar,
    private val tabContentPanel: JPanel,
    private val settings: StandaloneTerminalSettings,
    private val defaultProfileProvider: () -> TerminalProfile,
) {
    private val panes = ArrayList<LatticeTerminalPane>(INITIAL_TAB_CAPACITY)
    private val workspace = TerminalWorkspace(StandaloneWorkspaceListener())
    private val tabRoots = HashMap<String, SplitNode>()
    private val tabContainers = HashMap<String, JPanel>()

    val selectedPane: LatticeTerminalPane?
        get() = tabBar.selectedId()?.let { getActivePane(it) }

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

                // 2. Open/Close/Split tab/panes:
                if (isMac) {
                    if (isMenuShortcut && !hasAlt) {
                        when (keyCode) {
                            KeyEvent.VK_T -> {
                                if (!isShiftPressed) {
                                    openTab(defaultProfileProvider())
                                    return@KeyEventDispatcher true
                                }
                            }
                            KeyEvent.VK_W -> {
                                if (!isShiftPressed) {
                                    closeActivePane()
                                    return@KeyEventDispatcher true
                                }
                            }
                            KeyEvent.VK_E -> {
                                if (isShiftPressed) {
                                    selectedPane?.let { splitPane(it, isVertical = true) }
                                    return@KeyEventDispatcher true
                                }
                            }
                            KeyEvent.VK_O -> {
                                if (isShiftPressed) {
                                    selectedPane?.let { splitPane(it, isVertical = false) }
                                    return@KeyEventDispatcher true
                                }
                            }
                        }
                    }
                } else {
                    if (isCtrlPressed && isShiftPressed && !hasAlt) {
                        when (keyCode) {
                            KeyEvent.VK_T -> {
                                openTab(defaultProfileProvider())
                                return@KeyEventDispatcher true
                            }
                            KeyEvent.VK_W -> {
                                closeActivePane()
                                return@KeyEventDispatcher true
                            }
                            KeyEvent.VK_E -> {
                                selectedPane?.let { splitPane(it, isVertical = true) }
                                return@KeyEventDispatcher true
                            }
                            KeyEvent.VK_O -> {
                                selectedPane?.let { splitPane(it, isVertical = false) }
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
        val nextTabId = getNeighborTabId(forward = true) ?: return
        selectTab(nextTabId)
    }

    private fun switchToPrevTab() {
        val prevTabId = getNeighborTabId(forward = false) ?: return
        selectTab(prevTabId)
    }

    private fun getNeighborTabId(forward: Boolean): String? {
        val currentId = tabBar.selectedId() ?: return null
        val tabIds = tabRoots.keys.toList()
        if (tabIds.size <= 1) return null
        val currentIndex = tabIds.indexOf(currentId)
        if (currentIndex == -1) return null
        val step = if (forward) 1 else -1
        val nextIndex = (currentIndex + step + tabIds.size) % tabIds.size
        return tabIds[nextIndex]
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
                                maxHistory = snapshot.scrollbackLines,
                            )
                        },
                )
            } catch (exception: Exception) {
                showStartError(profile, exception)
                return false
            }

        val pane =
            LatticeTerminalPane.create(workspaceTab, settings) { p, x, y ->
                showPaneContextMenu(p, p.terminal, x, y)
            }
        panes += pane

        val tabId = pane.tab.id
        val leaf = LeafNode(pane)
        tabRoots[tabId] = leaf

        val container =
            JPanel(BorderLayout()).apply {
                background = LatticeChrome.terminalBackground
                isOpaque = true
                add(leaf.component, BorderLayout.CENTER)
            }
        tabContainers[tabId] = container

        tabContentPanel.add(container, tabId)
        tabBar.addTab(TabEntry(id = tabId, title = profile.displayName, profileKind = profile.kind))
        showPane(tabId)
        updateFrameTitle()
        pane.requestFocus()
        return true
    }

    /** Closes the tab identified by [id]. No-op if the id is unknown. */
    fun closeTab(id: String) {
        val root = tabRoots[id] ?: return
        val tabPanes = root.allPanes()
        for (pane in tabPanes) {
            panes.remove(pane)
            pane.close()
            workspace.closeTab(pane.tab.id)
        }
        tabRoots.remove(id)
        val container = tabContainers.remove(id)
        if (container != null) {
            tabContentPanel.remove(container)
        }
        tabBar.removeTab(id)
        updateFrameTitle()
        selectedPane?.let { onTabSelected(it.tab.id) }
    }

    /** Closes every open tab and shuts down the workspace. */
    fun closeAllTabs() {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(keyEventDispatcher)
        val tabIds = tabRoots.keys.toList()
        for (tabId in tabIds) {
            closeTab(tabId)
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
        val activePane = getActivePane(id) ?: return
        if (workspace.selectedTab()?.id != activePane.tab.id) {
            workspace.selectTab(activePane.tab.id)
        }
        showPane(id)
        tabBar.updateTitle(id, activePane.tab.title)
        tabBar.updateColor(id, activePane.tab.color?.let { Color.decode(it) })
        updateFrameTitle()
        activePane.requestFocus()
    }

    /**
     * Updates the custom color of the corresponding workspace tab.
     * Called by [LatticeTabBar] when a user picks a custom color.
     */
    fun onTabColorChanged(
        id: String,
        colorHex: String?,
    ) {
        val activePane = getActivePane(id) ?: return
        activePane.tab.color = colorHex
    }

    /**
     * Updates the custom title of the corresponding workspace tab.
     * Called by [LatticeTabBar] when a user renames a tab.
     */
    fun onTabRenameRequested(
        id: String,
        newName: String?,
    ) {
        val activePane = getActivePane(id) ?: return
        activePane.tab.customTitle = newName
    }

    fun splitPane(
        pane: LatticeTerminalPane,
        isVertical: Boolean,
    ) {
        val tabId = tabBar.selectedId() ?: return
        val root = tabRoots[tabId] ?: return

        val profile = defaultProfileProvider()
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
                                maxHistory = snapshot.scrollbackLines,
                            )
                        },
                )
            } catch (exception: Exception) {
                showStartError(profile, exception)
                return
            }
        val newPane =
            LatticeTerminalPane.create(workspaceTab, settings) { p, x, y ->
                showPaneContextMenu(p, p.terminal, x, y)
            }

        panes += newPane

        val newRoot = splitNodeInTree(root, pane, newPane, isVertical)
        tabRoots[tabId] = newRoot

        val container = tabContainers[tabId] ?: return
        container.removeAll()
        container.add(newRoot.component, BorderLayout.CENTER)
        container.revalidate()
        container.repaint()

        newPane.requestFocus()
        updateFrameTitle()
    }

    private fun splitNodeInTree(
        current: SplitNode,
        target: LatticeTerminalPane,
        newPane: LatticeTerminalPane,
        isVertical: Boolean,
    ): SplitNode =
        when (current) {
            is LeafNode -> {
                if (current.pane === target) {
                    val left = LeafNode(current.pane)
                    val right = LeafNode(newPane)
                    val splitPane = createStyledSplitPane(isVertical, left.component, right.component)
                    ParentNode(left, right, splitPane)
                } else {
                    current
                }
            }
            is ParentNode -> {
                current.left = splitNodeInTree(current.left, target, newPane, isVertical)
                current.right = splitNodeInTree(current.right, target, newPane, isVertical)
                current.splitPane.leftComponent = current.left.component
                current.splitPane.rightComponent = current.right.component
                current
            }
        }

    fun closePane(pane: LatticeTerminalPane) {
        val tabId = tabBar.selectedId() ?: return
        val root = tabRoots[tabId] ?: return
        val allPanes = root.allPanes()

        if (allPanes.size <= 1) {
            closeTab(tabId)
            return
        }

        val newRoot = root.removePane(pane)
        if (newRoot != null) {
            tabRoots[tabId] = newRoot
            val container = tabContainers[tabId] ?: return
            container.removeAll()
            container.add(newRoot.component, BorderLayout.CENTER)
            container.revalidate()
            container.repaint()
        }

        panes.remove(pane)
        pane.close()
        workspace.closeTab(pane.tab.id)

        val newActive = getActivePane(tabId)
        newActive?.requestFocus()
        updateFrameTitle()
    }

    fun closeActivePane() {
        val tabId = tabBar.selectedId() ?: return
        val activePane = getActivePane(tabId) ?: return
        closePane(activePane)
    }

    fun getActivePane(tabId: String): LatticeTerminalPane? {
        val root = tabRoots[tabId] ?: return null
        val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
        return root.findActivePane(focusOwner) ?: root.allPanes().firstOrNull()
    }

    fun showPaneContextMenu(
        pane: LatticeTerminalPane,
        component: Component,
        x: Int,
        y: Int,
    ) {
        val menu = JPopupMenu()

        val copyItem =
            JMenuItem("Copy").apply {
                isEnabled = pane.terminal.currentSelection() != null
                addActionListener {
                    pane.terminal.copySelectionToClipboard()
                }
            }
        val pasteItem =
            JMenuItem("Paste").apply {
                addActionListener {
                    pane.terminal.pasteClipboardText()
                }
            }

        menu.add(copyItem)
        menu.add(pasteItem)
        menu.addSeparator()

        val splitVert =
            JMenuItem("Split Vertically").apply {
                addActionListener {
                    splitPane(pane, isVertical = true)
                }
            }
        val splitHor =
            JMenuItem("Split Horizontally").apply {
                addActionListener {
                    splitPane(pane, isVertical = false)
                }
            }

        menu.add(splitVert)
        menu.add(splitHor)

        val closeItem =
            JMenuItem("Close Pane").apply {
                addActionListener {
                    closePane(pane)
                }
            }
        menu.addSeparator()
        menu.add(closeItem)

        menu.show(component, x, y)
    }

    private fun createStyledSplitPane(
        isVertical: Boolean,
        left: Component,
        right: Component,
    ): JSplitPane {
        val orientation = if (isVertical) JSplitPane.HORIZONTAL_SPLIT else JSplitPane.VERTICAL_SPLIT
        return JSplitPane(orientation, left, right).apply {
            border = null
            dividerSize = 4
            isContinuousLayout = true
            resizeWeight = 0.5
            setUI(
                object : javax.swing.plaf.basic.BasicSplitPaneUI() {
                    override fun createDefaultDivider(): javax.swing.plaf.basic.BasicSplitPaneDivider =
                        object : javax.swing.plaf.basic.BasicSplitPaneDivider(this) {
                            override fun paint(g: Graphics) {
                                g.color = LatticeChrome.border
                                g.fillRect(0, 0, width, height)
                            }
                        }
                },
            )
            background = LatticeChrome.terminalBackground
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private fun showPane(tabId: String) {
        (tabContentPanel.layout as CardLayout).show(tabContentPanel, tabId)
    }

    private fun updateTabTitle(
        tabId: String,
        title: String,
    ) {
        val visualTabId = tabRoots.entries.find { (_, root) -> root.allPanes().any { it.tab.id == tabId } }?.key ?: tabId
        val activePane = getActivePane(visualTabId)
        if (activePane?.tab?.id == tabId) {
            tabBar.updateTitle(visualTabId, title)
            if (tabBar.selectedId() == visualTabId) {
                frame.title = title
            }
        }
    }

    private fun updateTabColor(
        tabId: String,
        colorHex: String?,
    ) {
        val visualTabId = tabRoots.entries.find { (_, root) -> root.allPanes().any { it.tab.id == tabId } }?.key ?: tabId
        val activePane = getActivePane(visualTabId)
        if (activePane?.tab?.id == tabId) {
            val color = colorHex?.let { Color.decode(it) }
            tabBar.updateColor(visualTabId, color)
        }
    }

    private fun updateFrameTitle() {
        frame.title = selectedPane?.tab?.title ?: LatticeChrome.APP_TITLE
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
            if (settings.audibleBell) {
                SwingUtilities.invokeLater {
                    frame.toolkit.beep()
                }
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

internal sealed interface SplitNode {
    val component: Component

    fun findPane(id: String): LatticeTerminalPane?

    fun findActivePane(focusedComponent: Component?): LatticeTerminalPane?

    fun allPanes(): List<LatticeTerminalPane>

    fun removePane(pane: LatticeTerminalPane): SplitNode?
}

internal class LeafNode(
    val pane: LatticeTerminalPane,
) : SplitNode {
    override val component: Component get() = pane.component

    override fun findPane(id: String): LatticeTerminalPane? = if (pane.tab.id == id) pane else null

    override fun findActivePane(focusedComponent: Component?): LatticeTerminalPane? {
        if (focusedComponent != null &&
            (pane.component == focusedComponent || SwingUtilities.isDescendingFrom(focusedComponent, pane.component))
        ) {
            return pane
        }
        return null
    }

    override fun allPanes(): List<LatticeTerminalPane> = listOf(pane)

    override fun removePane(pane: LatticeTerminalPane): SplitNode? = if (this.pane === pane) null else this
}

internal class ParentNode(
    var left: SplitNode,
    var right: SplitNode,
    val splitPane: JSplitPane,
) : SplitNode {
    override val component: Component get() = splitPane

    override fun findPane(id: String): LatticeTerminalPane? = left.findPane(id) ?: right.findPane(id)

    override fun findActivePane(focusedComponent: Component?): LatticeTerminalPane? =
        left.findActivePane(focusedComponent) ?: right.findActivePane(focusedComponent)

    override fun allPanes(): List<LatticeTerminalPane> = left.allPanes() + right.allPanes()

    override fun removePane(pane: LatticeTerminalPane): SplitNode {
        val nextLeft = left.removePane(pane)
        val nextRight = right.removePane(pane)
        if (nextLeft == null) return right
        if (nextRight == null) return left
        left = nextLeft
        right = nextRight
        splitPane.leftComponent = left.component
        splitPane.rightComponent = right.component
        return this
    }
}
