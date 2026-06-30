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

import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.*
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
    }

    fun update(
        suggestions: List<SwingShellSuggestion>,
        selectedIndex: Int,
    ) {
        this.suggestions = suggestions
        this.selectedIndex = selectedIndex
        revalidate()
        repaint()
    }

    override fun getPreferredSize(): Dimension {
        if (suggestions.isEmpty()) return Dimension(0, 0)
        val rows = min(suggestions.size, MAX_VISIBLE_ROWS)
        return Dimension(DEFAULT_WIDTH, VERTICAL_PADDING * 2 + rows * ROW_HEIGHT)
    }

    override fun paintComponent(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            paintChrome(g)
            paintRows(g)
        } finally {
            g.dispose()
        }
    }

    private fun paintChrome(g: Graphics2D) {
        g.color = BACKGROUND
        g.fillRoundRect(0, 0, width - 1, height - 1, ARC, ARC)
        g.color = BORDER
        g.drawRoundRect(0, 0, width - 1, height - 1, ARC, ARC)
    }

    private fun paintRows(g: Graphics2D) {
        val textFont = font ?: g.font
        g.font = textFont
        val metrics = g.fontMetrics
        val detailFont = textFont.deriveFont(Font.PLAIN, (textFont.size2D - 1f).coerceAtLeast(MIN_DETAIL_FONT_SIZE))
        val detailMetrics = g.getFontMetrics(detailFont)
        val sourceFont = textFont.deriveFont(Font.BOLD, SOURCE_FONT_SIZE)
        val sourceMetrics = g.getFontMetrics(sourceFont)
        val count = min(suggestions.size, MAX_VISIBLE_ROWS)
        var index = 0
        while (index < count) {
            val top = VERTICAL_PADDING + index * ROW_HEIGHT
            val suggestion = suggestions[index]
            if (index == selectedIndex) {
                g.color = SELECTED_BACKGROUND
                g.fillRoundRect(ROW_INSET, top + 2, width - ROW_INSET * 2, ROW_HEIGHT - 4, 8, 8)
            }

            val markerColor = MARKER_COLORS[index % MARKER_COLORS.size]
            g.color = markerColor
            g.fillRoundRect(ROW_INSET + 2, top + 8, MARKER_SIZE, MARKER_SIZE, MARKER_SIZE, MARKER_SIZE)

            val textX = ROW_INSET + 18
            val rightLimit = width - ROW_INSET
            val source = suggestion.source.trim()
            val sourceWidth =
                if (source.isEmpty()) {
                    0
                } else {
                    sourceMetrics.stringWidth(source.uppercase(Locale.ROOT)) + SOURCE_HORIZONTAL_PADDING * 2
                }
            val textLimit = rightLimit - sourceWidth - if (sourceWidth == 0) 0 else SOURCE_GAP

            g.font = textFont
            g.color = TEXT
            g.drawString(ellipsize(suggestion.displayText, metrics, textLimit - textX), textX, top + 18)

            val detail = suggestion.detail.trim()
            if (detail.isNotEmpty()) {
                g.font = detailFont
                g.color = DETAIL
                g.drawString(ellipsize(detail, detailMetrics, textLimit - textX), textX, top + 34)
            }

            if (sourceWidth > 0) {
                paintSource(g, source, rightLimit - sourceWidth, top + 10, sourceWidth, sourceFont)
            }
            index++
        }
    }

    private fun paintSource(
        g: Graphics2D,
        source: String,
        x: Int,
        y: Int,
        sourceWidth: Int,
        sourceFont: Font,
    ) {
        val label = source.uppercase(Locale.ROOT)
        g.color = SOURCE_BACKGROUND
        g.fillRoundRect(x, y, sourceWidth, SOURCE_HEIGHT, SOURCE_ARC, SOURCE_ARC)
        g.font = sourceFont
        g.color = SOURCE_TEXT
        g.drawString(label, x + SOURCE_HORIZONTAL_PADDING, y + SOURCE_BASELINE)
    }

    private fun rowAt(y: Int): Int {
        if (y < VERTICAL_PADDING) return NO_SELECTION
        val row = (y - VERTICAL_PADDING) / ROW_HEIGHT
        return if (row in suggestions.indices && row < MAX_VISIBLE_ROWS) row else NO_SELECTION
    }

    private fun ellipsize(
        text: String,
        metrics: FontMetrics,
        maxWidth: Int,
    ): String {
        if (maxWidth <= 0) return ""
        if (metrics.stringWidth(text) <= maxWidth) return text

        var end = text.length
        while (end > 0) {
            val candidate = text.substring(0, end) + ELLIPSIS
            if (metrics.stringWidth(candidate) <= maxWidth) return candidate
            end--
        }
        return ""
    }

    private companion object {
        private const val NO_SELECTION = -1
        private const val MAX_VISIBLE_ROWS = 8
        private const val DEFAULT_WIDTH = 440
        private const val ROW_HEIGHT = 44
        private const val ROW_INSET = 8
        private const val VERTICAL_PADDING = 8
        private const val ARC = 14
        private const val MARKER_SIZE = 8
        private const val SOURCE_HEIGHT = 18
        private const val SOURCE_BASELINE = 13
        private const val SOURCE_ARC = 8
        private const val SOURCE_FONT_SIZE = 10f
        private const val SOURCE_HORIZONTAL_PADDING = 7
        private const val SOURCE_GAP = 10
        private const val MIN_DETAIL_FONT_SIZE = 10f
        private const val ELLIPSIS = "..."

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
