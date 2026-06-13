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
package io.github.jvterm.ui.swing.viewport

import io.github.jvterm.render.api.TerminalRenderCursorShape
import io.github.jvterm.render.cache.TerminalRenderCache
import io.github.jvterm.ui.swing.render.visualCellRangeSpan
import io.github.jvterm.ui.swing.render.visualCellRangeStart
import io.github.jvterm.ui.swing.settings.TerminalSwingMetrics
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Computes bounded Swing repaint regions from render-cache change metadata.
 *
 * The planner is component-owned and EDT-confined. It keeps the previously
 * painted cursor so cursor-only updates can repaint both the old and new cell
 * without repainting the full terminal surface.
 */
internal class TerminalSwingRepaintPlanner {
    private var lastCursorKnown: Boolean = false
    private var lastCursorColumn: Int = 0
    private var lastCursorRow: Int = 0
    private var lastCursorVisible: Boolean = false
    private var lastCursorBlinking: Boolean = false
    private var lastCursorShape: TerminalRenderCursorShape = TerminalRenderCursorShape.BLOCK
    private var lastCursorGeneration: Long = UNINITIALIZED_GENERATION
    private var lastColumns: Int = 0
    private var lastRows: Int = 0
    private var lastStructureGeneration: Long = UNINITIALIZED_GENERATION
    private var lastScrollbackOffset: Int = UNINITIALIZED_OFFSET
    private var lastActiveBufferOrdinal: Int = UNINITIALIZED_ACTIVE_BUFFER
    private var lastLineGenerations: LongArray = LongArray(0)
    private var lastLineWrapped: BooleanArray = BooleanArray(0)

    /**
     * Clears remembered cursor state when the component unbinds or resets.
     */
    fun reset() {
        lastCursorKnown = false
        lastCursorColumn = 0
        lastCursorRow = 0
        lastCursorVisible = false
        lastCursorBlinking = false
        lastCursorShape = TerminalRenderCursorShape.BLOCK
        lastCursorGeneration = UNINITIALIZED_GENERATION
        lastColumns = 0
        lastRows = 0
        lastStructureGeneration = UNINITIALIZED_GENERATION
        lastScrollbackOffset = UNINITIALIZED_OFFSET
        lastActiveBufferOrdinal = UNINITIALIZED_ACTIVE_BUFFER
        lastLineGenerations = LongArray(0)
        lastLineWrapped = BooleanArray(0)
    }

    /**
     * Requests the smallest repaint regions needed for the latest published
     * [cache] update. [contentYOffset] must match the vertical translation used
     * by painting the same cache.
     */
    fun requestFrameRepaint(
        cache: TerminalRenderCache,
        metrics: TerminalSwingMetrics,
        componentWidth: Int,
        componentHeight: Int,
        contentYOffset: Double,
        padding: java.awt.Insets,
        repaintSink: TerminalRepaintSink,
    ) {
        if (requiresFullRepaint(cache)) {
            snapshotCacheState(cache)
            snapshotCursor(cache)
            repaintSink.requestFullRepaint()
            return
        }

        val visibleRows = visibleRows(cache, metrics, componentHeight, padding.top, padding.bottom)
        repaintChangedRows(
            cache = cache,
            metrics = metrics,
            componentWidth = componentWidth,
            componentHeight = componentHeight,
            visibleRows = visibleRows,
            contentYOffset = contentYOffset,
            padding = padding,
            repaintSink = repaintSink,
        )

        if (cursorChanged(cache)) {
            repaintCursorIfNeeded(
                known = lastCursorKnown,
                column = lastCursorColumn,
                row = lastCursorRow,
                visible = lastCursorVisible,
                cache = cache,
                metrics = metrics,
                componentWidth = componentWidth,
                componentHeight = componentHeight,
                visibleRows = visibleRows,
                contentYOffset = contentYOffset,
                padding = padding,
                skipChangedRows = true,
                repaintSink = repaintSink,
            )
            repaintCursorIfNeeded(
                known = true,
                column = cache.cursorColumn,
                row = cache.cursorRow,
                visible = cache.cursorVisible,
                cache = cache,
                metrics = metrics,
                componentWidth = componentWidth,
                componentHeight = componentHeight,
                visibleRows = visibleRows,
                contentYOffset = contentYOffset,
                padding = padding,
                skipChangedRows = true,
                repaintSink = repaintSink,
            )
        }

        snapshotCacheState(cache)
        snapshotCursor(cache)
    }

