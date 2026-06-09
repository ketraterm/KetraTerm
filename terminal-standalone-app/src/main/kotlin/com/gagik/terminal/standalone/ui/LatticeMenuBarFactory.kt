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

import com.gagik.terminal.session.TerminalSession
import com.gagik.terminal.standalone.config.StandaloneTerminalSettings
import com.gagik.terminal.ui.swing.api.TerminalSwingTerminal
import com.gagik.terminal.ui.swing.settings.TerminalTheme
import javax.swing.JCheckBoxMenuItem
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.JMenuItem

/**
 * Builds standalone application menus and maps menu changes to host settings.
 */
internal class LatticeMenuBarFactory(
    private val settings: StandaloneTerminalSettings,
) {
    fun create(
        terminal: TerminalSwingTerminal,
        session: TerminalSession,
    ): JMenuBar {
        val menuBar = JMenuBar()
        menuBar.add(createThemeMenu(terminal, session))
        menuBar.add(createWidthMenu(terminal))
        return menuBar
    }

    private fun createThemeMenu(
        terminal: TerminalSwingTerminal,
        session: TerminalSession,
    ): JMenu {
        val themeMenu = JMenu("Theme")
        TerminalTheme.entries.forEach { theme ->
            val item = JMenuItem(theme.displayName())
            item.addActionListener {
                settings.theme = theme
                terminal.reloadSettings()
                session.setThemePalette(settings.current().palette)
                session.notifyRenderDirty()
            }
            themeMenu.add(item)
        }
        return themeMenu
    }

    private fun createWidthMenu(terminal: TerminalSwingTerminal): JMenu {
        val widthMenu = JMenu("Width")
        val ambiguousWidthItem = JCheckBoxMenuItem("Ambiguous as wide", settings.treatAmbiguousAsWide)
        ambiguousWidthItem.addActionListener {
            settings.treatAmbiguousAsWide = ambiguousWidthItem.isSelected
            terminal.reloadSettings()
        }
        widthMenu.add(ambiguousWidthItem)
        return widthMenu
    }

    private fun TerminalTheme.displayName(): String =
        name.lowercase().split("_").joinToString(" ") {
            it.replaceFirstChar { char -> if (char.isLowerCase()) char.titlecase() else char.toString() }
        }
}
