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
package io.github.ketraterm.host

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.core.model.CellColor
import io.github.ketraterm.parser.api.TerminalParsers
import io.github.ketraterm.parser.spi.TerminalCommandSink
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HostRepeatTest {
    @Test
    fun `maximum wire REP matches bounded ordinary writes across placement modes`() {
        val setups = listOf("", "\u001B[?7l", "\u001B[4h", "\u001B[?69h\u001B[2;7s\u001B[2;3r", "\u001B[?1049h")
        for (graphic in listOf("A", "界", "e\u0301", "👩‍💻", "e" + "\u0301".repeat(31))) {
            val codepoints = graphic.codePoints().toArray()
            for (setup in setups) {
                val actual = TerminalBuffers.create(9, 4, maxHistory = 3)
                val expected = TerminalBuffers.create(9, 4, maxHistory = 3)
                val adapter = HostCommandAdapter(actual)
                val parser =
                    TerminalParsers.create(
                        object : TerminalCommandSink by adapter {
                            override fun writeRepeatedCluster(
                                codepoints: IntArray,
                                length: Int,
                                count: Int,
                            ) {
                                // Fail before grid mutation if admission regresses; never run billions of writes in a test.
                                check(count in 1..65_535) { "unbounded REP reached the host: $count" }
                                adapter.writeRepeatedCluster(codepoints, length, count)
                            }
                        },
                    )
                val referenceParser = TerminalParsers.create(HostCommandAdapter(expected))
                val prefix = ("outside\r" + setup + "\u001B[31m" + graphic).encodeToByteArray()
                parser.accept(prefix)
                referenceParser.accept(prefix)
                parser.accept("\u001B[2147483647b\u001B[2bZ".encodeToByteArray())
                repeat(65_535) { expected.writeCluster(codepoints, codepoints.size) }
                referenceParser.accept("\u001B[2bZ".encodeToByteArray())
                val context = "graphic=$graphic setup=$setup"
                assertEquals(expected.getAllAsString(), actual.getAllAsString(), context)
                assertEquals(expected.cursorCol, actual.cursorCol, context)
                assertEquals(expected.cursorRow, actual.cursorRow, context)
                assertEquals(expected.historySize, actual.historySize, context)
                expected.readRenderFrame { expectedFrame ->
                    actual.readRenderFrame { actualFrame ->
                        assertEquals(expectedFrame.discardedCount, actualFrame.discardedCount, context)
                        assertEquals(expectedFrame.cursorPendingWrap, actualFrame.cursorPendingWrap, context)
                        for (row in 0 until 4) {
                            assertEquals(expectedFrame.lineWrapped(row), actualFrame.lineWrapped(row), context)
                            for (col in 0 until 9) {
                                assertEquals(expected.getCodepointAt(col, row), actual.getCodepointAt(col, row), context)
                                assertEquals(expected.getAttrAt(col, row), actual.getAttrAt(col, row), context)
                            }
                        }
                    }
                }
                if (setup == "\u001B[?1049h") {
                    parser.accept("\u001B[?1049l".encodeToByteArray())
                    referenceParser.accept("\u001B[?1049l".encodeToByteArray())
                    assertEquals("outside", actual.getLineAsString(0))
                    assertEquals(expected.getAllAsString(), actual.getAllAsString(), context)
                }
            }
        }
    }

    @Test
    fun `REP preserves ASCII wide and complete Unicode graphics at every byte split`() {
        for ((graphic, width) in listOf(
            "c" to 1,
            "界" to 2,
            "e\u0301" to 1,
            "#\uFE0F\u20E3" to 2,
            "👩‍💻" to 2,
            "🇦🇲" to 2,
        )) {
            val bytes = ("ab" + graphic + "\u001B[2b\u001B[bZ").encodeToByteArray()
            for (split in -1..bytes.size) {
                val terminal = TerminalBuffers.create(40, 2)
                val parser = TerminalParsers.create(HostCommandAdapter(terminal))
                if (split < 0) {
                    for (byte in bytes) parser.acceptByte(byte.toInt() and 0xff)
                } else {
                    parser.accept(bytes, 0, split)
                    parser.accept(bytes, split, bytes.size - split)
                }
                parser.endOfInput()
                val context = "graphic=$graphic split=$split"
                assertEquals("ab" + graphic.repeat(4) + "Z", terminal.getLineAsString(0), context)
                assertEquals(3 + 4 * width, terminal.cursorCol, context)
                assertEquals(0, terminal.cursorRow, context)
            }
        }
    }

    @Test
    fun `REP uses current attributes and remembers text across cursor moves erasure and charset changes`() {
        val terminal = TerminalBuffers.create(10, 3)
        val parser = TerminalParsers.create(HostCommandAdapter(terminal))
        parser.accept("\u001B(0q\u001B(B\u001B[2K\u001B[31m\u001B[2;1H\u001B[3b".encodeToByteArray())
        assertEquals("", terminal.getLineAsString(0))
        assertEquals("───", terminal.getLineAsString(1))
        assertEquals(3, terminal.cursorCol)
        for (col in 0..2) assertEquals(CellColor.indexed(1), terminal.getAttrAt(col, 1)?.foreground)
        parser.accept(" \u001B[2b".encodeToByteArray())
        assertEquals(' '.code, terminal.getCodepointAt(5, 1), "spaces are repeatable graphics")
        assertEquals(6, terminal.cursorCol)
    }

    @Test
    fun `REP consumes deferred wrap and scrolls into bounded history like printed output`() {
        val terminal = TerminalBuffers.create(5, 3, maxHistory = 2)
        val parser = TerminalParsers.create(HostCommandAdapter(terminal))
        parser.accept("A\u001B[24b".encodeToByteArray())
        assertEquals("AAAAA\nAAAAA\nAAAAA\nAAAAA\nAAAAA", terminal.getAllAsString())
        assertEquals(2, terminal.historySize)
        assertEquals(4, terminal.cursorCol)
        assertEquals(2, terminal.cursorRow)
        parser.accept("X".encodeToByteArray())
        assertEquals("AAAAA\nAAAAA\nAAAAA\nAAAAA\nX", terminal.getAllAsString())
        assertEquals(1, terminal.cursorCol)
    }

    @Test
    fun `REP obeys insert mode horizontal margins and alternate screen ownership`() {
        val terminal = TerminalBuffers.create(8, 3)
        val parser = TerminalParsers.create(HostCommandAdapter(terminal))
        parser.accept("abcd\rX\u001B[4h\u001B[2b".encodeToByteArray())
        assertEquals("XXXbcd", terminal.getLineAsString(0))
        parser.accept("\u001B[4l\u001B[?1049h\u001B[?69h\u001B[2;5sA\u001B[5b".encodeToByteArray())
        assertEquals('A'.code, terminal.getCodepointAt(1, 0))
        assertEquals(" AAAA", terminal.getLineAsString(0))
        assertEquals(" AA", terminal.getLineAsString(1))
        assertEquals(3, terminal.cursorCol)
        assertEquals(1, terminal.cursorRow)
        parser.accept("\u001B[?1049l".encodeToByteArray())
        assertEquals("XXXbcd", terminal.getLineAsString(0))
        assertEquals(0, terminal.historySize)
    }

    @Test
    fun `REP honors disabled autowrap and recovers malformed UTF8 before CSI`() {
        val terminal = TerminalBuffers.create(5, 2)
        val parser = TerminalParsers.create(HostCommandAdapter(terminal))
        parser.accept("\u001B[?7lA\u001B[20b".encodeToByteArray())
        assertEquals("AAAAA", terminal.getLineAsString(0))
        assertEquals(4, terminal.cursorCol)
        assertEquals(0, terminal.cursorRow)
        parser.accept("\r".encodeToByteArray() + byteArrayOf(0xc3.toByte()) + "\u001B[2b".encodeToByteArray())
        assertEquals("���AA", terminal.getLineAsString(0))
        assertEquals(3, terminal.cursorCol)
    }
}
