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
import io.github.ketraterm.core.api.TerminalBuffer
import io.github.ketraterm.core.model.CellColor
import io.github.ketraterm.parser.api.TerminalParsers
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class HostGraphemeTest {
    @Test
    fun `full updates preserve original styling and precede structural commands`() {
        for (suffix in listOf("X", "\u001B[32mX", "\r\nX")) {
            val terminal = TerminalBuffers.create(6, 2)
            val parser = TerminalParsers.create(HostCommandAdapter(terminal))
            parser.accept("\u001B[31me".encodeToByteArray())
            parser.accept(("\u0301\u0300" + suffix).encodeToByteArray())
            assertCluster(terminal, 0, 0, intArrayOf('e'.code, 0x0301, 0x0300), suffix)
            assertEquals(CellColor.indexed(1), terminal.getAttrAt(0, 0)?.foreground)
            val newLine = suffix.startsWith("\r")
            val column = if (newLine) 0 else 1
            val row = if (newLine) 1 else 0
            assertEquals('X'.code, terminal.getCodepointAt(column, row))
            assertEquals(CellColor.indexed(if (suffix.startsWith("\u001B")) 2 else 1), terminal.getAttrAt(column, row)?.foreground)
        }
    }

    @Test
    fun `long graphemes retain one span across every byte split`() {
        val clusters =
            listOf(
                "a" + "\u0301".repeat(40),
                "\u4E00" + "\u0301".repeat(40),
                "\uD83D\uDC69" + "\u200D\uD83D\uDC69".repeat(20),
                "\u1100" + "\u1161".repeat(40),
            )
        for ((index, cluster) in clusters.withIndex()) {
            val retained = cluster.codePoints().limit(32).toArray()
            val width = if (index == 0) 1 else 2
            val bytes = (cluster + "X").encodeToByteArray()
            for (split in -1..bytes.size) {
                val terminal = TerminalBuffers.create(6, 2)
                val parser = TerminalParsers.create(HostCommandAdapter(terminal))
                if (split < 0) {
                    for (byte in bytes) parser.acceptByte(byte.toInt() and 0xff)
                } else {
                    parser.accept(bytes, 0, split)
                    parser.accept(bytes, split, bytes.size - split)
                }
                parser.endOfInput()
                val context = "cluster=$index split=$split"
                assertCluster(terminal, 0, 0, retained, context)
                if (width == 2) assertEquals(-1, terminal.getCodepointAt(1, 0), context)
                assertEquals('X'.code, terminal.getCodepointAt(width, 0), context)
                assertEquals(width + 1, terminal.cursorCol, context)
                assertEquals(0, terminal.cursorRow, context)
            }
        }
    }

    @Test
    fun `discarded variation selector cannot widen the retained prefix`() {
        val prefix = "\u2764" + "\u0301".repeat(31)
        val bytes = (prefix + "\uFE0F" + "\u0300".repeat(100) + "X").encodeToByteArray()
        for (split in 0..bytes.size) {
            val terminal = TerminalBuffers.create(2, 2)
            val parser = TerminalParsers.create(HostCommandAdapter(terminal))
            parser.accept(bytes, 0, split)
            parser.accept(bytes, split, bytes.size - split)
            assertCluster(terminal, 0, 0, prefix.codePoints().toArray(), "split=$split")
            assertEquals('X'.code, terminal.getCodepointAt(1, 0))
            assertEquals(0, terminal.cursorRow)
        }
    }

    @Test
    fun `retained variation selectors determine width across read boundaries within a row`() {
        for (startColumn in 0..1) {
            for (shrink in listOf(false, true)) {
                val base = if (shrink) "\uD83D\uDE00" else "\u2764"
                val selector = if (shrink) "\uFE0E" else "\uFE0F"
                val cluster = base + "\u0301".repeat(30) + selector
                val bytes = (cluster + "X").encodeToByteArray()
                for (split in 0..bytes.size) {
                    val terminal = TerminalBuffers.create(4, 3)
                    terminal.positionCursor(startColumn, 0)
                    val parser = TerminalParsers.create(HostCommandAdapter(terminal))
                    parser.accept(bytes, 0, split)
                    parser.accept(bytes, split, bytes.size - split)
                    val clusterWidth = if (shrink) 1 else 2
                    val xColumn = startColumn + clusterWidth
                    val context = "start=$startColumn shrink=$shrink split=$split"
                    assertCluster(terminal, startColumn, 0, cluster.codePoints().toArray(), context)
                    assertEquals('X'.code, terminal.getCodepointAt(xColumn, 0), context)
                    assertEquals(0, terminal.cursorRow, context)
                    assertEquals(minOf(xColumn + 1, 3), terminal.cursorCol, context)
                }
            }
        }
    }

    @Test
    fun `overflow preserves narrow and wide wrapping inside horizontal margins`() {
        for (base in listOf("a", "\u4E00")) {
            for (chunked in listOf(false, true)) {
                val terminal = TerminalBuffers.create(8, 3)
                val parser = TerminalParsers.create(HostCommandAdapter(terminal))
                parser.accept("LL....RR\r\nLL....RR\u001B[?69h\u001B[3;6s\u001B[1;6H".encodeToByteArray())
                val bytes = (base + "\u0301".repeat(100) + "X").encodeToByteArray()
                if (chunked) for (byte in bytes) parser.acceptByte(byte.toInt() and 0xff) else parser.accept(bytes)
                val wide = base == "\u4E00"
                assertCluster(
                    terminal,
                    if (wide) 2 else 5,
                    if (wide) 1 else 0,
                    (base + "\u0301".repeat(31)).codePoints().toArray(),
                    "chunked=$chunked",
                )
                assertEquals('X'.code, terminal.getCodepointAt(if (wide) 4 else 2, 1))
                if (wide) assertEquals(-1, terminal.getCodepointAt(3, 1))
                assertEquals(1, terminal.cursorRow)
                assertEquals(if (wide) 5 else 3, terminal.cursorCol)
                for (row in 0..1) {
                    assertEquals('L'.code, terminal.getCodepointAt(1, row))
                    assertEquals('R'.code, terminal.getCodepointAt(6, row))
                }
            }
        }
    }

    @Test
    fun `EOF repairs incomplete UTF8 after overflow exactly once`() {
        val terminal = TerminalBuffers.create(5, 2)
        val parser = TerminalParsers.create(HostCommandAdapter(terminal))
        parser.accept(("a" + "\u0301".repeat(100)).encodeToByteArray() + byteArrayOf(0xc3.toByte()))
        parser.endOfInput()
        parser.endOfInput()
        parser.accept("X".encodeToByteArray())
        assertCluster(terminal, 0, 0, ("a" + "\u0301".repeat(31)).codePoints().toArray(), "EOF")
        assertEquals(0xfffd, terminal.getCodepointAt(1, 0))
        assertEquals('X'.code, terminal.getCodepointAt(2, 0))
        assertEquals(3, terminal.cursorCol)
    }

    @ParameterizedTest
    @ValueSource(strings = ["\u001B[31m", "\u0018", "\u001A", "\u001B(B"])
    fun `overflow ends before structural controls and following text`(control: String) {
        val prefix = "a" + "\u0301".repeat(31)
        val bytes = (prefix + "\u0300".repeat(10) + control + "X").encodeToByteArray()
        for (split in 0..bytes.size) {
            val terminal = TerminalBuffers.create(4, 2)
            val parser = TerminalParsers.create(HostCommandAdapter(terminal))
            parser.accept(bytes, 0, split)
            parser.accept(bytes, split, bytes.size - split)
            parser.endOfInput()
            assertCluster(terminal, 0, 0, prefix.codePoints().toArray(), "control=$control split=$split")
            assertEquals('X'.code, terminal.getCodepointAt(1, 0))
            assertEquals(2, terminal.cursorCol)
            if (control == "\u001B[31m") assertEquals(CellColor.indexed(1), terminal.getAttrAt(1, 0)?.foreground)
        }
    }

    @Test
    fun `malformed UTF8 after overflow emits replacement and replays ESC structurally`() {
        val prefix = "a" + "\u0301".repeat(31)
        val bytes = (prefix + "\u0300".repeat(10)).encodeToByteArray() + byteArrayOf(0xc3.toByte()) + "\u001B[31mX".encodeToByteArray()
        for (split in 0..bytes.size) {
            val terminal = TerminalBuffers.create(5, 2)
            val parser = TerminalParsers.create(HostCommandAdapter(terminal))
            parser.accept(bytes, 0, split)
            parser.accept(bytes, split, bytes.size - split)
            assertCluster(terminal, 0, 0, prefix.codePoints().toArray(), "split=$split")
            assertEquals(0xfffd, terminal.getCodepointAt(1, 0))
            assertEquals('X'.code, terminal.getCodepointAt(2, 0))
            assertEquals(CellColor.indexed(1), terminal.getAttrAt(2, 0)?.foreground)
        }
    }

    @Test
    fun `EOF and parser reset terminate an overflowed grapheme without repeating it`() {
        for (reset in listOf(false, true)) {
            val terminal = TerminalBuffers.create(6, 2)
            val parser = TerminalParsers.create(HostCommandAdapter(terminal))
            parser.accept(("\uD83D\uDC69" + "\u0301".repeat(40) + "\u200D").encodeToByteArray())
            if (reset) parser.reset() else parser.endOfInput()
            parser.accept("\uD83D\uDC69X".encodeToByteArray())
            assertCluster(terminal, 0, 0, ("\uD83D\uDC69" + "\u0301".repeat(31)).codePoints().toArray(), "reset=$reset")
            assertEquals(0x1f469, terminal.getCodepointAt(2, 0))
            assertEquals('X'.code, terminal.getCodepointAt(4, 0))
        }
    }

    private fun assertCluster(
        terminal: TerminalBuffer,
        column: Int,
        row: Int,
        expected: IntArray,
        context: String,
    ) {
        val actual = IntArray(64)
        val length = terminal.getLine(row).readCluster(column, actual)
        assertEquals(expected.size, length, context)
        assertArrayEquals(expected, actual.copyOf(length), context)
    }
}
