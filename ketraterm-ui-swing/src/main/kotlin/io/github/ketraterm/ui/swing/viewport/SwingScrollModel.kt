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
package io.github.ketraterm.ui.swing.viewport

import kotlin.math.floor

/**
 * EDT-confined smooth scrollback viewport model.
 *
 * The model owns the precise in-flight animation position. Input accumulation
 * and integer destination policy belong to [SmoothRowScroller]. The
 * line-addressed render reader receives a whole-row anchor plus one overscan
 * row; fractional motion is a renderer translation only. Renderer decorations
 * do not contribute scrollable height.
 */
internal class SwingScrollModel {
    private var cellHeight: Int = 1
    private var historySize: Int = 0
    private var discardedCount: Long = 0L
    private var preciseOffset: Double = 0.0
    private var committedOffset: Int = 0
    private var renderOffset: Int = 0
    private var fraction: Double = 0.0

    /**
     * Current committed scrollback offset in whole terminal rows.
     */
    val offset: Int
        get() = committedOffset

    /**
     * Precise visual scrollback offset in terminal rows.
     *
     * `0.0` is the live viewport. Larger values move farther back into
     * scrollback history. Fractional values exist only during animation.
     */
    val preciseScrollbackOffset: Double
        get() = preciseOffset

    /**
     * Scrollback offset that should be requested from the render reader.
     */
    val requestedOffset: Int
        get() = renderOffset

    /**
     * Current visual pixel offset from the live bottom.
     */
    val visualScrollOffsetPixels: Double
        get() = preciseOffset * cellHeight

    /**
     * Maximum visual pixel offset from the live bottom.
     */
    val visualScrollRangePixels: Int
        get() = (historySize.toLong() * cellHeight).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    /** Current fixed terminal row height used by visual controls. */
    val cellHeightPixels: Int
        get() = cellHeight

    /** Whether the current viewport needs one row of smooth-scroll overscan. */
    val needsOverscan: Boolean
        get() = fraction > 0.0 && renderOffset > committedOffset

    /**
     * Clears scrollback state back to the live viewport.
     */
    fun reset() {
        cellHeight = 1
        historySize = 0
        discardedCount = 0L
        preciseOffset = 0.0
        committedOffset = 0
        renderOffset = 0
        fraction = 0.0
    }

    /**
     * Clamps the current offset after history size changes.
     *
     * @return true when the requested render offset changed.
     */
    fun clamp(
        historySize: Int,
        discardedCount: Long,
        scrollOnOutput: Boolean,
    ): Boolean {
        require(historySize >= 0) { "historySize must be >= 0, was $historySize" }
        require(discardedCount >= 0L) { "discardedCount must be >= 0, was $discardedCount" }
        val deltaBottom = (historySize + discardedCount) - (this.historySize + this.discardedCount)
        this.historySize = historySize
        this.discardedCount = discardedCount

        if (deltaBottom > 0) {
            if (scrollOnOutput || preciseOffset == 0.0) {
                preciseOffset = 0.0
            } else {
                preciseOffset += deltaBottom
            }
        }
        return clampPreciseOffset()
    }

    /**
     * Updates pixel metrics used to derive row-based render requests.
     *
     * @return true when the requested render offset changed.
     */
    fun updateVisualMetrics(
        historySize: Int,
        discardedCount: Long,
        cellHeight: Int,
        visualOverflowPixels: Int,
    ): Boolean {
        require(historySize >= 0) { "historySize must be >= 0, was $historySize" }
        require(discardedCount >= 0L) { "discardedCount must be >= 0, was $discardedCount" }
        require(cellHeight > 0) { "cellHeight must be > 0, was $cellHeight" }
        require(visualOverflowPixels == 0) { "visualOverflowPixels must be 0 for fixed-row geometry, was $visualOverflowPixels" }
        this.historySize = historySize
        this.discardedCount = discardedCount
        this.cellHeight = cellHeight
        return clampPreciseOffset()
    }

    /**
     * Moves to [offsetLines], clamped to the available scrollback history.
     *
     * @return true when the precise offset changed.
     */
    fun scrollTo(
        offsetLines: Double,
        historySize: Int,
    ): Boolean {
        require(offsetLines.isFinite()) { "offsetLines must be finite, was $offsetLines" }
        require(historySize >= 0) { "historySize must be >= 0, was $historySize" }

        this.historySize = historySize
        return scrollToPreciseOffset(offsetLines.coerceIn(0.0, historySize.toDouble()))
    }

    /** Returns the fractional vertical content translation for rendering. */
    fun contentYOffset(cellHeight: Int): Double {
        require(cellHeight > 0) {
            "cellHeight must be > 0, was $cellHeight"
        }
        if (!needsOverscan) return 0.0
        return -(1.0 - fraction) * cellHeight
    }

    /** Returns the render-cache row count needed for the current viewport. */
    fun requestedRows(renderRows: Int): Int {
        require(renderRows > 0) { "renderRows must be > 0, was $renderRows" }
        return if (needsOverscan) renderRows + 1 else renderRows
    }

    private fun committed(
        offset: Double,
        historySize: Int,
    ): Int = floor(offset).toInt().coerceIn(0, historySize)

    private fun renderOffset(
        offset: Double,
        historySize: Int,
    ): Int {
        val committed = committed(offset, historySize)
        if (fractionalPart(offset) == 0.0) return committed
        return (committed + 1).coerceIn(0, historySize)
    }

    private fun clampPreciseOffset(): Boolean {
        val oldRenderOffset = renderOffset
        preciseOffset = preciseOffset.coerceIn(0.0, historySize.toDouble())
        recomputeDerivedOffsets()
        return oldRenderOffset != renderOffset
    }

    private fun scrollToPreciseOffset(offset: Double): Boolean {
        val nextOffset = offset.coerceIn(0.0, historySize.toDouble())
        if (nextOffset == preciseOffset) return false
        preciseOffset = nextOffset
        recomputeDerivedOffsets()
        return true
    }

    private fun recomputeDerivedOffsets() {
        preciseOffset = preciseOffset.coerceIn(0.0, historySize.toDouble())
        committedOffset = committed(preciseOffset, historySize)
        renderOffset = renderOffset(preciseOffset, historySize)
        fraction = fractionalPart(preciseOffset)
    }

    private fun fractionalPart(offset: Double): Double = offset - floor(offset)
}
