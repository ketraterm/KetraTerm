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
package io.github.jvterm.core.model

/**
 * Manages VT100 horizontal tab stop positions.
 *
 * Stops are stored as a dense boolean array indexed by zero-based columns.
 * Newly exposed columns inherit the default VT100 rule: a stop every 8 cells
 * starting at column 0.
 */
internal class TabStops(
    private var width: Int,
) {
    /** `true` at index `i` means a tab stop exists at column `i`. */
    private var stops = BooleanArray(width) { col -> col % 8 == 0 }

    /**
     * Resizes the stop table to [newWidth].
     *
     * - Shrink: stops beyond [newWidth] are discarded.
     * - Grow: existing stops are preserved and newly exposed columns receive
     *   fresh default VT100 stops (`i % 8 == 0`).
     * - Same width: returns immediately without allocating.
     */
    fun resize(newWidth: Int) {
        val oldWidth = stops.size
        if (newWidth == oldWidth) return

        if (newWidth <= oldWidth) {
            stops = stops.copyOf(newWidth)
            width = newWidth
            return
        }

        stops = stops.copyOf(newWidth)
        for (i in oldWidth until newWidth) {
            stops[i] = (i % 8 == 0)
        }
        width = newWidth
    }

    /**
     * Sets a tab stop at [col].
     *
     * Called when the parser receives HTS (`ESC H`) at the current cursor column.
     * Out-of-bounds values are ignored.
     */
    fun setStop(col: Int) {
        if (col in 0 until width) stops[col] = true
    }

    /**
     * Clears the tab stop at [col].
     *
     * Called when the parser receives TBC 0 (`CSI 0 g`) at the current cursor column.
     * Out-of-bounds values are ignored.
     */
    fun clearStop(col: Int) {
        if (col in 0 until width) stops[col] = false
    }

    /**
     * Clears all tab stops.
     *
     * After this call, [getNextStop] falls back to the right margin until stops
     * are explicitly re-added or [resetToDefault] is called.
     */
    fun clearAll() {
        stops.fill(false)
    }

    /** Restores the default VT100 stop pattern for the current width. */
    fun resetToDefault() {
        reset(width)
    }

    /**
     * Resets all tab stops to the standard VT100 8-column rhythm at [newWidth].
     *
     * Unlike [resize], this does not preserve custom stops. It is used by
     * destructive terminal operations such as DECCOLM and hard reset.
     */
    fun reset(newWidth: Int) {
        width = newWidth
        if (newWidth != stops.size) {
            stops = BooleanArray(newWidth)
        }
        resetStopsToDefault()
    }

    private fun resetStopsToDefault() {
        stops.fill(false)
        var col = 0
        while (col < stops.size) {
            stops[col] = true
            col += 8
        }
    }

    // Query

    /**
     * Returns the column of the next tab stop strictly to the right of
     * [currentCol].
     *
     * If no stop exists to the right, returns the right margin (`width - 1`);
     * tab never wraps to the next line. If the terminal has zero columns,
     * returns `0`.
     *
     * @param currentCol The cursor's current zero-based column index.
     * @return The column index the cursor should jump to.
     */
    fun getNextStop(currentCol: Int): Int {
        val margin = (width - 1).coerceAtLeast(0)
        if (currentCol >= margin) return margin
        for (i in currentCol + 1 until width) {
            if (stops[i]) return i
        }
        return margin
    }

    /**
     * Returns the nearest tab stop strictly to the left of [currentCol].
     *
     * If no stop exists to the left, returns [leftBoundary]. This lets callers
     * implement CBT against either the full viewport (`0`) or an active
     * DECLRMM left margin. Tab never wraps and never crosses the supplied
     * left boundary.
     *
     * @param currentCol The cursor's current zero-based column index.
     * @param leftBoundary The minimum column the cursor may land on.
     * @return The column index the cursor should jump to.
     */
    fun getPreviousStop(
        currentCol: Int,
        leftBoundary: Int = 0,
    ): Int {
        if (currentCol <= leftBoundary) return leftBoundary
        for (i in currentCol - 1 downTo leftBoundary) {
            if (i in 0 until width && stops[i]) return i
        }
        return leftBoundary
    }
}
