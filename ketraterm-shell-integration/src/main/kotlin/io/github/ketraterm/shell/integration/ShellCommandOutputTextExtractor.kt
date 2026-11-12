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
package io.github.ketraterm.shell.integration

import io.github.ketraterm.render.api.*
import io.github.ketraterm.session.*

/** One on-demand read; neither borrowed frames nor row scratch survive the request. */
internal class ShellCommandOutputTextExtractor {
    fun extract(
        reader: TerminalRenderFrameReader,
        state: TerminalShellIntegrationView,
        recordId: Int,
        maxChars: Int,
        maxRows: Int,
    ): TerminalCommandOutputText? {
        val metadata = state.commandMetadata(recordId) ?: return null
        if (metadata.lifecycle != TerminalShellIntegrationCommandLifecycle.SUCCEEDED &&
            metadata.lifecycle != TerminalShellIntegrationCommandLifecycle.FAILED &&
            metadata.lifecycle != TerminalShellIntegrationCommandLifecycle.FINISHED_UNKNOWN
        ) {
            return null
        }

        val range = LongArray(TerminalShellIntegrationCommandOutputRange.REQUIRED_LONGS)
        val hasOutput = state.copyCommandOutputRange(recordId, range)
        // A missing output range is not enough: the command block must also be unambiguous.
        if (!hasOutput && !state.copyCommandBlockRange(recordId, LongArray(TerminalShellIntegrationCommandBlockRange.REQUIRED_LONGS))) {
            return null
        }
        var result: TerminalCommandOutputText? = null
        reader.readRenderFrameForAbsoluteRange(0L, Long.MAX_VALUE) { frame ->
            if (frame.activeBuffer != TerminalRenderBufferKind.PRIMARY) return@readRenderFrameForAbsoluteRange
            result =
                if (hasOutput) {
                    extractFrame(frame, range, maxChars, maxRows)
                } else {
                    TerminalCommandOutputText("", truncated = false, historyIncomplete = false)
                }
        }
        return result
    }

    private fun extractFrame(
        frame: TerminalRenderFrame,
        range: LongArray,
        maxChars: Int,
        maxRows: Int,
    ): TerminalCommandOutputText? {
        val start = range[TerminalShellIntegrationCommandOutputRange.START_LINE_ID_INDEX]
        val end = range[TerminalShellIntegrationCommandOutputRange.END_LINE_ID_INDEX]
        val inclusive = range[TerminalShellIntegrationCommandOutputRange.START_INCLUSIVE_INDEX] != 0L
        var startRetained = false
        var endRetained = false
        var first = -1
        var last = -1
        var count = 0
        // Inspect identities only. Cell content is copied lazily, backwards, within both limits.
        for (row in 0 until frame.rows) {
            val id = frame.lineId(row)
            if (id == start) startRetained = true
            if (id == end) endRetained = true
            if (id in start..end && (inclusive || id != start)) {
                if (first < 0) first = row
                last = row
                count++
            }
        }
        if (last < 0) return null
        val codes = IntArray(frame.columns)
        val flags = IntArray(frame.columns)
        val clusters = arrayOfNulls<String>(frame.columns)
        val sink =
            TerminalRenderClusterDataSink { column, points, offset, length ->
                clusters[column] = String(points, offset, length)
            }
        val reversed = StringBuilder(minOf(maxChars, 4096))
        var copiedRows = 0
        var truncated = false
        var row = last
        rows@ while (row >= first) {
            val id = frame.lineId(row)
            if (id !in start..end || (!inclusive && id == start)) {
                // Do not join text across rows belonging to another record after row rearrangement.
                return null
            }
            if (copiedRows == maxRows) {
                truncated = true
                break
            }
            if (copiedRows > 0 && !frame.lineWrapped(row)) {
                if (reversed.length == maxChars) {
                    truncated = true
                    break
                }
                reversed.append('\n')
            }
            clusters.fill(null)
            frame.copyTextLine(row, codes, flags = flags, clusterDataSink = sink)
            copiedRows++
            var endColumn = frame.columns
            if (frame.lineWrapped(row)) {
                if (endColumn > 0 && flags[endColumn - 1] and TerminalRenderCellFlags.WRAP_PADDING != 0) endColumn--
            } else {
                while (endColumn > 0) {
                    val column = endColumn - 1
                    val empty = flags[column] and TerminalRenderCellFlags.EMPTY != 0
                    val space = flags[column] and TerminalRenderCellFlags.CODEPOINT != 0 && codes[column] == ' '.code
                    if (!empty && !space) break
                    endColumn--
                }
            }
            for (column in endColumn - 1 downTo 0) {
                val cellFlags = flags[column]
                if (cellFlags and TerminalRenderCellFlags.WIDE_TRAILING != 0) continue
                val cluster = clusters[column]
                if (cellFlags and TerminalRenderCellFlags.CLUSTER != 0 && cluster == null) return null
                val codepoint = if (cellFlags and TerminalRenderCellFlags.CODEPOINT != 0) codes[column] else ' '.code
                val length = cluster?.length ?: Character.charCount(codepoint)
                if (length > maxChars - reversed.length) {
                    truncated = true
                    break@rows
                }
                if (cluster != null) {
                    var offset = cluster.length
                    while (offset > 0) {
                        val cp = cluster.codePointBefore(offset)
                        reversed.appendCodePoint(cp)
                        offset -= Character.charCount(cp)
                    }
                } else {
                    reversed.appendCodePoint(codepoint)
                }
            }
            row--
        }
        return TerminalCommandOutputText(
            reversed.reverse().toString(),
            truncated = truncated || copiedRows < count,
            historyIncomplete = !startRetained || !endRetained,
        )
    }
}
