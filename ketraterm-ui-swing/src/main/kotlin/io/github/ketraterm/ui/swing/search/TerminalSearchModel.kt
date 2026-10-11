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
package io.github.ketraterm.ui.swing.search

import io.github.ketraterm.render.api.TerminalRenderCellFlags
import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.render.cache.TerminalTextRangeCopy
import io.github.ketraterm.ui.swing.render.forEachLogicalTextCell

/**
 * Scans primitive render-cache rows for literal text matches.
 *
 * Search is line-oriented over terminal logical lines: soft-wrapped visual rows
 * are joined before matching, and final trailing grid spaces are ignored. The
 * model allocates only during search updates, never from the frame paint loop.
 */
internal class TerminalSearchModel {
    private val lineText = StringBuilder(INITIAL_TEXT_CAPACITY)

    // Each run stores UTF-16 start/end, row, column start and column end.
    // LINEAR_COLUMNS means consecutive UTF-16 units occupy consecutive cells;
    // otherwise every unit in the run maps to the same complete cell span.
    private var runs = IntArray(INITIAL_TEXT_CAPACITY)
    private var runsSize = 0
    private var matchRun = 0
    private val highlights = TerminalSearchHighlights()
    private var rowIds = LongArray(INITIAL_TEXT_CAPACITY)
    private var rowGenerations = LongArray(INITIAL_TEXT_CAPACITY)
    private var firstAbsoluteRow = 0L
    private var currentRow = 0
    private var query = ""
    private var queryPrefixes: IntArray? = null
    private var ignoreCase = true
    private var checkCancelled: () -> Unit = {}

    fun search(
        cache: TerminalRenderCache,
        query: String,
        ignoreCase: Boolean,
    ): TerminalSearchHighlights {
        begin(query, ignoreCase, cache.discardedCount + cache.historySize - cache.scrollbackOffset)
        if (query.isEmpty()) return highlights
        append(cache, firstAbsoluteRow)
        return finish()
    }

    fun begin(
        query: String,
        ignoreCase: Boolean,
        firstAbsoluteRow: Long,
        checkCancelled: () -> Unit = {},
        validateRows: Boolean = false,
    ) {
        this.query = query
        this.ignoreCase = ignoreCase
        this.firstAbsoluteRow = firstAbsoluteRow
        this.checkCancelled = checkCancelled
        highlights.clear()
        highlights.validatesRows = validateRows
        discardPendingLine()
        prepareQuery()
    }

    fun discardPendingLine() {
        lineText.setLength(0)
        runsSize = 0
        matchRun = 0
    }

    fun append(
        cache: TerminalRenderCache,
        firstRow: Long,
    ) {
        appendRows(cache.rows, firstRow, cache.lineIds, cache.lineGenerations, cache.lineWrapped) { row -> appendRow(cache, row) }
    }

    fun append(copy: TerminalTextRangeCopy) {
        appendRows(copy.rows, copy.firstAbsoluteRow, copy.lineIds, copy.lineGenerations, copy.lineWrapped) { row ->
            forEachLogicalTextCell(
                copy.columns,
                copy.codeWords,
                copy.flags,
                copy.clusterRefs,
                copy.clusterCodepoints,
                row,
                checkCancelled,
                textEndColumn(copy.columns, copy.codeWords, copy.flags, row, copy.lineWrapped[row]),
            ) { codePoint, startColumn, endColumn ->
                appendMappedCodePoint(codePoint, startColumn, endColumn, copy.flags[row * copy.columns + startColumn])
            }
        }
    }

    private inline fun appendRows(
        rows: Int,
        firstRow: Long,
        ids: LongArray,
        generations: LongArray,
        wrapped: BooleanArray,
        appendRow: (Int) -> Unit,
    ) {
        for (row in 0 until rows) {
            checkCancelled()
            currentRow = (firstRow + row - firstAbsoluteRow).toInt()
            if (currentRow >= rowIds.size) {
                val capacity = nextCapacity(rowIds.size, currentRow + 1)
                rowIds = rowIds.copyOf(capacity)
                rowGenerations = rowGenerations.copyOf(capacity)
            }
            rowIds[currentRow] = ids[row]
            rowGenerations[currentRow] = generations[row]
            appendRow(row)
            if (!wrapped[row]) finishLine()
        }
    }

