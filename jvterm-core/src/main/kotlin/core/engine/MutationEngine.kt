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
package com.gagik.core.engine

import com.gagik.core.buffer.HistoryRing
import com.gagik.core.codec.AttributeCodec
import com.gagik.core.model.Line
import com.gagik.core.model.TerminalConstants
import com.gagik.core.state.TerminalState
import com.gagik.core.store.ClusterStore
import com.gagik.core.util.UnicodeWidth

/**
 * Dedicated mutation engine for grid writes and line-level erase/edit operations.
 *
 * Owns all overwrite physics so callers cannot leave orphaned wide-character
 * spacers or partially overwritten clusters.
 *
 * Responsibilities:
 * - printable writes, including deferred-wrap handling
 * - line and screen erase operations
 * - scroll-region mutations (`IL`, `DL`, `SU`, `SD`, `RI`)
 * - cell-shift edits (`ICH`, `DCH`)
 *
 * Non-responsibilities:
 * - grapheme segmentation over a byte/codepoint stream
 * - cursor-addressing policy
 * - direct circular-buffer arithmetic outside [HistoryRing]
 *
 * All ring-index translation is delegated to [TerminalState.resolveRingIndex].
 * All circular-buffer arithmetic is encapsulated inside [HistoryRing].
 */
internal class MutationEngine(
    private val state: TerminalState,
) {
    private val width: Int get() = state.dimensions.width
    private val height: Int get() = state.dimensions.height
    private val leftMargin: Int get() = state.effectiveLeftMargin
    private val rightMargin: Int get() = state.effectiveRightMargin
    private val blankAttr: Long get() = state.pen.blankAttr
    private val blankExtendedAttr: Long get() = state.pen.blankExtendedAttr
    private var clusterScratch = IntArray(16)

    /**
     * Wraps mutations that conceptually break phantom-column state.
     *
     * Deferred wrap survives only until the next printable write. Structural
     * edits and explicit scroll operations must cancel it first.
     */
    private inline fun structuralMutation(block: () -> Unit) {
        state.cancelPendingWrap()
        block()
    }

    /**
     * Returns the mutable [Line] for a viewport row.
     *
     * This is the single source of truth for viewport-to-ring translation.
     */
    private fun getLine(row: Int): Line = state.ring[state.resolveRingIndex(row)]

    private fun markCursorIfMoved(
        oldCol: Int,
        oldRow: Int,
    ) {
        if (state.cursor.col != oldCol || state.cursor.row != oldRow) {
            state.markCursorChanged()
        }
    }

    /**
     * Internal scroll-up primitive that deliberately does not cancel pending wrap.
     *
     * This helper is used both from public structural commands and from
     * printable-write flow via [advanceRow]. The caller is responsible for
     * deciding whether pending-wrap state must survive.
     */
    private fun scrollUpInternal(count: Int = 1) {
        val top = state.scrollTop
        val bottom = state.scrollBottom
        val n = count.coerceIn(0, bottom - top + 1)
        if (n == 0) return

        if (state.isFullViewportScroll) {
            repeat(n) {
                val line = state.ring.push()
                line.clear(blankAttr, blankExtendedAttr)
                state.markLineChanged(line)
            }
        } else {
            val absTop = state.resolveRingIndex(top)
            val absBottom = state.resolveRingIndex(bottom)
            repeat(n) {
                state.ring.rotateUp(absTop, absBottom)
                val line = state.ring[absBottom]
                line.clear(blankAttr, blankExtendedAttr)
                state.markLineChanged(line)
            }
        }
        state.markStructureChanged()
    }

    /**
     * Internal scroll-down primitive that deliberately does not cancel pending wrap.
     *
     * See [scrollUpInternal] for the rationale: this helper is shared by public
     * commands and internal row-advance logic.
     */
    private fun scrollDownInternal(count: Int = 1) {
        val top = state.scrollTop
        val bottom = state.scrollBottom
        val n = count.coerceIn(0, bottom - top + 1)
        if (n == 0) return

        val absTop = state.resolveRingIndex(top)
        val absBottom = state.resolveRingIndex(bottom)
        repeat(n) {
            state.ring.rotateDown(absTop, absBottom)
            val line = state.ring[absTop]
            line.clear(blankAttr, blankExtendedAttr)
            state.markLineChanged(line)
        }
        state.markStructureChanged()
    }

    /** Scrolls the active region upward by [count] lines. */
    fun scrollUp(count: Int = 1) =
        structuralMutation {
            scrollUpInternal(count)
        }

    /** Scrolls the active region downward by [count] lines. */
    fun scrollDown(count: Int = 1) =
        structuralMutation {
            scrollDownInternal(count)
        }

    /**
     * Advances one viewport row, scrolling only when the cursor is exactly on
     * the active bottom margin.
     *
     * A cursor outside a restricted scroll region never triggers a scroll here,
     * which matches VT behavior for writes below the margins.
     */
    private fun advanceRow(row: Int): Int =
        if (row == state.scrollBottom) {
            scrollUpInternal()
            state.scrollBottom
        } else {
            (row + 1).coerceAtMost(height - 1)
        }

    /**
     * Resolves the canonical owner column for [col].
     *
     * If [col] points at a wide spacer, the owner is the preceding leader cell.
     * Ordinary cells resolve to themselves.
     */
    private fun findClusterStart(
        line: Line,
        col: Int,
    ): Int {
        if (col !in 0 until width) return col

        val raw = line.rawCodepoint(col)
        if (raw == TerminalConstants.WIDE_CHAR_SPACER) {
            val prev = col - 1
            if (prev >= 0) {
                val prevRaw = line.rawCodepoint(prev)
                if (prevRaw != TerminalConstants.EMPTY && prevRaw != TerminalConstants.WIDE_CHAR_SPACER) {
                    return prev
                }
            }
        }
        return col
    }

    /**
     * Clears the full visual occupant that owns `[row, col]`.
     *
     * If [col] lands on a wide spacer, this method walks back to the leader so
     * overwrite operations never leave an orphan spacer behind.
     */
    private fun annihilateAt(
        row: Int,
        col: Int,
    ) {
        if (row !in 0 until height || col !in 0 until width) return

        val line = getLine(row)
        val start = findClusterStart(line, col)
        val raw = line.rawCodepoint(start)
        val attr = blankAttr
        val extendedAttr = blankExtendedAttr

        if (raw == TerminalConstants.WIDE_CHAR_SPACER) {
            line.setCell(start, TerminalConstants.EMPTY, attr, extendedAttr)
            state.markLineChanged(line)
            return
        }

        if (start + 1 < width && line.rawCodepoint(start + 1) == TerminalConstants.WIDE_CHAR_SPACER) {
            line.setCell(start, TerminalConstants.EMPTY, attr, extendedAttr)
            line.setCell(start + 1, TerminalConstants.EMPTY, attr, extendedAttr)
            state.markLineChanged(line)
            return
        }

        line.setCell(start, TerminalConstants.EMPTY, attr, extendedAttr)
        state.markLineChanged(line)
    }

    /**
     * Deep-copies a horizontal slice from [src] to [dest].
     *
     * This helper is intentionally used only for rectangular LR-margin edits.
     * It preserves cluster ownership correctly by copying cluster payloads into
     * fresh slots before the source cells are later cleared or overwritten.
     */
    private fun copySlice(
        src: Line,
        dest: Line,
        left: Int,
        right: Int,
    ) {
        for (col in left..right) {
            val raw = src.rawCodepoint(col)
            val attr = src.getPackedAttr(col)
            val extendedAttr = src.getPackedExtendedAttr(col)
            if (raw <= TerminalConstants.CLUSTER_HANDLE_MAX) {
                val cpLen = src.store.length(raw)
                if (clusterScratch.size < cpLen) {
                    clusterScratch = IntArray(cpLen)
                }
                src.store.readInto(raw, clusterScratch, 0)
                dest.setCluster(col, clusterScratch, cpLen, attr, extendedAttr)
            } else {
                dest.setCell(col, raw, attr, extendedAttr)
            }
        }
    }

    private fun occupantEndExclusive(
        line: Line,
        start: Int,
    ): Int {
        if (start !in 0 until width) return start + 1
        return if (start + 1 < width && line.rawCodepoint(start + 1) == TerminalConstants.WIDE_CHAR_SPACER) {
            start + 2
        } else {
            start + 1
        }
    }

    private fun ensureClusterScratchCapacity(required: Int) {
        if (clusterScratch.size >= required) return
        var nextSize = clusterScratch.size
        while (nextSize < required) {
            nextSize *= 2
        }
        clusterScratch = IntArray(nextSize)
    }

    private fun copyCellCodepoints(
        line: Line,
        col: Int,
    ): Int {
        val raw = line.rawCodepoint(col)
        return if (raw <= TerminalConstants.CLUSTER_HANDLE_MAX) {
            val length = line.store.length(raw)
            ensureClusterScratchCapacity(length)
            line.readCluster(col, clusterScratch)
        } else {
            ensureClusterScratchCapacity(1)
            clusterScratch[0] = raw
            1
        }
    }

    private fun isProtectedOccupant(
        line: Line,
        col: Int,
    ): Boolean {
        if (col !in 0 until width) return false
        val start = findClusterStart(line, col)
        return start in 0 until width && AttributeCodec.isProtected(line.getPackedAttr(start))
    }

    /**
     * Protection policy matrix:
     * - printable writes overwrite protected and unprotected cells alike
     * - normal erase paths (ED/EL and hard clears) ignore protection
     * - selective erase paths (DECSED/DECSEL) skip protected cells
     * - structural shifts and hard resets move or clear cells regardless of protection
     *
     * DECSCA therefore affects only selective erase. It does not make a cell
     * immutable to later printable output.
     */
    private inline fun withEraseRange(
        row: Int,
        startCol: Int,
        endExclusive: Int,
        block: (line: Line, from: Int, to: Int) -> Unit,
    ) {
        if (row !in 0 until height) return
        val line = getLine(row)
        val from = startCol.coerceIn(0, width)
        val to = endExclusive.coerceIn(0, width)
        if (from >= to) return
        block(line, from, to)
    }

    private fun selectiveEraseRange(
        row: Int,
        startCol: Int,
        endExclusive: Int,
    ) {
        withEraseRange(row, startCol, endExclusive) { line, from, to ->
            var col = from
            while (col < to) {
                val start = findClusterStart(line, col).coerceIn(0, width - 1)
                val next = occupantEndExclusive(line, start)
                if (!isProtectedOccupant(line, start)) {
                    line.clearRange(start, minOf(next, width), blankAttr, blankExtendedAttr)
                    state.markLineChanged(line)
                }
                col = maxOf(col + 1, next)
            }
            line.wrapped = false
            state.markLineChanged(line)
        }
    }

    /**
     * Erases the visual occupants intersecting `[startCol, endExclusive)` on one row.
     *
     * This is the normal-erase counterpart to [selectiveEraseRange]: protection
     * is ignored, but wide leaders/spacers and cluster owners are still erased
     * as full visual units so no orphaned cells remain.
     */
    private fun eraseRange(
        row: Int,
        startCol: Int,
        endExclusive: Int,
    ) {
        withEraseRange(row, startCol, endExclusive) { line, from, to ->
            var col = from
            while (col < to) {
                val start = findClusterStart(line, col).coerceIn(0, width - 1)
                val next = occupantEndExclusive(line, start)
                line.clearRange(start, minOf(next, width), blankAttr, blankExtendedAttr)
                state.markLineChanged(line)
                col = maxOf(col + 1, next)
            }
        }
    }

    /**
     * Core write engine shared by [printCodepoint] and [printCluster].
     *
     * Ordering matters here: deferred wrap, annihilation, insert-mode shifting,
     * payload write, spacer placement, and cursor commit are intentionally
     * interleaved. Keep this hot path monolithic unless profiling shows a real
     * improvement from refactoring.
     */
    private inline fun writeToGrid(
        charWidth: Int,
        crossinline writeCell: (line: Line, col: Int) -> Unit,
    ) {
        val oldCursorCol = state.cursor.col
        val oldCursorRow = state.cursor.row
        var cCol = state.cursor.col
        var cRow = state.cursor.row
        val widthInCells = if (charWidth == 2) 2 else 1

        if (cRow !in 0 until height || cCol !in 0 until width) return
        if (cCol !in leftMargin..rightMargin) return

        if (widthInCells == 2 && cCol >= rightMargin && !state.modes.isAutoWrap) {
            return
        }

        var line = getLine(cRow)

        if (state.cursor.pendingWrap) {
            state.cursor.pendingWrap = false
            line.wrapped = true
            state.markLineChanged(line)
            cCol = leftMargin
            cRow = advanceRow(cRow)
            line = getLine(cRow)
        }

        if (widthInCells == 2 && cCol >= rightMargin) {
            annihilateAt(cRow, cCol)
            cCol = leftMargin
            cRow = advanceRow(cRow)
            line = getLine(cRow)
        }

        if (state.modes.isInsertMode) {
            if (line.rawCodepoint(cCol) == TerminalConstants.WIDE_CHAR_SPACER) {
                annihilateAt(cRow, cCol)
            }
            line.insertCellsInRange(cCol, widthInCells, rightMargin, blankAttr, blankExtendedAttr)
            state.markLineChanged(line)
        }

        annihilateAt(cRow, cCol)
        if (widthInCells == 2 && cCol + 1 <= rightMargin) {
            annihilateAt(cRow, cCol + 1)
        }

        val writtenCol = cCol
        val writtenRow = cRow
        writeCell(line, writtenCol)
        state.markLineChanged(line)
        cCol += 1

        if (widthInCells == 2 && cCol < width) {
            line.setCell(
                cCol,
                TerminalConstants.WIDE_CHAR_SPACER,
                state.pen.currentAttr,
                state.pen.currentExtendedAttr,
            )
            state.markLineChanged(line)
            cCol += 1
        }

        if (cCol > rightMargin) {
            state.cursor.col = rightMargin
            state.cursor.row = cRow
            state.cursor.pendingWrap = state.modes.isAutoWrap
            state.rememberPrintableCell(writtenRow, writtenCol)
            markCursorIfMoved(oldCursorCol, oldCursorRow)
            return
        }

        state.cursor.col = cCol
        state.cursor.row = cRow
        state.cursor.pendingWrap = false
        state.rememberPrintableCell(writtenRow, writtenCol)
        markCursorIfMoved(oldCursorCol, oldCursorRow)
    }

    /**
     * Writes one scalar codepoint using the active pen and width supplied by the caller.
     *
     * Fast path: width-1 overwrite into an empty cell with no pending wrap and
     * no insert mode. Slow path: delegates to [writeToGrid] for full cell physics.
     */
    fun printCodepoint(
        codepoint: Int,
        charWidth: Int,
    ) {
        val attr = state.pen.currentAttr
        val extendedAttr = state.pen.currentExtendedAttr
        val cCol = state.cursor.col
        val cRow = state.cursor.row

        if (!state.modes.isInsertMode &&
            charWidth != 2 &&
            !state.cursor.pendingWrap &&
            cRow in 0 until height &&
            cCol in leftMargin..rightMargin
        ) {
            val line = getLine(cRow)
            if (line.rawCodepoint(cCol) == TerminalConstants.EMPTY) {
                line.setCell(cCol, codepoint, attr, extendedAttr)
                state.markLineChanged(line)
                state.rememberPrintableCell(cRow, cCol)
                if (cCol == rightMargin) {
                    if (state.modes.isAutoWrap) {
                        state.cursor.pendingWrap = true
                    } else {
                        state.cursor.col = cCol
                        state.cursor.pendingWrap = false
                    }
                } else {
                    state.cursor.col = cCol + 1
                    state.cursor.pendingWrap = false
                }
                markCursorIfMoved(cCol, cRow)
                return
            }
        }

        writeToGrid(charWidth) { line, col ->
            line.setCell(col, codepoint, attr, extendedAttr)
        }
    }

    /**
     * Writes one pre-segmented grapheme cluster.
     *
     * Single-codepoint clusters fall back to [printCodepoint] so the scalar fast
     * path remains available.
     */
    fun printCluster(
        cps: IntArray,
        cpLen: Int,
        charWidth: Int,
    ) {
        if (cpLen == 1) {
            printCodepoint(cps[0], charWidth)
            return
        }
        val attr = state.pen.currentAttr
        val extendedAttr = state.pen.currentExtendedAttr
        writeToGrid(charWidth) { line, col ->
            line.setCluster(col, cps, cpLen, attr, extendedAttr)
        }
    }

    /**
     * Appends [codepoint] to the most recently written printable cell without
     * moving the cursor.
     */
    fun appendToPreviousCluster(codepoint: Int) {
        val row = state.lastPrintableRow
        val col = state.lastPrintableCol
        if (row !in 0 until height || col !in 0 until width) return

        val line = getLine(row)
        val raw = line.rawCodepoint(col)
        if (raw == TerminalConstants.EMPTY || raw == TerminalConstants.WIDE_CHAR_SPACER) return

        val existingLength = copyCellCodepoints(line, col)
        ensureClusterScratchCapacity(existingLength + 1)
        clusterScratch[existingLength] = codepoint

        val attr = line.getPackedAttr(col)
        val extendedAttr = line.getPackedExtendedAttr(col)
        val oldWidth = if (col + 1 < width && line.rawCodepoint(col + 1) == TerminalConstants.WIDE_CHAR_SPACER) 2 else 1
        val clusterWidth = UnicodeWidth.calculateCluster(clusterScratch, existingLength + 1, state.modes.treatAmbiguousAsWide)

        if (clusterWidth == 2 && col + 1 <= rightMargin) {
            if (line.rawCodepoint(col + 1) != TerminalConstants.WIDE_CHAR_SPACER) {
                annihilateAt(row, col + 1)
            }
        } else if (col + 1 < width && line.rawCodepoint(col + 1) == TerminalConstants.WIDE_CHAR_SPACER) {
            line.setCell(col + 1, TerminalConstants.EMPTY, blankAttr, blankExtendedAttr)
        }

        line.setCluster(col, clusterScratch, existingLength + 1, attr, extendedAttr)
        if (clusterWidth == 2 && col + 1 <= rightMargin) {
            line.setCell(col + 1, TerminalConstants.WIDE_CHAR_SPACER, attr, extendedAttr)
        }
        adjustCursorAfterClusterWidthChange(row, col, oldWidth, clusterWidth)
        state.markLineChanged(line)
        state.rememberPrintableCell(row, col)
    }

    private fun adjustCursorAfterClusterWidthChange(
        row: Int,
        col: Int,
        oldWidth: Int,
        newWidth: Int,
    ) {
        if (oldWidth == newWidth || state.cursor.row != row || state.cursor.pendingWrap) return

        val oldEnd = col + oldWidth
        if (state.cursor.col != oldEnd) return

        val newEnd = col + newWidth
        if (newEnd <= rightMargin) {
            state.cursor.col = newEnd
            state.markCursorChanged()
        }
    }

    /**
     * Applies a vertical line mutation within the active scroll region.
     *
     * Returns immediately when the cursor is outside the region. The callback
     * receives resolved ring indices and a count already clamped to the region.
     */
    private inline fun mutateLines(
        count: Int,
        onMutate: (absCursorRow: Int, absBottom: Int, times: Int) -> Unit,
    ) {
        val cRow = state.cursor.row
        val top = state.scrollTop
        val bottom = state.scrollBottom
        if (cRow !in top..bottom) return

        val times = count.coerceAtMost(bottom - cRow + 1)
        if (times <= 0) return

        val absCursorRow = state.resolveRingIndex(cRow)
        val absBottom = state.resolveRingIndex(bottom)
        onMutate(absCursorRow, absBottom, times)
    }

    /** Inserts [count] blank lines at the cursor row within the active scroll region. */
    fun insertLines(count: Int) {
        if (count <= 0) return

        structuralMutation {
            mutateLines(count) { absCursorRow, absBottom, times ->
                if (!state.modes.isLeftRightMarginMode) {
                    repeat(times) {
                        state.ring.rotateDown(absCursorRow, absBottom)
                        val line = state.ring[absCursorRow]
                        line.clear(blankAttr, blankExtendedAttr)
                        state.markLineChanged(line)
                    }
                    state.markStructureChanged()
                    return@mutateLines
                }

                val topRow = state.cursor.row
                val bottomRow = state.scrollBottom
                for (row in bottomRow downTo topRow + times) {
                    copySlice(getLine(row - times), getLine(row), leftMargin, rightMargin)
                    getLine(row).wrapped = false
                    state.markLineChanged(getLine(row))
                }
                for (row in topRow until topRow + times) {
                    val line = getLine(row)
                    line.clearRange(leftMargin, rightMargin + 1, blankAttr, blankExtendedAttr)
                    line.wrapped = false
                    state.markLineChanged(line)
                }
                state.markStructureChanged()
            }
        }
    }

    /** Deletes [count] lines at the cursor row within the active scroll region. */
    fun deleteLines(count: Int) {
        if (count <= 0) return

        structuralMutation {
            mutateLines(count) { absCursorRow, absBottom, times ->
                if (!state.modes.isLeftRightMarginMode) {
                    repeat(times) {
                        state.ring.rotateUp(absCursorRow, absBottom)
                        val line = state.ring[absBottom]
                        line.clear(blankAttr, blankExtendedAttr)
                        state.markLineChanged(line)
                    }
                    state.markStructureChanged()
                    return@mutateLines
                }

                val topRow = state.cursor.row
                val bottomRow = state.scrollBottom
                for (row in topRow..bottomRow - times) {
                    copySlice(getLine(row + times), getLine(row), leftMargin, rightMargin)
                    getLine(row).wrapped = false
                    state.markLineChanged(getLine(row))
                }
                for (row in bottomRow - times + 1..bottomRow) {
                    val line = getLine(row)
                    line.clearRange(leftMargin, rightMargin + 1, blankAttr, blankExtendedAttr)
                    line.wrapped = false
                    state.markLineChanged(line)
                }
                state.markStructureChanged()
            }
        }
    }

    @Suppress("NOTHING_TO_INLINE")
    private inline fun isCursorInMargins(): Boolean {
        val cCol = state.cursor.col
        val cRow = state.cursor.row
        return cRow in 0 until height && cCol in leftMargin..rightMargin
    }

    /**
     * Inserts [count] blank cells at the cursor column, shifting the remainder
     * of the line right and discarding cells pushed past the right margin.
     */
    fun insertBlankCharacters(count: Int) {
        if (count <= 0) return

        structuralMutation {
            if (!isCursorInMargins()) return@structuralMutation
            val cRow = state.cursor.row
            val cCol = state.cursor.col

            val line = getLine(cRow)

            if (line.rawCodepoint(cCol) == TerminalConstants.WIDE_CHAR_SPACER) {
                annihilateAt(cRow, cCol)
            }

            val safeCount = count.coerceAtMost(rightMargin - cCol + 1)
            val edgeCol = rightMargin - safeCount + 1

            if (edgeCol in (cCol + 1)..rightMargin &&
                line.rawCodepoint(edgeCol) == TerminalConstants.WIDE_CHAR_SPACER
            ) {
                annihilateAt(cRow, edgeCol)
            }

            line.insertCellsInRange(cCol, safeCount, rightMargin, blankAttr, blankExtendedAttr)
            state.markLineChanged(line)
        }
    }

    /**
     * Deletes [count] cells at the cursor column, shifting remaining content
     * left and blank-filling the vacated right edge with the current pen attr.
     */
    fun deleteCharacters(count: Int) {
        if (count <= 0) return

        structuralMutation {
            if (!isCursorInMargins()) return@structuralMutation
            val cRow = state.cursor.row
            val cCol = state.cursor.col

            val safeCount = count.coerceAtMost(rightMargin - cCol + 1)

            annihilateAt(cRow, cCol)

            if (safeCount < rightMargin - cCol + 1) {
                val rightEdge = cCol + safeCount
                if (rightEdge <= rightMargin &&
                    getLine(cRow).rawCodepoint(rightEdge) == TerminalConstants.WIDE_CHAR_SPACER
                ) {
                    annihilateAt(cRow, rightEdge)
                }
            }

            val line = getLine(cRow)
            line.deleteCellsInRange(cCol, safeCount, rightMargin, blankAttr, blankExtendedAttr)
            state.markLineChanged(line)
        }
    }

    /**
     * Erases [count] cells starting at the cursor column without shifting the
     * remainder of the line (ECH).
     *
     * A count of `0` follows VT semantics and erases one character. Negative
     * values are ignored. Wide characters and cluster owners are erased as full
     * visual occupants, so erasing a spacer also clears its leader.
     */
    fun eraseCharacters(count: Int) {
        if (count < 0) return

        structuralMutation {
            val cRow = state.cursor.row
            val cCol = state.cursor.col
            if (cRow !in 0 until height || cCol !in 0 until width) return@structuralMutation
            if (state.modes.isLeftRightMarginMode && cCol !in leftMargin..rightMargin) return@structuralMutation

            val safeCount = if (count == 0) 1 else count
            val endExclusive =
                if (state.modes.isLeftRightMarginMode) {
                    minOf(cCol + safeCount, rightMargin + 1)
                } else {
                    minOf(cCol + safeCount, width)
                }

            eraseRange(cRow, cCol, endExclusive)
        }
    }

    private fun eraseLineToEndInternal(
        cRow: Int,
        cCol: Int,
    ) {
        val line = getLine(cRow)
        if (state.modes.isLeftRightMarginMode) {
            if (cCol !in leftMargin..rightMargin) {
                line.wrapped = false
                state.markLineChanged(line)
                return
            }
            val start = maxOf(cCol, leftMargin)
            if (start <= rightMargin) {
                annihilateAt(cRow, start)
                line.clearRange(start, rightMargin + 1, blankAttr, blankExtendedAttr)
                state.markLineChanged(line)
            }
        } else {
            annihilateAt(cRow, cCol)
            line.clearFromColumn(cCol, blankAttr, blankExtendedAttr)
            state.markLineChanged(line)
        }
        line.wrapped = false
        state.markLineChanged(line)
    }

    /** Erases from the cursor through the end of the current line (EL 0). */
    fun eraseLineToEnd() =
        structuralMutation {
            val cRow = state.cursor.row
            val cCol = state.cursor.col
            if (cRow !in 0 until height || cCol !in 0 until width) return@structuralMutation
            eraseLineToEndInternal(cRow, cCol)
        }

    private fun eraseLineToCursorInternal(
        cRow: Int,
        cCol: Int,
    ) {
        val line = getLine(cRow)
        if (state.modes.isLeftRightMarginMode) {
            if (cCol !in leftMargin..rightMargin) return
            val end = minOf(cCol, rightMargin)
            if (end >= leftMargin) {
                annihilateAt(cRow, end)
                line.clearRange(leftMargin, end + 1, blankAttr, blankExtendedAttr)
                state.markLineChanged(line)
            }
        } else {
            annihilateAt(cRow, cCol)
            line.clearToColumn(cCol, blankAttr, blankExtendedAttr)
            state.markLineChanged(line)
        }
    }

    /** Erases from the start of the line through the cursor (EL 1). */
    fun eraseLineToCursor() =
        structuralMutation {
            val cRow = state.cursor.row
            val cCol = state.cursor.col.coerceAtMost(width - 1)
            if (cRow !in 0 until height || cCol < 0) return@structuralMutation
            eraseLineToCursorInternal(cRow, cCol)
        }

    /** Erases the entire current line without moving the cursor (EL 2). */
    fun eraseCurrentLine() =
        structuralMutation {
            val cRow = state.cursor.row
            if (cRow !in 0 until height) return@structuralMutation
            val line = getLine(cRow)
            if (state.modes.isLeftRightMarginMode) {
                line.clearRange(leftMargin, rightMargin + 1, blankAttr, blankExtendedAttr)
            } else {
                line.clear(blankAttr, blankExtendedAttr)
            }
            line.wrapped = false
            state.markLineChanged(line)
        }

    /** Selectively erases from the cursor through the end of the current line (DECSEL 0). */
    fun selectiveEraseLineToEnd() =
        structuralMutation {
            val cRow = state.cursor.row
            val cCol = state.cursor.col
            if (cRow !in 0 until height || cCol !in 0 until width) return@structuralMutation

            if (state.modes.isLeftRightMarginMode) {
                if (cCol !in leftMargin..rightMargin) {
                    val line = getLine(cRow)
                    line.wrapped = false
                    state.markLineChanged(line)
                    return@structuralMutation
                }
                selectiveEraseRange(cRow, maxOf(cCol, leftMargin), rightMargin + 1)
            } else {
                selectiveEraseRange(cRow, cCol, width)
            }
        }

    /** Selectively erases from the start of the current line through the cursor (DECSEL 1). */
    fun selectiveEraseLineToCursor() =
        structuralMutation {
            val cRow = state.cursor.row
            val cCol = state.cursor.col.coerceAtMost(width - 1)
            if (cRow !in 0 until height || cCol < 0) return@structuralMutation

            if (state.modes.isLeftRightMarginMode) {
                if (cCol !in leftMargin..rightMargin) return@structuralMutation
                selectiveEraseRange(cRow, leftMargin, cCol + 1)
            } else {
                selectiveEraseRange(cRow, 0, cCol + 1)
            }
        }

    /** Selectively erases the entire current line without moving the cursor (DECSEL 2). */
    fun selectiveEraseCurrentLine() =
        structuralMutation {
            val cRow = state.cursor.row
            if (cRow !in 0 until height) return@structuralMutation
            if (state.modes.isLeftRightMarginMode) {
                selectiveEraseRange(cRow, leftMargin, rightMargin + 1)
            } else {
                selectiveEraseRange(cRow, 0, width)
            }
        }

    /** Erases from the cursor through the end of the visible screen (ED 0). */
    fun eraseScreenToEnd() =
        structuralMutation {
            val cRow = state.cursor.row
            val cCol = state.cursor.col
            if (cRow !in 0 until height) return@structuralMutation

            if (cCol in 0 until width) {
                eraseLineToEndInternal(cRow, cCol)
            }

            for (row in cRow + 1 until height) {
                val line = getLine(row)
                line.clear(blankAttr, blankExtendedAttr)
                state.markLineChanged(line)
            }
        }

    /** Erases from the start of the visible screen through the cursor (ED 1). */
    fun eraseScreenToCursor() =
        structuralMutation {
            val cRow = state.cursor.row
            if (cRow !in 0 until height) return@structuralMutation

            for (row in 0 until cRow) {
                val line = getLine(row)
                line.clear(blankAttr, blankExtendedAttr)
                state.markLineChanged(line)
            }

            val cCol = state.cursor.col.coerceAtMost(width - 1)
            if (cCol >= 0) {
                eraseLineToCursorInternal(cRow, cCol)
            }
        }

    /** Selectively erases from the cursor through the end of the visible screen (DECSED 0). */
    fun selectiveEraseScreenToEnd() =
        structuralMutation {
            val cRow = state.cursor.row
            val cCol = state.cursor.col
            if (cRow !in 0 until height) return@structuralMutation

            if (cCol in 0 until width) {
                if (state.modes.isLeftRightMarginMode) {
                    if (cCol in leftMargin..rightMargin) {
                        selectiveEraseRange(cRow, maxOf(cCol, leftMargin), rightMargin + 1)
                    } else {
                        val line = getLine(cRow)
                        line.wrapped = false
                        state.markLineChanged(line)
                    }
                } else {
                    selectiveEraseRange(cRow, cCol, width)
                }
            }

            for (row in cRow + 1 until height) {
                selectiveEraseRange(row, 0, width)
            }
        }

    /** Selectively erases from the start of the visible screen through the cursor (DECSED 1). */
    fun selectiveEraseScreenToCursor() =
        structuralMutation {
            val cRow = state.cursor.row
            if (cRow !in 0 until height) return@structuralMutation

            for (row in 0 until cRow) {
                selectiveEraseRange(row, 0, width)
            }

            val cCol = state.cursor.col.coerceAtMost(width - 1)
            if (cCol >= 0) {
                if (state.modes.isLeftRightMarginMode) {
                    if (cCol in leftMargin..rightMargin) {
                        selectiveEraseRange(cRow, leftMargin, cCol + 1)
                    }
                } else {
                    selectiveEraseRange(cRow, 0, cCol + 1)
                }
            }
        }

    /** Selectively erases the entire visible screen without moving the cursor (DECSED 2). */
    fun selectiveEraseEntireScreen() =
        structuralMutation {
            for (row in 0 until height) {
                selectiveEraseRange(row, 0, width)
            }
        }

    /**
     * Clears scrollback history while preserving the current visible viewport (ED 3).
     *
     * Visible rows are deep-copied into a fresh ring/store pair so dropped
     * history lines release any cluster payloads they owned.
     */
    fun eraseScreenAndHistory() =
        structuralMutation {
            val buffer = state.activeBuffer
            val sourceStore = buffer.store
            val newStore = ClusterStore()
            val newRing = HistoryRing(buffer.maxHistory + height) { Line(width, newStore) }
            val visibleTop = (buffer.ring.size - height).coerceAtLeast(0)
            var clusterBuf = IntArray(16)

            for (row in 0 until height) {
                val srcLine = buffer.ring[visibleTop + row]
                val destLine = newRing.push()
                for (col in 0 until width) {
                    val raw = srcLine.rawCodepoint(col)
                    val attr = srcLine.getPackedAttr(col)
                    val extendedAttr = srcLine.getPackedExtendedAttr(col)
                    if (raw <= TerminalConstants.CLUSTER_HANDLE_MAX) {
                        val cpLen = sourceStore.length(raw)
                        if (clusterBuf.size < cpLen) {
                            clusterBuf = IntArray(cpLen)
                        }
                        sourceStore.readInto(raw, clusterBuf, 0)
                        destLine.setCluster(col, clusterBuf, cpLen, attr, extendedAttr)
                    } else {
                        destLine.setRawCell(col, raw, attr, extendedAttr)
                    }
                }
                destLine.wrapped = srcLine.wrapped
                state.markLineChanged(destLine)
            }

            buffer.store = newStore
            buffer.ring = newRing
            state.markStructureChanged()
        }

    private fun clearViewportInternal() {
        for (row in 0 until height.coerceAtMost(state.ring.size)) {
            val line = getLine(row)
            line.clear(blankAttr, blankExtendedAttr)
            state.markLineChanged(line)
        }
    }

    /** Clears the visible viewport without touching scrollback history. */
    fun clearViewport() =
        structuralMutation {
            clearViewportInternal()
        }

    /** Clears both the viewport and retained history on the active buffer. */
    private fun clearAllHistoryInternal() {
        state.activeBuffer.clearGrid(blankAttr, blankExtendedAttr, height)
    }

    /** Clears the active buffer's viewport and scrollback history. */
    fun clearAllHistory() =
        structuralMutation {
            clearAllHistoryInternal()
            for (row in 0 until height) {
                state.markLineChanged(getLine(row))
            }
            state.markStructureChanged()
        }

    /**
     * Performs the destructive post-resize reset phase of DECCOLM on the active buffer.
     *
     * Resize must happen first. Never call this directly on stale dimensions or
     * before the width switch has been applied; [newWidth] and the active buffer
     * geometry must already match.
     *
     * Sequence:
     * 1. Clear the active display and its retained history
     * 2. Home the active cursor to absolute `(0, 0)` regardless of DECOM
     * 3. Reset vertical and horizontal margins to the full viewport
     * 4. Reset tab stops to the default 8-column rhythm for [newWidth]
     * 5. Cancel pending wrap
     *
     * This method intentionally does not touch either DECSC saved-cursor slot.
     * The caller is responsible for any width change and for preserving saved
     * cursor state across that resize when DECCOLM semantics require it.
     */
    fun deccolmReset(newWidth: Int) =
        structuralMutation {
            clearAllHistoryInternal()
            for (row in 0 until height) {
                state.markLineChanged(getLine(row))
            }
            val oldCursorCol = state.cursor.col
            val oldCursorRow = state.cursor.row
            state.activeBuffer.cursor.col = 0
            state.activeBuffer.cursor.row = 0
            markCursorIfMoved(oldCursorCol, oldCursorRow)
            state.activeBuffer.resetScrollRegion(height)
            state.activeBuffer.resetLeftRightMargins(newWidth)
            state.tabStops.reset(newWidth)
            state.markStructureChanged()
        }

    /** Executes a line feed relative to the active scroll region. */
    fun newLine() =
        structuralMutation {
            val oldCursorCol = state.cursor.col
            val oldCursorRow = state.cursor.row
            state.cursor.row = advanceRow(state.cursor.row)
            markCursorIfMoved(oldCursorCol, oldCursorRow)
        }

    /** Executes reverse index relative to the active scroll region. */
    fun reverseLineFeed() =
        structuralMutation {
            val oldCursorCol = state.cursor.col
            val oldCursorRow = state.cursor.row
            val cRow = state.cursor.row
            if (cRow == state.scrollTop) {
                scrollDownInternal()
            } else {
                state.cursor.row = (cRow - 1).coerceAtLeast(0)
            }
            markCursorIfMoved(oldCursorCol, oldCursorRow)
        }

    /**
     * Executes the DEC Screen Alignment Test (DECALN, `ESC # 8`).
     *
     * Fills the entire visible screen viewport with uppercase 'E' characters, resets all vertical
     * and horizontal scrolling regions to the full viewport limits, homes the cursor to (0, 0), and
     * cancels any pending cursor wrap state.
     */
    fun decaln() =
        structuralMutation {
            state.cancelPendingWrap()

            // Reset margins
            state.activeBuffer.resetScrollRegion(height)
            state.activeBuffer.resetLeftRightMargins(width)

            // Home the cursor
            state.cursor.col = 0
            state.cursor.row = 0

            // Fill the screen with uppercase 'E's using the default blank attributes
            for (viewportRow in 0 until height) {
                val line = state.ring[state.resolveRingIndex(viewportRow)]
                line.clear(blankAttr, blankExtendedAttr)
                for (col in 0 until width) {
                    line.setCell(col, 'E'.code, blankAttr, blankExtendedAttr)
                }
                state.markLineChanged(line)
            }
            state.markStructureChanged()
        }
}
