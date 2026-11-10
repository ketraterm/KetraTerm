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
package io.github.ketraterm.session

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.host.HostCommandAdapter
import io.github.ketraterm.parser.api.TerminalParsers
import io.github.ketraterm.render.api.*
import io.github.ketraterm.render.cache.TerminalRenderCache

/** Cold-path layout using exactly the printable pipeline used by the live session. */
internal object CommandSuffixPreview {
    fun validText(text: String): Boolean {
        if (text.isEmpty() || text.length > 16_384) return false
        var offset = 0
        while (offset < text.length) {
            val cp = text.codePointAt(offset)
            if (cp < 0x20 || cp in 0x7f..0x9f || cp in 0xd800..0xdfff || cp == 0x2028 || cp == 0x2029) return false
            offset += Character.charCount(cp)
        }
        return true
    }

    fun capture(
        frame: TerminalRenderFrame,
        text: String,
        ambiguousAsWide: Boolean,
    ): TerminalRenderCache? {
        val row = frame.cursor.row
        val insertion = frame.cursor.column + if (frame.cursorPendingWrap) 1 else 0
        if (row !in 0 until frame.rows || insertion !in 0..frame.columns || frame.rows == Int.MAX_VALUE) return null
        val codes = IntArray(frame.columns)
        val flags = IntArray(frame.columns)
        var preceding = insertion - 1
        var precedingText: String? = null
        if (preceding >= 0) {
            frame.copyTextLine(row, codes, flags = flags)
            if (flags[preceding] and TerminalRenderCellFlags.WIDE_TRAILING != 0) preceding--
            precedingText = cellText(frame, row, preceding)
            if (precedingText == null) return null
        }
        val buffer = TerminalBuffers.create(frame.columns, frame.rows + 1, maxHistory = 0)
        buffer.setTreatAmbiguousAsWide(ambiguousAsWide)
        buffer.setCursorVisible(false)
        buffer.positionCursor(if (precedingText != null) preceding else insertion, row)
        val parser = TerminalParsers.create(HostCommandAdapter(buffer))
        if (precedingText != null) parser.accept(precedingText.toByteArray(Charsets.UTF_8))
        val bytes = text.toByteArray(Charsets.UTF_8)
        var offset = 0
        while (offset < bytes.size && buffer.cursorRow < frame.rows) {
            // Keep the parser's grapheme context across reads, including variation/ZWJ updates.
            val length =
                when (bytes[offset].toInt() and 0xff) {
                    in 0..0x7f -> 1
                    in 0xc0..0xdf -> 2
                    in 0xe0..0xef -> 3
                    else -> 4
                }
            parser.accept(bytes, offset, length)
            offset += length
            if (precedingText == null && offset == length && buffer.cursorRow == row && buffer.cursorCol == insertion) return null
        }
        parser.endOfInput()
        var result: TerminalRenderCache? = null
        buffer.readRenderFrame { laidOut ->
            if (precedingText != null && cellText(laidOut, row, preceding) != precedingText) return@readRenderFrame
            val preview = TerminalRenderCache(frame.columns, frame.rows)
            preview.accept(
                object : TerminalRenderFrame by laidOut {
                    override val rows: Int get() = frame.rows

                    override fun copyCursor(sink: TerminalRenderCursorSink) {
                        sink.onCursor(0, 0, false, false, TerminalRenderCursorShape.BLOCK, 1)
                    }
                },
            )
            // Remove the preceding grapheme used only to detect cross-boundary attachment.
            val clearEnd = row * preview.columns + insertion
            preview.codeWords.fill(0, 0, clearEnd)
            preview.flags.fill(TerminalRenderCellFlags.EMPTY, 0, clearEnd)
            preview.clusterRefs.fill(0L, 0, clearEnd)
            result = preview
        }
        return result
    }

    private fun cellText(
        frame: TerminalRenderFrame,
        row: Int,
        column: Int,
    ): String? {
        if (column !in 0 until frame.columns) return null
        val codes = IntArray(frame.columns)
        val flags = IntArray(frame.columns)
        var cluster: String? = null
        frame.copyTextLine(
            row,
            codes,
            flags = flags,
            clusterDataSink =
                TerminalRenderClusterDataSink { col, points, offset, length ->
                    if (col == column) cluster = String(points, offset, length)
                },
        )
        return when {
            flags[column] and TerminalRenderCellFlags.CLUSTER != 0 -> cluster
            flags[column] and TerminalRenderCellFlags.CODEPOINT != 0 -> String(Character.toChars(codes[column]))
            flags[column] and TerminalRenderCellFlags.EMPTY != 0 -> " "
            else -> null
        }
    }
}
