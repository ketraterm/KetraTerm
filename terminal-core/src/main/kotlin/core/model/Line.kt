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
package com.gagik.core.model

import com.gagik.core.api.TerminalLineApi
import com.gagik.core.store.ClusterStore

/**
 * A mutable physical terminal line backed by primitive arrays.
 *
 * ## Cell encoding
 *
 * Each column is represented by parallel primitive values:
 * - `codepoints[col]` — the raw storage value (see [TerminalConstants] for the full encoding)
 * - `attrs[col]`      — the primary packed cell attribute
 * - `extendedAttrs[col]` — the extended packed cell attribute
 *
 * wrapped=true means this line is a soft continuation of the previous line
 * caused by wrapping at the terminal width.
 *
 * ## Ownership
 *
 * [store] is shared by all lines belonging to the same [com.gagik.core.buffer.HistoryRing].
 * Lines must never be transferred to a ring that uses a different store without
 * deep-copying their cluster payloads (see [com.gagik.core.engine.TerminalResizer]).
 *
 * ## Mutability
 *
 * All mutation methods are `internal` and called exclusively by
 * [com.gagik.core.engine.MutationEngine]. The [TerminalLineApi] surface exposed to
 * the renderer is strictly read-only.
 *
 * @param width The number of columns in this line. Immutable after construction.
 * @param store The [ClusterStore] that owns cluster payloads for this line's ring.
 */
