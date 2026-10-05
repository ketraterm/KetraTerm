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

/**
 * EDT-owned projection of a frozen grid into a bottom-anchored presentation.
 *
 * Width and row identities stay unchanged. The presentation history includes grid
 * rows hidden above a shorter view. Absolute row coordinates stay in the source namespace.
 * Only completed session closure permits these separate metadata and content reads.
 */
internal class RetainedFrameViewport(
    private val reader: TerminalRenderFrameReader,
) : TerminalRenderFrameReader,
    TerminalRenderFrame,
    TerminalRenderCursorSink {
    var visibleRows: Int = 1
    private var gridRows = 1
    private var sourceHistory = 0
    private var skippedRows = 0
    private var requestedRows = 1
    private var source: TerminalRenderFrame? = null
    private var consumer: TerminalRenderFrameConsumer? = null
    private var cursorSink: TerminalRenderCursorSink? = null
    private val frame get() = checkNotNull(source)
    private val metadataConsumer =
        TerminalRenderFrameConsumer {
            gridRows = it.rows
            sourceHistory = it.historySize
        }
    private val contentConsumer =
        TerminalRenderFrameConsumer {
            source = it
            try {
                checkNotNull(consumer).accept(this)
            } finally {
                source = null
            }
        }

    override var historySize: Int = 0
        private set
    override var scrollbackOffset: Int = 0
        private set

    override fun readRenderFrame(consumer: TerminalRenderFrameConsumer) = readRenderFrame(0, visibleRows, consumer)

    override fun readRenderFrame(
        scrollbackOffset: Int,
        consumer: TerminalRenderFrameConsumer,
    ) = readRenderFrame(scrollbackOffset, visibleRows, consumer)

    override fun readRenderFrame(
        scrollbackOffset: Int,
        viewportRows: Int,
        consumer: TerminalRenderFrameConsumer,
    ) {
        check(this.consumer == null) { "Retained viewport reads must not be nested" }
        require(visibleRows > 0 && viewportRows > 0)
        reader.readRenderFrame(metadataConsumer)
        historySize = (sourceHistory.toLong() + gridRows - visibleRows).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        this.scrollbackOffset = scrollbackOffset.coerceIn(0, historySize)
        val firstRow = historySize - this.scrollbackOffset
        val sourceOffset = (sourceHistory - firstRow).coerceAtLeast(0)
        skippedRows = (firstRow - sourceHistory).coerceAtLeast(0)
        requestedRows = viewportRows
        this.consumer = consumer
        try {
            val sourceRows = (viewportRows.toLong() + skippedRows).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            reader.readRenderFrame(sourceOffset, sourceRows, contentConsumer)
        } finally {
            this.consumer = null
        }
    }

    override val columns get() = frame.columns
    override val rows get() = minOf(requestedRows, frame.rows - skippedRows)
    override val discardedCount get() = frame.discardedCount
    override val frameGeneration get() = frame.frameGeneration
    override val contentGeneration get() = frame.contentGeneration
    override val historyContentGeneration get() = frame.historyContentGeneration
    override val outputEndAbsoluteRow get() = frame.outputEndAbsoluteRow
    override val structureGeneration get() = frame.structureGeneration
    override val activeBuffer get() = frame.activeBuffer
    override val palette get() = frame.palette
    override val cursor: TerminalRenderCursor
        get() {
            val cursor = frame.cursor
            val row = cursor.row - skippedRows
            return cursor.copy(row = row, visible = cursor.visible && row in 0 until rows)
        }

    override fun lineGeneration(row: Int): Long = frame.lineGeneration(sourceRow(row))

    override fun lineId(row: Int): Long = frame.lineId(sourceRow(row))

    override fun lineWrapped(row: Int): Boolean = frame.lineWrapped(sourceRow(row))

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
        frame.copyLine(
            sourceRow(row),
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

    override fun copyCursor(sink: TerminalRenderCursorSink) {
        cursorSink = sink
        try {
            frame.copyCursor(this)
        } finally {
            cursorSink = null
        }
    }

    override fun onCursor(
        column: Int,
        row: Int,
        visible: Boolean,
        blinking: Boolean,
        shape: TerminalRenderCursorShape,
        generation: Long,
    ) {
        val projectedRow = row - skippedRows
        checkNotNull(cursorSink).onCursor(
            column,
            projectedRow,
            visible && projectedRow in 0 until rows,
            blinking,
            shape,
            generation,
        )
    }

    private fun sourceRow(row: Int): Int {
        require(row in 0 until rows) { "row outside retained viewport: $row" }
        return row + skippedRows
    }
}
