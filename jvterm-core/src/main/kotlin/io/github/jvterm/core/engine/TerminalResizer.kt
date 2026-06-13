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
package io.github.jvterm.core.engine

import io.github.jvterm.core.buffer.HistoryRing
import io.github.jvterm.core.model.Line
import io.github.jvterm.core.model.TerminalConstants
import io.github.jvterm.core.state.ScreenBuffer
import io.github.jvterm.core.store.ClusterStore

/**
 * Stateless resize engine.
 *
 * Reflowing a terminal grid on resize is a three-phase operation:
 *
 * 1. Logical-line reconstruction from wrapped physical rows.
 * 2. Re-wrapping into new-width physical rows.
 * 3. Cursor relocation into the reflowed result.
 *
 * A resize creates a brand-new [ClusterStore] alongside a brand-new [HistoryRing].
 * Cluster payloads that survive reflow are deep-copied into the new store.
 */
internal object TerminalResizer {
    /**
     * Resizes a specific [ScreenBuffer], reflowing all its content and safely
     * copying surviving grapheme clusters to a new memory arena.
     */
    fun resizeBuffer(
        buffer: ScreenBuffer,
        oldWidth: Int,
        oldHeight: Int,
        newWidth: Int,
        newHeight: Int,
        oldScrollbackOffset: Int = 0,
    ): Int {
        val newStore = ClusterStore()
        val newRing = HistoryRing(buffer.maxHistory + newHeight) { Line(newWidth, newStore) }
        var clusterBuf = IntArray(16)
        val builder = LogicalLineBuilder(oldWidth * 10)

        val absoluteOldCursorRow =
            (buffer.ring.size - oldHeight).coerceAtLeast(0) + buffer.cursor.row
        val oldLiveScreenTop = (buffer.ring.size - oldHeight).coerceAtLeast(0)
        val oldTrailingBlankRows = countTrailingBlankRows(buffer, oldLiveScreenTop, oldHeight)
        val oldTrailingBlankStart = oldLiveScreenTop + oldHeight - oldTrailingBlankRows
        val shouldPreserveViewportTop = newWidth >= oldWidth || oldTrailingBlankRows > 0

        val absoluteOldViewportTopRow = oldLiveScreenTop - oldScrollbackOffset

        var newAbsoluteCursorRow = 0
        var newCursorCol = 0
        var cursorPlaced = false
        var newLiveScreenTop = 0

        var newViewportTopRow = -1
        var viewportTopPlaced = false

        fun flushBuilder() {
            if (builder.size == 0) {
                val newLine = newRing.push()
                newLine.clear(0, 0)
                if (builder.cursorAbsoluteIndex != -1) {
                    newAbsoluteCursorRow = newRing.size - 1
                    newCursorCol = 0
                    cursorPlaced = true
                }
                if (builder.viewportTopAbsoluteIndex != -1) {
                    newViewportTopRow = newRing.size - 1
                    viewportTopPlaced = true
                }
                return
            }

            var offset = 0
            while (offset < builder.size) {
                val newLine = newRing.push()
                newLine.clear(0, 0)

                var chunkLength = minOf(newWidth, builder.size - offset)
                if (chunkLength == newWidth &&
                    chunkLength > 1 &&
                    offset + chunkLength < builder.size &&
                    builder.codepoints[offset + chunkLength] == TerminalConstants.WIDE_CHAR_SPACER
                ) {
                    chunkLength--
                }

                newLine.wrapped = (offset + chunkLength < builder.size)

                for (i in 0 until chunkLength) {
                    val srcIndex = offset + i
                    val raw = builder.codepoints[srcIndex]
                    val attr = builder.attrs[srcIndex]
                    val extendedAttr = builder.extendedAttrs[srcIndex]

                    if (raw <= TerminalConstants.CLUSTER_HANDLE_MAX) {
                        val cpLen = buffer.store.length(raw)
                        if (clusterBuf.size < cpLen) {
                            clusterBuf = IntArray(cpLen)
                        }
                        buffer.store.readInto(raw, clusterBuf)
                        newLine.setCluster(i, clusterBuf, cpLen, attr, extendedAttr)
                    } else {
                        newLine.setRawCell(i, raw, attr, extendedAttr)
                    }

                    if (srcIndex == builder.cursorAbsoluteIndex) {
                        newAbsoluteCursorRow = newRing.size - 1
                        newCursorCol = i
                        cursorPlaced = true
                    }
                    if (srcIndex == builder.viewportTopAbsoluteIndex) {
                        newViewportTopRow = newRing.size - 1
                        viewportTopPlaced = true
                    }
                }
                if (chunkLength < newWidth && offset + chunkLength < builder.size) {
                    newLine.endsWithResizePadding = true
                }
                offset += chunkLength
            }
        }

        for (i in 0 until buffer.ring.size) {
            if (i == oldLiveScreenTop) {
                if (builder.size > 0) {
                    flushBuilder()
                    builder.clear()
                }
                newLiveScreenTop = newRing.size
            }
            if (i >= oldTrailingBlankStart && i < oldLiveScreenTop + oldHeight) {
                continue
            }

            val oldLine = buffer.ring[i]
            val logicalLen = getLogicalLength(oldLine)
            val dataLength = if (oldLine.wrapped && logicalLen > 0) oldWidth else logicalLen
            val hasCursor = i == absoluteOldCursorRow
            val hasViewportTop = oldScrollbackOffset > 0 && i == absoluteOldViewportTopRow

            val readLength =
                if (hasCursor && buffer.cursor.col > 0) {
                    maxOf(dataLength, buffer.cursor.col + 1)
                } else {
                    dataLength
                }

            for (col in 0 until readLength) {
                if (oldLine.endsWithResizePadding && col == oldLine.width - 1) continue
                val isCursor = hasCursor && col == buffer.cursor.col
                val isViewportTop = hasViewportTop && col == 0
                builder.append(
                    oldLine.rawCodepoint(col),
                    oldLine.getPackedAttr(col),
                    oldLine.getPackedExtendedAttr(col),
                    isCursor,
                    isViewportTop,
                )
            }

            if (hasCursor && readLength == 0) {
                builder.cursorAbsoluteIndex = 0
            }
            if (hasViewportTop && readLength == 0) {
                builder.viewportTopAbsoluteIndex = 0
            }

            if (!oldLine.wrapped) {
                flushBuilder()
                builder.clear()
            }
        }

        if (builder.size > 0 || absoluteOldCursorRow >= buffer.ring.size) {
            flushBuilder()
        }

        val minimumRingSize =
            if (shouldPreserveViewportTop) {
                minOf(newLiveScreenTop, buffer.maxHistory) + newHeight
            } else {
                newHeight
            }
        while (newRing.size < minimumRingSize) {
            newRing.push().clear(0, 0)
        }

        val liveScreenTop = (newRing.size - newHeight).coerceAtLeast(0)

        if (!cursorPlaced) {
            newCursorCol = buffer.cursor.col.coerceIn(0, newWidth - 1)
            newAbsoluteCursorRow =
                (liveScreenTop + buffer.cursor.row).coerceIn(liveScreenTop, newRing.size - 1)
        }

        val newRelativeRow = (newAbsoluteCursorRow - liveScreenTop).coerceIn(0, newHeight - 1)

        buffer.store = newStore
        buffer.ring = newRing
        buffer.cursor.col = newCursorCol
        buffer.cursor.row = newRelativeRow

        return if (oldScrollbackOffset > 0 && viewportTopPlaced) {
            (liveScreenTop - newViewportTopRow).coerceIn(0, liveScreenTop)
        } else if (oldScrollbackOffset > 0) {
            oldScrollbackOffset.coerceIn(0, liveScreenTop)
        } else {
            0
        }
    }

