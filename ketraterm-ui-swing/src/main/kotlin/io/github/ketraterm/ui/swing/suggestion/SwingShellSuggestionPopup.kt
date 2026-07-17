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
package io.github.ketraterm.ui.swing.suggestion

import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.SwingUtilities
import kotlin.math.min

internal interface SwingShellSuggestionPopupListener {
    fun onSuggestionHovered(index: Int)

    fun onSuggestionClicked(index: Int)
}

internal class SwingShellSuggestionPopup(
    private val listener: SwingShellSuggestionPopupListener,
) : JComponent() {
    private var suggestions: List<SwingShellSuggestion> = emptyList()
    private var selectedIndex: Int = NO_SELECTION
    private val preparedLayout = SwingShellSuggestionPopupLayout()

    init {
        isOpaque = false
        isFocusable = false
        addMouseMotionListener(
            object : MouseAdapter() {
                override fun mouseMoved(event: MouseEvent) {
                    val row = rowAt(event.y)
                    if (row != NO_SELECTION) listener.onSuggestionHovered(row)
                }
            },
        )
        addMouseListener(
            object : MouseAdapter() {
                override fun mousePressed(event: MouseEvent) {
                    if (!SwingUtilities.isLeftMouseButton(event)) return
                    val row = rowAt(event.y)
                    if (row != NO_SELECTION) {
                        listener.onSuggestionClicked(row)
                        event.consume()
                    }
                }
            },
        )
        addComponentListener(
            object : ComponentAdapter() {
                override fun componentResized(event: ComponentEvent) {
                    prepareLayout()
                }
            },
        )
        addPropertyChangeListener("font") {
            prepareLayout()
        }
    }

    fun update(
        suggestions: List<SwingShellSuggestion>,
        selectedIndex: Int,
    ) {
        if (this.suggestions != suggestions) {
            this.suggestions = suggestions
            prepareLayout()
            revalidate()
        }
        this.selectedIndex = selectedIndex
        repaint()
    }

    override fun getPreferredSize(): Dimension {
        if (suggestions.isEmpty()) return Dimension(0, 0)
        val rows = min(suggestions.size, POPUP_MAX_VISIBLE_ROWS)
        return Dimension(DEFAULT_WIDTH, VERTICAL_PADDING * 2 + rows * ROW_HEIGHT)
    }

    override fun paintComponent(graphics: Graphics) {
        val g = graphics as? Graphics2D ?: return
        val previousColor = g.color
        val previousFont = g.font
        val previousAntialiasing = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING)
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            paintChrome(g)
            paintRows(g)
        } finally {
            g.color = previousColor
            g.font = previousFont
            g.setRenderingHint(
                RenderingHints.KEY_ANTIALIASING,
                previousAntialiasing ?: RenderingHints.VALUE_ANTIALIAS_DEFAULT,
            )
        }
    }

    private fun paintChrome(g: Graphics2D) {
        g.color = BACKGROUND
        g.fillRoundRect(0, 0, width - 1, height - 1, ARC, ARC)
        g.color = BORDER
        g.drawRoundRect(0, 0, width - 1, height - 1, ARC, ARC)
    }

    private fun paintRows(g: Graphics2D) {
        val count = preparedLayout.rowCount
        var index = 0
        while (index < count) {
            val top = VERTICAL_PADDING + index * ROW_HEIGHT
            val row = preparedLayout.row(index)
            if (index == selectedIndex) {
                g.color = SELECTED_BACKGROUND
                g.fillRoundRect(POPUP_ROW_INSET, top + 2, width - POPUP_ROW_INSET * 2, ROW_HEIGHT - 4, 8, 8)
            }

            val markerColor = MARKER_COLORS[index % MARKER_COLORS.size]
            g.color = markerColor
            g.fillRoundRect(POPUP_ROW_INSET + 2, top + 8, MARKER_SIZE, MARKER_SIZE, MARKER_SIZE, MARKER_SIZE)

            val textX = POPUP_ROW_INSET + POPUP_TEXT_LEFT_OFFSET
            val rightLimit = width - POPUP_ROW_INSET
            g.font = preparedLayout.textFont
            g.color = TEXT
            g.drawString(row.displayText, textX, top + 18)

            if (row.detail.isNotEmpty()) {
                g.font = preparedLayout.detailFont
                g.color = DETAIL
                g.drawString(row.detail, textX, top + 34)
            }

            if (row.sourceWidth > 0) {
                paintSource(g, row, rightLimit - row.sourceWidth, top + 10)
            }
            index++
        }
    }

    private fun paintSource(
        g: Graphics2D,
        row: SwingShellSuggestionPopupRow,
        x: Int,
        y: Int,
    ) {
        g.color = SOURCE_BACKGROUND
        g.fillRoundRect(x, y, row.sourceWidth, SOURCE_HEIGHT, SOURCE_ARC, SOURCE_ARC)
        g.font = preparedLayout.sourceFont
        g.color = SOURCE_TEXT
        g.drawString(row.sourceLabel, x + POPUP_SOURCE_HORIZONTAL_PADDING, y + SOURCE_BASELINE)
    }

    private fun prepareLayout() {
        preparedLayout.prepare(
            component = this,
            suggestions = suggestions,
            availableWidth = if (width > 0) width else DEFAULT_WIDTH,
        )
    }

    private fun rowAt(y: Int): Int {
        if (y < VERTICAL_PADDING) return NO_SELECTION
        val row = (y - VERTICAL_PADDING) / ROW_HEIGHT
        return if (row in suggestions.indices && row < POPUP_MAX_VISIBLE_ROWS) row else NO_SELECTION
    }

    private companion object {
        private const val NO_SELECTION = -1
        private const val DEFAULT_WIDTH = 440
        private const val ROW_HEIGHT = 44
        private const val VERTICAL_PADDING = 8
        private const val ARC = 14
        private const val MARKER_SIZE = 8
        private const val SOURCE_HEIGHT = 18
        private const val SOURCE_BASELINE = 13
        private const val SOURCE_ARC = 8
        private val BACKGROUND = Color(0xF21F2329.toInt(), true)
        private val BORDER = Color(0x663F4752, true)
        private val SELECTED_BACKGROUND = Color(0x384DA3FF, true)
        private val TEXT = Color(0xFFF3F6FA.toInt(), true)
        private val DETAIL = Color(0xFFA8B0BD.toInt(), true)
        private val SOURCE_BACKGROUND = Color(0x24FFFFFF, true)
        private val SOURCE_TEXT = Color(0xFFCBD5E1.toInt(), true)
        private val MARKER_COLORS =
            arrayOf(
                Color(0xFF6EE7B7.toInt(), true),
                Color(0xFF60A5FA.toInt(), true),
                Color(0xFFFBBF24.toInt(), true),
                Color(0xFFF472B6.toInt(), true),
            )
    }
}
