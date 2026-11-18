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

import io.github.ketraterm.render.api.TerminalRenderCellFlags
import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.ui.swing.render.TerminalBidiLayout

/**
 * Extracts selected visible text from primitive render-cache rows in logical text order.
 * Linear selections preserve spaces inside logical lines and selected hard line breaks.
 * Empty row padding and spaces at hard line ends are omitted.
 * Rectangular selections project their visual interval independently on each row.
 */
internal class TerminalSelectionTextExtractor {
    private val bidiLayout by lazy(LazyThreadSafetyMode.NONE) { TerminalBidiLayout() }

    fun selectedText(
        cache: TerminalRenderCache,
        selection: CellSelection,
        joinSoftWrappedRows: Boolean = false,
    ): String {
        if (selection.isEmpty) return ""
        val result = StringBuilder()
        appendSelectedText(result, cache, selection, joinSoftWrappedRows)
        return result.toString()
    }

    /** Appends a bounded batch without allocating an intermediate string. */
    fun appendSelectedText(
        result: StringBuilder,
        cache: TerminalRenderCache,
        selection: CellSelection,
        joinSoftWrappedRows: Boolean,
    ) {
        if (selection.isEmpty) return

        val layout = if (selection.isBlock) bidiLayout.also { it.reset() } else null
        var row = selection.startRow.coerceAtLeast(0)
        val lastRow = selection.endRow.coerceAtMost(cache.rows - 1)
        var hasPreviousSelectedRow = false
        while (row <= lastRow) {
            val bidi = layout?.row(cache, row)
            val range = selection.packedColumnRange(row, cache, bidi)
            // Linear selections can include a row boundary without selecting a cell on one side.
            if (!selection.isBlock || range != CellSelection.NO_RANGE) {
                if (
                    hasPreviousSelectedRow &&
                    (!joinSoftWrappedRows || selection.isBlock || row == 0 || !cache.lineWrapped[row - 1])
                ) {
                    result.append('\n')
                }
                if (range != CellSelection.NO_RANGE) {
                    val endColumn = CellSelection.rangeEnd(range)
                    appendRow(
                        destination = result,
                        cache = cache,
                        row = row,
                        startColumn = CellSelection.rangeStart(range),
                        endColumn = if (selection.isBlock) endColumn else minOf(endColumn, linearTextEndColumn(cache, row)),
                        bidi = bidi,
                        trimTrailingSpaces = selection.isBlock,
                    )
                }
                hasPreviousSelectedRow = true
            }
            row++
        }
    }

    fun wordSelectionAt(
        cache: TerminalRenderCache,
        row: Int,
        column: Int,
    ): CellSelection? {
        if (row !in 0 until cache.rows || column !in 0 until cache.columns) return null

        val offset = cache.rowOffset(row)
        val clickedColumn = leadingColumn(cache, offset, column)
        var hasPathIndicator = false
        val path =
            expandSelection(
                cache,
                row,
                clickedColumn,
                matches = { flags, codeWord ->
                    val matches = isPathChar(flags, codeWord)
                    if (matches &&
                        flags and TerminalRenderCellFlags.CODEPOINT != 0 &&
                        (codeWord == '/'.code || codeWord == '\\'.code || codeWord == '.'.code || codeWord == ':'.code)
                    ) {
                        hasPathIndicator = true
                    }
                    matches
                },
                accept = { hasPathIndicator },
            )
        if (path != null) return path

        val clickedKind = wordKind(cache.flags[offset + clickedColumn], cache.codeWords[offset + clickedColumn])
        return expandSelection(cache, row, clickedColumn, matches = { flags, codeWord -> wordKind(flags, codeWord) == clickedKind })
    }

    private inline fun expandSelection(
        cache: TerminalRenderCache,
        row: Int,
        column: Int,
        matches: (flags: Int, codeWord: Int) -> Boolean,
        accept: () -> Boolean = { true },
    ): CellSelection? {
        if (!matchesCell(cache, row, column, matches)) return null

        var startRow = row
        var start = column
        while (true) {
            while (start > 0 && matchesCell(cache, startRow, start - 1, matches)) start--
            if (start != 0 || startRow == 0 || !cache.lineWrapped[startRow - 1]) break
            val previous = wrappedTextEndColumn(cache, startRow - 1) - 1
            if (previous < 0 || !matchesCell(cache, startRow - 1, previous, matches)) break
            startRow--
            start = previous
        }

        var endRow = row
        var end = column + 1
        while (true) {
            val wrapped = cache.lineWrapped[endRow]
            val textEnd = if (wrapped) wrappedTextEndColumn(cache, endRow) else cache.columns
            while (end < textEnd && matchesCell(cache, endRow, end, matches)) end++
            if (end != textEnd || !wrapped || endRow == cache.rows - 1 || !matchesCell(cache, endRow + 1, 0, matches)) break
            endRow++
            end = 1
        }

        return if (accept()) CellSelection(start, startRow, end, endRow) else null
    }

    private inline fun matchesCell(
        cache: TerminalRenderCache,
        row: Int,
        column: Int,
        matches: (flags: Int, codeWord: Int) -> Boolean,
    ): Boolean {
        val offset = cache.rowOffset(row)
        val index = offset + leadingColumn(cache, offset, column)
        return matches(cache.flags[index], cache.codeWords[index])
    }

