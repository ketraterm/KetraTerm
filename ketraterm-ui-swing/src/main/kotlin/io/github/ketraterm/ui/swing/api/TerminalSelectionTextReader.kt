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

import io.github.ketraterm.render.api.*
import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.render.cache.TerminalTextRangeCopy

/** Bounded text copying under source leases; bidi and string construction run after each lease. */
internal class TerminalSelectionTextReader {
    private val copy = TerminalTextRangeCopy()
    private val frame = TextFrame(copy)
    private val cache = TerminalRenderCache(1, 1)
    private val extractor = TerminalSelectionTextExtractor()

    /** Returns no text if content/layout changes between batches, rather than mixing revisions. */
    fun read(
        reader: TerminalRenderFrameReader,
        anchorAbsoluteRow: Long,
        anchorColumn: Int,
        caretAbsoluteRow: Long,
        caretColumn: Int,
        isBlock: Boolean = false,
        expectedContentGeneration: Long? = null,
        validateLayout: (TerminalRenderFrame) -> Boolean = { true },
    ): String? {
        val forward = anchorAbsoluteRow < caretAbsoluteRow || anchorAbsoluteRow == caretAbsoluteRow && anchorColumn <= caretColumn
        val requestedStart = minOf(anchorAbsoluteRow, caretAbsoluteRow)
        val requestedEnd = maxOf(anchorAbsoluteRow, caretAbsoluteRow)
        var first = -1L
        var last = -1L
        var columns = 0
        var generation = 0L
        var historyGeneration = 0L
        var buffer = TerminalRenderBufferKind.PRIMARY
        reader.readRenderFrameForAbsoluteRange(requestedStart, requestedEnd) { source ->
            if (!validateLayout(source) ||
                expectedContentGeneration != null &&
                expectedContentGeneration != source.contentGeneration
            ) {
                return@readRenderFrameForAbsoluteRange
            }
            val top = source.discardedCount + source.historySize - source.scrollbackOffset
            val bottom = top + source.rows - 1L
            first = maxOf(requestedStart, top)
            last = minOf(requestedEnd, bottom)
            columns = source.columns
            generation = source.contentGeneration
            historyGeneration = source.historyContentGeneration
            buffer = source.activeBuffer
        }
        if (first !in 0..last) return null
        val startColumn =
            when {
                isBlock -> minOf(anchorColumn, caretColumn)
                requestedStart < first -> 0
                forward -> anchorColumn
                else -> caretColumn
            }.coerceIn(0, columns)
        val endColumn =
            when {
                isBlock -> maxOf(anchorColumn, caretColumn)
                requestedEnd > last -> columns
                forward -> caretColumn
                else -> anchorColumn
            }.coerceIn(0, columns)
        val result = StringBuilder()
        var next = first
        var previousWrapped = false
        while (next <= last) {
            if (!copy.read(reader, next, last) ||
                copy.firstAbsoluteRow != next ||
                copy.columns != columns ||
                copy.contentGeneration != generation ||
                copy.historyContentGeneration != historyGeneration ||
                copy.activeBuffer != buffer
            ) {
                return null
            }
            frame.advance()
            cache.accept(frame)
            if (next != first && (isBlock || !previousWrapped)) result.append('\n')
            extractor.appendSelectedText(
                result,
                cache,
                CellSelection(
                    if (isBlock || next == first) startColumn else 0,
                    0,
                    if (isBlock || copy.lastAbsoluteRow == last) endColumn else columns,
                    copy.rows - 1,
                    isBlock,
                ),
                joinSoftWrappedRows = !isBlock,
            )
            previousWrapped = copy.lineWrapped[copy.rows - 1]
            if (copy.lastAbsoluteRow == last) break
            next = copy.lastAbsoluteRow + 1L
        }
        return result.toString()
    }

    /** Adapts owned, bounded text planes to the existing wide-cell/bidi selection machinery. */
    private class TextFrame(
        private val text: TerminalTextRangeCopy,
    ) : TerminalRenderFrame {
        override val columns: Int get() = text.columns
        override val rows: Int get() = text.rows
        override val discardedCount: Long get() = text.firstAbsoluteRow
        override val frameGeneration: Long get() = generation
        override val contentGeneration: Long get() = text.contentGeneration
        override val historyContentGeneration: Long get() = text.historyContentGeneration
        override val structureGeneration: Long get() = generation
        override val activeBuffer: TerminalRenderBufferKind get() = text.activeBuffer
        override val cursor =
            TerminalRenderCursor(
                column = 0,
                row = 0,
                visible = false,
                blinking = false,
                shape = TerminalRenderCursorShape.BLOCK,
                generation = 0,
            )
        private var generation = 0L

        fun advance() {
            generation++
        }

        override fun lineId(row: Int): Long = text.lineIds[row]

        override fun lineGeneration(row: Int): Long = text.lineGenerations[row]

        override fun lineWrapped(row: Int): Boolean = text.lineWrapped[row]

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
            val offset = row * columns
            text.codeWords.copyInto(codeWords, codeOffset, offset, offset + columns)
            text.flags.copyInto(flags, flagOffset, offset, offset + columns)
            attrWords.fill(TerminalRenderAttrs.DEFAULT, attrOffset, attrOffset + columns)
            extraAttrWords?.fill(0L, extraAttrOffset, extraAttrOffset + columns)
            hyperlinkIds?.fill(0, hyperlinkOffset, hyperlinkOffset + columns)
            for (column in 0 until columns) {
                if (text.flags[offset + column] and TerminalRenderCellFlags.CLUSTER == 0) continue
                val ref = text.clusterRefs[offset + column]
                if (ref == 0L) continue
                val start = (ref ushr 32).toInt()
                val length = ref.toInt()
                clusterDataSink?.onCluster(column, text.clusterCodepoints, start, length)
                clusterSink?.onCluster(column, String(text.clusterCodepoints, start, length))
            }
        }
    }
}
