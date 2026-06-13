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
package io.github.jvterm.ui.swing.render

import io.github.jvterm.render.api.TerminalColorPalette
import io.github.jvterm.render.cache.TerminalRenderCache
import io.github.jvterm.ui.swing.api.CellSelection
import io.github.jvterm.ui.swing.render.cache.AwtColorCache
import io.github.jvterm.ui.swing.render.painter.*
import io.github.jvterm.ui.swing.search.TerminalSearchViewportHighlights
import io.github.jvterm.ui.swing.settings.TerminalSwingMetrics
import io.github.jvterm.ui.swing.settings.TerminalSwingSettings
import java.awt.Font
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Java2D renderer facade for cached primitive terminal frames.
 *
 * The facade owns renderer-local caches and delegates row backgrounds, text,
 * decorations, and cursor presentation to smaller collaborators. Callers keep a
 * single component-owned painter instance so those caches are reused across
 * paint calls.
 */
internal class TerminalGridPainter {
    private val colorCache = AwtColorCache()
    private val backgroundPainter = TerminalBackgroundPainter(colorCache)
    private val selectionPainter = TerminalSelectionPainter(colorCache)
    private val searchPainter = TerminalSearchPainter(colorCache)
    private val decorationPainter = TerminalDecorationPainter(colorCache)
    private val textPainter = TerminalTextPainter(colorCache, decorationPainter)
    private val cursorPainter = TerminalCursorPainter(colorCache, textPainter)
    private val clipScratch = Rectangle()

    /**
     * Clears [width] x [height] with the terminal default background.
     */
    fun clear(
        g: Graphics2D,
        palette: TerminalColorPalette,
        width: Int,
        height: Int,
    ) {
        backgroundPainter.clear(g, palette, width, height)
    }

    /**
     * Paints [cache] into the supplied graphics context.
     */
    fun paint(
        g: Graphics2D,
        cache: TerminalRenderCache,
        settings: TerminalSwingSettings,
        metrics: TerminalSwingMetrics,
        width: Int,
        height: Int,
        cursorBlinkVisible: Boolean,
        textBlinkVisible: Boolean = true,
        cursorVisible: Boolean = true,
        contentYOffset: Double = 0.0,
        selection: CellSelection? = null,
        searchHighlights: TerminalSearchViewportHighlights? = null,
        hoveredHyperlinkId: Int = 0,
        hyperlinkActivationHover: Boolean = false,
    ) {
        val palette = cache.palette
        textPainter.updateSettings(settings)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, settings.textAntialiasing)
        g.setRenderingHint(RenderingHints.KEY_TEXT_LCD_CONTRAST, TEXT_LCD_CONTRAST)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_NORMALIZE)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY)
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, settings.fractionalMetrics)
        g.font = textPainter.font(Font.PLAIN)
        val fontRenderContext = g.fontRenderContext

        val clip = g.getClipBounds(clipScratch)
        backgroundPainter.clear(g, palette, width, height)

        val padding = settings.padding
        val firstRow = firstPaintRow(clip, metrics, contentYOffset, padding.top)
        val rows = lastPaintRowExclusive(clip, cache, metrics, height, contentYOffset, padding.top, padding.bottom)
        g.translate(padding.left.toDouble(), padding.top.toDouble() + contentYOffset)
        try {
            var row = firstRow
            while (row < rows) {
                backgroundPainter.paintRow(g, cache, palette, metrics, row)
                searchPainter.paint(
                    g = g,
                    metrics = metrics,
                    row = row,
                    highlights = searchHighlights,
                    matchBackground = settings.searchMatchBackground,
                    activeMatchBackground = settings.searchActiveMatchBackground,
                )
                selectionPainter.paint(g, cache, metrics, row, selection, settings.selectionBackground)
                textPainter.paintRow(
                    g = g,
                    cache = cache,
                    palette = palette,
                    metrics = metrics,
                    row = row,
                    fontRenderContext = fontRenderContext,
                    textBlinkVisible = textBlinkVisible,
                    hoveredHyperlinkId = hoveredHyperlinkId,
                    hyperlinkActivationHover = hyperlinkActivationHover,
                    hyperlinkActivationForeground = settings.hyperlinkActivationForeground,
                )
                row++
            }

            cursorPainter.paint(
                g,
                cache,
                palette,
                metrics,
                cursorBlinkVisible,
                textBlinkVisible,
                fontRenderContext,
                cursorVisible = cursorVisible,
            )
        } finally {
            g.translate(-padding.left.toDouble(), -(padding.top.toDouble() + contentYOffset))
        }
    }

    private fun firstPaintRow(
        clip: Rectangle?,
        metrics: TerminalSwingMetrics,
        contentYOffset: Double,
        paddingTop: Int,
    ): Int {
        if (clip == null) return 0
        return maxOf(0, floor((clip.y - paddingTop - contentYOffset) / metrics.cellHeight).toInt())
    }

    private fun lastPaintRowExclusive(
        clip: Rectangle?,
        cache: TerminalRenderCache,
        metrics: TerminalSwingMetrics,
        componentHeight: Int,
        contentYOffset: Double,
        paddingTop: Int,
        paddingBottom: Int,
    ): Int {
        val visibleRows = minOf(cache.rows, maxOf(1, (componentHeight - paddingTop - paddingBottom) / metrics.cellHeight) + 2)
        if (clip == null || clip.height <= 0) return visibleRows

        val clipBottom = clip.y + clip.height
        if (clipBottom <= 0) return 0
        val clippedRows = ceil((clipBottom - paddingTop - contentYOffset) / metrics.cellHeight).toInt()
        return clippedRows.coerceIn(0, visibleRows)
    }

    private companion object {
        private const val TEXT_LCD_CONTRAST = 140
    }
}
