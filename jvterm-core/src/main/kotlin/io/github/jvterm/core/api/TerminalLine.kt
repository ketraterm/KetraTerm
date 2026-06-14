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
 * A read-only, EPHEMERAL view of a single physical terminal line.
 * * DANGER - TEMPORAL COUPLING:
 * The UI Renderer MUST NOT store or hold references to this object outside the
 * immediate execution scope of the current render frame. The backing memory
 * may mutate at any time if background output arrives.
 */
interface TerminalLine {
    /** Number of columns in this line. */
    val width: Int

    /**
     * Returns the **base (first) codepoint** for the cell at [col].
     *
     * - For plain cells this is the full Unicode scalar value.
     * - For cluster cells this is the leading codepoint of the grapheme sequence.
     *   Simple renderers that map one cell to one glyph can use this value directly.
     * - Returns [io.github.jvterm.core.model.TerminalConstants.EMPTY] (0) for blank cells.
     * - Returns [io.github.jvterm.core.model.TerminalConstants.WIDE_CHAR_SPACER] (-1)
     *   for the right half of a 2-cell wide character; renderers should skip such cells.
     *
     * @param col Column index (0-based).
     * @return The base Unicode codepoint at the specified column, or a spacer/empty sentinel.
     */
    fun getCodepoint(col: Int): Int

    /**
     * Returns the primary packed attribute word for the cell at [col].
     *
     * The primary word stores foreground/background colors plus the most common
     * SGR flags. Renderers should read it together with [getPackedExtendedAttr]
     * and decode both words with [io.github.jvterm.core.codec.AttributeCodec].
     *
     * This method is intended for render loops and performs no allocation.
     *
     * @param col Column index (0-based).
     * @return Primary packed attribute word for the cell.
     */
    fun getPackedAttr(col: Int): Long

    /**
     * Returns the extended packed attribute word for the cell at [col].
     *
     * The extended word stores underline color/style, decoration flags, conceal,
     * and the numeric hyperlink id. Renderers should read it together with
     * [getPackedAttr] and decode both words with [io.github.jvterm.core.codec.AttributeCodec].
     *
     * This method is intended for render loops and performs no allocation.
     *
     * @param col Column index (0-based).
     * @return Extended packed attribute word for the cell.
     */
    fun getPackedExtendedAttr(col: Int): Long

    /**
     * Returns `true` if the cell at [col] holds a multi-codepoint grapheme cluster
     * requiring a call to [readCluster] for full rendering.
     *
     * Defaults to `false` so that [io.github.jvterm.core.model.VoidLine] and simple
     * stub implementations need not override it.
     *
     * @param col Column index (0-based).
     * @return `true` if the cell holds a grapheme cluster, `false` otherwise.
     */
    fun isCluster(col: Int): Boolean = false

    /**
     * Copies all codepoints of the grapheme cluster at [col] into [dest] and
     * returns the number of codepoints written.
     *
     * **Zero-allocation contract:** the renderer allocates `dest` once at startup
     * and reuses it across all frames. This method never allocates.
     *
     * Returns `0` for non-cluster cells; callers should check [isCluster] first
     * or treat a return value of `0` as "use [getCodepoint] instead".
     *
     * @param col  Column index (0-based).
     * @param dest Destination array. Must have capacity >= actual cluster length;
     * there is no fixed public upper bound guaranteed by this API.
     * @return Number of codepoints written, or 0 if the cell is not a cluster.
     */
    fun readCluster(
        col: Int,
        dest: IntArray,
    ): Int = 0
}
