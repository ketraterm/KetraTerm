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
import io.github.ketraterm.render.cache.TerminalRenderCache
import java.util.*

internal const val DEFAULT_SHELL_INTEGRATION_COMMAND_TEXT_LENGTH = 4096
internal const val MAX_SHELL_INTEGRATION_COMMAND_ROWS = 256
internal const val TERMINAL_SHELL_COMMAND_FINGERPRINT_LONGS = 3
internal const val TERMINAL_SHELL_COMMAND_FINGERPRINT_HASH_A_INDEX = 0
internal const val TERMINAL_SHELL_COMMAND_FINGERPRINT_HASH_B_INDEX = 1
internal const val TERMINAL_SHELL_COMMAND_FINGERPRINT_UTF16_LENGTH_INDEX = 2

internal enum class TerminalShellCommandFingerprintStatus {
    COMPLETE,
    MISSING_START_LINE,
    INVALID,
}

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
     * Computes an allocation-free fingerprint for one active command line.
     *
     * The fingerprint covers the exact code-point sequence reconstructed by
     * [extract], including hard row-boundary newlines. [destination] receives
     * two independent hashes and the UTF-16 text length. Callers compare all
     * three values together with cursor coordinates before publishing a new
     * shell-edit revision.
     *
     * @param frame immutable render frame containing the active command.
     * @param promptEndLineId stable line id recorded at OSC 133 prompt end.
     * @param promptEndColumn first command column on the prompt-end line.
     * @param cursorRow zero-based cursor row in [frame].
     * @param cursorColumn zero-based cursor column in [frame].
     * @param destination reusable primitive output with at least
     * [TERMINAL_SHELL_COMMAND_FINGERPRINT_LONGS] elements.
     * @return fingerprint status; [TerminalShellCommandFingerprintStatus.MISSING_START_LINE]
     * allows the caller to retry with bounded scrollback rows.
     */
    fun fingerprint(
        frame: TerminalRenderFrame,
        promptEndLineId: Long,
        promptEndColumn: Int,
        cursorRow: Int,
        cursorColumn: Int,
        destination: LongArray,
    ): TerminalShellCommandFingerprintStatus =
        fingerprintSource(
            frame = frame,
            cache = null,
            promptEndLineId = promptEndLineId,
            promptEndColumn = promptEndColumn,
            cursorRow = cursorRow,
            cursorColumn = cursorColumn,
            destination = destination,
        )

    /** Allocation-free fingerprint overload for an immutable published render cache. */
    fun fingerprint(
        cache: TerminalRenderCache,
        promptEndLineId: Long,
        promptEndColumn: Int,
        cursorRow: Int,
        cursorColumn: Int,
        destination: LongArray,
    ): TerminalShellCommandFingerprintStatus =
        fingerprintSource(
            frame = null,
            cache = cache,
            promptEndLineId = promptEndLineId,
            promptEndColumn = promptEndColumn,
            cursorRow = cursorRow,
            cursorColumn = cursorColumn,
            destination = destination,
        )

    private fun fingerprintSource(
        frame: TerminalRenderFrame?,
        cache: TerminalRenderCache?,
        promptEndLineId: Long,
        promptEndColumn: Int,
        cursorRow: Int,
        cursorColumn: Int,
        destination: LongArray,
    ): TerminalShellCommandFingerprintStatus {
        require(destination.size >= TERMINAL_SHELL_COMMAND_FINGERPRINT_LONGS) {
            "destination must contain at least $TERMINAL_SHELL_COMMAND_FINGERPRINT_LONGS longs"
        }
        val rows = frame?.rows ?: cache?.rows ?: return TerminalShellCommandFingerprintStatus.INVALID
        val columns = frame?.columns ?: cache?.columns ?: return TerminalShellCommandFingerprintStatus.INVALID
        if (promptEndLineId == NO_LINE_ID || cursorRow !in 0 until rows) {
            return TerminalShellCommandFingerprintStatus.INVALID
        }

        val startRow = findFingerprintLine(frame, cache, promptEndLineId, cursorRow)
        if (startRow < 0) return TerminalShellCommandFingerprintStatus.MISSING_START_LINE

        val cursorAtNextLineStart = cursorColumn == 0 && cursorRow > startRow
        val endRow = if (cursorAtNextLineStart) cursorRow - 1 else cursorRow
        if (endRow < startRow || endRow - startRow + 1 > maxRows) {
            return TerminalShellCommandFingerprintStatus.INVALID
        }

        if (cursorAtNextLineStart) {
            copyFingerprintLine(frame, cache, cursorRow, columns)
            if (!validClusterData || lastTextColumnExclusive(columns) > 0) {
                return TerminalShellCommandFingerprintStatus.INVALID
            }
        }

        destination[TERMINAL_SHELL_COMMAND_FINGERPRINT_HASH_A_INDEX] = FINGERPRINT_HASH_A_SEED
        destination[TERMINAL_SHELL_COMMAND_FINGERPRINT_HASH_B_INDEX] = FINGERPRINT_HASH_B_SEED
        destination[TERMINAL_SHELL_COMMAND_FINGERPRINT_UTF16_LENGTH_INDEX] = 0L
        val startColumn = promptEndColumn.coerceIn(0, columns)
        var row = startRow
        while (row <= endRow) {
            copyFingerprintLine(frame, cache, row, columns)
            if (!validClusterData) return TerminalShellCommandFingerprintStatus.INVALID
            if (!cursorAtNextLineStart && row == cursorRow) {
                if (cursorColumn.coerceIn(0, columns) < lastTextColumnExclusive(columns)) {
                    return TerminalShellCommandFingerprintStatus.INVALID
                }
            }

            val fromColumn = if (row == startRow) startColumn else 0
            val toColumn =
                when {
                    row < endRow -> lastTextColumnExclusive(columns)
                    cursorAtNextLineStart -> lastTextColumnExclusive(columns)
                    else -> cursorColumn.coerceIn(0, columns)
                }
            if (toColumn < fromColumn) return TerminalShellCommandFingerprintStatus.INVALID

            var column = fromColumn
            while (column < toColumn) {
                val cellFlags = flags[column]
                when {
                    cellFlags and TerminalRenderCellFlags.WIDE_TRAILING != 0 -> Unit
                    cellFlags and TerminalRenderCellFlags.CODEPOINT != 0 -> {
                        if (!hashCodePoint(codeWords[column], destination)) {
                            return TerminalShellCommandFingerprintStatus.INVALID
                        }
                    }
                    cellFlags and TerminalRenderCellFlags.CLUSTER != 0 -> {
                        val offset = clusterOffsets[column]
                        val length = clusterLengths[column]
                        if (offset == NO_CLUSTER_OFFSET || length <= 0) {
                            return TerminalShellCommandFingerprintStatus.INVALID
                        }
                        var index = offset
                        val end = offset + length
                        while (index < end) {
                            if (!hashCodePoint(clusterCodePoints[index], destination)) {
                                return TerminalShellCommandFingerprintStatus.INVALID
                            }
                            index++
                        }
                    }
                    else -> return TerminalShellCommandFingerprintStatus.INVALID
                }
                column++
            }

            if (row < endRow && !(frame?.lineWrapped(row) ?: cache!!.lineWrapped[row])) {
                if (!hashCodePoint('\n'.code, destination)) {
                    return TerminalShellCommandFingerprintStatus.INVALID
                }
            }
            row++
        }
        return TerminalShellCommandFingerprintStatus.COMPLETE
    }

    private fun findFingerprintLine(
        frame: TerminalRenderFrame?,
        cache: TerminalRenderCache?,
        lineId: Long,
        lastRow: Int,
    ): Int {
        var row = lastRow
        while (row >= 0 && lastRow - row < maxRows) {
            if ((frame?.lineId(row) ?: cache!!.lineIds[row]) == lineId) return row
            row--
        }
        return -1
    }

    private fun copyFingerprintLine(
        frame: TerminalRenderFrame?,
        cache: TerminalRenderCache?,
        row: Int,
        columns: Int,
    ) {
        if (frame != null) {
            copyLine(frame, row)
            return
        }
        val source = cache ?: error("fingerprint source is missing")
        ensureColumnCapacity(columns)
        val rowOffset = row * columns
        System.arraycopy(source.codeWords, rowOffset, codeWords, 0, columns)
        System.arraycopy(source.attrWords, rowOffset, attrWords, 0, columns)
        System.arraycopy(source.flags, rowOffset, flags, 0, columns)
        Arrays.fill(clusterOffsets, 0, columns, NO_CLUSTER_OFFSET)
        Arrays.fill(clusterLengths, 0, columns, 0)
        clusterCodePointCount = 0
        validClusterData = true
        var column = 0
        while (column < columns) {
            val reference = source.clusterRefs[rowOffset + column]
            if (reference != 0L) {
                val sourceOffset = (reference ushr 32).toInt()
                val length = reference.toInt()
                if (sourceOffset < 0 || length <= 0 || sourceOffset > source.clusterCodepoints.size - length) {
                    validClusterData = false
                    return
                }
                ensureClusterCapacity(clusterCodePointCount + length)
                System.arraycopy(
                    source.clusterCodepoints,
                    sourceOffset,
                    clusterCodePoints,
                    clusterCodePointCount,
                    length,
                )
                clusterOffsets[column] = clusterCodePointCount
                clusterLengths[column] = length
                clusterCodePointCount += length
            }
            column++
        }
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
        return validClusterData && cursorColumn.coerceIn(0, frame.columns) >= lastTextColumnExclusive(frame.columns)
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

    private fun hashCodePoint(
        codePoint: Int,
        destination: LongArray,
    ): Boolean {
        if (!Character.isValidCodePoint(codePoint)) return false
        val utf16Length = destination[TERMINAL_SHELL_COMMAND_FINGERPRINT_UTF16_LENGTH_INDEX].toInt()
        val charCount = Character.charCount(codePoint)
        if (utf16Length > maxTextLength - charCount) return false
        val value = codePoint.toLong() and 0xffff_ffffL
        destination[TERMINAL_SHELL_COMMAND_FINGERPRINT_HASH_A_INDEX] =
            (destination[TERMINAL_SHELL_COMMAND_FINGERPRINT_HASH_A_INDEX] xor value) * FINGERPRINT_HASH_A_PRIME
        destination[TERMINAL_SHELL_COMMAND_FINGERPRINT_HASH_B_INDEX] =
            java.lang.Long.rotateLeft(
                destination[TERMINAL_SHELL_COMMAND_FINGERPRINT_HASH_B_INDEX] xor (value + FINGERPRINT_HASH_B_MIX),
                27,
            ) * FINGERPRINT_HASH_B_PRIME
        destination[TERMINAL_SHELL_COMMAND_FINGERPRINT_UTF16_LENGTH_INDEX] = (utf16Length + charCount).toLong()
        return true
    }

    private companion object {
        private const val NO_LINE_ID = 0L
        private const val NO_CLUSTER_OFFSET = -1
        private const val MIN_CLUSTER_CAPACITY = 16
        private const val FINGERPRINT_HASH_A_SEED = -0x340d631b7bdddcdbL
        private const val FINGERPRINT_HASH_A_PRIME = 0x100000001b3L
        private const val FINGERPRINT_HASH_B_SEED = -0x61c8864680b583ebL
        private const val FINGERPRINT_HASH_B_MIX = -0x61c8864680b583ebL
        private const val FINGERPRINT_HASH_B_PRIME = -0x3d4d51cb3a5b3b5dL
    }
}