    fun finish(): TerminalSearchHighlights {
        finishLine()
        highlights.activate(if (highlights.resultCount == 0) -1 else 0)
        return highlights
    }

    private fun finishLine() {
        trimTrailingSpaces()
        scanLine(query, ignoreCase)
        discardPendingLine()
    }

    private fun scanLine(
        query: String,
        ignoreCase: Boolean,
    ) {
        if (query.isEmpty() || lineText.length < query.length) return
        val prefixes = queryPrefixes
        if (prefixes == null) {
            scanShortLine(query, ignoreCase)
            return
        }

        var index = 0
        var matched = 0
        var untilCancellationCheck = 0
        while (index < lineText.length) {
            if (untilCancellationCheck-- == 0) {
                checkCancelled()
                untilCancellationCheck = 255
            }
            if (lineText[index].equals(query[matched], ignoreCase)) {
                index++
                matched++
                if (matched == query.length) {
                    appendMatch(firstAbsoluteRow, index - matched, index)
                    // Search results are non-overlapping, even when the query has a repeated prefix.
                    matched = 0
                }
            } else if (matched > 0) {
                matched = prefixes[matched - 1]
            } else {
                index++
            }
        }
    }

    private fun prepareQuery() {
        checkCancelled()
        // A bounded direct comparison is cheaper for short queries and needs no table.
        queryPrefixes = null
        if (query.length <= SHORT_QUERY_LIMIT) return
        val prefixes = IntArray(query.length)
        var index = 1
        var matched = 0
        var untilCancellationCheck = 0
        while (index < query.length) {
            if (untilCancellationCheck-- == 0) {
                checkCancelled()
                untilCancellationCheck = 255
            }
            if (query[index].equals(query[matched], ignoreCase)) {
                matched++
                prefixes[index++] = matched
            } else if (matched > 0) {
                matched = prefixes[matched - 1]
            } else {
                index++
            }
        }
        queryPrefixes = prefixes
    }

    private fun scanShortLine(
        query: String,
        ignoreCase: Boolean,
    ) {
        var index = 0
        var untilCancellationCheck = 0
        val lastStart = lineText.length - query.length
        while (index <= lastStart) {
            if (untilCancellationCheck-- == 0) {
                checkCancelled()
                untilCancellationCheck = 255
            }
            var matched = 0
            while (matched < query.length && lineText[index + matched].equals(query[matched], ignoreCase)) {
                matched++
            }
            if (matched == query.length) {
                appendMatch(firstAbsoluteRow, index, index + query.length)
                index += query.length
            } else {
                index++
            }
        }
    }

    private fun appendMatch(
        firstAbsoluteRow: Long,
        startChar: Int,
        endChar: Int,
    ) {
        highlights.beginResult()

        var run = matchRun
        var untilCancellationCheck = 0
        while (runs[run + 1] <= startChar) {
            if (untilCancellationCheck-- == 0) {
                checkCancelled()
                untilCancellationCheck = 255
            }
            run += RUN_SIZE
        }
        var segmentRow = runs[run + 2]
        var startColumn = runColumn(run, startChar, false)
        var endColumn = startColumn
        while (true) {
            if (untilCancellationCheck-- == 0) {
                checkCancelled()
                untilCancellationCheck = 255
            }
            val row = runs[run + 2]
            if (row != segmentRow) {
                highlights.addSegment(firstAbsoluteRow + segmentRow, startColumn, endColumn, rowIds[segmentRow], rowGenerations[segmentRow])
                segmentRow = row
                startColumn = runs[run + 3]
            }
            endColumn = runColumn(run, minOf(endChar, runs[run + 1]), true)
            if (runs[run + 1] >= endChar) break
            run += RUN_SIZE
        }
        // Subsequent non-overlapping matches never need to revisit earlier runs.
        matchRun = run
        highlights.addSegment(firstAbsoluteRow + segmentRow, startColumn, endColumn, rowIds[segmentRow], rowGenerations[segmentRow])
        highlights.finishResult()
    }

