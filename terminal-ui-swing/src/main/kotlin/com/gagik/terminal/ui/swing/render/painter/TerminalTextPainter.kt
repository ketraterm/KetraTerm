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
package com.gagik.terminal.ui.swing.render.painter

import com.gagik.terminal.render.api.TerminalColorPalette
import com.gagik.terminal.render.api.TerminalRenderAttrs
import com.gagik.terminal.render.api.TerminalRenderCellFlags
import com.gagik.terminal.render.cache.TerminalRenderCache
import com.gagik.terminal.ui.swing.render.*
import com.gagik.terminal.ui.swing.render.cache.*
import com.gagik.terminal.ui.swing.render.font.TerminalTextRunBuffer
import com.gagik.terminal.ui.swing.render.primitives.TerminalCellPrimitivePainter
import com.gagik.terminal.ui.swing.render.primitives.TerminalPlatformEmojiPainter
import com.gagik.terminal.ui.swing.settings.TerminalSwingMetrics
import com.gagik.terminal.ui.swing.settings.TerminalSwingSettings
import java.awt.Font
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.font.FontRenderContext

/**
 * Paints terminal cell text runs and text-only cursor foreground.
 */
internal class TerminalTextPainter(
    private val colorCache: AwtColorCache,
    private val decorationPainter: TerminalDecorationPainter,
    private val platformEmojiPainter: TerminalPlatformEmojiPainter = TerminalPlatformEmojiPainter(),
) {
    private val fontCache = TerminalFontCache()
    private val complexTextLayouts = TerminalComplexTextLayoutCache()
    private val asciiGlyphVectors = TerminalAsciiGlyphVectorCache()
    private val asciiDrawChars = TerminalAsciiDrawCharsCache()
    private val cellPrimitives = TerminalCellPrimitivePainter()
    private val textRun = TerminalTextRunBuffer(INITIAL_TEXT_RUN_CAPACITY)
    private val clipBoundsScratch = Rectangle()

    /**
     * Updates font-dependent caches for a settings snapshot.
     */
    fun updateSettings(settings: TerminalSwingSettings) {
        if (fontCache.update(settings.font, settings.fallbackFonts, settings.useSystemFallbackFonts)) {
            complexTextLayouts.clear()
            asciiGlyphVectors.clear()
            asciiDrawChars.clear()
        }
    }

    /**
     * Returns a cached font for [style].
     */
    fun font(style: Int): Font = fontCache.font(style)

    /**
     * Paints all drawable text runs in [row].
     */
    fun paintRow(
        g: Graphics2D,
        cache: TerminalRenderCache,
        palette: TerminalColorPalette,
        metrics: TerminalSwingMetrics,
        row: Int,
        fontRenderContext: FontRenderContext,
    ) {
        val flagsPlane = cache.flags
        val codeWords = cache.codeWords
        val rowOffset = cache.rowOffset(row)
        val baselineY = row * metrics.cellHeight + metrics.baseline
        var column = 0

        while (column < cache.columns) {
            val index = rowOffset + column
            val flags = flagsPlane[index]
            if (!hasDrawableText(flags)) {
                column++
                continue
            }

            val codeWord = codeWords[index]
            column =
                if (isFastAsciiCell(flags, codeWord)) {
                    paintAsciiRun(
                        g = g,
                        cache = cache,
                        palette = palette,
                        metrics = metrics,
                        row = row,
                        startColumn = column,
                        baselineY = baselineY,
                        fontRenderContext = fontRenderContext,
                    )
                } else {
                    paintComplexCell(
                        g = g,
                        cache = cache,
                        palette = palette,
                        metrics = metrics,
                        row = row,
                        column = column,
                        baselineY = baselineY,
                        fontRenderContext = fontRenderContext,
                    )
                }
        }
    }

    /**
     * Paints one cell's text clipped to a block cursor cell.
     */
    fun paintCellForeground(
        g: Graphics2D,
        cache: TerminalRenderCache,
        metrics: TerminalSwingMetrics,
        column: Int,
        row: Int,
        columnSpan: Int = 1,
        foreground: Int,
        fontRenderContext: FontRenderContext,
    ) {
        val flagsPlane = cache.flags
        val attrWords = cache.attrWords
        val codeWords = cache.codeWords
        val clusterRefs = cache.clusterRefs
        val index = cache.rowOffset(row) + column
        val flags = flagsPlane[index]
        if (!hasDrawableText(flags)) return

        val attr = attrWords[index]
        val safeColumnSpan = maxOf(1, columnSpan)
        val oldClipBounds = g.getClipBounds(clipBoundsScratch) != null
        try {
            g.clipRect(
                column * metrics.cellWidth,
                row * metrics.cellHeight,
                metrics.cellWidth * safeColumnSpan,
                metrics.cellHeight,
            )
            g.font = fontCache.font(terminalFontStyle(attr))
            g.color = colorCache.color(foreground)

            val baselineY = row * metrics.cellHeight + metrics.baseline
            val codeWord = codeWords[index]
            if (flags and TerminalRenderCellFlags.CLUSTER == 0 && cellPrimitives.canPaint(codeWord)) {
                cellPrimitives.paint(g, codeWord, column, row, metrics)
            } else if (flags and TerminalRenderCellFlags.CLUSTER != 0) {
                val clusterRef = clusterRefs[index]
                if (clusterRef != 0L) {
                    val offset = cache.clusterOffset(clusterRef)
                    val length = cache.clusterLength(clusterRef)
                    val paintedEmoji =
                        platformEmojiPainter.paintCluster(
                            g = g,
                            codepoints = cache.clusterCodepoints,
                            offset = offset,
                            length = length,
                            column = column,
                            row = row,
                            columnSpan = safeColumnSpan,
                            metrics = metrics,
                        )
                    if (!paintedEmoji) {
                        drawComplexCluster(
                            g = g,
                            codepoints = cache.clusterCodepoints,
                            offset = offset,
                            length = length,
                            fontStyle = terminalFontStyle(attr),
                            x = column * metrics.cellWidth,
                            baselineY = baselineY,
                            fontRenderContext = fontRenderContext,
                        )
                    }
                }
            } else if (platformEmojiPainter.paintCodePoint(
                    g = g,
                    codePoint = codeWord,
                    column = column,
                    row = row,
                    columnSpan = safeColumnSpan,
                    metrics = metrics,
                )
            ) {
                // Painted by the native platform text stack.
            } else {
                drawComplexCodePoint(
                    g = g,
                    codePoint = codeWord,
                    fontStyle = terminalFontStyle(attr),
                    x = column * metrics.cellWidth,
                    baselineY = baselineY,
                    fontRenderContext = fontRenderContext,
                )
            }
        } finally {
            if (oldClipBounds) {
                g.setClip(
                    clipBoundsScratch.x,
                    clipBoundsScratch.y,
                    clipBoundsScratch.width,
                    clipBoundsScratch.height,
                )
            } else {
                g.setClip(null)
            }
        }
    }

    private fun paintAsciiRun(
        g: Graphics2D,
        cache: TerminalRenderCache,
        palette: TerminalColorPalette,
        metrics: TerminalSwingMetrics,
        row: Int,
        startColumn: Int,
        baselineY: Int,
        fontRenderContext: FontRenderContext,
    ): Int {
        val flagsPlane = cache.flags
        val attrWords = cache.attrWords
        val extraAttrWords = cache.extraAttrWords
        val codeWords = cache.codeWords
        val rowOffset = cache.rowOffset(row)
        val startIndex = rowOffset + startColumn
        val attr = attrWords[startIndex]
        val extraAttr = extraAttrWords[startIndex]
        val foreground = TerminalSwingColors.foreground(palette, attr)
        val fontStyle = terminalFontStyle(attr)
        val decoration = decorationKey(attr, extraAttr)
        var column = startColumn

        textRun.clear()
        while (column < cache.columns) {
            val index = rowOffset + column
            val flags = flagsPlane[index]
            val codeWord = codeWords[index]
            val currentAttr = attrWords[index]
            val currentExtraAttr = extraAttrWords[index]
            if (
                !isFastAsciiCell(flags, codeWord) ||
                TerminalSwingColors.foreground(palette, currentAttr) != foreground ||
                terminalFontStyle(currentAttr) != fontStyle ||
                decorationKey(currentAttr, currentExtraAttr) != decoration
            ) {
                break
            }

            textRun.appendAscii(codeWord)
            column++
        }

        g.font = fontCache.font(fontStyle)
        g.color = colorCache.color(foreground)
        drawAsciiRun(g, metrics, startColumn, baselineY, fontStyle, fontRenderContext)
        decorationPainter.paint(g, palette, attr, extraAttr, foreground, startColumn, column, row, metrics)
        return column
    }

    private fun paintComplexCell(
        g: Graphics2D,
        cache: TerminalRenderCache,
        palette: TerminalColorPalette,
        metrics: TerminalSwingMetrics,
        row: Int,
        column: Int,
        baselineY: Int,
        fontRenderContext: FontRenderContext,
    ): Int {
        val flagsPlane = cache.flags
        val attrWords = cache.attrWords
        val extraAttrWords = cache.extraAttrWords
        val codeWords = cache.codeWords
        val clusterRefs = cache.clusterRefs
        val index = cache.rowOffset(row) + column
        val flags = flagsPlane[index]
        val attr = attrWords[index]
        val extraAttr = extraAttrWords[index]
        val foreground = TerminalSwingColors.foreground(palette, attr)
        val fontStyle = terminalFontStyle(attr)
        val endColumn = minOf(cache.columns, column + cellSpan(flags))

        g.font = fontCache.font(fontStyle)
        g.color = colorCache.color(foreground)

        val codeWord = codeWords[index]
        if (flags and TerminalRenderCellFlags.CLUSTER == 0 && cellPrimitives.canPaint(codeWord)) {
            cellPrimitives.paint(g, codeWord, column, row, metrics)
        } else if (flags and TerminalRenderCellFlags.CLUSTER != 0) {
            val clusterRef = clusterRefs[index]
            if (clusterRef != 0L) {
                val offset = cache.clusterOffset(clusterRef)
                val length = cache.clusterLength(clusterRef)
                val paintedEmoji =
                    platformEmojiPainter.paintCluster(
                        g = g,
                        codepoints = cache.clusterCodepoints,
                        offset = offset,
                        length = length,
                        column = column,
                        row = row,
                        columnSpan = endColumn - column,
                        metrics = metrics,
                    )
                if (!paintedEmoji) {
                    drawComplexCluster(
                        g = g,
                        codepoints = cache.clusterCodepoints,
                        offset = offset,
                        length = length,
                        fontStyle = fontStyle,
                        x = column * metrics.cellWidth,
                        baselineY = baselineY,
                        fontRenderContext = fontRenderContext,
                    )
                }
            }
        } else if (platformEmojiPainter.paintCodePoint(
                g = g,
                codePoint = codeWord,
                column = column,
                row = row,
                columnSpan = endColumn - column,
                metrics = metrics,
            )
        ) {
            // Painted by the native platform text stack.
        } else {
            drawComplexCodePoint(
                g = g,
                codePoint = codeWord,
                fontStyle = fontStyle,
                x = column * metrics.cellWidth,
                baselineY = baselineY,
                fontRenderContext = fontRenderContext,
            )
        }

        decorationPainter.paint(g, palette, attr, extraAttr, foreground, column, endColumn, row, metrics)
        return endColumn
    }

    private fun drawAsciiRun(
        g: Graphics2D,
        metrics: TerminalSwingMetrics,
        startColumn: Int,
        baselineY: Int,
        fontStyle: Int,
        fontRenderContext: FontRenderContext,
    ) {
        if (asciiDrawChars.canDrawChars(g.font, fontStyle, metrics.cellWidth, fontRenderContext)) {
            g.drawChars(textRun.chars, 0, textRun.length, startColumn * metrics.cellWidth, baselineY)
            return
        }

        val glyphVector =
            asciiGlyphVectors.glyphVector(
                chars = textRun.chars,
                offset = 0,
                length = textRun.length,
                font = g.font,
                style = fontStyle,
                cellWidth = metrics.cellWidth,
                fontRenderContext = fontRenderContext,
            )
        g.drawGlyphVector(glyphVector, (startColumn * metrics.cellWidth).toFloat(), baselineY.toFloat())
    }

    private fun drawComplexCluster(
        g: Graphics2D,
        codepoints: IntArray,
        offset: Int,
        length: Int,
        fontStyle: Int,
        x: Int,
        baselineY: Int,
        fontRenderContext: FontRenderContext,
    ) {
        val shapedLength = minOf(length, TerminalComplexTextLayoutCache.MAX_CLUSTER_LENGTH)
        val baseline = baselineY.toFloat()
        var drawX = x.toFloat()

        val layout =
            complexTextLayouts
                .clusterLayout(codepoints, offset, shapedLength, fontStyle, fontRenderContext, fontCache)
        layout.draw(g, drawX, baseline)
        drawX += layout.advance

        var index = offset + shapedLength
        val end = offset + length
        while (index < end) {
            val codePointLayout =
                complexTextLayouts
                    .codePointLayout(codepoints[index], fontStyle, fontRenderContext, fontCache)
            codePointLayout.draw(g, drawX, baseline)
            drawX += codePointLayout.advance
            index++
        }
    }

    private fun drawComplexCodePoint(
        g: Graphics2D,
        codePoint: Int,
        fontStyle: Int,
        x: Int,
        baselineY: Int,
        fontRenderContext: FontRenderContext,
    ) {
        complexTextLayouts
            .codePointLayout(codePoint, fontStyle, fontRenderContext, fontCache)
            .draw(g, x.toFloat(), baselineY.toFloat())
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
    }
}
