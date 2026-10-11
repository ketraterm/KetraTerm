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

/** Worker-owned bounded cell copy. Results and longest logical-line scratch grow with retained text. */
internal class TerminalSearchScan {
    private val copy = TerminalTextRangeCopy()
    private var cached: RowIndex? = null
    private var spare: RowIndex? = null

    suspend fun scan(
        reader: TerminalRenderFrameReader,
        model: TerminalSearchModel,
        query: String,
        ignoreCase: Boolean,
        onProgress: (suspend (Progress) -> Unit)? = null,
    ): Result? {
        val context = currentCoroutineContext()
        var first = 0L
        var last = 0L
        var columns = 0
        var buffer = TerminalRenderBufferKind.PRIMARY
        var generation = 0L
        var historyContentGeneration = 0L
        reader.readRenderFrame { frame ->
            first = frame.discardedCount
            last = first + frame.historySize + frame.rows - 1L
            columns = frame.columns
            buffer = frame.activeBuffer
            generation = frame.contentGeneration
            historyContentGeneration = frame.historyContentGeneration
        }
        val old =
            cached?.takeIf {
                it.reader === reader &&
                    it.query == query &&
                    it.ignoreCase == ignoreCase &&
                    it.columns == columns &&
                    it.buffer == buffer &&
                    it.historyGeneration == historyContentGeneration
            }
        val index = (spare ?: RowIndex()).also { spare = null }
        index.reset(reader, query, ignoreCase, first, (last - first + 1).toInt(), columns, buffer, historyContentGeneration)
        model.begin(query, ignoreCase, first, { context.ensureActive() }, validateRows = true)
        var lastGeneration = generation
        var published = 0
        var nextPublication = first

        suspend fun publish(through: Long) {
            val completed = model.completedResults
            if (onProgress != null && through < last && through >= nextPublication && completed.resultCount > published) {
                val delta = completed.snapshot(published) { context.ensureActive() }
                published = completed.resultCount
                nextPublication = through + 4096
                onProgress(Progress(delta, columns, buffer, historyContentGeneration))
            }
        }

        suspend fun scanRange(
            start: Long,
            end: Long,
            verifyWrap: Boolean,
        ): Boolean {
            var next = start
            while (next <= end) {
                context.ensureActive()
                val copied = copy.read(reader, next, end) { context.ensureActive() }
                val copiedFirst = copy.firstAbsoluteRow
                val copiedLast = copy.lastAbsoluteRow
                if (copied &&
                    (copy.columns != columns || copy.activeBuffer != buffer || copy.historyContentGeneration != historyContentGeneration)
                ) {
                    return false
                }
                if (copied) {
                    lastGeneration = copy.contentGeneration
                    for (row in 0 until copy.rows) {
                        val target = (copiedFirst + row - first).toInt()
                        if (verifyWrap && index.wrapped[target] != copy.lineWrapped[row]) return false
                        index.ids[target] = copy.lineIds[row]
                        index.generations[target] = copy.lineGenerations[row]
                        index.wrapped[target] = copy.lineWrapped[row]
                    }
                }
                if (copiedFirst > next) model.discardPendingLine()
                if (copiedFirst > end) break
                if (copied) model.append(copy) else model.discardPendingLine()
                if (!copied || copiedLast == end) break
                next = copiedLast + 1L
                publish(copiedLast)
                yield()
            }
            return true
        }
        if (old == null) {
            if (!scanRange(first, last, false)) return null
        } else {
            // Metadata only: unchanged history never enters the text-copy/decoder path.
            var next = first
            while (next <= last) {
                context.ensureActive()
                val end = minOf(last, next + 255)
                var valid = true
                reader.readRenderFrameForAbsoluteRange(next, end) { frame ->
                    valid =
                        frame.columns == columns &&
                        frame.activeBuffer == buffer &&
                        frame.historyContentGeneration == historyContentGeneration
                    if (!valid) return@readRenderFrameForAbsoluteRange
                    val top = frame.discardedCount + frame.historySize - frame.scrollbackOffset
                    val from = maxOf(next, top)
                    val to = minOf(end, top + frame.rows - 1)
                    for (absolute in from..to) {
                        val row = (absolute - top).toInt()
                        val target = (absolute - first).toInt()
                        index.ids[target] = frame.lineId(row)
                        index.generations[target] = frame.lineGeneration(row)
                        index.wrapped[target] = frame.lineWrapped(row)
                    }
                }
                if (!valid) return null
                next = end + 1
                yield()
            }
            var start = 0
            while (start < index.count) {
                context.ensureActive()
                var end = start
                var dirty = false
                do {
                    if (end and 255 == 0) context.ensureActive()
                    val previous = first + end - old.first
                    if (previous !in 0L until old.count.toLong() ||
                        index.ids[end] == 0L ||
                        index.ids[end] != old.ids[previous.toInt()] ||
                        index.generations[end] != old.generations[previous.toInt()] ||
                        index.wrapped[end] != old.wrapped[previous.toInt()]
                    ) {
                        dirty = true
                    }
                    end++
                } while (end < index.count && index.wrapped[end - 1])
                val oldStart = first + start - old.first
                // A split, or eviction of a logical-line prefix, changes matching semantics.
                if (oldStart > 0 && oldStart <= old.count && old.wrapped[oldStart.toInt() - 1]) dirty = true
                if (end == index.count && index.wrapped[end - 1] && first + end - old.first < old.count) dirty = true
                if (dirty) {
                    if (!scanRange(first + start, first + end - 1, true)) return null
                } else {
                    model.reuseMatches(old.highlights, first + start, first + end - 1)
                }
                publish(first + end - 1)
                start = end
                if (start and 255 == 0) yield()
            }
        }
        val highlights = model.finish()
        var retainedFirst = first
        var valid = true
        reader.readRenderFrame { frame ->
            retainedFirst = frame.discardedCount
            lastGeneration = frame.contentGeneration
            valid = frame.columns == columns && frame.activeBuffer == buffer && frame.historyContentGeneration == historyContentGeneration
        }
        if (!valid) return null
        highlights.discardBefore(retainedFirst) { context.ensureActive() }
        // Only a stable pass becomes the next reuse baseline. Publications may still
        // show validated results from a moving source while a follow-up catches up.
        var reusable = generation == lastGeneration
        if (reusable) {
            for (row in 0 until index.count) {
                if (row and 255 == 0) context.ensureActive()
                if (index.ids[row] == 0L) {
                    reusable = false
                    break
                }
            }
        }
        if (reusable) {
            index.highlights.clear()
            index.highlights.validatesRows = true
            index.highlights.appendResults(highlights, checkCancelled = { context.ensureActive() })
        }
        if (reusable) {
            spare = cached
            cached = index
        } else {
            // Keep the last stable baseline during continuous output. Its rows are
            // still individually validated on the next pass.
            spare = index
        }
        return Result(highlights, columns, buffer, lastGeneration, historyContentGeneration, generation != lastGeneration)
    }

