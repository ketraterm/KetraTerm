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
 * Zero-allocation read contract for the terminal buffer.
 *
 * Exposes viewport-relative state plus random-access helpers for the currently
 * active screen buffer. Out-of-bounds probes never throw; they return stable
 * sentinel values so renderers can remain branch-light.
 */
interface TerminalReader {
    /** Current viewport width in cells. */
    val width: Int

    /** Current viewport height in rows. */
    val height: Int

    /** Active xterm window title. */
    val windowTitle: String

    /** Active xterm icon title. */
    val iconTitle: String

    /** Active cursor column in zero-based viewport coordinates. */
    val cursorCol: Int

    /** Active cursor row in zero-based viewport coordinates. */
    val cursorRow: Int

    /** Number of retained off-screen history lines in the active buffer. */
    val historySize: Int

    /**
     * Returns the visible line at [row], or a shared void line when [row] is out of bounds.
     *
     * @param row Zero-based row index.
     * @return The [TerminalLine] at the specified row, or a dummy blank line if out of bounds.
     */
    fun getLine(row: Int): TerminalLine

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
    fun getCodepointAt(
        col: Int,
        row: Int,
    ): Int

    /**
     * Returns the primary packed cell attribute word at `[col, row]`.
     *
     * Out-of-bounds column probes return the active primary pen word. This
     * mirrors the terminal's current erase/write attribute for off-grid queries.
     * Decode this value together with [getPackedExtendedAttrAt] via
     * [io.github.ketraterm.core.codec.AttributeCodec].
     *
     * @param col Column index (0-based).
     * @param row Row index (0-based).
     * @return Primary packed attribute word for the cell or active pen.
     */
    fun getPackedAttrAt(
        col: Int,
        row: Int,
    ): Long

    /**
     * Returns the extended packed cell attribute word at `[col, row]`.
     *
     * Out-of-bounds column probes return the active extended pen word. Decode
     * this value together with [getPackedAttrAt] via
     * [io.github.ketraterm.core.codec.AttributeCodec].
     *
     * @param col Column index (0-based).
     * @param row Row index (0-based).
     * @return Extended packed attribute word for the cell or active pen.
     */
    fun getPackedExtendedAttrAt(
        col: Int,
        row: Int,
    ): Long
}
