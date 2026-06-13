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
package io.github.jvterm.render.api

import io.github.jvterm.render.api.TerminalRenderCellFlags.CLUSTER
import io.github.jvterm.render.api.TerminalRenderCellFlags.CODEPOINT
import io.github.jvterm.render.api.TerminalRenderCellFlags.EMPTY
import io.github.jvterm.render.api.TerminalRenderCellFlags.WIDE_LEADING
import io.github.jvterm.render.api.TerminalRenderCellFlags.WIDE_TRAILING

/**
 * Public render cell flag bit set.
 *
 * Valid combinations are:
 *
 * - [EMPTY]
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
     * Returns whether [flags] is one of the valid public render cell flag
     * combinations.
     *
     * @param flags flag bit set to validate.
     * @return `true` when the bit set is valid for one render cell.
     */
    fun isValidCombination(flags: Int): Boolean =
        when (flags) {
            EMPTY,
            CODEPOINT,
            CODEPOINT or WIDE_LEADING,
            CLUSTER,
            CLUSTER or WIDE_LEADING,
            WIDE_TRAILING,
            -> true
            else -> false
        }
}
