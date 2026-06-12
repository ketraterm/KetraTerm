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

import com.formdev.flatlaf.extras.FlatSVGIcon
import com.gagik.terminal.standalone.config.StandaloneTerminalSettings
import com.gagik.terminal.workspace.TerminalProfileRegistry
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.nio.file.Path
import javax.swing.*
import javax.swing.border.EmptyBorder

/**
 * Creates and wires the standalone terminal window.
 *
 * FlatLaf merges [JMenuBar] into the custom title bar when
 * `useWindowDecorations=true` and `JFrame.setDefaultLookAndFeelDecorated(true)`
 * are active. We place [LatticeTabBar] at the left of the menu bar and the
 * action buttons at the right, with a horizontal glue in between.
 *
 * @property profileRegistry source of available shell profiles for the new-tab
 *   dropdown menu and the default-profile provider lambda.
 */
internal class LatticeWindowFactory(
    private val settings: StandaloneTerminalSettings,
    private val profileRegistry: TerminalProfileRegistry,
) {
    fun createWindow(): LatticeWindow {
        val frame =
            JFrame(LatticeChrome.APP_TITLE).apply {
                defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
                minimumSize = Dimension(720, 420)
                iconImages =
                    listOf(
                        FlatSVGIcon("com/gagik/terminal/standalone/icons/logo.svg", 16, 16).image,
                        FlatSVGIcon("com/gagik/terminal/standalone/icons/logo.svg", 32, 32).image,
                        FlatSVGIcon("com/gagik/terminal/standalone/icons/logo.svg", 48, 48).image,
                        FlatSVGIcon("com/gagik/terminal/standalone/icons/logo.svg", 128, 128).image,
                    )
            }

        val tabContentPanel =
            JPanel(CardLayout()).apply {
                background = LatticeChrome.terminalBackground
                isOpaque = true
            }

        // Tab bar callbacks reference tabManager; forward via lambda so the
        // lateinit is resolved at call time, not at construction time.
        lateinit var tabManager: LatticeTabManager
        lateinit var tabBar: LatticeTabBar

        val availableProfiles = profileRegistry.availableProfiles()

        // Builds a fresh configured profile from the current settings each time
        // a new default tab or split is opened. This means shell changes in the
        // Settings dialog take effect on the very next new tab without a restart.
        val defaultProfileProvider: () -> com.gagik.terminal.workspace.TerminalProfile = {
            profileRegistry.configuredProfile(
                shellPath = settings.shellPath,
                workingDirectory =
                    settings.startDirectory
                        .takeIf { it.isNotBlank() }
                        ?.let { runCatching { Path.of(it) }.getOrNull() },
            )
        }

        tabBar =
            LatticeTabBar(
                onTabSelected = { id -> tabManager.onTabSelected(id) },
                onTabClose = { id -> tabManager.closeTab(id) },
                onNewTab = { tabManager.openTab(defaultProfileProvider()) },
                onMenuClick = { x, y -> showDropdownMenu(frame, tabBar, x, y, availableProfiles, tabManager, defaultProfileProvider) },
                onTabColorChanged = { id, color -> tabManager.onTabColorChanged(id, color) },
                onTabRenameRequested = { id, newName -> tabManager.onTabRenameRequested(id, newName) },
            )

        tabManager = LatticeTabManager(frame, tabBar, tabContentPanel, settings, defaultProfileProvider)

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
        frame: JFrame,
        invoker: java.awt.Component,
        x: Int,
        y: Int,
        profiles: List<com.gagik.terminal.workspace.TerminalProfile>,
        tabManager: LatticeTabManager,
        defaultProfileProvider: () -> com.gagik.terminal.workspace.TerminalProfile,
    ) {
        val popup =
            JPopupMenu().apply {
                background = LatticeChrome.popupBackground
                border = BorderFactory.createLineBorder(LatticeChrome.border)
            }

        val profileIcons = LatticeProfileIcons()
        profiles.forEach { profile ->
            val item =
                JMenuItem(profile.displayName, profileIcons.icon(profile.kind)).apply {
                    background = LatticeChrome.popupBackground
                    foreground = LatticeChrome.textPrimary
                    addActionListener {
                        // Stamp the configured startDirectory onto built-in profiles
                        // whose workingDirectory is null (i.e. they carry no explicit
                        // directory preference). Profiles with a profile-level directory
                        // are left untouched.
                        val configuredDirectory = defaultProfileProvider().workingDirectory
                        val effectiveProfile =
                            if (profile.workingDirectory == null && configuredDirectory != null) {
                                profile.copy(workingDirectory = configuredDirectory)
                            } else {
                                profile
                            }
                        tabManager.openTab(effectiveProfile)
                    }
                }
            popup.add(item)
        }

        popup.addSeparator()

        val settingsItem =
            JMenuItem("Settings").apply {
                icon =
                    FlatSVGIcon("com/gagik/terminal/standalone/icons/settings.svg", 16, 16).apply {
                        colorFilter =
                            FlatSVGIcon.ColorFilter().apply {
                                add(java.awt.Color.BLACK, LatticeChrome.textPrimary)
                            }
                    }
                background = LatticeChrome.popupBackground
                foreground = LatticeChrome.textPrimary
                addActionListener {
                    LatticeSettingsDialog(frame, settings, profileRegistry) {
                        tabManager.reloadAllPanes()
                    }.isVisible = true
                }
            }
        popup.add(settingsItem)

        val aboutItem =
            JMenuItem("About").apply {
                icon =
                    FlatSVGIcon("com/gagik/terminal/standalone/icons/about.svg", 16, 16).apply {
                        colorFilter =
                            FlatSVGIcon.ColorFilter().apply {
                                add(java.awt.Color.BLACK, LatticeChrome.textPrimary)
                            }
                    }
                background = LatticeChrome.popupBackground
                foreground = LatticeChrome.textPrimary
                addActionListener {
                    LatticeAboutDialog(frame).isVisible = true
                }
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
            background = LatticeChrome.surface
            putClientProperty("JRootPane.titleBarBackground", LatticeChrome.topBarBackground)
            putClientProperty("JRootPane.titleBarForeground", LatticeChrome.textPrimary)
            putClientProperty("JRootPane.titleBarShowIcon", false)
            putClientProperty("JRootPane.titleBarShowTitle", false)
        }
    }
}

/**
 * Standalone window and its terminal tab controller.
 */
internal data class LatticeWindow(
    val frame: JFrame,
    val tabManager: LatticeTabManager,
)
