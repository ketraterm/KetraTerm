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
package io.github.ketraterm.render.cache

import io.github.ketraterm.render.api.TerminalRenderBufferKind
import io.github.ketraterm.render.api.TerminalRenderClusterDataSink
import io.github.ketraterm.render.api.TerminalRenderFrameReader

/**
 * Worker-confined, reusable text-only copy of a bounded absolute row range.
 *
 * Only primitive copying occurs inside the reader callback. Arrays are borrowed until
 * the next [read]; only [rows] times [columns] cells are active. Every active code/flag
 * entry is overwritten, so spare capacity needs no clearing. Cluster references are
 * meaningful only for cells with the CLUSTER flag and a nonzero reference.
 *
 * Bounds and cancellation have the same meaning as [TerminalRenderRangeCopy]. Failed
 * or empty reads leave [rows] zero; partially copied arrays must not be consumed.
 */
public class TerminalTextRangeCopy(
    public val maxRows: Int = 64,
    public val maxCells: Int = 4096,
) {
    init {
        require(maxRows > 0 && maxCells > 0)
    }

    /** Column stride of the active cell arrays; usable when [rows] is nonzero. */
    public var columns: Int = 0
        private set

    /** Number of usable rows; zero until a read completes successfully. */
    public var rows: Int = 0
        private set

    /** First row of the requested/retained intersection. */
    public var firstAbsoluteRow: Long = 0L
        private set

    /** Inclusive last copied row, or less than [firstAbsoluteRow] for an empty read. */
    public var lastAbsoluteRow: Long = -1L
        private set

    /** Source buffer for the completed batch. */
    public var activeBuffer: TerminalRenderBufferKind = TerminalRenderBufferKind.PRIMARY
        private set

    /** Source content generation for the completed batch. */
    public var contentGeneration: Long = 0L
        private set

    /** Source history identity/layout generation for the completed batch. */
    public var historyContentGeneration: Long = 0L
        private set

    /** Unicode scalars, with zero for empty, cluster, and trailing cells. */
    public var codeWords: IntArray = IntArray(0)
        private set

    /** Public render cell flags, including wide cells and wrap padding. */
    public var flags: IntArray = IntArray(0)
        private set

    /** Packed cluster offset (high 32 bits) and length (low 32 bits). */
    public var clusterRefs: LongArray = LongArray(0)
        private set

    /** Primitive cluster payload, valid only through references in active cluster cells. */
    public var clusterCodepoints: IntArray = IntArray(0)
        private set

    /** Source identities for active rows. */
    public var lineIds: LongArray = LongArray(0)
        private set

    /** Source generations for active rows. */
    public var lineGenerations: LongArray = LongArray(0)
        private set

    /** Whether each active physical row continues into the next. */
    public var lineWrapped: BooleanArray = BooleanArray(0)
        private set

    private var clusterSize = 0
    private var sinkOffset = 0
    private val clusterSink =
        TerminalRenderClusterDataSink { column, source, offset, length ->
            require(column in 0 until columns)
            require(length > 0 && offset >= 0)
            val copiedLength = minOf(length, MAX_COPIED_CLUSTER_CODEPOINTS)
            require(source.size - offset >= copiedLength)
            require(clusterSize <= Int.MAX_VALUE - copiedLength)
            val required = clusterSize + copiedLength
            if (required > clusterCodepoints.size) {
                val capacity =
                    maxOf(
                        required.toLong(),
                        clusterCodepoints.size.toLong() * 2,
                        256L,
                    ).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                clusterCodepoints = clusterCodepoints.copyOf(capacity)
            }
            source.copyInto(clusterCodepoints, clusterSize, offset, offset + copiedLength)
            clusterRefs[sinkOffset + column] = (clusterSize.toLong() shl 32) or copiedLength.toLong()
            clusterSize = required
        }

    /**
     * Copies one retained intersection, at most [maxRows] rows and [maxCells] cells,
     * except that a single row wider than the cell budget is allowed. Returns whether
     * a complete nonempty batch was copied. [checkCancelled] must not suspend or block.
     */
    public fun read(
        reader: TerminalRenderFrameReader,
        startAbsoluteRow: Long,
        endAbsoluteRow: Long,
        checkCancelled: () -> Unit = {},
    ): Boolean {
        rows = 0
        require(startAbsoluteRow in 0..endAbsoluteRow)
        clusterSize = 0
        firstAbsoluteRow = startAbsoluteRow
        lastAbsoluteRow = startAbsoluteRow - 1L
        val requestedEnd = startAbsoluteRow + minOf(endAbsoluteRow - startAbsoluteRow, maxRows - 1L)
        reader.readRenderFrameForAbsoluteRange(startAbsoluteRow, requestedEnd) { frame ->
            checkCancelled()
            val top = frame.discardedCount + frame.historySize - frame.scrollbackOffset
            firstAbsoluteRow = maxOf(startAbsoluteRow, top)
            val count = minOf(maxRows, maxOf(1, maxCells / frame.columns))
            val last = minOf(endAbsoluteRow, top + frame.rows - 1L, firstAbsoluteRow + minOf(endAbsoluteRow - firstAbsoluteRow, count - 1L))
            if (last < firstAbsoluteRow) return@readRenderFrameForAbsoluteRange
            val offset = (firstAbsoluteRow - top).toInt()
            val copiedRows = (last - firstAbsoluteRow + 1L).toInt()
            require(frame.columns <= Int.MAX_VALUE / copiedRows)
            val cells = frame.columns * copiedRows
            if (cells > codeWords.size) {
                codeWords = IntArray(cells)
                flags = IntArray(cells)
                clusterRefs = LongArray(cells)
            }
            if (copiedRows > lineIds.size) {
                lineIds = LongArray(copiedRows)
                lineGenerations = LongArray(copiedRows)
                lineWrapped = BooleanArray(copiedRows)
            }
            columns = frame.columns
            for (row in 0 until copiedRows) {
                checkCancelled()
                sinkOffset = row * columns
                // An optional/missing cluster callback must never expose an earlier payload.
                clusterRefs.fill(0L, sinkOffset, sinkOffset + columns)
                frame.copyTextLine(offset + row, codeWords, sinkOffset, flags, sinkOffset, clusterSink)
                lineIds[row] = frame.lineId(offset + row)
                lineGenerations[row] = frame.lineGeneration(offset + row)
                lineWrapped[row] = frame.lineWrapped(offset + row)
            }
            activeBuffer = frame.activeBuffer
            contentGeneration = frame.contentGeneration
            historyContentGeneration = frame.historyContentGeneration
            lastAbsoluteRow = last
            // Publish only after the reader callback itself has returned successfully.
        }
        if (lastAbsoluteRow >= firstAbsoluteRow) rows = (lastAbsoluteRow - firstAbsoluteRow + 1L).toInt()
        return rows > 0
    }
}
