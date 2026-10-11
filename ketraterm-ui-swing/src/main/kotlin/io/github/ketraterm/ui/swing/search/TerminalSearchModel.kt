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
    private var charRows = IntArray(INITIAL_TEXT_CAPACITY)
    private var charStartColumns = IntArray(INITIAL_TEXT_CAPACITY)
    private var charEndColumns = IntArray(INITIAL_TEXT_CAPACITY)
    private val highlights = TerminalSearchHighlights()
    private var rowIds = LongArray(INITIAL_TEXT_CAPACITY)
    private var rowGenerations = LongArray(INITIAL_TEXT_CAPACITY)
    private var firstAbsoluteRow = 0L
    private var currentRow = 0
    private var query = ""
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
    }

    fun discardPendingLine() {
        lineText.setLength(0)
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
                appendMappedCodePoint(codePoint, startColumn, endColumn)
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
        if (lineText.length < query.length) return

        var index = 0
        var untilCancellationCheck = 0
        val lastStart = lineText.length - query.length
        while (index <= lastStart) {
            if (untilCancellationCheck-- == 0) {
                checkCancelled()
                untilCancellationCheck = 255
            }
            if (matchesAt(index, query, ignoreCase)) {
                appendMatch(firstAbsoluteRow, index, index + query.length)
                index += maxOf(1, query.length)
            } else {
                index++
            }
        }
    }

    private fun matchesAt(
        startIndex: Int,
        query: String,
        ignoreCase: Boolean,
    ): Boolean {
        var index = 0
        while (index < query.length) {
            if (index > 0 && index and 255 == 0) checkCancelled()
            val actual = lineText[startIndex + index]
            val expected = query[index]
            if (actual != expected) {
                if (!ignoreCase || !actual.equals(expected, ignoreCase = true)) return false
            }
            index++
        }
        return true
    }

    private fun appendMatch(
        firstAbsoluteRow: Long,
        startChar: Int,
        endChar: Int,
    ) {
        highlights.beginResult()

        var currentRow = charRows[startChar]
        var startColumn = charStartColumns[startChar]
        var endColumn = startColumn

        var index = startChar
        while (index < endChar) {
            if (index and 255 == 0) checkCancelled()
            val row = charRows[index]
            if (row != currentRow) {
                highlights.addSegment(firstAbsoluteRow + currentRow, startColumn, endColumn, rowIds[currentRow], rowGenerations[currentRow])
                currentRow = row
                startColumn = charStartColumns[index]
            }
            endColumn = charEndColumns[index]
            index++
        }
        highlights.addSegment(firstAbsoluteRow + currentRow, startColumn, endColumn, rowIds[currentRow], rowGenerations[currentRow])
        highlights.finishResult()
    }

    private fun appendRow(
        cache: TerminalRenderCache,
        row: Int,
    ) {
        val end = textEndColumn(cache.columns, cache.codeWords, cache.flags, row, cache.lineWrapped[row])
        forEachLogicalTextCell(cache, row, checkCancelled, end) { codePoint, startColumn, endColumn ->
            appendMappedCodePoint(codePoint, startColumn, endColumn)
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
    ) {
        val charStart = lineText.length
        lineText.appendCodePoint(codePoint)
        val charEnd = lineText.length
        ensureCharCapacity(charEnd)

        var charIndex = charStart
        while (charIndex < charEnd) {
            charRows[charIndex] = currentRow
            charStartColumns[charIndex] = startColumn
            charEndColumns[charIndex] = endColumn
            charIndex++
        }
    }

    private fun trimTrailingSpaces() {
        var end = lineText.length
        while (end > 0 && lineText[end - 1] == ' ') {
            if (end and 255 == 0) checkCancelled()
            end--
        }
        lineText.setLength(end)
    }

    private fun ensureCharCapacity(required: Int) {
        if (required <= charRows.size) return
        val nextCapacity = nextCapacity(charRows.size, required)
        charRows = charRows.copyOf(nextCapacity)
        charStartColumns = charStartColumns.copyOf(nextCapacity)
        charEndColumns = charEndColumns.copyOf(nextCapacity)
    }

    private companion object {
        private const val INITIAL_TEXT_CAPACITY = 256

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
