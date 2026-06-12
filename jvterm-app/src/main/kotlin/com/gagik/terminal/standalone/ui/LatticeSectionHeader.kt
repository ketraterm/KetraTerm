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

import java.awt.*
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSeparator
import javax.swing.border.EmptyBorder

/**
 * A sleek section header component consisting of a bold title on the left
 * and a thin horizontal separator line extending across the remaining width.
 */
internal class LatticeSectionHeader(
    title: String,
) : JPanel() {
    init {
        layout = GridBagLayout()
        isOpaque = false
        border = EmptyBorder(24, 0, 12, 0)
        alignmentX = Component.LEFT_ALIGNMENT
        maximumSize = Dimension(Int.MAX_VALUE, 50)

        val titleLabel =
            JLabel(title).apply {
                foreground = LatticeChrome.textPrimary
                font = font.deriveFont(Font.BOLD, 13f)
            }

        val separator =
            JSeparator(JSeparator.HORIZONTAL).apply {
                foreground = LatticeChrome.border
            }

        val gbc =
            GridBagConstraints().apply {
                gridy = 0
                fill = GridBagConstraints.HORIZONTAL
                anchor = GridBagConstraints.WEST
            }

        gbc.gridx = 0
        gbc.weightx = 0.0
        gbc.insets = Insets(0, 0, 0, 10)
        add(titleLabel, gbc)

        gbc.gridx = 1
        gbc.weightx = 1.0
        gbc.insets = Insets(0, 0, 0, 0)
        add(separator, gbc)
    }
}
