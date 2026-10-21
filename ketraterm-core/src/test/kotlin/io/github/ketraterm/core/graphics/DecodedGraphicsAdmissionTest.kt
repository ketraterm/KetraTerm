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

class DecodedGraphicsAdmissionTest {
    @Test
    fun `decoded queries do not retain replace evict move cursor or change graphics generation`() {
        val terminal = terminal()
        val first = image(4096, 2048)
        val second = image(4096, 2048)
        display(terminal, 1, first)
        display(terminal, 2, second)
        terminal.positionCursor(3, 2)
        val before = snapshot(terminal)

        for (id in listOf(1L, 99L)) {
            terminal.applyDecodedGraphicsCommand(
                command('a' to 'q'.code.toLong(), 'i' to id),
                image(4096, 2048),
                TerminalGraphicsResult.OK,
                true,
            )
            assertEquals("\u001b_Gi=$id;OK\u001b\\", replies(terminal))
            assertEquals(before, snapshot(terminal))
            assertEquals(3, terminal.cursorCol)
            assertEquals(2, terminal.cursorRow)
        }

        place(terminal, 99)
        assertEquals("\u001b_Gi=99;ENOENT:image not found\u001b\\", replies(terminal))
        place(terminal, 1)
        assertEquals("\u001b_Gi=1;OK\u001b\\", replies(terminal))
        place(terminal, 2)
        assertEquals("\u001b_Gi=2;OK\u001b\\", replies(terminal))
        assertEquals(listOf(first, second), snapshot(terminal).images.distinct())
    }

    @Test
    fun `failed decoded replacement preserves retained identity and all placements`() {
        val terminal = terminal()
        val original = image(2, 3)
        display(terminal, 7, original)
        terminal.positionCursor(4, 3)
        place(terminal, 7, placement = 2)
        replies(terminal)
        val before = snapshot(terminal)
        for (status in listOf(TerminalGraphicsResult.INVALID, TerminalGraphicsResult.TOO_LARGE, TerminalGraphicsResult.UNSUPPORTED)) {
            terminal.applyDecodedGraphicsCommand(command('a' to 'T'.code.toLong(), 'i' to 7), null, status, true)
            assertEquals("\u001b_Gi=7;${status.reply}\u001b\\", replies(terminal))
            assertEquals(before, snapshot(terminal))
            assertEquals(4, terminal.cursorCol)
            assertEquals(3, terminal.cursorRow)
        }
        terminal.applyDecodedGraphicsCommand(
            command('a' to 'T'.code.toLong(), 'i' to 7, 'x' to 100),
            image(2, 3),
            TerminalGraphicsResult.OK,
            true,
        )
        assertEquals("\u001b_Gi=7;EINVAL:invalid graphics data\u001b\\", replies(terminal))
        assertEquals(before, snapshot(terminal))
        assertSame(original, snapshot(terminal).images.first())
    }

    @Test
    fun `decoded PNG dimensions come from the immutable image and commit without copying pixels`() {
        val terminal = terminal()
        val decoded = image(2, 3)
        terminal.positionCursor(1, 2)
        terminal.applyDecodedGraphicsCommand(
            command('a' to 'T'.code.toLong(), 'i' to 7, 's' to 9000, 'v' to 9000, 'C' to 1),
            decoded,
            TerminalGraphicsResult.OK,
            true,
        )
        assertEquals("\u001b_Gi=7;OK\u001b\\", replies(terminal))
        terminal.readRenderFrame(0) { frame ->
            var count = 0
            frame.copyImages { image, column, row, _, _, sourceX, sourceY, width, height, _, _, _ ->
                count++
                assertSame(decoded, image)
                assertEquals(1.0, column)
                assertEquals(2.0, row)
                assertEquals(0, sourceX)
                assertEquals(0, sourceY)
                assertEquals(2, width)
                assertEquals(3, height)
            }
            assertEquals(1, count)
        }
        assertEquals(1, terminal.cursorCol)
        assertEquals(2, terminal.cursorRow)
    }

    @Test
    fun `decoded boundary rejects absent or out of range images without replacing retained state`() {
        val terminal = terminal()
        display(terminal, 7, image(1, 1))
        val before = snapshot(terminal)
        for ((invalid, status) in listOf(
            null to TerminalGraphicsResult.INVALID,
            image(0, 1) to TerminalGraphicsResult.INVALID,
            image(1, -1) to TerminalGraphicsResult.INVALID,
            image(8193, 1) to TerminalGraphicsResult.TOO_LARGE,
            image(8192, 1025) to TerminalGraphicsResult.TOO_LARGE,
            image(Int.MAX_VALUE, Int.MAX_VALUE) to TerminalGraphicsResult.TOO_LARGE,
        )) {
            terminal.applyDecodedGraphicsCommand(command('a' to 'T'.code.toLong(), 'i' to 7), invalid, TerminalGraphicsResult.OK, true)
            assertEquals("\u001b_Gi=7;${status.reply}\u001b\\", replies(terminal))
            assertEquals(before, snapshot(terminal))
        }
    }

