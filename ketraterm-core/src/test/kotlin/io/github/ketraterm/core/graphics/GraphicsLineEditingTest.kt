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
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame

class GraphicsLineEditingTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `line edits preserve ordinary placements on both screens and with horizontal margins`(insert: Boolean) {
        for (alternate in listOf(false, true)) {
            for (horizontalMargins in listOf(false, true)) {
                for (count in intArrayOf(1, Int.MAX_VALUE)) {
                    val terminal = createTerminal(alternate)
                    place(terminal, id = 1, column = 3, row = 2)
                    place(terminal, id = 2, column = 3, row = 3)
                    place(terminal, id = 3, column = 0, row = 2)
                    place(terminal, id = 4, column = 3, row = 0)
                    setMargins(terminal, horizontalMargins)
                    terminal.positionCursor(3, 2)
                    terminal.writeCodepoint('A'.code)
                    terminal.positionCursor(3, 3)
                    terminal.writeCodepoint('B'.code)
                    terminal.positionCursor(3, 2)
                    val before = images(terminal)
                    val context = "insert=$insert alternate=$alternate horizontalMargins=$horizontalMargins count=$count"
                    assertEquals(listOf(2.0, 3.0, 2.0, 0.0), before.second.map { it.row }, context)

                    if (insert) terminal.insertLines(count) else terminal.deleteLines(count)

                    assertEquals(before, images(terminal), "Image identity, geometry and generation: $context")
                    val movedText =
                        when {
                            count > 1 -> ""
                            insert -> "   A"
                            else -> "   B"
                        }
                    assertEquals(movedText, terminal.getLineAsString(if (insert) 3 else 2).trimEnd(), context)
                    assertEquals("", terminal.getLineAsString(if (insert) 2 else 4).trimEnd(), context)
                    assertEquals(0, terminal.historySize, context)
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `actual scrolling still moves ordinary placements with the text`(up: Boolean) {
        for (alternate in listOf(false, true)) {
            for (horizontalMargins in listOf(false, true)) {
                val terminal = createTerminal(alternate)
                place(terminal, id = 1, column = 3, row = 2)
                setMargins(terminal, horizontalMargins)
                terminal.positionCursor(3, 2)
                terminal.writeCodepoint('A'.code)
                val before = images(terminal)

                if (up) terminal.scrollUp(1) else terminal.scrollDown(1)

                val after = images(terminal)
                val expectedRow = if (up) 1 else 3
                val context = "up=$up alternate=$alternate horizontalMargins=$horizontalMargins"
                assertSame(before.second.single().image, after.second.single().image, context)
                assertEquals(expectedRow.toDouble(), after.second.single().row, context)
                assertEquals("   A", terminal.getLineAsString(expectedRow).trimEnd(), context)
                assertNotEquals(before.first, after.first, context)
            }
        }
    }

    private fun createTerminal(alternate: Boolean): TerminalRenderBuffer =
        TerminalBuffers.create(10, 6).apply {
            resize(10, 6, 0, 80, 96)
            if (alternate) enterAltBuffer()
        }

    private fun setMargins(
        terminal: TerminalRenderBuffer,
        horizontal: Boolean,
    ) {
        terminal.setScrollRegion(2, 5)
        if (horizontal) {
            terminal.setLeftRightMarginMode(true)
            terminal.setLeftRightMargins(3, 8)
        }
    }

    private fun place(
        terminal: TerminalRenderBuffer,
        id: Long,
        column: Int,
        row: Int,
    ) {
        terminal.positionCursor(column, row)
        val command =
            object : TerminalGraphicsCommand {
                override fun value(key: Char): Long =
                    when (key) {
                        'a' -> 'T'.code.toLong()
                        'i' -> id
                        'f' -> 24
                        's', 'v', 'C' -> 1
                        'c', 'r' -> 2
                        else -> TerminalGraphicsCommand.OMITTED
                    }
            }
        terminal.applyGraphicsCommand(command, byteArrayOf(-1, 0, 0), 3, TerminalGraphicsResult.OK, false)
    }

    private fun images(terminal: TerminalRenderBuffer): Pair<Long, List<Placement>> {
        val placements = mutableListOf<Placement>()
        var generation = 0L
        terminal.readRenderFrame(0) { frame ->
            generation = frame.graphicsGeneration
            frame.copyImages { image, column, row, width, height, sx, sy, sw, sh, top, bottom, z ->
                placements += Placement(image, column, row, width, height, sx, sy, sw, sh, top, bottom, z)
            }
        }
        return generation to placements
    }

    private data class Placement(
        val image: TerminalRenderImage,
        val column: Double,
        val row: Double,
        val width: Double,
        val height: Double,
        val sourceX: Int,
        val sourceY: Int,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val clipTop: Double,
        val clipBottom: Double,
        val zIndex: Int,
    )
}
