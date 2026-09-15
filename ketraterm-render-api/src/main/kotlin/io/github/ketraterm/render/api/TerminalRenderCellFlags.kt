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
package io.github.ketraterm.render.api

/**
 * Public render cell flag bit set.
 *
 * Valid combinations are:
 *
 * - [EMPTY]
 * - [EMPTY] or [WRAP_PADDING]
 * - [CODEPOINT]
 * - [CODEPOINT] or [WIDE_LEADING]
 * - [CLUSTER]
 * - [CLUSTER] or [WIDE_LEADING]
 * - [WIDE_TRAILING]
 */
object TerminalRenderCellFlags {
    /**
     * No glyph should be drawn for this cell.
     */
    const val EMPTY: Int = 1 shl 0

    /**
     * The corresponding code word contains a Unicode scalar value.
     */
    const val CODEPOINT: Int = 1 shl 1

    /**
     * This cell contains a grapheme cluster delivered through
     * [TerminalRenderClusterSink].
     */
    const val CLUSTER: Int = 1 shl 2

    /**
     * This cell is the leading cell of a width-2 glyph or cluster.
     */
    const val WIDE_LEADING: Int = 1 shl 3

    /**
     * This cell is the trailing continuation cell of a width-2 glyph or cluster.
     * Renderers must not draw text for this cell.
     */
    const val WIDE_TRAILING: Int = 1 shl 4

    /**
     * Artificial final-column blank inserted when a width-2 glyph moves to the
     * next physical row. Valid only with [EMPTY], at the last column of a row
     * whose [TerminalRenderFrame.lineWrapped] is `true`.
     *
     * Logical text extraction omits this cell when joining soft-wrapped rows;
     * ordinary empty cells still represent spaces within a logical line.
     * Rendering and rectangular selection retain its physical cell geometry.
     */
    const val WRAP_PADDING: Int = 1 shl 5

    /**
     * Returns whether [flags] is one of the valid public render cell flag
     * combinations.
     *
     * @param flags flag bit set to validate.
     * @return `true` when the bit set is valid for one render cell.
     */
    fun isValidCombination(flags: Int): Boolean =
        when (flags) {
            EMPTY,
            EMPTY or WRAP_PADDING,
            CODEPOINT,
            CODEPOINT or WIDE_LEADING,
            CLUSTER,
            CLUSTER or WIDE_LEADING,
            WIDE_TRAILING,
            -> true
            else -> false
        }
}
