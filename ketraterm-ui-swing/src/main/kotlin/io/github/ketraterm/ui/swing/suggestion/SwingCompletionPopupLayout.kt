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
import java.awt.font.FontRenderContext
import java.awt.font.TextAttribute
import java.awt.font.TextLayout
import java.awt.font.TextMeasurer
import java.text.AttributedString
import java.util.*
import java.util.regex.Pattern
import javax.swing.JComponent
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

internal const val SWING_COMPLETION_POPUP_MIN_WIDTH = 320
internal const val SWING_COMPLETION_POPUP_MAX_WIDTH = 640

/** One immutable, fully measured row consumed by the popup paint hot path. */
internal class SwingCompletionPopupRow(
    val displayText: String,
    val detailText: String,
    val sourceText: String,
    val accentRole: SwingShellSuggestionAccentRole,
    val matchedRanges: SwingShellSuggestionMatchRanges,
    val primaryLayout: TextLayout,
    val selectedPrimaryLayout: TextLayout,
    val detailLayout: TextLayout?,
    val sourceLayout: TextLayout?,
    val sourceBadgeWidth: Int,
    val accessibleName: String,
    val accessibleDescription: String,
    val tooltipText: String,
)

/**
 * Bounded layout cache for the standalone custom-painted completion surface.
 *
 * All provider-controlled strings are normalized, grapheme-safe truncated,
 * styled, and measured here. Painting subsequently consumes at most eight
 * immutable rows and creates no text, font, or color objects.
 */
internal class SwingCompletionPopupLayout {
    private val rows = arrayOfNulls<SwingCompletionPopupRow>(SwingShellSuggestionViewSnapshot.MAX_VISIBLE_SUGGESTIONS)

    var rowCount: Int = 0
        private set

    var preferredWidth: Int = SWING_COMPLETION_POPUP_MIN_WIDTH
        private set

    var rowHeight: Int = DEFAULT_ROW_HEIGHT
        private set

    var primaryBaseline: Int = DEFAULT_PRIMARY_BASELINE
        private set

    var detailBaseline: Int = DEFAULT_PRIMARY_BASELINE
        private set

    var sourceBaseline: Int = DEFAULT_PRIMARY_BASELINE
        private set

    var iconSize: Int = DEFAULT_ICON_SIZE
        private set

    var fontRenderContext: FontRenderContext? = null
        private set

    val preferredHeight: Int
        get() = if (rowCount == 0) 0 else SURFACE_PADDING * 2 + rowCount * rowHeight

