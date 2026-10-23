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
package io.github.ketraterm.core.graphics

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.core.api.TerminalRenderBuffer
import io.github.ketraterm.protocol.TerminalGraphicsCommand
import io.github.ketraterm.protocol.TerminalGraphicsResult
import io.github.ketraterm.render.api.TerminalRenderImage
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class GraphicsResizeTest {
    @Test
    fun `height resizing shifts anchors only by rows removed in this resize after history eviction`() {
        for (history in intArrayOf(0, 2, 20)) {
            for (scrolls in intArrayOf(0, 40)) {
                val terminal = terminal(10, 6, history)
                repeat(scrolls) { terminal.scrollUp(1) }
                for (row in 0 until 6) {
                    terminal.positionCursor(0, row)
                    terminal.writeCodepoint('A'.code + row)
                }
                place(terminal, 2, 2)
                val before = images(terminal).single()

                terminal.resize(10, 5, 0, 80, 80)

                assertEquals(
                    before.copy(row = 1.0, clipTop = 1.0, clipBottom = 3.0),
                    images(terminal).single(),
                    "history=$history scrolls=$scrolls",
                )
                assertEquals("B", terminal.getLineAsString(0).trimEnd())
                assertEquals("F", terminal.getLineAsString(4).trimEnd())
                // A second shrink must not reuse either eviction epoch's discarded-row count.
                terminal.resize(10, 4, 0, 80, 64)
                assertEquals(before.copy(row = 0.0, clipTop = 0.0, clipBottom = 2.0), images(terminal).single())
                assertEquals("C", terminal.getLineAsString(0).trimEnd())
            }
        }
    }

    @Test
    fun `growing and consuming trailing blanks preserve anchors after history eviction`() {
        val terminal = terminal(10, 6, 2)
        repeat(40) { terminal.scrollUp(1) }
        terminal.positionCursor(0, 0)
        terminal.writeCodepoint('A'.code)
        place(terminal, 2, 1)
        val before = images(terminal).single()

        terminal.resize(10, 8, 0, 80, 128)
        assertEquals(before, images(terminal).single())
        terminal.resize(10, 4, 0, 80, 64)
        assertEquals(before, images(terminal).single())
        assertEquals("A", terminal.getLineAsString(0).trimEnd())
    }

    @Test
    fun `height resize preserves history image pixels and anchors while primary is inactive`() {
        val terminal = terminal(10, 6, 4)
        repeat(40) { terminal.scrollUp(1) }
        place(terminal, 2, 0)
        repeat(3) { terminal.scrollUp(1) }
        for (row in 0 until 6) {
            terminal.positionCursor(0, row)
            terminal.writeCodepoint('A'.code + row)
        }
        val before = images(terminal, offset = 3).single()
        terminal.enterAltBuffer()

        terminal.resize(10, 5, 0, 80, 80)
        assertEquals(emptyList(), images(terminal))
        terminal.exitAltBuffer()

        assertEquals(before, images(terminal, offset = 4).single())
        assertEquals("B", terminal.getLineAsString(0).trimEnd())
    }

    @Test
    fun `width reflow moves text independently of ordinary image anchors and typing follows the cursor`() {
        val terminal = terminal(8, 8, 20)
        text(terminal, "abcdefghijklmnop")
        place(terminal, 2, 2)
        terminal.positionCursor(0, 4)
        text(terminal, "PS> ")
        val before = images(terminal).single()

        terminal.resize(16, 8, 0, 128, 128)

        assertEquals(before, images(terminal).single())
        assertEquals("abcdefghijklmnop", terminal.getLineAsString(0).trimEnd())
        assertEquals("PS> ", terminal.getLineAsString(3).take(4))
        assertEquals(4, terminal.cursorCol)
        assertEquals(3, terminal.cursorRow)
        text(terminal, "X")
        assertEquals("PS> X", terminal.getLineAsString(3).trimEnd())

        terminal.resize(8, 8, 0, 64, 128)
        assertEquals(before, images(terminal).single())
        assertEquals("abcdefgh", terminal.getLineAsString(0).trimEnd())
        assertEquals("ijklmnop", terminal.getLineAsString(1).trimEnd())
        assertEquals("PS> X", terminal.getLineAsString(4).trimEnd())
        assertEquals(5, terminal.cursorCol)
        assertEquals(4, terminal.cursorRow)
    }

    @Test
    fun `cell resizing recomputes geometry without decoding or replacing retained pixels`() {
        val terminal = terminal(10, 6, 2)
        place(terminal, 2, 1)
        val before = images(terminal).single()
        terminal.resize(10, 6, 0, 160, 192)
        val after = images(terminal).single()
        assertEquals(before, after) // Explicit two-by-two cell placements keep their cell span.
        assertSame(before.image, after.image)
    }

    private fun terminal(
        width: Int,
        height: Int,
        history: Int,
    ): TerminalRenderBuffer = TerminalBuffers.create(width, height, history).apply { resize(width, height, 0, width * 8, height * 16) }

    private fun text(
        terminal: TerminalRenderBuffer,
        text: String,
    ) {
        for (character in text) terminal.writeCodepoint(character.code)
    }

    private fun place(
        terminal: TerminalRenderBuffer,
        column: Int,
        row: Int,
    ) {
        terminal.positionCursor(column, row)
        val command =
            object : TerminalGraphicsCommand {
                override fun value(key: Char): Long =
                    when (key) {
                        'a' -> 'T'.code.toLong()
                        'i', 's', 'v', 'C' -> 1
                        'f' -> 24
                        'c', 'r' -> 2
                        else -> TerminalGraphicsCommand.OMITTED
                    }
            }
        terminal.applyGraphicsCommand(command, byteArrayOf(-1, 0, 0), 3, TerminalGraphicsResult.OK, false)
    }

    private fun images(
        terminal: TerminalRenderBuffer,
        offset: Int = 0,
    ): List<Placement> =
        buildList {
            terminal.readRenderFrame(offset) { frame ->
                frame.copyImages { image, column, row, width, height, _, _, _, _, top, bottom, _ ->
                    add(Placement(image, column, row, width, height, top, bottom))
                }
            }
        }

    private data class Placement(
        val image: TerminalRenderImage,
        val column: Double,
        val row: Double,
        val width: Double,
        val height: Double,
        val clipTop: Double,
        val clipBottom: Double,
    )
}
