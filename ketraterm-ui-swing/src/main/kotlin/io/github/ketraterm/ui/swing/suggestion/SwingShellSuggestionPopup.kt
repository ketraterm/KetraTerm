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
import java.awt.font.TextAttribute
import java.awt.font.TextLayout
import java.text.AttributedString
import java.util.*
import javax.swing.JComponent
import javax.swing.SwingUtilities
import kotlin.math.ceil
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
    val matchedRanges: SwingShellSuggestionMatchRanges,
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
        val regularFont = component.font ?: DEFAULT_FONT
        val highlightFont = regularFont.deriveFont(Font.BOLD)
        val detailFont = regularFont.deriveFont(Font.PLAIN, (regularFont.size2D - 1f).coerceAtLeast(10f))
        val sourceFont = regularFont.deriveFont(Font.BOLD, 10f)

        val regularMetrics = component.getFontMetrics(regularFont)
        val highlightMetrics = component.getFontMetrics(highlightFont)
        val detailMetrics = component.getFontMetrics(detailFont)
        val sourceMetrics = component.getFontMetrics(sourceFont)
        val count = min(suggestions.size, POPUP_MAX_VISIBLE_ROWS)
        var maxNaturalWidth = POPUP_MIN_WIDTH

        var index = 0
        while (index < count) {
            val suggestion = suggestions[index]
            val sourceLabel =
                ellipsizePlainText(
                    text = formatSourceLabel(suggestion.source),
                    metrics = sourceMetrics,
                    maxWidth = min(SOURCE_LABEL_MAX_WIDTH, availableWidth - SOURCE_HORIZONTAL_RESERVE),
                )
            val sourceWidth = if (sourceLabel.isEmpty()) 0 else sourceMetrics.stringWidth(sourceLabel) + SOURCE_BADGE_PADDING
            val naturalPrimaryWidth =
                measureMixedTextWidth(
                    text = suggestion.displayText,
                    length = suggestion.displayText.length,
                    ranges = suggestion.matchedRanges,
                    regularMetrics = regularMetrics,
                    highlightMetrics = highlightMetrics,
                )
            val normalizedDetail = suggestion.detail.trim()
            val detailWidth = detailMetrics.stringWidth(normalizedDetail)
            val itemNaturalWidth =
                ROW_HORIZONTAL_RESERVE +
                    naturalPrimaryWidth +
                    (if (detailWidth > 0) detailWidth + DETAIL_GAP else 0) +
                    sourceWidth
            maxNaturalWidth = maxOf(maxNaturalWidth, itemNaturalWidth)

            val maxPrimaryWidth = (availableWidth - sourceWidth - PRIMARY_HORIZONTAL_RESERVE).coerceAtLeast(MIN_TEXT_WIDTH)
            val retainedPrefixLength =
                fittingMixedPrefixLength(
                    text = suggestion.displayText,
                    ranges = suggestion.matchedRanges,
                    regularMetrics = regularMetrics,
                    highlightMetrics = highlightMetrics,
                    maxWidth = maxPrimaryWidth,
                )
            val preparedDisplayText =
                when (retainedPrefixLength) {
                    suggestion.displayText.length -> suggestion.displayText
                    0 -> ""
                    else -> suggestion.displayText.substring(0, retainedPrefixLength) + ELLIPSIS
                }
            val preparedRanges = suggestion.matchedRanges.truncatedTo(preparedDisplayText, retainedPrefixLength)

            rows[index] =
                SwingShellSuggestionPopupRow(
                    displayText = preparedDisplayText,
                    detail = ellipsizePlainText(normalizedDetail, detailMetrics, maxPrimaryWidth),
                    sourceLabel = sourceLabel,
                    sourceWidth = sourceWidth,
                    accentRole = suggestion.accentRole,
                    matchedRanges = preparedRanges,
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

    private fun fittingMixedPrefixLength(
        text: String,
        ranges: SwingShellSuggestionMatchRanges,
        regularMetrics: FontMetrics,
        highlightMetrics: FontMetrics,
        maxWidth: Int,
    ): Int {
        if (text.isEmpty() || maxWidth <= 0) return 0
        if (measureMixedTextWidth(text, text.length, ranges, regularMetrics, highlightMetrics) <= maxWidth) {
            return text.length
        }
        val ellipsisWidth = regularMetrics.stringWidth(ELLIPSIS)
        if (ellipsisWidth >= maxWidth) return 0

        var low = 0
        var high = text.length
        var best = 0
        while (low <= high) {
            val middle = (low + high) ushr 1
            val boundary = text.scalarBoundaryBefore(middle)
            val width =
                measureMixedTextWidth(text, boundary, ranges, regularMetrics, highlightMetrics) + ellipsisWidth
            if (width <= maxWidth) {
                best = boundary
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return text.scalarBoundaryBefore(best)
    }

    private fun measureMixedTextWidth(
        text: String,
        length: Int,
        ranges: SwingShellSuggestionMatchRanges,
        regularMetrics: FontMetrics,
        highlightMetrics: FontMetrics,
    ): Int {
        var width = 0
        var characterOffset = 0
        var rangeIndex = 0
        while (rangeIndex < ranges.rangeCount) {
            val start = minOf(ranges.startOffset(rangeIndex), length)
            val end = minOf(ranges.endOffset(rangeIndex), length)
            if (start > characterOffset) {
                width += regularMetrics.stringWidth(text.substring(characterOffset, start))
            }
            if (end > start) {
                width += highlightMetrics.stringWidth(text.substring(start, end))
            }
            characterOffset = end
            if (characterOffset == length) break
            rangeIndex++
        }
        if (characterOffset < length) {
            width += regularMetrics.stringWidth(text.substring(characterOffset, length))
        }
        return width
    }

    private fun formatSourceLabel(source: String): String {
        val trimmed = source.trim()
        return when (trimmed.lowercase(Locale.ROOT)) {
            "mru" -> "MRU"
            "history" -> "HISTORY"
            "spec" -> "SPEC"
            "path" -> "PATH"
            "git" -> "GIT"
            "gradle" -> "GRADLE"
            else -> trimmed.uppercase(Locale.ROOT)
        }
    }

    private fun ellipsizePlainText(
        text: String,
        metrics: FontMetrics,
        maxWidth: Int,
    ): String {
        if (text.isEmpty() || maxWidth <= 0) return ""
        if (metrics.stringWidth(text) <= maxWidth) return text
        val ellipsisWidth = metrics.stringWidth(ELLIPSIS)
        if (ellipsisWidth >= maxWidth) return ""
        var low = 0
        var high = text.length
        var best = 0
        while (low <= high) {
            val middle = (low + high) ushr 1
            val boundary = text.scalarBoundaryBefore(middle)
            if (metrics.stringWidth(text.substring(0, boundary)) + ellipsisWidth <= maxWidth) {
                best = boundary
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return if (best == 0) "" else text.substring(0, text.scalarBoundaryBefore(best)) + ELLIPSIS
    }

    private fun String.scalarBoundaryBefore(offset: Int): Int =
        if (offset in 1 until length && Character.isHighSurrogate(this[offset - 1]) && Character.isLowSurrogate(this[offset])) {
            offset - 1
        } else {
            offset
        }

    private companion object {
        private const val SOURCE_LABEL_MAX_WIDTH = 112
        private const val SOURCE_HORIZONTAL_RESERVE = 28
        private const val SOURCE_BADGE_PADDING = 12
        private const val ROW_HORIZONTAL_RESERVE = 32
        private const val PRIMARY_HORIZONTAL_RESERVE = 40
        private const val DETAIL_GAP = 8
        private const val MIN_TEXT_WIDTH = 40
        private const val ELLIPSIS = "..."
        private val DEFAULT_FONT = Font(Font.SANS_SERIF, Font.PLAIN, 12)
    }
}

internal class SwingShellSuggestionPopup(
    private val listener: SwingShellSuggestionViewListener,
) : JComponent(),
    SwingShellSuggestionView {
    override val component: JComponent
        get() = this

    private var suggestions: List<SwingShellSuggestion> = emptyList()
    private var selectedIndex: Int = NO_SELECTION
    private val layout = SwingShellSuggestionPopupLayout()

    init {
        isOpaque = false
        isFocusable = false

        val mouseHandler =
            object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    if (!SwingUtilities.isLeftMouseButton(e)) return
                    val row = rowAt(e.y)
                    if (row != NO_SELECTION) listener.onSuggestionClicked(row)
                }

                override fun mouseMoved(e: MouseEvent) {
                    val row = rowAt(e.y)
                    if (row != NO_SELECTION) listener.onSuggestionHovered(row)
                }
            }
        addMouseListener(mouseHandler)
        addMouseMotionListener(mouseHandler)
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
        val graphics2D = graphics as? Graphics2D ?: return
        graphics2D.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val background = parent?.background ?: DEFAULT_BG
        val foreground = parent?.foreground ?: DEFAULT_FG
        val border = mix(background, foreground, 0.18)
        val selectionBackground = mix(background, foreground, 0.24)
        val detailColor = mix(foreground, background, 0.40)
        val sourceColor = mix(foreground, background, 0.20)
        val matchColor = mix(foreground, COLOR_MATCH_ACCENT, 0.70)
        val regularFont = font ?: DEFAULT_FONT
        val highlightFont = regularFont.deriveFont(Font.BOLD)
        val detailFont = regularFont.deriveFont(Font.PLAIN, (regularFont.size2D - 1f).coerceAtLeast(10f))
        val sourceFont = regularFont.deriveFont(Font.BOLD, 10f)

        graphics2D.color = background
        graphics2D.fillRoundRect(0, 0, width - 1, height - 1, SURFACE_ARC, SURFACE_ARC)
        graphics2D.color = border
        graphics2D.drawRoundRect(0, 0, width - 1, height - 1, SURFACE_ARC, SURFACE_ARC)

        var rowIndex = 0
        while (rowIndex < layout.rowCount) {
            val row = layout.row(rowIndex)
            val top = SURFACE_PADDING + rowIndex * ROW_HEIGHT
            if (rowIndex == selectedIndex) {
                graphics2D.color = selectionBackground
                graphics2D.fillRoundRect(4, top + 1, width - 8, ROW_HEIGHT - 2, SELECTION_ARC, SELECTION_ARC)
            }

            graphics2D.color = accentColor(row.accentRole, foreground)
            graphics2D.fillRoundRect(8, top + 5, 3, 18, 3, 3)
            val primaryRight =
                drawPrimaryText(
                    graphics2D = graphics2D,
                    row = row,
                    x = PRIMARY_X,
                    y = top + PRIMARY_BASELINE,
                    regularFont = regularFont,
                    highlightFont = highlightFont,
                    regularColor = foreground,
                    highlightColor = matchColor,
                )

            if (row.detail.isNotEmpty()) {
                val detailX = primaryRight + DETAIL_GAP
                if (detailX < width - row.sourceWidth - DETAIL_RIGHT_RESERVE) {
                    graphics2D.font = detailFont
                    graphics2D.color = detailColor
                    graphics2D.drawString(row.detail, detailX, top + PRIMARY_BASELINE)
                }
            }

            if (row.sourceWidth > 0) {
                val sourceX = width - row.sourceWidth - SOURCE_RIGHT_INSET
                graphics2D.color = border
                graphics2D.fillRoundRect(sourceX, top + 5, row.sourceWidth, 18, 4, 4)
                graphics2D.font = sourceFont
                graphics2D.color = sourceColor
                graphics2D.drawString(row.sourceLabel, sourceX + 6, top + 17)
            }
            rowIndex++
        }
    }

    private fun drawPrimaryText(
        graphics2D: Graphics2D,
        row: SwingShellSuggestionPopupRow,
        x: Int,
        y: Int,
        regularFont: Font,
        highlightFont: Font,
        regularColor: Color,
        highlightColor: Color,
    ): Int {
        val text = row.displayText
        if (text.isEmpty()) return x
        val attributedText = AttributedString(text)
        attributedText.addAttribute(TextAttribute.FONT, regularFont)
        attributedText.addAttribute(TextAttribute.FOREGROUND, regularColor)

        val ranges = row.matchedRanges
        var rangeIndex = 0
        while (rangeIndex < ranges.rangeCount) {
            val start = ranges.startOffset(rangeIndex)
            val end = ranges.endOffset(rangeIndex)
            attributedText.addAttribute(TextAttribute.FONT, highlightFont, start, end)
            attributedText.addAttribute(TextAttribute.FOREGROUND, highlightColor, start, end)
            rangeIndex++
        }
        val textLayout = TextLayout(attributedText.iterator, graphics2D.fontRenderContext)
        textLayout.draw(graphics2D, x.toFloat(), y.toFloat())
        return x + ceil(textLayout.advance.toDouble()).toInt()
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
        foreground: Color,
    ): Color =
        when (role) {
            SwingShellSuggestionAccentRole.COMMAND -> COLOR_COMMAND
            SwingShellSuggestionAccentRole.PATH -> COLOR_PATH
            SwingShellSuggestionAccentRole.OPTION -> COLOR_OPTION
            SwingShellSuggestionAccentRole.HISTORY -> COLOR_HISTORY
            SwingShellSuggestionAccentRole.OTHER -> foreground
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
        val overlayRatio = ratio.coerceIn(0.0, 1.0)
        val baseRatio = 1.0 - overlayRatio
        return Color(
            (base.red * baseRatio + overlay.red * overlayRatio).toInt().coerceIn(0, 255),
            (base.green * baseRatio + overlay.green * overlayRatio).toInt().coerceIn(0, 255),
            (base.blue * baseRatio + overlay.blue * overlayRatio).toInt().coerceIn(0, 255),
        )
    }

    private companion object {
        private const val NO_SELECTION = -1
        private const val ROW_HEIGHT = 28
        private const val SURFACE_PADDING = 4
        private const val SURFACE_ARC = 10
        private const val SELECTION_ARC = 6
        private const val PRIMARY_X = 20
        private const val PRIMARY_BASELINE = 19
        private const val DETAIL_GAP = 8
        private const val DETAIL_RIGHT_RESERVE = 16
        private const val SOURCE_RIGHT_INSET = 8

        private val DEFAULT_FONT = Font(Font.SANS_SERIF, Font.PLAIN, 12)
        private val DEFAULT_BG = Color(0x10, 0x14, 0x18)
        private val DEFAULT_FG = Color(0xE5, 0xE7, 0xEB)
        private val COLOR_COMMAND = Color(0x70, 0xD6, 0xB0)
        private val COLOR_PATH = Color(0x69, 0xA7, 0xFF)
        private val COLOR_OPTION = Color(0xFF, 0xC8, 0x57)
        private val COLOR_HISTORY = Color(0xD8, 0x92, 0xFF)
        private val COLOR_MATCH_ACCENT = Color(0x35, 0x92, 0xFF)
    }
}