    fun prepare(
        component: JComponent,
        suggestions: List<SwingShellSuggestion>,
        appearance: SwingCompletionPopupAppearance,
        availableWidth: Int,
        renderContext: FontRenderContext = component.getFontMetrics(appearance.primaryFont).fontRenderContext,
    ) {
        fontRenderContext = renderContext
        updateVerticalMetrics(component, appearance)

        val count = min(suggestions.size, rows.size)
        val measuredWidth = max(1, availableWidth)
        var maximumNaturalWidth = SWING_COMPLETION_POPUP_MIN_WIDTH
        var index = 0
        while (index < count) {
            val suggestion = suggestions[index]
            val boundedPrimary = boundedPrimary(suggestion.displayText, suggestion.matchedRanges)
            val detail = normalizeSecondary(suggestion.detail, MAX_DETAIL_CODE_UNITS)
            val source =
                normalizeSecondary(suggestion.sourceDisplayText, MAX_SOURCE_CODE_UNITS)
                    .uppercase(Locale.ROOT)
                    .let { normalizeSecondary(it, MAX_SOURCE_CODE_UNITS) }

            val naturalPrimaryWidth =
                mixedAdvance(
                    boundedPrimary.text,
                    boundedPrimary.ranges,
                    appearance.primaryFont,
                    appearance.matchedFont,
                    renderContext,
                )
            val naturalDetailWidth = plainAdvance(detail, appearance.detailFont, renderContext)
            val naturalSourceWidth = plainAdvance(source, appearance.sourceFont, renderContext)
            val naturalSourceBadgeWidth =
                if (source.isEmpty()) 0 else ceil(naturalSourceWidth.toDouble()).toInt() + SOURCE_HORIZONTAL_PADDING * 2
            maximumNaturalWidth =
                max(
                    maximumNaturalWidth,
                    PRIMARY_X +
                        ceil(naturalPrimaryWidth.toDouble()).toInt() +
                        (if (detail.isEmpty()) 0 else DETAIL_GAP + ceil(naturalDetailWidth.toDouble()).toInt()) +
                        (if (source.isEmpty()) 0 else SOURCE_GAP + naturalSourceBadgeWidth) +
                        RIGHT_CONTENT_INSET +
                        SCROLLBAR_RESERVE,
                )

            val sourceTextBudget =
                min(
                    SOURCE_TEXT_MAX_WIDTH,
                    measuredWidth -
                        PRIMARY_X -
                        MIN_PRIMARY_WIDTH -
                        SOURCE_GAP -
                        SOURCE_HORIZONTAL_PADDING * 2 -
                        RIGHT_CONTENT_INSET -
                        SCROLLBAR_RESERVE,
                ).coerceAtLeast(0)
            val preparedSource =
                truncatePlainText(
                    source,
                    appearance.sourceFont,
                    renderContext,
                    sourceTextBudget.toFloat(),
                )
            val sourceLayout = textLayoutOrNull(preparedSource, appearance.sourceFont, null, renderContext)
            val sourceBadgeWidth =
                sourceLayout?.let { ceil(it.advance.toDouble()).toInt() + SOURCE_HORIZONTAL_PADDING * 2 } ?: 0

            val bodyWidth =
                (
                    measuredWidth -
                        PRIMARY_X -
                        RIGHT_CONTENT_INSET -
                        SCROLLBAR_RESERVE -
                        (if (sourceBadgeWidth == 0) 0 else SOURCE_GAP + sourceBadgeWidth)
                ).coerceAtLeast(1)
            val detailBudget = detailBudget(bodyWidth, naturalPrimaryWidth, naturalDetailWidth, detail.isNotEmpty())
            val primaryBudget =
                (bodyWidth - if (detailBudget == 0) 0 else DETAIL_GAP + detailBudget)
                    .coerceAtLeast(1)
            val preparedPrimary =
                truncateMixedText(
                    boundedPrimary,
                    appearance.primaryFont,
                    appearance.matchedFont,
                    renderContext,
                    primaryBudget.toFloat(),
                )
            val preparedDetail =
                truncatePlainText(
                    detail,
                    appearance.detailFont,
                    renderContext,
                    detailBudget.toFloat(),
                )

            rows[index] =
                SwingCompletionPopupRow(
                    displayText = preparedPrimary.text,
                    detailText = preparedDetail,
                    sourceText = preparedSource,
                    accentRole = suggestion.accentRole,
                    matchedRanges = preparedPrimary.ranges,
                    primaryLayout =
                        styledLayout(
                            preparedPrimary,
                            appearance.primaryFont,
                            appearance.matchedFont,
                            appearance.palette.foreground,
                            appearance.palette.matchForeground,
                            renderContext,
                        ),
                    selectedPrimaryLayout =
                        styledLayout(
                            preparedPrimary,
                            appearance.primaryFont,
                            appearance.matchedFont,
                            appearance.palette.selectedForeground,
                            appearance.palette.selectedMatchForeground,
                            renderContext,
                        ),
                    detailLayout = textLayoutOrNull(preparedDetail, appearance.detailFont, null, renderContext),
                    sourceLayout = sourceLayout,
                    sourceBadgeWidth = sourceBadgeWidth,
                    accessibleName =
                        normalizeSecondary(suggestion.displayText, MAX_ACCESSIBLE_CODE_UNITS)
                            .ifEmpty { ACCESSIBLE_FALLBACK_NAME },
                    accessibleDescription = accessibleDescription(suggestion),
                    tooltipText = tooltipText(suggestion),
                )
            index++
        }
        while (index < rowCount) {
            rows[index] = null
            index++
        }
        rowCount = count
        preferredWidth = maximumNaturalWidth.coerceIn(SWING_COMPLETION_POPUP_MIN_WIDTH, SWING_COMPLETION_POPUP_MAX_WIDTH)
    }