    // Private helpers.

    /**
     * Returns the index one past the last durable cell, using the raw value so
     * that cluster handles are correctly treated as content and resize-only
     * wide padding is trimmed like ordinary empty storage.
     */
    private fun getLogicalLength(line: Line): Int {
        var len = line.width
        while (len > 0 && line.rawCodepoint(len - 1) == TerminalConstants.EMPTY) len--
        if (line.endsWithResizePadding && len == line.width) len--
        return len
    }

    private fun countTrailingBlankRows(
        buffer: ScreenBuffer,
        liveScreenTop: Int,
        viewportHeight: Int,
    ): Int {
        var count = 0
        var row = minOf(buffer.ring.size, liveScreenTop + viewportHeight) - 1
        while (row >= liveScreenTop && getLogicalLength(buffer.ring[row]) == 0) {
            count++
            row--
        }
        return count
    }
}

/**
 * Accumulates cells from one or more soft-wrapped physical lines into a single
 * flat logical line for re-wrapping at the new width.
 *
 * All arrays are grown by doubling; the initial capacity should be generous enough
 * to avoid a growth on typical terminal content (oldWidth * 10 is safe).
 *
 * @param initialCapacity Starting array capacity in cells.
 */
private class LogicalLineBuilder(
    initialCapacity: Int,
) {
    var codepoints = IntArray(initialCapacity)
    var attrs = LongArray(initialCapacity)
    var extendedAttrs = LongArray(initialCapacity)
    var size = 0
    var cursorAbsoluteIndex = -1
    var viewportTopAbsoluteIndex = -1

    /**
     * Appends one cell (raw codepoint value plus packed attr) to the builder.
     *
     * @param raw The raw [Int] from [Line.rawCodepoint]; it may be a cluster handle.
     * @param attr The packed cell attribute.
     * @param isCursor `true` if this cell is the current cursor position.
     */
    fun append(
        raw: Int,
        attr: Long,
        extendedAttr: Long,
        isCursor: Boolean,
        isViewportTop: Boolean,
    ) {
        if (size == codepoints.size) grow()
        if (isCursor) cursorAbsoluteIndex = size
        if (isViewportTop) viewportTopAbsoluteIndex = size
        codepoints[size] = raw
        attrs[size] = attr
        extendedAttrs[size] = extendedAttr
        size++
    }

    /** Resets the builder for the next logical line without releasing arrays. */
    fun clear() {
        size = 0
        cursorAbsoluteIndex = -1
        viewportTopAbsoluteIndex = -1
    }

    private fun grow() {
        codepoints = codepoints.copyOf(size * 2)
        attrs = attrs.copyOf(size * 2)
        extendedAttrs = extendedAttrs.copyOf(size * 2)
    }
}
