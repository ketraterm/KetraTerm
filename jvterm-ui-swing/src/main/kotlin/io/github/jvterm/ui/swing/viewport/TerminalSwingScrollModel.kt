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

import kotlin.math.floor

/**
 * EDT-confined scrollback viewport model for Swing wheel input.
 *
 * Wheel devices can report fractional line deltas. The terminal grid remains
 * line-addressed, but the Swing renderer can request one overscan row and
 * translate the snapshot by sub-row pixels for smooth scrollback composition.
 */
internal class TerminalSwingScrollModel {
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
     * Precise logical scrollback offset in terminal rows.
     *
     * `0.0` is the live viewport. Larger values move farther back into
     * scrollback history.
     */
    val preciseScrollbackOffset: Double
        get() = preciseOffset

    /**
     * Scrollback offset that should be requested from the render reader.
     */
    val requestedOffset: Int
        get() = renderOffset

    /**
     * Whether the current viewport needs one row of overscan.
     */
    val needsOverscan: Boolean
        get() = fraction > 0.0 && renderOffset > committedOffset

    /**
     * Clears scrollback state back to the live viewport.
     */
    fun reset() {
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
    fun clamp(historySize: Int): Boolean {
        val nextPrecise = preciseOffset.coerceIn(0.0, historySize.toDouble())
        val nextCommitted = committed(nextPrecise, historySize)
        val nextRenderOffset = renderOffset(nextPrecise, historySize)
        val changed = nextRenderOffset != renderOffset
        preciseOffset = nextPrecise
        committedOffset = nextCommitted
        renderOffset = nextRenderOffset
        fraction = fractionalPart(nextPrecise)
        return changed
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
        require(!offsetLines.isNaN()) { "offsetLines must not be NaN" }

        val nextPrecise = offsetLines.coerceIn(0.0, historySize.toDouble())
        if (nextPrecise == preciseOffset) return false

        val nextCommitted = committed(nextPrecise, historySize)
        val nextRenderOffset = renderOffset(nextPrecise, historySize)
        preciseOffset = nextPrecise
        committedOffset = nextCommitted
        renderOffset = nextRenderOffset
        fraction = fractionalPart(nextPrecise)
        return true
    }

    /**
     * Applies a fractional line delta.
     *
     * @return true when the precise offset changed.
     */
    fun scrollBy(
        deltaLines: Double,
        historySize: Int,
    ): Boolean {
        require(!deltaLines.isNaN()) { "deltaLines must not be NaN" }
        if (deltaLines == 0.0) return false

        return scrollTo(preciseOffset + deltaLines, historySize)
    }

    /**
     * Returns the vertical content translation for the current fractional
     * scroll position.
     */
    fun contentYOffset(cellHeight: Int): Double {
        if (!needsOverscan) return 0.0
        return -(1.0 - fraction) * cellHeight
    }

    /**
     * Returns the render-cache row count needed for the current viewport.
     */
    fun requestedRows(visibleRows: Int): Int {
        require(visibleRows > 0) { "visibleRows must be > 0, was $visibleRows" }
        return if (needsOverscan) visibleRows + 1 else visibleRows
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
        val offsetFraction = fractionalPart(offset)
        if (offsetFraction == 0.0) return committed
        return (committed + 1).coerceIn(0, historySize)
    }

    private fun fractionalPart(offset: Double): Double = offset - floor(offset)
}
