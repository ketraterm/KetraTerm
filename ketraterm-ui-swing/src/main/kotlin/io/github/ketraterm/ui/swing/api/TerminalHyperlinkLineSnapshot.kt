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
import io.github.ketraterm.render.api.TerminalRenderCellFlags
import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.ui.swing.render.forEachLogicalTextCell

/** Immutable text and UTF-16-to-cell mapping for one soft-wrapped logical line. */
internal class TerminalHyperlinkLineSnapshot(
    val text: String,
    val firstAbsoluteRow: Long,
    val lastAbsoluteRow: Long,
    val columns: Int,
    val activeBuffer: TerminalRenderBufferKind,
    val cellStarts: IntArray,
    val cellEnds: IntArray,
    private val lineIds: LongArray,
    private val generations: LongArray,
    private val fingerprints: LongArray,
    private val wrapped: BooleanArray,
) {
    val firstLineId: Long get() = lineIds[0]

    /** First UTF-16 unit owning a cell after the given physical-cell boundary. */
    fun firstOffsetAfterCell(cell: Long): Int {
        var low = 0
        var high = cellEnds.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (cellEnds[middle] <= cell) low = middle + 1 else high = middle
        }
        return low
    }

    /** Exclusive UTF-16 bound before the given physical-cell boundary. */
    fun endOffsetBeforeCell(cell: Long): Int {
        var low = 0
        var high = cellStarts.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (cellStarts[middle] < cell) low = middle + 1 else high = middle
        }
        return low
    }

    fun matchesRow(
        cache: TerminalRenderCache,
        row: Int,
        absoluteRow: Long,
    ): Boolean {
        val offset = (absoluteRow - firstAbsoluteRow).toInt()
        return !(offset !in lineIds.indices || cache.columns != columns || cache.activeBuffer != activeBuffer) && cache.lineIds[row] == lineIds[offset] &&
                cache.lineWrapped[row] == wrapped[offset] &&
                if (lineIds[offset] != 0L) {
                    cache.lineGenerations[row] == generations[offset]
                } else {
                    rowFingerprint(cache, row) == fingerprints[offset]
                }
    }

    /** Validates copied source text even when presentation-only mutations advanced row generations. */
    fun matchesTextRow(
        cache: TerminalRenderCache,
        row: Int,
        absoluteRow: Long,
    ): Boolean {
        val offset = (absoluteRow - firstAbsoluteRow).toInt()
        return offset in lineIds.indices &&
            cache.columns == columns &&
            cache.activeBuffer == activeBuffer &&
            cache.lineIds[row] == lineIds[offset] &&
            cache.lineWrapped[row] == wrapped[offset] &&
            rowFingerprint(cache, row) == fingerprints[offset]
    }

    /** Keeps an evicted prefix only while its copied, still-retained suffix is unchanged. */
    fun withRetainedSuffix(suffix: TerminalHyperlinkLineSnapshot): TerminalHyperlinkLineSnapshot? {
        if (suffix.columns != columns ||
            suffix.activeBuffer != activeBuffer ||
            suffix.firstAbsoluteRow <= firstAbsoluteRow ||
            suffix.lastAbsoluteRow != lastAbsoluteRow
        ) {
            return null
        }
        val rowOffset = (suffix.firstAbsoluteRow - firstAbsoluteRow).toInt()
        val cellOffset = rowOffset * columns
        val textOffset = firstOffsetAfterCell(cellOffset.toLong())
        if (text.length - textOffset != suffix.text.length ||
            !text.regionMatches(textOffset, suffix.text, 0, suffix.text.length)
        ) {
            return null
        }
        for (row in suffix.lineIds.indices) {
            if (lineIds[rowOffset + row] != suffix.lineIds[row] || wrapped[rowOffset + row] != suffix.wrapped[row]) return null
        }
        for (offset in suffix.cellStarts.indices) {
            if (cellStarts[textOffset + offset] != cellOffset + suffix.cellStarts[offset] ||
                cellEnds[textOffset + offset] != cellOffset + suffix.cellEnds[offset]
            ) {
                return null
            }
        }
        val updatedGenerations = generations.copyOf()
        suffix.generations.copyInto(updatedGenerations, rowOffset)
        return TerminalHyperlinkLineSnapshot(
            text,
            firstAbsoluteRow,
            lastAbsoluteRow,
            columns,
            activeBuffer,
            cellStarts,
            cellEnds,
            lineIds,
            updatedGenerations,
            fingerprints,
            wrapped,
        )
    }
}

