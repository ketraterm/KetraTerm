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

import io.github.ketraterm.render.api.TerminalRenderCellFlags
import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.render.cache.TerminalTextRangeCopy
import io.github.ketraterm.ui.swing.render.forEachLogicalTextCell

/** Matches decoded UTF-16 as it arrives, retaining only query-sized candidate geometry. */
internal class TerminalSearchModel {
    private val highlights = TerminalSearchHighlights()

    // A partial logical line must remain discardable after history eviction.
    private val pending = TerminalSearchHighlights()
    private val previousConfirmed = TerminalSearchHighlights()
    private var maxResults = Int.MAX_VALUE
    private var keepLast = false
    private var acceptsMatch: (Long, Int, Long) -> Boolean = { _, _, _ -> true }
    val limitReached: Boolean get() = !keepLast && highlights.resultCount + confirmedResults >= maxResults
    private var confirmedResults = 0
    private var query = ""
    private var prefixes = IntArray(0)
    private var skipOrdinaryRuns = false
    private var shifts = IntArray(0)
    private var textScratch = CharArray(0)
    private var foldedLast = ' '
    private var ignoreCase = false
    private var matched = 0
    private var nextPosition = 0
    private var rows = LongArray(0)
    private var ranges = LongArray(0)
    private var ids = LongArray(0)
    private var generations = LongArray(0)
    private var currentRow = 0L
    private var currentId = 0L
    private var currentGeneration = 0L
    private var checkCancelled: () -> Unit = {}
    private var untilCheck = 0

    fun search(
        cache: TerminalRenderCache,
        query: String,
        ignoreCase: Boolean,
    ): TerminalSearchHighlights {
        val first = cache.discardedCount + cache.historySize - cache.scrollbackOffset
        begin(query, ignoreCase, first)
        append(cache, first)
        return finish()
    }

    fun begin(
        query: String,
        ignoreCase: Boolean,
        firstAbsoluteRow: Long,
        checkCancelled: () -> Unit = {},
        validateRows: Boolean = false,
        maxResults: Int = Int.MAX_VALUE,
        keepLast: Boolean = false,
        acceptsMatch: (Long, Int, Long) -> Boolean = { _, _, _ -> true },
    ) {
        this.maxResults = maxResults
        this.keepLast = keepLast
        this.acceptsMatch = acceptsMatch
        this.query = query
        this.ignoreCase = ignoreCase
        this.checkCancelled = checkCancelled
        currentRow = firstAbsoluteRow
        highlights.clear()
        highlights.validatesRows = validateRows
        discardPendingLine()
        untilCheck = 0
        checkCancelled()
        prefixes = IntArray(query.length)
        var index = 1
        var prefix = 0
        while (index < query.length) {
            checkStep()
            if (query[index].equals(query[prefix], ignoreCase)) {
                prefixes[index++] = ++prefix
            } else if (prefix > 0) {
                prefix = prefixes[prefix - 1]
            } else {
                index++
            }
        }
        skipOrdinaryRuns = query.length in 4..64
        if (skipOrdinaryRuns) {
            if (shifts.isEmpty()) {
                shifts = IntArray(128)
                textScratch = CharArray(256)
            }
            shifts.fill(query.length)
            // Later positions shorten the shift, including collisions in the folded-character table.
            for (position in 0 until query.lastIndex) {
                shifts[fold(query[position]).code and 127] = query.lastIndex - position
            }
            foldedLast = fold(query.last())
        }
        if (rows.size < query.length) {
            rows = LongArray(query.length)
            ranges = LongArray(query.length)
            ids = LongArray(query.length)
            generations = LongArray(query.length)
        }
    }

    fun discardPendingLine() {
        matched = 0
        nextPosition = 0
        pending.clear()
        previousConfirmed.clear()
        confirmedResults = 0
    }

    fun append(
        cache: TerminalRenderCache,
        firstRow: Long,
    ) {
        if (query.isEmpty()) return
        for (row in 0 until cache.rows) {
            currentRow = firstRow + row
            currentId = cache.lineIds[row]
            currentGeneration = cache.lineGenerations[row]
            appendRow(cache.columns, cache.codeWords, cache.flags, cache.clusterRefs, cache.clusterCodepoints, row, cache.lineWrapped[row])
        }
    }

