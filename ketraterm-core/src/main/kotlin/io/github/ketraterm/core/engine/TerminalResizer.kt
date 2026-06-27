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
package io.github.ketraterm.core.engine

import io.github.ketraterm.core.buffer.HistoryRing
import io.github.ketraterm.core.model.Line
import io.github.ketraterm.core.model.TerminalConstants
import io.github.ketraterm.core.state.ScreenBuffer
import io.github.ketraterm.core.store.ClusterStore

/**
 * Stateless resize engine.
 *
 * Reflowing a terminal grid on resize is a four-phase operation:
 *
 * 1. Reconstruct logical lines from soft-wrapped physical rows.
 * 2. Re-wrap those logical lines at the new width.
 * 3. Select the new live-screen boundary using ConPTY-compatible anchoring.
 * 4. Relocate the cursor and scrollback viewport into the reflowed result.
 *
 * A resize creates a brand-new [ClusterStore] alongside a brand-new [HistoryRing].
 * Cluster payloads that survive reflow are deep-copied into the new store.
 *
 * Important:
 *
 * The history/live-screen boundary is not necessarily a logical-line boundary.
 * A soft-wrapped logical line may begin in history and continue into the visible
 * screen. Such a line must be reconstructed and reflowed as one unit.
 */
internal object TerminalResizer {
    /**
     * Resizes a specific [ScreenBuffer], reflowing all its content and safely
     * copying surviving grapheme clusters to a new memory arena.
     *
     * The returned value is the updated scrollback offset for a user-controlled
     * viewport. A return value of zero means the viewport remains attached to
     * the live screen.
     */
    fun resizeBuffer(
        buffer: ScreenBuffer,
        oldWidth: Int,
        oldHeight: Int,
        newWidth: Int,
        newHeight: Int,
        oldScrollbackOffset: Int = 0,
        lineIdProvider: () -> Long = ResizeFallbackLineIdProvider(),
    ): Int {
        require(oldWidth > 0) { "oldWidth must be positive" }
        require(oldHeight > 0) { "oldHeight must be positive" }
        require(newWidth > 0) { "newWidth must be positive" }
        require(newHeight > 0) { "newHeight must be positive" }

        val newStore = ClusterStore()
        val newRing =
            HistoryRing(buffer.maxHistory + newHeight) {
                Line(newWidth, newStore)
            }

        var clusterBuf = IntArray(16)
        val builder = LogicalLineBuilder(oldWidth * 10)

        val oldLiveScreenTop =
            (buffer.ring.size - oldHeight).coerceAtLeast(0)

        val absoluteOldCursorRow =
            oldLiveScreenTop + buffer.cursor.row

        val oldTrailingBlankRows =
            countTrailingBlankRows(
                buffer = buffer,
                liveScreenTop = oldLiveScreenTop,
                viewportHeight = oldHeight,
            )

        val oldTrailingBlankStart =
            oldLiveScreenTop + oldHeight - oldTrailingBlankRows

        /*
         * Existing policy:
         *
         * - Widening preserves the old screen top.
         * - Existing trailing blank rows also preserve the old screen top.
         * - Otherwise the result stays bottom-attached.
         *
         * The important correction is that "preserving the screen top" does
         * not necessarily mean preserving the old first physical row.
         *
         * If that physical row is a soft-wrap continuation, we preserve the
         * start of its logical line instead.
         */
        val shouldPreserveLiveScreenTop =
            newWidth >= oldWidth || oldTrailingBlankRows > 0

        /*
         * `wrapped` means that a row continues into the following row.
         *
         * Therefore, the first visible row is a continuation exactly when the
         * preceding ring row has wrapped == true.
         */
        val oldLiveScreenStartsInsideWrappedLine =
            oldLiveScreenTop > 0 &&
                buffer.ring[oldLiveScreenTop - 1].wrapped

        val absoluteOldViewportTopRow =
            oldLiveScreenTop - oldScrollbackOffset

        var newAbsoluteCursorRow = 0
        var newCursorCol = 0
        var cursorPlaced = false

        /*
         * Target live-screen top in the newly reflowed ring.
         *
         * For a hard boundary, this is the first row emitted after the old
         * history section.
         *
         * For a boundary inside a wrapped line, this becomes the first row
         * emitted for that entire reconstructed logical line.
         */
        var targetNewLiveScreenTop = -1

        /*
         * True while the current builder contains the old history/live-screen
         * boundary.
         *
         * When this is true, flushBuilder() records the beginning of the newly
         * reflowed logical line as the target live-screen top.
         */
        var builderContainsOldLiveScreenTop = false

        var newViewportTopRow = -1
        var viewportTopPlaced = false

        fun placeCursorAtEmptyLogicalLine() {
            if (builder.cursorAbsoluteIndex == -1) return

            newAbsoluteCursorRow = newRing.size - 1
            newCursorCol = 0
            cursorPlaced = true
        }

        fun placeViewportAtEmptyLogicalLine() {
            if (builder.viewportTopAbsoluteIndex == -1) return

            newViewportTopRow = newRing.size - 1
            viewportTopPlaced = true
        }

        fun recordBuilderLiveScreenTop() {
            if (!builderContainsOldLiveScreenTop) return
            if (targetNewLiveScreenTop >= 0) return

            /*
             * This is the beginning of the fully reconstructed logical line,
             * including any wrapped fragments that previously lived in history.
             */
            targetNewLiveScreenTop = newRing.size
        }

        fun flushBuilder() {
            recordBuilderLiveScreenTop()

            val outputLineId =
                if (builder.lineId > 0L) {
                    builder.lineId
                } else {
                    lineIdProvider()
                }

            if (builder.size == 0) {
                val newLine = newRing.push()
                newLine.assignLineId(outputLineId)
                newLine.clear(0, 0)

                placeCursorAtEmptyLogicalLine()
                placeViewportAtEmptyLogicalLine()

                builderContainsOldLiveScreenTop = false
                return
            }

            var offset = 0

            while (offset < builder.size) {
                val newLine = newRing.push()
                newLine.assignLineId(outputLineId)
                newLine.clear(0, 0)

                var chunkLength =
                    minOf(newWidth, builder.size - offset)

                /*
                 * Never place a wide-character spacer at the start of the next
                 * physical row. Keep the wide occupant together.
                 */
                if (
                    chunkLength == newWidth &&
                    chunkLength > 1 &&
                    offset + chunkLength < builder.size &&
                    builder.codepoints[offset + chunkLength] ==
                    TerminalConstants.WIDE_CHAR_SPACER
                ) {
                    chunkLength--
                }

                newLine.wrapped =
                    offset + chunkLength < builder.size

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
                        newLine.setCluster(
                            i,
                            clusterBuf,
                            cpLen,
                            attr,
                            extendedAttr,
                        )
                    } else {
                        newLine.setRawCell(
                            i,
                            raw,
                            attr,
                            extendedAttr,
                        )
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

                /*
                 * This can occur when a wide occupant forced the current chunk
                 * to stop one cell before the physical right edge.
                 */
                if (
                    chunkLength < newWidth &&
                    offset + chunkLength < builder.size
                ) {
                    newLine.endsWithResizePadding = true
                }

                offset += chunkLength
            }

            builderContainsOldLiveScreenTop = false
        }

        for (i in 0 until buffer.ring.size) {
            /*
             * The old history/live-screen boundary is only a storage boundary.
             * It is not necessarily a logical-line boundary.
             */
            if (i == oldLiveScreenTop) {
                if (
                    oldLiveScreenStartsInsideWrappedLine &&
                    builder.size > 0
                ) {
                    /*
                     * Do not flush.
                     *
                     * The previous row wraps into this row, so the builder
                     * already contains the beginning of the same logical line.
                     * Flushing here would incorrectly split:
                     *
                     *     CCC | DDDEEE
                     *
                     * instead of reconstructing:
                     *
                     *     CCCDDDEEE
                     */
                    builderContainsOldLiveScreenTop = true
                } else {
                    /*
                     * This is a genuine logical-line boundary. Finish any
                     * history-side builder, then record the start of the
                     * visible section.
                     */
                    if (builder.size > 0) {
                        flushBuilder()
                        builder.clear()
                    }

                    if (targetNewLiveScreenTop < 0) {
                        targetNewLiveScreenTop = newRing.size
                    }
                }
            }

            /*
             * Trailing blank viewport rows are layout capacity rather than
             * durable content. They are recreated after reflow.
             */
            if (
                i >= oldTrailingBlankStart &&
                i < oldLiveScreenTop + oldHeight
            ) {
                continue
            }

            val oldLine = buffer.ring[i]

            builder.startLogicalLine(oldLine.lineId)

            val logicalLen = getLogicalLength(oldLine)

            /*
             * A wrapped row contributes its complete physical width to the
             * logical line. Empty cells at its right edge are still part of
             * the continuation geometry.
             */
            val dataLength =
                if (oldLine.wrapped && logicalLen > 0) {
                    oldWidth
                } else {
                    logicalLen
                }

            val hasCursor =
                i == absoluteOldCursorRow

            val hasViewportTop =
                oldScrollbackOffset > 0 &&
                    i == absoluteOldViewportTopRow

            val readLength =
                if (hasCursor && buffer.cursor.col > 0) {
                    maxOf(
                        dataLength,
                        buffer.cursor.col + 1,
                    )
                } else {
                    dataLength
                }

            for (col in 0 until readLength) {
                /*
                 * Resize padding is not logical terminal content.
                 */
                if (
                    oldLine.endsWithResizePadding &&
                    col == oldLine.width - 1
                ) {
                    continue
                }

                val isCursor =
                    hasCursor && col == buffer.cursor.col

                val isViewportTop =
                    hasViewportTop && col == 0

                builder.append(
                    raw = oldLine.rawCodepoint(col),
                    attr = oldLine.getPackedAttr(col),
                    extendedAttr = oldLine.getPackedExtendedAttr(col),
                    isCursor = isCursor,
                    isViewportTop = isViewportTop,
                )
            }

            /*
             * Preserve markers for empty rows where no cell was appended.
             */
            if (hasCursor && readLength == 0) {
                builder.cursorAbsoluteIndex = 0
            }

            if (hasViewportTop && readLength == 0) {
                builder.viewportTopAbsoluteIndex = 0
            }

            /*
             * A row with wrapped == false terminates the logical line.
             */
            if (!oldLine.wrapped) {
                flushBuilder()
                builder.clear()
            }
        }

        if (
            builder.size > 0 ||
            absoluteOldCursorRow >= buffer.ring.size
        ) {
            flushBuilder()
            builder.clear()
        }

        /*
         * Empty buffers and degenerate source states may not have crossed the
         * old boundary while iterating.
         */
        if (targetNewLiveScreenTop < 0) {
            targetNewLiveScreenTop =
                (newRing.size - newHeight).coerceAtLeast(0)
        }

        /*
         * When preserving the top, append enough blank rows that the selected
         * top remains the first visible row.
         *
         * Hard boundary:
         *
         *     preserve old first screen row
         *     append blanks at bottom
         *
         * Wrapped boundary:
         *
         *     preserve logical-line start
         *     allow its earlier history fragment into the screen
         *     append blanks at bottom if required
         */
        val minimumRingSize =
            if (shouldPreserveLiveScreenTop) {
                minOf(
                    targetNewLiveScreenTop,
                    buffer.maxHistory,
                ) + newHeight
            } else {
                newHeight
            }

        while (newRing.size < minimumRingSize) {
            val line = newRing.push()
            line.assignLineId(lineIdProvider())
            line.clear(0, 0)
        }

        /*
         * The actual live screen is always the final newHeight rows.
         *
         * Padding above makes this equal targetNewLiveScreenTop when top
         * preservation is requested.
         */
        val liveScreenTop =
            (newRing.size - newHeight).coerceAtLeast(0)

        if (!cursorPlaced) {
            newCursorCol =
                buffer.cursor.col.coerceIn(
                    0,
                    newWidth - 1,
                )

            newAbsoluteCursorRow =
                (liveScreenTop + buffer.cursor.row).coerceIn(
                    liveScreenTop,
                    newRing.size - 1,
                )
        }

        val newRelativeCursorRow =
            (newAbsoluteCursorRow - liveScreenTop).coerceIn(
                0,
                newHeight - 1,
            )

        buffer.store = newStore
        buffer.ring = newRing
        buffer.cursor.col = newCursorCol
        buffer.cursor.row = newRelativeCursorRow

        return when {
            oldScrollbackOffset > 0 && viewportTopPlaced -> {
                (liveScreenTop - newViewportTopRow).coerceIn(
                    0,
                    liveScreenTop,
                )
            }

            oldScrollbackOffset > 0 -> {
                oldScrollbackOffset.coerceIn(
                    0,
                    liveScreenTop,
                )
            }

            else -> {
                0
            }
        }
    }

