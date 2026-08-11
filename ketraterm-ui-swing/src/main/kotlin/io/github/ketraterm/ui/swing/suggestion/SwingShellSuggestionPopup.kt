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
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.*
import javax.swing.JComponent
import javax.swing.SwingUtilities
import kotlin.math.min

internal const val POPUP_MAX_VISIBLE_ROWS = 8
internal const val POPUP_MIN_WIDTH = 320
internal const val POPUP_MAX_WIDTH = 620

internal data class SwingShellSuggestionPopupRow(
    val displayText: String,
    val detail: String,
    val sourceLabel: String,
    val sourceWidth: Int,
    val accentRole: SwingShellSuggestionAccentRole,
)

internal class SwingShellSuggestionPopupLayout {
    private val rows = arrayOfNulls<SwingShellSuggestionPopupRow>(POPUP_MAX_VISIBLE_ROWS)

    var rowCount: Int = 0
        private set

    var preferredWidth: Int = POPUP_MIN_WIDTH
        private set

    fun prepare(
        component: JComponent,
        suggestions: List<SwingShellSuggestion>,
        availableWidth: Int,
    ) {
        val font = component.font ?: DEFAULT_FONT
        val fontMetrics = component.getFontMetrics(font)
        val sourceMetrics = component.getFontMetrics(font.deriveFont(Font.BOLD, 10f))
        val count = min(suggestions.size, POPUP_MAX_VISIBLE_ROWS)
        var maxNaturalWidth = POPUP_MIN_WIDTH

        var index = 0
        while (index < count) {
            val suggestion = suggestions[index]
            val formattedSource = formatSourceLabel(suggestion.source)
            val sourceLabel = ellipsize(formattedSource, sourceMetrics, min(112, availableWidth - 28))
            val sourceWidth = if (sourceLabel.isEmpty()) 0 else sourceMetrics.stringWidth(sourceLabel) + 12
            val textWidth = fontMetrics.stringWidth(suggestion.displayText)
            val detailWidth = fontMetrics.stringWidth(suggestion.detail.trim())

            val itemNaturalWidth = 32 + textWidth + (if (detailWidth > 0) detailWidth + 8 else 0) + sourceWidth
            maxNaturalWidth = maxOf(maxNaturalWidth, itemNaturalWidth)

            val maxTextWidth = (availableWidth - sourceWidth - 40).coerceAtLeast(40)
            rows[index] =
                SwingShellSuggestionPopupRow(
                    displayText = ellipsize(suggestion.displayText, fontMetrics, maxTextWidth),
                    detail = ellipsize(suggestion.detail.trim(), fontMetrics, maxTextWidth),
                    sourceLabel = sourceLabel,
                    sourceWidth = sourceWidth,
                    accentRole = suggestion.accentRole,
                )
            index++
        }
        while (index < rowCount) {
            rows[index] = null
            index++
        }
        rowCount = count
        preferredWidth = maxNaturalWidth.coerceIn(POPUP_MIN_WIDTH, POPUP_MAX_WIDTH)
    }

    fun row(index: Int): SwingShellSuggestionPopupRow {
        require(index in 0 until rowCount) { "row index must be in 0 until $rowCount, was $index" }
        return checkNotNull(rows[index])
    }

    private fun formatSourceLabel(source: String): String =
        when (source) {
            "mru", "MRU" -> "MRU"
            "history", "HISTORY" -> "HISTORY"
            "spec", "SPEC" -> "SPEC"
            "path", "PATH" -> "PATH"
            "stats", "STATS" -> "STATS"
            "git", "GIT" -> "GIT"
            "gradle", "GRADLE" -> "GRADLE"
            else -> source.trim().uppercase(Locale.ROOT)
        }

    private fun ellipsize(
        text: String,
        metrics: FontMetrics,
        maxWidth: Int,
    ): String {
        if (text.isEmpty() || maxWidth <= 0) return ""
        if (metrics.stringWidth(text) <= maxWidth) return text
        val ellipsisWidth = metrics.stringWidth(ELLIPSIS)
        if (ellipsisWidth > maxWidth) return ""

        var low = 0
        var high = text.length
        while (low < high) {
            val middle = (low + high + 1) ushr 1
            val boundary = text.safeUtf16BoundaryBefore(middle)
            if (metrics.stringWidth(text.substring(0, boundary)) + ellipsisWidth <= maxWidth) {
                low = middle
            } else {
                high = middle - 1
            }
        }
        val boundary = text.safeUtf16BoundaryBefore(low)
        return if (boundary == 0) ELLIPSIS else text.substring(0, boundary) + ELLIPSIS
    }

    private fun String.safeUtf16BoundaryBefore(offset: Int): Int =
        if (offset in 1 until length && Character.isHighSurrogate(this[offset - 1]) && Character.isLowSurrogate(this[offset])) {
            offset - 1
        } else {
            offset
        }

    private companion object {
        private const val ELLIPSIS = "..."
        private val DEFAULT_FONT = Font(Font.SANS_SERIF, Font.PLAIN, 12)
    }
}

