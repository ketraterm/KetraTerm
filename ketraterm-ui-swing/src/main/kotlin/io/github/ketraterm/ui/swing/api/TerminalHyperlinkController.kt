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

import io.github.ketraterm.render.api.TerminalRenderAttrs
import io.github.ketraterm.render.api.TerminalRenderBufferKind
import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.ui.swing.input.hyperlinkNavigationModifierDown
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

    fun openHyperlink(
        hyperlinkId: Int,
        event: MouseEvent,
    ): Boolean = openHyperlink(hyperlinkId)

    fun hyperlinkActivation(hyperlinkId: Int): SwingHyperlinkActivation = SwingHyperlinkActivation.MODIFIER

    fun hyperlinkAction(hyperlinkId: Int): SwingHyperlinkAction? = null

    fun isHyperlinkVisible(hyperlinkId: Int): Boolean = true

    fun enterHyperlink(
        action: SwingHyperlinkAction,
        row: Int,
        startColumn: Int,
        endColumn: Int,
    ) = Unit
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

    var hover = TerminalHyperlinkHover()
        private set
    private var pendingHover = TerminalHyperlinkHover()
    val hoveredHyperlinkId: Int get() = hover.hyperlinkId
    val hyperlinkActivationHover: Boolean get() = hover.activation
    val hoveredSegmentCount: Int get() = hover.segmentCount
    private var pointerKnown = false
    private var pointerX = 0
    private var pointerY = 0
    private var controlDown = false
    private var pressedHyperlinkId = 0
    private var pressedAbsoluteRow = 0L
    private var pressedColumn = 0
    private var pressedLineId = 0L
    private var pressedLineGeneration = 0L
    private var pressedHistoryGeneration = 0L
    private var pressedBuffer = TerminalRenderBufferKind.PRIMARY
    private var hoverAction: SwingHyperlinkAction? = null

    fun segmentRow(index: Int): Int = hover.row(index)

    fun segmentStartColumn(index: Int): Int = hover.start(index)

    fun segmentEndColumn(index: Int): Int = hover.end(index)

    fun handleMouseMoved(
        event: MouseEvent,
        enabled: Boolean = true,
    ) {
        controlDown = hyperlinkNavigationModifierDown(event)
        pointerKnown = true
        pointerX = event.x
        pointerY = event.y
        if (!enabled) {
            clearHyperlinkHover(forgetPointer = false)
            return
        }
        val id = hyperlinkIdAt(event)
        val cell = host.cellAt(event.x, event.y)
        if (id == hoveredHyperlinkId && hover.contains(unpackCellRow(cell), unpackCellColumn(cell))) {
            updateHyperlinkActivationHover(controlDown)
        } else {
            refreshHyperlinkHover()
        }
    }

    /** Restores stationary-pointer state after showing or returning to the application. */
    fun updatePointerPosition(
        x: Int,
        y: Int,
        enabled: Boolean = true,
    ) {
        pointerKnown = true
        pointerX = x
        pointerY = y
        if (enabled) refreshHyperlinkHover() else clearHyperlinkHover(forgetPointer = false)
    }

    fun handleMouseExited() = clearHyperlinkHover()

    fun handleMousePressed(event: MouseEvent): Boolean {
        pressedHyperlinkId = 0
        if (!SwingUtilities.isLeftMouseButton(event) ||
            event.clickCount != 1 ||
            (event.isShiftDown && !hyperlinkNavigationModifierDown(event))
        ) {
            return false
        }
        controlDown = hyperlinkNavigationModifierDown(event)
        updatePointerPosition(event.x, event.y)
        val hyperlinkId = hoveredHyperlinkId
        if (hyperlinkId != NO_HYPERLINK_ID &&
            canActivate(hyperlinkId, controlDown)
        ) {
            pressedHyperlinkId = hyperlinkId
            if (hyperlinkId > 0) {
                val cache = host.renderCache
                val cell = host.cellAt(event.x, event.y)
                val row = unpackCellRow(cell)
                pressedAbsoluteRow = cache.discardedCount + cache.historySize - cache.scrollbackOffset + row
                pressedColumn = unpackCellColumn(cell)
                pressedLineId = cache.lineIds[row]
                pressedLineGeneration = cache.lineGenerations[row]
                pressedHistoryGeneration = cache.historyContentGeneration
                pressedBuffer = cache.activeBuffer
            }
        }
        // Direct links still allow a selection drag. Modifier clicks preserve an existing selection.
        return pressedHyperlinkId != 0 && hyperlinkNavigationModifierDown(event)
    }

    fun handleMouseDragged() {
        pressedHyperlinkId = 0
    }

    fun handleMouseReleased(event: MouseEvent): Boolean {
        val pressed = pressedHyperlinkId
        pressedHyperlinkId = 0
        if (!SwingUtilities.isLeftMouseButton(event) || pressed == 0) return false
        controlDown = hyperlinkNavigationModifierDown(event)
        updatePointerPosition(event.x, event.y)
        if (hoveredHyperlinkId != pressed ||
            (pressed > 0 && !pressedOsc8OccurrenceIsHovered()) ||
            !canActivate(pressed, hyperlinkNavigationModifierDown(event)) ||
            !host.openHyperlink(pressed, event)
        ) {
            return false
        }
        event.consume()
        return true
    }

    /** A shared OSC 8 ID cannot identify which displayed occurrence received the press. */
    private fun pressedOsc8OccurrenceIsHovered(): Boolean {
        val cache = host.renderCache
        if (cache.activeBuffer != pressedBuffer || cache.historyContentGeneration != pressedHistoryGeneration) return false
        val row = pressedAbsoluteRow - (cache.discardedCount + cache.historySize - cache.scrollbackOffset)
        return !(row !in 0 until cache.rows.toLong() || !hover.contains(row.toInt(), pressedColumn)) && if (pressedLineId != 0L) {
            cache.lineIds[row.toInt()] == pressedLineId
        } else {
            cache.lineGenerations[row.toInt()] == pressedLineGeneration
        }
    }

    private fun canActivate(
        id: Int,
        modifier: Boolean,
    ): Boolean = host.hyperlinkActivation(id) == SwingHyperlinkActivation.DIRECT || modifier

    fun hyperlinkIdAt(event: MouseEvent): Int {
        val cache = host.renderCache
        if (cache.columns <= 0 || cache.rows <= 0) return NO_HYPERLINK_ID
        return resolvableHyperlinkIdAt(host.cellAt(event.x, event.y))
    }

    fun updateHyperlinkActivationHover(active: Boolean) {
        controlDown = active
        val activation = hoveredHyperlinkId != NO_HYPERLINK_ID && canActivate(hoveredHyperlinkId, active)
        if (hoveredHyperlinkId == NO_HYPERLINK_ID || hyperlinkActivationHover == activation) return
        hover.activation = activation
        updateCursor(hoveredHyperlinkId, activation)
        repaintSegments()
    }

    fun clearHyperlinkHover(forgetPointer: Boolean = true) {
        if (forgetPointer) {
            pointerKnown = false
            controlDown = false
        }
        pressedHyperlinkId = 0
        pendingHover.reset()
        applyHyperlinkHover(NO_HYPERLINK_ID)
    }

    /** Reprojects the semantic occurrence after frame, geometry, or detected-link changes. */
    fun refreshHyperlinkHover() {
        if (!pointerKnown) return
        val cache = host.renderCache
        val cell = if (cache.columns > 0 && cache.rows > 0) host.cellAt(pointerX, pointerY) else -1L
        val id = resolvableHyperlinkIdAt(cell)
        pendingHover.reset()
        if (id != NO_HYPERLINK_ID) {
            // Detector IDs identify occurrences; OSC 8 IDs can be shared by separate displayed links.
            for (row in 0 until cache.rows) {
                var column = 0
                while (column < cache.columns) {
                    if (hyperlinkIdAt(row, column) != id) {
                        column++
                        continue
                    }
                    val start = column++
                    while (column < cache.columns && hyperlinkIdAt(row, column) == id) column++
                    pendingHover.add(row, start, column)
                }
            }
        }
        if (id > 0) pendingHover.retainConnected(unpackCellRow(cell), unpackCellColumn(cell), cache)
        applyHyperlinkHover(id)
    }

    private fun applyHyperlinkHover(id: Int) {
        val occurrenceChanged = id != hoveredHyperlinkId
        val activation = id != NO_HYPERLINK_ID && canActivate(id, controlDown)
        pendingHover.setIdentity(id, activation)
        if (!hover.sameAs(pendingHover)) {
            repaintSegments()
            val previous = hover
            hover = pendingHover
            pendingHover = previous
            repaintSegments()
        }
        val action = if (id == 0) null else host.hyperlinkAction(id)
        if (occurrenceChanged || hoverAction !== action) {
            hoverAction?.mouseExited()
            hoverAction = action
            if (action != null && hoveredSegmentCount > 0) {
                val cell = host.cellAt(pointerX, pointerY)
                val row = unpackCellRow(cell)
                val column = unpackCellColumn(cell)
                for (segment in 0 until hoveredSegmentCount) {
                    if (segmentRow(segment) == row && column in segmentStartColumn(segment) until segmentEndColumn(segment)) {
                        host.enterHyperlink(action, row, segmentStartColumn(segment), segmentEndColumn(segment))
                        break
                    }
                }
            }
        }
        updateCursor(id, activation)
    }

    private fun updateCursor(
        id: Int,
        activation: Boolean,
    ) {
        val cursor = if (id != NO_HYPERLINK_ID && (host.isHyperlinkVisible(id) || activation)) HAND_CURSOR else DEFAULT_CURSOR
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
        if (TerminalRenderAttrs.isInvisible(cache.attrWords[cache.rowOffset(row) + column])) return NO_HYPERLINK_ID
        val id = hyperlinkIdAt(row, column)
        return if (id != NO_HYPERLINK_ID && host.isHyperlinkResolvable(id)) id else NO_HYPERLINK_ID
    }

    private fun hyperlinkIdAt(
        row: Int,
        column: Int,
    ): Int {
        val cell = host.renderCache.rowOffset(row) + column
        if (TerminalRenderAttrs.isInvisible(host.renderCache.attrWords[cell])) return NO_HYPERLINK_ID
        return hyperlinkIdForCell(host.hyperlinkIdAt(row, column), host.renderCache.flags[cell])
    }
}
