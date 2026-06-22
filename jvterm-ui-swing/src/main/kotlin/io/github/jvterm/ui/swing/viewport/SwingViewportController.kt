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

import io.github.jvterm.ui.swing.api.TerminalViewportListener
import io.github.jvterm.ui.swing.api.TerminalViewportState
import io.github.jvterm.ui.swing.settings.SwingMetrics
import io.github.jvterm.ui.swing.settings.SwingSettings
import java.awt.Dimension
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * EDT-owned viewport and scrollback controller with off-EDT snapshots.
 *
 * The controller owns smooth-scroll state, render-cache row requests, visible
 * grid dimensions, and host-facing viewport snapshots. It does not read or
 * mutate render-cache contents.
 */
internal class SwingViewportController(
    private val listener: TerminalViewportListener,
) {
    private val scrollModel = SwingScrollModel()
    private val visibleGridSizeSnapshot = AtomicLong(packVisibleGridSize(1, 1))
    private val viewportHistorySizeSnapshot = AtomicInteger(0)
    private val viewportScrollbackOffsetSnapshot = AtomicLong(doubleToRawLongBits(0.0))
    private val viewportRenderOffsetSnapshot = AtomicInteger(0)
    private val viewportVisibleRowsSnapshot = AtomicInteger(1)
    private val viewportRequestedRowsSnapshot = AtomicInteger(1)
    private val viewportVisualOffsetPixelsSnapshot = AtomicLong(doubleToRawLongBits(0.0))
    private val viewportVisualRangePixelsSnapshot = AtomicInteger(0)
    private val viewportHeightPixelsSnapshot = AtomicInteger(0)
    private val viewportContentHeightPixelsSnapshot = AtomicInteger(0)
    private val viewportCellHeightPixelsSnapshot = AtomicInteger(1)

    val requestedOffset: Int
        get() = scrollModel.requestedOffset

    val preciseOffset: Double
        get() = scrollModel.preciseScrollbackOffset

    fun reset() {
        scrollModel.reset()
    }

    fun visibleGridSizeSnapshot(): Dimension {
        val packed = visibleGridSizeSnapshot.get()
        return Dimension(unpackVisibleColumns(packed), unpackVisibleRows(packed))
    }

    fun visibleGridSizeOnEdt(
        settings: SwingSettings,
        metrics: SwingMetrics,
        componentWidth: Int,
        componentHeight: Int,
    ): Dimension {
        val packed = updateVisibleGridSize(settings, metrics, componentWidth, componentHeight)
        return Dimension(unpackVisibleColumns(packed), unpackVisibleRows(packed))
    }

    fun updateVisibleGridSize(
        settings: SwingSettings,
        metrics: SwingMetrics,
        componentWidth: Int,
        componentHeight: Int,
    ): Long {
        val columns = visibleGridColumns(settings, metrics, componentWidth)
        val rows = visibleGridRows(settings, metrics, componentHeight)
        val packed = packVisibleGridSize(columns, rows)
        visibleGridSizeSnapshot.set(packed)
        return packed
    }

    fun visibleGridRows(
        settings: SwingSettings,
        metrics: SwingMetrics,
        componentHeight: Int,
    ): Int {
        val padding = settings.padding
        return maxOf(1, (componentHeight - padding.top - padding.bottom) / metrics.cellHeight)
    }

    fun visibleRenderRows(
        settings: SwingSettings,
        metrics: SwingMetrics,
        componentHeight: Int,
    ): Int {
        val padding = settings.padding
        val availableHeight = componentHeight - padding.top - padding.bottom
        if (availableHeight <= 0) return 1
        return ceilDiv(availableHeight, metrics.cellHeight)
    }

    fun viewportPixelHeight(
        settings: SwingSettings,
        componentHeight: Int,
    ): Int {
        val padding = settings.padding
        return maxOf(0, componentHeight - padding.top - padding.bottom)
    }

    fun requestedRows(renderRows: Int): Int = scrollModel.requestedRows(renderRows)

    fun scrollBy(
        delta: Double,
        historySize: Int,
    ): Boolean = scrollModel.scrollBy(delta, historySize)

    fun scrollTo(
        offsetLines: Double,
        historySize: Int,
    ): Boolean = scrollModel.scrollTo(offsetLines, historySize)

    fun clamp(historySize: Int): Boolean = scrollModel.clamp(historySize)

    fun updateVisualMetrics(
        historySize: Int,
        cellHeight: Int,
        visualOverflowPixels: Int,
    ): Boolean = scrollModel.updateVisualMetrics(historySize, cellHeight, visualOverflowPixels)

    fun resizeRequestedOffset(): Int = scrollModel.requestedOffset

    fun resizeFraction(): Double = scrollModel.preciseScrollbackOffset - scrollModel.offset

    fun anchorAfterResize(
        newOffset: Int,
        newHistorySize: Int,
        oldFraction: Double,
    ) {
        scrollModel.scrollTo((newOffset + oldFraction).coerceIn(0.0, newHistorySize.toDouble()), newHistorySize)
    }

    fun contentOriginY(
        cacheScrollbackOffset: Int,
        cellHeight: Int,
    ): Double {
        require(cacheScrollbackOffset >= 0) {
            "cacheScrollbackOffset must be >= 0, was $cacheScrollbackOffset"
        }
        require(cellHeight > 0) { "cellHeight must be > 0, was $cellHeight" }
        if (cacheScrollbackOffset != scrollModel.requestedOffset) return 0.0
        return scrollModel.contentYOffset(cellHeight)
    }

    fun viewportStateSnapshot(): TerminalViewportState =
        TerminalViewportState(
            historySize = viewportHistorySizeSnapshot.get(),
            scrollbackOffset = longBitsToDouble(viewportScrollbackOffsetSnapshot.get()),
            renderOffset = viewportRenderOffsetSnapshot.get(),
            visibleRows = viewportVisibleRowsSnapshot.get(),
            requestedRows = viewportRequestedRowsSnapshot.get(),
            visualScrollOffsetPixels = longBitsToDouble(viewportVisualOffsetPixelsSnapshot.get()),
            visualScrollRangePixels = viewportVisualRangePixelsSnapshot.get(),
            viewportHeightPixels = viewportHeightPixelsSnapshot.get(),
            contentHeightPixels = viewportContentHeightPixelsSnapshot.get(),
            cellHeightPixels = viewportCellHeightPixelsSnapshot.get(),
        )

    fun publishViewportState(
        historySize: Int,
        visibleRows: Int,
        renderRows: Int,
        viewportHeightPixels: Int,
        contentHeightPixels: Int,
        notifyListener: Boolean = true,
    ) {
        val requestedRows = scrollModel.requestedRows(renderRows)
        val scrollbackOffset = scrollModel.preciseScrollbackOffset
        val renderOffset = scrollModel.requestedOffset
        val visualScrollOffsetPixels = scrollModel.visualScrollOffsetPixels
        val visualScrollRangePixels = scrollModel.visualScrollRangePixels

        viewportHistorySizeSnapshot.set(historySize)
        viewportScrollbackOffsetSnapshot.set(doubleToRawLongBits(scrollbackOffset))
        viewportRenderOffsetSnapshot.set(renderOffset)
        viewportVisibleRowsSnapshot.set(visibleRows)
        viewportRequestedRowsSnapshot.set(requestedRows)
        viewportVisualOffsetPixelsSnapshot.set(doubleToRawLongBits(visualScrollOffsetPixels))
        viewportVisualRangePixelsSnapshot.set(visualScrollRangePixels)
        viewportHeightPixelsSnapshot.set(viewportHeightPixels)
        viewportContentHeightPixelsSnapshot.set(contentHeightPixels)
        viewportCellHeightPixelsSnapshot.set(scrollModel.cellHeightPixels)
        if (!notifyListener) return

        listener.viewportStateChanged(
            TerminalViewportState(
                historySize = historySize,
                scrollbackOffset = scrollbackOffset,
                renderOffset = renderOffset,
                visibleRows = visibleRows,
                requestedRows = requestedRows,
                visualScrollOffsetPixels = visualScrollOffsetPixels,
                visualScrollRangePixels = visualScrollRangePixels,
                viewportHeightPixels = viewportHeightPixels,
                contentHeightPixels = contentHeightPixels,
                cellHeightPixels = scrollModel.cellHeightPixels,
            ),
        )
    }

    private companion object {
        private fun visibleGridColumns(
            settings: SwingSettings,
            metrics: SwingMetrics,
            componentWidth: Int,
        ): Int {
            val padding = settings.padding
            return maxOf(1, (componentWidth - padding.left) / metrics.cellWidth)
        }

        private fun packVisibleGridSize(
            columns: Int,
            rows: Int,
        ): Long = (columns.toLong() shl 32) or (rows.toLong() and 0xffff_ffffL)

        fun unpackVisibleColumns(packed: Long): Int = (packed ushr 32).toInt()

        fun unpackVisibleRows(packed: Long): Int = packed.toInt()

        private fun doubleToRawLongBits(value: Double): Long = java.lang.Double.doubleToRawLongBits(value)

        private fun longBitsToDouble(value: Long): Double = java.lang.Double.longBitsToDouble(value)

        private fun ceilDiv(
            value: Int,
            divisor: Int,
        ): Int {
            require(divisor > 0) { "divisor must be > 0, was $divisor" }
            return (value - 1) / divisor + 1
        }
    }
}