    internal class Progress(
        val highlights: TerminalSearchHighlights,
        val columns: Int,
        val buffer: TerminalRenderBufferKind,
        val historyContentGeneration: Long,
    )

    private class RowIndex {
        var reader: TerminalRenderFrameReader? = null
        var query = ""
        var ignoreCase = false
        var first = 0L
        var count = 0
        var columns = 0
        var buffer = TerminalRenderBufferKind.PRIMARY
        var historyGeneration = 0L
        var ids = LongArray(0)
        var generations = LongArray(0)
        var wrapped = BooleanArray(0)
        val highlights = TerminalSearchHighlights()

        fun reset(
            reader: TerminalRenderFrameReader,
            query: String,
            ignoreCase: Boolean,
            first: Long,
            count: Int,
            columns: Int,
            buffer: TerminalRenderBufferKind,
            historyGeneration: Long,
        ) {
            this.reader = reader
            this.query = query
            this.ignoreCase = ignoreCase
            this.first = first
            this.count = count
            this.columns = columns
            this.buffer = buffer
            this.historyGeneration = historyGeneration
            if (ids.size < count) {
                ids = LongArray(count)
                generations = LongArray(count)
                wrapped = BooleanArray(count)
            } else {
                ids.fill(0L, 0, count)
                wrapped.fill(false, 0, count)
            }
        }
    }

    internal class Result(
        val highlights: TerminalSearchHighlights,
        val columns: Int,
        val buffer: TerminalRenderBufferKind,
        val generation: Long,
        val historyContentGeneration: Long,
        val changedDuringScan: Boolean,
    )
}
