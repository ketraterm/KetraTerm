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
package io.github.ketraterm.core.render

import io.github.ketraterm.core.codec.AttributeCodec
import io.github.ketraterm.core.model.Line
import io.github.ketraterm.core.model.TerminalConstants

internal fun Line.copyToRenderAbi(
    width: Int,
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
    clusterSink: io.github.ketraterm.render.api.TerminalRenderClusterSink?,
    clusterDataSink: io.github.ketraterm.render.api.TerminalRenderClusterDataSink?,
    attrTranslator: RenderAttrTranslator,
    clusterScratch: RenderClusterScratch,
    reverseVideo: Boolean,
) {
    var col = 0
    while (col < width) {
        val raw = rawCodepoint(col)
        val primaryAttr = getPackedAttr(col)
        val extendedAttr = getPackedExtendedAttr(col)

        codeWords[codeOffset + col] = 0
        attrWords[attrOffset + col] =
            attrTranslator.toRenderAttrWord(
                primaryAttr = primaryAttr,
                extendedAttr = extendedAttr,
                reverseVideo = reverseVideo,
            )
        flags[flagOffset + col] = cellFlags(col, raw)

        if (extraAttrWords != null) {
            extraAttrWords[extraAttrOffset + col] = attrTranslator.toRenderExtraAttrWord(extendedAttr)
        }
        if (hyperlinkIds != null) {
            hyperlinkIds[hyperlinkOffset + col] = AttributeCodec.hyperlinkId(extendedAttr)
        }

        when {
            raw == TerminalConstants.EMPTY ||
                raw == TerminalConstants.WIDE_CHAR_SPACER -> {
                codeWords[codeOffset + col] = 0
            }
            raw <= TerminalConstants.CLUSTER_HANDLE_MAX -> {
                if (clusterDataSink != null || clusterSink != null) {
                    val length = clusterScratch.readCluster(this, col)
                    clusterDataSink?.onCluster(col, clusterScratch.codepoints, 0, length)
                    clusterSink?.onCluster(col, String(clusterScratch.codepoints, 0, length))
                }
            }
            else -> {
                codeWords[codeOffset + col] = raw
            }
        }
        col++
    }
}

private fun Line.cellFlags(
    col: Int,
    raw: Int,
): Int =
    when {
        raw == TerminalConstants.EMPTY -> io.github.ketraterm.render.api.TerminalRenderCellFlags.EMPTY
        raw == TerminalConstants.WIDE_CHAR_SPACER -> io.github.ketraterm.render.api.TerminalRenderCellFlags.WIDE_TRAILING
        raw <= TerminalConstants.CLUSTER_HANDLE_MAX -> {
            var flags = io.github.ketraterm.render.api.TerminalRenderCellFlags.CLUSTER
            if (isWideLeading(col)) {
                flags = flags or io.github.ketraterm.render.api.TerminalRenderCellFlags.WIDE_LEADING
            }
            flags
        }
        else -> {
            var flags = io.github.ketraterm.render.api.TerminalRenderCellFlags.CODEPOINT
            if (isWideLeading(col)) {
                flags = flags or io.github.ketraterm.render.api.TerminalRenderCellFlags.WIDE_LEADING
            }
            flags
        }
    }

private fun Line.isWideLeading(col: Int): Boolean = col + 1 < width && rawCodepoint(col + 1) == TerminalConstants.WIDE_CHAR_SPACER

internal class RenderClusterScratch {
    var codepoints = IntArray(16)
        private set

    fun readCluster(
        line: Line,
        col: Int,
    ): Int {
        val raw = line.rawCodepoint(col)
        val length = line.store.length(raw)
        if (codepoints.size < length) {
            codepoints = IntArray(length)
        }
        line.readCluster(col, codepoints)
        return length
    }
}