internal class Line(
    override val width: Int,
    internal val store: ClusterStore,
) : TerminalLineApi {
    init {
        require(width > 0) { "Line width must be positive, got $width" }
    }

    /** Raw storage array. May contain codepoints, EMPTY, WIDE_CHAR_SPACER, or cluster handles. */
    private val codepoints = IntArray(width) { TerminalConstants.EMPTY }

    /** Primary packed cell attributes, parallel to [codepoints]. */
    private val attrs = LongArray(width)

    /** Extended packed cell attributes, parallel to [codepoints]. */
    private val extendedAttrs = LongArray(width)

    /**
     * True when this line's content continues on the next physical line.
     * Set by [com.gagik.core.engine.MutationEngine] during soft-wrap events.
     */
    var wrapped: Boolean = false

    /**
     * Visual generation for this physical line.
     *
     * Updated by terminal state mutation helpers when content, attributes,
     * cluster payloads, hyperlink identifiers, or the wrap flag changes.
     */
    var renderGeneration: Long = 0L
        internal set

    // Internal raw accessors: used by GridWriter and TerminalResizer only

    /**
     * Returns the raw value stored at [col] without any decoding.
     * May return a plain codepoint, [TerminalConstants.EMPTY],
     * [TerminalConstants.WIDE_CHAR_SPACER], or a cluster handle (<= -2).
     *
     * @param col Column index.
     */
    fun rawCodepoint(col: Int): Int = codepoints[col]

    /**
     * Writes [raw] and [attr] directly into [col] without allocating or freeing any
     * cluster handle. Used by [com.gagik.core.engine.TerminalResizer] to transplant
     * raw values (including live cluster handles) into a newly allocated line that
     * shares the same [store].
     *
     * **Caller is responsible** for ensuring the handle is valid in [store].
     *
     * @param col Column index.
     */
    fun setRawCell(
        col: Int,
        raw: Int,
        attr: Long,
        extendedAttr: Long = 0L,
    ) {
        codepoints[col] = raw
        attrs[col] = attr
        extendedAttrs[col] = extendedAttr
    }

    // TerminalLineApi — public read-only surface

    /**
     * Returns the base (first) codepoint for the cell at [col].
     *
     * For cluster cells the leading codepoint of the grapheme sequence is returned,
     * enabling simple renderers to draw one glyph without knowing about clusters.
     *
     * @param col Column index.
     */
    override fun getCodepoint(col: Int): Int {
        val raw = codepoints[col]
        return if (raw <= TerminalConstants.CLUSTER_HANDLE_MAX) store.baseCodepoint(raw) else raw
    }

    /**
     * Returns the packed attribute for the cell at [col].
     *
     * @param col Column index.
     */
    override fun getPackedAttr(col: Int): Long = attrs[col]

    override fun getPackedExtendedAttr(col: Int): Long = extendedAttrs[col]

    /**
     * Returns `true` if [col] holds a multi-codepoint grapheme cluster.
     * Shape-aware renderers should call [readCluster] for such cells.
     *
     * @param col Column index.
     */
    override fun isCluster(col: Int): Boolean = codepoints[col] <= TerminalConstants.CLUSTER_HANDLE_MAX

    /**
     * Copies the full codepoint sequence of the cluster at [col] into [dest].
     * Returns the number of codepoints written, or 0 if the cell is not a cluster.
     *
     * Zero-allocation: no heap objects are created by this method.
     *
     * @param col  Column index.
     * @param dest Destination array. Must have capacity >= actual cluster length.
     */
    override fun readCluster(
        col: Int,
        dest: IntArray,
    ): Int {
        val raw = codepoints[col]
        if (raw > TerminalConstants.CLUSTER_HANDLE_MAX) return 0
        return store.readInto(raw, dest)
    }

    // Internal mutation — called exclusively by GridWriter

    /**
     * Writes a single codepoint into [col], freeing any cluster handle previously
     * stored there. This is the standard write path for all non-cluster cells.
     */
    fun setCell(
        col: Int,
        codepoint: Int,
        attr: Long,
        extendedAttr: Long = 0L,
    ) {
        freeHandleAt(col)
        codepoints[col] = codepoint
        attrs[col] = attr
        extendedAttrs[col] = extendedAttr
    }

    /**
     * Writes a grapheme cluster into [col] by allocating a slot in [store] and
     * storing the resulting handle. Any previous value (including another cluster
     * handle) at [col] is freed first.
     *
     * @param col    Target column.
     * @param cps    Source array of codepoints. Not retained after this call.
     * @param cpLen  Number of valid codepoints in [cps].
     * @param attr   Packed cell attribute.
     */
    fun setCluster(
        col: Int,
        cps: IntArray,
        cpLen: Int,
        attr: Long,
        extendedAttr: Long = 0L,
    ) {
        freeHandleAt(col)
        codepoints[col] = store.alloc(cps, 0, cpLen)
        attrs[col] = attr
        extendedAttrs[col] = extendedAttr
    }

    /**
     * Clears all cells to [defaultAttr] and frees every cluster handle in the line.
     * Also resets the [wrapped] flag.
     */
    fun clear(
        defaultAttr: Long,
        defaultExtendedAttr: Long = 0L,
    ) {
        store.freeRange(codepoints, 0, width)
        codepoints.fill(TerminalConstants.EMPTY)
        attrs.fill(defaultAttr)
        extendedAttrs.fill(defaultExtendedAttr)
        wrapped = false
    }

    /**
     * Clears cells from [startCol] (inclusive) to the end of the line,
     * freeing any cluster handles in that range.
     */
    fun clearFromColumn(
        startCol: Int,
        attr: Long,
        extendedAttr: Long = 0L,
    ) {
        val from = startCol.coerceAtLeast(0)
        if (from >= width) return
        store.freeRange(codepoints, from, width)
        codepoints.fill(TerminalConstants.EMPTY, from, width)
        attrs.fill(attr, from, width)
        extendedAttrs.fill(extendedAttr, from, width)
    }

    /**
     * Clears cells from the start of the line through [endCol] (inclusive),
     * freeing any cluster handles in that range.
     */
    fun clearToColumn(
        endCol: Int,
        attr: Long,
        extendedAttr: Long = 0L,
    ) {
        val to = (endCol + 1).coerceAtMost(width)
        if (to <= 0) return
        store.freeRange(codepoints, 0, to)
        codepoints.fill(TerminalConstants.EMPTY, 0, to)
        attrs.fill(attr, 0, to)
        extendedAttrs.fill(extendedAttr, 0, to)
    }

    /**
     * Clears cells in `[startCol, endExclusive)`, freeing any cluster handles in
     * that range and leaving cells outside the range untouched.
     */
    fun clearRange(
        startCol: Int,
        endExclusive: Int,
        attr: Long,
        extendedAttr: Long = 0L,
    ) {
        val from = startCol.coerceIn(0, width)
        val to = endExclusive.coerceIn(0, width)
        if (from >= to) return
        store.freeRange(codepoints, from, to)
        codepoints.fill(TerminalConstants.EMPTY, from, to)
        attrs.fill(attr, from, to)
        extendedAttrs.fill(extendedAttr, from, to)
    }

    /**
     * Inserts [count] blank cells at [col], shifting existing content to the right.
     * Cells shifted off the right edge are freed (cluster handles are released).
     * The wide-cluster leader at [col] must be annihilated by the caller before
     * this method is invoked.
     */
    fun insertCells(
        col: Int,
        count: Int,
        defaultAttr: Long,
        defaultExtendedAttr: Long = 0L,
    ) {
        insertCellsInRange(col, count, width - 1, defaultAttr, defaultExtendedAttr)
    }

    /**
     * Inserts [count] blank cells at [col], shifting existing content to the
     * right within `[col, rightInclusive]` only.
     */
    fun insertCellsInRange(
        col: Int,
        count: Int,
        rightInclusive: Int,
        defaultAttr: Long,
        defaultExtendedAttr: Long = 0L,
    ) {
        if (isInvalidRange(col, count, rightInclusive)) return

        val safeCount = count.coerceAtMost(rightInclusive - col + 1)
        val shiftCount = rightInclusive - col + 1 - safeCount
        val clearStart = rightInclusive - safeCount + 1

        // Free handles that will fall off the active range.
        store.freeRange(codepoints, clearStart, rightInclusive + 1)

        if (shiftCount > 0) {
            System.arraycopy(codepoints, col, codepoints, col + safeCount, shiftCount)
            System.arraycopy(attrs, col, attrs, col + safeCount, shiftCount)
            System.arraycopy(extendedAttrs, col, extendedAttrs, col + safeCount, shiftCount)
        }
        codepoints.fill(TerminalConstants.EMPTY, col, col + safeCount)
        attrs.fill(defaultAttr, col, col + safeCount)
        extendedAttrs.fill(defaultExtendedAttr, col, col + safeCount)
    }

    /**
     * Deletes [count] cells starting at [col], shifting the tail of the line
     * left to fill the gap. The [count] trailing cells are then overwritten with
     * [TerminalConstants.EMPTY] codepoints and [defaultAttr] attributes.
     *
     * [count] is clamped to the number of cells remaining from [col] to the end
     * of the line, so callers do not need to pre-clamp.
     *
     * @param col        First cell to delete (0-based). Out-of-range values are ignored.
     * @param count      Number of cells to delete. Non-positive values are ignored.
     * @param defaultAttr Packed attribute used to fill the vacated trailing cells.
     */
    fun deleteCells(
        col: Int,
        count: Int,
        defaultAttr: Long,
        defaultExtendedAttr: Long = 0L,
    ) {
        deleteCellsInRange(col, count, width - 1, defaultAttr, defaultExtendedAttr)
    }

    /**
     * Deletes [count] cells at [col], shifting surviving content left within
     * `[col, rightInclusive]` only.
     */
    fun deleteCellsInRange(
        col: Int,
        count: Int,
        rightInclusive: Int,
        defaultAttr: Long,
        defaultExtendedAttr: Long = 0L,
    ) {
        if (isInvalidRange(col, count, rightInclusive)) return

        val safeCount = count.coerceAtMost(rightInclusive - col + 1)
        val shiftCount = rightInclusive - col + 1 - safeCount

        // Free cluster handles for the cells being deleted before the shift overwrites them.
        store.freeRange(codepoints, col, col + safeCount)

        // Shift surviving cells left to close the gap.
        if (shiftCount > 0) {
            System.arraycopy(codepoints, col + safeCount, codepoints, col, shiftCount)
            System.arraycopy(attrs, col + safeCount, attrs, col, shiftCount)
            System.arraycopy(extendedAttrs, col + safeCount, extendedAttrs, col, shiftCount)
        }

        // Fill the vacated trailing cells with blanks.
        // Do NOT call store.freeRange() here: the cluster handles that previously
        // occupied these slots were shifted left above and are still live.
        val clearStart = rightInclusive - safeCount + 1
        codepoints.fill(TerminalConstants.EMPTY, clearStart, rightInclusive + 1)
        attrs.fill(defaultAttr, clearStart, rightInclusive + 1)
        extendedAttrs.fill(defaultExtendedAttr, clearStart, rightInclusive + 1)
    }

    /**
     * Fills every cell with [codepoint] and [attr], freeing all cluster handles first.
     * Used for bulk background-color fills (e.g. erase-display operations).
     */
    fun fill(
        codepoint: Int,
        attr: Long,
        extendedAttr: Long = 0L,
    ) {
        store.freeRange(codepoints, 0, width)
        codepoints.fill(codepoint)
        attrs.fill(attr)
        extendedAttrs.fill(extendedAttr)
    }

    // -------------------------------------------------------------------------
    // Text rendering helpers — allocating, not for use in render loops
    // -------------------------------------------------------------------------

    /**
     * Renders the full line as a [String], including trailing spaces.
     * Wide-char spacer cells are omitted (the leader already contributed its glyph).
     * Intended for debugging and tests only.
     */
    fun toText(): String =
        buildString(width) {
            for (col in 0 until width) {
                appendCell(col)
            }
        }

    /**
     * Renders the line as a [String], trimming trailing blank cells.
     * Intended for scrollback serialisation, debugging, and tests.
     */
    fun toTextTrimmed(): String {
        var last = width - 1
        while (last >= 0 && codepoints[last] == TerminalConstants.EMPTY) last--
        if (last < 0) return ""
        return buildString(last + 1) {
            for (col in 0..last) appendCell(col)
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Appends the glyph(s) at [col] to [this] [StringBuilder].
     * Cluster cells emit all codepoints in sequence; spacer cells are skipped.
     */
    private fun StringBuilder.appendCell(col: Int) {
        val raw = codepoints[col]
        when {
            raw == TerminalConstants.EMPTY -> append(' ')
            raw == TerminalConstants.WIDE_CHAR_SPACER -> Unit // skip; leader already appended
            raw <= TerminalConstants.CLUSTER_HANDLE_MAX -> {
                val len = store.length(raw)
                for (i in 0 until len) appendCodePoint(store.codepointAt(raw, i))
            }
            else -> appendCodePoint(raw)
        }
    }

    /** Frees the cluster handle at [col] if present; no-op otherwise. */
    private fun freeHandleAt(col: Int) {
        val raw = codepoints[col]
        if (raw <= TerminalConstants.CLUSTER_HANDLE_MAX) store.free(raw)
    }

    @Suppress("NOTHING_TO_INLINE")
    private inline fun isInvalidRange(
        col: Int,
        count: Int,
        rightInclusive: Int,
    ): Boolean = col !in 0 until width || count <= 0 || rightInclusive !in 0 until width || col > rightInclusive
}

/**
 * A read-only, zero-allocation sentinel returned by [com.gagik.terminal.buffer.TerminalBufferApi.getLine]
 * when the requested row is out of bounds. Avoids null checks in the renderer.
 */
internal object VoidLine : TerminalLineApi {
    override val width: Int = 0

    override fun getCodepoint(col: Int): Int = TerminalConstants.EMPTY

    override fun getPackedAttr(col: Int): Long = 0

    override fun getPackedExtendedAttr(col: Int): Long = 0

    override fun isCluster(col: Int): Boolean = false

    override fun readCluster(
        col: Int,
        dest: IntArray,
    ): Int = 0
}
