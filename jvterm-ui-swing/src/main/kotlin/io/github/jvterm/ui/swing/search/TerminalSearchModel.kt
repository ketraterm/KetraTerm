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
package io.github.jvterm.ui.swing.search

import io.github.jvterm.render.api.TerminalRenderCellFlags
import io.github.jvterm.render.cache.TerminalRenderCache

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

    fun search(
        cache: TerminalRenderCache,
        query: String,
        ignoreCase: Boolean,
    ): TerminalSearchHighlights {
        highlights.clear()
        if (query.isEmpty()) return highlights

        var row = 0
        while (row < cache.rows) {
            lineText.setLength(0)
            do {
                appendRow(cache, row)
                row++
            } while (row < cache.rows && cache.lineWrapped[row - 1])

            trimTrailingSpaces()
            scanLine(
                cache = cache,
                query = query,
                ignoreCase = ignoreCase,
            )
        }

        highlights.activate(if (highlights.resultCount == 0) -1 else 0)
        return highlights
    }

    private fun scanLine(
        cache: TerminalRenderCache,
        query: String,
        ignoreCase: Boolean,
    ) {
        if (lineText.length < query.length) return
        val firstAbsoluteRow = cache.discardedCount + cache.historySize - cache.scrollbackOffset

        var index = 0
        val lastStart = lineText.length - query.length
        while (index <= lastStart) {
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
            val row = charRows[index]
            if (row != currentRow) {
                highlights.addSegment(firstAbsoluteRow + currentRow, startColumn, endColumn)
                currentRow = row
                startColumn = charStartColumns[index]
            }
            endColumn = charEndColumns[index]
            index++
        }
        highlights.addSegment(firstAbsoluteRow + currentRow, startColumn, endColumn)
        highlights.finishResult()
    }

    private fun appendRow(
        cache: TerminalRenderCache,
        row: Int,
    ) {
        var column = 0
        while (column < cache.columns) {
            column = appendCell(cache, row, column)
        }
    }

    private fun appendCell(
        cache: TerminalRenderCache,
        row: Int,
        column: Int,
    ): Int {
        val index = cache.rowOffset(row) + column
        val flags = cache.flags[index]
        if (flags and TerminalRenderCellFlags.WIDE_TRAILING != 0) {
            return column + 1
        }

        val columnSpan = if (flags and TerminalRenderCellFlags.WIDE_LEADING != 0) 2 else 1
        when {
            flags and TerminalRenderCellFlags.CLUSTER != 0 -> {
                val ref = cache.clusterRefs[index]
                if (ref == NO_CLUSTER_REF) {
                    appendMappedCodePoint(' '.code, row, column, column + columnSpan)
                } else {
                    val offset = cache.clusterOffset(ref)
                    val length = cache.clusterLength(ref)
                    var clusterIndex = offset
                    val end = offset + length
                    while (clusterIndex < end) {
                        appendMappedCodePoint(cache.clusterCodepoints[clusterIndex], row, column, column + columnSpan)
                        clusterIndex++
                    }
                }
            }

            flags and TerminalRenderCellFlags.CODEPOINT != 0 -> {
                appendMappedCodePoint(cache.codeWords[index], row, column, column + columnSpan)
            }

            else -> appendMappedCodePoint(' '.code, row, column, column + columnSpan)
        }

        return column + columnSpan
    }

    private fun appendMappedCodePoint(
        codePoint: Int,
        row: Int,
        startColumn: Int,
        endColumn: Int,
    ) {
        val charStart = lineText.length
        lineText.appendCodePoint(codePoint)
        val charEnd = lineText.length
        ensureCharCapacity(charEnd)

        var charIndex = charStart
        while (charIndex < charEnd) {
            charRows[charIndex] = row
            charStartColumns[charIndex] = startColumn
            charEndColumns[charIndex] = endColumn
            charIndex++
        }
    }

    private fun trimTrailingSpaces() {
        var end = lineText.length
        while (end > 0 && lineText[end - 1] == ' ') {
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
        private const val NO_CLUSTER_REF = 0L

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
