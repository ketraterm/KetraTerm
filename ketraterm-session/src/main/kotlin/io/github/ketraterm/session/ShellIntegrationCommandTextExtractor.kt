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
package io.github.ketraterm.session

import io.github.ketraterm.render.api.TerminalRenderCellFlags
import io.github.ketraterm.render.api.TerminalRenderClusterDataSink
import io.github.ketraterm.render.api.TerminalRenderFrame
import java.util.*

internal const val DEFAULT_SHELL_INTEGRATION_COMMAND_TEXT_LENGTH = 4096
internal const val MAX_SHELL_INTEGRATION_COMMAND_ROWS = 256

/**
 * Reconstructs command text between OSC 133 prompt-end and command-start markers.
 *
 * The extractor consumes stable render-line identities rather than retaining
 * core storage. Soft-wrapped rows are joined directly, while hard row
 * boundaries become newlines. Its primitive scratch storage is reused between
 * marker events; the returned [String] is the only steady-state allocation.
 */
internal class ShellIntegrationCommandTextExtractor(
    private val maxTextLength: Int = DEFAULT_SHELL_INTEGRATION_COMMAND_TEXT_LENGTH,
    private val maxRows: Int = MAX_SHELL_INTEGRATION_COMMAND_ROWS,
) {
    init {
        require(maxTextLength >= 0) { "maxTextLength must be >= 0, was $maxTextLength" }
        require(maxRows > 0) { "maxRows must be > 0, was $maxRows" }
    }

    private var codeWords = IntArray(0)
    private var attrWords = LongArray(0)
    private var flags = IntArray(0)
    private var clusterOffsets = IntArray(0)
    private var clusterLengths = IntArray(0)
    private var clusterCodePoints = IntArray(0)
    private var clusterCodePointCount = 0
    private var validClusterData = true
    private val builder = StringBuilder()
    private val clusterDataSink =
        TerminalRenderClusterDataSink { column, source, offset, length ->
            if (column !in clusterOffsets.indices || offset < 0 || length <= 0 || offset > source.size - length) {
                validClusterData = false
            } else {
                ensureClusterCapacity(clusterCodePointCount + length)
                source.copyInto(
                    destination = clusterCodePoints,
                    destinationOffset = clusterCodePointCount,
                    startIndex = offset,
                    endIndex = offset + length,
                )
                clusterOffsets[column] = clusterCodePointCount
                clusterLengths[column] = length
                clusterCodePointCount += length
            }
        }

    /**
     * Extracts one bounded command from [frame].
     *
     * @return reconstructed command text, or `null` when its marker range is
     * absent, reversed, too large, or contains incomplete render data.
     */
    fun extract(
        frame: TerminalRenderFrame,
        promptEndLineId: Long,
        promptEndColumn: Int,
        cursorRow: Int,
        cursorColumn: Int,
    ): String? {
        if (promptEndLineId == NO_LINE_ID || cursorRow !in 0 until frame.rows) return null

        val startRow = findLine(frame, promptEndLineId, cursorRow)
        if (startRow < 0) return null

        val cursorAtNextLineStart = cursorColumn == 0 && cursorRow > startRow
        val endRow = if (cursorAtNextLineStart) cursorRow - 1 else cursorRow
        if (endRow < startRow || endRow - startRow + 1 > maxRows) return null

        val startColumn = promptEndColumn.coerceIn(0, frame.columns)
        builder.setLength(0)
        var row = startRow
        while (row <= endRow) {
            copyLine(frame, row)
            if (!validClusterData) return null

            val fromColumn = if (row == startRow) startColumn else 0
            val toColumn =
                when {
                    row < endRow -> lastTextColumnExclusive(frame.columns)
                    cursorAtNextLineStart -> lastTextColumnExclusive(frame.columns)
                    else -> cursorColumn.coerceIn(0, frame.columns)
                }
            if (toColumn < fromColumn || !appendCopiedRange(fromColumn, toColumn)) return null

            if (row < endRow && !frame.lineWrapped(row) && !appendCodePoint('\n'.code)) return null
            row++
        }
        return builder.toString()
    }

    /**
     * Returns whether [cursorColumn] is at the visible text end of [cursorRow].
     *
     * Active command-line suggestions currently require this guard because the
     * terminal can reconstruct the prompt-to-cursor prefix from OSC 133 markers,
     * but it does not yet own a full shell-editor buffer including suffix text
     * to the right of the cursor.
     *
     * @param frame render frame containing the cursor row.
     * @param cursorRow zero-based row containing the cursor.
     * @param cursorColumn zero-based cursor column.
     * @return `true` when no visible command text exists after the cursor.
     */
    fun isCursorAtVisibleLineEnd(
        frame: TerminalRenderFrame,
        cursorRow: Int,
        cursorColumn: Int,
    ): Boolean {
        if (cursorRow !in 0 until frame.rows) return false
        copyLine(frame, cursorRow)
        if (!validClusterData) return false
        return cursorColumn.coerceIn(0, frame.columns) >= lastTextColumnExclusive(frame.columns)
    }

    private fun findLine(
        frame: TerminalRenderFrame,
        lineId: Long,
        lastRow: Int,
    ): Int {
        var row = lastRow
        while (row >= 0 && lastRow - row < maxRows) {
            if (frame.lineId(row) == lineId) return row
            row--
        }
        return -1
    }

    private fun copyLine(
        frame: TerminalRenderFrame,
        row: Int,
    ) {
        ensureColumnCapacity(frame.columns)
        Arrays.fill(clusterOffsets, 0, frame.columns, NO_CLUSTER_OFFSET)
        Arrays.fill(clusterLengths, 0, frame.columns, 0)
        clusterCodePointCount = 0
        validClusterData = true
        frame.copyLine(
            row = row,
            codeWords = codeWords,
            attrWords = attrWords,
            flags = flags,
            clusterDataSink = clusterDataSink,
        )
    }

    private fun ensureColumnCapacity(columns: Int) {
        if (codeWords.size >= columns) return
        codeWords = IntArray(columns)
        attrWords = LongArray(columns)
        flags = IntArray(columns)
        clusterOffsets = IntArray(columns)
        clusterLengths = IntArray(columns)
    }

    private fun ensureClusterCapacity(required: Int) {
        if (clusterCodePoints.size >= required) return
        var capacity = maxOf(MIN_CLUSTER_CAPACITY, clusterCodePoints.size)
        while (capacity < required) capacity = capacity shl 1
        clusterCodePoints = clusterCodePoints.copyOf(capacity)
    }

    private fun lastTextColumnExclusive(columns: Int): Int {
        var column = columns - 1
        while (column >= 0) {
            val cellFlags = flags[column]
            if (cellFlags != TerminalRenderCellFlags.EMPTY) {
                if (cellFlags and TerminalRenderCellFlags.CODEPOINT != 0) {
                    val code = codeWords[column]
                    if (code != 0 && code != 0x20) {
                        return column + 1
                    }
                } else {
                    return column + 1
                }
            }
            column--
        }
        return 0
    }

    private fun appendCopiedRange(
        startColumn: Int,
        endColumn: Int,
    ): Boolean {
        var column = startColumn
        while (column < endColumn) {
            val cellFlags = flags[column]
            when {
                cellFlags and TerminalRenderCellFlags.WIDE_TRAILING != 0 -> Unit
                cellFlags and TerminalRenderCellFlags.CODEPOINT != 0 -> {
                    if (!appendCodePoint(codeWords[column])) return false
                }
                cellFlags and TerminalRenderCellFlags.CLUSTER != 0 -> {
                    val offset = clusterOffsets[column]
                    val length = clusterLengths[column]
                    if (offset == NO_CLUSTER_OFFSET || length <= 0) return false
                    var index = offset
                    val end = offset + length
                    while (index < end) {
                        if (!appendCodePoint(clusterCodePoints[index])) return false
                        index++
                    }
                }
                else -> return false
            }
            column++
        }
        return true
    }

    private fun appendCodePoint(codePoint: Int): Boolean {
        if (!Character.isValidCodePoint(codePoint)) return false
        val charCount = Character.charCount(codePoint)
        if (builder.length > maxTextLength - charCount) return false
        builder.appendCodePoint(codePoint)
        return true
    }

    private companion object {
        private const val NO_LINE_ID = 0L
        private const val NO_CLUSTER_OFFSET = -1
        private const val MIN_CLUSTER_CAPACITY = 16
    }
}
