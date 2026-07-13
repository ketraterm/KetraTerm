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
package io.github.ketraterm.core.buffer

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.core.api.TerminalBuffer
import io.github.ketraterm.core.model.TerminalConstants
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TerminalColumnOperationTest {
    @Test
    fun `DECIC shifts every scroll-region row and leaves outer rows untouched`() {
        val buffer = TerminalBuffers.create(width = 6, height = 4)
        writeRows(buffer, "ABCDEF", "ABCDEF", "ABCDEF", "ABCDEF")
        buffer.setScrollRegion(top = 2, bottom = 3)
        buffer.positionCursor(col = 1, row = 1)

        buffer.insertColumns(1)

        assertAll(
            { assertEquals("ABCDEF", buffer.getLineAsString(0)) },
            { assertEquals("A BCDE", buffer.getLineAsString(1)) },
            { assertEquals("A BCDE", buffer.getLineAsString(2)) },
            { assertEquals("ABCDEF", buffer.getLineAsString(3)) },
            { assertEquals(1, buffer.cursorCol) },
            { assertEquals(1, buffer.cursorRow) },
        )
    }

    @Test
    fun `DECDC shifts only inside horizontal margins across the scroll region`() {
        val buffer = TerminalBuffers.create(width = 6, height = 3)
        writeRows(buffer, "abcdef", "abcdef", "abcdef")
        buffer.setLeftRightMarginMode(true)
        buffer.setLeftRightMargins(left = 2, right = 5)
        buffer.setScrollRegion(top = 2, bottom = 3)
        buffer.positionCursor(col = 2, row = 1)

        buffer.deleteColumns(1)

        assertAll(
            { assertEquals("abcdef", buffer.getLineAsString(0)) },
            { assertEquals("abde f", buffer.getLineAsString(1)) },
            { assertEquals("abde f", buffer.getLineAsString(2)) },
            { assertEquals('a'.code, buffer.getCodepointAt(0, 1)) },
            { assertEquals('f'.code, buffer.getCodepointAt(5, 1)) },
        )
    }

    @Test
    fun `DECDC removes a complete wide span when cursor selects its spacer`() {
        val buffer = TerminalBuffers.create(width = 6, height = 2)
        buffer.positionCursor(col = 1, row = 0)
        buffer.writeCodepoint(0x1F642)
        buffer.writeCodepoint('B'.code)
        buffer.positionCursor(col = 1, row = 1)
        buffer.writeCodepoint(0x1F642)
        buffer.writeCodepoint('B'.code)
        buffer.positionCursor(col = 2, row = 0)

        buffer.deleteColumns(1)

        assertAll(
            { assertEquals(TerminalConstants.EMPTY, buffer.getCodepointAt(1, 0)) },
            { assertEquals('B'.code, buffer.getCodepointAt(2, 0)) },
            { assertEquals(TerminalConstants.EMPTY, buffer.getCodepointAt(1, 1)) },
            { assertEquals('B'.code, buffer.getCodepointAt(2, 1)) },
        )
    }

    @Test
    fun `DECIC moves cluster ownership without copying or freeing its payload`() {
        val buffer = TerminalBuffers.create(width = 5, height = 2)
        buffer.writeCluster(intArrayOf('A'.code, 0x0301), 2)
        buffer.positionCursor(col = 0, row = 0)

        buffer.insertColumns(1)

        val cluster = IntArray(2)
        val line = buffer.getLine(0)
        assertAll(
            { assertEquals(TerminalConstants.EMPTY, buffer.getCodepointAt(0, 0)) },
            { assertTrue(line.isCluster(1)) },
            { assertEquals(2, line.readCluster(1, cluster)) },
            { assertArrayEquals(intArrayOf('A'.code, 0x0301), cluster) },
        )
    }

    @Test
    fun `DEC column edits do nothing outside scrolling margins`() {
        val buffer = TerminalBuffers.create(width = 5, height = 3)
        writeRows(buffer, "ABCDE", "ABCDE", "ABCDE")
        buffer.setScrollRegion(top = 2, bottom = 3)
        buffer.positionCursor(col = 1, row = 0)

        buffer.insertColumns(2)
        buffer.deleteColumns(2)

        assertAll(
            { assertEquals("ABCDE", buffer.getLineAsString(0)) },
            { assertEquals("ABCDE", buffer.getLineAsString(1)) },
            { assertEquals("ABCDE", buffer.getLineAsString(2)) },
        )
    }

    @Test
    fun `DECIC mutates only the active alternate buffer`() {
        val buffer = TerminalBuffers.create(width = 5, height = 2)
        writeRows(buffer, "ABCDE")
        buffer.enterAltBuffer()
        writeRows(buffer, "12345")
        buffer.positionCursor(col = 1, row = 0)

        buffer.insertColumns(1)

        assertEquals("1 234", buffer.getLineAsString(0))
        buffer.exitAltBuffer()
        assertEquals("ABCDE", buffer.getLineAsString(0))
    }

    private fun writeRows(
        buffer: TerminalBuffer,
        vararg rows: String,
    ) {
        rows.forEachIndexed { row, text ->
            buffer.positionCursor(col = 0, row = row)
            text.forEach { buffer.writeCodepoint(it.code) }
        }
    }
}
