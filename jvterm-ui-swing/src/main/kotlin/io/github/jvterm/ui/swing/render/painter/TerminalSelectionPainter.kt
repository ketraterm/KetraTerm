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

import io.github.jvterm.render.cache.TerminalRenderCache
import io.github.jvterm.ui.swing.api.CellSelection
import io.github.jvterm.ui.swing.render.cache.AwtColorCache
import io.github.jvterm.ui.swing.settings.TerminalSwingMetrics
import java.awt.Graphics2D

/**
 * Paints selected terminal cell ranges as per-row rectangles.
 */
internal class TerminalSelectionPainter(
    private val colorCache: AwtColorCache,
) {
    fun paint(
        g: Graphics2D,
        cache: TerminalRenderCache,
        metrics: TerminalSwingMetrics,
        row: Int,
        selection: CellSelection?,
        selectionBackground: Int,
    ) {
        if (selection == null) return

        val range = selection.packedColumnRange(row, cache.columns, cache)
        if (range == CellSelection.NO_RANGE) return

        val startColumn = CellSelection.rangeStart(range)
        val endColumn = CellSelection.rangeEnd(range)
        g.color = colorCache.color(selectionBackground)
        g.fillRect(
            startColumn * metrics.cellWidth,
            row * metrics.cellHeight,
            (endColumn - startColumn) * metrics.cellWidth,
            metrics.cellHeight,
        )
    }
}
