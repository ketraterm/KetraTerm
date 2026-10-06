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
package io.github.ketraterm.ui.swing.render

import io.github.ketraterm.render.api.*
import io.github.ketraterm.session.TerminalShellIntegrationView
import java.util.*
import kotlin.math.ceil
import kotlin.math.floor

/**
 * EDT-owned sparse prompt index and projection between terminal rows and visual row slots.
 * Each divider occupies one slot. Only prompt identities and absolute positions are retained;
 * cell content stays in the render cache. Ordinary output scans the live grid and newly admitted
 * history, while history replacement/reflow rebuilds the index. Animation never reads the source.
 */
internal class PromptDividerLayout : TerminalRenderCursorSink {
    private var ids = LongArray(0)
    private var positions = LongArray(0)
    private var nextIds = LongArray(0)
    private var nextPositions = LongArray(0)
    private var recordIds = IntArray(0)
    private var lifecycleStates = IntArray(0)
    private var promptEnds = LongArray(0)
    private var commandStarts = LongArray(0)
    private var commandEnds = LongArray(0)
    private var exitCodes = IntArray(0)
    private var dividerRows = LongArray(0)
    private var newPrompts = BooleanArray(0)
    private var count = 0
    private var dividerCount = 0
    private var revision = Long.MIN_VALUE
    private var historyGeneration = Long.MIN_VALUE
    private var contentGeneration = Long.MIN_VALUE
    private var source: TerminalRenderFrameReader? = null
    private var cursorRow = 0
    private var viewportRows = 1
    private var retainedOutput = false
    private var anchorLineId = 0L
    var resolvedAnchorRow: Long = NO_ROW
        private set
    var gridRows: Int = 1
        private set
    private var promptsChanged = false
    private var retainedBottom = 0L

    var discardedCount: Long = 0
        private set
    var liveTop: Long = 0
        private set
    var liveOrigin: Long = 0
        private set
    var visualDiscardedCount: Long = 0
        private set
    val scrollRange: Int get() = liveOrigin.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    private val consumer = TerminalRenderFrameConsumer(::updateFrame)

    fun updateFrom(
        reader: TerminalRenderFrameReader,
        state: TerminalShellIntegrationView,
        viewportRows: Int,
        retainedOutput: Boolean = false,
        anchorLineId: Long = 0,
    ) {
        require(viewportRows > 0)
        if (source !== reader) reset()
        source = reader
        this.viewportRows = viewportRows
        this.retainedOutput = retainedOutput
        this.anchorLineId = anchorLineId
        resolvedAnchorRow = NO_ROW
        val nextRevision = state.revision.value
        promptsChanged = false
        if (revision != nextRevision) {
            copyPrompts(state)
            revision = nextRevision
        }
        reader.readRenderFrameForAbsoluteRange(0, Long.MAX_VALUE, consumer)
    }

    fun reset() {
        source = null
        count = 0
        dividerCount = 0
        revision = Long.MIN_VALUE
        historyGeneration = Long.MIN_VALUE
        contentGeneration = Long.MIN_VALUE
        discardedCount = 0
        visualDiscardedCount = 0
        liveTop = 0
        liveOrigin = 0
        retainedBottom = 0
    }

    /** Visual boundary before a row's optional divider, relative to retained history. */
    fun rowBoundary(absoluteRow: Long): Long = absoluteRow - discardedCount + dividersBefore(absoluteRow)

    fun hasDividerAt(absoluteRow: Long): Boolean = Arrays.binarySearch(dividerRows, 0, dividerCount, absoluteRow) >= 0

    /** Maps a visual slot to its text row, including the band immediately before that row. */
    fun rowAt(slot: Double): Long {
        if (dividerCount == 0) {
            return (floor(slot).toLong() + discardedCount).coerceIn(discardedCount, maxOf(discardedCount, retainedBottom - 1))
        }
        var low = discardedCount
        var high = maxOf(low, retainedBottom - 1)
        while (low < high) {
            val middle = low + (high - low + 1) / 2
            if (rowBoundary(middle).toDouble() <= slot) low = middle else high = middle - 1
        }
        return low
    }

    fun renderOffset(visualOffset: Double): Int =
        (liveTop - rowAt(liveOrigin - ceil(visualOffset))).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

    /** Resolves terminal-native navigation to presentation scroll coordinates. */
    fun visualOffsetForRow(absoluteRow: Long): Int = (liveOrigin - rowBoundary(absoluteRow)).coerceIn(0, scrollRange.toLong()).toInt()

    override fun onCursor(
        column: Int,
        row: Int,
        visible: Boolean,
        blinking: Boolean,
        shape: TerminalRenderCursorShape,
        generation: Long,
    ) {
        cursorRow = row
    }

    private fun copyPrompts(state: TerminalShellIntegrationView) {
        ensureCapacity(state.recordCount())
        val records =
            state.copyRecords(
                recordIds,
                lifecycleStates,
                nextIds,
                promptEnds,
                commandStarts,
                commandEnds,
                exitCodes,
                0,
                nextIds.size,
            )
        Arrays.sort(nextIds, 0, records)
        var nextCount = 0
        for (i in 0 until records) {
            val id = nextIds[i]
            if (id != 0L && (nextCount == 0 || nextIds[nextCount - 1] != id)) nextIds[nextCount++] = id
        }
        promptsChanged = nextCount != count
        var old = 0
        for (i in 0 until nextCount) {
            val id = nextIds[i]
            if (!promptsChanged && ids[i] != id) promptsChanged = true
            while (old < count && ids[old] < id) old++
            val retained = old < count && ids[old] == id
            nextPositions[i] = if (retained) positions[old] else NO_ROW
            newPrompts[i] = !retained
        }
        val oldIds = ids
        ids = nextIds
        nextIds = oldIds
        val oldPositions = positions
        positions = nextPositions
        nextPositions = oldPositions
        count = nextCount
    }

