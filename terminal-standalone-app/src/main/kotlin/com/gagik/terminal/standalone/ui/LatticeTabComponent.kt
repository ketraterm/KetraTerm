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

import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.border.EmptyBorder

/**
 * Compact tab header used by the standalone host.
 */
internal class LatticeTabComponent(
    initialTitle: String,
    onClose: () -> Unit,
) : JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)) {
    private val titleLabel = JLabel()

    var title: String
        get() = titleLabel.text
        set(value) {
            titleLabel.text = value
            titleLabel.toolTipText = value
            val labelWidth = (value.length * APPROXIMATE_CHARACTER_WIDTH).coerceIn(MINIMUM_LABEL_WIDTH, MAXIMUM_LABEL_WIDTH)
            titleLabel.preferredSize = Dimension(labelWidth, TAB_HEIGHT)
            revalidate()
            repaint()
        }

    init {
        isOpaque = false
        border = EmptyBorder(0, 2, 0, 0)
        titleLabel.foreground = LatticeChrome.TITLE_FOREGROUND
        title = initialTitle
        add(titleLabel)
        add(closeButton(onClose))
    }

    private fun closeButton(onClose: () -> Unit): JButton =
        JButton(CLOSE_GLYPH).apply {
            toolTipText = "Close tab"
            preferredSize = Dimension(TAB_HEIGHT, TAB_HEIGHT)
            minimumSize = preferredSize
            maximumSize = preferredSize
            isFocusable = false
            foreground = LatticeChrome.TEXT_MUTED
            background = LatticeChrome.TAB_SELECTED
            border = EmptyBorder(0, 0, 1, 0)
            putClientProperty("JButton.buttonType", "toolBarButton")
            addActionListener {
                onClose()
            }
        }

    private companion object {
        private const val TAB_HEIGHT = 22
        private const val APPROXIMATE_CHARACTER_WIDTH = 8
        private const val MINIMUM_LABEL_WIDTH = 58
        private const val MAXIMUM_LABEL_WIDTH = 168
        private const val CLOSE_GLYPH = "\u00D7"
    }
}
