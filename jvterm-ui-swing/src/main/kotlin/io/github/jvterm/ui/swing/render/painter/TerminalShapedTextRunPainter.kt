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
package io.github.jvterm.ui.swing.render.painter

import io.github.jvterm.render.api.TerminalColorPalette
import io.github.jvterm.render.api.TerminalRenderAttrs
import io.github.jvterm.render.api.TerminalRenderCellFlags
import io.github.jvterm.render.cache.TerminalRenderCache
import io.github.jvterm.ui.swing.render.TerminalSwingColors
import io.github.jvterm.ui.swing.render.cache.AwtColorCache
import io.github.jvterm.ui.swing.render.cache.TerminalComplexTextLayoutCache
import io.github.jvterm.ui.swing.render.cache.TerminalFontCache
import io.github.jvterm.ui.swing.render.hasDrawableText
import io.github.jvterm.ui.swing.render.terminalFontStyle
import io.github.jvterm.ui.swing.settings.TerminalSwingMetrics
import java.awt.Graphics2D
import java.awt.font.FontRenderContext
import java.awt.font.TextLayout
import java.text.Bidi

/**
 * Paints row-shaped complex text spans that cannot be rendered correctly one
 * terminal cell at a time.
 *
 * This helper owns Unicode Bidirectional Algorithm row planning and
 * complex-script run shaping for Brahmic and Southeast Asian scripts. The
 * ordinary ASCII path remains in [TerminalTextPainter] so the common repaint
 * path does not pay for Bidi or script-run machinery.
 */
