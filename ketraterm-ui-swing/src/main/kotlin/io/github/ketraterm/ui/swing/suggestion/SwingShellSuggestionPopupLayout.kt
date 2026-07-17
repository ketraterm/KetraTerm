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

import java.awt.Font
import java.awt.FontMetrics
import java.util.Locale
import javax.swing.JComponent
import kotlin.math.min

internal const val POPUP_MAX_VISIBLE_ROWS = 8
internal const val POPUP_ROW_INSET = 8
internal const val POPUP_TEXT_LEFT_OFFSET = 18
internal const val POPUP_SOURCE_HORIZONTAL_PADDING = 7
internal const val POPUP_SOURCE_GAP = 10

/** Prepared, immutable row consumed directly by the popup paint loop. */
internal data class SwingShellSuggestionPopupRow(
    val displayText: String,
    val detail: String,
    val sourceLabel: String,
    val sourceWidth: Int,
)

/**
 * Allocation boundary for suggestion popup presentation.
 *
 * Fonts, normalized labels, measurements, and width-specific truncation are
 * rebuilt only when content, font, or component width changes. Selection-only
 * repaints reuse this state without running text preparation in the paint loop.
 */
internal class SwingShellSuggestionPopupLayout {
    private val rows = arrayOfNulls<SwingShellSuggestionPopupRow>(POPUP_MAX_VISIBLE_ROWS)

    var rowCount: Int = 0
        private set

    var textFont: Font = DEFAULT_FONT
        private set

    var detailFont: Font = DEFAULT_DETAIL_FONT
        private set

    var sourceFont: Font = DEFAULT_SOURCE_FONT
        private set

    fun prepare(
        component: JComponent,
        suggestions: List<SwingShellSuggestion>,
        availableWidth: Int,
    ) {
        prepareFonts(component.font ?: DEFAULT_FONT)
        val textMetrics = component.getFontMetrics(textFont)
        val detailMetrics = component.getFontMetrics(detailFont)
        val sourceMetrics = component.getFontMetrics(sourceFont)
        val count = min(suggestions.size, POPUP_MAX_VISIBLE_ROWS)
        var index = 0
        while (index < count) {
            val suggestion = suggestions[index]
            val sourceLabel =
                ellipsize(
                    text = suggestion.source.trim().uppercase(Locale.ROOT),
                    metrics = sourceMetrics,
                    maxWidth =
                        min(
                            MAX_SOURCE_LABEL_WIDTH,
                            (availableWidth - POPUP_ROW_INSET * 2 - POPUP_SOURCE_HORIZONTAL_PADDING * 2)
                                .coerceAtLeast(0),
                        ),
                )
            val sourceWidth =
                if (sourceLabel.isEmpty()) {
                    0
                } else {
                    sourceMetrics.stringWidth(sourceLabel) + POPUP_SOURCE_HORIZONTAL_PADDING * 2
                }
            val rightLimit = availableWidth - POPUP_ROW_INSET
            val textX = POPUP_ROW_INSET + POPUP_TEXT_LEFT_OFFSET
            val textLimit = rightLimit - sourceWidth - if (sourceWidth == 0) 0 else POPUP_SOURCE_GAP
            val maxTextWidth = textLimit - textX
            rows[index] =
                SwingShellSuggestionPopupRow(
                    displayText = ellipsize(suggestion.displayText, textMetrics, maxTextWidth),
                    detail = ellipsize(suggestion.detail.trim(), detailMetrics, maxTextWidth),
                    sourceLabel = sourceLabel,
                    sourceWidth = sourceWidth,
                )
            index++
        }
        while (index < rowCount) {
            rows[index] = null
            index++
        }
        rowCount = count
    }

    fun row(index: Int): SwingShellSuggestionPopupRow {
        require(index in 0 until rowCount) { "row index must be in 0 until $rowCount, was $index" }
        return checkNotNull(rows[index])
    }

    private fun prepareFonts(candidate: Font) {
        if (candidate == textFont) return
        textFont = candidate
        detailFont = candidate.deriveFont(Font.PLAIN, (candidate.size2D - 1f).coerceAtLeast(MIN_DETAIL_FONT_SIZE))
        sourceFont = candidate.deriveFont(Font.BOLD, SOURCE_FONT_SIZE)
    }

    private fun ellipsize(
        text: String,
        metrics: FontMetrics,
        maxWidth: Int,
    ): String {
        if (text.isEmpty() || maxWidth <= 0) return ""
        if (metrics.stringWidth(text) <= maxWidth) return text
        if (metrics.stringWidth(ELLIPSIS) > maxWidth) return ""

        var low = 0
        var high = text.length
        while (low < high) {
            val middle = (low + high + 1) ushr 1
            val boundary = text.safeUtf16BoundaryBefore(middle)
            if (metrics.stringWidth(text.substring(0, boundary)) + metrics.stringWidth(ELLIPSIS) <= maxWidth) {
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
        private const val SOURCE_FONT_SIZE = 10f
        private const val MAX_SOURCE_LABEL_WIDTH = 112
        private const val MIN_DETAIL_FONT_SIZE = 10f
        private const val ELLIPSIS = "..."
        private val DEFAULT_FONT = Font(Font.SANS_SERIF, Font.PLAIN, 12)
        private val DEFAULT_DETAIL_FONT = DEFAULT_FONT.deriveFont(Font.PLAIN, 11f)
        private val DEFAULT_SOURCE_FONT = DEFAULT_FONT.deriveFont(Font.BOLD, SOURCE_FONT_SIZE)
    }
}
