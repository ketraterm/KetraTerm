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

import io.github.ketraterm.core.model.TerminalConstants
import io.github.ketraterm.core.state.TerminalState
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class DefaultTerminalBufferResizeTest {
    @Test
    fun `narrowing keeps a retained scrollback anchor after earlier rows are evicted`() {
        val buffer = DefaultTerminalBuffer(initialWidth = 8, initialHeight = 3, maxHistory = 2)
        writeRows(buffer, "A", "BBBBBB", "C", "D", "E")
        buffer.positionCursor(col = 0, row = 2)
        assertEquals(2, buffer.historySize)

        val result = buffer.resize(newWidth = 3, newHeight = 3, oldScrollbackOffset = 1)

        assertAll(
            { assertEquals(2 to 2, result) },
            { assertEquals("BBB\nBBB\nC\nD\nE", buffer.getAllAsString()) },
            { assertEquals("C\nD\nE", buffer.getScreenAsString()) },
        )
        val state = stateOf(buffer)
        assertEquals("BBB", state.ring[state.resolveScrollbackRingIndex(0, result.first)].toTextTrimmed())
    }

    @Test
    fun `narrowing clamps an evicted scrollback anchor to the oldest surviving row`() {
        val buffer = DefaultTerminalBuffer(initialWidth = 8, initialHeight = 3, maxHistory = 2)
        writeRows(buffer, "AAAAAA", "BBBBBB", "CCCCCC", "DDDDDD", "EEEEEE")
        buffer.positionCursor(col = 0, row = 2)
        assertEquals(2, buffer.historySize)

        val result = buffer.resize(newWidth = 3, newHeight = 3, oldScrollbackOffset = 1)

        assertAll(
            { assertEquals(2 to 2, result) },
            { assertEquals("CCC\nDDD\nDDD\nEEE\nEEE", buffer.getAllAsString()) },
        )
        val state = stateOf(buffer)
        assertEquals("CCC", state.ring[state.resolveScrollbackRingIndex(0, result.first)].toTextTrimmed())
    }

    @Test
    fun `narrowing keeps the cursor on retained text when later rows evict earlier output`() {
        val buffer = DefaultTerminalBuffer(initialWidth = 8, initialHeight = 3, maxHistory = 2)
        writeRows(buffer, "A", "B", "C", "DDDDDD", "EEEEEE")
        buffer.positionCursor(col = 4, row = 1)

        assertEquals(0 to 2, buffer.resize(newWidth = 3, newHeight = 3))

        assertAll(
            { assertEquals("C\nDDD\nDDD\nEEE\nEEE", buffer.getAllAsString()) },
            { assertEquals(1, buffer.cursorCol) },
            { assertEquals(0, buffer.cursorRow) },
        )
        buffer.writeCodepoint('X'.code)
        assertEquals("C\nDDD\nDXD\nEEE\nEEE", buffer.getAllAsString())
    }

    @Test
    fun `narrowing keeps an empty scrollback anchor after earlier rows are evicted`() {
        val buffer = DefaultTerminalBuffer(initialWidth = 8, initialHeight = 3, maxHistory = 2)
        writeRows(buffer, "AAAAAA", "", "C", "D", "E")
        buffer.positionCursor(col = 0, row = 2)

        assertEquals(1 to 2, buffer.resize(newWidth = 3, newHeight = 3, oldScrollbackOffset = 1))
        assertEquals("AAA\n\nC\nD\nE", buffer.getAllAsString())
    }

    @Test
    fun `narrowing keeps the cursor on an empty row after later output evicts earlier rows`() {
        val buffer = DefaultTerminalBuffer(initialWidth = 8, initialHeight = 3, maxHistory = 2)
        writeRows(buffer, "AAAAAA", "B", "", "D", "EEEEEE")
        buffer.positionCursor(col = 0, row = 0)

        assertEquals(0 to 2, buffer.resize(newWidth = 3, newHeight = 4))
        assertAll(
            { assertEquals("AAA\nB\n\nD\nEEE\nEEE", buffer.getAllAsString()) },
            { assertEquals(0, buffer.cursorRow) },
            { assertEquals(0, buffer.cursorCol) },
        )
        buffer.writeCodepoint('X'.code)
        assertEquals("AAA\nB\nX\nD\nEEE\nEEE", buffer.getAllAsString())
    }

    @ParameterizedTest
    @CsvSource("中, 2, Z", "e\u0301, 1, YZ", "👩‍💻, 2, Z")
    fun `narrowing keeps cursor and Unicode content together across history eviction`(
        glyph: String,
        glyphWidth: Int,
        tail: String,
    ) {
        val buffer = DefaultTerminalBuffer(initialWidth = 8, initialHeight = 3, maxHistory = 2)
        writeRows(buffer, "A", "B", "C", "abc", "EEEEEE")
        buffer.positionCursor(col = 3, row = 1)
        val codepoints = glyph.codePoints().toArray()
        buffer.writeCluster(codepoints)
        buffer.writeText(tail)
        buffer.positionCursor(col = 3, row = 1)

        assertEquals(0 to 2, buffer.resize(newWidth = 3, newHeight = 3))

        assertAll(
            { assertEquals("C\nabc\n$glyph$tail\nEEE\nEEE", buffer.getAllAsString()) },
            { assertEquals(0, buffer.cursorRow) },
            { assertEquals(0, buffer.cursorCol) },
        )
        val state = stateOf(buffer)
        val line = state.ring[state.resolveRingIndex(buffer.cursorRow)]
        if (codepoints.size > 1) {
            val actual = IntArray(codepoints.size)
            assertEquals(codepoints.size, line.readCluster(buffer.cursorCol, actual))
            assertArrayEquals(codepoints, actual)
        }
        if (glyphWidth == 2) {
            assertEquals(TerminalConstants.WIDE_CHAR_SPACER, line.getCodepoint(1))
        }
    }

    private fun writeRows(
        buffer: DefaultTerminalBuffer,
        vararg rows: String,
    ) {
        for ((index, text) in rows.withIndex()) {
            if (index > 0) {
                buffer.carriageReturn()
                buffer.newLine()
            }
            buffer.writeText(text)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["abc中x", "ab 中x"])
    fun `widening after immediate wide prewrap removes only artificial padding`(text: String) {
        val buffer = DefaultTerminalBuffer(initialWidth = 4, initialHeight = 3)
        buffer.writeText(text)

        buffer.resize(newWidth = 8, newHeight = 3)

        assertEquals(text, buffer.getAllAsString().trimEnd())
        buffer.resize(newWidth = 4, newHeight = 3)
        buffer.resize(newWidth = 8, newHeight = 3)
        assertEquals(text, buffer.getAllAsString().trimEnd())
    }

    @Test
    fun `widening preserves a fully erased wrapped continuation row`() {
        val buffer = DefaultTerminalBuffer(initialWidth = 4, initialHeight = 3)
        buffer.writeText("abcdefghij")
        buffer.positionCursor(col = 0, row = 1)
        buffer.eraseCharacters(4)
        buffer.positionCursor(col = 2, row = 2)

        buffer.resize(newWidth = 12, newHeight = 3)

        assertEquals("abcd    ij", buffer.getAllAsString().trimEnd())
    }

    @Test
    fun `widening preserves erased space adjacent to wide prewrap padding`() {
        val buffer = DefaultTerminalBuffer(initialWidth = 4, initialHeight = 3)
        buffer.writeText("abc中x")
        buffer.positionCursor(col = 2, row = 0)
        buffer.eraseCharacters(1)
        buffer.positionCursor(col = 3, row = 1)

        buffer.resize(newWidth = 8, newHeight = 3)

        assertEquals("ab 中x", buffer.getAllAsString().trimEnd())
    }

    private fun stateOf(buffer: DefaultTerminalBuffer): TerminalState {
        val componentsField = DefaultTerminalBuffer::class.java.getDeclaredField("components")
        componentsField.isAccessible = true
        val components = componentsField.get(buffer)

        val stateField = components.javaClass.getDeclaredField("state")
        stateField.isAccessible = true
        return stateField.get(components) as TerminalState
    }

    @ParameterizedTest
    @CsvSource("8, 3", "8, 4", "10, 3")
    fun `resize returns the active alternate viewport while retaining primary history`(
        newWidth: Int,
        newHeight: Int,
    ) {
        val buffer = DefaultTerminalBuffer(initialWidth = 8, initialHeight = 3, maxHistory = 16)
        val sourceRows = (0..6).map { "row$it" }
        for ((row, text) in sourceRows.withIndex()) {
            if (row > 0) {
                buffer.carriageReturn()
                buffer.newLine()
            }
            for (character in text) buffer.writeCodepoint(character.code)
        }
        assertEquals(4, buffer.historySize)
        buffer.enterAltBuffer()

        val result = buffer.resize(newWidth, newHeight, oldScrollbackOffset = 2)

        assertEquals(0 to 0, result)
        assertEquals(newWidth, buffer.width)
        assertEquals(newHeight, buffer.height)
        assertEquals(0, buffer.historySize)
        buffer.exitAltBuffer()
        assertEquals(newWidth, buffer.width)
        assertEquals(newHeight, buffer.height)
        assertEquals(4, buffer.historySize)
        assertEquals(sourceRows.joinToString("\n"), buffer.getAllAsString().trimEnd())
    }

    @Test
    fun `resizeWhileAltActive_clampsPrimaryScrollRegionBeforeExit`() {
        val buffer = DefaultTerminalBuffer(initialWidth = 5, initialHeight = 6, maxHistory = 4)
        buffer.setScrollRegion(top = 3, bottom = 6)
        buffer.enterAltBuffer()

        buffer.resize(newWidth = 5, newHeight = 3)

        val state = stateOf(buffer)
        assertAll(
            { assertTrue(state.primaryBuffer.scrollTop in 0 until 3) },
            { assertTrue(state.primaryBuffer.scrollBottom in 0 until 3) },
            { assertTrue(state.primaryBuffer.scrollTop <= state.primaryBuffer.scrollBottom) },
        )
    }

    @Test
    fun `resizeWhileAltActive_thenInsertLinesOnPrimary_doesNotIndexPastRing`() {
        val buffer = DefaultTerminalBuffer(initialWidth = 5, initialHeight = 6, maxHistory = 4)
        buffer.setScrollRegion(top = 3, bottom = 6)
        buffer.enterAltBuffer()
        buffer.resize(newWidth = 5, newHeight = 3)
        buffer.exitAltBuffer()
        buffer.positionCursor(col = 0, row = 2)

        assertDoesNotThrow {
            buffer.insertLines(1)
        }
    }

    @Test
    fun `resize_resetsOrClampsBothBuffersMarginsPerChosenPolicy`() {
        val buffer = DefaultTerminalBuffer(initialWidth = 7, initialHeight = 6, maxHistory = 4)
        buffer.setScrollRegion(top = 2, bottom = 5)
        buffer.enterAltBuffer()
        buffer.setScrollRegion(top = 3, bottom = 6)

        buffer.resize(newWidth = 7, newHeight = 3)

        val state = stateOf(buffer)
        assertAll(
            { assertTrue(state.primaryBuffer.scrollTop in 0 until 3) },
            { assertTrue(state.primaryBuffer.scrollBottom in 0 until 3) },
            { assertTrue(state.primaryBuffer.scrollTop <= state.primaryBuffer.scrollBottom) },
            { assertTrue(state.altBuffer.scrollTop in 0 until 3) },
            { assertTrue(state.altBuffer.scrollBottom in 0 until 3) },
            { assertTrue(state.altBuffer.scrollTop <= state.altBuffer.scrollBottom) },
        )
    }

    @Test
    fun `resize while scrolled into top anchored history preserves viewport anchor`() {
        val buffer = DefaultTerminalBuffer(initialWidth = 3, initialHeight = 4, maxHistory = 8)
        val state = stateOf(buffer)
        val rows = arrayOf("AAA", "BBB", "CCC", "DDD")
        for (row in rows.indices) {
            val line = state.primaryBuffer.ring[row]
            for (column in rows[row].indices) {
                line.setCell(column, rows[row][column].code, state.pen.currentAttr)
            }
        }
        buffer.setScrollRegion(top = 1, bottom = 3)
        buffer.positionCursor(col = 0, row = 2)
        buffer.newLine()

        val anchoredLine = state.ring[state.resolveScrollbackRingIndex(viewportRow = 0, scrollbackOffset = 1)]
        val anchoredLineId = anchoredLine.lineId
        assertEquals("AAA", anchoredLine.toTextTrimmed())

        val (newOffset, newHistorySize) = buffer.resize(newWidth = 4, newHeight = 4, oldScrollbackOffset = 1)

        val reanchoredLine = state.ring[state.resolveScrollbackRingIndex(viewportRow = 0, scrollbackOffset = newOffset)]
        assertAll(
            { assertEquals(1, newOffset) },
            { assertEquals(1, newHistorySize) },
            { assertEquals(anchoredLineId, reanchoredLine.lineId) },
            { assertEquals("AAA", reanchoredLine.toTextTrimmed()) },
            { assertEquals("DDD", state.ring[state.resolveRingIndex(3)].toTextTrimmed()) },
        )
    }
}
