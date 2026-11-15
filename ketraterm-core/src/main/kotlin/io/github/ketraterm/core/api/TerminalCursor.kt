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
package io.github.ketraterm.core.api

/**
 * Cursor-movement contract for the terminal buffer.
 *
 * Consumed by the ANSI parser for all cursor positioning, save/restore,
 * and tab stop commands.
 */
public interface TerminalCursor {
    /**
     * Moves the cursor to an absolute position (CUP / HVP, `CSI row ; col H`).
     *
     * When DECOM is active, [row] is relative to the top of the scroll region
     * and clamped within it. When DECOM is inactive, [row] is absolute in the
     * viewport.
     *
     * With DECOM inactive, [col] addresses the entire viewport even when
     * DECLRMM is active. With DECOM active, [col] is relative to the effective
     * left margin and clamped to the horizontal margins (the page edges when
     * DECLRMM is inactive).
     *
     * @param col Target column (0-based).
     * @param row Target row (0-based).
     */
    public fun positionCursor(
        col: Int,
        row: Int,
    )

    /**
     * Positions the column (CHA), preserving the row and cancelling pending wrap.
     * [col] is zero-based and follows [positionCursor]'s origin and bounds rules.
     */
    public fun positionCursorColumn(col: Int)

    /**
     * Positions the row (VPA), preserving the column and cancelling pending wrap.
     * [row] is zero-based and follows [positionCursor]'s origin and bounds rules.
     */
    public fun positionCursorRow(row: Int)

    /**
     * Moves the cursor up by [n] rows (CUU, `CSI n A`).
     *
     * Stops at the top margin when approaching it from below, or at row 0
     * when already above it. Non-positive values are no-ops.
     *
     * @param n Number of rows. Must be >= 1.
     */
    public fun cursorUp(n: Int = 1)

    /**
     * Moves the cursor down by [n] rows (CUD, `CSI n B`).
     *
     * Stops at the bottom margin when approaching it from above, or at the
     * last row when already below it. Non-positive values are no-ops.
     *
     * @param n Number of rows. Must be >= 1.
     */
    public fun cursorDown(n: Int = 1)

    /**
     * Moves the cursor left by [n] columns (CUB, `CSI n D`).
     *
     * Without reverse wrap, stops at the left margin (column 0 if DECLRMM is off).
     * Starting left of that margin uses column 0 as the boundary. With auto-wrap,
     * mode 45 crosses soft-wrapped rows; mode 1045 crosses all rows and cycles
     * within vertical margins. Neither scrolls or accesses history. In either
     * reverse-wrap mode, pending wrap consumes one step before cursor movement.
     *
     * @param n Number of columns. Non-positive values are no-ops.
     */
    public fun cursorLeft(n: Int = 1)

    /**
     * Moves the cursor right by [n] columns (CUF, `CSI n C`).
     *
     * Stops at the effective right margin, or at the viewport right edge
     * when already beyond that margin.
     *
     * @param n Number of columns. Non-positive values are no-ops.
     */
    public fun cursorRight(n: Int = 1)

    /**
     * Saves the core-owned cursor state (DECSC, `ESC 7`).
     *
     * The saved state includes:
     * - cursor column
     * - cursor row
     * - pen attributes
     * - pending-wrap state
     * - origin mode (DECOM)
     *
     * One save slot exists per screen. Subsequent calls overwrite that slot and
     * [restoreCursor] restores it later.
     *
     * This core does not model parser-owned charset designation or shift state
     * (`G0..G3`, `SO`, `SI`), so DECSC does not capture charset state here.
     */
    public fun saveCursor()

    /**
     * Restores the core-owned state saved by [saveCursor] (DECRC, `ESC 8`).
     *
     * The restored state includes:
     * - cursor column
     * - cursor row
     * - pen attributes
     * - pending-wrap state
     * - origin mode (DECOM)
     *
     * If no cursor has been saved, the core homes the cursor to absolute
     * `(0, 0)`, clears origin mode, and resets the pen to its default, matching
     * the current xterm-style fallback implemented by the core.
     *
     * Charset designation and shift state remain outside this contract because
     * they belong to the parser layer, not to `:ketraterm-core`.
     */
    public fun restoreCursor()

    /**
     * Resets the cursor to the home position `(col=0, row=0)`.
     *
     * Pen attributes are not affected.
     */
    public fun resetCursor()

    // Tab stops

    /**
     * Sets a tab stop at the current cursor column (HTS, `ESC H`).
     *
     * The stop persists until cleared by [clearTabStop], [clearAllTabStops],
     * or a hard reset. As a non-printing control, HTS cancels any pending wrap.
     */
    public fun setTabStop()

    /**
     * Clears the tab stop at the current cursor column (TBC 0, `CSI 0 g`).
     *
     * No-op if no stop exists at the current column. As a non-printing control,
     * TBC 0 cancels any pending wrap.
     */
    public fun clearTabStop()

    /**
     * Clears all tab stops (TBC 3, `CSI 3 g`).
     *
     * After this call, [horizontalTab] advances directly to its right boundary
     * until stops are re-established. As a non-printing control, TBC 3
     * cancels any pending wrap.
     */
    public fun clearAllTabStops()

    /**
     * Advances the cursor to the next tab stop (HT, `0x09`).
     *
     * Clamps to the active right boundary when no stop exists to the right.
     * The boundary is the effective right margin, or the page edge when already
     * beyond that margin. HT never triggers a line wrap.
     */
    public fun horizontalTab()

    /**
     * Advances the cursor forward by [count] tab stops (CHT, `CSI Ps I`).
     *
     * A count of `0` uses the ANSI default of `1`. The cursor never wraps:
     * if fewer than [count] stops remain, movement clamps at the active right
     * boundary, as defined by [horizontalTab].
     *
     * @param count Number of tab stops to advance.
     */
    public fun cursorForwardTab(count: Int = 1)

    /**
     * Moves the cursor backward by [count] tab stops (CBT, `CSI Ps Z`).
     *
     * A count of `0` uses the ANSI default of `1`. The cursor never wraps:
     * if fewer than [count] stops exist to the left, movement clamps at the
     * effective left margin, or column `0` when already left of that margin.
     *
     * @param count Number of tab stops to move backward.
     */
    public fun cursorBackwardTab(count: Int = 1)
}
