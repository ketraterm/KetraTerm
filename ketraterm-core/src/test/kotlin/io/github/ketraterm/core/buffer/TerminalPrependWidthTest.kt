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
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TerminalPrependWidthTest {
    @Test
    fun `a complete Prepend CJK cluster reserves two cells and preserves its full payload`() {
        val buffer = TerminalBuffers.create(6, 2)
        val cluster = intArrayOf(0x0600, 0x754C)
        buffer.writeCluster(cluster)
        buffer.writeText("X")
        val retained = IntArray(2)
        assertEquals(2, buffer.getLine(0).readCluster(0, retained))
        assertArrayEquals(cluster, retained)
        assertEquals(-1, buffer.getCodepointAt(1, 0))
        assertEquals('X'.code, buffer.getCodepointAt(2, 0))
        assertEquals(3 to 0, buffer.cursorCol to buffer.cursorRow)
    }

    @Test
    fun `a published prefix expands when its CJK base arrives`() {
        val buffer = TerminalBuffers.create(6, 2)
        buffer.writeCodepoint(0x0600)
        assertEquals(1, buffer.cursorCol)
        buffer.updatePreviousCluster(intArrayOf(0x0600, 0x754C))
        assertEquals(2, buffer.cursorCol)
        assertEquals(-1, buffer.getCodepointAt(1, 0))
        buffer.writeText("X")
        assertEquals("\u0600界X", buffer.getLineAsString(0))
        assertEquals(3 to 0, buffer.cursorCol to buffer.cursorRow)
    }

    @Test
    fun `selectors widen and narrow the base following a published prefix`() {
        val buffer = TerminalBuffers.create(6, 2)
        buffer.writeCluster(intArrayOf(0x0600, 0x2764))
        assertEquals(1, buffer.cursorCol)
        buffer.updatePreviousCluster(intArrayOf(0x0600, 0x2764, 0xFE0F))
        assertEquals(-1, buffer.getCodepointAt(1, 0))
        assertEquals(2, buffer.cursorCol)
        buffer.updatePreviousCluster(intArrayOf(0x0600, 0x2764, 0xFE0F, 0xFE0E))
        assertEquals(0, buffer.getCodepointAt(1, 0))
        assertEquals(1, buffer.cursorCol)
        buffer.writeText("X")
        assertEquals("\u0600❤\uFE0F\uFE0EX", buffer.getLineAsString(0))
        assertEquals(2 to 0, buffer.cursorCol to buffer.cursorRow)
    }

    @Test
    fun `repeated Prepend CJK clusters use the same two cell allocation`() {
        val buffer = TerminalBuffers.create(6, 2)
        buffer.writeRepeatedCluster(intArrayOf(0x0600, 0x754C), 2, 2)
        assertEquals(-1, buffer.getCodepointAt(1, 0))
        assertEquals(-1, buffer.getCodepointAt(3, 0))
        buffer.writeText("X")
        assertEquals("\u0600界\u0600界X", buffer.getLineAsString(0))
        assertEquals(5 to 0, buffer.cursorCol to buffer.cursorRow)
    }

    @Test
    fun `right edge placement preserves the committed prefix policy`() {
        for (lateBase in listOf(false, true)) {
            for (wrap in listOf(false, true)) {
                val buffer = TerminalBuffers.create(6, 2)
                buffer.setAutoWrap(wrap)
                buffer.positionCursor(5, 0)
                if (lateBase) {
                    buffer.writeCodepoint(0x0600)
                    buffer.updatePreviousCluster(intArrayOf(0x0600, 0x754C))
                    assertEquals("     \u0600界", buffer.getLineAsString(0))
                    assertEquals(5 to 0, buffer.cursorCol to buffer.cursorRow)
                } else {
                    buffer.writeCluster(intArrayOf(0x0600, 0x754C))
                    assertEquals(if (wrap) "\u0600界" else "", buffer.getLineAsString(1))
                    assertEquals(if (wrap) 2 to 1 else 5 to 0, buffer.cursorCol to buffer.cursorRow)
                }
                buffer.writeText("X")
                val column = if (wrap) (if (lateBase) 0 else 2) else 5
                val row = if (wrap) 1 else 0
                assertEquals('X'.code, buffer.getCodepointAt(column, row))
                assertEquals(minOf(column + 1, 5) to row, buffer.cursorCol to buffer.cursorRow)
                assertEquals(0, buffer.historySize)
            }
        }
    }
}
