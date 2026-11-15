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
package io.github.ketraterm.core.buffer.impl

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.core.codec.AttributeCodec
import io.github.ketraterm.core.engine.CursorEngine
import io.github.ketraterm.core.engine.MutationEngine
import io.github.ketraterm.core.model.CellAttributes
import io.github.ketraterm.core.model.CellColor
import io.github.ketraterm.core.model.UnderlineStyle
import io.github.ketraterm.core.state.TerminalState
import io.github.ketraterm.render.api.TerminalRenderFrameReader
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class BufferWriterTest {
    @Test
    fun `direct repeated writes remain caller controlled beyond the wire REP limit`() {
        val terminal = TerminalBuffers.create(7, 1, maxHistory = 0)
        terminal.writeRepeatedCluster(intArrayOf('A'.code), 1, 65_536)

        assertEquals("AA", terminal.getLineAsString(0))
        assertEquals(2, terminal.cursorCol)
        assertEquals(0, terminal.cursorRow)
        assertEquals(0, terminal.historySize)
        terminal.readRenderFrame { frame ->
            assertEquals(9_362L, frame.discardedCount)
            assertFalse(frame.cursorPendingWrap)
        }
    }

    @Test
    fun `repeated scalar writes preserve wide occupants pen attributes and deferred wrap`() {
        val terminal = TerminalBuffers.create(6, 2)
        terminal.writeText("界xy界")
        terminal.positionCursor(col = 1, row = 0)
        terminal.setPenColors(CellColor.rgb(0x123456), CellColor.indexed(7), underlineStyle = UnderlineStyle.CURLY)
        terminal.setHyperlinkId(17)
        terminal.setSelectiveEraseProtection(true)
        terminal.writeRepeatedCluster(intArrayOf('A'.code), 1, 5)
        assertEquals(" AAAAA", terminal.getLineAsString(0))
        assertEquals(5, terminal.cursorCol)
        for (col in 1..5) {
            val attr = terminal.getAttrAt(col, 0)
            assertEquals(CellColor.rgb(0x123456), attr?.foreground)
            assertEquals(UnderlineStyle.CURLY, attr?.underlineStyle)
            assertEquals(17, attr?.hyperlinkId)
            assertEquals(true, attr?.selectiveEraseProtected)
        }
        terminal.writeCodepoint('Z'.code)
        assertEquals("Z", terminal.getLineAsString(1))
        assertEquals(1, terminal.cursorCol)
        assertEquals(1, terminal.cursorRow)
    }

    @Test
    fun `repeated graphemes match successive writes through modes margins history and cluster replacement`() {
        val graphics =
            listOf(
                intArrayOf('A'.code),
                intArrayOf('─'.code),
                intArrayOf('界'.code),
                intArrayOf('e'.code, 0x0301),
                intArrayOf(0x1f469, 0x200d, 0x1f4bb),
            )
        for (graphic in graphics) {
            for (mode in 0 until 32) {
                for (count in listOf(-1, 0, 1, 7, 81)) {
                    val actual = TerminalBuffers.create(8, 3, maxHistory = 2)
                    val expected = TerminalBuffers.create(8, 3, maxHistory = 2)
                    for (terminal in listOf(actual, expected)) {
                        if (mode and 1 != 0) terminal.enterAltBuffer()
                        repeat(3) {
                            terminal.writeText("界")
                            terminal.writeCluster(intArrayOf('e'.code, 0x0301))
                            terminal.writeText("12345")
                        }
                        terminal.setAutoWrap(mode and 2 == 0)
                        terminal.setInsertMode(mode and 4 != 0)
                        terminal.setTreatAmbiguousAsWide(mode and 8 != 0)
                        if (mode and 16 != 0) {
                            terminal.setLeftRightMarginMode(true)
                            terminal.setLeftRightMargins(2, 7)
                            terminal.setScrollRegion(2, 3)
                        }
                        terminal.positionCursor(1, 1)
                        terminal.setPenColors(CellColor.rgb(0x123456), CellColor.indexed(7), underlineStyle = UnderlineStyle.CURLY)
                    }
                    actual.writeRepeatedCluster(graphic, graphic.size, count)
                    repeat(maxOf(count, 0)) { expected.writeCluster(graphic) }
                    val context = "graphic=${graphic.contentToString()} mode=$mode count=$count"
                    assertEquals(expected.getAllAsString(), actual.getAllAsString(), context)
                    assertEquals(expected.cursorCol, actual.cursorCol, context)
                    assertEquals(expected.cursorRow, actual.cursorRow, context)
                    assertEquals(expected.historySize, actual.historySize, context)
                    val expectedCluster = IntArray(32)
                    val actualCluster = IntArray(32)
                    for (row in 0..2) {
                        for (col in 0..7) {
                            assertEquals(expected.getCodepointAt(col, row), actual.getCodepointAt(col, row), context)
                            assertEquals(expected.getAttrAt(col, row), actual.getAttrAt(col, row), context)
                            expectedCluster.fill(0)
                            actualCluster.fill(0)
                            assertEquals(
                                expected.getLine(row).readCluster(col, expectedCluster),
                                actual.getLine(row).readCluster(col, actualCluster),
                                context,
                            )
                            assertArrayEquals(expectedCluster, actualCluster, context)
                        }
                    }
                    actual.writeCodepoint('Z'.code)
                    expected.writeCodepoint('Z'.code)
                    assertEquals(expected.getAllAsString(), actual.getAllAsString(), context)
                    assertEquals(expected.cursorCol, actual.cursorCol, context)
                    assertEquals(expected.cursorRow, actual.cursorRow, context)
                }
            }
        }
    }

    @Test
    fun `repeated graphemes validate the whole prefix before any mutation even for nonpositive counts`() {
        val terminal = TerminalBuffers.create(6, 2)
        terminal.writeText("AB")
        for (count in listOf(-1, 0, 3)) {
            for ((graphic, length) in listOf(
                intArrayOf(65) to 0,
                intArrayOf(65) to 2,
                IntArray(33) { 65 } to 33,
                intArrayOf(65, 0xd800) to 2,
                intArrayOf(65, 0x110000) to 2,
                intArrayOf(-1) to 1,
            )) {
                assertThrows(IllegalArgumentException::class.java) { terminal.writeRepeatedCluster(graphic, length, count) }
                assertEquals("AB", terminal.getLineAsString(0))
                assertEquals(2, terminal.cursorCol)
            }
        }
        terminal.writeRepeatedCluster(intArrayOf(65), 1, 0)
        terminal.writeRepeatedCluster(intArrayOf(65), 1, -1)
        terminal.updatePreviousCluster(intArrayOf('B'.code, 0x0301))
        assertEquals("AB\u0301", terminal.getLineAsString(0), "empty repetition preserves the previous cluster target")
    }

    @Test
    fun `partial pen updates distinguish unchanged fields defaults and false flags`() {
        val terminal = TerminalBuffers.create(4, 2)
        terminal.setPenColors(CellColor.rgb(0x123456), CellColor.indexed(255), bold = true, faint = true)
        terminal.setHyperlinkId(Int.MAX_VALUE)
        terminal.setSelectiveEraseProtection(true)
        terminal.writeCodepoint('A'.code)
        val before = terminal.getAttrAt(0, 0)
        terminal.updatePenColors()
        terminal.writeCodepoint('B'.code)
        assertEquals(before, terminal.getAttrAt(1, 0))
        terminal.updatePenColors(foreground = CellColor.DEFAULT, bold = false, underlineStyle = UnderlineStyle.CURLY)
        terminal.writeCodepoint('C'.code)
        assertEquals(
            before?.copy(foreground = CellColor.DEFAULT, bold = false, underlineStyle = UnderlineStyle.CURLY),
            terminal.getAttrAt(2, 0),
        )
        assertEquals(before, terminal.getAttrAt(0, 0), "pen updates do not modify previously written cells")
        terminal.saveCursor()
        terminal.resetPen()
        terminal.restoreCursor()
        terminal.updatePenColors(background = CellColor.indexed(0), faint = false)
        terminal.writeCodepoint('D'.code)
        assertEquals(
            CellAttributes(
                background = CellColor.indexed(0),
                underlineStyle = UnderlineStyle.CURLY,
                hyperlinkId = Int.MAX_VALUE,
                selectiveEraseProtected = true,
            ),
            terminal.getAttrAt(3, 0),
        )
    }

    @ParameterizedTest
    @CsvSource("true,false", "false,false", "true,true", "false,true")
    fun `counted scroll preserves bounded scalar semantics for cells attributes and history`(
        up: Boolean,
        alternate: Boolean,
    ) {
        for (top in listOf(1, 2)) {
            for (partialWidth in listOf(false, true)) {
                for (count in listOf(-1, 0, 1, 2, 3, Int.MAX_VALUE)) {
                    val actual = TerminalBuffers.create(8, 5, maxHistory = 2)
                    val expected = TerminalBuffers.create(8, 5, maxHistory = 2)
                    for (terminal in listOf(actual, expected)) {
                        if (alternate) terminal.enterAltBuffer()
                        for (row in 0 until 5) {
                            terminal.positionCursor(0, row)
                            terminal.writeText("${row}界")
                            terminal.writeCluster(intArrayOf('e'.code, 0x0301), 2)
                            terminal.writeText("END")
                        }
                        terminal.setScrollRegion(top, top + 2)
                        if (partialWidth) {
                            terminal.setLeftRightMarginMode(true)
                            terminal.setLeftRightMargins(3, 6)
                        }
                        terminal.setPenAttributes(3, 5, bold = true)
                        terminal.positionCursor(4, 2)
                    }
                    if (up) actual.scrollUp(count) else actual.scrollDown(count)
                    repeat(count.coerceIn(0, 3)) {
                        if (up) expected.scrollUp() else expected.scrollDown()
                    }
                    val context = "up=$up alternate=$alternate top=$top partial=$partialWidth count=$count"
                    assertEquals(expected.getAllAsString(), actual.getAllAsString(), context)
                    assertEquals(expected.historySize, actual.historySize, context)
                    assertEquals(expected.cursorRow, actual.cursorRow, context)
                    assertEquals(expected.cursorCol, actual.cursorCol, context)
                    for (row in 0 until 5) {
                        for (col in 0 until 8) {
                            assertEquals(expected.getCodepointAt(col, row), actual.getCodepointAt(col, row), context)
                            assertEquals(expected.getAttrAt(col, row), actual.getAttrAt(col, row), context)
                        }
                    }
                    var discarded = -1L
                    (expected as TerminalRenderFrameReader).readRenderFrame { discarded = it.discardedCount }
                    (actual as TerminalRenderFrameReader).readRenderFrame { assertEquals(discarded, it.discardedCount, context) }
                }
            }
        }
    }

    @Test
    fun `writes codepoints and advances the cursor`() {
        val state = TerminalState(5, 2, 2)
        val writer = BufferWriter(state, MutationEngine(state), CursorEngine(state))

        writer.setPenAttributes(3, 7, bold = true, italic = true)
        writer.writeCodepoint('X'.code)

        assertAll(
            { assertEquals('X'.code, state.ring[state.resolveRingIndex(0)].getCodepoint(0)) },
            { assertEquals(1, state.cursor.col) },
            { assertEquals(0, state.cursor.row) },
            {
                assertEquals(
                    CellAttributes(
                        foreground = CellColor.indexed(2),
                        background = CellColor.indexed(6),
                        bold = true,
                        italic = true,
                        underlineStyle = UnderlineStyle.NONE,
                    ),
                    AttributeCodec.unpack(
                        state.ring[state.resolveRingIndex(0)].getPackedAttr(0),
                        state.ring[state.resolveRingIndex(0)].getPackedExtendedAttr(0),
                    ),
                )
            },
        )
    }

    @Test
    fun `writes codepoints with explicit rgb indexed and inverse colors`() {
        val state = TerminalState(5, 2, 2)
        val writer = BufferWriter(state, MutationEngine(state), CursorEngine(state))

        writer.setPenColors(
            foreground = CellColor.rgb(0x10, 0x20, 0x30),
            background = CellColor.indexed(231),
            underlineStyle = UnderlineStyle.SINGLE,
            inverse = true,
        )
        writer.writeCodepoint('R'.code)

        val unpacked =
            AttributeCodec.unpack(
                state.ring[state.resolveRingIndex(0)].getPackedAttr(0),
                state.ring[state.resolveRingIndex(0)].getPackedExtendedAttr(0),
            )

        assertAll(
            { assertEquals(CellColor.rgb(0x10, 0x20, 0x30), unpacked.foreground) },
            { assertEquals(CellColor.indexed(231), unpacked.background) },
            { assertEquals(UnderlineStyle.SINGLE, unpacked.underlineStyle) },
            { assertTrue(unpacked.inverse) },
        )
    }

    @Test
    fun `writes text with supplementary code points literally`() {
        val state = TerminalState(6, 2, 2)
        val writer = BufferWriter(state, MutationEngine(state), CursorEngine(state))

        writer.writeText("A\uD83D\uDE00B")

        assertAll(
            { assertEquals("A\uD83D\uDE00B", state.ring[state.resolveRingIndex(0)].toTextTrimmed()) },
            { assertEquals(4, state.cursor.col) },
        )
    }

    @Test
    fun `writes parser segmented cluster through explicit cluster api`() {
        val state = TerminalState(6, 2, 2)
        val writer = BufferWriter(state, MutationEngine(state), CursorEngine(state))

        writer.writeCluster(intArrayOf('e'.code, 0x0301))

        val dest = IntArray(4)
        val written = state.ring[state.resolveRingIndex(0)].readCluster(0, dest)

        assertAll(
            { assertTrue(state.ring[state.resolveRingIndex(0)].isCluster(0)) },
            { assertEquals(2, written) },
            { assertEquals('e'.code, dest[0]) },
            { assertEquals(0x0301, dest[1]) },
        )
    }

    @Test
    fun `clearAll wipes screen history cursor and saved cursor`() {
        val state = TerminalState(4, 2, 2)
        val mutation = MutationEngine(state)
        val writer = BufferWriter(state, mutation, CursorEngine(state))

        writer.writeText("ABCD")
        state.savedCursor.isSaved = true
        writer.clearAll()

        assertAll(
            { assertEquals("", state.ring[state.resolveRingIndex(0)].toTextTrimmed()) },
            { assertEquals(0, state.cursor.col) },
            { assertEquals(0, state.cursor.row) },
            { assertEquals(false, state.savedCursor.isSaved) },
        )
    }
}