    fun clear() {
        var index = 0
        while (index < rowCount) {
            rows[index] = null
            index++
        }
        rowCount = 0
        preferredWidth = SWING_COMPLETION_POPUP_MIN_WIDTH
        fontRenderContext = null
    }

    fun row(index: Int): SwingCompletionPopupRow {
        require(index in 0 until rowCount) { "row index must be in 0 until $rowCount, was $index" }
        return checkNotNull(rows[index])
    }

    fun visibleRowCapacity(componentHeight: Int): Int {
        if (rowCount == 0 || componentHeight <= SURFACE_PADDING * 2) return 0
        return ((componentHeight - SURFACE_PADDING * 2) / rowHeight).coerceIn(1, rowCount)
    }

    fun firstVisibleRow(
        componentHeight: Int,
        selectedIndex: Int,
    ): Int {
        val capacity = visibleRowCapacity(componentHeight)
        if (capacity == 0 || capacity >= rowCount) return 0
        if (selectedIndex !in 0 until rowCount) return 0
        return (selectedIndex - capacity / 2).coerceIn(0, rowCount - capacity)
    }

    private fun updateVerticalMetrics(
        component: JComponent,
        appearance: SwingCompletionPopupAppearance,
    ) {
        val primaryMetrics = component.getFontMetrics(appearance.primaryFont)
        val detailMetrics = component.getFontMetrics(appearance.detailFont)
        val sourceMetrics = component.getFontMetrics(appearance.sourceFont)
        iconSize = ceil(appearance.primaryFont.size2D * ICON_FONT_RATIO).toInt().coerceIn(MIN_ICON_SIZE, MAX_ICON_SIZE)
        val contentHeight = max(max(primaryMetrics.height, detailMetrics.height), max(sourceMetrics.height, iconSize))
        rowHeight = max(MIN_ROW_HEIGHT, contentHeight + ROW_VERTICAL_PADDING * 2)
        primaryBaseline = centeredBaseline(rowHeight, primaryMetrics.ascent, primaryMetrics.descent)
        detailBaseline = centeredBaseline(rowHeight, detailMetrics.ascent, detailMetrics.descent)
        sourceBaseline = centeredBaseline(rowHeight, sourceMetrics.ascent, sourceMetrics.descent)
    }

    private fun detailBudget(
        bodyWidth: Int,
        naturalPrimaryWidth: Float,
        naturalDetailWidth: Float,
        hasDetail: Boolean,
    ): Int {
        if (!hasDetail || bodyWidth < MIN_PRIMARY_WIDTH + DETAIL_GAP + MIN_DETAIL_WIDTH) return 0
        var budget = min(ceil(naturalDetailWidth.toDouble()).toInt(), bodyWidth * DETAIL_WIDTH_PERCENT / 100)
        budget = max(MIN_DETAIL_WIDTH, budget)
        budget = min(budget, bodyWidth - DETAIL_GAP - MIN_PRIMARY_WIDTH)
        val unusedPrimarySpace =
            (bodyWidth - DETAIL_GAP - budget - ceil(naturalPrimaryWidth.toDouble()).toInt()).coerceAtLeast(0)
        return min(ceil(naturalDetailWidth.toDouble()).toInt(), budget + unusedPrimarySpace)
    }

    private fun boundedPrimary(
        text: String,
        ranges: SwingShellSuggestionMatchRanges,
    ): PreparedPrimary {
        val retainedLength = boundedInputPrefixLength(text, MAX_PRIMARY_CODE_UNITS)
        val prefix = sanitizePrimary(text, retainedLength)
        val truncated = retainedLength < text.length
        val preparedText = if (truncated) prefix + ELLIPSIS else prefix
        return PreparedPrimary(
            text = preparedText,
            ranges = ranges.truncatedTo(preparedText, retainedLength),
            retainedPrefixLength = retainedLength,
            wasTruncated = truncated,
        )
    }

