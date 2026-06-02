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
package com.gagik.core.render

import com.gagik.core.buffer.TerminalBuffer
import com.gagik.core.model.AttributeColor
import com.gagik.core.model.UnderlineStyle
import com.gagik.terminal.render.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CoreTerminalRenderFrameTest {
    @Test
    fun `terminal buffer exposes render frame reader callback`() {
        val buffer = TerminalBuffer(initialWidth = 3, initialHeight = 2)
        val reader = buffer as TerminalRenderFrameReader

        reader.readRenderFrame { frame ->
            assertAll(
                { assertEquals(3, frame.columns) },
                { assertEquals(2, frame.rows) },
                { assertEquals(TerminalRenderBufferKind.PRIMARY, frame.activeBuffer) },
                { assertEquals(0, frame.cursor.column) },
                { assertEquals(0, frame.cursor.row) },
                { assertTrue(frame.cursor.visible) },
                { assertTrue(frame.cursor.blinking) },
                { assertEquals(TerminalRenderCursorShape.BLOCK, frame.cursor.shape) },
            )
        }
    }

    @Test
    fun `terminal render frame reflects mutated cursor shape`() {
        val buffer = TerminalBuffer(initialWidth = 3, initialHeight = 2)
        val reader = buffer as TerminalRenderFrameReader

        buffer.setCursorShape(TerminalRenderCursorShape.UNDERLINE)

        reader.readRenderFrame { frame ->
            assertEquals(TerminalRenderCursorShape.UNDERLINE, frame.cursor.shape)
        }

        var reportedShape: TerminalRenderCursorShape? = null
        reader.readRenderFrame { frame ->
            frame.copyCursor { _, _, _, _, shape, _ ->
                reportedShape = shape
            }
        }
        assertEquals(TerminalRenderCursorShape.UNDERLINE, reportedShape)
    }

    @Test
    fun `empty line copies empty flags and default attrs`() {
        val buffer = TerminalBuffer(initialWidth = 3, initialHeight = 1)
        val reader = buffer as TerminalRenderFrameReader

        reader.readRenderFrame { frame ->
            val row = copyRow(frame)

            assertAll(
                { assertEquals(listOf(0, 0, 0), row.codeWords.toList()) },
                {
                    assertEquals(
                        listOf(
                            TerminalRenderCellFlags.EMPTY,
                            TerminalRenderCellFlags.EMPTY,
                            TerminalRenderCellFlags.EMPTY,
                        ),
                        row.flags.toList(),
                    )
                },
                { assertTrue(row.attrWords.all { it == TerminalRenderAttrs.DEFAULT }) },
            )
        }
    }

    @Test
    fun `ascii cells copy as codepoints`() {
        val buffer = TerminalBuffer(initialWidth = 4, initialHeight = 1)
        buffer.writeText("AB")
        val reader = buffer as TerminalRenderFrameReader

        reader.readRenderFrame { frame ->
            val row = copyRow(frame)

            assertAll(
                { assertEquals('A'.code, row.codeWords[0]) },
                { assertEquals('B'.code, row.codeWords[1]) },
                { assertEquals(TerminalRenderCellFlags.CODEPOINT, row.flags[0]) },
                { assertEquals(TerminalRenderCellFlags.CODEPOINT, row.flags[1]) },
                { assertEquals(TerminalRenderCellFlags.EMPTY, row.flags[2]) },
            )
        }
    }

    @Test
    fun `wide codepoint copies leader and trailing flags`() {
        val buffer = TerminalBuffer(initialWidth = 4, initialHeight = 1)
        buffer.writeCodepoint(0x1F600)
        val reader = buffer as TerminalRenderFrameReader

        reader.readRenderFrame { frame ->
            val row = copyRow(frame)

            assertAll(
                {
                    assertEquals(
                        TerminalRenderCellFlags.CODEPOINT or TerminalRenderCellFlags.WIDE_LEADING,
                        row.flags[0],
                    )
                },
                { assertEquals(TerminalRenderCellFlags.WIDE_TRAILING, row.flags[1]) },
                { assertEquals(0x1F600, row.codeWords[0]) },
                { assertEquals(0, row.codeWords[1]) },
            )
        }
    }

    @Test
    fun `cluster cells call sink and copy stable cluster flags`() {
        val buffer = TerminalBuffer(initialWidth = 4, initialHeight = 1)
        buffer.writeCluster(intArrayOf('e'.code, 0x0301), length = 2)
        val reader = buffer as TerminalRenderFrameReader

        reader.readRenderFrame { frame ->
            val clusters = mutableMapOf<Int, String>()
            val row = copyRow(frame, clusterSink = { col, text -> clusters[col] = text })

            assertAll(
                { assertEquals(TerminalRenderCellFlags.CLUSTER, row.flags[0]) },
                { assertEquals(0, row.codeWords[0]) },
                { assertEquals("e\u0301", clusters[0]) },
            )
        }
    }

    @Test
    fun `cluster cells can copy primitive cluster data without text sink`() {
        val buffer = TerminalBuffer(initialWidth = 4, initialHeight = 1)
        buffer.writeCluster(intArrayOf('e'.code, 0x0301), length = 2)
        val reader = buffer as TerminalRenderFrameReader

        reader.readRenderFrame { frame ->
            val copiedCluster = IntArray(4)
            var copiedColumn = -1
            var copiedLength = 0
            val row =
                copyRow(
                    frame = frame,
                    clusterDataSink = { column, codepoints, offset, length ->
                        copiedColumn = column
                        copiedLength = length
                        System.arraycopy(codepoints, offset, copiedCluster, 0, length)
                    },
                )

            assertAll(
                { assertEquals(TerminalRenderCellFlags.CLUSTER, row.flags[0]) },
                { assertEquals(0, copiedColumn) },
                { assertEquals(2, copiedLength) },
                { assertEquals(listOf('e'.code, 0x0301), copiedCluster.copyOf(copiedLength).toList()) },
            )
        }
    }

    @Test
    fun `wide cluster copies leader and trailing flags`() {
        val buffer = TerminalBuffer(initialWidth = 4, initialHeight = 1)
        buffer.writeCluster(intArrayOf(0x1F468, 0x200D, 0x1F469), length = 3)
        val reader = buffer as TerminalRenderFrameReader

        reader.readRenderFrame { frame ->
            val clusters = mutableMapOf<Int, String>()
            val row = copyRow(frame, clusterSink = { col, text -> clusters[col] = text })

            assertAll(
                {
                    assertEquals(
                        TerminalRenderCellFlags.CLUSTER or TerminalRenderCellFlags.WIDE_LEADING,
                        row.flags[0],
                    )
                },
                { assertEquals(TerminalRenderCellFlags.WIDE_TRAILING, row.flags[1]) },
                { assertEquals(String(intArrayOf(0x1F468, 0x200D, 0x1F469), 0, 3), clusters[0]) },
            )
        }
    }

    @Test
    fun `cell attributes translate to public render ABI`() {
        val buffer = TerminalBuffer(initialWidth = 2, initialHeight = 1)
        buffer.setPenColors(
            foreground = AttributeColor.rgb(0x12_34_56),
            background = AttributeColor.indexed(42),
            underlineColor = AttributeColor.rgb(0x65_43_21),
            bold = true,
            faint = true,
            italic = true,
            underlineStyle = UnderlineStyle.DASHED,
            strikethrough = true,
            overline = true,
            blink = true,
            inverse = true,
            conceal = true,
        )
        buffer.writeCodepoint('X'.code)
        val reader = buffer as TerminalRenderFrameReader

        reader.readRenderFrame { frame ->
            val row = copyRow(frame)
            val attr = row.attrWords[0]
            val extra = row.extraAttrWords[0]

            assertAll(
                { assertEquals(TerminalRenderColorKind.RGB, TerminalRenderAttrs.foregroundKind(attr)) },
                { assertEquals(0x12_34_56, TerminalRenderAttrs.foregroundValue(attr)) },
                { assertEquals(TerminalRenderColorKind.INDEXED, TerminalRenderAttrs.backgroundKind(attr)) },
                { assertEquals(42, TerminalRenderAttrs.backgroundValue(attr)) },
                { assertTrue(TerminalRenderAttrs.isBold(attr)) },
                { assertTrue(TerminalRenderAttrs.isFaint(attr)) },
                { assertTrue(TerminalRenderAttrs.isItalic(attr)) },
                { assertEquals(TerminalRenderUnderline.DASHED, TerminalRenderAttrs.underlineStyle(attr)) },
                { assertTrue(TerminalRenderAttrs.isBlink(attr)) },
                { assertTrue(TerminalRenderAttrs.isInverse(attr)) },
                { assertTrue(TerminalRenderAttrs.isInvisible(attr)) },
                { assertTrue(TerminalRenderAttrs.isStrikethrough(attr)) },
                { assertEquals(TerminalRenderColorKind.RGB, TerminalRenderExtraAttrs.underlineColorKind(extra)) },
                { assertEquals(0x65_43_21, TerminalRenderExtraAttrs.underlineColorValue(extra)) },
                { assertTrue(TerminalRenderExtraAttrs.isOverline(extra)) },
            )
        }
    }

    @Test
    fun `reverse video is reflected in copied public attrs`() {
        val buffer = TerminalBuffer(initialWidth = 2, initialHeight = 1)
        buffer.writeCodepoint('X'.code)
        buffer.setReverseVideo(true)
        val reader = buffer as TerminalRenderFrameReader

        reader.readRenderFrame { frame ->
            val row = copyRow(frame)

            assertTrue(TerminalRenderAttrs.isInverse(row.attrWords[0]))
        }
    }

    @Test
    fun `hyperlink ids copy through optional array`() {
        val buffer = TerminalBuffer(initialWidth = 2, initialHeight = 1)
        buffer.setHyperlinkId(77)
        buffer.writeCodepoint('X'.code)
        val reader = buffer as TerminalRenderFrameReader

        reader.readRenderFrame { frame ->
            val row = copyRow(frame)

            assertEquals(77, row.hyperlinkIds[0])
        }
    }

    @Test
    fun `line metadata exposes generations and wrap flag`() {
        val buffer = TerminalBuffer(initialWidth = 2, initialHeight = 2)
        val reader = buffer as TerminalRenderFrameReader
        var oldFrame = 0L
        var oldLine = 0L

        reader.readRenderFrame { before ->
            oldFrame = before.frameGeneration
            oldLine = before.lineGeneration(0)
        }

        buffer.writeText("AB")
        buffer.writeCodepoint('C'.code)

        reader.readRenderFrame { after ->
            assertAll(
                { assertNotEquals(oldFrame, after.frameGeneration) },
                { assertNotEquals(oldLine, after.lineGeneration(0)) },
                { assertTrue(after.lineWrapped(0)) },
            )
        }
    }

    @Test
    fun `scrollback offset maps render rows without mutating live viewport`() {
        val buffer = TerminalBuffer(initialWidth = 4, initialHeight = 3, maxHistory = 5)
        val reader = buffer as TerminalRenderFrameReader
        repeat(6) { buffer.writeLogicalLine("L$it") }

        reader.readRenderFrame(scrollbackOffset = 2) { frame ->
            assertAll(
                { assertEquals(4, frame.historySize) },
                { assertEquals(2, frame.scrollbackOffset) },
                { assertFalse(frame.cursor.visible) },
                { assertEquals("L2", rowText(frame, 0)) },
                { assertEquals("L3", rowText(frame, 1)) },
                { assertEquals("L4", rowText(frame, 2)) },
            )
        }

        reader.readRenderFrame(scrollbackOffset = Int.MAX_VALUE) { frame ->
            assertAll(
                { assertEquals(frame.historySize, frame.scrollbackOffset) },
                { assertEquals("L0", rowText(frame, 0)) },
            )
        }

        reader.readRenderFrame { frame ->
            assertAll(
                { assertEquals(0, frame.scrollbackOffset) },
                { assertTrue(frame.cursor.visible) },
                { assertEquals("L4", rowText(frame, 0)) },
                { assertEquals("L5", rowText(frame, 1)) },
            )
        }
    }

    @Test
    fun `scrollback cursor row is translated into render viewport and clipped`() {
        val buffer = TerminalBuffer(initialWidth = 4, initialHeight = 3, maxHistory = 5)
        val reader = buffer as TerminalRenderFrameReader
        repeat(6) { buffer.writeLogicalLine("L$it") }
        buffer.positionCursor(col = 1, row = 0)

        reader.readRenderFrame(scrollbackOffset = 1) { frame ->
            assertAll(
                { assertEquals(1, frame.cursor.column) },
                { assertEquals(1, frame.cursor.row) },
                { assertTrue(frame.cursor.visible) },
            )
        }

        reader.readRenderFrame(scrollbackOffset = 3) { frame ->
            assertAll(
                { assertEquals(3, frame.cursor.row) },
                { assertFalse(frame.cursor.visible) },
            )
        }
    }

    @Test
    fun `scrollback render viewport can expose one overscan row`() {
        val buffer = TerminalBuffer(initialWidth = 4, initialHeight = 3, maxHistory = 5)
        val reader = buffer as TerminalRenderFrameReader
        repeat(6) { buffer.writeLogicalLine("L$it") }

        reader.readRenderFrame(scrollbackOffset = 1, viewportRows = 4) { frame ->
            assertAll(
                { assertEquals(4, frame.rows) },
                { assertEquals(1, frame.scrollbackOffset) },
                { assertEquals(3, frame.cursor.row) },
                { assertTrue(frame.cursor.visible) },
                { assertEquals("L3", rowText(frame, 0)) },
                { assertEquals("L4", rowText(frame, 1)) },
                { assertEquals("L5", rowText(frame, 2)) },
            )
        }

        reader.readRenderFrame(scrollbackOffset = 0, viewportRows = 4) { frame ->
            assertEquals(3, frame.rows)
        }
    }

    @Test
    fun `active buffer reports alternate after switch`() {
        val buffer = TerminalBuffer(initialWidth = 2, initialHeight = 1)
        val reader = buffer as TerminalRenderFrameReader

        buffer.enterAltBuffer()

        reader.readRenderFrame { frame ->
            assertEquals(TerminalRenderBufferKind.ALTERNATE, frame.activeBuffer)
        }
    }

    @Test
    fun `copyLine validates row and destination capacity`() {
        val buffer = TerminalBuffer(initialWidth = 2, initialHeight = 1)
        val reader = buffer as TerminalRenderFrameReader

        reader.readRenderFrame { frame ->
            assertAll(
                {
                    assertThrows(IllegalArgumentException::class.java) {
                        frame.lineGeneration(1)
                    }
                },
                {
                    assertThrows(IllegalArgumentException::class.java) {
                        frame.copyLine(
                            row = 0,
                            codeWords = IntArray(1),
                            attrWords = LongArray(2),
                            flags = IntArray(2),
                        )
                    }
                },
            )
        }
    }

    private fun copyRow(
        frame: TerminalRenderFrame,
        row: Int = 0,
        clusterSink: ((Int, String) -> Unit)? = null,
        clusterDataSink: ((Int, IntArray, Int, Int) -> Unit)? = null,
    ): CopiedRow {
        val copied = CopiedRow(frame.columns)
        frame.copyLine(
            row = row,
            codeWords = copied.codeWords,
            attrWords = copied.attrWords,
            flags = copied.flags,
            extraAttrWords = copied.extraAttrWords,
            hyperlinkIds = copied.hyperlinkIds,
            clusterSink =
                if (clusterSink == null) {
                    null
                } else {
                    TerminalRenderClusterSink(clusterSink)
                },
            clusterDataSink =
                if (clusterDataSink == null) {
                    null
                } else {
                    TerminalRenderClusterDataSink(clusterDataSink)
                },
        )
        copied.flags.forEach {
            assertTrue(TerminalRenderCellFlags.isValidCombination(it), "invalid flag combination: $it")
        }
        return copied
    }

    private fun rowText(
        frame: TerminalRenderFrame,
        row: Int,
    ): String =
        copyRow(frame, row)
            .codeWords
            .map { if (it == 0) ' ' else it.toChar() }
            .joinToString("")
            .trimEnd()

    private fun TerminalBuffer.writeLogicalLine(text: String) {
        writeText(text)
        carriageReturn()
        newLine()
    }

    private class CopiedRow(
        columns: Int,
    ) {
        val codeWords = IntArray(columns)
        val attrWords = LongArray(columns)
        val flags = IntArray(columns)
        val extraAttrWords = LongArray(columns)
        val hyperlinkIds = IntArray(columns)
    }
}
