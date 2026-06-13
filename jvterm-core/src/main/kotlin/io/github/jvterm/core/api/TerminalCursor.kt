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
package io.github.jvterm.core.api

/**
 * Cursor-movement contract for the terminal buffer.
 *
 * Consumed by the ANSI parser for all cursor positioning, save/restore,
 * and tab stop commands.
 */
interface TerminalCursor {
    /**
     * Moves the cursor to an absolute position (CUP / HVP, `CSI row ; col H`).
     *
     * When DECOM is active, [row] is relative to the top of the scroll region
     * and clamped within it. When DECOM is inactive, [row] is absolute in the
     * viewport.
     *
     * Column handling depends on DECLRMM:
     * - with DECLRMM off, [col] is absolute in the viewport
     * - with DECLRMM on and DECOM off, [col] is absolute but clamped to the active horizontal margins
     * - with both DECLRMM and DECOM on, [col] is relative to the left margin
     *
     * @param col Target column (0-based).
     * @param row Target row (0-based).
     */
    fun positionCursor(
        col: Int,
        row: Int,
    )

    /**
     * Moves the cursor up by [n] rows (CUU, `CSI n A`).
     *
     * Stops at the top of the scroll region when the cursor is inside it,
     * or at row 0 when outside. Non-positive values are no-ops.
     *
     * @param n Number of rows. Must be >= 1.
     */
    fun cursorUp(n: Int = 1)

    /**
     * Moves the cursor down by [n] rows (CUD, `CSI n B`).
     *
     * Stops at the bottom of the scroll region when the cursor is inside it,
     * or at the last row when outside. Non-positive values are no-ops.
     *
     * @param n Number of rows. Must be >= 1.
     */
    fun cursorDown(n: Int = 1)

    /**
     * Moves the cursor left by [n] columns (CUB, `CSI n D`).
     *
     * Clamps to column 0 when DECLRMM is off, or to the active left margin when
     * DECLRMM is on.
     *
     * @param n Number of columns. Non-positive values are no-ops.
     */
    fun cursorLeft(n: Int = 1)

    /**
     * Moves the cursor right by [n] columns (CUF, `CSI n C`).
     *
     * Clamps to the viewport right edge when DECLRMM is off, or to the active
     * right margin when DECLRMM is on.
     *
     * @param n Number of columns. Non-positive values are no-ops.
     */
    fun cursorRight(n: Int = 1)

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
    fun saveCursor()

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
     * they belong to the parser layer, not to `:terminal-core`.
     */
    fun restoreCursor()

    /**
     * Resets the cursor to the home position `(col=0, row=0)`.
     *
     * Pen attributes are not affected.
     */
    fun resetCursor()

    // Tab stops

    /**
     * Sets a tab stop at the current cursor column (HTS, `ESC H`).
     *
     * The stop persists until cleared by [clearTabStop], [clearAllTabStops],
     * or a hard reset. As a non-printing control, HTS cancels any pending wrap.
     */
    fun setTabStop()

    /**
     * Clears the tab stop at the current cursor column (TBC 0, `CSI 0 g`).
     *
     * No-op if no stop exists at the current column. As a non-printing control,
     * TBC 0 cancels any pending wrap.
     */
    fun clearTabStop()

    /**
     * Clears all tab stops (TBC 3, `CSI 3 g`).
     *
     * After this call, [horizontalTab] advances the cursor directly to the right
     * margin until stops are re-established. As a non-printing control, TBC 3
     * cancels any pending wrap.
     */
    fun clearAllTabStops()

    /**
     * Advances the cursor to the next tab stop (HT, `0x09`).
     *
     * Clamps to the active right boundary when no stop exists to the right.
     * With DECLRMM off, that boundary is `width - 1`; with DECLRMM on, it is
     * the active horizontal right margin. HT never triggers a line wrap.
     */
    fun horizontalTab()

    /**
     * Advances the cursor forward by [count] tab stops (CHT, `CSI Ps I`).
     *
     * A count of `0` uses the ANSI default of `1`. The cursor never wraps:
     * if fewer than [count] stops remain, movement clamps at the active right
     * boundary. With DECLRMM off, that boundary is `width - 1`; with
     * DECLRMM on, it is the active horizontal right margin.
     */
    fun cursorForwardTab(count: Int = 1)

    /**
     * Moves the cursor backward by [count] tab stops (CBT, `CSI Ps Z`).
     *
     * A count of `0` uses the ANSI default of `1`. The cursor never wraps:
     * if fewer than [count] stops exist to the left, movement clamps at the
     * active left boundary. With DECLRMM off, that boundary is column `0`;
     * with DECLRMM on, it is the active horizontal left margin.
     */
    fun cursorBackwardTab(count: Int = 1)
}