    private fun truncateMixedText(
        bounded: PreparedPrimary,
        regularFont: Font,
        matchedFont: Font,
        renderContext: FontRenderContext,
        maxWidth: Float,
    ): PreparedPrimary {
        if (maxWidth <= 0f) return PreparedPrimary(ELLIPSIS, SwingShellSuggestionMatchRanges.EMPTY, 0, true)
        if (mixedAdvance(bounded.text, bounded.ranges, regularFont, matchedFont, renderContext) <= maxWidth) return bounded

        val ellipsisWidth = plainAdvance(ELLIPSIS, regularFont, renderContext)
        if (ellipsisWidth >= maxWidth) {
            return PreparedPrimary(ELLIPSIS, SwingShellSuggestionMatchRanges.EMPTY, 0, true)
        }
        val prefixLength = if (bounded.wasTruncated) bounded.retainedPrefixLength else bounded.text.length
        val prefix = bounded.text.substring(0, prefixLength)
        val prefixRanges = bounded.ranges.truncatedTo(prefix, prefix.length)
        val breakOffset =
            styledMeasurer(prefix, prefixRanges, regularFont, matchedFont, renderContext)
                ?.getLineBreakIndex(0, maxWidth - ellipsisWidth)
                ?: 0
        val retainedLength = graphemePrefixLength(prefix, breakOffset)
        val preparedText = prefix.substring(0, retainedLength) + ELLIPSIS
        return PreparedPrimary(
            text = preparedText,
            ranges = prefixRanges.truncatedTo(preparedText, retainedLength),
            retainedPrefixLength = retainedLength,
            wasTruncated = true,
        )
    }

    private fun styledLayout(
        text: PreparedPrimary,
        regularFont: Font,
        matchedFont: Font,
        foreground: java.awt.Color,
        matchedForeground: java.awt.Color,
        renderContext: FontRenderContext,
    ): TextLayout {
        val attributed = AttributedString(text.text)
        attributed.addAttribute(TextAttribute.FONT, regularFont)
        attributed.addAttribute(TextAttribute.FOREGROUND, foreground)
        var rangeIndex = 0
        while (rangeIndex < text.ranges.rangeCount) {
            val start = text.ranges.startOffset(rangeIndex)
            val end = text.ranges.endOffset(rangeIndex)
            attributed.addAttribute(TextAttribute.FONT, matchedFont, start, end)
            attributed.addAttribute(TextAttribute.FOREGROUND, matchedForeground, start, end)
            rangeIndex++
        }
        return TextLayout(attributed.iterator, renderContext)
    }

    private fun styledMeasurer(
        text: String,
        ranges: SwingShellSuggestionMatchRanges,
        regularFont: Font,
        matchedFont: Font,
        renderContext: FontRenderContext,
    ): TextMeasurer? {
        if (text.isEmpty()) return null
        val attributed = AttributedString(text)
        attributed.addAttribute(TextAttribute.FONT, regularFont)
        var rangeIndex = 0
        while (rangeIndex < ranges.rangeCount) {
            attributed.addAttribute(
                TextAttribute.FONT,
                matchedFont,
                ranges.startOffset(rangeIndex),
                ranges.endOffset(rangeIndex),
            )
            rangeIndex++
        }
        return TextMeasurer(attributed.iterator, renderContext)
    }

    private fun mixedAdvance(
        text: String,
        ranges: SwingShellSuggestionMatchRanges,
        regularFont: Font,
        matchedFont: Font,
        renderContext: FontRenderContext,
    ): Float = styledMeasurer(text, ranges, regularFont, matchedFont, renderContext)?.getAdvanceBetween(0, text.length) ?: 0f

    private fun plainAdvance(
        text: String,
        font: Font,
        renderContext: FontRenderContext,
    ): Float = if (text.isEmpty()) 0f else TextLayout(text, font, renderContext).advance

    private fun textLayoutOrNull(
        text: String,
        font: Font,
        color: java.awt.Color?,
        renderContext: FontRenderContext,
    ): TextLayout? {
        if (text.isEmpty()) return null
        if (color == null) return TextLayout(text, font, renderContext)
        val attributed = AttributedString(text)
        attributed.addAttribute(TextAttribute.FONT, font)
        attributed.addAttribute(TextAttribute.FOREGROUND, color)
        return TextLayout(attributed.iterator, renderContext)
    }

