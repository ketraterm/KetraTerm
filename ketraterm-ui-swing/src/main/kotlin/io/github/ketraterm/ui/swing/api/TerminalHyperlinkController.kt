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

import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.ui.swing.render.hyperlinkIdForCell
import java.awt.Cursor
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities

internal interface TerminalHyperlinkHost {
    val renderCache: TerminalRenderCache
    var cursor: Cursor

    fun cellAt(
        x: Int,
        y: Int,
    ): Long

    fun repaintHyperlinkSpan(
        startRow: Int,
        startColumn: Int,
        endRow: Int,
        endColumn: Int,
    )

    fun hyperlinkIdAt(
        row: Int,
        column: Int,
    ): Int

    fun isHyperlinkResolvable(hyperlinkId: Int): Boolean

    fun openHyperlink(hyperlinkId: Int): Boolean
}

internal class TerminalHyperlinkController(
    private val host: TerminalHyperlinkHost,
) {
    companion object {
        private const val NO_HYPERLINK_ID = 0
        private val HAND_CURSOR: Cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        private val DEFAULT_CURSOR: Cursor = Cursor.getDefaultCursor()

        private fun unpackCellColumn(packed: Long): Int = (packed ushr 32).toInt()

        private fun unpackCellRow(packed: Long): Int = packed.toInt()
    }

    var hoveredHyperlinkId: Int = NO_HYPERLINK_ID
        private set
    var hyperlinkActivationHover: Boolean = false
        private set

    // Row/start/end triples retain the previous projection so erased or moved segments are repainted.
    private var segments = IntArray(0)
    private var pendingSegments = IntArray(0)
    var hoveredSegmentCount: Int = 0
        private set
    private var pendingSegmentCount = 0
    private var pointerKnown = false
    private var pointerX = 0
    private var pointerY = 0
    private var controlDown = false

    fun segmentRow(index: Int): Int = segments[index * 3]

    fun segmentStartColumn(index: Int): Int = segments[index * 3 + 1]

    fun segmentEndColumn(index: Int): Int = segments[index * 3 + 2]

    fun handleMouseMoved(event: MouseEvent) {
        controlDown = event.isControlDown
        pointerKnown = true
        pointerX = event.x
        pointerY = event.y
        val id = hyperlinkIdAt(event)
        if (id == hoveredHyperlinkId) {
            updateHyperlinkActivationHover(controlDown)
        } else {
            refreshHyperlinkHover()
        }
    }

    /** Restores stationary-pointer state after showing or returning to the application. */
    fun updatePointerPosition(
        x: Int,
        y: Int,
    ) {
        pointerKnown = true
        pointerX = x
        pointerY = y
        refreshHyperlinkHover()
    }

    fun handleMouseExited() = clearHyperlinkHover()

    fun handleMousePressed(event: MouseEvent): Boolean {
        if (!SwingUtilities.isLeftMouseButton(event) || !event.isControlDown) return false
        val hyperlinkId = hyperlinkIdAt(event)
        if (hyperlinkId == NO_HYPERLINK_ID || !host.openHyperlink(hyperlinkId)) return false
        event.consume()
        return true
    }

    fun hyperlinkIdAt(event: MouseEvent): Int {
        val cache = host.renderCache
        if (cache.columns <= 0 || cache.rows <= 0) return NO_HYPERLINK_ID
        return resolvableHyperlinkIdAt(host.cellAt(event.x, event.y))
    }

    fun updateHyperlinkActivationHover(active: Boolean) {
        controlDown = active
        if (hoveredHyperlinkId == NO_HYPERLINK_ID || hyperlinkActivationHover == active) return
        hyperlinkActivationHover = active
        repaintSegments()
    }

    fun clearHyperlinkHover() {
        pointerKnown = false
        controlDown = false
        pendingSegmentCount = 0
        applyHyperlinkHover(NO_HYPERLINK_ID)
    }

    /** Reprojects the semantic occurrence after frame, geometry, or detected-link changes. */
    fun refreshHyperlinkHover() {
        if (!pointerKnown) return
        val cache = host.renderCache
        val cell = if (cache.columns > 0 && cache.rows > 0) host.cellAt(pointerX, pointerY) else -1L
        val id = resolvableHyperlinkIdAt(cell)
        pendingSegmentCount = 0
        if (id != NO_HYPERLINK_ID) {
            // Identity distinguishes explicit OSC 8 groups, anonymous runs and detector occurrences.
            for (row in 0 until cache.rows) {
                var column = 0
                while (column < cache.columns) {
                    if (hyperlinkIdAt(row, column) != id) {
                        column++
                        continue
                    }
                    val start = column++
                    while (column < cache.columns && hyperlinkIdAt(row, column) == id) column++
                    val offset = pendingSegmentCount * 3
                    if (offset + 3 > pendingSegments.size) {
                        pendingSegments = pendingSegments.copyOf(maxOf(24, pendingSegments.size * 2))
                    }
                    pendingSegments[offset] = row
                    pendingSegments[offset + 1] = start
                    pendingSegments[offset + 2] = column
                    pendingSegmentCount++
                }
            }
        }
        applyHyperlinkHover(id)
    }

    private fun applyHyperlinkHover(id: Int) {
        val activation = id != NO_HYPERLINK_ID && controlDown
        var changed =
            hoveredHyperlinkId != id ||
                hyperlinkActivationHover != activation ||
                hoveredSegmentCount != pendingSegmentCount
        if (!changed) {
            for (index in 0 until hoveredSegmentCount * 3) {
                if (segments[index] != pendingSegments[index]) {
                    changed = true
                    break
                }
            }
        }
        if (changed) {
            repaintSegments()
            val previous = segments
            segments = pendingSegments
            pendingSegments = previous
            hoveredSegmentCount = pendingSegmentCount
            hoveredHyperlinkId = id
            hyperlinkActivationHover = activation
            repaintSegments()
        }
        val cursor = if (id != NO_HYPERLINK_ID) HAND_CURSOR else DEFAULT_CURSOR
        if (host.cursor !== cursor) host.cursor = cursor
    }

    private fun repaintSegments() {
        for (index in 0 until hoveredSegmentCount) {
            val row = segmentRow(index)
            host.repaintHyperlinkSpan(row, segmentStartColumn(index), row, segmentEndColumn(index))
        }
    }

    private fun resolvableHyperlinkIdAt(cell: Long): Int {
        val cache = host.renderCache
        val column = unpackCellColumn(cell)
        val row = unpackCellRow(cell)
        if (row !in 0 until cache.rows || column !in 0 until cache.columns) return NO_HYPERLINK_ID
        val id = hyperlinkIdAt(row, column)
        return if (id != NO_HYPERLINK_ID && host.isHyperlinkResolvable(id)) id else NO_HYPERLINK_ID
    }

    private fun hyperlinkIdAt(
        row: Int,
        column: Int,
    ): Int = hyperlinkIdForCell(host.hyperlinkIdAt(row, column), host.renderCache.flags[host.renderCache.rowOffset(row) + column])
}
