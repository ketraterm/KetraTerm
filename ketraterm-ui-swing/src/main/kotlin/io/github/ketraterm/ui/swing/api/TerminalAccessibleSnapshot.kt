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
package io.github.ketraterm.ui.swing.api

import io.github.ketraterm.render.api.TerminalRenderCellFlags
import io.github.ketraterm.render.cache.TerminalRenderCache
import java.text.BreakIterator
import java.util.*
import javax.accessibility.AccessibleText

/** Detached text and cell offsets for the displayed rows. No session or cache storage escapes. */
internal class TerminalAccessibleSnapshot private constructor(
    val text: String,
    val firstRow: Int,
    val lastRow: Int,
    val columns: Int,
    private val starts: IntArray,
    private val ends: IntArray,
    val caret: Int,
    val selectionStart: Int,
    val selectionEnd: Int,
) {
    fun indexAt(
        column: Int,
        row: Int,
    ): Int {
        if (row !in firstRow..lastRow || column !in 0 until columns) return -1
        val cell = (row - firstRow) * columns + column
        return if (starts[cell] < ends[cell]) starts[cell] else -1
    }

    /** Returns a packed row/column in the displayed frame, or -1 for a line separator. */
    fun cellAt(index: Int): Long {
        if (index !in text.indices) return -1L
        var low = 0
        var high = ends.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (ends[middle] <= index) low = middle + 1 else high = middle
        }
        if (low == ends.size || starts[low] > index) return -1L
        return ((firstRow + low / columns).toLong() shl 32) or (low % columns).toLong()
    }

    fun textAt(
        part: Int,
        index: Int,
        direction: Int,
        locale: Locale,
    ): String? {
        if (index !in text.indices) return null
        if (part == AccessibleText.CHARACTER) {
            val position = index + direction
            return if (position in text.indices) text.substring(position, position + 1) else null
        }
        val iterator =
            when (part) {
                AccessibleText.WORD -> BreakIterator.getWordInstance(locale)
                AccessibleText.SENTENCE -> BreakIterator.getSentenceInstance(locale)
                else -> return null
            }
        iterator.setText(text)
        var end = iterator.following(index)
        var start = iterator.previous()
        if (direction < 0) {
            end = start
            start = iterator.previous()
        } else if (direction > 0) {
            start = end
            end = iterator.following(end)
        }
        return if (start == BreakIterator.DONE || end == BreakIterator.DONE) null else text.substring(start, end)
    }

    companion object {
        val EMPTY = TerminalAccessibleSnapshot("", 0, -1, 0, IntArray(0), IntArray(0), -1, -1, -1)

        fun capture(
            cache: TerminalRenderCache,
            firstRow: Int,
            lastRow: Int,
            selection: CellSelection?,
        ): TerminalAccessibleSnapshot {
            if (!cache.hasFrame || firstRow > lastRow) return EMPTY
            val columns = cache.columns
            val count = (lastRow - firstRow + 1) * columns
            val starts = IntArray(count)
            val ends = IntArray(count)
            val text = StringBuilder(count)
            for (row in firstRow..lastRow) {
                if (row > firstRow && !cache.lineWrapped[row - 1]) text.append('\n')
                val offset = cache.rowOffset(row)
                for (column in 0 until columns) {
                    val cell = (row - firstRow) * columns + column
                    val flags = cache.flags[offset + column]
                    if (flags and TerminalRenderCellFlags.WIDE_TRAILING != 0 && column > 0) {
                        starts[cell] = starts[cell - 1]
                        ends[cell] = ends[cell - 1]
                        continue
                    }
                    starts[cell] = text.length
                    when {
                        flags and TerminalRenderCellFlags.WRAP_PADDING != 0 -> Unit
                        flags and TerminalRenderCellFlags.CLUSTER != 0 -> {
                            val ref = cache.clusterRefs[offset + column]
                            var cp = cache.clusterOffset(ref)
                            val end = cp + cache.clusterLength(ref)
                            while (cp < end) text.appendCodePoint(cache.clusterCodepoints[cp++])
                        }
                        flags and TerminalRenderCellFlags.CODEPOINT != 0 -> text.appendCodePoint(cache.codeWords[offset + column])
                        else -> text.append(' ')
                    }
                    ends[cell] = text.length
                }
            }

            fun edge(
                row: Int,
                column: Int,
                end: Boolean,
            ): Int {
                if (row < firstRow) return 0
                if (row > lastRow) return text.length
                val offset = (row - firstRow) * columns
                return when {
                    column == 0 -> starts[offset]
                    column >= columns -> ends[offset + columns - 1]
                    end -> ends[offset + column - 1]
                    else -> starts[offset + column]
                }
            }
            val caret =
                if (cache.cursorVisible && cache.cursorRow in firstRow..lastRow) {
                    edge(cache.cursorRow, cache.cursorColumn, false)
                } else {
                    -1
                }
            var selectionStart = caret
            var selectionEnd = caret
            if (selection != null &&
                !selection.isBlock &&
                !selection.isEmpty &&
                selection.endRow >= firstRow &&
                selection.startRow <= lastRow
            ) {
                selectionStart = edge(selection.startRow, selection.startColumn, false)
                selectionEnd = edge(selection.endRow, selection.endColumn, true)
            }
            return TerminalAccessibleSnapshot(
                text.toString(),
                firstRow,
                lastRow,
                columns,
                starts,
                ends,
                caret,
                selectionStart,
                selectionEnd,
            )
        }
    }
}
