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

import io.github.ketraterm.render.api.*
import io.github.ketraterm.render.cache.TerminalRenderCache
import java.util.*

/** Replay source for immutable test frames. Unspecified retained rows contain empty cells. */
internal class TerminalHyperlinkTestSource(
    private val cache: TerminalRenderCache,
) : TerminalRenderFrameReader {
    private val retained = TreeMap<Long, Pair<TerminalRenderFrame, Int>>()
    private var current: TerminalRenderFrame? = null

    @Synchronized
    fun publish(
        frame: TerminalRenderFrame,
        publishViewport: Boolean = true,
    ) {
        val previous = current
        if (previous != null &&
            (
                previous.historyContentGeneration != frame.historyContentGeneration ||
                    previous.columns != frame.columns ||
                    previous.activeBuffer != frame.activeBuffer
            )
        ) {
            retained.clear()
        }
        current = frame
        retained.headMap(frame.discardedCount).clear()
        val top = frame.discardedCount + frame.historySize - frame.scrollbackOffset
        for (row in 0 until frame.rows) retained[top + row] = frame to row
        if (publishViewport) cache.accept(frame)
    }

    @Synchronized
    override fun readRenderFrame(consumer: TerminalRenderFrameConsumer) {
        val frame = checkNotNull(current)
        val liveTop = frame.discardedCount + frame.historySize
        readRange(liveTop, maxOf(liveTop, retained.lastKey()), consumer)
    }

    @Synchronized
    override fun readRenderFrameForAbsoluteRange(
        startAbsoluteRow: Long,
        endAbsoluteRow: Long,
        consumer: TerminalRenderFrameConsumer,
    ) {
        val frame = checkNotNull(current)
        val last = maxOf(frame.discardedCount + frame.historySize, retained.lastKey())
        val start = startAbsoluteRow.coerceIn(frame.discardedCount, last)
        readRange(start, endAbsoluteRow.coerceIn(start, last), consumer)
    }

    private fun readRange(
        first: Long,
        last: Long,
        consumer: TerminalRenderFrameConsumer,
    ) {
        val frame = checkNotNull(current)
        val liveTop = frame.discardedCount + frame.historySize
        val top = minOf(first, liveTop)
        consumer.accept(
            object : TerminalRenderFrame by frame {
                override val scrollbackOffset = (liveTop - top).toInt()
                override val rows = (last - top + 1).toInt()

                override fun lineId(row: Int): Long = retained[top + row]?.let { (source, offset) -> source.lineId(offset) } ?: 0L

                override fun lineGeneration(row: Int): Long =
                    retained[top + row]?.let { (source, offset) -> source.lineGeneration(offset) } ?: 0L

                override fun lineWrapped(row: Int): Boolean =
                    retained[top + row]?.let { (source, offset) -> source.lineWrapped(offset) } ?: false

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
                    val stored = retained[top + row]
                    if (stored != null) {
                        stored.first.copyLine(
                            stored.second,
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
                    } else {
                        codeWords.fill(0, codeOffset, codeOffset + columns)
                        attrWords.fill(TerminalRenderAttrs.DEFAULT, attrOffset, attrOffset + columns)
                        flags.fill(0, flagOffset, flagOffset + columns)
                        extraAttrWords?.fill(TerminalRenderExtraAttrs.DEFAULT, extraAttrOffset, extraAttrOffset + columns)
                        hyperlinkIds?.fill(0, hyperlinkOffset, hyperlinkOffset + columns)
                    }
                }
            },
        )
    }
}
