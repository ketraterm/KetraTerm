package com.gagik.terminal.ui.swing.render

import com.gagik.terminal.render.api.TerminalColorPalette
import com.gagik.terminal.render.cache.TerminalRenderCache
import com.gagik.terminal.ui.swing.api.CellSelection
import com.gagik.terminal.ui.swing.render.cache.AwtColorCache
import com.gagik.terminal.ui.swing.render.painter.*
import com.gagik.terminal.ui.swing.settings.TerminalSwingMetrics
import com.gagik.terminal.ui.swing.settings.TerminalSwingSettings
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
        contentYOffset: Double = 0.0,
        selection: CellSelection? = null,
    ) {
        val palette = settings.palette
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

        val firstRow = firstPaintRow(clip, metrics, contentYOffset)
        val rows = lastPaintRowExclusive(clip, cache, metrics, height, contentYOffset)
        g.translate(0.0, contentYOffset)
        try {
            var row = firstRow
            while (row < rows) {
                backgroundPainter.paintRow(g, cache, palette, metrics, row)
                selectionPainter.paint(g, cache, metrics, row, selection, settings.selectionBackground)
                textPainter.paintRow(g, cache, palette, metrics, row, fontRenderContext)
                row++
            }

            cursorPainter.paint(g, cache, palette, metrics, cursorBlinkVisible, fontRenderContext)
        } finally {
            g.translate(0.0, -contentYOffset)
        }
    }

    private fun firstPaintRow(
        clip: Rectangle?,
        metrics: TerminalSwingMetrics,
        contentYOffset: Double,
    ): Int {
        if (clip == null) return 0
        return maxOf(0, floor((clip.y - contentYOffset) / metrics.cellHeight).toInt())
    }

    private fun lastPaintRowExclusive(
        clip: Rectangle?,
        cache: TerminalRenderCache,
        metrics: TerminalSwingMetrics,
        componentHeight: Int,
        contentYOffset: Double,
    ): Int {
        val visibleRows = minOf(cache.rows, componentHeight / metrics.cellHeight + 2)
        if (clip == null || clip.height <= 0) return visibleRows

        val clipBottom = clip.y + clip.height
        if (clipBottom <= 0) return 0
        val clippedRows = ceil((clipBottom - contentYOffset) / metrics.cellHeight).toInt()
        return clippedRows.coerceIn(0, visibleRows)
    }

    private companion object {
        private const val TEXT_LCD_CONTRAST = 140
    }
}
