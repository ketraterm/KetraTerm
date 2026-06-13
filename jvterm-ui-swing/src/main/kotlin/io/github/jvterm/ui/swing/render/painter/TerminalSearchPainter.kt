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

import io.github.jvterm.ui.swing.render.cache.AwtColorCache
import io.github.jvterm.ui.swing.search.TerminalSearchViewportHighlights
import io.github.jvterm.ui.swing.settings.TerminalSwingMetrics
import java.awt.Graphics2D

/**
 * Paints terminal search result overlays from precomputed viewport segments.
 */
internal class TerminalSearchPainter(
    private val colorCache: AwtColorCache,
) {
    fun paint(
        g: Graphics2D,
        metrics: TerminalSwingMetrics,
        row: Int,
        highlights: TerminalSearchViewportHighlights?,
        matchBackground: Int,
        activeMatchBackground: Int,
    ) {
        if (highlights == null || highlights.segmentCount == 0) return
        val count = highlights.segmentCountForRow(row)
        if (count == 0) return

        var segment = highlights.firstSegmentForRow(row)
        val end = segment + count
        while (segment < end) {
            val startColumn = highlights.startColumn(segment)
            val endColumn = highlights.endColumn(segment)
            g.color =
                colorCache.color(
                    if (highlights.isActive(segment)) activeMatchBackground else matchBackground,
                )
            g.fillRect(
                startColumn * metrics.cellWidth,
                row * metrics.cellHeight,
                (endColumn - startColumn) * metrics.cellWidth,
                metrics.cellHeight,
            )
            segment++
        }
    }
}