/** Reuses extraction scratch storage; snapshots are created only for changed logical lines. */
internal class TerminalHyperlinkLineSnapshotBuilder {
    private val text = StringBuilder(256)
    private var starts = IntArray(256)
    private var ends = IntArray(256)
    private var ids = LongArray(16)
    private var generations = LongArray(16)
    private var fingerprints = LongArray(16)
    private var wrapped = BooleanArray(16)
    private var rowCount = 0
    private var first = 0L
    private var columns = 0
    private var buffer = TerminalRenderBufferKind.PRIMARY

    fun begin(
        firstAbsoluteRow: Long,
        columns: Int,
        buffer: TerminalRenderBufferKind,
    ) {
        text.setLength(0)
        rowCount = 0
        first = firstAbsoluteRow
        this.columns = columns
        this.buffer = buffer
    }

    fun append(
        cache: TerminalRenderCache,
        row: Int,
    ) {
        if (rowCount == ids.size) {
            val size = ids.size * 2
            ids = ids.copyOf(size)
            generations = generations.copyOf(size)
            fingerprints = fingerprints.copyOf(size)
            wrapped = wrapped.copyOf(size)
        }
        ids[rowCount] = cache.lineIds[row]
        generations[rowCount] = cache.lineGenerations[row]
        fingerprints[rowCount] = rowFingerprint(cache, row)
        wrapped[rowCount] = cache.lineWrapped[row]
        forEachLogicalTextCell(cache, row) { codePoint, startColumn, endColumn ->
            val offset = rowCount * columns
            appendCodePoint(codePoint, offset + startColumn, offset + endColumn)
        }
        rowCount++
    }

    fun finish(): TerminalHyperlinkLineSnapshot {
        check(rowCount > 0)
        while (text.isNotEmpty() && text.last() == ' ') text.setLength(text.length - 1)
        val length = text.length
        text.append('\n')
        return TerminalHyperlinkLineSnapshot(
            text.toString(),
            first,
            first + rowCount - 1L,
            columns,
            buffer,
            starts.copyOf(length),
            ends.copyOf(length),
            ids.copyOf(rowCount),
            generations.copyOf(rowCount),
            fingerprints.copyOf(rowCount),
            wrapped.copyOf(rowCount),
        )
    }

    fun snapshot(
        cache: TerminalRenderCache,
        startRow: Int,
        endRow: Int,
    ): TerminalHyperlinkLineSnapshot {
        begin(cache.discardedCount + cache.historySize - cache.scrollbackOffset + startRow, cache.columns, cache.activeBuffer)
        for (row in startRow until endRow) {
            append(cache, row)
        }
        return finish()
    }

    private fun appendCodePoint(
        codePoint: Int,
        start: Int,
        end: Int,
    ) {
        val before = text.length
        text.appendCodePoint(if (Character.isValidCodePoint(codePoint)) codePoint else 0xFFFD)
        if (text.length > starts.size) {
            val capacity = maxOf(text.length, starts.size * 2)
            starts = starts.copyOf(capacity)
            ends = ends.copyOf(capacity)
        }
        for (offset in before until text.length) {
            starts[offset] = start
            ends[offset] = end
        }
    }
}

private fun rowFingerprint(
    cache: TerminalRenderCache,
    row: Int,
): Long {
    var hash = -3750763034362895579L
    val rowOffset = cache.rowOffset(row)
    for (column in 0 until cache.columns) {
        val index = rowOffset + column
        val flags = cache.flags[index]
        val textFlags =
            flags and (
                TerminalRenderCellFlags.CODEPOINT or TerminalRenderCellFlags.CLUSTER or
                    TerminalRenderCellFlags.WIDE_LEADING or TerminalRenderCellFlags.WIDE_TRAILING or TerminalRenderCellFlags.WRAP_PADDING
            )
        hash = (hash xor textFlags.toLong()) * 1099511628211L
        if (flags and TerminalRenderCellFlags.CLUSTER != 0) {
            val ref = cache.clusterRefs[index]
            if (ref != 0L) {
                val start = cache.clusterOffset(ref)
                val end = start + cache.clusterLength(ref)
                for (offset in start until end) hash = (hash xor cache.clusterCodepoints[offset].toLong()) * 1099511628211L
            }
        } else {
            hash = (hash xor cache.codeWords[index].toLong()) * 1099511628211L
        }
    }
    return hash
}
