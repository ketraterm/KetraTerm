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
 * Read-only borrowed view of one physical terminal line.
 *
 * Read only while the owning terminal is serialized against mutation and do not
 * retain the view after releasing that serialization. Mutations may replace or reuse
 * its backing storage. Renderer-owned copies use the public render frame contract.
 */
public interface TerminalLine {
    /** Number of columns in this line. */
    public val width: Int

    /**
     * Returns the **base (first) codepoint** for the cell at [col].
     *
     * - For plain cells this is the full Unicode scalar value.
     * - For cluster cells this is the leading codepoint of the grapheme sequence.
     *   Simple renderers that map one cell to one glyph can use this value directly.
     * - Returns `0` for blank cells.
     * - Returns `-1`
     *   for the right half of a 2-cell wide character; renderers should skip such cells.
     *
     * @param col Column index (0-based).
     * @return The base Unicode codepoint at the specified column, or a spacer/empty sentinel.
     */
    public fun getCodepoint(col: Int): Int

    /**
     * Returns `true` if the cell at [col] holds a multi-codepoint grapheme cluster
     * requiring a call to [readCluster] for full rendering.
     *
     * Defaults to `false` for implementations without cluster storage.
     *
     * @param col Column index (0-based).
     * @return `true` if the cell holds a grapheme cluster, `false` otherwise.
     */
    public fun isCluster(col: Int): Boolean = false

    /**
     * Returns the number of codepoints in the cluster at [col], or `0` for a
     * scalar, blank, or wide spacer cell. This is the capacity required by
     * [readCluster], not a UTF-16 length or a display width.
     *
     * Core-owned lines answer in constant time without allocating or copying.
     * Grow a reusable destination only when this count exceeds its capacity.
     * Keep the owning terminal serialized against mutation continuously from
     * obtaining this borrowed line through sizing and copying; the count does
     * not remain valid across mutation and the line must not escape that boundary.
     *
     * Implementations with cluster storage must override this operation together
     * with [isCluster] and [readCluster]. The default returns `0` when [isCluster]
     * is false and rejects cluster cells rather than reporting an incorrect size.
     *
     * @param col Column index (0-based), within this physical line's width.
     * @return Exact cluster codepoint count, or `0` for a non-cluster cell.
     * A core-owned void line returns `0` for any column.
     * @throws IndexOutOfBoundsException for an invalid column in a core-owned physical line.
     * @throws UnsupportedOperationException if a clustered implementation has not
     * provided capacity discovery.
     */
    public fun getClusterLength(col: Int): Int {
        if (isCluster(col)) {
            throw UnsupportedOperationException("Clustered lines must implement getClusterLength")
        }
        return 0
    }

    /**
     * Copies all codepoints of the grapheme cluster at [col] into [dest] and
     * returns the number of codepoints written.
     *
     * The copy itself does not allocate. The caller owns capacity and may reuse
     * [dest] while it remains large enough. Use [getClusterLength] to discover
     * required capacity before copying, under the same uninterrupted serialization;
     * directly written clusters have no fixed public length bound. Alternatively, use
     * [io.github.ketraterm.render.api.TerminalRenderFrame.copyLine] with a
     * [io.github.ketraterm.render.api.TerminalRenderClusterDataSink]. Its callback
     * supplies the full length and a borrowed primitive range to copy before returning.
     * Keep the owning terminal serialized for either read path. An insufficient
     * destination is rejected by core-owned lines before any elements are changed.
     *
     * Returns `0` for non-cluster cells; callers should check [isCluster] first
     * or treat a return value of `0` as "use [getCodepoint] instead".
     *
     * @param col  Column index (0-based).
     * @param dest Destination array. Must have capacity >= actual cluster length;
     * there is no fixed public upper bound guaranteed by this API.
     * @return Number of codepoints written, or 0 if the cell is not a cluster.
     * @throws IndexOutOfBoundsException when a core-owned cluster exceeds [dest]'s capacity.
     */
    public fun readCluster(
        col: Int,
        dest: IntArray,
    ): Int = 0
}
