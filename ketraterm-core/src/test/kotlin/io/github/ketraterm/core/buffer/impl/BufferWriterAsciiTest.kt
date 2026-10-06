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
import io.github.ketraterm.core.api.TerminalRenderBuffer
import io.github.ketraterm.core.api.TerminalWriter
import io.github.ketraterm.core.model.CellColor
import io.github.ketraterm.core.model.UnderlineStyle
import io.github.ketraterm.render.api.TerminalRenderFrame
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class BufferWriterAsciiTest {
    @ParameterizedTest
    @CsvSource("1,0", "1,2", "4,0", "4,10", "80,2", "80,10")
    fun `ASCII spans match scalar writes through wrapping history eviction and reflow`(
        width: Int,
        history: Int,
    ) {
        val actual = TerminalBuffers.create(width, 3, maxHistory = history)
        val expected = TerminalBuffers.create(width, 3, maxHistory = history)
        repeat(20) { row ->
            val text =
                buildString {
                    repeat(width * 3 + row % 5) { append((0x20 + (it + row) % 95).toChar()) }
                }
            val bytes = byteArrayOf(0) + text.toByteArray(Charsets.US_ASCII) + byteArrayOf(-1)
            actual.writeAscii(bytes, 1, text.length)
            text.forEach { expected.writeCodepoint(it.code) }
            assertEquivalent(expected, actual)
            for (terminal in listOf(actual, expected)) {
                terminal.carriageReturn()
                terminal.newLine()
            }
        }
        assertEquivalent(expected, actual)
        for (terminal in listOf(actual, expected)) terminal.resize(width + 2, 4)
        assertEquivalent(expected, actual)
        for (terminal in listOf(actual, expected)) terminal.resize(width, 3)
        assertEquivalent(expected, actual)
    }

    @Test
    fun `filling the final column defers wrap until the next printable and CR cancels it`() {
        val terminal = TerminalBuffers.create(4, 2)
        terminal.writeAscii("ABCD".toByteArray(), 0, 4)
        assertEquals("ABCD\n", terminal.getScreenAsString())
        assertEquals(3, terminal.cursorCol)
        assertEquals(0, terminal.cursorRow)
        terminal.readRenderFrame { assertFalse(it.lineWrapped(0)) }

        terminal.carriageReturn()
        terminal.writeAscii("XY".toByteArray(), 0, 2)
        assertEquals("XYCD\n", terminal.getScreenAsString())
        assertEquals(0, terminal.cursorRow)

        terminal.clearAll()
        terminal.writeAscii("ABCD".toByteArray(), 0, 4)
        terminal.writeAscii("EF".toByteArray(), 0, 2)
        assertEquals("ABCD\nEF", terminal.getScreenAsString())
        assertEquals(2, terminal.cursorCol)
        assertEquals(1, terminal.cursorRow)
        terminal.readRenderFrame { assertTrue(it.lineWrapped(0)) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["overwrite", "insert", "nowrap", "margins", "scroll-region", "alternate", "ambiguous-wide"])
    fun `ASCII spans preserve scalar behavior for complex occupants attributes and terminal modes`(mode: String) {
        val actual = TerminalBuffers.create(8, 4, maxHistory = 3)
        val expected = TerminalBuffers.create(8, 4, maxHistory = 3)
        for (terminal in listOf(actual, expected)) {
            if (mode == "alternate") terminal.enterAltBuffer()
            repeat(4) { row ->
                terminal.positionCursor(0, row)
                terminal.writeText("A界")
                terminal.writeCluster(intArrayOf('e'.code, 0x0301))
                terminal.writeCluster(intArrayOf(0x1F469, 0x200D, 0x1F4BB))
                terminal.writeText("Z")
            }
            when (mode) {
                "insert" -> terminal.setInsertMode(true)
                "nowrap" -> terminal.setAutoWrap(false)
                "margins" -> {
                    terminal.setLeftRightMarginMode(true)
                    terminal.setLeftRightMargins(2, 7)
                }
                "scroll-region" -> terminal.setScrollRegion(2, 3)
                "ambiguous-wide" -> terminal.setTreatAmbiguousAsWide(true)
            }
            terminal.setPenColors(
                CellColor.rgb(0x123456),
                CellColor.indexed(255),
                CellColor.rgb(0x654321),
                bold = true,
                italic = true,
                underlineStyle = UnderlineStyle.CURLY,
                inverse = true,
            )
            terminal.setHyperlinkId(12345)
            terminal.setSelectiveEraseProtection(true)
            terminal.positionCursor(2, 0)
        }
        for (text in listOf("bc", "defghijklmnop", "q", "rstuvwxyz-0123456789")) {
            actual.writeAscii(text.toByteArray(), 0, text.length)
            text.forEach { expected.writeCodepoint(it.code) }
            assertEquivalent(expected, actual)
            val continuation = intArrayOf(text.last().code, 0x0301)
            for (terminal in listOf(actual, expected)) terminal.updatePreviousCluster(continuation)
            assertEquivalent(expected, actual)
        }
        if (mode == "alternate") {
            for (terminal in listOf(actual, expected)) terminal.exitAltBuffer()
            assertEquivalent(expected, actual)
        }
    }

    @Test
    fun `replacing wide wrap padding preserves reflow and the last printable continuation`() {
        val actual = TerminalBuffers.create(4, 3, maxHistory = 4)
        val expected = TerminalBuffers.create(4, 3, maxHistory = 4)
        for (terminal in listOf(actual, expected)) {
            terminal.writeText("ABC界")
            terminal.positionCursor(3, 0)
        }
        actual.writeAscii("123456".toByteArray(), 0, 6)
        "123456".forEach { expected.writeCodepoint(it.code) }
        for (terminal in listOf(actual, expected)) terminal.updatePreviousCluster(intArrayOf('6'.code, 0xFE0F, 0x20E3))
        assertEquivalent(expected, actual)
        for (terminal in listOf(actual, expected)) terminal.resize(6, 3)
        assertEquivalent(expected, actual)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `invalid ranges and nonprintable bytes fail before any mutation`(defaultImplementation: Boolean) {
        val terminal = TerminalBuffers.create(4, 2)
        val writer = if (defaultImplementation) scalarAsciiWriter(terminal) else terminal
        writer.writeAscii("ABCD".toByteArray(), 0, 4)
        val generations = generations(terminal)
        val invalid =
            listOf(
                Triple(byteArrayOf(65), -1, 1),
                Triple(byteArrayOf(65), 0, -1),
                Triple(byteArrayOf(65), 2, 0),
                Triple(byteArrayOf(65), 0, 2),
                Triple(byteArrayOf(65), Int.MAX_VALUE, 1),
                Triple(byteArrayOf(65), 1, Int.MAX_VALUE),
                Triple(byteArrayOf(65, 0), 0, 2),
                Triple(byteArrayOf(65, 0x1F), 0, 2),
                Triple(byteArrayOf(65, 0x7F), 0, 2),
                Triple(byteArrayOf(65, 0x80.toByte()), 0, 2),
                Triple(byteArrayOf(65, 0xFF.toByte()), 0, 2),
            )
        for ((bytes, offset, length) in invalid) {
            assertThrows(IllegalArgumentException::class.java) { writer.writeAscii(bytes, offset, length) }
            assertEquals("ABCD\n", terminal.getScreenAsString())
            assertEquals(3, terminal.cursorCol)
            assertEquals(0, terminal.cursorRow)
            assertEquals(generations, generations(terminal))
        }
        terminal.updatePreviousCluster(intArrayOf('D'.code, 0x0301))
        assertEquals("ABCD\u0301\n", terminal.getScreenAsString())
        terminal.writeCodepoint('E'.code)
        assertEquals('E'.code, terminal.getCodepointAt(0, 1))
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `empty ranges preserve generations wrap and continuation and arrays are not retained`(defaultImplementation: Boolean) {
        val terminal = TerminalBuffers.create(4, 2)
        val writer = if (defaultImplementation) scalarAsciiWriter(terminal) else terminal
        val bytes = "ABCD".toByteArray()
        writer.writeAscii(bytes, 0, bytes.size)
        bytes.fill('X'.code.toByte())
        val generations = generations(terminal)
        writer.writeAscii(byteArrayOf(0), 1, 0)
        writer.writeAscii(byteArrayOf(), 0, 0)
        assertEquals(generations, generations(terminal))
        terminal.updatePreviousCluster(intArrayOf('D'.code, 0x0301))
        assertEquals("ABCD\u0301\n", terminal.getScreenAsString())
        terminal.writeCodepoint('E'.code)
        assertEquals('E'.code, terminal.getCodepointAt(0, 1))
    }

    @Test
    fun `span changes content cursor and affected line generations`() {
        val terminal = TerminalBuffers.create(8, 2)
        var frameGeneration = 0L
        var contentGeneration = 0L
        var cursorGeneration = 0L
        var firstLineGeneration = 0L
        var secondLineGeneration = 0L
        terminal.readRenderFrame {
            frameGeneration = it.frameGeneration
            contentGeneration = it.contentGeneration
            cursorGeneration = it.cursor.generation
            firstLineGeneration = it.lineGeneration(0)
            secondLineGeneration = it.lineGeneration(1)
        }
        terminal.writeAscii("ABCD".toByteArray(), 0, 4)
        terminal.readRenderFrame {
            assertNotEquals(frameGeneration, it.frameGeneration)
            assertNotEquals(contentGeneration, it.contentGeneration)
            assertNotEquals(cursorGeneration, it.cursor.generation)
            assertNotEquals(firstLineGeneration, it.lineGeneration(0))
            assertEquals(secondLineGeneration, it.lineGeneration(1))
            assertEquals(1L, it.outputEndAbsoluteRow)
        }
    }

    private fun generations(terminal: TerminalRenderBuffer): List<Long> {
        var result = emptyList<Long>()
        terminal.readRenderFrame { result = listOf(it.frameGeneration, it.contentGeneration, it.structureGeneration, it.cursor.generation) }
        return result
    }

    private fun scalarAsciiWriter(terminal: TerminalWriter): TerminalWriter =
        object : TerminalWriter by terminal {
            override fun writeAscii(
                bytes: ByteArray,
                offset: Int,
                length: Int,
            ) = super.writeAscii(bytes, offset, length)
        }

    private fun assertEquivalent(
        expected: TerminalRenderBuffer,
        actual: TerminalRenderBuffer,
    ) {
        assertEquals(expected.cursorCol, actual.cursorCol)
        assertEquals(expected.cursorRow, actual.cursorRow)
        assertEquals(expected.getAllAsString(), actual.getAllAsString())
        expected.readRenderFrameForAbsoluteRange(0, Long.MAX_VALUE) { expectedFrame ->
            actual.readRenderFrameForAbsoluteRange(0, Long.MAX_VALUE) { actualFrame ->
                assertEquals(expectedFrame.columns, actualFrame.columns)
                assertEquals(expectedFrame.rows, actualFrame.rows)
                assertEquals(expectedFrame.historySize, actualFrame.historySize)
                assertEquals(expectedFrame.discardedCount, actualFrame.discardedCount)
                assertEquals(expectedFrame.outputEndAbsoluteRow, actualFrame.outputEndAbsoluteRow)
                assertEquals(expectedFrame.activeBuffer, actualFrame.activeBuffer)
                repeat(expectedFrame.rows) { row ->
                    assertEquals(expectedFrame.lineWrapped(row), actualFrame.lineWrapped(row), "wrap row=$row")
                    assertEquals(copyRow(expectedFrame, row), copyRow(actualFrame, row), "row=$row")
                }
            }
        }
    }

    private fun copyRow(
        frame: TerminalRenderFrame,
        row: Int,
    ): List<Any> {
        val codepoints = IntArray(frame.columns)
        val attributes = LongArray(frame.columns)
        val extended = LongArray(frame.columns)
        val flags = IntArray(frame.columns)
        val hyperlinks = IntArray(frame.columns)
        val clusters = mutableMapOf<Int, String>()
        frame.copyLine(
            row,
            codeWords = codepoints,
            attrWords = attributes,
            extraAttrWords = extended,
            flags = flags,
            hyperlinkIds = hyperlinks,
            clusterSink = { column, text -> clusters[column] = text },
        )
        return listOf(codepoints.toList(), attributes.toList(), extended.toList(), flags.toList(), hyperlinks.toList(), clusters)
    }
}
