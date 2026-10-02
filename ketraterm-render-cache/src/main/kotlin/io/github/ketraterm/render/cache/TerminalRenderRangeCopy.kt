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

import io.github.ketraterm.render.api.*

/**
 * Worker-owned primitive copy of an absolute source range, reusable across reads.
 *
 * Only row/cell copying occurs in the reader callback (and any session mutation
 * lock); string assembly and analysis use [cache] after [read] returns. Each read
 * copies at most [maxRows] and [maxCells], except that one physical row is always
 * permitted when wider than the cell budget. Cluster payloads copy with that row.
 * The cache and bounds remain owned by this instance until the next read.
 */
class TerminalRenderRangeCopy(
    val maxRows: Int = 64,
    val maxCells: Int = 4096,
) {
    init {
        require(maxRows > 0 && maxCells > 0)
    }

    /** Copied cells and source generations; use [firstAbsoluteRow] for the sliced row origin. */
    val cache = TerminalRenderCache(1, 1, rowCapacityReserve = maxRows - 1)

    /** First copied absolute row, or the requested/retained intersection start for an empty read. */
    var firstAbsoluteRow: Long = 0L
        private set

    /** Inclusive last copied absolute row; smaller than [firstAbsoluteRow] for an empty read. */
    var lastAbsoluteRow: Long = -1L
        private set

    /**
     * Copies one bounded retained intersection. Returns false if no requested row
     * remains. The reader owns synchronization; [checkCancelled] must be cheap and
     * must not suspend or perform analysis while the reader's lock is held.
     */
    fun read(
        reader: TerminalRenderFrameReader,
        startAbsoluteRow: Long,
        endAbsoluteRow: Long,
        checkCancelled: () -> Unit = {},
    ): Boolean {
        require(startAbsoluteRow >= 0 && endAbsoluteRow >= startAbsoluteRow)
        firstAbsoluteRow = startAbsoluteRow
        lastAbsoluteRow = startAbsoluteRow - 1L
        cache.reset()
        val requestedEnd = startAbsoluteRow + minOf(endAbsoluteRow - startAbsoluteRow, maxRows - 1L)
        reader.readRenderFrameForAbsoluteRange(startAbsoluteRow, requestedEnd) { frame ->
            checkCancelled()
            val top = frame.discardedCount + frame.historySize - frame.scrollbackOffset
            firstAbsoluteRow = maxOf(startAbsoluteRow, top)
            val count = minOf(maxRows, maxOf(1, maxCells / frame.columns))
            lastAbsoluteRow =
                minOf(
                    endAbsoluteRow,
                    top + frame.rows - 1L,
                    firstAbsoluteRow + minOf(endAbsoluteRow - firstAbsoluteRow, count - 1L),
                )
            if (lastAbsoluteRow < firstAbsoluteRow) return@readRenderFrameForAbsoluteRange
            val offset = (firstAbsoluteRow - top).toInt()
            val rows = (lastAbsoluteRow - firstAbsoluteRow + 1L).toInt()
            val slice =
                object : TerminalRenderFrame by frame {
                    override val rows = rows

                    override fun lineId(row: Int) = frame.lineId(offset + row)

                    override fun lineGeneration(row: Int) = frame.lineGeneration(offset + row)

                    override fun lineWrapped(row: Int) = frame.lineWrapped(offset + row)

                    override fun copyCursor(sink: TerminalRenderCursorSink) {
                        frame.copyCursor { column, row, visible, blinking, shape, generation ->
                            sink.onCursor(column, row - offset, visible && row - offset in 0 until rows, blinking, shape, generation)
                        }
                    }

                    override fun copyLine(
                        row: Int,
                        codeWords: IntArray,
                        codeOffset: Int,
                        attrWords: LongArray,
                        attrOffset: Int,
                        flags: IntArray,
                        flagOffset: Int,
                        extraAttrWords: LongArray?,
                        extraAttrOffset: Int,
                        hyperlinkIds: IntArray?,
                        hyperlinkOffset: Int,
                        clusterSink: TerminalRenderClusterSink?,
                        clusterDataSink: TerminalRenderClusterDataSink?,
                    ) {
                        checkCancelled()
                        frame.copyLine(
                            offset + row,
                            codeWords,
                            codeOffset,
                            attrWords,
                            attrOffset,
                            flags,
                            flagOffset,
                            extraAttrWords,
                            extraAttrOffset,
                            hyperlinkIds,
                            hyperlinkOffset,
                            clusterSink,
                            clusterDataSink,
                        )
                    }
                }
            cache.accept(slice)
        }
        return cache.hasFrame
    }
}