    /**
     * Returns the index one past the last durable cell.
     *
     * Raw values are used so cluster handles count as content. Temporary
     * resize-only wide padding is excluded.
     */
    private fun getLogicalLength(line: Line): Int {
        var len = line.width

        while (
            len > 0 &&
            line.rawCodepoint(len - 1) == TerminalConstants.EMPTY
        ) {
            len--
        }

        if (
            line.endsWithResizePadding &&
            len == line.width
        ) {
            len--
        }

        return len
    }

    private fun countTrailingBlankRows(
        buffer: ScreenBuffer,
        liveScreenTop: Int,
        viewportHeight: Int,
    ): Int {
        var count = 0

        var row =
            minOf(
                buffer.ring.size,
                liveScreenTop + viewportHeight,
            ) - 1

        while (
            row >= liveScreenTop &&
            getLogicalLength(buffer.ring[row]) == 0
        ) {
            count++
            row--
        }

        return count
    }
}

private class ResizeFallbackLineIdProvider : () -> Long {
    private var nextLineId = 1L

    override fun invoke(): Long {
        val id = nextLineId
        nextLineId++
        return id
    }
}

/**
 * Accumulates cells from one or more soft-wrapped physical rows into one flat
 * logical line for re-wrapping at the new width.
 *
 * The old history/live-screen boundary is deliberately not represented here.
 * It is a viewport/storage boundary, not a logical-line boundary.
 */
