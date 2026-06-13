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

import io.github.jvterm.core.state.TerminalState

/**
 * Handles cursor and pen state operations that require no grid mutation.
 *
 * Owns: cursor positioning, pen save/restore (DECSC/DECRC), carriage return,
 * and tab navigation. Does NOT touch the ring, lines, or any physical grid memory.
 *
 * ## Pending-wrap invariant
 *
 * Every method in this class that repositions the cursor must call
 * [TerminalState.cancelPendingWrap] before committing the new position. This ensures that
 * no cursor-movement sequence leaves a stale pending-wrap flag that could
 * cause the next printed character to wrap from an unexpected position.
 */
internal class CursorEngine(
    private val state: TerminalState,
) {
    private val height: Int get() = state.dimensions.height
    private val leftMargin: Int get() = state.effectiveLeftMargin
    private val rightMargin: Int get() = state.effectiveRightMargin

    /** Advances once to the next tab stop, clamped to the active right boundary. */
    private fun advanceToNextTabStop() {
        state.cursor.col = minOf(state.tabStops.getNextStop(state.cursor.col), rightMargin)
    }

    /** Retreats once to the previous tab stop, clamped to the active left boundary. */
    private fun retreatToPreviousTabStop() {
        state.cursor.col = state.tabStops.getPreviousStop(state.cursor.col, leftMargin)
    }

    private inline fun cursorMutation(block: () -> Unit) {
        val oldCol = state.cursor.col
        val oldRow = state.cursor.row
        block()
        if (state.cursor.col != oldCol || state.cursor.row != oldRow) {
            state.markCursorChanged()
        }
    }

    // --- Cursor Positioning ----------------------------------------------

    /** Homes the cursor using the active origin and horizontal-margin modes. */
    fun homeCursor() =
        cursorMutation {
            state.cancelPendingWrap()
            state.cursor.col = leftMargin
            state.cursor.row = if (state.modes.isOriginMode) state.scrollTop else 0
        }

    /**
     * Moves the cursor to the beginning of the current line (CR, U+000D).
     * Cancels any pending wrap.
     */
    fun carriageReturn() =
        cursorMutation {
            state.cancelPendingWrap()
            state.cursor.col = leftMargin
        }

    /**
     * Absolute positioning for programmatic/test use.
     * This does NOT apply DECOM translation and addresses the entire viewport.
     *
     * @param col Target column (0-based).
     * @param row Target row (0-based).
     */
    fun setCursorAbsolute(
        col: Int,
        row: Int,
    ) = cursorMutation {
        state.cancelPendingWrap()
        state.cursor.col = state.dimensions.clampCol(col)
        state.cursor.row = state.dimensions.clampRow(row)
    }

    /**
     * ANSI CUP/HVP positioning.
     * Applies VT100 Origin Mode (DECOM) translation.
     *
     * When DECOM is active, [row] is treated as relative to the active scroll
     * region, and the cursor is mathematically trapped within those margins.
     * When DECOM is inactive, positioning is absolute across the entire viewport.
     */
    fun setCursor(
        col: Int,
        row: Int,
    ) = cursorMutation {
        state.cancelPendingWrap()
        val targetCol =
            if (state.modes.isOriginMode && state.modes.isLeftRightMarginMode) {
                leftMargin + col
            } else {
                col
            }
        state.cursor.col = targetCol.coerceIn(leftMargin, rightMargin)

        state.cursor.row =
            if (state.modes.isOriginMode) {
                (state.scrollTop + row).coerceIn(state.scrollTop, state.scrollBottom)
            } else {
                state.dimensions.clampRow(row)
            }
    }

    // --- Relative Movement -----------------------------------------------

    /**
     * Moves the cursor up by [n] rows (CUU, CSI n A).
     *
     * If the cursor is within the active scroll region,
     * movement stops at scrollTop. If the cursor is above the scroll region
     * (i.e., outside it entirely), it clamps to row 0.
     */
    fun cursorUp(n: Int): Unit =
        cursorMutation {
            if (n <= 0) return
            state.cancelPendingWrap()
            val top = if (state.cursor.row in state.scrollTop..state.scrollBottom) state.scrollTop else 0
            state.cursor.row = (state.cursor.row - n).coerceAtLeast(top)
        }

    /**
     * Moves the cursor down by [n] rows (CUD, CSI n B).
     *
     * If the cursor is within the active scroll region,
     * movement stops at scrollBottom. If the cursor is below the scroll region,
     * it clamps to height - 1.
     */
    fun cursorDown(n: Int): Unit =
        cursorMutation {
            if (n <= 0) return
            state.cancelPendingWrap()
            val bottom = if (state.cursor.row in state.scrollTop..state.scrollBottom) state.scrollBottom else height - 1
            state.cursor.row = (state.cursor.row + n).coerceAtMost(bottom)
        }

    /**
     * Moves the cursor left by [n] columns, clamped to column 0 (CUB).
     */
    fun cursorLeft(n: Int): Unit =
        cursorMutation {
            if (n <= 0) return
            state.cancelPendingWrap()
            state.cursor.col = (state.cursor.col - n).coerceAtLeast(leftMargin)
        }

    /**
     * Moves the cursor right by [n] columns, clamped to width - 1 (CUF).
     */
    fun cursorRight(n: Int): Unit =
        cursorMutation {
            if (n <= 0) return
            state.cancelPendingWrap()
            state.cursor.col = (state.cursor.col + n).coerceAtMost(rightMargin)
        }

    /**
     * Advances the cursor to the next tab stop (HT, U+0009).
     * Clamps to the right margin if no further stops exist.
     * Tab never triggers a line wrap.
     */
    fun horizontalTab() =
        cursorMutation {
            state.cancelPendingWrap()
            advanceToNextTabStop()
        }

    /**
     * Advances the cursor forward by [count] tab stops (CHT, CSI Ps I).
     *
     * A count of `0` uses the ANSI default of `1`. Movement never wraps:
     * if fewer than [count] stops exist before the active right boundary,
     * the cursor clamps there and remains pinned for the remaining steps.
     */
    fun cursorForwardTab(count: Int = 1) =
        cursorMutation {
            state.cancelPendingWrap()
            val steps = if (count <= 0) 1 else count
            repeat(steps) {
                advanceToNextTabStop()
            }
        }

    /**
     * Moves the cursor backward by [count] tab stops (CBT, CSI Ps Z).
     *
     * A count of `0` uses the ANSI default of `1`. Movement never wraps:
     * if fewer than [count] stops exist before the active left boundary,
     * the cursor clamps there and remains pinned for the remaining steps.
     */
    fun cursorBackwardTab(count: Int = 1) =
        cursorMutation {
            state.cancelPendingWrap()
            val steps = if (count <= 0) 1 else count
            repeat(steps) {
                retreatToPreviousTabStop()
            }
        }

    // --- Save / Restore ----------------------------------------------

    /**
     * Saves the current cursor position, pen attributes, pending-wrap state,
     * and origin mode (DECSC, ESC 7).
     *
     * Mutates the pre-allocated state.savedCursor slot in place — zero heap
     * allocation. If called again before a restore, the previous save is overwritten.
     */
    fun saveCursor() {
        state.savedCursor.col = state.cursor.col
        state.savedCursor.row = state.cursor.row
        state.savedCursor.attr = state.pen.currentAttr
        state.savedCursor.extendedAttr = state.pen.currentExtendedAttr
        state.savedCursor.pendingWrap = state.cursor.pendingWrap
        state.savedCursor.isOriginMode = state.modes.isOriginMode
        state.savedCursor.isSaved = true
    }

    /**
     * Restores the cursor state previously saved by [saveCursor] (DECRC, ESC 8).
     *
     * The restored state includes:
     * - cursor column
     * - cursor row
     * - pen attributes
     * - pending-wrap state
     * - origin mode (DECOM)
     *
     * ## Restoration model
     *
     * This implementation follows the professional emulator convention that
     * DECSC stores the cursor position in absolute screen coordinates, not
     * coordinates relative to the current origin mode or scroll margins.
     * Therefore, DECRC restores the saved row/column as absolute viewport
     * coordinates.
     *
     * Origin mode is restored as a terminal state, but it does not reinterpret
     * the saved cursor position relative to the current scroll region. The
     * restored row is clamped only to the current viewport height. The restored
     * column is clamped to the current effective horizontal bounds:
     * - with DECLRMM off: `0 .. width - 1`
     * - with DECLRMM on: `leftMargin .. rightMargin`
     *
     * ## Resize safety
     *
     * A saved pending-wrap state is only valid when the restored cursor is
     * still on the current effective right margin. If the terminal width or
     * active horizontal margins changed since [saveCursor], [pendingWrap] is
     * restored only when `restoredCol == rightMargin`; otherwise it is cleared
     * to avoid an impossible deferred-wrap state.
     *
     * ## No prior save
     *
     * If no cursor has been saved:
     * - the cursor homes to absolute `(0, 0)`
     * - pending-wrap is cleared
     * - pen attributes are reset
     * - origin mode is cleared
     *
     * The no-save path uses [setCursorAbsolute], so it deliberately ignores
     * DECOM and DECLRMM when homing.
     */
    fun restoreCursor(): Unit =
        cursorMutation {
            if (!state.savedCursor.isSaved) {
                setCursorAbsolute(0, 0)
                state.pen.reset()
                state.modes.isOriginMode = false
                return
            }

            state.modes.isOriginMode = state.savedCursor.isOriginMode

            val restoredCol = state.savedCursor.col.coerceIn(leftMargin, rightMargin)
            val restoredRow = state.savedCursor.row.coerceIn(0, height - 1)

            state.cursor.col = restoredCol
            state.cursor.row = restoredRow
            state.cursor.pendingWrap = state.savedCursor.pendingWrap && restoredCol == rightMargin

            state.pen.restoreAttr(state.savedCursor.attr, state.savedCursor.extendedAttr)
        }
}
