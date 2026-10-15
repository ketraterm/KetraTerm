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
package io.github.ketraterm.core.model

import io.github.ketraterm.core.api.TerminalLine
import io.github.ketraterm.protocol.TerminalTextLimits.MAX_CLUSTER_CODEPOINTS
import java.util.*

/**
 * A mutable physical terminal line backed by primitive arrays.
 *
 * ## Cell encoding
 *
 * Each column is represented by parallel primitive values:
 * - `codepoints[col]` — the raw storage value (see [TerminalConstants] for the full encoding)
 * - `attrs[col]`      — the primary packed cell attribute
 * - `extendedAttrs[col]` — the extended packed cell attribute, implicitly zero without storage
 *
 * `wrapped=true` means this line continues into the following physical line
 * because of a soft wrap at the terminal width.
 *
 * ## Ownership
 *
 * Each row owns its cluster payloads. Raw cluster references never cross rows;
 * copies read the source payload and write it into the destination row.
 *
 * ## Mutability
 *
 * This type is internal to core. Writer, screen-lifecycle, and resize code own its mutation;
 * the [TerminalLine] surface exposed to the renderer is strictly read-only.
 *
 * @param width The number of columns in this line. Immutable after construction.
 */
internal class Line(
    override val width: Int,
) : TerminalLine {
    init {
        require(width > 0) { "Line width must be positive, got $width" }
    }

    /** Raw storage array. May contain codepoints, EMPTY, WIDE_CHAR_SPACER, or cluster handles. */
    private val codepoints = IntArray(width) { TerminalConstants.EMPTY }

    /** Primary-packed cell attributes, parallel to [codepoints]. */
    private val attrs = LongArray(width)

    /** Allocated on the first nonzero extended write and retained through clears for reuse. */
    private var extendedAttrs: LongArray? = null

    /** False only when every retained extended word is known to be zero. */
    private var hasExtendedAttrs: Boolean = false

    /** Lazy row-owned payload arena and reusable compaction destination. */
    private var clusterData: IntArray? = null
    private var clusterCompactionData: IntArray? = null
    private var clusterDataSize = 0

    /** Exact live payload size; zero resets the append cursor without scanning cells. */
    private var liveClusterCodepoints = 0

    /**
     * True when this line's content continues on the next physical line.
     * Set by [io.github.ketraterm.core.engine.MutationEngine] during soft-wrap events.
     */
    var wrapped: Boolean = false
        set(value) {
            field = value
            if (!value) endsWithWrapPadding = false
        }

    /**
     * True when writing or reflow inserted an artificial trailing blank cell to keep
     * a wide character from being split across physical rows.
     *
     * Padding can only occupy the last cell of a wrapped physical row. Mutations
     * that replace that cell or remove the soft wrap clear this provenance;
     * changes to earlier columns or visual attributes preserve it.
     */
    var endsWithWrapPadding: Boolean = false

    /**
     * Visual generation for this physical line.
     *
     * Updated by terminal state mutation helpers when content, attributes,
     * cluster payloads, hyperlink identifiers, or the wrap flag changes.
     */
    var renderGeneration: Long = 0L
        internal set

    /**
     * Whether this row has received text or an explicit line feed from the host.
     * Unlike cell emptiness, this preserves authored blank lines. Cursor movement
     * and erasure do not create output; replacing the logical row resets it.
     */
    var hasOutput: Boolean = false

    /**
     * Stable identity of the logical buffer line represented by this physical row.
     *
     * The identifier moves with row content through scroll operations and is
     * preserved by resize reflow so host metadata such as shell integration
     * markers can remain anchored to content without using fragile absolute row
     * numbers. `0` is reserved for unassigned sentinel lines.
     */
    var lineId: Long = 0L
        private set

    /**
     * Assigns a new logical line identity.
     *
     * This is intentionally separate from [clear]: many erase operations clear
     * cell contents while preserving the row's logical identity, whereas scroll
     * and history destruction create fresh logical lines.
     */
    fun assignLineId(lineId: Long) {
        require(lineId > 0L) { "lineId must be positive, was $lineId" }
        this.lineId = lineId
        hasOutput = false
    }

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
     * Replaces only the packed visual attributes at [col], retaining its raw cell value and any
     * row-owned cluster payload. This is the safe attribute-only mutation used by DEC rectangle
     * operations.
     */
    fun setCellAttributes(
        col: Int,
        attr: Long,
        extendedAttr: Long,
    ) {
        attrs[col] = attr
        extendedAttrsForWrite(extendedAttr)?.set(col, extendedAttr)
    }

    // TerminalLine — public read-only surface

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
        return if (raw <= TerminalConstants.CLUSTER_HANDLE_MAX) checkNotNull(clusterData)[clusterOffset(raw)] else raw
    }

    /**
     * Returns the packed attribute for the cell at [col].
     *
     * @param col Column index.
     */
    fun getPackedAttr(col: Int): Long = attrs[col]

    fun getPackedExtendedAttr(col: Int): Long = extendedAttrs?.get(col) ?: 0L

    /**
     * Returns `true` if [col] holds a multi-codepoint grapheme cluster.
     * Shape-aware renderers should call [readCluster] for such cells.
     *
     * @param col Column index.
     */
    override fun isCluster(col: Int): Boolean = codepoints[col] <= TerminalConstants.CLUSTER_HANDLE_MAX

    override fun getClusterLength(col: Int): Int {
        val raw = codepoints[col]
        return if (raw <= TerminalConstants.CLUSTER_HANDLE_MAX) clusterLength(raw) else 0
    }

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
    ): Int = readCluster(col, dest, 0)

    /** Copies a row-owned payload into reusable mutation or reflow scratch storage. */
    fun readCluster(
        col: Int,
        dest: IntArray,
        destOffset: Int,
    ): Int {
        val raw = codepoints[col]
        if (raw > TerminalConstants.CLUSTER_HANDLE_MAX) return 0
        val length = clusterLength(raw)
        Objects.checkFromIndexSize(destOffset, length, dest.size)
        System.arraycopy(checkNotNull(clusterData), clusterOffset(raw), dest, destOffset, length)
        return length
    }

    // Internal mutation

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
        if (col == width - 1) endsWithWrapPadding = false
        freeHandleAt(col)
        codepoints[col] = codepoint
        attrs[col] = attr
        extendedAttrsForWrite(extendedAttr)?.set(col, extendedAttr)
    }

    /**
     * Writes validated printable ASCII over empty or single-width scalar cells.
     * Stops before a cluster, wide leader, or spacer so the caller can clear its full span.
     * The caller bounds [length] to the remaining row width and owns generation/cursor updates.
     * Returns the number written without freeing cluster handles.
     */
    fun writeAsciiIntoSimpleCells(
        col: Int,
        bytes: ByteArray,
        offset: Int,
        length: Int,
        attr: Long,
        extendedAttr: Long,
    ): Int {
        var written = 0
        while (written < length) {
            val target = col + written
            if (codepoints[target] < TerminalConstants.EMPTY ||
                (target + 1 < width && codepoints[target + 1] == TerminalConstants.WIDE_CHAR_SPACER)
            ) {
                break
            }
            codepoints[target] = bytes[offset + written].toInt()
            attrs[target] = attr
            written++
        }
        if (written > 0) fillExtendedAttrs(extendedAttr, col, col + written)
        if (written > 0 && col + written == width) endsWithWrapPadding = false
        return written
    }

    /**
     * Copies a bounded grapheme into this row's arena. Replacements reuse an existing
     * payload region when it fits; other writes append, compacting dead regions as needed.
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
        require(cpLen in 1..MAX_CLUSTER_CODEPOINTS)
        Objects.checkFromIndexSize(0, cpLen, cps.size)
        val old = codepoints[col]
        if (col == width - 1) endsWithWrapPadding = false
        if (old <= TerminalConstants.CLUSTER_HANDLE_MAX && clusterLength(old) >= cpLen) {
            val offset = clusterOffset(old)
            System.arraycopy(cps, 0, checkNotNull(clusterData), offset, cpLen)
            liveClusterCodepoints += cpLen - clusterLength(old)
            codepoints[col] = encodeCluster(offset, cpLen)
        } else {
            freeHandleAt(col)
            // Compaction scans live cells, so remove the obsolete reference first.
            codepoints[col] = TerminalConstants.EMPTY
            val data = reserveClusterData(cpLen)
            System.arraycopy(cps, 0, data, clusterDataSize, cpLen)
            codepoints[col] = encodeCluster(clusterDataSize, cpLen)
            clusterDataSize += cpLen
            liveClusterCodepoints += cpLen
        }
        attrs[col] = attr
        extendedAttrsForWrite(extendedAttr)?.set(col, extendedAttr)
    }

    /**
     * Clears all cells to [defaultAttr] and frees every cluster handle in the line.
     * Also resets the [wrapped] flag.
     */
    fun clear(
        defaultAttr: Long,
        defaultExtendedAttr: Long = 0L,
    ) {
        liveClusterCodepoints = 0
        clusterDataSize = 0
        codepoints.fill(TerminalConstants.EMPTY)
        attrs.fill(defaultAttr)
        fillExtendedAttrs(defaultExtendedAttr)
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
        endsWithWrapPadding = false
        freeHandlesInRange(from, width)
        codepoints.fill(TerminalConstants.EMPTY, from, width)
        attrs.fill(attr, from, width)
        fillExtendedAttrs(extendedAttr, from, width)
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
        if (to == width) endsWithWrapPadding = false
        freeHandlesInRange(0, to)
        codepoints.fill(TerminalConstants.EMPTY, 0, to)
        attrs.fill(attr, 0, to)
        fillExtendedAttrs(extendedAttr, 0, to)
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
        if (to == width) endsWithWrapPadding = false
        freeHandlesInRange(from, to)
        codepoints.fill(TerminalConstants.EMPTY, from, to)
        attrs.fill(attr, from, to)
        fillExtendedAttrs(extendedAttr, from, to)
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
        if (rightInclusive == width - 1) endsWithWrapPadding = false

        val safeCount = count.coerceAtMost(rightInclusive - col + 1)
        val shiftCount = rightInclusive - col + 1 - safeCount
        val clearStart = rightInclusive - safeCount + 1

        // Free handles that will fall off the active range.
        freeHandlesInRange(clearStart, rightInclusive + 1)

        if (shiftCount > 0) {
            System.arraycopy(codepoints, col, codepoints, col + safeCount, shiftCount)
            System.arraycopy(attrs, col, attrs, col + safeCount, shiftCount)
            if (hasExtendedAttrs) extendedAttrs?.let { System.arraycopy(it, col, it, col + safeCount, shiftCount) }
        }
        codepoints.fill(TerminalConstants.EMPTY, col, col + safeCount)
        attrs.fill(defaultAttr, col, col + safeCount)
        fillExtendedAttrs(defaultExtendedAttr, col, col + safeCount)
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
        if (rightInclusive == width - 1) endsWithWrapPadding = false

        val safeCount = count.coerceAtMost(rightInclusive - col + 1)
        val shiftCount = rightInclusive - col + 1 - safeCount

        // Free cluster handles for the cells being deleted before the shift overwrites them.
        freeHandlesInRange(col, col + safeCount)

        // Shift surviving cells left to close the gap.
        if (shiftCount > 0) {
            System.arraycopy(codepoints, col + safeCount, codepoints, col, shiftCount)
            System.arraycopy(attrs, col + safeCount, attrs, col, shiftCount)
            if (hasExtendedAttrs) extendedAttrs?.let { System.arraycopy(it, col + safeCount, it, col, shiftCount) }
        }

        // Fill the vacated trailing cells with blanks.
        // Do NOT free handles here: the cluster handles that previously
        // occupied these slots were shifted left above and are still live.
        val clearStart = rightInclusive - safeCount + 1
        codepoints.fill(TerminalConstants.EMPTY, clearStart, rightInclusive + 1)
        attrs.fill(defaultAttr, clearStart, rightInclusive + 1)
        fillExtendedAttrs(defaultExtendedAttr, clearStart, rightInclusive + 1)
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
        endsWithWrapPadding = false
        liveClusterCodepoints = 0
        clusterDataSize = 0
        codepoints.fill(codepoint)
        attrs.fill(attr)
        fillExtendedAttrs(extendedAttr)
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

    private fun extendedAttrsForWrite(value: Long): LongArray? {
        if (value == 0L && !hasExtendedAttrs) return null
        if (value != 0L) hasExtendedAttrs = true
        val existing = extendedAttrs
        if (existing != null) return existing
        return LongArray(width).also { extendedAttrs = it }
    }

    private fun fillExtendedAttrs(
        value: Long,
        fromIndex: Int = 0,
        toIndex: Int = width,
    ) {
        extendedAttrsForWrite(value)?.fill(value, fromIndex, toIndex)
        if (fromIndex == 0 && toIndex == width) hasExtendedAttrs = value != 0L
    }

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
                val len = clusterLength(raw)
                val data = checkNotNull(clusterData)
                val offset = clusterOffset(raw)
                for (i in 0 until len) appendCodePoint(data[offset + i])
            }
            else -> appendCodePoint(raw)
        }
    }

    /** Marks a payload dead; row-local references need no shared allocator bookkeeping. */
    private fun freeHandleAt(col: Int) {
        val raw = codepoints[col]
        if (raw <= TerminalConstants.CLUSTER_HANDLE_MAX) {
            liveClusterCodepoints -= clusterLength(raw)
            if (liveClusterCodepoints == 0) clusterDataSize = 0
        }
    }

    private fun freeHandlesInRange(
        fromIndex: Int,
        toIndex: Int,
    ) {
        if (liveClusterCodepoints == 0) return
        for (col in fromIndex until toIndex) freeHandleAt(col)
    }

    /**
     * Compacts only on append exhaustion. Keeping at least half the destination free amortizes
     * the row scan under repeated overwrites. Two retained arrays make compaction safe after
     * arbitrary cell shifts and allocation-free once their capacities have warmed up.
     */
    private fun reserveClusterData(length: Int): IntArray {
        val data = clusterData
        if (data != null && length <= data.size - clusterDataSize) return data
        val required = liveClusterCodepoints.toLong() + length
        if (required > MAX_CLUSTER_DATA_CAPACITY) throw OutOfMemoryError("Row cluster storage exhausted")
        val capacity = maxOf(16L, required * 2).coerceAtMost(MAX_CLUSTER_DATA_CAPACITY.toLong()).toInt()
        val spare = clusterCompactionData
        val target = if (spare != null && spare.size >= capacity) spare else IntArray(capacity)
        var position = 0
        if (liveClusterCodepoints != 0) {
            val source = checkNotNull(data)
            for (col in 0 until width) {
                val raw = codepoints[col]
                if (raw > TerminalConstants.CLUSTER_HANDLE_MAX) continue
                val count = clusterLength(raw)
                System.arraycopy(source, clusterOffset(raw), target, position, count)
                codepoints[col] = encodeCluster(position, count)
                position += count
            }
        }
        clusterCompactionData = data
        clusterData = target
        clusterDataSize = position
        return target
    }

    private companion object {
        // Sign bit marks a cluster; bit 30 stays clear, excluding the -1 spacer sentinel.
        // Five low bits encode length - 1; the remaining 25 bits encode a row-local offset.
        const val MAX_CLUSTER_DATA_CAPACITY = 1 shl 25

        fun clusterLength(raw: Int): Int = (raw and 31) + 1

        fun clusterOffset(raw: Int): Int = (raw ushr 5) and (MAX_CLUSTER_DATA_CAPACITY - 1)

        fun encodeCluster(
            offset: Int,
            length: Int,
        ): Int = Int.MIN_VALUE or (offset shl 5) or (length - 1)
    }

    @Suppress("NOTHING_TO_INLINE")
    private inline fun isInvalidRange(
        col: Int,
        count: Int,
        rightInclusive: Int,
    ): Boolean = col !in 0 until width || count <= 0 || rightInclusive !in 0 until width || col > rightInclusive
}

/**
 * A read-only, zero-allocation sentinel returned by [io.github.ketraterm.buffer.TerminalBufferApi.getLine]
 * when the requested row is out of bounds. Avoids null checks in the renderer.
 */
internal object VoidLine : TerminalLine {
    override val width: Int = 0

    override fun getCodepoint(col: Int): Int = TerminalConstants.EMPTY

    override fun isCluster(col: Int): Boolean = false

    override fun readCluster(
        col: Int,
        dest: IntArray,
    ): Int = 0
}
