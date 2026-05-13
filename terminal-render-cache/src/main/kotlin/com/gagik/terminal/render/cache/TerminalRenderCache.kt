package com.gagik.terminal.render.cache

import com.gagik.terminal.render.api.TerminalRenderBufferKind
import com.gagik.terminal.render.api.TerminalRenderClusterSink
import com.gagik.terminal.render.api.TerminalRenderCursor
import com.gagik.terminal.render.api.TerminalRenderFrameReader

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
) {
    @Volatile
    private var ownerThread: Thread? = null

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
     * Copied row code words. See [TerminalRenderFrame.copyLine].
     */
    var codeWords: Array<IntArray> = emptyIntRows()
        private set

    /**
     * Copied primary public render attribute words.
     */
    var attrWords: Array<LongArray> = emptyLongRows()
        private set

    /**
     * Copied public render cell flags.
     */
    var flags: Array<IntArray> = emptyIntRows()
        private set

    /**
     * Copied optional public extra-attribute words.
     */
    var extraAttrWords: Array<LongArray> = emptyLongRows()
        private set

    /**
     * Copied optional hyperlink identifiers. Zero means no hyperlink.
     */
    var hyperlinkIds: Array<IntArray> = emptyIntRows()
        private set

    /**
     * Copied cluster text by row and column. Non-cluster cells contain `null`.
     */
    var clusters: Array<Array<String?>> = emptyClusterRows()
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
     * Last copied cursor state.
     */
    var cursor: TerminalRenderCursor? = null
        private set

    /**
     * Rows copied by the most recent [updateFrom] call.
     *
     * Renderers can consume this and mark only those row layout caches dirty.
     * The array instance is stable until resize and its contents are overwritten
     * on each update.
     */
    var dirtyRows: BooleanArray = BooleanArray(0)
        private set

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

    private var clusterSinkRow: Int = NO_CLUSTER_SINK_ROW

    /**
     * Reused sink to avoid allocating one capturing lambda per copied row.
     *
     * [clusterSinkRow] is set immediately before [TerminalRenderFrame.copyLine]
     * and reset immediately after it. This object must not escape this cache.
     */
    private val reusableClusterSink = TerminalRenderClusterSink { column, text ->
        val row = clusterSinkRow
        check(row != NO_CLUSTER_SINK_ROW) {
            "TerminalRenderClusterSink invoked outside copyLine"
        }
        check(row in 0 until rows) {
            "Cluster sink row out of bounds: row=$row, rows=$rows"
        }
        check(column in 0 until columns) {
            "Cluster sink column out of bounds: column=$column, columns=$columns"
        }

        clusters[row][column] = text
    }

    init {
        require(columns > 0) { "columns must be > 0, was $columns" }
        require(rows > 0) { "rows must be > 0, was $rows" }
        resizeStorage(columns, rows)
    }

    /**
     * Asserts that this cache is owned by the current thread.
     *
     * The first thread to call this method becomes the owner. Subsequent calls
     * from other threads will throw [IllegalStateException].
     */
    fun assertOwnership() {
        val current = Thread.currentThread()
        val owner = ownerThread
        if (owner == null) {
            ownerThread = current
            return
        }
        check(owner == current) {
            "TerminalRenderCache accessed from $current but owned by $owner"
        }
    }

    /**
     * Resizes the primitive storage to [columns] x [rows].
     */
    fun resize(columns: Int, rows: Int) {
        resizeStorage(columns, rows)
    }

    /**
     * Clears thread ownership after external lifecycle changes.
     *
     * Only [TerminalRenderPublisher] should call this after resizing all
     * buffers under its publish lock. The render worker will acquire ownership
     * again on the next [updateFrom].
     */
    internal fun resetOwnership() {
        ownerThread = null
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
    fun updateFrom(reader: TerminalRenderFrameReader, scrollbackOffset: Int) {
        assertOwnership()
        reader.readRenderFrame(scrollbackOffset) { frame ->
            resizedOnLastUpdate = false

            if (columns != frame.columns || rows != frame.rows) {
                resizeStorage(frame.columns, frame.rows)
                resizedOnLastUpdate = true
                structureGeneration = UNINITIALIZED_GENERATION
            }

            dirtyRows.fill(false)
            cursorChangedOnLastUpdate = false

            val viewportChanged = this.scrollbackOffset != frame.scrollbackOffset
            val structureChanged = structureGeneration != frame.structureGeneration || viewportChanged
            if (structureChanged) {
                clearAllClusters()
            }

            var row = 0
            while (row < frame.rows) {
                val lineGeneration = frame.lineGeneration(row)
                val wrapped = frame.lineWrapped(row)

                if (
                    structureChanged ||
                    lineGenerations[row] != lineGeneration ||
                    lineWrapped[row] != wrapped
                ) {
                    clearClusterRow(row)

                    clusterSinkRow = row
                    try {
                        frame.copyLine(
                            row = row,
                            codeWords = codeWords[row],
                            attrWords = attrWords[row],
                            flags = flags[row],
                            extraAttrWords = extraAttrWords[row],
                            hyperlinkIds = hyperlinkIds[row],
                            clusterSink = reusableClusterSink,
                        )
                    } finally {
                        clusterSinkRow = NO_CLUSTER_SINK_ROW
                    }

                    lineGenerations[row] = lineGeneration
                    lineWrapped[row] = wrapped
                    dirtyRows[row] = true
                }

                row++
            }

            activeBuffer = frame.activeBuffer

            val oldCursor = cursor
            val newCursor = frame.cursor
            if (oldCursor != newCursor) {
                cursorChangedOnLastUpdate = true
            }

            cursor = newCursor
            historySize = frame.historySize
            this.scrollbackOffset = frame.scrollbackOffset
            frameGeneration = frame.frameGeneration
            structureGeneration = frame.structureGeneration
        }
    }

    private fun resizeStorage(newColumns: Int, newRows: Int) {
        require(newColumns > 0) { "columns must be > 0, was $newColumns" }
        require(newRows > 0) { "rows must be > 0, was $newRows" }

        columns = newColumns
        rows = newRows

        codeWords = Array(newRows) { IntArray(newColumns) }
        attrWords = Array(newRows) { LongArray(newColumns) }
        flags = Array(newRows) { IntArray(newColumns) }
        extraAttrWords = Array(newRows) { LongArray(newColumns) }
        hyperlinkIds = Array(newRows) { IntArray(newColumns) }
        clusters = Array(newRows) { arrayOfNulls(newColumns) }

        lineGenerations = LongArray(newRows) { UNINITIALIZED_GENERATION }
        lineWrapped = BooleanArray(newRows)
        dirtyRows = BooleanArray(newRows)

        cursor = null
        historySize = 0
        scrollbackOffset = 0
        frameGeneration = UNINITIALIZED_GENERATION
        structureGeneration = UNINITIALIZED_GENERATION
        activeBuffer = TerminalRenderBufferKind.PRIMARY
        cursorChangedOnLastUpdate = false
        clusterSinkRow = NO_CLUSTER_SINK_ROW
    }

    private fun clearAllClusters() {
        var row = 0
        while (row < rows) {
            clearClusterRow(row)
            row++
        }
    }

    private fun clearClusterRow(row: Int) {
        clusters[row].fill(null)
    }

    private companion object {
        private const val UNINITIALIZED_GENERATION = -1L
        private const val NO_CLUSTER_SINK_ROW = -1

        private fun emptyIntRows(): Array<IntArray> = emptyArray()

        private fun emptyLongRows(): Array<LongArray> = emptyArray()

        private fun emptyClusterRows(): Array<Array<String?>> = emptyArray()
    }
}