    /**
     * Requests a repaint for the current blinking cursor cell only.
     */
    fun requestCursorBlinkRepaint(
        cache: TerminalRenderCache,
        metrics: TerminalSwingMetrics,
        componentWidth: Int,
        componentHeight: Int,
        contentYOffset: Double,
        padding: java.awt.Insets,
        repaintSink: TerminalRepaintSink,
    ) {
        if (!cache.cursorVisible || !cache.cursorBlinking) return

        repaintCursorIfNeeded(
            known = true,
            column = cache.cursorColumn,
            row = cache.cursorRow,
            visible = true,
            cache = cache,
            metrics = metrics,
            componentWidth = componentWidth,
            componentHeight = componentHeight,
            visibleRows = visibleRows(cache, metrics, componentHeight, padding.top, padding.bottom),
            contentYOffset = contentYOffset,
            padding = padding,
            skipChangedRows = false,
            repaintSink = repaintSink,
        )
    }

    /**
     * Requests a repaint for the current cursor cell regardless of blink mode.
     */
    fun requestCursorRepaint(
        cache: TerminalRenderCache,
        metrics: TerminalSwingMetrics,
        componentWidth: Int,
        componentHeight: Int,
        contentYOffset: Double,
        padding: java.awt.Insets,
        repaintSink: TerminalRepaintSink,
    ) {
        if (!cache.cursorVisible) return

        repaintCursorIfNeeded(
            known = true,
            column = cache.cursorColumn,
            row = cache.cursorRow,
            visible = true,
            cache = cache,
            metrics = metrics,
            componentWidth = componentWidth,
            componentHeight = componentHeight,
            visibleRows = visibleRows(cache, metrics, componentHeight, padding.top, padding.bottom),
            contentYOffset = contentYOffset,
            padding = padding,
            skipChangedRows = false,
            repaintSink = repaintSink,
        )
    }

    /**
     * Requests repaints for visible rows containing SGR blinking text.
     */
    fun requestBlinkingTextRepaint(
        cache: TerminalRenderCache,
        metrics: TerminalSwingMetrics,
        componentWidth: Int,
        componentHeight: Int,
        contentYOffset: Double,
        padding: java.awt.Insets,
        repaintSink: TerminalRepaintSink,
    ) {
        if (!cache.hasBlinkingText) return

        val visibleRows = visibleRows(cache, metrics, componentHeight, padding.top, padding.bottom)
        var row = 0
        while (row < visibleRows) {
            if (!cache.lineHasBlinkingText[row]) {
                row++
                continue
            }

            val startRow = row
            row++
            while (row < visibleRows && cache.lineHasBlinkingText[row]) {
                row++
            }

            repaintRowRun(
                startRow = startRow,
                endRow = row,
                metrics = metrics,
                componentWidth = componentWidth,
                componentHeight = componentHeight,
                contentYOffset = contentYOffset,
                padding = padding,
                repaintSink = repaintSink,
            )
        }
    }

    private fun repaintChangedRows(
        cache: TerminalRenderCache,
        metrics: TerminalSwingMetrics,
        componentWidth: Int,
        componentHeight: Int,
        visibleRows: Int,
        contentYOffset: Double,
        padding: java.awt.Insets,
        repaintSink: TerminalRepaintSink,
    ) {
        var row = 0
        while (row < visibleRows) {
            if (!rowChanged(cache, row)) {
                row++
                continue
            }

            val startRow = row
            row++
            while (row < visibleRows && rowChanged(cache, row)) {
                row++
            }

            repaintRowRun(
                startRow = startRow,
                endRow = row,
                metrics = metrics,
                componentWidth = componentWidth,
                componentHeight = componentHeight,
                contentYOffset = contentYOffset,
                padding = padding,
                repaintSink = repaintSink,
            )
        }
    }

    private fun repaintCursorIfNeeded(
        known: Boolean,
        column: Int,
        row: Int,
        visible: Boolean,
        cache: TerminalRenderCache,
        metrics: TerminalSwingMetrics,
        componentWidth: Int,
        componentHeight: Int,
        visibleRows: Int,
        contentYOffset: Double,
        padding: java.awt.Insets,
        skipChangedRows: Boolean,
        repaintSink: TerminalRepaintSink,
    ): Boolean {
        if (!known || !visible) return false
        if (column !in 0 until cache.columns || row !in 0 until visibleRows) return false
        if (skipChangedRows && rowChanged(cache, row)) return false

        val flags = cache.flags[cache.rowOffset(row) + column]
        val startColumn = visualCellRangeStart(flags, column)
        val columnSpan = visualCellRangeSpan(flags, column, cache.columns)
        val x = startColumn * metrics.cellWidth + padding.left
        if (x >= componentWidth) return false
        val regionWidth = minOf(columnSpan * metrics.cellWidth, componentWidth - x)
        if (regionWidth <= 0) return false

        val y = rowTop(row, metrics.cellHeight, contentYOffset) + padding.top
        val bottom = rowBottom(row + 1, metrics.cellHeight, contentYOffset) + padding.top
        if (bottom <= 0 || y >= componentHeight) return false
        val clippedY = maxOf(0, y)
        val clippedBottom = minOf(componentHeight, bottom)
        val regionHeight = clippedBottom - clippedY
        if (regionHeight <= 0) return false

        repaintSink.requestRegionRepaint(
            x,
            clippedY,
            regionWidth,
            regionHeight,
        )
        return true
    }

