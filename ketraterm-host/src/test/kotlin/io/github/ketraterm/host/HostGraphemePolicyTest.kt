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
import io.github.ketraterm.parser.api.TerminalParsers
import io.github.ketraterm.render.api.TerminalRenderFrameReader
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class HostGraphemePolicyTest {
    @ParameterizedTest
    @CsvSource("false,false", "false,true", "true,false", "true,true")
    fun `continuations of a rejected prefix never replace an earlier character`(
        clusterPrefix: Boolean,
        shrink: Boolean,
    ) {
        val prefix = "\uD83D\uDE00" + if (clusterPrefix) "\u0301" else ""
        val update = (if (shrink) "\uFE0E" else "\uFE0F").encodeToByteArray()
        assertAllByteSplits(update) { split ->
            val terminal = TerminalBuffers.create(4, 2)
            terminal.setAutoWrap(false)
            val parser = TerminalParsers.create(HostCommandAdapter(terminal))
            parser.accept(("abc" + prefix).encodeToByteArray())
            assertEquals("abc", terminal.getLineAsString(0))
            parser.accept(update, 0, split)
            parser.accept(update, split, update.size - split)
            assertEquals("abc", terminal.getLineAsString(0), "rejected prefix has no continuation target")
            assertEquals(3, terminal.cursorCol)
            assertEquals(0, terminal.cursorRow)
            parser.accept("X\u0301".encodeToByteArray())
            assertEquals("abcX\u0301", terminal.getLineAsString(0), "next grapheme starts normally")
        }
    }

    @ParameterizedTest
    @CsvSource("false,false", "false,true", "true,false", "true,true")
    fun `late width changes preserve committed overwrites and insert shifts`(
        shrink: Boolean,
        insert: Boolean,
    ) {
        val base = if (shrink) "\uD83D\uDE00" else "\u2764"
        val selector = if (shrink) "\uFE0E" else "\uFE0F"
        val update = selector.encodeToByteArray()
        assertAllByteSplits(update) { split ->
            val terminal = TerminalBuffers.create(6, 2)
            terminal.writeText("abcdef")
            terminal.positionCursor(1, 0)
            terminal.setInsertMode(insert)
            val parser = TerminalParsers.create(HostCommandAdapter(terminal))
            parser.accept(base.encodeToByteArray())
            assertEquals(base.codePointAt(0), terminal.getCodepointAt(1, 0), "prefix is immediately visible")
            parser.accept(update, 0, split)
            parser.accept(update, split, update.size - split)
            val expected =
                when {
                    shrink && insert -> "a${base + selector} bcd"
                    shrink -> "a${base + selector} def"
                    insert -> "a${base + selector}cde"
                    else -> "a${base + selector}def"
                }
            assertEquals(expected, terminal.getLineAsString(0))
            assertCluster(terminal, 1, 0, base + selector)
            assertEquals(if (shrink) 2 else 3, terminal.cursorCol)
            assertEquals(0, terminal.cursorRow)
            parser.accept("X".encodeToByteArray())
            val following =
                when {
                    shrink && insert -> "a${base + selector}X bc"
                    shrink -> "a${base + selector}Xdef"
                    insert -> "a${base + selector}Xcd"
                    else -> "a${base + selector}Xef"
                }
            assertEquals(following, terminal.getLineAsString(0))
        }
    }

    @ParameterizedTest
    @CsvSource("false,false", "false,true", "true,false", "true,true")
    fun `late narrowing leaves completed wrap and scroll committed`(
        alternate: Boolean,
        bottomRow: Boolean,
    ) {
        val update = "\uFE0EX".encodeToByteArray()
        for (history in listOf(0, 1, 3)) {
            assertAllByteSplits(update) { split ->
                val terminal = TerminalBuffers.create(4, 3, maxHistory = history)
                if (alternate) terminal.enterAltBuffer()
                terminal.writeText("KEEP")
                terminal.positionCursor(0, 1)
                terminal.writeText("MID")
                terminal.positionCursor(3, if (bottomRow) 2 else 0)
                val parser = TerminalParsers.create(HostCommandAdapter(terminal))
                parser.accept("\uD83D\uDE00".encodeToByteArray())
                val row = if (bottomRow) 2 else 1
                val retainedRows = (0..2).map { terminal.getLineAsString(it) }
                val historySize = terminal.historySize
                val discarded = discardedCount(terminal)
                parser.accept(update, 0, split)
                parser.accept(update, split, update.size - split)
                parser.endOfInput()
                assertCluster(terminal, 0, row, "\uD83D\uDE00\uFE0E")
                assertEquals('X'.code, terminal.getCodepointAt(1, row), "following text uses the released cell")
                assertEquals(2, terminal.cursorCol)
                assertEquals(row, terminal.cursorRow)
                assertEquals(historySize, terminal.historySize)
                assertEquals(discarded, discardedCount(terminal))
                for (other in 0..2) {
                    if (other != row) assertEquals(retainedRows[other], terminal.getLineAsString(other))
                }
            }
        }
    }

    @ParameterizedTest
    @CsvSource("1,0,0", "2,0,1", "4,0,3", "6,1,4")
    fun `late widening at right margin stays in place and following text obeys autowrap`(
        width: Int,
        left: Int,
        right: Int,
    ) {
        for (wrap in listOf(false, true)) {
            val update = "\uFE0F".encodeToByteArray()
            assertAllByteSplits(update) { split ->
                val terminal = TerminalBuffers.create(width, 3)
                if (left > 0) {
                    terminal.writeText("L....R")
                    terminal.setLeftRightMarginMode(true)
                    terminal.setLeftRightMargins(left + 1, right + 1)
                }
                terminal.setAutoWrap(wrap)
                terminal.positionCursor(right, 0)
                val parser = TerminalParsers.create(HostCommandAdapter(terminal))
                parser.accept("\u2764".encodeToByteArray())
                parser.accept(update, 0, split)
                parser.accept(update, split, update.size - split)
                assertCluster(terminal, right, 0, "\u2764\uFE0F")
                assertEquals(right, terminal.cursorCol)
                assertEquals(0, terminal.cursorRow)
                assertEquals(0, terminal.historySize)
                if (left > 0) {
                    assertEquals('L'.code, terminal.getCodepointAt(0, 0))
                    assertEquals('R'.code, terminal.getCodepointAt(width - 1, 0), "no spacer outside the margin")
                }
                parser.accept("X".encodeToByteArray())
                val xCol = if (wrap) left else right
                val xRow = if (wrap) 1 else 0
                assertEquals('X'.code, terminal.getCodepointAt(xCol, xRow))
                assertEquals(minOf(xCol + 1, right), terminal.cursorCol)
                assertEquals(xRow, terminal.cursorRow)
                if (wrap) assertCluster(terminal, right, 0, "\u2764\uFE0F")
            }
        }
    }

    @ParameterizedTest
    @CsvSource("1,1", "1,2", "2,1", "2,2", "3,1", "3,2")
    fun `narrowing on tiny grids does not undo eviction or a completed pending wrap`(
        width: Int,
        height: Int,
    ) {
        for (pendingWrap in listOf(false, true)) {
            for (history in 0..1) {
                val update = "\uFE0E".encodeToByteArray()
                assertAllByteSplits(update) { split ->
                    val terminal = TerminalBuffers.create(width, height, maxHistory = history)
                    val parser = TerminalParsers.create(HostCommandAdapter(terminal))
                    if (pendingWrap) parser.accept("A".repeat(width).encodeToByteArray())
                    parser.accept("\uD83D\uDE00".encodeToByteArray())
                    val row = terminal.cursorRow
                    val historySize = terminal.historySize
                    val discarded = discardedCount(terminal)
                    parser.accept(update, 0, split)
                    parser.accept(update, split, update.size - split)
                    assertCluster(terminal, 0, row, "\uD83D\uDE00\uFE0E")
                    assertEquals(minOf(1, width - 1), terminal.cursorCol)
                    assertEquals(row, terminal.cursorRow)
                    assertEquals(historySize, terminal.historySize)
                    assertEquals(discarded, discardedCount(terminal))
                    parser.accept("X".encodeToByteArray())
                    val xRow = if (width == 1) minOf(row + 1, height - 1) else row
                    assertEquals('X'.code, terminal.getCodepointAt(minOf(1, width - 1), xRow))
                }
            }
        }
    }

    private fun discardedCount(terminal: TerminalBuffer): Long {
        var count = -1L
        (terminal as TerminalRenderFrameReader).readRenderFrame(0, terminal.height) { count = it.discardedCount }
        return count
    }

    private fun assertCluster(
        terminal: TerminalBuffer,
        column: Int,
        row: Int,
        expected: String,
    ) {
        val codepoints = expected.codePoints().toArray()
        val actual = IntArray(32)
        val length = terminal.getLine(row).readCluster(column, actual)
        assertEquals(codepoints.size, length)
        assertArrayEquals(codepoints, actual.copyOf(length))
    }
}
