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
 * Full public contract for the terminal buffer.
 *
 * Composes all role-specific interfaces into a single surface for host
 * applications and host tests.
 *
 * Cursor positions and cell/line reads use zero-based viewport coordinates.
 * Margin and rectangular commands retain the DEC one-based conventions documented
 * on each method, including zero for omitted rectangle parameters. The host adapter
 * owns conversion from parser commands; the parser does not own grid coordinates.
 *
 * Instances returned by [io.github.ketraterm.core.TerminalBuffers] require external
 * serialization of mutations and grid/cursor/history reads. Atomic mode snapshots
 * may be read independently as documented by [TerminalModeReader]. Borrowed line and render views are valid
 * only while that serialization is held. Session-backed hosts access terminal state
 * through their session boundary rather than concurrently mutating this buffer.
 *
 * Host adapters should depend on the narrower interfaces they need; this facade
 * composes the complete core contract for direct embedders.
 */
public interface TerminalBuffer :
    TerminalWriter,
    TerminalCursor,
    TerminalModeController,
    TerminalModeReader,
    TerminalResponseChannel,
    TerminalReader,
    TerminalInspector {
    /**
     * Resizes the terminal to [newWidth] x [newHeight].
     *
     * Existing content is reflowed to the new width. The cursor is relocated to
     * the corresponding position in the reflowed content. Height-only changes retain
     * physical primary rows without reflow, adjusting blank rows and the viewport boundary.
     * Scrollback history is
     * preserved within the configured capacity. Both the primary and alternate
     * grids are resized, and both screen buffers reset their scroll regions to
     * the full viewport. Tab stops are resized non-destructively: surviving
     * custom stops are preserved, stops past the new width are dropped, and any
     * newly exposed columns receive the default 8-column VT rhythm. Saved-cursor
     * state is clamped to the new bounds.
     *
     * @param newWidth New terminal width in cells. Must be > 0.
     * @param newHeight New terminal height in rows. Must be > 0.
     * @param oldScrollbackOffset The active scrollback offset before the resize.
     * @return A [Pair] of (newScrollbackOffset, newHistorySize), allowing the caller to
     *   re-anchor a scrollback viewport that was active at [oldScrollbackOffset] before
     *   the reflow. Both values describe the active buffer, with the offset clamped
     *   to its history. An active alternate screen returns zero for both values.
     * @throws IllegalArgumentException if either dimension is <= 0, or adding
     * [newHeight] to the configured primary history capacity would overflow [Int].
     * Invalid dimensions are rejected before allocation or state mutation.
     */
    public fun resize(
        newWidth: Int,
        newHeight: Int,
        oldScrollbackOffset: Int = 0,
    ): Pair<Int, Int>

    /**
     * Performs a full terminal reset (RIS, `ESC c`).
     *
     * Clears all visible content and scrollback history; resets the pen to
     * defaults; homes the cursor; restores the scroll region to the full
     * viewport; resets all mode flags to their VT defaults; and restores tab
     * stops to the standard 8-column VT100 spacing. If the alternate buffer is
     * active, exits it first.
     */
    public fun reset()

    /**
     * Performs a soft terminal reset (DECSTR, `CSI ! p`).
     *
     * Leaves visible content, scrollback history, tab stops, dimensions, and the
     * active screen selection intact. Resets modes and write state that affect
     * subsequent output/input coordination: insert, origin, application
     * cursor/keypad, cursor presentation, modify-other-keys, margins, pen
     * attributes, selective-erase write protection, and pending wrap. The saved
     * cursor slots are replaced with a home/default restore target.
     */
    public fun softReset()

    /**
     * Executes DECCOLM (`CSI ? 3 h` / `CSI ? 3 l`) as a core-owned macro command.
     *
     * Valid widths are `80` and `132`; all other values are ignored.
     *
     * Sequence:
     * 1. Resize both buffers to `newWidth × currentHeight`
     * 2. Destructively clear the active display and its history
     * 3. Home the active cursor to absolute `(0, 0)` regardless of DECOM
     * 4. Reset active scroll margins to the full viewport
     * 5. Reset active left/right margins to the full width
     * 6. Reset tab stops to the default 8-column rhythm for the new width
     * 7. Cancel pending wrap
     * 8. Preserve both DECSC saved-cursor slots unchanged
     *
     * When the alternate screen is active, DECCOLM follows the xterm-style
     * policy used by [resize]: the alternate screen is wiped at the new width
     * while the primary screen is reflowed in the background.
     *
     * @param newWidth The target width, either 80 or 132 columns.
     */
    public fun executeDeccolm(newWidth: Int)
}
