package com.gagik.terminal.ui.swing.render

import com.gagik.terminal.render.cache.TerminalRenderCache
import com.gagik.terminal.ui.swing.settings.TerminalColorPalette
import com.gagik.terminal.ui.swing.settings.TerminalSwingMetrics
import com.gagik.terminal.ui.swing.settings.TerminalSwingSettings
import java.awt.Font
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints

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
    ) {
        val palette = settings.palette
        textPainter.updateSettings(settings)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, settings.textAntialiasing)
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, settings.fractionalMetrics)
        g.font = textPainter.font(Font.PLAIN)
        val fontRenderContext = g.fontRenderContext

        val clip = g.getClipBounds(clipScratch)
        val firstRow = firstPaintRow(clip, metrics)
        val rows = lastPaintRowExclusive(clip, cache, metrics, height)
        backgroundPainter.clear(g, palette, width, height)

        var row = firstRow
        while (row < rows) {
            backgroundPainter.paintRow(g, cache, palette, metrics, row)
            textPainter.paintRow(g, cache, palette, metrics, row, fontRenderContext)
            row++
        }

        cursorPainter.paint(g, cache, palette, metrics, cursorBlinkVisible, fontRenderContext)
    }

    private fun firstPaintRow(
        clip: Rectangle?,
        metrics: TerminalSwingMetrics,
    ): Int {
        if (clip == null) return 0
        return maxOf(0, clip.y / metrics.cellHeight)
    }

    private fun lastPaintRowExclusive(
        clip: Rectangle?,
        cache: TerminalRenderCache,
        metrics: TerminalSwingMetrics,
        componentHeight: Int,
    ): Int {
        val visibleRows = minOf(cache.rows, componentHeight / metrics.cellHeight + 1)
        if (clip == null || clip.height <= 0) return visibleRows

        val clipBottom = clip.y + clip.height
        if (clipBottom <= 0) return 0
        val clippedRows = (clipBottom + metrics.cellHeight - 1) / metrics.cellHeight
        return clippedRows.coerceIn(0, visibleRows)
    }
}
