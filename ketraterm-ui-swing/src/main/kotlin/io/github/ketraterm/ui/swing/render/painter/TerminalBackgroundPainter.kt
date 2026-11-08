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
import io.github.ketraterm.render.api.TerminalRenderAttrs
import io.github.ketraterm.render.api.TerminalRenderColorKind
import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.ui.swing.render.SwingColors
import io.github.ketraterm.ui.swing.render.TerminalBidiLayout
import io.github.ketraterm.ui.swing.render.TerminalDeviceGeometry
import io.github.ketraterm.ui.swing.render.cache.AwtColorCache
import io.github.ketraterm.ui.swing.settings.SwingMetrics
import java.awt.Graphics2D

/**
 * Paints terminal default clears and row background runs.
 */
internal class TerminalBackgroundPainter(
    private val colorCache: AwtColorCache,
) {
    private val pixels = TerminalDeviceGeometry()

    /**
     * Clears a rectangular component area with the palette default background.
     */
    fun clear(
        g: Graphics2D,
        palette: TerminalColorPalette,
        width: Int,
        height: Int,
    ) {
        fill(g, 0, 0, width, height, palette.defaultBackground)
    }

    /**
     * Paints contiguous background runs for [row].
     */
    fun paintRow(
        g: Graphics2D,
        cache: TerminalRenderCache,
        palette: TerminalColorPalette,
        metrics: SwingMetrics,
        row: Int,
        bidi: TerminalBidiLayout.Row? = null,
        skipDefault: Boolean = false,
    ) {
        val attrWords = cache.attrWords
        val rowOffset = cache.rowOffset(row)

        pixels.update(g)

        var column = 0
        while (column < cache.columns) {
            val background = SwingColors.background(palette, attrWords[rowOffset + (bidi?.logicalColumn(column) ?: column)])
            val defaultBackground = skipDefault && isDefaultBackground(attrWords[rowOffset + (bidi?.logicalColumn(column) ?: column)])
            val start = column

            column++
            while (
                column < cache.columns &&
                SwingColors.background(palette, attrWords[rowOffset + (bidi?.logicalColumn(column) ?: column)]) == background &&
                (
                    !skipDefault ||
                        isDefaultBackground(attrWords[rowOffset + (bidi?.logicalColumn(column) ?: column)]) == defaultBackground
                )
            ) {
                column++
            }

            if (skipDefault && defaultBackground) continue
            g.color = colorCache.color(background)
            pixels.fillRect(
                g,
                start * metrics.cellWidth,
                row * metrics.cellHeight,
                (column - start) * metrics.cellWidth,
                metrics.cellHeight,
            )
        }
    }

    private fun isDefaultBackground(word: Long): Boolean =
        !TerminalRenderAttrs.isInverse(word) && TerminalRenderAttrs.backgroundKind(word) == TerminalRenderColorKind.DEFAULT

    private fun fill(
        g: Graphics2D,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        argb: Int,
    ) {
        g.color = colorCache.color(argb)
        pixels.update(g)
        pixels.fillRect(g, x, y, width, height)
    }
}
