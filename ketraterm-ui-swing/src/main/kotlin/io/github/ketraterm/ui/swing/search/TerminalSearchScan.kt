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

import io.github.ketraterm.render.api.*
import io.github.ketraterm.render.cache.TerminalRenderCache
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield

/** Worker-owned bounded cell copy. Results and longest logical-line scratch grow with retained text. */
internal class TerminalSearchScan {
    private val cache = TerminalRenderCache(1, 1, rowCapacityReserve = COPY_ROWS - 1)

    suspend fun scan(
        reader: TerminalRenderFrameReader,
        model: TerminalSearchModel,
        query: String,
        ignoreCase: Boolean,
    ): Result? {
        val context = currentCoroutineContext()
        var first = 0L
        var last = 0L
        var columns = 0
        var buffer = TerminalRenderBufferKind.PRIMARY
        var generation = 0L
        reader.readRenderFrame { frame ->
            first = frame.discardedCount
            last = first + frame.historySize + frame.rows - 1L
            columns = frame.columns
            buffer = frame.activeBuffer
            generation = frame.contentGeneration
        }
        model.begin(query, ignoreCase, first, { context.ensureActive() }, validateRows = true)
        var next = first
        var lastGeneration = generation
        while (next <= last) {
            context.ensureActive()
            var copiedFirst = next
            var copiedLast = next - 1
            var valid = true
            // Cell work per lock acquisition is bounded by COPY_CELLS, or one physical row.
            val count = minOf(COPY_ROWS, maxOf(1, COPY_CELLS / columns))
            val end = next + minOf(last - next, count - 1L)
            reader.readRenderFrameForAbsoluteRange(next, end) { frame ->
                context.ensureActive()
                if (frame.columns != columns || frame.activeBuffer != buffer) {
                    valid = false
                    return@readRenderFrameForAbsoluteRange
                }
                lastGeneration = frame.contentGeneration
                val top = frame.discardedCount + frame.historySize - frame.scrollbackOffset
                copiedFirst = maxOf(next, top)
                copiedLast = minOf(end, top + frame.rows - 1L)
                if (copiedFirst > copiedLast) return@readRenderFrameForAbsoluteRange
                val offset = (copiedFirst - top).toInt()
                val rows = (copiedLast - copiedFirst + 1).toInt()
                // The range API may include the live grid prefix. Copy only the requested intersection.
                val slice =
                    object : TerminalRenderFrame by frame {
                        override val rows = rows

                        override fun lineId(row: Int) = frame.lineId(offset + row)

                        override fun lineGeneration(row: Int) = frame.lineGeneration(offset + row)

                        override fun lineWrapped(row: Int) = frame.lineWrapped(offset + row)

                        override fun copyLine(
                            row: Int,
                            codeWords: IntArray,
                            codeOffset: Int,
                            attrWords: LongArray,
                            attrOffset: Int,
                            flags: IntArray,
                            flagOffset: Int,
                            extraAttrWords: LongArray?,
                            extraAttrOffset: Int,
                            hyperlinkIds: IntArray?,
                            hyperlinkOffset: Int,
                            clusterSink: TerminalRenderClusterSink?,
                            clusterDataSink: TerminalRenderClusterDataSink?,
                        ) {
                            context.ensureActive()
                            frame.copyLine(
                                offset + row,
                                codeWords,
                                codeOffset,
                                attrWords,
                                attrOffset,
                                flags,
                                flagOffset,
                                extraAttrWords,
                                extraAttrOffset,
                                hyperlinkIds,
                                hyperlinkOffset,
                                clusterSink,
                                clusterDataSink,
                            )
                        }
                    }
                cache.reset()
                cache.accept(slice)
            }
            if (!valid) return null
            if (copiedFirst > next) model.discardPendingLine()
            if (copiedFirst > last) break
            if (copiedLast >= copiedFirst) model.append(cache, copiedFirst) else model.discardPendingLine()
            if (end == last) break
            next = end + 1L
            yield()
        }
        val highlights = model.finish()
        var retainedFirst = first
        var valid = true
        reader.readRenderFrame { frame ->
            retainedFirst = frame.discardedCount
            lastGeneration = frame.contentGeneration
            valid = frame.columns == columns && frame.activeBuffer == buffer
        }
        if (!valid) return null
        highlights.discardBefore(retainedFirst) { context.ensureActive() }
        return Result(highlights, columns, buffer, lastGeneration, generation != lastGeneration)
    }

    internal class Result(
        val highlights: TerminalSearchHighlights,
        val columns: Int,
        val buffer: TerminalRenderBufferKind,
        val generation: Long,
        val changedDuringScan: Boolean,
    )

    private companion object {
        const val COPY_ROWS = 64
        const val COPY_CELLS = 4096
    }
}
