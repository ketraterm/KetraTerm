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

import com.gagik.terminal.pty.TerminalPtyEventListener
import com.gagik.terminal.session.TerminalSession
import com.gagik.terminal.standalone.config.StandaloneTerminalSettings
import com.gagik.terminal.standalone.profile.StandaloneTerminalProfile
import javax.swing.JFrame
import javax.swing.JOptionPane
import javax.swing.JTabbedPane
import javax.swing.SwingUtilities

/**
 * Owns standalone terminal tabs and tab-scoped session lifecycle.
 *
 * The manager is intentionally host-side. It creates and closes PTY-backed
 * panes, updates Swing tab metadata, and coordinates settings reloads across
 * live panes without leaking this policy into reusable UI components.
 */
internal class LatticeTabManager(
    private val frame: JFrame,
    private val tabPane: JTabbedPane,
    private val settings: StandaloneTerminalSettings,
) {
    private val tabs = ArrayList<LatticeTerminalPane>(INITIAL_TAB_CAPACITY)

    init {
        tabPane.addChangeListener {
            updateFrameTitleFromSelection()
            selectedPane?.requestFocus()
        }
    }

    val selectedPane: LatticeTerminalPane?
        get() {
            val selectedIndex = tabPane.selectedIndex
            return if (selectedIndex in tabs.indices) tabs[selectedIndex] else null
        }

    fun openTab(profile: StandaloneTerminalProfile): Boolean {
        val pane =
            try {
                LatticeTerminalPane.start(
                    profile = profile,
                    settings = settings,
                    eventListener = tabEventListener(profile),
                )
            } catch (exception: Exception) {
                showStartError(profile, exception)
                return false
            }

        tabs += pane
        tabPane.addTab(profile.displayName, pane.component)
        tabPane.setTabComponentAt(
            tabs.lastIndex,
            LatticeTabComponent(profile.displayName) {
                closePane(pane)
            },
        )
        tabPane.selectedIndex = tabs.lastIndex
        updateFrameTitleFromSelection()
        pane.requestFocus()
        return true
    }

    fun closeSelectedTab() {
        val selectedIndex = tabPane.selectedIndex
        if (selectedIndex !in tabs.indices) return
        closeTabAt(selectedIndex)
    }

    fun closeAllTabs() {
        while (tabs.isNotEmpty()) {
            closeTabAt(tabs.lastIndex)
        }
    }

    fun reloadAllPanes() {
        val palette = settings.current().palette
        var index = 0
        while (index < tabs.size) {
            tabs[index].reloadSettings(palette)
            index++
        }
    }

    private fun closeTabAt(index: Int) {
        val pane = tabs.removeAt(index)
        tabPane.removeTabAt(index)
        pane.close()
        updateFrameTitleFromSelection()
        selectedPane?.requestFocus()
    }

    private fun closePane(pane: LatticeTerminalPane) {
        val index = tabs.indexOf(pane)
        if (index >= 0) closeTabAt(index)
    }

    private fun tabEventListener(profile: StandaloneTerminalProfile): TerminalPtyEventListener =
        object : TerminalPtyEventListener {
            override fun bell(session: TerminalSession) {
                SwingUtilities.invokeLater {
                    frame.toolkit.beep()
                }
            }

            override fun iconTitleChanged(
                session: TerminalSession,
                title: String,
            ) = Unit

            override fun windowTitleChanged(
                session: TerminalSession,
                title: String,
            ) {
                SwingUtilities.invokeLater {
                    updateTabTitle(session, title.ifBlank { profile.displayName })
                }
            }

            override fun listenerFailed(
                session: TerminalSession,
                exception: Exception,
            ) = Unit
        }

    private fun updateTabTitle(
        session: TerminalSession,
        title: String,
    ) {
        var index = 0
        while (index < tabs.size) {
            if (tabs[index].session === session) {
                tabPane.setTitleAt(index, title)
                (tabPane.getTabComponentAt(index) as? LatticeTabComponent)?.title = title
                if (index == tabPane.selectedIndex) {
                    frame.title = title
                }
                return
            }
            index++
        }
    }

    private fun updateFrameTitleFromSelection() {
        val index = tabPane.selectedIndex
        frame.title =
            if (index in 0 until tabPane.tabCount) {
                tabPane.getTitleAt(index)
            } else {
                LatticeChrome.APP_TITLE
            }
    }

    private fun showStartError(
        profile: StandaloneTerminalProfile,
        exception: Exception,
    ) {
        JOptionPane.showMessageDialog(
            frame,
            exception.message ?: exception.javaClass.name,
            "Unable to start ${profile.displayName}",
            JOptionPane.ERROR_MESSAGE,
        )
    }

    private companion object {
        private const val INITIAL_TAB_CAPACITY = 4
    }
}
