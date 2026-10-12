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
package io.github.ketraterm.ui.swing.search

import io.github.ketraterm.ui.swing.render.forEachLogicalTextCell
import java.util.regex.Pattern

/** Logical-line buffering is confined to pattern searches; ordinary literals stay streaming. */
internal class TerminalSearchPatternModel(
    pattern: Pattern,
) {
    private var checkCancelled: () -> Unit = {}
    private var maxResults = Int.MAX_VALUE
    private var keepLast = false
    private var acceptsMatch: (Long, Int, Long) -> Boolean = { _, _, _ -> true }
    val highlights = TerminalSearchHighlights()
    val limitReached: Boolean get() = !keepLast && highlights.resultCount >= maxResults
    private val text = StringBuilder()
    private var positions = LongArray(0)
    private var endColumns = IntArray(0)
    private var rows = LongArray(0)
    private var ids = LongArray(0)
    private var generations = LongArray(0)
    private var rowCount = 0
    private var trimmedLength = 0
    private var untilCheck = 0
    private val input = CheckedText()
    private val matcher = pattern.matcher(input)

    fun begin(
        pattern: Pattern,
        checkCancelled: () -> Unit,
        validateRows: Boolean,
        maxResults: Int,
        keepLast: Boolean,
        acceptsMatch: (Long, Int, Long) -> Boolean,
    ) {
        this.checkCancelled = checkCancelled
        this.maxResults = maxResults
        this.keepLast = keepLast
        this.acceptsMatch = acceptsMatch
        highlights.clear()
        highlights.validatesRows = validateRows
        discardPendingLine()
        untilCheck = 0
        if (matcher.pattern() !== pattern) matcher.usePattern(pattern)
        checkCancelled()
    }

    fun appendRow(
        columns: Int,
        codes: IntArray,
        flags: IntArray,
        refs: LongArray,
        clusters: IntArray,
        row: Int,
        wrapped: Boolean,
        absoluteRow: Long,
        id: Long,
        generation: Long,
    ) {
        if (limitReached) return
        checkCancelled()
        if (rowCount == rows.size) {
            val size = maxOf(16, rowCount * 2)
            rows = rows.copyOf(size)
            ids = ids.copyOf(size)
            generations = generations.copyOf(size)
        }
        rows[rowCount] = absoluteRow
        ids[rowCount] = id
        generations[rowCount] = generation
        forEachLogicalTextCell(columns, codes, flags, refs, clusters, row, checkCancelled) { cp, from, to ->
            require(Character.isValidCodePoint(cp))
            val start = text.length
            text.appendCodePoint(cp)
            if (text.length > positions.size) {
                val size = maxOf(256, text.length, positions.size * 2)
                positions = positions.copyOf(size)
                endColumns = endColumns.copyOf(size)
            }
            for (index in start until text.length) {
                positions[index] = (rowCount.toLong() shl 32) or from.toLong()
                endColumns[index] = to
            }
            if (cp != ' '.code) trimmedLength = text.length
        }
        rowCount++
        if (!wrapped) finishLine()
    }

    fun discardPendingLine() {
        text.setLength(0)
        trimmedLength = 0
        rowCount = 0
    }

    fun finish(): TerminalSearchHighlights {
        finishLine()
        highlights.activate(if (highlights.resultCount == 0) -1 else 0)
        return highlights
    }

    private fun finishLine() {
        checkCancelled()
        if (rowCount == 0) return
        matcher.reset(input)
        while (!limitReached) {
            checkCancelled()
            val found =
                try {
                    matcher.find()
                } catch (error: StackOverflowError) {
                    throw IllegalArgumentException("Regular expression requires too much stack", error)
                }
            if (!found) break
            val start = matcher.start()
            val end = matcher.end()
            // Empty matches have no terminal cells to highlight. find() advances past them.
            if (start == end) continue
            val firstRow = (positions[start] ushr 32).toInt()
            val lastRow = (positions[end - 1] ushr 32).toInt()
            if (!acceptsMatch(rows[firstRow], positions[start].toInt(), rows[lastRow])) continue
            if (keepLast) highlights.clear()
            highlights.beginResult()
            var segmentRow = firstRow
            var from = positions[start].toInt()
            var to = endColumns[start]
            for (index in start + 1 until end) {
                if (index and 255 == 0) checkCancelled()
                val nextRow = (positions[index] ushr 32).toInt()
                if (nextRow != segmentRow) {
                    highlights.addSegment(rows[segmentRow], from, to, ids[segmentRow], generations[segmentRow])
                    segmentRow = nextRow
                    from = positions[index].toInt()
                }
                to = endColumns[index]
            }
            highlights.addSegment(rows[segmentRow], from, to, ids[segmentRow], generations[segmentRow])
            highlights.finishResult()
        }
        discardPendingLine()
    }

    /** Check inside the regex engine too, including backtracking that never returns a match. */
    private inner class CheckedText(
        private val offset: Int = 0,
        private val end: Int? = null,
    ) : CharSequence {
        override val length: Int get() = (end ?: trimmedLength) - offset

        override fun get(index: Int): Char {
            if (untilCheck-- == 0) {
                checkCancelled()
                untilCheck = 255
            }
            require(index in 0 until length)
            return text[offset + index]
        }

        override fun subSequence(
            startIndex: Int,
            endIndex: Int,
        ): CharSequence {
            require(startIndex in 0..endIndex && endIndex <= length)
            return CheckedText(offset + startIndex, offset + endIndex)
        }

        override fun toString(): String =
            buildString(length) {
                for (index in 0 until this@CheckedText.length) append(this@CheckedText[index])
            }
    }
}

internal fun compileSearchPattern(
    query: String,
    ignoreCase: Boolean,
    wholeWord: Boolean,
    regex: Boolean,
): Pattern {
    val expression = if (regex) query else Pattern.quote(query)
    val bounded = if (wholeWord) "(?<!\\w)(?:$expression)(?!\\w)" else expression
    val flags = Pattern.UNICODE_CHARACTER_CLASS or if (ignoreCase) Pattern.CASE_INSENSITIVE else 0
    return try {
        Pattern.compile(bounded, flags)
    } catch (error: StackOverflowError) {
        throw IllegalArgumentException("Regular expression is too deeply nested", error)
    }
}
