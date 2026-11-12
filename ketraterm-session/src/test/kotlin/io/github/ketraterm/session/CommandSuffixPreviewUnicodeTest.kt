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
import io.github.ketraterm.render.api.TerminalRenderCellFlags
import io.github.ketraterm.render.cache.TerminalRenderCache
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CommandSuffixPreviewUnicodeTest {
    @Test
    fun `complete variation-selector and ZWJ graphemes match ordinary terminal layout at the right edge`() {
        for (suffix in listOf("❤️", "⌚︎", "1️⃣", "👩‍💻", "🏳️‍🌈")) {
            val preview = preview("abcde", suffix)
            val normal = normal("abcde", suffix)
            assertSuffixCellsEqual(normal, preview, startRow = 0, startColumn = 5)
        }
        val heart = preview("abcde", "❤️")
        assertTrue(heart.flags[5] and TerminalRenderCellFlags.WRAP_PADDING != 0)
        assertTrue(heart.flags[6] and TerminalRenderCellFlags.WIDE_LEADING != 0)
        assertEquals("❤️", heart.clusterText(1, 0))
        assertTrue(heart.flags[7] and TerminalRenderCellFlags.WIDE_TRAILING != 0)
    }

    @Test
    fun `complete graphemes clip at the bottom without leaving a partial glyph or scrolling`() {
        for (suffix in listOf("❤️", "⌚︎", "1️⃣", "👩‍💻", "🏳️‍🌈")) {
            val preview = preview("abcde", suffix, row = 2)
            val normal = normal("abcde", suffix, row = 2)
            assertSuffixCellsEqual(normal, preview, startRow = 2, startColumn = 5)
        }
        val heart = preview("abcde", "❤️", row = 2)
        assertTrue(heart.flags[17] and TerminalRenderCellFlags.WRAP_PADDING != 0)
        assertFalse(heart.flags.take(18).any { it and TerminalRenderCellFlags.CLUSTER != 0 })
    }

    @Test
    fun `long input cannot scroll away the visible prefix of the preview`() {
        val preview = preview("abcde", "0123456789".repeat(100))
        assertEquals('0'.code, preview.codeWords[5])
        assertEquals('1'.code, preview.codeWords[6])
        assertEquals('2'.code, preview.codeWords[7])
        assertEquals('2'.code, preview.codeWords[17])
    }

    @Test
    fun `leading zero-width text retains ordinary terminal orphan-glyph semantics`() {
        assertSuffixCellsEqual(normal("", "\u0301x"), preview("", "\u0301x"), startRow = 0, startColumn = 0)
    }

    private fun preview(
        prefix: String,
        suffix: String,
        row: Int = 0,
    ): TerminalRenderCache {
        val buffer = TerminalBuffers.create(6, 3, 0)
        buffer.positionCursor(0, row)
        val parser = TerminalParsers.create(HostCommandAdapter(buffer))
        parser.accept(prefix.toByteArray())
        var result: TerminalRenderCache? = null
        buffer.readRenderFrame { result = CommandSuffixPreview.capture(it, suffix, false) }
        return assertNotNull(result)
    }

    private fun normal(
        prefix: String,
        suffix: String,
        row: Int = 0,
    ): TerminalRenderCache {
        // Extra rows preserve ordinary output below the clipped viewport for comparison.
        val buffer = TerminalBuffers.create(6, 6, 0)
        buffer.positionCursor(0, row)
        val parser = TerminalParsers.create(HostCommandAdapter(buffer))
        parser.accept(prefix.toByteArray())
        parser.accept(suffix.toByteArray())
        parser.endOfInput()
        return TerminalRenderCache(6, 6).apply { updateFrom(buffer) }
    }

    private fun assertSuffixCellsEqual(
        normal: TerminalRenderCache,
        preview: TerminalRenderCache,
        startRow: Int,
        startColumn: Int,
    ) {
        for (row in startRow until preview.rows) {
            for (column in (if (row == startRow) startColumn else 0) until preview.columns) {
                val index = row * preview.columns + column
                assertEquals(normal.codeWords[index], preview.codeWords[index], "code at $row,$column")
                assertEquals(normal.flags[index], preview.flags[index], "flags at $row,$column")
                assertEquals(normal.clusterText(row, column), preview.clusterText(row, column), "cluster at $row,$column")
            }
        }
    }
}
