package com.gagik.terminal.ui.swing.api

import com.gagik.terminal.render.api.TerminalRenderCursor
import com.gagik.terminal.render.cache.TerminalRenderCache
import com.gagik.terminal.ui.swing.settings.TerminalSwingMetrics

/**
 * Computes bounded Swing repaint regions from render-cache change metadata.
 *
 * The planner is component-owned and EDT-confined. It keeps the previously
 * painted cursor so cursor-only updates can repaint both the old and new cell
 * without repainting the full terminal surface.
 */
internal class TerminalSwingRepaintPlanner {
    private var lastCursor: TerminalRenderCursor? = null

    /**
     * Clears remembered cursor state when the component unbinds or resets.
     */
    fun reset() {
        lastCursor = null
    }

    /**
     * Requests the smallest repaint regions needed for the latest published
     * [cache] update.
     */
    fun requestFrameRepaint(
        cache: TerminalRenderCache,
        metrics: TerminalSwingMetrics,
        componentWidth: Int,
        componentHeight: Int,
        repaintAll: () -> Unit,
        repaintRegion: (x: Int, y: Int, width: Int, height: Int) -> Unit,
    ) {
        if (cache.resizedOnLastUpdate) {
            lastCursor = cache.cursor
            repaintAll()
            return
        }

        val visibleRows = visibleRows(cache, metrics, componentHeight)
        repaintDirtyRows(
            cache = cache,
            metrics = metrics,
            componentWidth = componentWidth,
            visibleRows = visibleRows,
            repaintRegion = repaintRegion,
        )

        if (cache.cursorChangedOnLastUpdate) {
            repaintCursorIfNeeded(
                cursor = lastCursor,
                cache = cache,
                metrics = metrics,
                visibleRows = visibleRows,
                skipDirtyRows = true,
                repaintRegion = repaintRegion,
            )
            repaintCursorIfNeeded(
                cursor = cache.cursor,
                cache = cache,
                metrics = metrics,
                visibleRows = visibleRows,
                skipDirtyRows = true,
                repaintRegion = repaintRegion,
            )
        }

        lastCursor = cache.cursor
    }

    /**
     * Requests a repaint for the current blinking cursor cell only.
     */
    fun requestCursorBlinkRepaint(
        cache: TerminalRenderCache,
        metrics: TerminalSwingMetrics,
        componentHeight: Int,
        repaintRegion: (x: Int, y: Int, width: Int, height: Int) -> Unit,
    ) {
        val cursor = cache.cursor ?: return
        if (!cursor.visible || !cursor.blinking) return

        repaintCursorIfNeeded(
            cursor = cursor,
            cache = cache,
            metrics = metrics,
            visibleRows = visibleRows(cache, metrics, componentHeight),
            skipDirtyRows = false,
            repaintRegion = repaintRegion,
        )
    }

    private fun repaintDirtyRows(
        cache: TerminalRenderCache,
        metrics: TerminalSwingMetrics,
        componentWidth: Int,
        visibleRows: Int,
        repaintRegion: (x: Int, y: Int, width: Int, height: Int) -> Unit,
    ) {
        var row = 0
        while (row < visibleRows) {
            if (!cache.dirtyRows[row]) {
                row++
                continue
            }

            val startRow = row
            row++
            while (row < visibleRows && cache.dirtyRows[row]) {
                row++
            }

            repaintRegion(
                0,
                startRow * metrics.cellHeight,
                componentWidth,
                (row - startRow) * metrics.cellHeight,
            )
        }
    }

    private fun repaintCursorIfNeeded(
        cursor: TerminalRenderCursor?,
        cache: TerminalRenderCache,
        metrics: TerminalSwingMetrics,
        visibleRows: Int,
        skipDirtyRows: Boolean,
        repaintRegion: (x: Int, y: Int, width: Int, height: Int) -> Unit,
    ): Boolean {
        if (cursor == null || !cursor.visible) return false
        if (cursor.column !in 0 until cache.columns || cursor.row !in 0 until visibleRows) return false
        if (skipDirtyRows && cache.dirtyRows[cursor.row]) return false

        repaintRegion(
            cursor.column * metrics.cellWidth,
            cursor.row * metrics.cellHeight,
            metrics.cellWidth,
            metrics.cellHeight,
        )
        return true
    }

    private fun visibleRows(
        cache: TerminalRenderCache,
        metrics: TerminalSwingMetrics,
        componentHeight: Int,
    ): Int {
        return minOf(cache.rows, componentHeight / metrics.cellHeight + 1)
    }
}