    fun append(copy: TerminalTextRangeCopy) {
        if (query.isEmpty()) return
        for (row in 0 until copy.rows) {
            if (limitReached) break
            currentRow = copy.firstAbsoluteRow + row
            currentId = copy.lineIds[row]
            currentGeneration = copy.lineGenerations[row]
            appendRow(copy.columns, copy.codeWords, copy.flags, copy.clusterRefs, copy.clusterCodepoints, row, copy.lineWrapped[row])
        }
    }

    fun finish(): TerminalSearchHighlights {
        finishLine()
        highlights.activate(if (highlights.resultCount == 0) -1 else 0)
        return highlights
    }

    fun reuseMatches(
        source: TerminalSearchHighlights,
        firstRow: Long,
        lastRow: Long,
    ) {
        val first = source.firstResultAtOrAfter(firstRow)
        val end =
            minOf(
                source.firstResultAtOrAfter(lastRow + 1),
                first + minOf(source.resultCount - first, maxResults - highlights.resultCount),
            )
        highlights.appendResults(source, first, end, checkCancelled)
    }

    val completedResults: TerminalSearchHighlights get() = highlights

    private fun finishLine() {
        checkCancelled()
        // Only matches ending before the trailing ASCII spaces survive trimming.
        if (keepLast) {
            val source = if (confirmedResults > 0) pending else previousConfirmed
            if (source.resultCount > 0) {
                highlights.clear()
                highlights.appendResults(source, 0, 1, checkCancelled)
            }
        } else {
            highlights.appendResults(pending, 0, confirmedResults, checkCancelled)
        }
        discardPendingLine()
    }

    private fun appendRow(
        columns: Int,
        codes: IntArray,
        flags: IntArray,
        refs: LongArray,
        clusters: IntArray,
        row: Int,
        wrapped: Boolean,
    ) {
        checkCancelled()
        var end = columns
        val offset = row * columns
        if (!wrapped) {
            while (end > 0) {
                checkStep()
                val index = offset + end - 1
                val cellFlags = flags[index]
                if (cellFlags and COMPLEX_FLAGS != 0 ||
                    cellFlags and TerminalRenderCellFlags.CODEPOINT != 0 &&
                    codes[index] != ' '.code
                ) {
                    break
                }
                end--
            }
        }
        val firstCharacter = query[0]
        var column = 0
        while (column < end) {
            checkCancelled()
            val start = column
            val limit = minOf(end, column + 256)
            var lastNonSpace = -1
            while (column < limit) {
                val cellFlags = flags[offset + column]
                val cp = if (cellFlags and TerminalRenderCellFlags.CODEPOINT != 0) codes[offset + column] else ' '.code
                if (cellFlags and COMPLEX_FLAGS != 0 || cp !in 0..0xFFFF) break
                val character = cp.toChar()
                if (skipOrdinaryRuns) {
                    textScratch[column - start] = character
                    if (character != ' ') lastNonSpace = column - start
                } else {
                    if (matched != 0 || character.equals(firstCharacter, ignoreCase)) accept(character, column, column + 1)
                    if (character != ' ') confirmedResults = pending.resultCount
                }
                column++
            }
            if (column > start) {
                if (skipOrdinaryRuns) appendOrdinaryRun(start, column - start, lastNonSpace)
                continue
            }
            do {
                column = minOf(end, column + if (flags[offset + column] and TerminalRenderCellFlags.WIDE_LEADING != 0) 2 else 1)
                if (column >= limit) break
                val cellFlags = flags[offset + column]
                val cp = if (cellFlags and TerminalRenderCellFlags.CODEPOINT != 0) codes[offset + column] else ' '.code
            } while (cellFlags and COMPLEX_FLAGS != 0 || cp !in 0..0xFFFF)
            forEachLogicalTextCell(columns, codes, flags, refs, clusters, row, checkCancelled, column, start) { cp, from, to ->
                require(Character.isValidCodePoint(cp))
                if (cp <= 0xFFFF) {
                    val character = cp.toChar()
                    if (matched != 0 || character.equals(firstCharacter, ignoreCase)) accept(character, from, to)
                } else {
                    accept(Character.highSurrogate(cp), from, to)
                    accept(Character.lowSurrogate(cp), from, to)
                }
                if (cp != ' '.code) confirmedResults = pending.resultCount
            }
        }
        if (!wrapped) finishLine()
    }

    private fun fold(character: Char): Char =
        if (!ignoreCase) {
            character
        } else if (character < '\u0080') {
            if (character in 'A'..'Z') character + 32 else character
        } else {
            character.uppercaseChar().lowercaseChar()
        }