    @Test
    fun `decoded reply permission suppresses success and every failure while quiet modes retain semantics`() {
        val terminal = terminal()
        for (status in TerminalGraphicsResult.entries) {
            terminal.applyDecodedGraphicsCommand(command('a' to 'q'.code.toLong(), 'i' to 7), image(1, 1), status, false)
            assertEquals("", replies(terminal))
        }
        for (status in listOf(TerminalGraphicsResult.OK, TerminalGraphicsResult.INVALID)) {
            terminal.applyDecodedGraphicsCommand(command('a' to 'q'.code.toLong(), 'i' to 7, 'q' to 2), image(1, 1), status, true)
            assertEquals("", replies(terminal))
        }
        terminal.applyDecodedGraphicsCommand(
            command('a' to 'q'.code.toLong(), 'i' to 7, 'q' to 1),
            image(1, 1),
            TerminalGraphicsResult.OK,
            true,
        )
        assertEquals("", replies(terminal))
        terminal.applyDecodedGraphicsCommand(
            command('a' to 'q'.code.toLong(), 'i' to 7, 'q' to 1),
            null,
            TerminalGraphicsResult.INVALID,
            true,
        )
        assertEquals("\u001b_Gi=7;EINVAL:invalid graphics data\u001b\\", replies(terminal))
        for (controls in listOf(command(), command('a' to 'q'.code.toLong(), 'I' to 7))) {
            terminal.applyDecodedGraphicsCommand(controls, null, TerminalGraphicsResult.INVALID, true)
            assertEquals("", replies(terminal))
        }
    }

    private fun terminal(): TerminalRenderBuffer = TerminalBuffers.create(10, 6).apply { resize(10, 6, 0, 80, 96) }

    private fun display(
        terminal: TerminalRenderBuffer,
        id: Long,
        image: TerminalRenderImage,
    ) {
        terminal.applyDecodedGraphicsCommand(
            command('a' to 'T'.code.toLong(), 'i' to id, 'p' to 1, 'C' to 1, 'c' to 1, 'r' to 1),
            image,
            TerminalGraphicsResult.OK,
            true,
        )
        assertEquals("\u001b_Gi=$id,p=1;OK\u001b\\", replies(terminal))
    }

    private fun place(
        terminal: TerminalRenderBuffer,
        id: Long,
        placement: Long = 0,
    ) {
        terminal.applyGraphicsCommand(
            command('a' to 'p'.code.toLong(), 'i' to id, 'p' to placement, 'f' to 32, 'C' to 1, 'c' to 1, 'r' to 1),
            byteArrayOf(),
            0,
            TerminalGraphicsResult.OK,
            true,
        )
    }

    private fun replies(terminal: TerminalRenderBuffer): String {
        val bytes = ByteArray(1024)
        val length = terminal.readResponseBytes(bytes)
        return bytes.decodeToString(endIndex = length)
    }

    private fun command(vararg entries: Pair<Char, Long>): TerminalGraphicsCommand =
        object : TerminalGraphicsCommand {
            override fun value(key: Char): Long =
                entries.firstOrNull { it.first == key }?.second ?: if (key == 'f') 100 else TerminalGraphicsCommand.OMITTED
        }

    private fun image(
        width: Int,
        height: Int,
    ): TerminalRenderImage =
        object : TerminalRenderImage {
            override val width = width
            override val height = height

            override fun copyArgb(
                destination: IntArray,
                offset: Int,
            ): Unit = error("Core admission and frame publication must retain the immutable resource without copying pixels")
        }

    private fun snapshot(terminal: TerminalRenderBuffer): Snapshot {
        val images = mutableListOf<TerminalRenderImage>()
        val coordinates = mutableListOf<Pair<Double, Double>>()
        var generation = 0L
        terminal.readRenderFrame(0) { frame ->
            generation = frame.graphicsGeneration
            frame.copyImages { image, column, row, _, _, _, _, _, _, _, _, _ ->
                images += image
                coordinates += column to row
            }
        }
        return Snapshot(generation, images, coordinates)
    }

    private data class Snapshot(
        val generation: Long,
        val images: List<TerminalRenderImage>,
        val coordinates: List<Pair<Double, Double>>,
    )
}
