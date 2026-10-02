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

import io.github.ketraterm.render.api.TerminalRenderBufferKind
import io.github.ketraterm.render.api.TerminalRenderFrameReader
import io.github.ketraterm.render.cache.TerminalRenderRangeCopy
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield

/** Worker-owned bounded copying, followed by logical text assembly outside the session lock. */
internal class TerminalHyperlinkSourceScan {
    private val copy = TerminalRenderRangeCopy()
    private val builder = TerminalHyperlinkLineSnapshotBuilder()

    /**
     * Revalidates a bounded detector batch against current source rows. Provider execution can
     * outlive a frame; unpublished edits must not install an action for obsolete text. An evicted
     * wrapped prefix is valid only while the retained suffix still has the same identity/text.
     */
    suspend fun validate(
        reader: TerminalRenderFrameReader,
        lines: List<TerminalHyperlinkLineSnapshot>,
        historyGeneration: Long,
    ): Validation? {
        val firstLine = lines.first()
        val context = currentCoroutineContext()
        var first = 0L
        var last = 0L
        var generation = 0L
        var valid = false
        reader.readRenderFrame { frame ->
            first = frame.discardedCount
            last = minOf(first + frame.historySize + frame.rows, frame.outputEndAbsoluteRow) - 1L
            generation = frame.contentGeneration
            valid = frame.activeBuffer == firstLine.activeBuffer &&
                frame.columns == firstLine.columns &&
                frame.historyContentGeneration == historyGeneration
        }
        if (!valid) return null
        val validLines = BooleanArray(lines.size)
        for (index in lines.indices) {
            val line = lines[index]
            var next = maxOf(first, line.firstAbsoluteRow)
            if (next > line.lastAbsoluteRow || line.lastAbsoluteRow > last) continue
            var sameText = true
            while (next <= line.lastAbsoluteRow) {
                context.ensureActive()
                if (!copy.read(reader, next, line.lastAbsoluteRow) { context.ensureActive() }) return null
                if (copy.firstAbsoluteRow != next ||
                    !matches(firstLine.activeBuffer, firstLine.columns, historyGeneration, generation)
                ) {
                    return null
                }
                for (row in 0 until copy.cache.rows) {
                    if (!line.matchesTextRow(copy.cache, row, next + row)) sameText = false
                }
                next += copy.cache.rows
                yield()
            }
            validLines[index] = sameText
        }
        return Validation(generation, validLines)
    }

    internal class Validation(
        val contentGeneration: Long,
        val validLines: BooleanArray,
    )

    /**
     * Reads newly admitted history and changed live rows in batches. A soft-wrapped line is never
     * split between results; copying remains bounded even when that line crosses many batches.
     */
    suspend fun scan(
        reader: TerminalRenderFrameReader,
        nextSourceRow: Long,
        expectedBuffer: TerminalRenderBufferKind,
        expectedColumns: Int,
        expectedHistoryGeneration: Long,
    ): Result? {
        val context = currentCoroutineContext()
        var first = 0L
        var liveTop = 0L
        var last = 0L
        var generation = 0L
        var valid = true
        reader.readRenderFrame { frame ->
            first = frame.discardedCount
            liveTop = first + frame.historySize
            last = minOf(liveTop + frame.rows, frame.outputEndAbsoluteRow) - 1L
            generation = frame.contentGeneration
            valid = frame.activeBuffer == expectedBuffer &&
                frame.columns == expectedColumns &&
                frame.historyContentGeneration == expectedHistoryGeneration
        }
        if (!valid) return null
        var next = if (nextSourceRow > last) liveTop else maxOf(nextSourceRow, first)
        // An admission/live-grid boundary can fall in the middle of a logical line.
        var probe = next - 1L
        while (probe >= first) {
            context.ensureActive()
            val count = minOf(copy.maxRows, maxOf(1, copy.maxCells / expectedColumns))
            val start = maxOf(first, probe - count + 1L)
            if (!copy.read(reader, start, probe) { context.ensureActive() }) return null
            if (!matches(expectedBuffer, expectedColumns, expectedHistoryGeneration, generation)) return null
            var row = copy.cache.rows - 1
            while (row >= 0 && copy.cache.lineWrapped[row]) {
                next = copy.firstAbsoluteRow + row
                row--
            }
            if (row >= 0 || next == first) break
            probe = next - 1L
            yield()
        }
        val lines = ArrayList<TerminalHyperlinkLineSnapshot>()
        val batchEnd = minOf(last, next + copy.maxRows - 1L)
        var assembling = false
        while (next <= last) {
            context.ensureActive()
            if (!copy.read(reader, next, last) { context.ensureActive() }) return null
            if (copy.firstAbsoluteRow != next ||
                !matches(expectedBuffer, expectedColumns, expectedHistoryGeneration, generation)
            ) {
                return null
            }
            for (row in 0 until copy.cache.rows) {
                val absolute = copy.firstAbsoluteRow + row
                if (!assembling) {
                    builder.begin(absolute, expectedColumns, expectedBuffer)
                    assembling = true
                }
                builder.append(copy.cache, row)
                next = absolute + 1L
                if (!copy.cache.lineWrapped[row] || absolute == last) {
                    lines.add(builder.finish())
                    assembling = false
                    if (absolute >= batchEnd) break
                }
            }
            if (!assembling && next > batchEnd) break
            yield()
        }
        reader.readRenderFrame { frame ->
            valid = frame.activeBuffer == expectedBuffer &&
                frame.columns == expectedColumns &&
                frame.historyContentGeneration == expectedHistoryGeneration &&
                frame.contentGeneration == generation
        }
        if (!valid) return null
        return Result(lines, first, next, liveTop, next > last)
    }

    private fun matches(
        buffer: TerminalRenderBufferKind,
        columns: Int,
        history: Long,
        content: Long,
    ): Boolean =
        copy.cache.activeBuffer == buffer &&
            copy.cache.columns == columns &&
            copy.cache.historyContentGeneration == history &&
            copy.cache.contentGeneration == content

    internal class Result(
        val lines: List<TerminalHyperlinkLineSnapshot>,
        val firstRetainedRow: Long,
        val nextSourceRow: Long,
        val liveTop: Long,
        val reachedEnd: Boolean,
    )
}