    private fun appendOrdinaryRun(
        startColumn: Int,
        count: Int,
        lastNonSpace: Int,
    ) {
        var position = 0
        // Text later in this captured run confirms earlier matches ending in spaces.
        if (lastNonSpace >= 0) confirmedResults = pending.resultCount
        while (matched != 0 && position < count) {
            accept(textScratch[position], startColumn + position, startColumn + position + 1)
            if (position <= lastNonSpace) confirmedResults = pending.resultCount
            position++
        }
        // Bound comparison work before falling back to the linear streaming matcher.
        var remainingComparisons = count * 2
        candidates@ while (position <= count - query.length) {
            checkStep()
            val last = fold(textScratch[position + query.lastIndex])
            remainingComparisons--
            if (last == foldedLast) {
                var index = query.lastIndex - 1
                while (index >= 0) {
                    if (--remainingComparisons < 0) break@candidates
                    checkStep()
                    if (!textScratch[position + index].equals(query[index], ignoreCase)) break
                    index--
                }
                if (index < 0) {
                    if (prepareMatch(currentRow, startColumn + position, currentRow)) {
                        pending.beginResult()
                        pending.addSegment(
                            currentRow,
                            startColumn + position,
                            startColumn + position + query.length,
                            currentId,
                            currentGeneration,
                        )
                        pending.finishResult()
                    }
                    position += query.length
                    if (position - 1 <= lastNonSpace) confirmedResults = pending.resultCount
                    continue
                }
            }
            position += shifts[last.code and 127]
        }
        // Preserve every possible prefix that can continue into the next run or row.
        while (position < count) {
            val character = textScratch[position]
            if (matched != 0 || character.equals(query[0], ignoreCase)) {
                accept(character, startColumn + position, startColumn + position + 1)
            }
            if (position <= lastNonSpace) confirmedResults = pending.resultCount
            position++
            checkStep()
        }
    }

    private fun checkStep() {
        if (untilCheck-- == 0) {
            checkCancelled()
            untilCheck = 255
        }
    }

    private fun accept(
        character: Char,
        start: Int,
        end: Int,
    ) {
        checkStep()
        while (matched > 0 && !character.equals(query[matched], ignoreCase)) {
            matched = prefixes[matched - 1]
            checkStep()
        }
        if (character.equals(query[matched], ignoreCase)) {
            rows[nextPosition] = currentRow
            ranges[nextPosition] = (start.toLong() shl 32) or end.toLong()
            ids[nextPosition] = currentId
            generations[nextPosition] = currentGeneration
            nextPosition++
            if (nextPosition == query.length) nextPosition = 0
            matched++
            if (matched == query.length) {
                appendMatch()
                matched = 0
            }
        }
    }

    private fun prepareMatch(
        row: Long,
        column: Int,
        endRow: Long,
    ): Boolean {
        if (!acceptsMatch(row, column, endRow)) return false
        if (keepLast) {
            if (confirmedResults > 0) {
                previousConfirmed.clear()
                previousConfirmed.appendResults(pending, 0, 1)
            }
            pending.clear()
            confirmedResults = 0
        }
        return highlights.resultCount + pending.resultCount < maxResults || keepLast
    }

    private fun appendMatch() {
        if (!prepareMatch(rows[nextPosition], (ranges[nextPosition] ushr 32).toInt(), currentRow)) return
        pending.beginResult()
        var position = nextPosition
        var segmentRow = rows[position]
        var start = (ranges[position] ushr 32).toInt()
        var end = ranges[position].toInt()
        var id = ids[position]
        var generation = generations[position]
        repeat(query.length) { index ->
            if (index and 255 == 0) checkCancelled()
            if (rows[position] != segmentRow) {
                pending.addSegment(segmentRow, start, end, id, generation)
                segmentRow = rows[position]
                start = (ranges[position] ushr 32).toInt()
                id = ids[position]
                generation = generations[position]
            }
            end = ranges[position].toInt()
            position++
            if (position == query.length) position = 0
        }
        pending.addSegment(segmentRow, start, end, id, generation)
        pending.finishResult()
    }

    private companion object {
        const val COMPLEX_FLAGS =
            TerminalRenderCellFlags.CLUSTER or TerminalRenderCellFlags.WIDE_LEADING or
                TerminalRenderCellFlags.WIDE_TRAILING or TerminalRenderCellFlags.WRAP_PADDING
    }
}
