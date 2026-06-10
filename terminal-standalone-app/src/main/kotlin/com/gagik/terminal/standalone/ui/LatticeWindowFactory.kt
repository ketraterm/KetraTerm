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
import com.gagik.terminal.standalone.profile.StandaloneTerminalProfile
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Insets
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.*
import javax.swing.border.EmptyBorder

/**
 * Creates and wires the standalone terminal window chrome.
 */
internal class LatticeWindowFactory(
    private val settings: StandaloneTerminalSettings,
    private val profiles: List<StandaloneTerminalProfile>,
) {
    private val tabPane = JTabbedPane()

    fun createWindow(): LatticeWindow {
        val frame =
            JFrame(LatticeChrome.APP_TITLE).apply {
                defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
                background = LatticeChrome.SURFACE
                minimumSize = Dimension(720, 420)
                rootPane.putClientProperty("JRootPane.titleBarBackground", LatticeChrome.TOP_BAR_BACKGROUND)
                rootPane.putClientProperty("JRootPane.titleBarForeground", LatticeChrome.TITLE_FOREGROUND)
            }
        val tabManager = LatticeTabManager(frame, tabPane, settings)
        frame.contentPane =
            windowPanel(
                LatticeCommandBarFactory(
                    settings = settings,
                    tabManager = tabManager,
                    profiles = profiles,
                ).create(),
            )
        frame.addWindowListener(
            object : WindowAdapter() {
                override fun windowClosed(event: WindowEvent) {
                    tabManager.closeAllTabs()
                }
            },
        )
        return LatticeWindow(frame, tabManager)
    }

    private fun windowPanel(commandBar: JComponent): JPanel =
        JPanel(BorderLayout()).apply {
            background = LatticeChrome.SURFACE
            border = EmptyBorder(0, 0, 0, 0)
            configureTabPane()
            add(commandBar, BorderLayout.NORTH)
            add(tabPane, BorderLayout.CENTER)
        }

    private fun configureTabPane() {
        tabPane.background = LatticeChrome.TAB_BAR_BACKGROUND
        tabPane.foreground = LatticeChrome.TEXT_MUTED
        tabPane.isFocusable = false
        tabPane.tabLayoutPolicy = JTabbedPane.SCROLL_TAB_LAYOUT
        tabPane.putClientProperty("JTabbedPane.tabType", "card")
        tabPane.putClientProperty("JTabbedPane.hasFullBorder", false)
        tabPane.putClientProperty("JTabbedPane.showTabSeparators", false)
        tabPane.putClientProperty("JTabbedPane.tabAreaInsets", Insets(5, 8, 0, 8))
        tabPane.putClientProperty("JTabbedPane.tabInsets", Insets(7, 12, 7, 10))
        tabPane.putClientProperty("JTabbedPane.minimumTabWidth", 132)
        tabPane.putClientProperty("JTabbedPane.maximumTabWidth", 220)
        tabPane.putClientProperty("JTabbedPane.scrollButtonsPolicy", "asNeeded")
    }
}

/**
 * Standalone window and its terminal tab controller.
 */
internal data class LatticeWindow(
    val frame: JFrame,
    val tabManager: LatticeTabManager,
)
