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
package io.github.ketraterm.app

import com.formdev.flatlaf.FlatDarkLaf
import com.formdev.flatlaf.FlatLaf
import java.awt.Insets
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.UIManager

/**
 * Installs the standalone application's Swing look and feel.
 */
internal object KetraTermLookAndFeel {
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
        UIManager.put("PopupMenu.borderInsets", Insets(6, 6, 6, 6))
        UIManager.put("ScrollBar.showButtons", false)
        UIManager.put("ScrollBar.width", 12)

        UIManager.put("TitlePane.unifiedBackground", true)
        UIManager.put("TitlePane.buttonSize", java.awt.Dimension(40, 40))
        UIManager.put("TitlePane.buttonMinimumWidth", 40)
        UIManager.put("TitlePane.buttonMaximizedHeight", 40)
        io.github.ketraterm.app.ui.Chrome
            .applySwingDefaults()
    }
}
