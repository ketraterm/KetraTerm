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
package io.github.ketraterm.render.cache

import io.github.ketraterm.render.api.*

/**
 * Immutable primitive copy of one physical terminal row, including colors, cell styles and clusters.
 * Capturing allocates; subsequent copies never read or retain the source frame. No cursor,
 * images, terminal hyperlinks, or viewport policy are retained. Consumers may share this value.
 *
 * Each snapshot is an independent content source with constant generations, even when two
 * snapshots preserve the same [lineId]. Call [TerminalRenderCache.reset] before passing a
 * different snapshot to [TerminalRenderCache.accept]; generations cannot be compared across
 * snapshot instances. Row access uses index zero, regardless of the original source row.
 */
public class TerminalRenderRowSnapshot private constructor(
    private val data: TerminalRenderCache,
) : TerminalRenderFrame {
    public override val columns: Int get() = data.columns
    public override val rows: Int get() = 1
    public override val frameGeneration: Long get() = 1
    public override val structureGeneration: Long get() = 1
    public override val activeBuffer: TerminalRenderBufferKind get() = data.activeBuffer
    public override val palette: TerminalColorPalette get() = data.palette
    public override val cursor: TerminalRenderCursor get() = HIDDEN_CURSOR

    /** Cell and cluster-plane bytes, excluding fixed row, object and palette overhead. */
    public val storageBytes: Long get() =
        data.codeWords.size.toLong() * 4 + data.attrWords.size.toLong() * 8 + data.extraAttrWords.size.toLong() * 8 +
            data.flags.size.toLong() * 4 + data.hyperlinkIds.size.toLong() * 4 + data.clusterRefs.size.toLong() * 8 +
            data.clusterCodepoints.size.toLong() * 4

    public override fun lineId(row: Int): Long {
        require(row == 0)
        return data.lineIds[0]
    }

    public override fun lineGeneration(row: Int): Long {
        require(row == 0)
        return 1
    }

    public override fun lineWrapped(row: Int): Boolean {
        require(row == 0)
        return data.lineWrapped[0]
    }

    public override fun copyLine(
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
        require(row == 0)
        requireSlice(codeOffset, codeWords.size)
        requireSlice(attrOffset, attrWords.size)
        requireSlice(flagOffset, flags.size)
        if (extraAttrWords != null) requireSlice(extraAttrOffset, extraAttrWords.size)
        if (hyperlinkIds != null) requireSlice(hyperlinkOffset, hyperlinkIds.size)
        data.codeWords.copyInto(codeWords, codeOffset, 0, columns)
        data.attrWords.copyInto(attrWords, attrOffset, 0, columns)
        data.flags.copyInto(flags, flagOffset, 0, columns)
        if (extraAttrWords != null) data.extraAttrWords.copyInto(extraAttrWords, extraAttrOffset, 0, columns)
        hyperlinkIds?.fill(0, hyperlinkOffset, hyperlinkOffset + columns)
        copyClusters(clusterSink, clusterDataSink)
    }

    public override fun copyTextLine(
        row: Int,
        codeWords: IntArray,
        codeOffset: Int,
        flags: IntArray,
        flagOffset: Int,
        clusterDataSink: TerminalRenderClusterDataSink?,
    ) {
        require(row == 0)
        requireSlice(codeOffset, codeWords.size)
        requireSlice(flagOffset, flags.size)
        data.codeWords.copyInto(codeWords, codeOffset, 0, columns)
        data.flags.copyInto(flags, flagOffset, 0, columns)
        copyClusters(null, clusterDataSink)
    }

    public override fun copyCursor(sink: TerminalRenderCursorSink) {
        sink.onCursor(0, 0, false, false, TerminalRenderCursorShape.BLOCK, 1)
    }

    private fun requireSlice(
        offset: Int,
        size: Int,
    ) {
        require(offset >= 0 && columns <= size - offset)
    }

    private fun copyClusters(
        textSink: TerminalRenderClusterSink?,
        dataSink: TerminalRenderClusterDataSink?,
    ) {
        if (textSink == null && dataSink == null) return
        for (column in 0 until columns) {
            val ref = data.clusterRefs[column]
            val length = ref.toInt()
            if (length <= 0) continue
            val offset = (ref ushr 32).toInt()
            dataSink?.onCluster(column, data.clusterCodepoints, offset, length)
            textSink?.onCluster(column, String(data.clusterCodepoints, offset, length))
        }
    }

    public companion object {
        private val HIDDEN_CURSOR = TerminalRenderCursor(0, 0, false, false, TerminalRenderCursorShape.BLOCK, 1)

        /**
         * Copies one physical [row] during [frame]'s borrowed lifetime. The returned snapshot
         * owns its copied cells and clusters and may outlive the callback or source mutation.
         * The source palette is immutable and shared. Soft-wrapped continuations are not joined.
         *
         * Each result starts an independent generation namespace; reset a reused
         * [TerminalRenderCache] before accepting it, even when capturing the same source row again.
         *
         * @param frame source whose owner keeps it stable throughout this call.
         * @param row zero-based physical row in [frame], exposed as row zero in the result.
         * @throws IllegalArgumentException if [row] is outside the frame.
         */
        @JvmStatic
        public fun copyOf(
            frame: TerminalRenderFrame,
            row: Int,
        ): TerminalRenderRowSnapshot {
            require(row in 0 until frame.rows)
            val sourceRow = row
            val slice =
                object : TerminalRenderFrame by frame {
                    override val rows: Int get() = 1

                    override fun lineId(row: Int): Long = frame.lineId(sourceRow)

                    override fun lineGeneration(row: Int): Long = frame.lineGeneration(sourceRow)

                    override fun lineWrapped(row: Int): Boolean = frame.lineWrapped(sourceRow)

                    override fun copyCursor(sink: TerminalRenderCursorSink) {
                        sink.onCursor(0, 0, false, false, TerminalRenderCursorShape.BLOCK, 1)
                    }

                    override fun copyImages(sink: TerminalRenderImageSink) = Unit

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
                        frame.copyLine(
                            sourceRow,
                            codeWords,
                            codeOffset,
                            attrWords,
                            attrOffset,
                            flags,
                            flagOffset,
                            extraAttrWords,
                            extraAttrOffset,
                            null,
                            0,
                            clusterSink,
                            clusterDataSink,
                        )
                        hyperlinkIds?.fill(0, hyperlinkOffset, hyperlinkOffset + frame.columns)
                    }
                }
            val cache = TerminalRenderCache(frame.columns, 1)
            cache.accept(slice)
            return TerminalRenderRowSnapshot(cache)
        }
    }
}