private class LogicalLineBuilder(
    initialCapacity: Int,
) {
    var codepoints =
        IntArray(initialCapacity.coerceAtLeast(1))

    var attrs =
        LongArray(initialCapacity.coerceAtLeast(1))

    var extendedAttrs =
        LongArray(initialCapacity.coerceAtLeast(1))

    var size = 0
    var cursorAbsoluteIndex = -1
    var viewportTopAbsoluteIndex = -1
    var lineId = 0L

    /**
     * Captures the source identity for the current logical line.
     *
     * Wrapped continuation rows retain the identity selected from the first
     * physical row.
     */
    fun startLogicalLine(sourceLineId: Long) {
        if (
            lineId == 0L &&
            sourceLineId > 0L
        ) {
            lineId = sourceLineId
        }
    }

    fun append(
        raw: Int,
        attr: Long,
        extendedAttr: Long,
        isCursor: Boolean,
        isViewportTop: Boolean,
    ) {
        ensureCapacity(size + 1)

        if (isCursor) {
            cursorAbsoluteIndex = size
        }

        if (isViewportTop) {
            viewportTopAbsoluteIndex = size
        }

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
        lineId = 0L
    }

    private fun ensureCapacity(required: Int) {
        if (required <= codepoints.size) return

        var newCapacity = codepoints.size.coerceAtLeast(1)

        while (newCapacity < required) {
            newCapacity *= 2
        }

        codepoints = codepoints.copyOf(newCapacity)
        attrs = attrs.copyOf(newCapacity)
        extendedAttrs = extendedAttrs.copyOf(newCapacity)
    }
}