    private fun updateFrame(frame: TerminalRenderFrame) {
        if (frame.activeBuffer == TerminalRenderBufferKind.ALTERNATE) return
        val firstRow = frame.discardedCount + frame.historySize - frame.scrollbackOffset
        val nextLiveTop = frame.discardedCount + frame.historySize
        val historyReplaced = historyGeneration != frame.historyContentGeneration || frame.discardedCount < discardedCount
        if (historyReplaced) {
            visualDiscardedCount = frame.discardedCount
        } else {
            visualDiscardedCount += frame.discardedCount - discardedCount + dividersBefore(frame.discardedCount)
        }
        val mappingChanged =
            historyReplaced ||
                promptsChanged ||
                contentGeneration != frame.contentGeneration ||
                nextLiveTop != liveTop ||
                frame.discardedCount != discardedCount
        if (mappingChanged && count > 0 || anchorLineId != 0L) {
            for (i in 0 until count) {
                if (historyReplaced || positions[i] < frame.discardedCount || positions[i] >= liveTop) positions[i] = NO_ROW
            }
            val scanStart = if (historyReplaced || anchorLineId != 0L) firstRow else maxOf(firstRow, minOf(liveTop, nextLiveTop))
            scan(frame, (scanStart - firstRow).toInt(), frame.rows, firstRow)
            if (promptsChanged && !historyReplaced) {
                for (i in 0 until count) {
                    if (newPrompts[i] && positions[i] == NO_ROW) {
                        scan(frame, 0, (nextLiveTop - firstRow).toInt().coerceAtMost(frame.rows), firstRow)
                        break
                    }
                }
            }
        }
        if (mappingChanged) {
            dividerCount = 0
            for (i in 0 until count) {
                if (positions[i] != NO_ROW) dividerRows[dividerCount++] = positions[i]
            }
            Arrays.sort(dividerRows, 0, dividerCount)
            var unique = 0
            for (i in 0 until dividerCount) {
                if (unique == 0 || dividerRows[unique - 1] != dividerRows[i]) dividerRows[unique++] = dividerRows[i]
            }
            dividerCount = unique
        }
        discardedCount = frame.discardedCount
        liveTop = nextLiveTop
        historyGeneration = frame.historyContentGeneration
        contentGeneration = frame.contentGeneration
        retainedBottom = firstRow + frame.rows
        gridRows = (retainedBottom - liveTop).toInt()
        val outputEnd = frame.outputEndAbsoluteRow.coerceIn(liveTop, retainedBottom)
        frame.copyCursor(this)
        val cursorAbsoluteRow = (firstRow + cursorRow).coerceIn(liveTop, maxOf(liveTop, retainedBottom - 1))
        val cursorTextSlot = rowBoundary(cursorAbsoluteRow) + if (hasDividerAt(cursorAbsoluteRow)) 1 else 0
        liveOrigin =
            if (retainedOutput) {
                (rowBoundary(retainedBottom) - viewportRows).coerceAtLeast(0)
            } else {
                maxOf(
                    rowBoundary(liveTop),
                    rowBoundary(outputEnd) - viewportRows,
                    rowBoundary(cursorAbsoluteRow + 1) - viewportRows,
                ).coerceIn(0, cursorTextSlot.coerceAtLeast(0))
            }
    }

    private fun scan(
        frame: TerminalRenderFrame,
        start: Int,
        end: Int,
        firstRow: Long,
    ) {
        for (row in start until end) {
            val id = frame.lineId(row)
            if (id == 0L) continue
            if (id == anchorLineId && resolvedAnchorRow == NO_ROW) resolvedAnchorRow = firstRow + row
            val index = Arrays.binarySearch(ids, 0, count, id)
            if (index >= 0 && positions[index] == NO_ROW) positions[index] = firstRow + row
        }
    }

    private fun dividersBefore(absoluteRow: Long): Int {
        val index = Arrays.binarySearch(dividerRows, 0, dividerCount, absoluteRow)
        return if (index >= 0) index else -index - 1
    }

    private fun ensureCapacity(records: Int) {
        if (ids.size >= records) return
        val capacity = maxOf(records, maxOf(16, ids.size * 2))
        ids = ids.copyOf(capacity)
        positions = positions.copyOf(capacity)
        nextIds = LongArray(capacity)
        nextPositions = LongArray(capacity)
        dividerRows = dividerRows.copyOf(capacity)
        newPrompts = BooleanArray(capacity)
        recordIds = IntArray(capacity)
        lifecycleStates = IntArray(capacity)
        promptEnds = LongArray(capacity)
        commandStarts = LongArray(capacity)
        commandEnds = LongArray(capacity)
        exitCodes = IntArray(capacity)
    }

    private companion object {
        const val NO_ROW = Long.MIN_VALUE
    }
}