    private fun leadingColumn(
        cache: TerminalRenderCache,
        offset: Int,
        column: Int,
    ): Int = if (column > 0 && cache.flags[offset + column] and TerminalRenderCellFlags.WIDE_TRAILING != 0) column - 1 else column

    private fun wrappedTextEndColumn(
        cache: TerminalRenderCache,
        row: Int,
    ): Int {
        val columns = cache.columns
        if (columns == 0) return 0
        return if (cache.flags[cache.rowOffset(row) + columns - 1] and TerminalRenderCellFlags.WRAP_PADDING != 0) columns - 1 else columns
    }

    /** Bounds trimming by the whole row, so a partial selection retains interior spaces. */
    private fun linearTextEndColumn(
        cache: TerminalRenderCache,
        row: Int,
    ): Int {
        if (cache.lineWrapped[row]) return wrappedTextEndColumn(cache, row)
        val rowOffset = cache.rowOffset(row)
        var end = cache.columns
        while (end > 0) {
            val index = rowOffset + end - 1
            val flags = cache.flags[index]
            val empty = flags and TerminalRenderCellFlags.EMPTY != 0
            val trailingSpace =
                flags and TerminalRenderCellFlags.CODEPOINT != 0 && cache.codeWords[index] == ' '.code
            if (!empty && !trailingSpace) break
            end--
        }
        return end
    }

    private fun appendRow(
        destination: StringBuilder,
        cache: TerminalRenderCache,
        row: Int,
        startColumn: Int,
        endColumn: Int,
        bidi: TerminalBidiLayout.Row?,
        trimTrailingSpaces: Boolean,
    ) {
        val rowStart = destination.length
        // A visual interval may select disjoint logical spans in mixed-direction text.
        // Visit logical cells in order so copying preserves text and cluster ordering.
        var column = if (bidi == null) startColumn else 0
        val limit = if (bidi == null) endColumn else cache.columns
        while (column < limit) {
            val visualColumn = bidi?.visualColumn(column) ?: column
            column =
                if (visualColumn >= startColumn && visualColumn < endColumn) {
                    appendCell(destination, cache, row, column)
                } else {
                    column + 1
                }
        }

        if (trimTrailingSpaces) {
            var trimmedEnd = destination.length
            while (trimmedEnd > rowStart && destination[trimmedEnd - 1] == ' ') {
                trimmedEnd--
            }
            destination.setLength(trimmedEnd)
        }
    }

    private fun appendCell(
        destination: StringBuilder,
        cache: TerminalRenderCache,
        row: Int,
        column: Int,
    ): Int {
        val index = cache.rowOffset(row) + column
        val flags = cache.flags[index]
        if (flags and TerminalRenderCellFlags.WIDE_TRAILING != 0) {
            return column + 1
        }

        when {
            flags and TerminalRenderCellFlags.CLUSTER != 0 -> {
                val ref = cache.clusterRefs[index]
                if (ref != 0L) {
                    val offset = cache.clusterOffset(ref)
                    val length = cache.clusterLength(ref)
                    destination.appendCodePoints(cache.clusterCodepoints, offset, length)
                }
            }

            flags and TerminalRenderCellFlags.CODEPOINT != 0 -> {
                destination.appendCodePoint(cache.codeWords[index])
            }

            else -> destination.append(' ')
        }

        return column + if (flags and TerminalRenderCellFlags.WIDE_LEADING != 0) 2 else 1
    }

    private fun StringBuilder.appendCodePoints(
        codepoints: IntArray,
        offset: Int,
        length: Int,
    ) {
        var index = offset
        val end = offset + length
        while (index < end) {
            appendCodePoint(codepoints[index])
            index++
        }
    }

    private fun isPathChar(
        flags: Int,
        codeWord: Int,
    ): Boolean {
        if (flags and (TerminalRenderCellFlags.CODEPOINT or TerminalRenderCellFlags.CLUSTER) == 0) return false
        if (flags and TerminalRenderCellFlags.CLUSTER != 0) return true
        if (Character.isLetterOrDigit(codeWord)) return true
        return when (codeWord) {
            '_'.code, '-'.code, '/'.code, '\\'.code, '.'.code, ':'.code, '~'.code,
            '?'.code, '&'.code, '='.code, '%'.code, '+'.code, '@'.code, '$'.code, '*'.code,
            -> true
            else -> false
        }
    }

    private fun wordKind(
        flags: Int,
        codeWord: Int,
    ): Int {
        if (flags and (TerminalRenderCellFlags.CODEPOINT or TerminalRenderCellFlags.CLUSTER) == 0) return WORD_KIND_SPACE
        if (flags and TerminalRenderCellFlags.CLUSTER != 0) return WORD_KIND_WORD
        if (Character.isLetterOrDigit(codeWord) || codeWord == '_'.code) {
            return WORD_KIND_WORD
        }
        if (Character.isWhitespace(codeWord)) return WORD_KIND_SPACE
        return WORD_KIND_PUNCTUATION_BASE + codeWord
    }

    private companion object {
        private const val WORD_KIND_SPACE = 0
        private const val WORD_KIND_WORD = 1
        private const val WORD_KIND_PUNCTUATION_BASE = 2
    }
}
