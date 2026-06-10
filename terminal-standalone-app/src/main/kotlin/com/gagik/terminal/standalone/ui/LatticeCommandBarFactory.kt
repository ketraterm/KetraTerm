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
import com.gagik.terminal.ui.swing.settings.TerminalTheme
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Insets
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JCheckBoxMenuItem
import javax.swing.JComponent
import javax.swing.JMenu
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JRadioButtonMenuItem
import javax.swing.JSeparator
import javax.swing.SwingConstants
import javax.swing.border.EmptyBorder

/**
 * Builds the standalone command bar above terminal tabs.
 */
internal class LatticeCommandBarFactory(
    private val settings: StandaloneTerminalSettings,
    private val tabManager: LatticeTabManager,
    private val profiles: List<StandaloneTerminalProfile>,
) {
    fun create(): JComponent =
        JPanel(BorderLayout()).apply {
            background = LatticeChrome.TOP_BAR_BACKGROUND
            border = EmptyBorder(6, 8, 5, 8)
            add(createLeftActions(), BorderLayout.WEST)
            add(createRightActions(), BorderLayout.EAST)
        }

    private fun createLeftActions(): JComponent =
        JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
            add(
                commandButton("+", "New tab").apply {
                    addActionListener {
                        tabManager.openTab(profiles.first())
                    }
                },
            )
            add(
                commandButton(PROFILE_MENU_GLYPH, "Profiles and commands").apply {
                    preferredSize = Dimension(34, 28)
                    addActionListener {
                        createProfilesPopup().show(this, 0, height)
                    }
                },
            )
        }

    private fun createRightActions(): JComponent =
        JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            isOpaque = false
            add(
                commandButton(SETTINGS_MENU_GLYPH, "Settings").apply {
                    preferredSize = Dimension(42, 28)
                    addActionListener {
                        createSettingsPopup().show(this, 0, height)
                    }
                },
            )
        }

    private fun createProfilesPopup(): JPopupMenu =
        JPopupMenu().apply {
            profiles.forEachIndexed { index, profile ->
                add(profile.displayName).addActionListener {
                    tabManager.openTab(profile)
                }
                getComponent(index).name = profile.id
            }
            add(JSeparator(SwingConstants.HORIZONTAL))
            add("Close tab").addActionListener {
                tabManager.closeSelectedTab()
            }
        }

    private fun createSettingsPopup(): JPopupMenu =
        JPopupMenu().apply {
            add(createThemeMenu())
            add(createWidthItem())
        }

    private fun createThemeMenu(): JMenu {
        val themeMenu = JMenu("Theme")
        val themeGroup = ButtonGroup()
        TerminalTheme.entries.forEach { theme ->
            val item = JRadioButtonMenuItem(theme.displayName(), theme == settings.theme)
            themeGroup.add(item)
            item.addActionListener {
                settings.theme = theme
                tabManager.reloadAllPanes()
            }
            themeMenu.add(item)
        }
        return themeMenu
    }

    private fun createWidthItem(): JCheckBoxMenuItem =
        JCheckBoxMenuItem("Ambiguous as wide", settings.treatAmbiguousAsWide).apply {
            addActionListener {
                settings.treatAmbiguousAsWide = isSelected
                tabManager.reloadAllPanes()
            }
        }

    private fun commandButton(
        text: String,
        tooltip: String,
    ): JButton =
        JButton(text).apply {
            toolTipText = tooltip
            preferredSize = Dimension(34, 28)
            margin = Insets(0, 0, 0, 0)
            isFocusable = false
            background = LatticeChrome.CONTROL_BACKGROUND
            foreground = LatticeChrome.TITLE_FOREGROUND
            border = EmptyBorder(0, 0, 1, 0)
            putClientProperty("JButton.buttonType", "toolBarButton")
        }

    private fun TerminalTheme.displayName(): String =
        name.lowercase().split("_").joinToString(" ") {
            it.replaceFirstChar { char -> if (char.isLowerCase()) char.titlecase() else char.toString() }
        }

    private companion object {
        private const val PROFILE_MENU_GLYPH = "\u25BE"
        private const val SETTINGS_MENU_GLYPH = "\u22EF"
    }
}