    private fun runColumn(
        run: Int,
        charIndex: Int,
        end: Boolean,
    ): Int =
        if (runs[run + 4] == LINEAR_COLUMNS) {
            runs[run + 3] + charIndex - runs[run]
        } else {
            runs[run + if (end) 4 else 3]
        }

    private fun appendRow(
        cache: TerminalRenderCache,
        row: Int,
    ) {
        val end = textEndColumn(cache.columns, cache.codeWords, cache.flags, row, cache.lineWrapped[row])
        forEachLogicalTextCell(cache, row, checkCancelled, end) { codePoint, startColumn, endColumn ->
            appendMappedCodePoint(codePoint, startColumn, endColumn, cache.flags[row * cache.columns + startColumn])
        }
    }

    private fun textEndColumn(
        columns: Int,
        codeWords: IntArray,
        flags: IntArray,
        row: Int,
        wrapped: Boolean,
    ): Int {
        if (wrapped) return columns
        val offset = row * columns
        var end = columns
        while (end > 0) {
            if ((columns - end) and 255 == 255) checkCancelled()
            val index = offset + end - 1
            val cellFlags = flags[index]
            // Leave multi-cell and cluster semantics to the logical-text decoder.
            if (cellFlags and (
                    TerminalRenderCellFlags.CLUSTER or TerminalRenderCellFlags.WIDE_LEADING or
                        TerminalRenderCellFlags.WIDE_TRAILING or TerminalRenderCellFlags.WRAP_PADDING
                ) != 0
            ) {
                break
            }
            if (cellFlags and TerminalRenderCellFlags.CODEPOINT != 0 && codeWords[index] != ' '.code) break
            end--
        }
        return end
    }

    private fun appendMappedCodePoint(
        codePoint: Int,
        startColumn: Int,
        endColumn: Int,
        flags: Int,
    ) {
        val charStart = lineText.length
        lineText.appendCodePoint(codePoint)
        val charEnd = lineText.length
        val linear = charEnd - charStart == 1 && endColumn - startColumn == 1 && flags and TerminalRenderCellFlags.CLUSTER == 0
        val previous = runsSize - RUN_SIZE
        if (previous >= 0 && runs[previous + 2] == currentRow) {
            val previousEnd = runs[previous + 4]
            if (linear && previousEnd == LINEAR_COLUMNS && runs[previous + 3] + charStart - runs[previous] == startColumn ||
                !linear && previousEnd == endColumn && runs[previous + 3] == startColumn
            ) {
                runs[previous + 1] = charEnd
                return
            }
        }
        if (runsSize + RUN_SIZE > runs.size) runs = runs.copyOf(nextCapacity(runs.size, runsSize + RUN_SIZE))
        runs[runsSize] = charStart
        runs[runsSize + 1] = charEnd
        runs[runsSize + 2] = currentRow
        runs[runsSize + 3] = startColumn
        runs[runsSize + 4] = if (linear) LINEAR_COLUMNS else endColumn
        runsSize += RUN_SIZE
    }

    private fun trimTrailingSpaces() {
        var end = lineText.length
        while (end > 0 && lineText[end - 1] == ' ') {
            if (end and 255 == 0) checkCancelled()
            end--
        }
        lineText.setLength(end)
    }

    private companion object {
        private const val RUN_SIZE = 5
        private const val LINEAR_COLUMNS = -1
        private const val INITIAL_TEXT_CAPACITY = 256
        private const val SHORT_QUERY_LIMIT = 8

        private fun nextCapacity(
            current: Int,
            required: Int,
        ): Int {
            var next = current
            while (next < required) {
                next *= 2
            }
            return next
        }
    }
}