internal class SwingShellSuggestionPopup(
    private val listener: SwingShellSuggestionViewListener,
) : JComponent(),
    SwingShellSuggestionView {
    private var suggestions: List<SwingShellSuggestion> = emptyList()
    private var selectedIndex: Int = NO_SELECTION
    private val layout = SwingShellSuggestionPopupLayout()

    override val component: JComponent get() = this

    init {
        isOpaque = false
        isFocusable = false
        addMouseMotionListener(
            object : MouseAdapter() {
                override fun mouseMoved(e: MouseEvent) {
                    val row = rowAt(e.y)
                    if (row != NO_SELECTION) listener.onSuggestionHovered(row)
                }
            },
        )
        addMouseListener(
            object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    if (SwingUtilities.isLeftMouseButton(e)) {
                        val row = rowAt(e.y)
                        if (row != NO_SELECTION) {
                            listener.onSuggestionClicked(row)
                            e.consume()
                        }
                    }
                }
            },
        )
        addComponentListener(
            object : ComponentAdapter() {
                override fun componentResized(e: ComponentEvent) {
                    prepareLayout()
                }
            },
        )
        addPropertyChangeListener("font") { prepareLayout() }
    }

    override fun update(
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
        val count = min(suggestions.size, POPUP_MAX_VISIBLE_ROWS)
        return Dimension(layout.preferredWidth, SURFACE_PADDING * 2 + count * ROW_HEIGHT)
    }

    override fun paintComponent(graphics: Graphics) {
        val g2 = graphics as? Graphics2D ?: return
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val bg = parent?.background ?: DEFAULT_BG
        val fg = parent?.foreground ?: DEFAULT_FG
        val border = mix(bg, fg, 0.18)
        val selectionBg = mix(bg, fg, 0.24)

        // Surface Fill & Border
        g2.color = bg
        g2.fillRoundRect(0, 0, width - 1, height - 1, SURFACE_ARC, SURFACE_ARC)
        g2.color = border
        g2.drawRoundRect(0, 0, width - 1, height - 1, SURFACE_ARC, SURFACE_ARC)

        // Rows Paint Loop
        val font = font ?: DEFAULT_FONT
        val detailFont = font.deriveFont(Font.PLAIN, (font.size2D - 1f).coerceAtLeast(10f))
        val sourceFont = font.deriveFont(Font.BOLD, 10f)

        val count = layout.rowCount
        var i = 0
        while (i < count) {
            val row = layout.row(i)
            val top = SURFACE_PADDING + i * ROW_HEIGHT

            if (i == selectedIndex) {
                g2.color = selectionBg
                g2.fillRoundRect(4, top + 1, width - 8, ROW_HEIGHT - 2, SELECTION_ARC, SELECTION_ARC)
            }

            // Accent Pill Indicator
            g2.color = accentColor(row.accentRole, fg)
            g2.fillRoundRect(8, top + 5, 3, 18, 3, 3)

            // Primary Text
            g2.font = font
            g2.color = fg
            g2.drawString(row.displayText, 20, top + PRIMARY_BASELINE)

            // Inline Detail
            if (row.detail.isNotEmpty()) {
                val primaryWidth = g2.fontMetrics.stringWidth(row.displayText)
                val detailX = 20 + primaryWidth + 8
                val maxDetailRight = width - row.sourceWidth - 16
                if (detailX < maxDetailRight) {
                    g2.font = detailFont
                    g2.color = mix(fg, bg, 0.40)
                    g2.drawString(row.detail, detailX, top + PRIMARY_BASELINE)
                }
            }

            // Source Badge
            if (row.sourceWidth > 0) {
                val sourceX = width - row.sourceWidth - 8
                g2.color = border
                g2.fillRoundRect(sourceX, top + 5, row.sourceWidth, 18, 4, 4)
                g2.font = sourceFont
                g2.color = mix(fg, bg, 0.20)
                g2.drawString(row.sourceLabel, sourceX + 6, top + 17)
            }

            i++
        }
    }

    private fun prepareLayout() {
        layout.prepare(
            component = this,
            suggestions = suggestions,
            availableWidth = if (width > 0) width - 8 else POPUP_MAX_WIDTH,
        )
    }

    private fun accentColor(
        role: SwingShellSuggestionAccentRole,
        fg: Color,
    ): Color =
        when (role) {
            SwingShellSuggestionAccentRole.COMMAND -> COLOR_COMMAND
            SwingShellSuggestionAccentRole.PATH -> COLOR_PATH
            SwingShellSuggestionAccentRole.OPTION -> COLOR_OPTION
            SwingShellSuggestionAccentRole.HISTORY -> COLOR_HISTORY
            SwingShellSuggestionAccentRole.OTHER -> fg
        }

    private fun rowAt(y: Int): Int {
        if (y < SURFACE_PADDING) return NO_SELECTION
        val row = (y - SURFACE_PADDING) / ROW_HEIGHT
        return if (row in 0 until layout.rowCount) row else NO_SELECTION
    }

    private fun mix(
        base: Color,
        overlay: Color,
        ratio: Double,
    ): Color {
        val r = ratio.coerceIn(0.0, 1.0)
        val br = 1.0 - r
        return Color(
            (base.red * br + overlay.red * r).toInt().coerceIn(0, 255),
            (base.green * br + overlay.green * r).toInt().coerceIn(0, 255),
            (base.blue * br + overlay.blue * r).toInt().coerceIn(0, 255),
        )
    }

    private companion object {
        private const val NO_SELECTION = -1
        private const val ROW_HEIGHT = 28
        private const val SURFACE_PADDING = 4
        private const val SURFACE_ARC = 10
        private const val SELECTION_ARC = 6
        private const val PRIMARY_BASELINE = 19

        private val DEFAULT_FONT = Font(Font.SANS_SERIF, Font.PLAIN, 12)
        private val DEFAULT_BG = Color(0x10, 0x14, 0x18)
        private val DEFAULT_FG = Color(0xE5, 0xE7, 0xEB)

        private val COLOR_COMMAND = Color(0x70, 0xD6, 0xB0)
        private val COLOR_PATH = Color(0x69, 0xA7, 0xFF)
        private val COLOR_OPTION = Color(0xFF, 0xC8, 0x57)
        private val COLOR_HISTORY = Color(0xD8, 0x92, 0xFF)
    }
}
