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
package com.gagik.terminal.standalone

import com.formdev.flatlaf.FlatDarkLaf
import com.formdev.flatlaf.FlatLaf
import com.gagik.terminal.standalone.ui.LatticeChrome
import java.awt.Insets
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.UIManager

/**
 * Installs the standalone application's Swing look and feel.
 */
internal object StandaloneLookAndFeel {
    fun install() {
        try {
            System.setProperty("flatlaf.useWindowDecorations", "true")
            JFrame.setDefaultLookAndFeelDecorated(true)
            JDialog.setDefaultLookAndFeelDecorated(true)

            FlatDarkLaf.setup()
            applyDefaults()
            FlatLaf.updateUI()
        } catch (_: RuntimeException) {
            // Keep the JDK default rather than failing terminal startup.
        }
    }

    private fun applyDefaults() {
        UIManager.put("Component.arc", 8)
        UIManager.put("Component.focusWidth", 1)
        UIManager.put("Component.innerFocusWidth", 0)
        UIManager.put("Button.arc", 7)
        UIManager.put("CheckBox.arc", 5)
        UIManager.put("Button.background", LatticeChrome.CONTROL_BACKGROUND)
        UIManager.put("Button.hoverBackground", LatticeChrome.CONTROL_HOVER)
        UIManager.put("Button.pressedBackground", LatticeChrome.CONTROL_PRESSED)
        UIManager.put("Button.foreground", LatticeChrome.TITLE_FOREGROUND)
        UIManager.put("Panel.background", LatticeChrome.SURFACE)
        UIManager.put("PopupMenu.borderInsets", Insets(6, 6, 6, 6))
        UIManager.put("PopupMenu.background", LatticeChrome.POPUP_BACKGROUND)
        UIManager.put("MenuItem.selectionBackground", LatticeChrome.CONTROL_HOVER)
        UIManager.put("MenuItem.selectionForeground", LatticeChrome.TITLE_FOREGROUND)
        UIManager.put("Separator.foreground", LatticeChrome.BORDER)
        UIManager.put("ScrollBar.showButtons", false)
        UIManager.put("ScrollBar.width", 12)
        UIManager.put("TabbedPane.background", LatticeChrome.TAB_BAR_BACKGROUND)
        UIManager.put("TabbedPane.contentAreaColor", LatticeChrome.TERMINAL_BACKGROUND)
        UIManager.put("TabbedPane.focusColor", LatticeChrome.TAB_SELECTED)
        UIManager.put("TabbedPane.foreground", LatticeChrome.TEXT_MUTED)
        UIManager.put("TabbedPane.hoverColor", LatticeChrome.TAB_HOVER)
        UIManager.put("TabbedPane.underlineColor", LatticeChrome.ACCENT)
        UIManager.put("TabbedPane.selectedBackground", LatticeChrome.TAB_SELECTED)
        UIManager.put("TabbedPane.selectedForeground", LatticeChrome.TITLE_FOREGROUND)
        UIManager.put("TitlePane.unifiedBackground", true)
        UIManager.put("TitlePane.background", LatticeChrome.TOP_BAR_BACKGROUND)
        UIManager.put("TitlePane.foreground", LatticeChrome.TITLE_FOREGROUND)
        UIManager.put("TitlePane.inactiveBackground", LatticeChrome.TOP_BAR_BACKGROUND)
        UIManager.put("TitlePane.inactiveForeground", LatticeChrome.TEXT_MUTED)
    }
}
