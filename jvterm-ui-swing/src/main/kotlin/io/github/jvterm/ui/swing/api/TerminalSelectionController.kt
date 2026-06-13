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
package io.github.jvterm.ui.swing.api

import io.github.jvterm.render.cache.TerminalRenderCache
import io.github.jvterm.ui.swing.settings.TerminalSwingMetrics
import io.github.jvterm.ui.swing.settings.TerminalSwingSettings
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities
import javax.swing.Timer

internal interface TerminalSelectionHost {
    val settings: TerminalSwingSettings
    val metrics: TerminalSwingMetrics
    val renderCache: TerminalRenderCache
    val contentYOffset: Double
    val componentWidth: Int
    val componentHeight: Int

    fun cellAt(
        x: Int,
        y: Int,
    ): Long

    fun scrollViewportBy(
        delta: Double,
        historySize: Int,
    ): Boolean

    fun repaint()

    fun requestFocusInWindow(): Boolean
}

internal class TerminalSelectionController(
    private val host: TerminalSelectionHost,
) {
    private val selectionTextExtractor = TerminalSelectionTextExtractor()

    var selectionAnchorAbsoluteRow: Long? = null
        private set
    var selectionAnchorColumn: Int = 0
        private set
    var selectionAnchorRow: Int = 0
        private set
    var selectionCaretAbsoluteRow: Long? = null
        private set
    var selectionCaretColumn: Int = 0
        private set
    var selectingWithMouse: Boolean = false
        private set
    var selectionIsBlock: Boolean = false
        private set
    var lastSelectionDragX: Int = 0
        private set
    var lastSelectionDragY: Int = 0
        private set

    private val selectionAutoscrollTimer =
        Timer(50) {
            handleSelectionAutoscrollTick()
        }

    fun clearSelection() {
        selectionAnchorAbsoluteRow = null
        selectionCaretAbsoluteRow = null
    }

    fun stopSelectionDrag() {
        selectingWithMouse = false
        selectionAutoscrollTimer.stop()
    }

    fun handleSelectionMousePressed(event: MouseEvent) {
        if (!SwingUtilities.isLeftMouseButton(event)) return
        host.requestFocusInWindow()

        selectingWithMouse = true
        selectionIsBlock = event.isAltDown
        lastSelectionDragX = event.x
        lastSelectionDragY = event.y

        val cache = host.renderCache
        val cell = host.cellAt(event.x, event.y)
        val column = unpackCellColumn(cell)
        val row = unpackCellRow(cell)

        when {
            event.clickCount >= 3 -> {
                val absRow = cache.discardedCount + cache.historySize - cache.scrollbackOffset + row
                selectionAnchorAbsoluteRow = absRow
                selectionAnchorColumn = 0
                selectionCaretAbsoluteRow = absRow
                selectionCaretColumn = cache.columns
            }

            event.clickCount == 2 -> {
                val wordSel = selectionTextExtractor.wordSelectionAt(cache, row, column)
                if (wordSel != null) {
                    selectionAnchorAbsoluteRow = cache.discardedCount + cache.historySize - cache.scrollbackOffset + wordSel.anchorRow
                    selectionAnchorColumn = wordSel.anchorColumn
                    selectionCaretAbsoluteRow = cache.discardedCount + cache.historySize - cache.scrollbackOffset + wordSel.caretRow
                    selectionCaretColumn = wordSel.caretColumn
                } else {
                    clearSelection()
                }
            }

            else -> {
                selectionAnchorColumn = column
                selectionAnchorRow = row
                selectionAnchorAbsoluteRow = cache.discardedCount + cache.historySize - cache.scrollbackOffset + row
                selectionCaretAbsoluteRow = null
            }
        }
        updateSelectionAutoscroll()
        host.repaint()
        event.consume()
    }

    fun handleSelectionMouseDragged(event: MouseEvent) {
        if (event.modifiersEx and MouseEvent.BUTTON1_DOWN_MASK == 0) return

        selectingWithMouse = true
        selectionIsBlock = event.isAltDown
        lastSelectionDragX = event.x
        lastSelectionDragY = event.y

        updateSelectionCaret(event.x, event.y)
        updateSelectionAutoscroll()
        host.repaint()
        event.consume()
    }

    fun handleSelectionMouseReleased(event: MouseEvent) {
        if (SwingUtilities.isLeftMouseButton(event)) {
            stopSelectionDrag()
            event.consume()
        }
    }

    fun getViewportSelection(cache: TerminalRenderCache?): CellSelection? {
        val anchorAbsRow = selectionAnchorAbsoluteRow ?: return null
        val caretAbsRow = selectionCaretAbsoluteRow ?: return null
        val c = cache ?: return null

        val isForward =
            caretAbsRow > anchorAbsRow ||
                (caretAbsRow == anchorAbsRow && selectionCaretColumn >= selectionAnchorColumn)

        val startAbsRow = if (isForward) anchorAbsRow else caretAbsRow
        val startCol = if (isForward) selectionAnchorColumn else selectionCaretColumn
        val endAbsRow = if (isForward) caretAbsRow else anchorAbsRow
        val endCol = if (isForward) selectionCaretColumn else selectionAnchorColumn

        val startViewportRow = (startAbsRow - c.discardedCount - (c.historySize - c.scrollbackOffset)).toInt()
        val endViewportRow = (endAbsRow - c.discardedCount - (c.historySize - c.scrollbackOffset)).toInt()

        if (endViewportRow < 0 || startViewportRow >= c.rows) {
            return null
        }

        val clampedStartRow = startViewportRow.coerceIn(0, c.rows - 1)
        val clampedStartCol = if (startViewportRow < 0) 0 else startCol.coerceIn(0, c.columns)

        val clampedEndRow = endViewportRow.coerceIn(0, c.rows - 1)
        val clampedEndCol = if (endViewportRow >= c.rows) c.columns else endCol.coerceIn(0, c.columns)

        return if (isForward) {
            CellSelection(
                anchorColumn = clampedStartCol,
                anchorRow = clampedStartRow,
                caretColumn = clampedEndCol,
                caretRow = clampedEndRow,
                isBlock = selectionIsBlock,
            )
        } else {
            CellSelection(
                anchorColumn = clampedEndCol,
                anchorRow = clampedEndRow,
                caretColumn = clampedStartCol,
                caretRow = clampedStartRow,
                isBlock = selectionIsBlock,
            )
        }
    }

    fun getSelectedText(cache: TerminalRenderCache): String? {
        val currentSelection = getViewportSelection(cache) ?: return null
        val text = selectionTextExtractor.selectedText(cache, currentSelection)
        return if (text.isEmpty()) null else text
    }

    private fun updateSelectionCaret(
        x: Int,
        y: Int,
    ) {
        val cache = host.renderCache
        val cell = host.cellAt(x, y)
        val column = unpackCellColumn(cell)
        val row = unpackCellRow(cell)

        val caretAbsRow = cache.discardedCount + cache.historySize - cache.scrollbackOffset + row
        val anchorAbsRow =
            selectionAnchorAbsoluteRow ?: (cache.discardedCount + cache.historySize - cache.scrollbackOffset + selectionAnchorRow)

        val caretColumn =
            if (
                caretAbsRow < anchorAbsRow ||
                (caretAbsRow == anchorAbsRow && column < selectionAnchorColumn)
            ) {
                column
            } else {
                column + 1
            }

        selectionAnchorAbsoluteRow = anchorAbsRow
        selectionCaretAbsoluteRow = caretAbsRow
        selectionCaretColumn = caretColumn
    }

    private fun updateSelectionAutoscroll() {
        if (selectingWithMouse && selectionAutoscrollDelta(lastSelectionDragY) != 0.0) {
            if (!selectionAutoscrollTimer.isRunning) selectionAutoscrollTimer.start()
        } else {
            selectionAutoscrollTimer.stop()
        }
    }

    private fun selectionAutoscrollDelta(y: Int): Double {
        val padding = host.settings.padding
        return when {
            y < padding.top -> {
                val distance = padding.top - y
                kotlin.math.floor(1.0 + (distance / 20.0))
            }

            y >= host.componentHeight - padding.bottom -> {
                val distance = y - (host.componentHeight - padding.bottom)
                -kotlin.math.floor(1.0 + (distance / 20.0))
            }

            else -> 0.0
        }
    }

    private fun handleSelectionAutoscrollTick() {
        if (!selectingWithMouse) {
            selectionAutoscrollTimer.stop()
            return
        }

        val cache = host.renderCache
        val delta = selectionAutoscrollDelta(lastSelectionDragY)
        if (delta == 0.0) {
            selectionAutoscrollTimer.stop()
            return
        }

        val changed = host.scrollViewportBy(delta, cache.historySize)
        if (changed) {
            updateSelectionCaret(lastSelectionDragX, lastSelectionDragY)
        }
    }

    private companion object {
        private fun unpackCellColumn(packed: Long): Int = (packed ushr 32).toInt()

        private fun unpackCellRow(packed: Long): Int = packed.toInt()
    }
}