    private fun truncatePlainText(
        text: String,
        font: Font,
        renderContext: FontRenderContext,
        maxWidth: Float,
    ): String {
        if (text.isEmpty() || maxWidth <= 0f) return ""
        if (plainAdvance(text, font, renderContext) <= maxWidth) return text
        val ellipsisWidth = plainAdvance(ELLIPSIS, font, renderContext)
        if (ellipsisWidth >= maxWidth) return ""
        val attributed = AttributedString(text)
        attributed.addAttribute(TextAttribute.FONT, font)
        val breakOffset = TextMeasurer(attributed.iterator, renderContext).getLineBreakIndex(0, maxWidth - ellipsisWidth)
        val retainedLength = graphemePrefixLength(text, breakOffset)
        return if (retainedLength == 0) "" else text.substring(0, retainedLength) + ELLIPSIS
    }

    private fun sanitizePrimary(
        text: String,
        retainedLength: Int,
    ): String {
        var firstControl = -1
        var index = 0
        while (index < retainedLength) {
            if (text[index].isUnsafeDisplayControl()) {
                firstControl = index
                break
            }
            index++
        }
        if (firstControl < 0) return text.substring(0, retainedLength)
        val result = StringBuilder(retainedLength)
        result.append(text, 0, firstControl)
        index = firstControl
        while (index < retainedLength) {
            val character = text[index]
            result.append(if (character.isUnsafeDisplayControl()) REPLACEMENT_CHARACTER else character)
            index++
        }
        return result.toString()
    }

    private fun normalizeSecondary(
        text: String,
        maximumCodeUnits: Int,
    ): String {
        if (text.isEmpty()) return ""
        val inspectedLength = boundedInputPrefixLength(text, maximumCodeUnits)
        val result = StringBuilder(min(inspectedLength, maximumCodeUnits))
        var whitespacePending = false
        var index = 0
        while (index < inspectedLength) {
            val codePoint = text.codePointAt(index)
            if (Character.isWhitespace(codePoint) || codePoint.isUnsafeDisplayControl()) {
                whitespacePending = result.isNotEmpty()
            } else {
                if (whitespacePending) result.append(' ')
                result.appendCodePoint(codePoint)
                whitespacePending = false
            }
            index += Character.charCount(codePoint)
        }
        if (inspectedLength < text.length) {
            if (result.isEmpty()) return ELLIPSIS
            result.append(ELLIPSIS)
        }
        return result.toString()
    }

    private fun boundedInputPrefixLength(
        text: String,
        maximumCodeUnits: Int,
    ): Int {
        if (maximumCodeUnits <= 0 || text.isEmpty()) return 0
        if (text.length <= maximumCodeUnits) return text.length
        var rawLimit = maximumCodeUnits
        if (
            rawLimit in 1 until text.length &&
            Character.isHighSurrogate(text[rawLimit - 1]) &&
            Character.isLowSurrogate(text[rawLimit])
        ) {
            rawLimit--
        }
        if (rawLimit == 0) return 0

        val boundedPrefix = text.substring(0, rawLimit)
        val matcher = EXTENDED_GRAPHEME_CLUSTER.matcher(boundedPrefix)
        var previousBoundary = 0
        var lastBoundary = 0
        while (matcher.find()) {
            previousBoundary = lastBoundary
            lastBoundary = matcher.end()
        }
        // The final cluster may continue past the raw bound. Dropping only that
        // cluster keeps work bounded without ever displaying a partial grapheme.
        return previousBoundary
    }

    private fun graphemePrefixLength(
        text: String,
        maximumOffset: Int,
    ): Int {
        if (maximumOffset <= 0 || text.isEmpty()) return 0
        if (maximumOffset >= text.length) return text.length
        val matcher = EXTENDED_GRAPHEME_CLUSTER.matcher(text)
        var boundary = 0
        while (matcher.find()) {
            if (matcher.end() > maximumOffset) break
            boundary = matcher.end()
        }
        return boundary
    }

    private fun Char.isUnsafeDisplayControl(): Boolean = code.isUnsafeDisplayControl()

    private fun Int.isUnsafeDisplayControl(): Boolean =
        Character.isISOControl(this) ||
            this == ARABIC_LETTER_MARK ||
            this == LEFT_TO_RIGHT_MARK ||
            this == RIGHT_TO_LEFT_MARK ||
            this in BIDI_EMBEDDING_START..BIDI_EMBEDDING_END ||
            this in BIDI_ISOLATE_START..BIDI_ISOLATE_END

