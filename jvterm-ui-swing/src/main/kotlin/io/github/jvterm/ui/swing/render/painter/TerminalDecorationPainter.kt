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

import io.github.jvterm.render.api.*
import io.github.jvterm.ui.swing.render.cache.AwtColorCache
import io.github.jvterm.ui.swing.settings.TerminalSwingMetrics
import java.awt.Graphics2D

/**
 * Paints text decorations exported through render attribute words.
 */
internal class TerminalDecorationPainter(
    private val colorCache: AwtColorCache,
) {
    /**
     * Paints underline, strikethrough, and overline for a contiguous cell span.
     */
    fun paint(
        g: Graphics2D,
        palette: TerminalColorPalette,
        attr: Long,
        extraAttr: Long,
        foreground: Int,
        startColumn: Int,
        endColumn: Int,
        row: Int,
        metrics: TerminalSwingMetrics,
    ) {
        val underline = TerminalRenderAttrs.underlineStyle(attr)
        val strikethrough = TerminalRenderAttrs.isStrikethrough(attr)
        val overline = TerminalRenderExtraAttrs.isOverline(extraAttr)
        if (underline == TerminalRenderUnderline.NONE && !strikethrough && !overline) return

        val x = startColumn * metrics.cellWidth
        val width = (endColumn - startColumn) * metrics.cellWidth
        val rowY = row * metrics.cellHeight

        if (underline != TerminalRenderUnderline.NONE) {
            g.color = colorCache.color(underlineColor(palette, extraAttr, foreground))
            val y = rowY + metrics.underlineY
            g.fillRect(x, y, width, DECORATION_THICKNESS)
            if (underline == TerminalRenderUnderline.DOUBLE) {
                val secondY = minOf(rowY + metrics.cellHeight - 1, y + DOUBLE_UNDERLINE_OFFSET)
                g.fillRect(x, secondY, width, DECORATION_THICKNESS)
            }
        }

        if (strikethrough) {
            g.color = colorCache.color(foreground)
            val y = rowY + metrics.strikethroughY
            g.fillRect(x, y, width, DECORATION_THICKNESS)
        }

        if (overline) {
            g.color = colorCache.color(foreground)
            val y = rowY + metrics.overlineY
            g.fillRect(x, y, width, DECORATION_THICKNESS)
        }
    }

    /**
     * Paints the UI hyperlink affordance for a contiguous linked span.
     *
     * Non-hovered links use a dotted underline. Hovered links use a solid
     * underline so explicit activation intent is visible without changing core
     * terminal attributes.
     */
    fun paintHyperlink(
        g: Graphics2D,
        color: Int,
        startColumn: Int,
        endColumn: Int,
        row: Int,
        metrics: TerminalSwingMetrics,
        hovered: Boolean,
    ) {
        if (startColumn >= endColumn) return

        val x = startColumn * metrics.cellWidth
        val width = (endColumn - startColumn) * metrics.cellWidth
        val rowY = row * metrics.cellHeight
        val y = rowY + metrics.underlineY
        g.color = colorCache.color(color)

        if (hovered) {
            g.fillRect(x, y, width, DECORATION_THICKNESS)
            return
        }

        val endX = x + width
        var dotX = x
        while (dotX < endX) {
            g.fillRect(dotX, y, DOT_WIDTH, DECORATION_THICKNESS)
            dotX += DOT_STRIDE
        }
    }

    private fun underlineColor(
        palette: TerminalColorPalette,
        extraAttr: Long,
        defaultColor: Int,
    ): Int {
        val value = TerminalRenderExtraAttrs.underlineColorValue(extraAttr)
        return when (TerminalRenderExtraAttrs.underlineColorKind(extraAttr)) {
            TerminalRenderColorKind.DEFAULT -> defaultColor
            TerminalRenderColorKind.INDEXED -> palette.indexedColor(value)
            TerminalRenderColorKind.RGB -> 0xFF000000.toInt() or value
            else -> defaultColor
        }
    }

    private companion object {
        private const val DECORATION_THICKNESS = 1
        private const val DOUBLE_UNDERLINE_OFFSET = 2
        private const val DOT_WIDTH = 1
        private const val DOT_STRIDE = 3
    }
}
