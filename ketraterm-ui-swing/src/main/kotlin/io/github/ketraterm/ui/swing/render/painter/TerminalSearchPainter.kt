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

import io.github.ketraterm.ui.swing.render.TerminalBidiLayout
import io.github.ketraterm.ui.swing.render.TerminalDeviceGeometry
import io.github.ketraterm.ui.swing.render.cache.AwtColorCache
import io.github.ketraterm.ui.swing.render.forEachVisualCellSpan
import io.github.ketraterm.ui.swing.search.TerminalSearchViewportHighlights
import io.github.ketraterm.ui.swing.settings.SwingMetrics
import io.github.ketraterm.ui.swing.settings.SwingSearchMatchStyle
import java.awt.Graphics2D

/**
 * Paints terminal search result overlays from precomputed viewport segments.
 */
internal class TerminalSearchPainter(
    private val colorCache: AwtColorCache,
) {
    private val pixels = TerminalDeviceGeometry()

    fun paint(
        g: Graphics2D,
        metrics: SwingMetrics,
        row: Int,
        highlights: TerminalSearchViewportHighlights?,
        matchStyle: SwingSearchMatchStyle,
        activeMatchStyle: SwingSearchMatchStyle,
        bidi: TerminalBidiLayout.Row? = null,
    ) {
        if (highlights == null || highlights.segmentCount == 0) return
        val count = highlights.segmentCountForRow(row)
        if (count == 0) return

        pixels.update(g)
        val originalClip = pixels.saveClip(g)
        var segment = highlights.firstSegmentForRow(row)
        val end = segment + count
        while (segment < end) {
            val startColumn = highlights.startColumn(segment)
            val endColumn = highlights.endColumn(segment)
            val style = if (highlights.isActive(segment)) activeMatchStyle else matchStyle
            forEachVisualCellSpan(bidi, startColumn, endColumn) { visualStart, visualEnd ->
                val x = visualStart * metrics.cellWidth
                val y = row * metrics.cellHeight
                val width = (visualEnd - visualStart) * metrics.cellWidth
                // Translucent fills can rasterize outside Swing's rounded dirty-region
                // edges at fractional scales. Clip to the cells that own the overlay.
                pixels.clipRect(g, x, y, width, metrics.cellHeight)
                try {
                    if (style.background ushr 24 != 0) {
                        g.color = colorCache.color(style.background)
                        pixels.fillRect(g, x, y, width, metrics.cellHeight)
                    }
                    val border = style.border
                    if (border != null && border ushr 24 != 0 && width > 0) {
                        g.color = colorCache.color(border)
                        paintBorder(g, x, y, width, metrics.cellHeight)
                    }
                } finally {
                    pixels.restoreClip(g, originalClip)
                }
            }
            segment++
        }
    }

    /** Disjoint fills avoid double alpha blending at corners and work for one-pixel spans. */
    private fun paintBorder(
        g: Graphics2D,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ) {
        pixels.border(g, x, y, width, height)
    }
}
