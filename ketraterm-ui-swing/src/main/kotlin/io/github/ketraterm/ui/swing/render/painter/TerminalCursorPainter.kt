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
package io.github.ketraterm.ui.swing.render.painter

import io.github.ketraterm.render.api.TerminalColorPalette
import io.github.ketraterm.render.api.TerminalRenderCursorShape
import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.ui.swing.render.TerminalBidiLayout
import io.github.ketraterm.ui.swing.render.cache.AwtColorCache
import io.github.ketraterm.ui.swing.render.visualCellRangeSpan
import io.github.ketraterm.ui.swing.render.visualCellRangeStart
import io.github.ketraterm.ui.swing.settings.SwingMetrics
import java.awt.BasicStroke
import java.awt.Graphics2D
import java.awt.font.FontRenderContext
import java.awt.geom.Rectangle2D
import kotlin.math.roundToInt

/**
 * Paints application cursor shapes, replacing inactive blocks with steady outlines.
 * Inactive bars and underlines retain their shape without blinking.
 * Inactive outlines preserve the row's existing text, background, and overlays.
 */
internal class TerminalCursorPainter(
    private val colorCache: AwtColorCache,
    private val textPainter: TerminalTextPainter,
) {
    private val cursorBounds = Rectangle2D.Double()

    // A device hairline stays uniformly thin even at fractional display scales.
    private val outlineStroke = BasicStroke(0f)

    /**
     * Paints the current cursor from [cache].
     */
    fun paint(
        g: Graphics2D,
        cache: TerminalRenderCache,
        palette: TerminalColorPalette,
        metrics: SwingMetrics,
        cursorBlinkVisible: Boolean,
        textBlinkVisible: Boolean,
        fontRenderContext: FontRenderContext,
        terminalFocused: Boolean = true,
        bidi: TerminalBidiLayout.Row? = null,
    ) {
        if (!cache.cursorVisible || (terminalFocused && cache.cursorBlinking && !cursorBlinkVisible)) return
        if (cache.cursorColumn !in 0 until cache.columns || cache.cursorRow !in 0 until cache.rows) return

        val cursorIndex = cache.rowOffset(cache.cursorRow) + cache.cursorColumn
        val cursorFlags = cache.flags[cursorIndex]
        val startColumn = visualCellRangeStart(cursorFlags, cache.cursorColumn)
        val columnSpan = visualCellRangeSpan(cursorFlags, cache.cursorColumn, cache.columns)
        val visualColumn = bidi?.visualColumn(startColumn) ?: startColumn
        val x = visualColumn * metrics.cellWidth
        val y = cache.cursorRow * metrics.cellHeight
        val width = columnSpan * metrics.cellWidth
        g.color = colorCache.color(palette.cursorBackground)

        if (!terminalFocused && cache.cursorShape == TerminalRenderCursorShape.BLOCK) {
            if (width == 1 || metrics.cellHeight == 1) {
                g.fillRect(x, y, width, metrics.cellHeight)
                return
            }
            // Inset the stroke center so its outer edge stays inside the cursor's repaint bounds.
            cursorBounds.setRect(x + 0.5, y + 0.5, width - 1.0, metrics.cellHeight - 1.0)
            val previousStroke = g.stroke
            g.stroke = outlineStroke
            try {
                g.draw(cursorBounds)
            } finally {
                g.stroke = previousStroke
            }
            return
        }

        if (cache.cursorShape != TerminalRenderCursorShape.BLOCK) {
            val transform = g.transform
            val scaleX = transform.scaleX
            val scaleY = transform.scaleY
            if (transform.shearX == 0.0 && transform.shearY == 0.0 && scaleX > 0.0 && scaleY > 0.0) {
                // Round thickness independently of position so split panes cannot gain or lose a pixel.
                val left = (x * scaleX + transform.translateX).roundToInt()
                val right = ((x + width) * scaleX + transform.translateX).roundToInt()
                val top = (y * scaleY + transform.translateY).roundToInt()
                val bottom = ((y + metrics.cellHeight) * scaleY + transform.translateY).roundToInt()
                val bar = cache.cursorShape == TerminalRenderCursorShape.BAR
                val cellWidth = maxOf(1, right - left)
                val cellHeight = maxOf(1, bottom - top)
                val strokeScale = if (bar) scaleX else scaleY
                val strokeLimit = if (bar) cellWidth else cellHeight
                val thickness = (metrics.cursorStrokeWidth * strokeScale).roundToInt().coerceIn(1, strokeLimit)
                val deviceWidth = if (bar) thickness else cellWidth
                val deviceHeight = if (bar) cellHeight else thickness
                val deviceTop = if (bar) top else bottom - deviceHeight
                cursorBounds.setRect(
                    (left - transform.translateX) / scaleX,
                    (deviceTop - transform.translateY) / scaleY,
                    deviceWidth / scaleX,
                    deviceHeight / scaleY,
                )
                g.fill(cursorBounds)
            } else {
                // Rotated or reflected hosts retain Java2D's ordinary transformed rectangle semantics.
                if (cache.cursorShape == TerminalRenderCursorShape.BAR) {
                    g.fillRect(x, y, metrics.cursorStrokeWidth, metrics.cellHeight)
                } else {
                    g.fillRect(x, y + metrics.cellHeight - metrics.cursorStrokeWidth, width, metrics.cursorStrokeWidth)
                }
            }
            return
        }

        g.fillRect(x, y, width, metrics.cellHeight)
        textPainter.paintCellForeground(
            g = g,
            cache = cache,
            metrics = metrics,
            column = startColumn,
            row = cache.cursorRow,
            columnSpan = columnSpan,
            visualColumn = visualColumn,
            foreground = palette.cursorForeground,
            fontRenderContext = fontRenderContext,
            textBlinkVisible = textBlinkVisible,
        )
    }
}
