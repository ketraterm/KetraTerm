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
package io.github.ketraterm.ui.swing.render

import io.github.ketraterm.render.api.TerminalRenderCellFlags
import io.github.ketraterm.render.cache.TerminalRenderCache

/** Shared scalar/cluster decoding and terminal-cell spans for search and hyperlink extraction. */
internal inline fun forEachLogicalTextCell(
    cache: TerminalRenderCache,
    row: Int,
    checkCancelled: () -> Unit = {},
    endColumnExclusive: Int = cache.columns,
    append: (codePoint: Int, startColumn: Int, endColumn: Int) -> Unit,
) {
    forEachLogicalTextCell(
        cache.columns,
        cache.codeWords,
        cache.flags,
        cache.clusterRefs,
        cache.clusterCodepoints,
        row,
        checkCancelled,
        endColumnExclusive,
        append,
    )
}

internal inline fun forEachLogicalTextCell(
    columns: Int,
    codeWords: IntArray,
    cellFlags: IntArray,
    clusterRefs: LongArray,
    clusterCodepoints: IntArray,
    row: Int,
    checkCancelled: () -> Unit,
    endColumnExclusive: Int = columns,
    append: (codePoint: Int, startColumn: Int, endColumn: Int) -> Unit,
) {
    var column = 0
    // Count skipped cells and cluster payloads too, so every decoding path remains bounded.
    var untilCancellationCheck = 0
    while (column < endColumnExclusive) {
        if (untilCancellationCheck-- == 0) {
            checkCancelled()
            untilCancellationCheck = 255
        }
        val index = row * columns + column
        val flags = cellFlags[index]
        if (flags and (TerminalRenderCellFlags.WIDE_TRAILING or TerminalRenderCellFlags.WRAP_PADDING) != 0) {
            column++
            continue
        }
        val endColumn = minOf(columns, column + if (flags and TerminalRenderCellFlags.WIDE_LEADING != 0) 2 else 1)
        val ref = clusterRefs[index]
        if (flags and TerminalRenderCellFlags.CLUSTER != 0 && ref != 0L) {
            val start = (ref ushr 32).toInt()
            val end = start + ref.toInt()
            for (offset in start until end) {
                if (untilCancellationCheck-- == 0) {
                    checkCancelled()
                    untilCancellationCheck = 255
                }
                append(clusterCodepoints[offset], column, endColumn)
            }
        } else {
            val codePoint = if (flags and TerminalRenderCellFlags.CODEPOINT != 0) codeWords[index] else ' '.code
            append(codePoint, column, endColumn)
        }
        column = endColumn
    }
}