    private fun repaintRowRun(
        startRow: Int,
        endRow: Int,
        metrics: TerminalSwingMetrics,
        componentWidth: Int,
        componentHeight: Int,
        contentYOffset: Double,
        padding: java.awt.Insets,
        repaintSink: TerminalRepaintSink,
    ) {
        if (componentWidth <= 0 || componentHeight <= 0) return

        val y = rowTop(startRow, metrics.cellHeight, contentYOffset) + padding.top
        val bottom = rowBottom(endRow, metrics.cellHeight, contentYOffset) + padding.top
        if (bottom <= 0 || y >= componentHeight) return

        val clippedY = maxOf(0, y)
        val clippedBottom = minOf(componentHeight, bottom)
        val regionHeight = clippedBottom - clippedY
        if (regionHeight <= 0) return

        repaintSink.requestRegionRepaint(0, clippedY, componentWidth, regionHeight)
    }

    private fun rowTop(
        row: Int,
        cellHeight: Int,
        contentYOffset: Double,
    ): Int =
        if (contentYOffset == 0.0) {
            row * cellHeight
        } else {
            floor(row.toDouble() * cellHeight.toDouble() + contentYOffset).toInt()
        }

    private fun rowBottom(
        endRow: Int,
        cellHeight: Int,
        contentYOffset: Double,
    ): Int =
        if (contentYOffset == 0.0) {
            endRow * cellHeight
        } else {
            ceil(endRow.toDouble() * cellHeight.toDouble() + contentYOffset).toInt()
        }

    private fun visibleRows(
        cache: TerminalRenderCache,
        metrics: TerminalSwingMetrics,
        componentHeight: Int,
        paddingTop: Int,
        paddingBottom: Int,
    ): Int = minOf(cache.rows, maxOf(1, (componentHeight - paddingTop - paddingBottom) / metrics.cellHeight) + 1)

    private fun rowChanged(
        cache: TerminalRenderCache,
        row: Int,
    ): Boolean =
        lastLineGenerations[row] != cache.lineGenerations[row] ||
            lastLineWrapped[row] != cache.lineWrapped[row]

    private fun requiresFullRepaint(cache: TerminalRenderCache): Boolean =
        cache.resizedOnLastUpdate ||
            lastColumns != cache.columns ||
            lastRows != cache.rows ||
            lastLineGenerations.size != cache.rows ||
            lastLineWrapped.size != cache.rows ||
            lastStructureGeneration != cache.structureGeneration ||
            lastScrollbackOffset != cache.scrollbackOffset ||
            lastActiveBufferOrdinal != cache.activeBuffer.ordinal

    private fun cursorChanged(cache: TerminalRenderCache): Boolean =
        !lastCursorKnown ||
            lastCursorColumn != cache.cursorColumn ||
            lastCursorRow != cache.cursorRow ||
            lastCursorVisible != cache.cursorVisible ||
            lastCursorBlinking != cache.cursorBlinking ||
            lastCursorShape != cache.cursorShape ||
            lastCursorGeneration != cache.cursorGeneration

    private fun snapshotCursor(cache: TerminalRenderCache) {
        lastCursorKnown = true
        lastCursorColumn = cache.cursorColumn
        lastCursorRow = cache.cursorRow
        lastCursorVisible = cache.cursorVisible
        lastCursorBlinking = cache.cursorBlinking
        lastCursorShape = cache.cursorShape
        lastCursorGeneration = cache.cursorGeneration
    }

    private fun snapshotCacheState(cache: TerminalRenderCache) {
        if (lastLineGenerations.size != cache.rows) {
            lastLineGenerations = LongArray(cache.rows)
            lastLineWrapped = BooleanArray(cache.rows)
        }

        var row = 0
        while (row < cache.rows) {
            lastLineGenerations[row] = cache.lineGenerations[row]
            lastLineWrapped[row] = cache.lineWrapped[row]
            row++
        }

        lastColumns = cache.columns
        lastRows = cache.rows
        lastStructureGeneration = cache.structureGeneration
        lastScrollbackOffset = cache.scrollbackOffset
        lastActiveBufferOrdinal = cache.activeBuffer.ordinal
    }

    private companion object {
        private const val UNINITIALIZED_GENERATION = -1L
        private const val UNINITIALIZED_OFFSET = Int.MIN_VALUE
        private const val UNINITIALIZED_ACTIVE_BUFFER = -1
    }
}
