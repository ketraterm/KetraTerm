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
import io.github.ketraterm.render.api.TerminalRenderFrameReader
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class HostGraphemeTest {
    @Test
    fun `narrowing restores text overwritten by the provisional wide prefix`() {
        knownR06Failure(listOf("expected: <a\uD83D\uDE00\uFE0Ecdef> but was: <a\uD83D\uDE00\uFE0E def>")) {
            for (insert in listOf(false, true)) {
                val terminal = TerminalBuffers.create(6, 2)
                terminal.writeText("abcdef")
                terminal.positionCursor(1, 0)
                terminal.setInsertMode(insert)
                val parser = TerminalParsers.create(HostCommandAdapter(terminal))
                parser.accept("\uD83D\uDE00".encodeToByteArray())
                assertEquals(0x1f600, terminal.getCodepointAt(1, 0), "prefix is immediately visible")
                assertEquals(-1, terminal.getCodepointAt(2, 0))
                parser.accept("\uFE0E".encodeToByteArray())
                assertEquals(if (insert) "a\uD83D\uDE00\uFE0Ebcde" else "a\uD83D\uDE00\uFE0Ecdef", terminal.getLineAsString(0))
                assertEquals(2, terminal.cursorCol)
            }
        }
    }

    @ParameterizedTest
    @CsvSource("false,full", "true,full", "false,vertical", "true,vertical", "false,top", "true,top", "false,horizontal", "true,horizontal")
    fun `provisional width changes are independent of chunks with occupied cells and scrolling`(
        alternate: Boolean,
        margins: String,
    ) {
        val expected = "abcdef\nabcdef\nabcdef\nabcdef"
        val actual =
            when (margins) {
                "vertical", "top" -> "abcdef\nabcdef\nabcde\u2764\uFE0F\nabcdef"
                "horizontal" -> "abcdef\nabcdef\nabcdef\nabcd\u2764\uFE0Ff"
                else -> "abcdef\nabcdef\nabcdef\nabcde\u2764\uFE0F"
            }
        val context = "alt=$alternate history=0 margins=$margins insert=false wrap=false cluster=\u2764\uFE0F suffix= split=-1"
        knownR06Failure(listOf("$context ==> expected: <$expected> but was: <$actual>")) {
            for (history in listOf(0, 1, 4)) {
                for (insert in listOf(false, true)) {
                    for (wrap in listOf(false, true)) {
                        for (cluster in listOf("\u2764\uFE0F", "\uD83D\uDE00\uFE0E", "\u2764\uFE0F\uFE0E")) {
                            for (suffix in listOf("", "X", "\r\nY")) {
                                val bytes = (cluster + suffix).encodeToByteArray()

                                fun terminal(): TerminalBuffer =
                                    TerminalBuffers.create(6, 4, maxHistory = history).apply {
                                        if (alternate) enterAltBuffer()
                                        repeat(8) {
                                            writeCluster(intArrayOf('a'.code + it, 0x0301))
                                            writeText("BCDEF")
                                            newLine()
                                            carriageReturn()
                                        }
                                        for (row in 0..3) {
                                            positionCursor(0, row)
                                            writeText("abcdef")
                                        }
                                        if (margins == "vertical") setScrollRegion(2, 3)
                                        if (margins == "top") setScrollRegion(1, 3)
                                        if (margins == "horizontal") {
                                            setLeftRightMarginMode(true)
                                            setLeftRightMargins(2, 5)
                                        }
                                        setInsertMode(insert)
                                        setAutoWrap(wrap)
                                        positionCursor(
                                            if (margins ==
                                                "horizontal"
                                            ) {
                                                4
                                            } else {
                                                5
                                            },
                                            if (margins == "vertical" || margins == "top") 2 else 3,
                                        )
                                    }
                                val whole = terminal()
                                TerminalParsers.create(HostCommandAdapter(whole)).apply {
                                    accept(bytes)
                                    endOfInput()
                                }
                                for (split in -1..bytes.size) {
                                    val streamed = terminal()
                                    val parser = TerminalParsers.create(HostCommandAdapter(streamed))
                                    if (split < 0) {
                                        for (byte in bytes) parser.acceptByte(byte.toInt() and 0xff)
                                    } else {
                                        parser.accept(bytes, 0, split)
                                        parser.accept(bytes, split, bytes.size - split)
                                    }
                                    parser.endOfInput()
                                    assertSameGrid(
                                        whole,
                                        streamed,
                                        "alt=$alternate history=$history margins=$margins insert=$insert wrap=$wrap cluster=$cluster suffix=$suffix split=$split",
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun assertSameGrid(
        expected: TerminalBuffer,
        actual: TerminalBuffer,
        context: String,
    ) {
        assertEquals(expected.getAllAsString(), actual.getAllAsString(), context)
        assertEquals(expected.historySize, actual.historySize, context)
        assertEquals(expected.cursorCol, actual.cursorCol, context)
        assertEquals(expected.cursorRow, actual.cursorRow, context)
        (expected as TerminalRenderFrameReader).readRenderFrame(
            expected.historySize,
            expected.historySize + expected.height,
        ) { expectedFrame ->
            (actual as TerminalRenderFrameReader).readRenderFrame(actual.historySize, actual.historySize + actual.height) { actualFrame ->
                assertEquals(expectedFrame.discardedCount, actualFrame.discardedCount, context)
                for (row in 0 until expectedFrame.rows) {
                    assertEquals(expectedFrame.lineWrapped(row), actualFrame.lineWrapped(row), "$context wrapped row=$row")
                    val expectedCodes = IntArray(expected.width)
                    val actualCodes = IntArray(actual.width)
                    val expectedAttrs = LongArray(expected.width)
                    val actualAttrs = LongArray(actual.width)
                    val expectedFlags = IntArray(expected.width)
                    val actualFlags = IntArray(actual.width)
                    expectedFrame.copyLine(row, expectedCodes, attrWords = expectedAttrs, flags = expectedFlags)
                    actualFrame.copyLine(row, actualCodes, attrWords = actualAttrs, flags = actualFlags)
                    assertArrayEquals(expectedCodes, actualCodes, context)
                    assertArrayEquals(expectedAttrs, actualAttrs, context)
                    assertArrayEquals(expectedFlags, actualFlags, context)
                }
            }
        }
        val expectedCluster = IntArray(32)
        val actualCluster = IntArray(32)
        for (row in 0 until expected.height) {
            for (col in 0 until expected.width) {
                assertEquals(expected.getCodepointAt(col, row), actual.getCodepointAt(col, row), "$context ($col,$row)")
                assertEquals(expected.getAttrAt(col, row), actual.getAttrAt(col, row), "$context ($col,$row)")
                val length = expected.getLine(row).readCluster(col, expectedCluster)
                assertEquals(length, actual.getLine(row).readCluster(col, actualCluster), context)
                assertArrayEquals(expectedCluster.copyOf(length), actualCluster.copyOf(length), context)
            }
        }
    }

    @Test
    fun `tiny grids and already pending wraps retain chunk equivalence`() {
        val context = "size=1,1 history=0 prefix= text=\uD83D\uDE00\uFE0E suffix= split=4"
        knownR06Failure(listOf("$context ==> expected: <0> but was: <1>")) {
            for (width in 1..3) {
                for (height in 1..2) {
                    for (history in 0..1) {
                        for (prefix in listOf("", "A".repeat(width))) {
                            for (text in listOf("\uD83D\uDE00\uFE0E", "\u2764\uFE0F\uFE0E", "\u4E00\uFE0E")) {
                                for (suffix in listOf("", "X", "\u001B[32mY", "\u0018X")) {
                                    val bytes = (prefix + text + suffix).encodeToByteArray()
                                    val whole = TerminalBuffers.create(width, height, maxHistory = history)
                                    TerminalParsers.create(HostCommandAdapter(whole)).apply {
                                        accept(bytes)
                                        endOfInput()
                                    }
                                    for (split in 0..bytes.size) {
                                        val actual = TerminalBuffers.create(width, height, maxHistory = history)
                                        TerminalParsers.create(HostCommandAdapter(actual)).apply {
                                            accept(bytes, 0, split)
                                            accept(bytes, split, bytes.size - split)
                                            endOfInput()
                                        }
                                        assertSameGrid(
                                            whole,
                                            actual,
                                            "size=$width,$height history=$history prefix=$prefix text=$text suffix=$suffix split=$split",
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @ParameterizedTest
    @CsvSource(
        "false,false,false",
        "false,false,true",
        "false,true,false",
        "false,true,true",
        "true,false,false",
        "true,false,true",
        "true,true,false",
        "true,true,true",
    )
    fun `variation selectors preserve right margin placement across every byte split`(
        shrink: Boolean,
        alternate: Boolean,
        bottomRow: Boolean,
    ) {
        val cluster = if (shrink) "\uD83D\uDE00\uFE0E" else "\u2764\uFE0F"
        val column = if (shrink) 3 else 2
        val bytes = (cluster + "X").encodeToByteArray()

        fun terminal(): TerminalBuffer =
            TerminalBuffers.create(4, 3, maxHistory = 3).apply {
                if (alternate) enterAltBuffer()
                writeText("KEEP")
                positionCursor(0, 1)
                writeText("MID")
                positionCursor(column, if (bottomRow) 2 else 0)
            }

        val whole = terminal()
        TerminalParsers.create(HostCommandAdapter(whole)).apply {
            accept(bytes)
            endOfInput()
        }
        val clusterRow = if (bottomRow) 1 else 0
        val followingRow = if (bottomRow) 2 else 1
        assertCluster(whole, column, clusterRow, cluster.codePoints().toArray(), "whole shrink=$shrink bottom=$bottomRow")
        assertEquals('X'.code, whole.getCodepointAt(0, followingRow))
        assertEquals(if (bottomRow && !alternate) 1 else 0, whole.historySize)
        assertEquals(followingRow, whole.cursorRow)
        assertEquals(1, whole.cursorCol)

        fun verifySplits() {
            assertAllByteSplits(bytes) { split ->
                val splitTerminal = terminal()
                val parser = TerminalParsers.create(HostCommandAdapter(splitTerminal))
                parser.accept(bytes, 0, split)
                parser.accept(bytes, split, bytes.size - split)
                parser.endOfInput()
                val context = "shrink=$shrink alternate=$alternate bottom=$bottomRow split=$split"

                assertEquals(whole.getAllAsString(), splitTerminal.getAllAsString(), context)
                assertEquals(whole.historySize, splitTerminal.historySize, context)
                assertEquals(whole.cursorRow, splitTerminal.cursorRow, context)
                assertEquals(whole.cursorCol, splitTerminal.cursorCol, context)
                assertCluster(splitTerminal, column, clusterRow, cluster.codePoints().toArray(), context)
                for (row in 0 until whole.height) {
                    for (col in 0 until whole.width) {
                        assertEquals(whole.getCodepointAt(col, row), splitTerminal.getCodepointAt(col, row), "$context cell=($col,$row)")
                    }
                }
            }
        }
        if (shrink) {
            val expected =
                when {
                    !bottomRow -> "KEE\uD83D\uDE00\uFE0E\nXID\n"
                    alternate -> "MID\n   \uD83D\uDE00\uFE0E\nX"
                    else -> "KEEP\nMID\n   \uD83D\uDE00\uFE0E\nX"
                }
            val actual =
                when {
                    !bottomRow -> "KEE\n\uD83D\uDE00\uFE0EXD\n"
                    alternate -> "MID\n\n\uD83D\uDE00\uFE0EX"
                    else -> "KEEP\nMID\n\n\uD83D\uDE00\uFE0EX"
                }
            knownR06Failure(
                (4..6).map { split ->
                    "shrink=true alternate=$alternate bottom=$bottomRow split=$split ==> expected: <$expected> but was: <$actual>"
                },
                ::verifySplits,
            )
        } else {
            verifySplits()
        }
    }

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
