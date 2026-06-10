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
import com.gagik.terminal.ui.swing.settings.TerminalTheme
import com.gagik.terminal.workspace.TerminalProfile
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.Box
import javax.swing.ButtonGroup
import javax.swing.JCheckBoxMenuItem
import javax.swing.JFrame
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.JPanel
import javax.swing.JRadioButtonMenuItem
import javax.swing.WindowConstants
import javax.swing.border.EmptyBorder

/**
 * Creates and wires the standalone terminal window.
 *
 * FlatLaf merges [JMenuBar] into the custom title bar when
 * `useWindowDecorations=true` and `JFrame.setDefaultLookAndFeelDecorated(true)`
 * are active. We place [LatticeTabBar] at the left of the menu bar and the
 * action buttons at the right, with a horizontal glue in between.
 *
 * ```
 * ┌──────────────────────────────────────────────┐
 * │  [tab1] [tab2] [+]        [≡] [⋯]  [ − □ × ]│  ← JMenuBar (title bar)
 * ├──────────────────────────────────────────────┤
 * │                                              │
 * │            terminal content                  │  ← content pane
 * │                                              │
 * └──────────────────────────────────────────────┘
 * ```
 */
internal class LatticeWindowFactory(
    private val settings: StandaloneTerminalSettings,
    private val profiles: List<TerminalProfile>,
) {
    fun createWindow(): LatticeWindow {
        val frame =
            JFrame(LatticeChrome.APP_TITLE).apply {
                defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
                background = LatticeChrome.SURFACE
                minimumSize = Dimension(720, 420)
            }

        val tabContentPanel =
            JPanel(CardLayout()).apply {
                background = LatticeChrome.TERMINAL_BACKGROUND
                isOpaque = true
            }

        // Tab bar callbacks reference tabManager; forward via lambda so the
        // lateinit is resolved at call time, not at construction time.
        lateinit var tabManager: LatticeTabManager
        lateinit var tabBar: LatticeTabBar

        tabBar =
            LatticeTabBar(
                onTabSelected = { id -> tabManager.onTabSelected(id) },
                onTabClose = { id -> tabManager.closeTab(id) },
                onNewTab = { tabManager.openTab(profiles.first()) },
                onMenuClick = { x, y -> showDropdownMenu(tabBar, x, y, profiles, tabManager) },
            )

        tabManager = LatticeTabManager(frame, tabBar, tabContentPanel, settings)

        installMenuBar(frame, tabBar)
        styleTitleBar(frame)
        frame.contentPane = tabContentPanel

        frame.addWindowListener(
            object : WindowAdapter() {
                override fun windowClosed(event: WindowEvent) {
                    tabManager.closeAllTabs()
                }
            },
        )

        return LatticeWindow(frame, tabManager)
    }

    /**
     * Places [tabBar] and the action panel inside a [JMenuBar] that FlatLaf
     * renders in the title bar area.
     */
    private fun installMenuBar(
        frame: JFrame,
        tabBar: LatticeTabBar,
    ) {
        frame.jMenuBar =
            JMenuBar().apply {
                isOpaque = false
                border = EmptyBorder(0, 0, 0, 0)
                add(tabBar)
                add(Box.createHorizontalGlue())
            }
    }

    private fun showDropdownMenu(
        invoker: java.awt.Component,
        x: Int,
        y: Int,
        profiles: List<TerminalProfile>,
        tabManager: LatticeTabManager,
    ) {
        val popup =
            javax.swing.JPopupMenu().apply {
                background = LatticeChrome.POPUP_BACKGROUND
                border = javax.swing.BorderFactory.createLineBorder(LatticeChrome.BORDER)
            }

        profiles.forEach { profile ->
            val item =
                javax.swing.JMenuItem(profile.displayName).apply {
                    background = LatticeChrome.POPUP_BACKGROUND
                    foreground = LatticeChrome.TEXT_PRIMARY
                    addActionListener { tabManager.openTab(profile) }
                }
            popup.add(item)
        }

        popup.addSeparator()

        val settingsMenu =
            JMenu("Settings").apply {
                background = LatticeChrome.POPUP_BACKGROUND
                foreground = LatticeChrome.TEXT_PRIMARY
                add(buildThemeMenu(tabManager))
                add(buildWidthItem(tabManager))
            }
        popup.add(settingsMenu)

        val commandPaletteItem =
            javax.swing.JMenuItem("Command palette").apply {
                background = LatticeChrome.POPUP_BACKGROUND
                foreground = LatticeChrome.TEXT_PRIMARY
            }
        popup.add(commandPaletteItem)

        val aboutItem =
            javax.swing.JMenuItem("About").apply {
                background = LatticeChrome.POPUP_BACKGROUND
                foreground = LatticeChrome.TEXT_PRIMARY
            }
        popup.add(aboutItem)

        popup.show(invoker, x, y)
    }

    /**
     * Applies FlatLaf title bar styling: background colour, no icon, no title
     * text (the tab bar provides context instead).
     */
    private fun styleTitleBar(frame: JFrame) {
        frame.rootPane.apply {
            putClientProperty("JRootPane.titleBarBackground", LatticeChrome.TOP_BAR_BACKGROUND)
            putClientProperty("JRootPane.titleBarForeground", LatticeChrome.TEXT_PRIMARY)
            putClientProperty("JRootPane.titleBarShowIcon", false)
            putClientProperty("JRootPane.titleBarShowTitle", false)
        }
    }

    private fun buildThemeMenu(tabManager: LatticeTabManager): JMenu {
        val themeMenu =
            JMenu("Theme").apply {
                background = LatticeChrome.POPUP_BACKGROUND
                foreground = LatticeChrome.TEXT_PRIMARY
            }
        val themeGroup = ButtonGroup()
        TerminalTheme.entries.forEach { theme ->
            val item =
                JRadioButtonMenuItem(theme.displayName(), theme == settings.theme).apply {
                    background = LatticeChrome.POPUP_BACKGROUND
                    foreground = LatticeChrome.TEXT_PRIMARY
                }
            themeGroup.add(item)
            item.addActionListener {
                settings.theme = theme
                tabManager.reloadAllPanes()
            }
            themeMenu.add(item)
        }
        return themeMenu
    }

    private fun buildWidthItem(tabManager: LatticeTabManager): JCheckBoxMenuItem =
        JCheckBoxMenuItem("Ambiguous as wide", settings.treatAmbiguousAsWide).apply {
            background = LatticeChrome.POPUP_BACKGROUND
            foreground = LatticeChrome.TEXT_PRIMARY
            addActionListener {
                settings.treatAmbiguousAsWide = isSelected
                tabManager.reloadAllPanes()
            }
        }

    private fun TerminalTheme.displayName(): String =
        name.lowercase().split("_").joinToString(" ") {
            it.replaceFirstChar { char -> if (char.isLowerCase()) char.titlecase() else char.toString() }
        }
}

/**
 * Standalone window and its terminal tab controller.
 */
internal data class LatticeWindow(
    val frame: JFrame,
    val tabManager: LatticeTabManager,
)