internal class TerminalShapedTextRunPainter(
    private val colorCache: AwtColorCache,
    private val decorationPainter: TerminalDecorationPainter,
    private val fontCache: TerminalFontCache,
    private val complexTextLayouts: TerminalComplexTextLayoutCache,
) {
    private var rowChars = CharArray(INITIAL_TEXT_RUN_CAPACITY)
    private var segmentCodepoints = IntArray(INITIAL_TEXT_RUN_CAPACITY)
    private var bidiRows = arrayOfNulls<Bidi>(0)
    private var rowGenerations = LongArray(0)
    private var rowHasStrongRtl = BooleanArray(0)
    private var cachedColumns = 0

    fun cachedRowContainsStrongRtl(
        cache: TerminalRenderCache,
        row: Int,
    ): Boolean {
        ensureBidiRowCache(cache)
        val generation = cache.lineGenerations[row]
        if (rowGenerations[row] == generation) return rowHasStrongRtl[row]

        val hasStrongRtl = rowContainsStrongRtl(cache, row)
        bidiRows[row] = null
        rowGenerations[row] = generation
        rowHasStrongRtl[row] = hasStrongRtl
        return hasStrongRtl
    }

    fun paintBidiRow(
        g: Graphics2D,
        cache: TerminalRenderCache,
        palette: TerminalColorPalette,
        metrics: TerminalSwingMetrics,
        row: Int,
        fontRenderContext: FontRenderContext,
        textBlinkVisible: Boolean,
        hoveredHyperlinkId: Int,
        hyperlinkActivationHover: Boolean,
        hyperlinkActivationForeground: Int,
    ) {
        val bidi = bidiForRow(cache, row)
        val baselineY = row * metrics.cellHeight + metrics.baseline
        var visualStartColumn = 0
        var runIndex = 0
        while (runIndex < bidi.runCount) {
            val runStart = bidi.getRunStart(runIndex)
            val runLimit = bidi.getRunLimit(runIndex)
            val rtlRun = bidi.getRunLevel(runIndex) and 1 != 0
            var segmentStart = runStart
            while (segmentStart < runLimit) {
                val segmentLimit =
                    bidiSegmentLimit(
                        cache = cache,
                        palette = palette,
                        row = row,
                        startColumn = segmentStart,
                        runLimit = runLimit,
                        textBlinkVisible = textBlinkVisible,
                        hoveredHyperlinkId = hoveredHyperlinkId,
                        hyperlinkActivationHover = hyperlinkActivationHover,
                        hyperlinkActivationForeground = hyperlinkActivationForeground,
                    )
                val segmentVisualStart =
                    if (rtlRun) {
                        visualStartColumn + runLimit - segmentLimit
                    } else {
                        visualStartColumn + segmentStart - runStart
                    }
                paintShapedLogicalSegment(
                    g = g,
                    cache = cache,
                    palette = palette,
                    metrics = metrics,
                    row = row,
                    startColumn = segmentStart,
                    endColumn = segmentLimit,
                    visualStartColumn = segmentVisualStart,
                    baselineY = baselineY,
                    fontRenderContext = fontRenderContext,
                    textBlinkVisible = textBlinkVisible,
                    hoveredHyperlinkId = hoveredHyperlinkId,
                    hyperlinkActivationHover = hyperlinkActivationHover,
                    hyperlinkActivationForeground = hyperlinkActivationForeground,
                )
                segmentStart = segmentLimit
            }
            visualStartColumn += runLimit - runStart
            runIndex++
        }
    }

    fun paintComplexShapingRun(
        g: Graphics2D,
        cache: TerminalRenderCache,
        palette: TerminalColorPalette,
        metrics: TerminalSwingMetrics,
        row: Int,
        startColumn: Int,
        baselineY: Int,
        fontRenderContext: FontRenderContext,
        textBlinkVisible: Boolean,
        hoveredHyperlinkId: Int,
        hyperlinkActivationHover: Boolean,
        hyperlinkActivationForeground: Int,
    ): Int {
        val endColumn =
            complexShapingRunEnd(
                cache = cache,
                palette = palette,
                row = row,
                startColumn = startColumn,
                textBlinkVisible = textBlinkVisible,
                hoveredHyperlinkId = hoveredHyperlinkId,
                hyperlinkActivationHover = hyperlinkActivationHover,
                hyperlinkActivationForeground = hyperlinkActivationForeground,
            )
        paintShapedLogicalSegment(
            g = g,
            cache = cache,
            palette = palette,
            metrics = metrics,
            row = row,
            startColumn = startColumn,
            endColumn = endColumn,
            visualStartColumn = startColumn,
            baselineY = baselineY,
            fontRenderContext = fontRenderContext,
            textBlinkVisible = textBlinkVisible,
            hoveredHyperlinkId = hoveredHyperlinkId,
            hyperlinkActivationHover = hyperlinkActivationHover,
            hyperlinkActivationForeground = hyperlinkActivationForeground,
        )
        return endColumn
    }

    fun isComplexShapingCell(
        cache: TerminalRenderCache,
        index: Int,
    ): Boolean {
        val flags = cache.flags[index]
        if (!hasDrawableText(flags) || flags and TerminalRenderCellFlags.WIDE_TRAILING != 0) return false
        if (flags and TerminalRenderCellFlags.CLUSTER != 0) {
            val clusterRef = cache.clusterRefs[index]
            if (clusterRef == 0L) return false
            val offset = cache.clusterOffset(clusterRef)
            val end = offset + cache.clusterLength(clusterRef)
            var clusterIndex = offset
            while (clusterIndex < end) {
                if (isComplexShapingCodePoint(cache.clusterCodepoints[clusterIndex])) return true
                clusterIndex++
            }
            return false
        }
        return isComplexShapingCodePoint(cache.codeWords[index])
    }

    private fun bidiForRow(
        cache: TerminalRenderCache,
        row: Int,
    ): Bidi {
        ensureBidiRowCache(cache)
        val generation = cache.lineGenerations[row]
        val cached = bidiRows[row]
        if (cached != null && rowGenerations[row] == generation) return cached

        ensureRowCharCapacity(cache.columns)
        fillBidiRowChars(cache, row)
        val bidi =
            Bidi(
                rowChars,
                0,
                null,
                0,
                cache.columns,
                Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT,
            )
        bidiRows[row] = bidi
        rowGenerations[row] = generation
        return bidi
    }

    private fun bidiSegmentLimit(
        cache: TerminalRenderCache,
        palette: TerminalColorPalette,
        row: Int,
        startColumn: Int,
        runLimit: Int,
        textBlinkVisible: Boolean,
        hoveredHyperlinkId: Int,
        hyperlinkActivationHover: Boolean,
        hyperlinkActivationForeground: Int,
    ): Int {
        val rowOffset = cache.rowOffset(row)
        val startIndex = rowOffset + startColumn
        val attr = cache.attrWords[startIndex]
        val extraAttr = cache.extraAttrWords[startIndex]
        val hyperlinkId = cache.hyperlinkIds[startIndex]
        val hovered = isHoveredHyperlink(hyperlinkId, hoveredHyperlinkId)
        val foreground =
            effectiveForeground(
                palette = palette,
                attr = attr,
                hovered = hovered,
                hyperlinkActivationHover = hyperlinkActivationHover,
                hyperlinkActivationForeground = hyperlinkActivationForeground,
            )
        val fontStyle = terminalFontStyle(attr)
        val decoration = decorationKey(attr, extraAttr)
        val blinkHidden = isBlinkHidden(attr, textBlinkVisible)
        var column = startColumn + 1
        while (column < runLimit) {
            val index = rowOffset + column
            val currentAttr = cache.attrWords[index]
            val currentExtraAttr = cache.extraAttrWords[index]
            val currentHyperlinkId = cache.hyperlinkIds[index]
            val currentHovered = isHoveredHyperlink(currentHyperlinkId, hoveredHyperlinkId)
            val currentForeground =
                effectiveForeground(
                    palette = palette,
                    attr = currentAttr,
                    hovered = currentHovered,
                    hyperlinkActivationHover = hyperlinkActivationHover,
                    hyperlinkActivationForeground = hyperlinkActivationForeground,
                )
            if (
                isBlinkHidden(currentAttr, textBlinkVisible) != blinkHidden ||
                currentForeground != foreground ||
                terminalFontStyle(currentAttr) != fontStyle ||
                decorationKey(currentAttr, currentExtraAttr) != decoration ||
                currentHyperlinkId != hyperlinkId
            ) {
                break
            }
            column++
        }
        return column
    }

    private fun complexShapingRunEnd(
        cache: TerminalRenderCache,
        palette: TerminalColorPalette,
        row: Int,
        startColumn: Int,
        textBlinkVisible: Boolean,
        hoveredHyperlinkId: Int,
        hyperlinkActivationHover: Boolean,
        hyperlinkActivationForeground: Int,
    ): Int {
        val rowOffset = cache.rowOffset(row)
        val startIndex = rowOffset + startColumn
        val attr = cache.attrWords[startIndex]
        val extraAttr = cache.extraAttrWords[startIndex]
        val hyperlinkId = cache.hyperlinkIds[startIndex]
        val hovered = isHoveredHyperlink(hyperlinkId, hoveredHyperlinkId)
        val foreground =
            effectiveForeground(
                palette = palette,
                attr = attr,
                hovered = hovered,
                hyperlinkActivationHover = hyperlinkActivationHover,
                hyperlinkActivationForeground = hyperlinkActivationForeground,
            )
        val fontStyle = terminalFontStyle(attr)
        val decoration = decorationKey(attr, extraAttr)
        var column = startColumn + 1
        while (column < cache.columns) {
            val index = rowOffset + column
            if (!isComplexShapingRunContinuation(cache, rowOffset, column)) break

            val currentAttr = cache.attrWords[index]
            val currentExtraAttr = cache.extraAttrWords[index]
            val currentHyperlinkId = cache.hyperlinkIds[index]
            val currentHovered = isHoveredHyperlink(currentHyperlinkId, hoveredHyperlinkId)
            val currentForeground =
                effectiveForeground(
                    palette = palette,
                    attr = currentAttr,
                    hovered = currentHovered,
                    hyperlinkActivationHover = hyperlinkActivationHover,
                    hyperlinkActivationForeground = hyperlinkActivationForeground,
                )
            if (
                isBlinkHidden(currentAttr, textBlinkVisible) ||
                currentForeground != foreground ||
                terminalFontStyle(currentAttr) != fontStyle ||
                decorationKey(currentAttr, currentExtraAttr) != decoration ||
                currentHyperlinkId != hyperlinkId
            ) {
                break
            }

            column++
        }
        return column
    }

    private fun isComplexShapingRunContinuation(
        cache: TerminalRenderCache,
        rowOffset: Int,
        column: Int,
    ): Boolean {
        val index = rowOffset + column
        if (isComplexShapingCell(cache, index)) return true
        if (isAsciiSpaceCell(cache.flags[index], cache.codeWords[index])) {
            val nextColumn = column + 1
            return nextColumn < cache.columns && isComplexShapingCell(cache, rowOffset + nextColumn)
        }
        return false
    }

    private fun paintShapedLogicalSegment(
        g: Graphics2D,
        cache: TerminalRenderCache,
        palette: TerminalColorPalette,
        metrics: TerminalSwingMetrics,
        row: Int,
        startColumn: Int,
        endColumn: Int,
        visualStartColumn: Int,
        baselineY: Int,
        fontRenderContext: FontRenderContext,
        textBlinkVisible: Boolean,
        hoveredHyperlinkId: Int,
        hyperlinkActivationHover: Boolean,
        hyperlinkActivationForeground: Int,
    ) {
        val rowOffset = cache.rowOffset(row)
        val index = rowOffset + startColumn
        val attr = cache.attrWords[index]
        if (isBlinkHidden(attr, textBlinkVisible)) return

        val extraAttr = cache.extraAttrWords[index]
        val hyperlinkId = cache.hyperlinkIds[index]
        val hovered = isHoveredHyperlink(hyperlinkId, hoveredHyperlinkId)
        val foreground =
            effectiveForeground(
                palette = palette,
                attr = attr,
                hovered = hovered,
                hyperlinkActivationHover = hyperlinkActivationHover,
                hyperlinkActivationForeground = hyperlinkActivationForeground,
            )
        val fontStyle = terminalFontStyle(attr)
        val cellPixelWidth = metrics.cellWidth * (endColumn - startColumn)
        val x = visualStartColumn * metrics.cellWidth
        val length = fillSegmentCodepoints(cache, row, startColumn, endColumn)
        if (length > 0) {
            g.font = fontCache.font(fontStyle)
            g.color = colorCache.color(foreground)
            val oldClip = g.clip
            try {
                g.clipRect(x, row * metrics.cellHeight, cellPixelWidth, metrics.cellHeight)
                val layout =
                    complexTextLayouts.clusterLayout(
                        segmentCodepoints,
                        0,
                        length,
                        fontStyle,
                        fontRenderContext,
                        fontCache,
                    )
                drawFittedLayout(g, layout, x.toFloat(), baselineY.toFloat(), x + cellPixelWidth)
            } finally {
                g.clip = oldClip
            }
        }

        decorationPainter.paint(
            g = g,
            palette = palette,
            attr = attr,
            extraAttr = extraAttr,
            foreground = foreground,
            startColumn = visualStartColumn,
            endColumn = visualStartColumn + endColumn - startColumn,
            row = row,
            metrics = metrics,
        )
        paintHyperlinkDecoration(
            g = g,
            hyperlinkId = hyperlinkId,
            hovered = hovered,
            color = foreground,
            startColumn = visualStartColumn,
            endColumn = visualStartColumn + endColumn - startColumn,
            row = row,
            metrics = metrics,
        )
    }

    private fun rowContainsStrongRtl(
        cache: TerminalRenderCache,
        row: Int,
    ): Boolean {
        val rowOffset = cache.rowOffset(row)
        var column = 0
        while (column < cache.columns) {
            val index = rowOffset + column
            val flags = cache.flags[index]
            if (hasDrawableText(flags)) {
                if (flags and TerminalRenderCellFlags.CLUSTER != 0) {
                    val clusterRef = cache.clusterRefs[index]
                    if (clusterRef != 0L) {
                        val offset = cache.clusterOffset(clusterRef)
                        val end = offset + cache.clusterLength(clusterRef)
                        var clusterIndex = offset
                        while (clusterIndex < end) {
                            if (isStrongRtl(cache.clusterCodepoints[clusterIndex])) return true
                            clusterIndex++
                        }
                    }
                } else if (isStrongRtl(cache.codeWords[index])) {
                    return true
                }
            }
            column++
        }
        return false
    }

    private fun ensureBidiRowCache(cache: TerminalRenderCache) {
        if (bidiRows.size == cache.rows && cachedColumns == cache.columns) return

        bidiRows = arrayOfNulls(cache.rows)
        rowGenerations = LongArray(cache.rows) { INVALID_GENERATION }
        rowHasStrongRtl = BooleanArray(cache.rows)
        cachedColumns = cache.columns
    }

    private fun fillBidiRowChars(
        cache: TerminalRenderCache,
        row: Int,
    ) {
        val rowOffset = cache.rowOffset(row)
        var column = 0
        while (column < cache.columns) {
            val index = rowOffset + column
            rowChars[column] = bidiClassChar(cache, index)
            column++
        }
    }

    private fun bidiClassChar(
        cache: TerminalRenderCache,
        index: Int,
    ): Char {
        val flags = cache.flags[index]
        if (!hasDrawableText(flags) || flags and TerminalRenderCellFlags.WIDE_TRAILING != 0) return ' '
        val codePoint =
            if (flags and TerminalRenderCellFlags.CLUSTER != 0) {
                val clusterRef = cache.clusterRefs[index]
                if (clusterRef == 0L) {
                    SPACE_CODE_POINT
                } else {
                    cache.clusterCodepoints[cache.clusterOffset(clusterRef)]
                }
            } else {
                cache.codeWords[index]
            }
        return if (codePoint in 0..0xffff) codePoint.toChar() else REPLACEMENT_CHAR
    }

    private fun fillSegmentCodepoints(
        cache: TerminalRenderCache,
        row: Int,
        startColumn: Int,
        endColumn: Int,
    ): Int {
        ensureSegmentCodepointCapacity((endColumn - startColumn) * MAX_CODEPOINTS_PER_CELL)
        val rowOffset = cache.rowOffset(row)
        var length = 0
        var column = startColumn
        while (column < endColumn) {
            val index = rowOffset + column
            val flags = cache.flags[index]
            if (!hasDrawableText(flags) || flags and TerminalRenderCellFlags.WIDE_TRAILING != 0) {
                segmentCodepoints[length++] = SPACE_CODE_POINT
            } else if (flags and TerminalRenderCellFlags.CLUSTER != 0) {
                val clusterRef = cache.clusterRefs[index]
                if (clusterRef == 0L) {
                    segmentCodepoints[length++] = SPACE_CODE_POINT
                } else {
                    val offset = cache.clusterOffset(clusterRef)
                    val clusterLength = cache.clusterLength(clusterRef)
                    ensureSegmentCodepointCapacity(length + clusterLength)
                    System.arraycopy(cache.clusterCodepoints, offset, segmentCodepoints, length, clusterLength)
                    length += clusterLength
                }
            } else {
                segmentCodepoints[length++] = cache.codeWords[index]
            }
            column++
        }
        return length
    }

    private fun ensureRowCharCapacity(columns: Int) {
        if (rowChars.size >= columns) return
        var capacity = rowChars.size
        while (capacity < columns) {
            capacity *= 2
        }
        rowChars = rowChars.copyOf(capacity)
    }

    private fun ensureSegmentCodepointCapacity(required: Int) {
        if (segmentCodepoints.size >= required) return
        var capacity = segmentCodepoints.size
        while (capacity < required) {
            capacity *= 2
        }
        segmentCodepoints = segmentCodepoints.copyOf(capacity)
    }

    private fun isAsciiSpaceCell(
        flags: Int,
        codeWord: Int,
    ): Boolean = flags == TerminalRenderCellFlags.CODEPOINT && codeWord == SPACE_CODE_POINT

    private fun isHoveredHyperlink(
        hyperlinkId: Int,
        hoveredHyperlinkId: Int,
    ): Boolean = hyperlinkId != NO_HYPERLINK_ID && hyperlinkId == hoveredHyperlinkId

    private fun effectiveForeground(
        palette: TerminalColorPalette,
        attr: Long,
        hovered: Boolean,
        hyperlinkActivationHover: Boolean,
        hyperlinkActivationForeground: Int,
    ): Int =
        if (hovered && hyperlinkActivationHover) {
            hyperlinkActivationForeground
        } else {
            TerminalSwingColors.foreground(palette, attr)
        }

    private fun isBlinkHidden(
        attr: Long,
        textBlinkVisible: Boolean,
    ): Boolean = !textBlinkVisible && TerminalRenderAttrs.isBlink(attr)

    private fun paintHyperlinkDecoration(
        g: Graphics2D,
        hyperlinkId: Int,
        hovered: Boolean,
        color: Int,
        startColumn: Int,
        endColumn: Int,
        row: Int,
        metrics: TerminalSwingMetrics,
    ) {
        if (hyperlinkId == NO_HYPERLINK_ID) return
        decorationPainter.paintHyperlink(
            g = g,
            color = color,
            startColumn = startColumn,
            endColumn = endColumn,
            row = row,
            metrics = metrics,
            hovered = hovered,
        )
    }

    private fun drawFittedLayout(
        g: Graphics2D,
        layout: TextLayout,
        x: Float,
        baselineY: Float,
        spanEndX: Int,
    ) {
        val available = spanEndX - x
        val advance = layout.advance
        if (available <= 0f || advance <= 0f) return
        if (advance <= available) {
            layout.draw(g, x, baselineY)
            return
        }

        val oldTransform = g.transform
        try {
            val scaleX = available / advance
            g.translate(x.toDouble(), 0.0)
            g.scale(scaleX.toDouble(), 1.0)
            layout.draw(g, 0f, baselineY)
        } finally {
            g.transform = oldTransform
        }
    }

    private fun decorationKey(
        attr: Long,
        extraAttr: Long,
    ): Long =
        TerminalRenderAttrs.underlineStyle(attr).toLong() or
            (if (TerminalRenderAttrs.isStrikethrough(attr)) STRIKETHROUGH_KEY else 0L) or
            (extraAttr shl EXTRA_ATTR_KEY_SHIFT)

    private companion object {
        private const val INITIAL_TEXT_RUN_CAPACITY = 256
        private const val STRIKETHROUGH_KEY = 1L shl 8
        private const val EXTRA_ATTR_KEY_SHIFT = 9
        private const val NO_HYPERLINK_ID = 0
        private const val SPACE_CODE_POINT = 0x20
        private const val MAX_CODEPOINTS_PER_CELL = 4
        private const val REPLACEMENT_CHAR = '\uFFFD'
        private const val INVALID_GENERATION = Long.MIN_VALUE

        @JvmStatic
        private fun isStrongRtl(codePoint: Int): Boolean =
            when (Character.getDirectionality(codePoint)) {
                Character.DIRECTIONALITY_RIGHT_TO_LEFT,
                Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC,
                -> true
                else -> false
            }

        @JvmStatic
        private fun isComplexShapingCodePoint(codePoint: Int): Boolean =
            codePoint in 0x0900..0x0DFF ||
                codePoint in 0x0E00..0x0EFF ||
                codePoint in 0x1780..0x17FF ||
                codePoint in 0x19E0..0x19FF ||
                codePoint in 0x1A20..0x1AAF ||
                codePoint in 0xA8E0..0xA8FF ||
                codePoint in 0xAA60..0xAA7F
    }
}
