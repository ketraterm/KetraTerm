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
package io.github.ketraterm.ui.swing.api

import io.github.ketraterm.render.api.TerminalRenderCellFlags
import io.github.ketraterm.render.cache.TerminalRenderCache

/** EDT-owned visible hover segments, shared by interaction, repainting and painting. */
internal class TerminalHyperlinkHover {
    var hyperlinkId = 0
        private set
    var activation = false
    var segmentCount = 0
        private set
    private var segments = IntArray(0)
    private var pending = IntArray(0)

    fun reset(
        id: Int = 0,
        active: Boolean = false,
    ) {
        hyperlinkId = id
        activation = active
        segmentCount = 0
    }

    fun setIdentity(
        id: Int,
        active: Boolean,
    ) {
        hyperlinkId = id
        activation = active
    }

    /** Appends a segment in row/column order. Storage grows only for newly encountered geometry. */
    fun add(
        row: Int,
        start: Int,
        end: Int,
    ) {
        val offset = segmentCount * 3
        if (offset + 3 > segments.size) segments = segments.copyOf(maxOf(24, segments.size * 2))
        segments[offset] = row
        segments[offset + 1] = start
        segments[offset + 2] = end
        segmentCount++
    }

    fun row(index: Int): Int = segments[index * 3]

    fun start(index: Int): Int = segments[index * 3 + 1]

    fun end(index: Int): Int = segments[index * 3 + 2]

    fun contains(
        row: Int,
        column: Int,
    ): Boolean {
        var index = firstSegment(row)
        while (index < segmentCount && row(index) == row) {
            if (column < start(index)) return false
            if (column < end(index)) return true
            index++
        }
        return false
    }

    fun isHovered(
        id: Int,
        row: Int,
        column: Int,
    ): Boolean = id != 0 && id == hyperlinkId && contains(row, column)

    fun sameAs(other: TerminalHyperlinkHover): Boolean {
        if (hyperlinkId != other.hyperlinkId || activation != other.activation || segmentCount != other.segmentCount) return false
        for (index in 0 until segmentCount * 3) if (segments[index] != other.segments[index]) return false
        return true
    }

    /**
     * Restricts an OSC 8 destination to the connected visible occurrence containing the pointer.
     * Adjacent rows connect through overlapping columns, or through an actual terminal soft wrap.
     * A blank row or a separate run on the same row does not join independent displayed links.
     */
    fun retainConnected(
        pointerRow: Int,
        pointerColumn: Int,
        cache: TerminalRenderCache,
    ) {
        var seed = firstSegment(pointerRow)
        while (seed < segmentCount && row(seed) == pointerRow && pointerColumn >= end(seed)) seed++
        if (seed >= segmentCount || row(seed) != pointerRow || pointerColumn < start(seed)) {
            segmentCount = 0
            return
        }
        if (pending.size < segmentCount) pending = IntArray(segmentCount)
        var head = 0
        var tail = 1
        pending[0] = seed
        // A negative start marks a queued segment; candidate row ordering stays intact.
        segments[seed * 3 + 1] = -start(seed) - 1
        while (head < tail) {
            val current = pending[head++]
            val currentRow = row(current)
            val currentStart = -start(current) - 1
            val currentEnd = end(current)
            var direction = -1
            while (direction <= 1) {
                val neighborRow = currentRow + direction
                if (neighborRow !in 0 until cache.rows) {
                    direction += 2
                    continue
                }
                var neighbor = firstSegment(neighborRow)
                while (neighbor < segmentCount && row(neighbor) == neighborRow) {
                    val neighborStart = start(neighbor)
                    val neighborEnd = end(neighbor)
                    if (neighborStart >= 0) {
                        val overlaps = currentStart < neighborEnd && neighborStart < currentEnd
                        val wraps =
                            if (direction > 0) {
                                continuesWrappedRow(currentRow, currentEnd, cache) && neighborStart == 0
                            } else {
                                continuesWrappedRow(neighborRow, neighborEnd, cache) && currentStart == 0
                            }
                        if (overlaps || wraps) {
                            segments[neighbor * 3 + 1] = -neighborStart - 1
                            pending[tail++] = neighbor
                        }
                    }
                    neighbor++
                }
                direction += 2
            }
        }
        var retained = 0
        for (index in 0 until segmentCount) {
            if (start(index) < 0) {
                segments[retained * 3] = row(index)
                segments[retained * 3 + 1] = -start(index) - 1
                segments[retained * 3 + 2] = end(index)
                retained++
            }
        }
        segmentCount = retained
    }

    private fun continuesWrappedRow(
        row: Int,
        end: Int,
        cache: TerminalRenderCache,
    ): Boolean {
        if (!cache.lineWrapped[row]) return false
        for (column in end until cache.columns) {
            if (cache.flags[cache.rowOffset(row) + column] and TerminalRenderCellFlags.WRAP_PADDING == 0) return false
        }
        return true
    }

    private fun firstSegment(row: Int): Int {
        var low = 0
        var high = segmentCount
        while (low < high) {
            val middle = (low + high) ushr 1
            if (row(middle) < row) low = middle + 1 else high = middle
        }
        return low
    }
}
