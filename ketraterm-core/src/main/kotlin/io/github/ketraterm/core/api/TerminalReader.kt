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

import io.github.ketraterm.render.api.TerminalColorPalette

/**
 * Zero-allocation read contract for the terminal buffer.
 *
 * Exposes viewport-relative state plus random-access helpers for the currently
 * active screen buffer. Out-of-bounds probes never throw; they return stable
 * sentinel values so renderers can remain branch-light.
 * Serialize reads with terminal mutations; returned [TerminalLine] views borrow
 * live storage and must not escape that serialization.
 */
public interface TerminalReader {
    /**
     * Current immutable effective palette, including application color overrides.
     * Reading retains the existing object without allocating or reading a render
     * frame. Synchronize with terminal mutations before reading; a retained palette
     * remains safe to use after releasing that synchronization.
     */
    public val palette: TerminalColorPalette

    /** Actual active screen. Read synchronously under the same serialization as terminal mutations. */
    public val isAlternateScreenActive: Boolean

    /** Current viewport width in cells. */
    public val width: Int

    /** Current viewport height in rows. */
    public val height: Int

    /** Active xterm window title. */
    public val windowTitle: String

    /** Active xterm icon title. */
    public val iconTitle: String

    /** Active cursor column in zero-based viewport coordinates. */
    public val cursorCol: Int

    /** Active cursor row in zero-based viewport coordinates. */
    public val cursorRow: Int

    /** Number of retained off-screen history lines in the active buffer. */
    public val historySize: Int

    /**
     * Returns the visible line at [row], or a shared void line when [row] is out of bounds.
     *
     * @param row Zero-based row index.
     * @return The [TerminalLine] at the specified row, or a dummy blank line if out of bounds.
     */
    public fun getLine(row: Int): TerminalLine

    /**
     * Returns the display/base codepoint at `[col, row]`.
     *
     * - Plain cells return their stored Unicode scalar value.
     * - Cluster cells return the leading codepoint of the grapheme sequence.
     * - Wide-character spacer cells return `-1`.
     * - Blank cells and out-of-bounds probes return `0`.
     *
     * @param col Column index (0-based).
     * @param row Row index (0-based).
     * @return The codepoint at the specified coordinate, or a sentinel/spacer value.
     */
    public fun getCodepointAt(
        col: Int,
        row: Int,
    ): Int
}
