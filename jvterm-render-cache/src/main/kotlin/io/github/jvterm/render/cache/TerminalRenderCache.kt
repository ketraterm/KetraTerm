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
package io.github.jvterm.render.cache

import io.github.jvterm.render.api.*

/**
 * Caller-owned primitive cache for render frames.
 *
 * This cache consumes [TerminalRenderFrameReader] and stores copied primitive
 * row data after the frame callback returns. It deliberately contains no
 * backend-specific glyph runs, font state, paint objects, selection model, or UI
 * timer logic. Swing, Compose, and other renderers can build their local layout
 * and paint caches from this primitive data.
 *
 * @param columns initial cache width in cells.
 * @param rows initial cache height in rows.
 */
class TerminalRenderCache(
    columns: Int,
    rows: Int,
) : TerminalRenderFrameConsumer {
    /**
     * Cached visible width in cells.
     */
    var columns: Int = columns
        private set

    /**
     * Cached visible height in rows.
     */
    var rows: Int = rows
        private set

    /**
     * Retained history lines reported by the most recent frame.
     */
    var historySize: Int = 0
        private set

    /**
     * Resolved scrollback offset copied by the most recent frame.
     */
    var scrollbackOffset: Int = 0
        private set

    /**
     * History lines discarded due to capacity wrapping since initialization.
     */
    var discardedCount: Long = 0L
        private set

    /**
     * Copied code words in row-major order. See [TerminalRenderFrame.copyLine].
     */
    var codeWords: IntArray = IntArray(0)
        private set

    /**
     * Copied primary public render attribute words in row-major order.
     */
    var attrWords: LongArray = LongArray(0)
        private set

    /**
     * Copied public render cell flags in row-major order.
     */
    var flags: IntArray = IntArray(0)
        private set

    /**
     * Copied optional public extra-attribute words in row-major order.
     */
    var extraAttrWords: LongArray = LongArray(0)
        private set

    /**
     * Copied optional hyperlink identifiers in row-major order. Zero means no
     * hyperlink.
     */
    var hyperlinkIds: IntArray = IntArray(0)
        private set

    /**
     * Copied cluster references in row-major order. Zero means no cluster;
     * other values pack a codepoint offset in the high 32 bits and a codepoint
     * length in the low 32 bits.
     */
    var clusterRefs: LongArray = LongArray(0)
        private set

    /**
     * Packed codepoint storage referenced by [clusterRefs].
     */
    var clusterCodepoints: IntArray = IntArray(0)
        private set

    /**
     * Cached per-row render generations.
     */
    var lineGenerations: LongArray = LongArray(0)
        private set

    /**
     * Cached per-row soft-wrap flags.
     */
    var lineWrapped: BooleanArray = BooleanArray(0)
        private set

    /**
     * Cached per-row marker for visible cells carrying the SGR blink attribute.
     *
     * Renderers use this metadata to schedule blink-phase repaints without
     * scanning attribute words on timer ticks.
     */
    var lineHasBlinkingText: BooleanArray = BooleanArray(0)
        private set

    /**
     * Whether any copied visible row contains SGR blinking text.
     */
    var hasBlinkingText: Boolean = false
        private set

    /**
     * Last copied frame generation.
     */
    var frameGeneration: Long = UNINITIALIZED_GENERATION
        private set

    /**
     * Last copied structure generation.
     */
    var structureGeneration: Long = UNINITIALIZED_GENERATION
        private set

    /**
     * Last copied active buffer kind.
     */
    var activeBuffer: TerminalRenderBufferKind = TerminalRenderBufferKind.PRIMARY
        private set

    /**
     * Last copied color palette.
     */
    var palette: TerminalColorPalette = TerminalColorPalette()
        private set

    /**
     * Last copied cursor column.
     */
    var cursorColumn: Int = 0
        private set

    /**
     * Last copied cursor row.
     */
    var cursorRow: Int = 0
        private set

    /**
     * Last copied cursor visibility.
     */
    var cursorVisible: Boolean = false
        private set

    /**
     * Last copied cursor blinking mode.
     */
    var cursorBlinking: Boolean = false
        private set

    /**
     * Last copied cursor shape.
     */
    var cursorShape: TerminalRenderCursorShape = TerminalRenderCursorShape.BLOCK
        private set

    /**
     * Last copied cursor generation.
     */
    var cursorGeneration: Long = UNINITIALIZED_GENERATION
        private set

    /**
     * Convenience cursor snapshot for tests and compatibility callers.
     *
     * Hot render paths should consume primitive cursor fields directly to avoid
     * allocating a value object on every frame.
     */
    val cursor: TerminalRenderCursor?
        get() {
            if (!hasCursor) return null
            return TerminalRenderCursor(
                column = cursorColumn,
                row = cursorRow,
                visible = cursorVisible,
                blinking = cursorBlinking,
                shape = cursorShape,
                generation = cursorGeneration,
            )
        }

    /**
     * Whether the most recent [updateFrom] call resized the primitive storage.
     */
    var resizedOnLastUpdate: Boolean = false
        private set

    /**
     * Whether the most recent [updateFrom] call changed the cursor state.
     */
    var cursorChangedOnLastUpdate: Boolean = false
        private set

    private var clusterCodepointCount: Int = 0
    private var nextClusterCodepoints: IntArray = IntArray(INITIAL_CLUSTER_CODEPOINT_CAPACITY)
    private var nextClusterCodepointCount: Int = 0
    private var clusterSinkRow: Int = NO_CLUSTER_SINK_ROW
    private var clusterSinkRefs: LongArray = LongArray(0)
    private var hasCursor: Boolean = false
    private var nextCursorColumn: Int = 0
    private var nextCursorRow: Int = 0
    private var nextCursorVisible: Boolean = false
    private var nextCursorBlinking: Boolean = false
    private var nextCursorShape: TerminalRenderCursorShape = TerminalRenderCursorShape.BLOCK
    private var nextCursorGeneration: Long = UNINITIALIZED_GENERATION

    /**
     * Reused sink to avoid allocating one capturing lambda per copied row.
     *
     * [clusterSinkRow] is set immediately before [TerminalRenderFrame.copyLine]
     * and reset immediately after it. This object must not escape this cache.
     */
    private val reusableClusterDataSink =
        TerminalRenderClusterDataSink { column, codepoints, offset, length ->
            val row = clusterSinkRow
            val copiedRefs = clusterSinkRefs
            check(row != NO_CLUSTER_SINK_ROW) {
                "TerminalRenderClusterDataSink invoked outside copyLine"
            }
            check(row in 0 until this@TerminalRenderCache.rows) {
                "Cluster sink row out of bounds: row=$row, rows=${this@TerminalRenderCache.rows}"
            }
            check(column in 0 until this@TerminalRenderCache.columns) {
                "Cluster sink column out of bounds: column=$column, columns=${this@TerminalRenderCache.columns}"
            }

            copiedRefs[rowOffset(row) + column] = appendNextCluster(codepoints, offset, length)
        }

    /**
     * Reused sink to copy cursor primitives without allocating a cursor object.
     */
    private val reusableCursorSink =
        TerminalRenderCursorSink { column, row, visible, blinking, shape, generation ->
            nextCursorColumn = column
            nextCursorRow = row
            nextCursorVisible = visible
            nextCursorBlinking = blinking
            nextCursorShape = shape
            nextCursorGeneration = generation
        }

    init {
        require(columns > 0) { "columns must be > 0, was $columns" }
        require(rows > 0) { "rows must be > 0, was $rows" }
        resizeStorage(columns, rows)
    }

    /**
     * Copies changed rows and cursor state from [reader].
     *
     * The read callback is used only to copy primitive frame data into this
     * cache. After this method returns, renderers should build glyph or text
     * runs from the copied rows and paint from their own backend-specific state.
     *
     * @param reader source of the short-lived render frame.
     */
    fun updateFrom(reader: TerminalRenderFrameReader) {
        updateFrom(reader, scrollbackOffset = 0)
    }

    /**
     * Copies changed rows and cursor state for a caller-owned scrollback
     * viewport.
     *
     * [scrollbackOffset] is requested in lines from the live bottom viewport.
     * The reader clamps it to available history; this cache stores the resolved
     * value reported by the frame and treats changes to it as row-mapping
     * changes even when line generations happen to match.
     *
     * @param reader source of the short-lived render frame.
     * @param scrollbackOffset requested lines above the live bottom viewport.
     */
    fun updateFrom(
        reader: TerminalRenderFrameReader,
        scrollbackOffset: Int,
    ) {
        updateFrom(reader, scrollbackOffset, viewportRows = 0)
    }

    /**
     * Copies changed rows and cursor state for a caller-owned scrollback
     * viewport with optional render-only overscan rows.
     *
     * A [viewportRows] value greater than zero asks the reader for that many
     * rows without resizing terminal state. Readers clamp the resolved count
     * before exposing [TerminalRenderFrame.rows], and this cache resizes only to
     * the resolved frame shape.
     *
     * @param reader source of the short-lived render frame.
     * @param scrollbackOffset requested lines above the live bottom viewport.
     * @param viewportRows requested render rows, or zero for the reader default.
     */
    fun updateFrom(
        reader: TerminalRenderFrameReader,
        scrollbackOffset: Int,
        viewportRows: Int,
    ) {
        if (viewportRows > 0) {
            reader.readRenderFrame(scrollbackOffset, viewportRows, this)
        } else {
            reader.readRenderFrame(scrollbackOffset, this)
        }
    }

    /**
     * Copies row data, cursor state, active buffer kind, and color palette
     * from the given [frame] into this cache's primitive storage.
     *
     * @param frame the short-lived render frame snapshot to copy from.
     */
    override fun accept(frame: TerminalRenderFrame) {
        resizedOnLastUpdate = false

        if (columns != frame.columns || rows != frame.rows) {
            resizeStorage(frame.columns, frame.rows)
            resizedOnLastUpdate = true
            structureGeneration = UNINITIALIZED_GENERATION
        }

        cursorChangedOnLastUpdate = false

        val viewportChanged = this.scrollbackOffset != frame.scrollbackOffset
        val structureChanged = structureGeneration != frame.structureGeneration || viewportChanged
        if (structureChanged) {
            clearAllClusters()
        }
        beginClusterCopy()

        var row = 0
        var nextHasBlinkingText = false
        while (row < frame.rows) {
            val lineGeneration = frame.lineGeneration(row)
            val wrapped = frame.lineWrapped(row)

            if (
                structureChanged ||
                lineGenerations[row] != lineGeneration ||
                lineWrapped[row] != wrapped
            ) {
                clearClusterRow(row)

                val offset = rowOffset(row)
                clusterSinkRow = row
                clusterSinkRefs = clusterRefs
                try {
                    frame.copyLine(
                        row = row,
                        codeWords = codeWords,
                        codeOffset = offset,
                        attrWords = attrWords,
                        attrOffset = offset,
                        flags = flags,
                        flagOffset = offset,
                        extraAttrWords = extraAttrWords,
                        extraAttrOffset = offset,
                        hyperlinkIds = hyperlinkIds,
                        hyperlinkOffset = offset,
                        clusterDataSink = reusableClusterDataSink,
                    )
                } finally {
                    clusterSinkRow = NO_CLUSTER_SINK_ROW
                    clusterSinkRefs = EMPTY_LONG_REFS
                }

                lineGenerations[row] = lineGeneration
                lineWrapped[row] = wrapped
                lineHasBlinkingText[row] = rowHasBlinkingText(offset)
            } else {
                preserveClusterRow(row)
            }

            if (lineHasBlinkingText[row]) nextHasBlinkingText = true
            row++
        }
        finishClusterCopy()
        hasBlinkingText = nextHasBlinkingText

        activeBuffer = frame.activeBuffer
        palette = frame.palette

        copyCursorFrom(frame)

        historySize = frame.historySize
        this.scrollbackOffset = frame.scrollbackOffset
        frameGeneration = frame.frameGeneration
        structureGeneration = frame.structureGeneration
        discardedCount = frame.discardedCount
    }

    private fun resizeStorage(
        newColumns: Int,
        newRows: Int,
    ) {
        require(newColumns > 0) { "columns must be > 0, was $newColumns" }
        require(newRows > 0) { "rows must be > 0, was $newRows" }

        columns = newColumns
        rows = newRows

        require(newColumns <= Int.MAX_VALUE / newRows) {
            "cell count overflows Int: columns=$newColumns, rows=$newRows"
        }
        val cellCount = newColumns * newRows
        codeWords = IntArray(cellCount)
        attrWords = LongArray(cellCount)
        flags = IntArray(cellCount)
        extraAttrWords = LongArray(cellCount)
        hyperlinkIds = IntArray(cellCount)
        clusterRefs = LongArray(cellCount)
        clusterCodepoints = IntArray(0)
        clusterCodepointCount = 0
        nextClusterCodepointCount = 0

        lineGenerations = LongArray(newRows) { UNINITIALIZED_GENERATION }
        lineWrapped = BooleanArray(newRows)
        lineHasBlinkingText = BooleanArray(newRows)
        hasBlinkingText = false

        hasCursor = false
        cursorColumn = 0
        cursorRow = 0
        cursorVisible = false
        cursorBlinking = false
        cursorShape = TerminalRenderCursorShape.BLOCK
        cursorGeneration = UNINITIALIZED_GENERATION
        historySize = 0
        scrollbackOffset = 0
        discardedCount = 0L
        frameGeneration = UNINITIALIZED_GENERATION
        structureGeneration = UNINITIALIZED_GENERATION
        activeBuffer = TerminalRenderBufferKind.PRIMARY
        cursorChangedOnLastUpdate = false
        clusterSinkRow = NO_CLUSTER_SINK_ROW
        clusterSinkRefs = EMPTY_LONG_REFS
    }

    private fun rowHasBlinkingText(offset: Int): Boolean {
        val end = offset + columns
        var index = offset
        while (index < end) {
            if (
                flags[index] != TerminalRenderCellFlags.EMPTY &&
                TerminalRenderAttrs.isBlink(attrWords[index])
            ) {
                return true
            }
            index++
        }
        return false
    }

    private fun copyCursorFrom(frame: TerminalRenderFrame) {
        val oldHasCursor = hasCursor
        val oldCursorColumn = cursorColumn
        val oldCursorRow = cursorRow
        val oldCursorVisible = cursorVisible
        val oldCursorBlinking = cursorBlinking
        val oldCursorShape = cursorShape
        val oldCursorGeneration = cursorGeneration

        frame.copyCursor(reusableCursorSink)

        cursorChangedOnLastUpdate = !oldHasCursor ||
            oldCursorColumn != nextCursorColumn ||
            oldCursorRow != nextCursorRow ||
            oldCursorVisible != nextCursorVisible ||
            oldCursorBlinking != nextCursorBlinking ||
            oldCursorShape != nextCursorShape ||
            oldCursorGeneration != nextCursorGeneration

        hasCursor = true
        cursorColumn = nextCursorColumn
        cursorRow = nextCursorRow
        cursorVisible = nextCursorVisible
        cursorBlinking = nextCursorBlinking
        cursorShape = nextCursorShape
        cursorGeneration = nextCursorGeneration
    }

    private fun clearAllClusters() {
        var row = 0
        while (row < rows) {
            clearClusterRow(row)
            row++
        }
    }

    private fun clearClusterRow(row: Int) {
        val start = rowOffset(row)
        clusterRefs.fill(NO_CLUSTER_REF, start, start + columns)
    }

    private fun beginClusterCopy() {
        nextClusterCodepointCount = 0
    }

    private fun finishClusterCopy() {
        val previous = clusterCodepoints
        clusterCodepoints = nextClusterCodepoints
        clusterCodepointCount = nextClusterCodepointCount
        nextClusterCodepoints = previous
        nextClusterCodepointCount = 0
    }

    private fun preserveClusterRow(row: Int) {
        val start = rowOffset(row)
        val end = start + columns
        var index = start
        while (index < end) {
            val ref = clusterRefs[index]
            if (ref != NO_CLUSTER_REF) {
                val offset = clusterOffset(ref)
                val length = clusterLength(ref)
                clusterRefs[index] = appendNextCluster(clusterCodepoints, offset, length)
            }
            index++
        }
    }

    private fun appendNextCluster(
        codepoints: IntArray,
        offset: Int,
        length: Int,
    ): Long {
        require(length > 0) { "cluster length must be > 0, was $length" }
        require(offset >= 0) { "cluster offset must be >= 0, was $offset" }

        val copiedLength = minOf(length, MAX_CLUSTER_CODEPOINTS)
        require(codepoints.size - offset >= copiedLength) {
            "cluster source has insufficient capacity: size=${codepoints.size}, offset=$offset, length=$copiedLength"
        }
        require(nextClusterCodepointCount <= Int.MAX_VALUE - copiedLength) {
            "cluster codepoint count overflows Int: current=$nextClusterCodepointCount, length=$copiedLength"
        }

        ensureNextClusterCapacity(nextClusterCodepointCount + copiedLength)
        val nextOffset = nextClusterCodepointCount
        System.arraycopy(codepoints, offset, nextClusterCodepoints, nextOffset, copiedLength)
        nextClusterCodepointCount += copiedLength
        return packClusterRef(nextOffset, copiedLength)
    }

    private fun ensureNextClusterCapacity(required: Int) {
        if (required <= nextClusterCodepoints.size) return

        require(nextClusterCodepoints.size <= Int.MAX_VALUE / 2) {
            "cluster codepoint capacity overflows Int: required=$required"
        }
        var newCapacity = maxOf(INITIAL_CLUSTER_CODEPOINT_CAPACITY, nextClusterCodepoints.size * 2)
        while (newCapacity < required) {
            require(newCapacity <= Int.MAX_VALUE / 2) {
                "cluster codepoint capacity overflows Int: required=$required"
            }
            newCapacity *= 2
        }
        nextClusterCodepoints = nextClusterCodepoints.copyOf(newCapacity)
    }

    /**
     * Returns cluster text for diagnostics or compatibility callers.
     *
     * Rendering should consume [clusterRefs] and [clusterCodepoints] directly so
     * repeated paint passes do not allocate strings for cache hits.
     *
     * @param row zero-based row index.
     * @param column zero-based column index.
     * @return the string representing the grapheme cluster, or null if no cluster exists.
     */
    fun clusterText(
        row: Int,
        column: Int,
    ): String? {
        val ref = clusterRefs[rowOffset(row) + column]
        if (ref == NO_CLUSTER_REF) return null
        return String(clusterCodepoints, clusterOffset(ref), clusterLength(ref))
    }

    /**
     * Returns the row-major base offset for [row] in flattened cell planes.
     *
     * @param row zero-based row index.
     * @return the flat index pointing to the start of the row.
     */
    fun rowOffset(row: Int): Int = row * columns

    /**
     * Returns the codepoint offset encoded in [ref].
     *
     * @param ref packed cluster reference.
     * @return the starting index of the cluster in [clusterCodepoints].
     */
    fun clusterOffset(ref: Long): Int = (ref ushr 32).toInt()

    /**
     * Returns the codepoint length encoded in [ref].
     *
     * @param ref packed cluster reference.
     * @return the number of codepoints in the cluster.
     */
    fun clusterLength(ref: Long): Int = (ref and 0xFFFF_FFFFL).toInt()

    private companion object {
        private const val UNINITIALIZED_GENERATION = -1L
        private const val NO_CLUSTER_SINK_ROW = -1
        private const val NO_CLUSTER_REF = 0L
        private const val INITIAL_CLUSTER_CODEPOINT_CAPACITY = 256

        // Cache-side defense: parser/core normally cap clusters much lower, but
        // render cache must not let a malformed frame dictate unbounded arrays.
        private const val MAX_CLUSTER_CODEPOINTS = 256

        private val EMPTY_LONG_REFS = LongArray(0)

        private fun packClusterRef(
            offset: Int,
            length: Int,
        ): Long = (offset.toLong() shl 32) or (length.toLong() and 0xFFFF_FFFFL)
    }
}