    private fun accessibleDescription(suggestion: SwingShellSuggestion): String {
        val detail = normalizeSecondary(suggestion.detail, MAX_ACCESSIBLE_CODE_UNITS)
        val source = normalizeSecondary(suggestion.sourceDisplayText, MAX_SOURCE_CODE_UNITS)
        return listOfNotEmpty(detail, source).joinToString(ACCESSIBLE_SEPARATOR)
    }

    private fun tooltipText(suggestion: SwingShellSuggestion): String {
        val primary = normalizeSecondary(suggestion.displayText, MAX_TOOLTIP_CODE_UNITS)
        val detail = normalizeSecondary(suggestion.detail, MAX_TOOLTIP_CODE_UNITS)
        val source = normalizeSecondary(suggestion.sourceDisplayText, MAX_SOURCE_CODE_UNITS)
        return listOfNotEmpty(primary, detail, source).joinToString(TOOLTIP_SEPARATOR)
    }

    private fun listOfNotEmpty(
        first: String,
        second: String,
    ): List<String> =
        buildList(2) {
            if (first.isNotEmpty()) add(first)
            if (second.isNotEmpty()) add(second)
        }

    private fun listOfNotEmpty(
        first: String,
        second: String,
        third: String,
    ): List<String> =
        buildList(3) {
            if (first.isNotEmpty()) add(first)
            if (second.isNotEmpty()) add(second)
            if (third.isNotEmpty()) add(third)
        }

    private fun centeredBaseline(
        height: Int,
        ascent: Int,
        descent: Int,
    ): Int = (height - ascent - descent) / 2 + ascent

    private data class PreparedPrimary(
        val text: String,
        val ranges: SwingShellSuggestionMatchRanges,
        val retainedPrefixLength: Int,
        val wasTruncated: Boolean,
    )

    companion object {
        internal const val SURFACE_PADDING = 4
        internal const val PRIMARY_X = 30
        internal const val RIGHT_CONTENT_INSET = 10
        internal const val DETAIL_GAP = 9
        internal const val SOURCE_GAP = 10
        internal const val SOURCE_HORIZONTAL_PADDING = 6
        internal const val SOURCE_VERTICAL_PADDING = 2
        internal const val SCROLLBAR_RESERVE = 8

        private const val DEFAULT_ROW_HEIGHT = 28
        private const val DEFAULT_PRIMARY_BASELINE = 19
        private const val DEFAULT_ICON_SIZE = 12
        private const val MIN_ROW_HEIGHT = 28
        private const val ROW_VERTICAL_PADDING = 5
        private const val MIN_ICON_SIZE = 12
        private const val MAX_ICON_SIZE = 18
        private const val ICON_FONT_RATIO = 0.9
        private const val MIN_PRIMARY_WIDTH = 56
        private const val MIN_DETAIL_WIDTH = 44
        private const val DETAIL_WIDTH_PERCENT = 38
        private const val SOURCE_TEXT_MAX_WIDTH = 108
        private const val MAX_PRIMARY_CODE_UNITS = 4096
        private const val MAX_DETAIL_CODE_UNITS = 1024
        private const val MAX_SOURCE_CODE_UNITS = 128
        private const val MAX_ACCESSIBLE_CODE_UNITS = 512
        private const val MAX_TOOLTIP_CODE_UNITS = 1024
        private const val ELLIPSIS = "…"
        private const val REPLACEMENT_CHARACTER = '\uFFFD'
        private const val ACCESSIBLE_SEPARATOR = ", "
        private const val TOOLTIP_SEPARATOR = " — "
        private const val ACCESSIBLE_FALLBACK_NAME = "Completion suggestion"
        private const val ARABIC_LETTER_MARK = 0x061C
        private const val LEFT_TO_RIGHT_MARK = 0x200E
        private const val RIGHT_TO_LEFT_MARK = 0x200F
        private const val BIDI_EMBEDDING_START = 0x202A
        private const val BIDI_EMBEDDING_END = 0x202E
        private const val BIDI_ISOLATE_START = 0x2066
        private const val BIDI_ISOLATE_END = 0x2069
        private val EXTENDED_GRAPHEME_CLUSTER: Pattern = Pattern.compile("\\X")
    }
}
