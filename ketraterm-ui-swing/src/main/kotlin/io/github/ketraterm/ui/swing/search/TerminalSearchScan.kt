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
import io.github.ketraterm.render.cache.TerminalRenderRangeCopy
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield

/** Worker-owned bounded cell copy. Results and longest logical-line scratch grow with retained text. */
internal class TerminalSearchScan {
    private val copy = TerminalRenderRangeCopy()

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
        var historyContentGeneration = 0L
        reader.readRenderFrame { frame ->
            first = frame.discardedCount
            last = first + frame.historySize + frame.rows - 1L
            columns = frame.columns
            buffer = frame.activeBuffer
            generation = frame.contentGeneration
            historyContentGeneration = frame.historyContentGeneration
        }
        model.begin(query, ignoreCase, first, { context.ensureActive() }, validateRows = true)
        var next = first
        var lastGeneration = generation
        while (next <= last) {
            context.ensureActive()
            val copied = copy.read(reader, next, last) { context.ensureActive() }
            val copiedFirst = copy.firstAbsoluteRow
            val copiedLast = copy.lastAbsoluteRow
            val cache = copy.cache
            if (copied &&
                (cache.columns != columns || cache.activeBuffer != buffer || cache.historyContentGeneration != historyContentGeneration)
            ) {
                return null
            }
            if (copied) lastGeneration = cache.contentGeneration
            if (copiedFirst > next) model.discardPendingLine()
            if (copiedFirst > last) break
            if (copiedLast >= copiedFirst) model.append(cache, copiedFirst) else model.discardPendingLine()
            if (!copied || copiedLast == last) break
            next = copiedLast + 1L
            yield()
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
        return Result(highlights, columns, buffer, lastGeneration, historyContentGeneration, generation != lastGeneration)
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
