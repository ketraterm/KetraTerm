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

import io.github.ketraterm.render.api.TerminalRenderBufferKind
import io.github.ketraterm.render.api.TerminalRenderFrameReader
import io.github.ketraterm.render.cache.TerminalTextRangeCopy
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import java.util.regex.Pattern

/** Bounded collection and directional searches without a history-sized row index. */
internal class TerminalSearchWindow(
    private val reader: TerminalRenderFrameReader,
    private val query: String,
    private val ignoreCase: Boolean,
    private val pattern: Pattern? = null,
) {
    private val copy = TerminalTextRangeCopy()
    private val model = TerminalSearchModel()
    private var first = 0L
    private var last = 0L
    private var columns = 0
    private var buffer = TerminalRenderBufferKind.PRIMARY
    private var generation = 0L
    private var historyGeneration = 0L

    init {
        reader.readRenderFrame { frame ->
            first = frame.discardedCount
            last = first + frame.historySize + frame.rows - 1
            columns = frame.columns
            buffer = frame.activeBuffer
            generation = frame.contentGeneration
            historyGeneration = frame.historyContentGeneration
        }
    }

    suspend fun collect(
        origin: Long,
        onProgress: (suspend (TerminalSearchScan.Progress) -> Unit)? = null,
    ): TerminalSearchScan.Result? {
        val start = origin.coerceIn(first, last)
        val after = range(lineStart(start), last, SEARCH_RESULT_LIMIT + 1, false, { row, _, _ -> row >= start }, onProgress)
        val result = TerminalSearchHighlights().also { it.validatesRows = true }
        if (after.resultCount <= SEARCH_RESULT_LIMIT && start > first) {
            val before = range(first, lineEnd(start - 1), SEARCH_RESULT_LIMIT + 1 - after.resultCount, false, { row, _, _ -> row < start })
            result.appendResults(before)
        }
        result.appendResults(after)
        if (after.resultCount == 0) result.activate(if (result.resultCount == 0) -1 else 0) else result.activateNearest(start, 0)
        return result(result)
    }

    suspend fun visible(
        top: Long,
        bottom: Long,
    ): TerminalSearchHighlights {
        val start = top.coerceIn(first, last)
        val end = bottom.coerceIn(start, last)
        return range(lineStart(start), lineEnd(end), Int.MAX_VALUE, false, { row, _, endRow -> endRow >= start && row <= end })
    }

    suspend fun find(
        row: Long,
        column: Int,
        backwards: Boolean,
    ): TerminalSearchHighlights {
        val anchor = row.coerceIn(first, last)

        fun before(
            r: Long,
            c: Int,
        ) = r < row || r == row && c < column

        fun after(
            r: Long,
            c: Int,
        ) = r > row || r == row && c > column
        if (!backwards) {
            val following = range(lineStart(anchor), last, 1, false, { r, c, _ -> after(r, c) })
            if (following.resultCount != 0) return following
            return range(first, lineEnd(anchor), 1, false, { r, c, _ -> !after(r, c) })
        }

        suspend fun backwards(
            from: Long,
            until: Long,
            accepts: (Long, Int, Long) -> Boolean,
        ): TerminalSearchHighlights {
            var end = from
            while (end >= until) {
                val start = lineStart(end)
                val found = range(start, lineEnd(end), 1, true, accepts)
                if (found.resultCount != 0) return found
                end = start - 1
                yield()
            }
            return TerminalSearchHighlights()
        }
        val preceding = backwards(anchor, first) { r, c, _ -> before(r, c) }
        if (preceding.resultCount != 0) return preceding
        return backwards(last, anchor) { r, c, _ -> !before(r, c) }
    }

    private suspend fun range(
        start: Long,
        end: Long,
        limit: Int,
        keepLast: Boolean,
        accepts: (Long, Int, Long) -> Boolean,
        onProgress: (suspend (TerminalSearchScan.Progress) -> Unit)? = null,
    ): TerminalSearchHighlights {
        val context = currentCoroutineContext()
        model.begin(query, ignoreCase, start, { context.ensureActive() }, true, limit, keepLast, accepts, pattern)
        var next = start
        var published = 0
        while (next <= end && !model.limitReached) {
            context.ensureActive()
            if (!copy.read(reader, next, end) { context.ensureActive() }) break
            if (copy.columns != columns || copy.activeBuffer != buffer || copy.historyContentGeneration != historyGeneration) break
            if (copy.firstAbsoluteRow > next) model.discardPendingLine()
            model.append(copy)
            next = copy.lastAbsoluteRow + 1
            val completed = model.completedResults
            if (onProgress != null && next <= end && completed.resultCount > published) {
                onProgress(TerminalSearchScan.Progress(completed.snapshot(published), columns, buffer, historyGeneration))
                published = completed.resultCount
            }
            if (next <= end && !model.limitReached) yield()
        }
        return model.finish().snapshot()
    }

    private suspend fun lineStart(row: Long): Long {
        var at = row
        while (at > first) {
            currentCoroutineContext().ensureActive()
            val from = maxOf(first, at - 256)
            var found = false
            reader.readRenderFrameForAbsoluteRange(from, at - 1) { frame ->
                val top = frame.discardedCount + frame.historySize - frame.scrollbackOffset
                var r = minOf(at - 1, top + frame.rows - 1)
                while (r >= maxOf(from, top)) {
                    if (!frame.lineWrapped((r - top).toInt())) {
                        at = r + 1
                        found = true
                        break
                    }
                    r--
                }
            }
            if (found) return at
            at = from
            yield()
        }
        return at
    }

    private suspend fun lineEnd(row: Long): Long {
        var at = row
        while (at <= last) {
            currentCoroutineContext().ensureActive()
            val through = minOf(last, at + 255)
            var found = false
            reader.readRenderFrameForAbsoluteRange(at, through) { frame ->
                val top = frame.discardedCount + frame.historySize - frame.scrollbackOffset
                for (r in maxOf(at, top)..minOf(through, top + frame.rows - 1)) {
                    if (!frame.lineWrapped((r - top).toInt())) {
                        at = r
                        found = true
                        break
                    }
                }
            }
            if (found) return at
            at = through + 1
            yield()
        }
        return last
    }

    private fun result(highlights: TerminalSearchHighlights): TerminalSearchScan.Result? {
        var valid = false
        var latest = generation
        reader.readRenderFrame { frame ->
            valid = frame.columns == columns && frame.activeBuffer == buffer && frame.historyContentGeneration == historyGeneration
            latest = frame.contentGeneration
            highlights.discardBefore(frame.discardedCount) {}
        }
        if (!valid) return null
        return TerminalSearchScan.Result(highlights, columns, buffer, latest, historyGeneration, latest != generation)
    }
}

internal const val SEARCH_RESULT_LIMIT = 1000
