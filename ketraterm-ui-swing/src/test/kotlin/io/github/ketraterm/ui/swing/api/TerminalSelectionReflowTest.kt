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
package io.github.ketraterm.ui.swing.api

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.core.api.TerminalBuffer
import io.github.ketraterm.host.HostCommandAdapter
import io.github.ketraterm.parser.api.TerminalParsers
import io.github.ketraterm.render.api.TerminalRenderFrameReader
import io.github.ketraterm.render.cache.TerminalRenderCache
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class TerminalSelectionReflowTest {
    @ParameterizedTest
    @ValueSource(ints = [17, 31, 32, 33, 256])
    fun `copy preserves the parser retained grapheme prefix through wrapping and reflow`(length: Int) {
        for (base in listOf("a", "\uD83D\uDC69")) {
            val terminal = TerminalBuffers.create(width = 4, height = 4, maxHistory = 8)
            val parser = TerminalParsers.create(HostCommandAdapter(terminal))
            val input = "abc" + base + "\u0301".repeat(length - 1) + "X"
            val expected = "abc" + base + "\u0301".repeat(minOf(length, 32) - 1) + "X"
            for (byte in input.encodeToByteArray()) parser.acceptByte(byte.toInt() and 0xff)
            parser.endOfInput()
            assertEquals(expected, copyFirstLogicalLine(terminal))
            for (width in intArrayOf(2, 12, 4)) {
                terminal.resize(newWidth = width, newHeight = 4)
                assertEquals(expected, copyFirstLogicalLine(terminal), "length=$length width=$width")
            }
        }
    }

    @Test
    fun `uncertain scalars and repaired UTF16 survive selection and reflow`() {
        val input = "A\u0378\uD87F\uDFFD\uFDD0\uE000\uD800Z"
        val expected = "A\u0378\uD87F\uDFFD\uFDD0\uE000\uFFFDZ"
        for (wide in listOf(false, true)) {
            val terminal = TerminalBuffers.create(width = 3, height = 5, maxHistory = 8)
            terminal.setTreatAmbiguousAsWide(wide)
            terminal.writeText(input)
            assertEquals(expected, copyFirstLogicalLine(terminal), "Initial copy, ambiguous wide=$wide")
            for (width in intArrayOf(12, 2, 5, 3)) {
                terminal.resize(newWidth = width, newHeight = 5)
                assertEquals(expected, copyFirstLogicalLine(terminal), "Width=$width, ambiguous wide=$wide")
            }
        }
    }

    @Test
    fun `an erased middle wrapped row remains spaces across resize`() {
        val terminal = TerminalBuffers.create(width = 4, height = 4, maxHistory = 8)
        terminal.writeText("abcdEFGHijkl")
        terminal.positionCursor(col = 0, row = 1)
        terminal.eraseCharacters(4)

        val before = copyFirstLogicalLine(terminal)
        terminal.resize(newWidth = 16, newHeight = 4)
        val wider = copyFirstLogicalLine(terminal)
        terminal.resize(newWidth = 4, newHeight = 4)
        val restored = copyFirstLogicalLine(terminal)

        assertAll(
            { assertEquals("abcd    ijkl", before, "Erased cells still occupy the wrapped logical line") },
            { assertEquals("abcd    ijkl", wider, "After widening to width 16") },
            { assertEquals("abcd    ijkl", restored, "After restoring width 4") },
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["test", "中x"])
    fun `erasing a wrapped edge cell leaves a space before its continuation`(continuation: String) {
        val terminal = TerminalBuffers.create(width = 5, height = 4, maxHistory = 8)
        terminal.writeText("echoX$continuation")
        val before = copyFirstLogicalLine(terminal)

        terminal.positionCursor(col = 4, row = 0)
        terminal.eraseCharacters(1)
        val after = copyFirstLogicalLine(terminal)

        assertAll(
            { assertEquals("echoX$continuation", before) },
            { assertEquals("echo $continuation", after, "An erased occupied cell is not wide-character wrap padding") },
        )
    }

    @Test
    fun `copied word separators survive narrowing and widening a real buffer`() {
        val terminal = TerminalBuffers.create(width = 5, height = 4, maxHistory = 8)
        terminal.writeText("echo test")

        val before = copyFirstLogicalLine(terminal)
        terminal.resize(newWidth = 3, newHeight = 4)
        val narrower = copyFirstLogicalLine(terminal)
        terminal.resize(newWidth = 12, newHeight = 4)
        val wider = copyFirstLogicalLine(terminal)
        terminal.resize(newWidth = 5, newHeight = 4)
        val restored = copyFirstLogicalLine(terminal)

        assertAll(
            { assertEquals("echo test", before, "Before resize at width 5") },
            { assertEquals("echo test", narrower, "After narrowing to width 3") },
            { assertEquals("echo test", wider, "After widening to width 12") },
            { assertEquals("echo test", restored, "After restoring width 5") },
        )
    }

    @Test
    fun `copy never materializes wide character wrap padding after resize`() {
        val terminal = TerminalBuffers.create(width = 4, height = 4, maxHistory = 8)
        terminal.writeText("abc中x")

        val before = copyFirstLogicalLine(terminal)
        terminal.resize(newWidth = 8, newHeight = 4)
        val wider = copyFirstLogicalLine(terminal)
        terminal.resize(newWidth = 4, newHeight = 4)
        val restored = copyFirstLogicalLine(terminal)

        assertAll(
            { assertEquals("abc中x", before, "Before resize at width 4") },
            { assertEquals("abc中x", wider, "After widening to width 8") },
            { assertEquals("abc中x", restored, "After restoring width 4") },
        )
    }

    @Test
    fun `copy never materializes wide cluster wrap padding after resize`() {
        val terminal = TerminalBuffers.create(width = 4, height = 4, maxHistory = 8)
        terminal.writeText("abc")
        terminal.writeCluster(intArrayOf(0x1F469, 0x200D, 0x1F4BB))
        terminal.writeText("x")

        val before = copyFirstLogicalLine(terminal)
        terminal.resize(newWidth = 8, newHeight = 4)
        val wider = copyFirstLogicalLine(terminal)
        terminal.resize(newWidth = 4, newHeight = 4)
        val restored = copyFirstLogicalLine(terminal)

        assertAll(
            { assertEquals("abc👩‍💻x", before, "Before resize at width 4") },
            { assertEquals("abc👩‍💻x", wider, "After widening to width 8") },
            { assertEquals("abc👩‍💻x", restored, "After restoring width 4") },
        )
    }

    private fun copyFirstLogicalLine(terminal: TerminalBuffer): String {
        val cache = TerminalRenderCache(terminal.width, terminal.height)
        val reader = terminal as TerminalRenderFrameReader
        reader.readRenderFrame(
            scrollbackOffset = terminal.historySize,
            viewportRows = terminal.historySize + terminal.height,
            consumer = cache,
        )
        var lastRow = 0
        while (lastRow + 1 < cache.rows && cache.lineWrapped[lastRow]) lastRow++
        return TerminalSelectionTextExtractor().selectedText(
            cache,
            CellSelection(0, 0, cache.columns, lastRow),
            joinSoftWrappedRows = true,
        )
    }
}
