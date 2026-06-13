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
package io.github.jvterm.core.buffer

import io.github.jvterm.core.model.CellColor
import io.github.jvterm.render.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class BrutalDefaultTerminalBufferTest {
    @Test
    fun `6-1 API lifetime - frame is only valid inside callback`() {
        val buffer = DefaultTerminalBuffer(initialWidth = 10, initialHeight = 3)
        var leakedFrame: TerminalRenderFrame? = null

        (buffer as TerminalRenderFrameReader).readRenderFrame { frame ->
            leakedFrame = frame
            // Should be valid here
            assertEquals(10, frame.columns)
        }

        val frame = leakedFrame!!
        assertThrows<IllegalStateException> { frame.columns }
        assertThrows<IllegalStateException> { frame.rows }
        assertThrows<IllegalStateException> { frame.frameGeneration }
        assertThrows<IllegalStateException> { frame.structureGeneration }
        assertThrows<IllegalStateException> { frame.activeBuffer }
        assertThrows<IllegalStateException> { frame.cursor }
        assertThrows<IllegalStateException> { frame.lineGeneration(0) }
        assertThrows<IllegalStateException> { frame.lineWrapped(0) }
        assertThrows<IllegalStateException> {
            frame.copyLine(0, IntArray(10), 0, LongArray(10), 0, IntArray(10), 0)
        }
    }

    @Test
    fun `6-4 Generation - printing char changes frame and line generation`() {
        val buffer = DefaultTerminalBuffer(initialWidth = 10, initialHeight = 3)
        val reader = buffer as TerminalRenderFrameReader
        var frameGen0 = 0L
        var lineGen0 = 0L

        reader.readRenderFrame { frame ->
            frameGen0 = frame.frameGeneration
            lineGen0 = frame.lineGeneration(0)
        }

        buffer.writeCodepoint('A'.code)

        reader.readRenderFrame { frame ->
            assertNotEquals(frameGen0, frame.frameGeneration, "frameGeneration should change")
            assertNotEquals(lineGen0, frame.lineGeneration(0), "lineGeneration(0) should change")
            assertEquals(0L, frame.lineGeneration(1), "lineGeneration(1) should NOT change")
        }
    }

    @Test
    fun `6-4 Generation - cursor move changes frame and cursor generation, NOT line generation`() {
        val buffer = DefaultTerminalBuffer(initialWidth = 10, initialHeight = 3)
        val reader = buffer as TerminalRenderFrameReader
        var frameGen0 = 0L
        var cursorGen0 = 0L
        var lineGen0 = 0L

        reader.readRenderFrame { frame ->
            frameGen0 = frame.frameGeneration
            cursorGen0 = frame.cursor.generation
            lineGen0 = frame.lineGeneration(0)
        }

        buffer.cursorRight()

        reader.readRenderFrame { frame ->
            assertNotEquals(frameGen0, frame.frameGeneration, "frameGeneration should change")
            assertNotEquals(cursorGen0, frame.cursor.generation, "cursor.generation should change")
            assertEquals(lineGen0, frame.lineGeneration(0), "lineGeneration should NOT change")
        }
    }

    @Test
    fun `6-4 Generation - SGR alone changes no line generation`() {
        val buffer = DefaultTerminalBuffer(initialWidth = 10, initialHeight = 3)
        val reader = buffer as TerminalRenderFrameReader
        var lineGen0 = 0L

        reader.readRenderFrame { frame ->
            lineGen0 = frame.lineGeneration(0)
        }

        buffer.setPenAttributes(fg = 1, bg = 0)

        reader.readRenderFrame { frame ->
            assertEquals(lineGen0, frame.lineGeneration(0), "SGR alone should not change line generation")
        }
    }

    @Test
    fun `6-4 Generation - SGR plus print changes line generation`() {
        val buffer = DefaultTerminalBuffer(initialWidth = 10, initialHeight = 3)
        val reader = buffer as TerminalRenderFrameReader
        buffer.writeCodepoint('A'.code)

        var lineGen0 = 0L
        reader.readRenderFrame { frame ->
            lineGen0 = frame.lineGeneration(0)
        }

        buffer.setPenAttributes(bold = true, fg = 0, bg = 0)
        buffer.writeCodepoint('B'.code)

        reader.readRenderFrame { frame ->
            assertNotEquals(lineGen0, frame.lineGeneration(0), "SGR + print should change line generation")
        }
    }

    @Test
    fun `6-4 Generation - erase changes line generation`() {
        val buffer = DefaultTerminalBuffer(initialWidth = 10, initialHeight = 3)
        buffer.writeText("ABC")
        val reader = buffer as TerminalRenderFrameReader
        var lineGen0 = 0L

        reader.readRenderFrame { frame ->
            lineGen0 = frame.lineGeneration(0)
        }

        buffer.eraseLineToCursor()

        reader.readRenderFrame { frame ->
            assertNotEquals(lineGen0, frame.lineGeneration(0), "erase should change line generation")
        }
    }

    @Test
    fun `6-4 Generation - wrap flag change changes line generation`() {
        val buffer = DefaultTerminalBuffer(initialWidth = 2, initialHeight = 3)
        buffer.writeText("AB")
        val reader = buffer as TerminalRenderFrameReader
        var lineGen0 = 0L

        reader.readRenderFrame { frame ->
            lineGen0 = frame.lineGeneration(0)
            assertFalse(frame.lineWrapped(0))
        }

        // Write 'C' to trigger wrap on line 0
        buffer.writeCodepoint('C'.code)

        reader.readRenderFrame { frame ->
            assertTrue(frame.lineWrapped(0))
            assertNotEquals(lineGen0, frame.lineGeneration(0), "wrap flag change should change line generation")
        }
    }

    @Test
    fun `6-4 Generation - scroll changes structure generation`() {
        val buffer = DefaultTerminalBuffer(initialWidth = 10, initialHeight = 3)
        val reader = buffer as TerminalRenderFrameReader
        var structureGen0 = 0L

        reader.readRenderFrame { frame ->
            structureGen0 = frame.structureGeneration
        }

        buffer.scrollUp()

        reader.readRenderFrame { frame ->
            assertNotEquals(structureGen0, frame.structureGeneration, "scroll should change structure generation")
        }
    }

    @Test
    fun `6-4 Generation - resize changes structure generation`() {
        val buffer = DefaultTerminalBuffer(initialWidth = 10, initialHeight = 3)
        val reader = buffer as TerminalRenderFrameReader
        var structureGen0 = 0L

        reader.readRenderFrame { frame ->
            structureGen0 = frame.structureGeneration
        }

        buffer.resize(12, 4)

        reader.readRenderFrame { frame ->
            assertNotEquals(structureGen0, frame.structureGeneration, "resize should change structure generation")
        }
    }

    @Test
    fun `6-4 Generation - alt-screen switch changes structure generation`() {
        val buffer = DefaultTerminalBuffer(initialWidth = 10, initialHeight = 3)
        val reader = buffer as TerminalRenderFrameReader
        var structureGen0 = 0L

        reader.readRenderFrame { frame ->
            structureGen0 = frame.structureGeneration
        }

        buffer.enterAltBuffer()

        reader.readRenderFrame { frame ->
            assertNotEquals(structureGen0, frame.structureGeneration, "alt-screen switch should change structure generation")
        }
    }

    @Test
    fun `6-4 Generation - title change changes frame generation`() {
        val buffer = DefaultTerminalBuffer(initialWidth = 10, initialHeight = 3)
        val reader = buffer as TerminalRenderFrameReader
        var frameGen0 = 0L

        reader.readRenderFrame { frame ->
            frameGen0 = frame.frameGeneration
        }

        buffer.setWindowTitle("New Title")

        reader.readRenderFrame { frame ->
            assertNotEquals(frameGen0, frame.frameGeneration, "title change should change frame generation")
            frameGen0 = frame.frameGeneration
        }

        buffer.setIconTitle("New Icon")

        reader.readRenderFrame { frame ->
            assertNotEquals(frameGen0, frame.frameGeneration, "icon title change should change frame generation")
        }
    }

    @Test
    fun `6-2 Cell encoding - emoji cluster and combining cluster`() {
        val buffer = DefaultTerminalBuffer(initialWidth = 10, initialHeight = 1)
        // Emoji: Family (Man, Woman, Girl, Boy) - often wide
        val emoji = intArrayOf(0x1F468, 0x200D, 0x1F469, 0x200D, 0x1F467, 0x200D, 0x1F466)
        buffer.writeCluster(emoji)

        // Combining: 'e' + acute accent
        buffer.writeCluster(intArrayOf('e'.code, 0x0301))

        val reader = buffer as TerminalRenderFrameReader
        reader.readRenderFrame { frame ->
            val codeWords = IntArray(10)
            val flags = IntArray(10)
            val clusters = mutableMapOf<Int, String>()

            frame.copyLine(
                row = 0,
                codeWords = codeWords,
                flags = flags,
                attrWords = LongArray(10),
                clusterSink = TerminalRenderClusterSink { col, text -> clusters[col] = text },
            )

            // Emoji (usually wide, 2 cells)
            assertEquals(TerminalRenderCellFlags.CLUSTER or TerminalRenderCellFlags.WIDE_LEADING, flags[0])
            assertEquals(TerminalRenderCellFlags.WIDE_TRAILING, flags[1])
            assertEquals(String(emoji, 0, emoji.size), clusters[0])

            // Combining (1 cell)
            assertEquals(TerminalRenderCellFlags.CLUSTER, flags[2])
            assertEquals("e\u0301", clusters[2])
        }
    }

    @Test
    fun `6-1 API lifetime - no core cluster handle leaks through copied code words`() {
        val buffer = DefaultTerminalBuffer(initialWidth = 3, initialHeight = 1)
        // Write a cluster which will have a negative internal handle
        buffer.writeCluster(intArrayOf('e'.code, 0x0301), length = 2)
        val reader = buffer as TerminalRenderFrameReader

        reader.readRenderFrame { frame ->
            val codeWords = IntArray(3)
            frame.copyLine(
                row = 0,
                codeWords = codeWords,
                attrWords = LongArray(3),
                flags = IntArray(3),
            )

            // Cluster cell codeWord must be 0 in the public ABI, not the internal handle
            assertEquals(0, codeWords[0], "cluster cell codeWord must be 0")
            assertTrue(codeWords.all { it >= 0 }, "negative core handles must never escape render ABI")
        }
    }

    @Test
    fun `6-3 Attr - RGB fg and bg`() {
        val buffer = DefaultTerminalBuffer(initialWidth = 10, initialHeight = 1)
        buffer.setPenColors(
            foreground = CellColor.rgb(0xFF, 0x00, 0x00),
            background = CellColor.rgb(0x00, 0xFF, 0x00),
        )
        buffer.writeCodepoint('X'.code)

        val reader = buffer as TerminalRenderFrameReader
        reader.readRenderFrame { frame ->
            val attrWords = LongArray(10)
            frame.copyLine(
                row = 0,
                codeWords = IntArray(10),
                flags = IntArray(10),
                attrWords = attrWords,
            )

            val attr = attrWords[0]
            assertEquals(TerminalRenderColorKind.RGB, TerminalRenderAttrs.foregroundKind(attr))
            assertEquals(0xFF0000, TerminalRenderAttrs.foregroundValue(attr))
            assertEquals(TerminalRenderColorKind.RGB, TerminalRenderAttrs.backgroundKind(attr))
            assertEquals(0x00FF00, TerminalRenderAttrs.backgroundValue(attr))
        }
    }

    @Test
    fun `6-5 Row mapping - line generations stay attached to line content after ring rotation`() {
        val buffer = DefaultTerminalBuffer(initialWidth = 3, initialHeight = 2, maxHistory = 10)
        val reader = buffer as TerminalRenderFrameReader

        // Write line 0
        buffer.writeText("abc")
        // Move to line 1 and column 0
        buffer.positionCursor(0, 1)
        // Write line 1
        buffer.writeText("def")

        var row1GenerationBeforeScroll = -1L
        reader.readRenderFrame { frame ->
            val codeWords = IntArray(3)
            frame.copyLine(row = 1, codeWords = codeWords, attrWords = LongArray(3), flags = IntArray(3))
            assertEquals("def", codeWords.joinToString("") { if (it == 0) " " else String(Character.toChars(it)) })
            row1GenerationBeforeScroll = frame.lineGeneration(1)
        }

        // Trigger scroll by positioning cursor at (0, 1) and calling newLine()
        // Or just write another line
        buffer.positionCursor(0, 1)
        buffer.newLine()
        buffer.writeText("ghi")

        reader.readRenderFrame { frame ->
            val codeWords = IntArray(3)
            frame.copyLine(row = 0, codeWords = codeWords, attrWords = LongArray(3), flags = IntArray(3))
            // The old row 1 ("def") should now be at row 0
            assertEquals("def", codeWords.joinToString("") { if (it == 0) " " else String(Character.toChars(it)) })
            // Its generation should NOT have changed
            assertEquals(row1GenerationBeforeScroll, frame.lineGeneration(0), "generation should move with content")
        }
    }
}
