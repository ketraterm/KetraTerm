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
package io.github.ketraterm.core.buffer

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.core.model.CellColor
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TerminalWriterUnicodeTest {
    @Test
    fun `full prefix updates preserve clusters beyond the parser retention limit`() {
        val buffer = TerminalBuffers.create(width = 6, height = 2)
        buffer.writeCodepoint('a'.code)
        val expected = IntArray(129) { if (it == 0) 'a'.code else 0x0301 }
        val actual = IntArray(expected.size)
        for (length in 2..expected.size) {
            buffer.updatePreviousCluster(expected, length)
            assertEquals(length, buffer.getLine(0).readCluster(0, actual))
            assertArrayEquals(expected.copyOf(length), actual.copyOf(length), "length=$length")
            assertEquals(1, buffer.cursorCol)
        }
    }

    @Test
    fun `invalid scalar ingress preserves wide cell and pending wrap atomically`() {
        for (invalid in intArrayOf(Int.MIN_VALUE, -2, -1, 0xD800, 0xDBFF, 0xDC00, 0xDFFF, 0x110000, Int.MAX_VALUE)) {
            val buffer = TerminalBuffers.create(width = 2, height = 2)
            assertThrows(IllegalArgumentException::class.java) { buffer.updatePreviousCluster(intArrayOf(0x2764, 0xFE0F, invalid)) }
            buffer.writeCluster(intArrayOf(0x2764, 0xFE0F))
            assertThrows(IllegalArgumentException::class.java) { buffer.writeCodepoint(invalid) }
            assertThrows(IllegalArgumentException::class.java) { buffer.updatePreviousCluster(intArrayOf(0x2764, 0xFE0F, invalid)) }
            for (cluster in listOf(intArrayOf(invalid), intArrayOf(invalid, 0xFE0E), intArrayOf('A'.code, 0xFE0E, invalid))) {
                assertThrows(IllegalArgumentException::class.java) { buffer.writeCluster(cluster) }
                assertThrows(IllegalArgumentException::class.java) { buffer.updatePreviousCluster(cluster) }
            }
            val stored = IntArray(4)
            assertEquals(2, buffer.getLine(0).readCluster(0, stored))
            assertArrayEquals(intArrayOf(0x2764, 0xFE0F, 0, 0), stored)
            assertEquals(-1, buffer.getCodepointAt(1, 0))
            assertEquals(0, buffer.cursorRow)
            assertEquals(1, buffer.cursorCol)
            assertEquals(0, buffer.getCodepointAt(0, 1))
            buffer.writeCodepoint('X'.code)
            assertEquals('X'.code, buffer.getCodepointAt(0, 1))
            assertEquals(1, buffer.cursorRow)
            assertEquals(1, buffer.cursorCol)
        }
    }

    @Test
    fun `cluster validation respects used prefix and rejects invalid lengths`() {
        val buffer = TerminalBuffers.create(width = 8, height = 2)
        val values = intArrayOf('e'.code, 0x0301, -1)
        for (length in intArrayOf(-1, 0, 4)) {
            assertThrows(IllegalArgumentException::class.java) { buffer.writeCluster(values, length) }
            assertThrows(IllegalArgumentException::class.java) { buffer.updatePreviousCluster(values, length) }
        }
        buffer.writeCluster(values, 2)
        buffer.updatePreviousCluster(values, 2)
        assertEquals(1, buffer.cursorCol)
        val stored = IntArray(2)
        assertEquals(2, buffer.getLine(0).readCluster(0, stored))
        assertArrayEquals(intArrayOf('e'.code, 0x0301), stored)
    }

    @Test
    fun `updates preserve original attributes and copy the borrowed prefix before returning`() {
        val buffer = TerminalBuffers.create(width = 8, height = 2)
        buffer.setPenColors(CellColor.indexed(1), CellColor.indexed(2), bold = true)
        buffer.setHyperlinkId(17)
        val prefix = intArrayOf('e'.code, 0x0301, 0x0300, -1)
        buffer.writeCluster(prefix, 2)
        val originalAttr = buffer.getLine(0).getPackedAttr(0)
        val originalExtendedAttr = buffer.getLine(0).getPackedExtendedAttr(0)
        buffer.setPenColors(CellColor.indexed(3), CellColor.DEFAULT)
        buffer.setHyperlinkId(99)
        buffer.updatePreviousCluster(prefix, 3)
        prefix.fill('X'.code)

        val actual = IntArray(3)
        assertEquals(3, buffer.getLine(0).readCluster(0, actual))
        assertArrayEquals(intArrayOf('e'.code, 0x0301, 0x0300), actual)
        assertEquals(originalAttr, buffer.getLine(0).getPackedAttr(0))
        assertEquals(originalExtendedAttr, buffer.getLine(0).getPackedExtendedAttr(0))
        assertEquals(1, buffer.cursorCol)
        buffer.writeCodepoint('Y'.code)
        assertEquals(CellColor.indexed(3), buffer.getAttrAt(1, 0)?.foreground)
    }

    @Test
    fun `updates with no target or after reset leave the grid empty`() {
        val buffer = TerminalBuffers.create(width = 6, height = 2)
        val cluster = intArrayOf('e'.code, 0x0301)
        buffer.updatePreviousCluster(cluster)
        assertEquals(0, buffer.getCodepointAt(0, 0))
        assertEquals(0, buffer.cursorCol)
        buffer.writeCluster(cluster)
        buffer.reset()
        buffer.updatePreviousCluster(cluster)
        assertEquals(0, buffer.getCodepointAt(0, 0))
        assertEquals(0, buffer.cursorCol)
        assertEquals(0, buffer.cursorRow)
    }

    @Test
    fun `unpaired UTF16 is replaced per code unit while valid pairs remain intact`() {
        for (wide in listOf(false, true)) {
            val buffer = TerminalBuffers.create(width = 20, height = 2)
            buffer.setTreatAmbiguousAsWide(wide)
            buffer.writeText("\uD800A\uDC00\uD800\uD83D\uDE00\uDFFF")
            val replacementWidth = if (wide) 2 else 1
            var col = 0
            for (cp in intArrayOf(0xFFFD, 'A'.code, 0xFFFD, 0xFFFD, 0x1F600, 0xFFFD)) {
                assertEquals(cp, buffer.getCodepointAt(col, 0))
                val width =
                    if (cp == 0x1F600) {
                        2
                    } else if (cp == 0xFFFD) {
                        replacementWidth
                    } else {
                        1
                    }
                if (width == 2) assertEquals(-1, buffer.getCodepointAt(col + 1, 0))
                col += width
            }
            assertEquals(col, buffer.cursorCol)
        }
    }

    @Test
    fun `writeCluster_combiningSequence_doesNotConsumeSecondCell`() {
        val buffer = TerminalBuffers.create(width = 6, height = 2)

        buffer.writeCluster(intArrayOf('e'.code, 0x0301))
        buffer.writeCodepoint('B'.code)

        val line = buffer.getLine(0)
        val clusterBuf = IntArray(4)
        val clusterLen = line.readCluster(0, clusterBuf)

        assertAll(
            { assertTrue(line.isCluster(0), "Base letter + combining mark must be stored as one cluster") },
            { assertEquals(2, clusterLen) },
            { assertEquals('e'.code, clusterBuf[0]) },
            { assertEquals(0x0301, clusterBuf[1]) },
            { assertEquals('B'.code, buffer.getCodepointAt(1, 0), "Next printable must land in the next cell") },
            { assertEquals(2, buffer.cursorCol) },
        )
    }

    @Test
    fun `updatePreviousCluster_mergesCombiningMarkWithoutMovingCursor`() {
        val buffer = TerminalBuffers.create(width = 6, height = 2)

        buffer.writeCodepoint('e'.code)
        buffer.updatePreviousCluster(intArrayOf('e'.code, 0x0301))
        buffer.writeCodepoint('X'.code)

        val line = buffer.getLine(0)
        val clusterBuf = IntArray(4)
        val clusterLen = line.readCluster(0, clusterBuf)

        assertAll(
            { assertTrue(line.isCluster(0), "Continuation must update the previous printable cell") },
            { assertEquals(2, clusterLen) },
            { assertEquals('e'.code, clusterBuf[0]) },
            { assertEquals(0x0301, clusterBuf[1]) },
            { assertEquals('X'.code, buffer.getCodepointAt(1, 0)) },
            { assertEquals(2, buffer.cursorCol) },
        )
    }

    @Test
    fun `updatePreviousCluster_preservesWideSpacerAndPendingWrap`() {
        val buffer = TerminalBuffers.create(width = 2, height = 2)

        buffer.writeCodepoint(0x1F600)
        buffer.updatePreviousCluster(intArrayOf(0x1F600, 0xFE0F))

        val line = buffer.getLine(0)
        val clusterBuf = IntArray(4)
        val clusterLen = line.readCluster(0, clusterBuf)

        assertAll(
            { assertTrue(line.isCluster(0)) },
            { assertEquals(2, clusterLen) },
            { assertEquals(0x1F600, clusterBuf[0]) },
            { assertEquals(0xFE0F, clusterBuf[1]) },
            { assertEquals(-1, buffer.getCodepointAt(1, 0)) },
            { assertEquals(1, buffer.cursorCol) },
        )
    }

    @Test
    fun `writeCluster_emojiZwjFamily_staysOneClusterAndOneVisualWidthSequence`() {
        val buffer = TerminalBuffers.create(width = 8, height = 2)

        buffer.writeCluster(
            intArrayOf(0x1F468, 0x200D, 0x1F469, 0x200D, 0x1F467, 0x200D, 0x1F466),
        )
        buffer.writeCodepoint('X'.code)

        val line = buffer.getLine(0)
        val clusterBuf = IntArray(8)
        val clusterLen = line.readCluster(0, clusterBuf)

        assertAll(
            { assertTrue(line.isCluster(0), "ZWJ emoji family must be stored as a single cluster leader") },
            { assertEquals(7, clusterLen) },
            { assertEquals(0x1F468, clusterBuf[0]) },
            { assertEquals(0x200D, clusterBuf[1]) },
            { assertEquals(0x1F469, clusterBuf[2]) },
            { assertEquals(0x200D, clusterBuf[3]) },
            { assertEquals(0x1F467, clusterBuf[4]) },
            { assertEquals(0x200D, clusterBuf[5]) },
            { assertEquals(0x1F466, clusterBuf[6]) },
            { assertEquals(-1, buffer.getCodepointAt(1, 0), "Wide cluster must reserve a spacer cell") },
            { assertEquals('X'.code, buffer.getCodepointAt(2, 0), "Next printable must start after the full visual sequence") },
            { assertEquals(3, buffer.cursorCol) },
        )
    }

    @Test
    fun `writeCluster_variationSelector_appendsToPreviousCellNotNextCell`() {
        val buffer = TerminalBuffers.create(width = 6, height = 2)

        buffer.writeCluster(intArrayOf(0x2764, 0xFE0F))
        buffer.writeCodepoint('X'.code)

        val line = buffer.getLine(0)
        val clusterBuf = IntArray(4)
        val clusterLen = line.readCluster(0, clusterBuf)

        assertAll(
            { assertTrue(line.isCluster(0), "Variation selector must merge into the previous cell") },
            { assertEquals(2, clusterLen) },
            { assertEquals(0x2764, clusterBuf[0]) },
            { assertEquals(0xFE0F, clusterBuf[1]) },
            { assertEquals(-1, buffer.getCodepointAt(1, 0), "Emoji-style heart cluster reserves its computed wide spacer") },
            { assertEquals('X'.code, buffer.getCodepointAt(2, 0)) },
            { assertEquals(3, buffer.cursorCol) },
        )
    }

    @Test
    fun `writeCluster_textPresentationSymbolStaysNarrowButEmojiVariationSequenceIsWide`() {
        val text = TerminalBuffers.create(width = 6, height = 2)
        val emoji = TerminalBuffers.create(width = 6, height = 2)

        text.writeCluster(intArrayOf(0x2615, 0xFE0E))
        text.writeCodepoint('X'.code)

        emoji.writeCluster(intArrayOf(0x2764, 0xFE0F))
        emoji.writeCodepoint('X'.code)

        assertAll(
            { assertEquals('X'.code, text.getCodepointAt(1, 0), "Text-presentation symbol must consume one cell") },
            { assertEquals(2, text.cursorCol) },
            { assertEquals(-1, emoji.getCodepointAt(1, 0), "Emoji variation sequence must reserve a spacer") },
            { assertEquals('X'.code, emoji.getCodepointAt(2, 0)) },
            { assertEquals(3, emoji.cursorCol) },
        )
    }

    @Test
    fun `updatePreviousCluster_textPresentationSelectorShrinksDefaultEmojiToOneCell`() {
        val buffer = TerminalBuffers.create(width = 6, height = 2)

        buffer.writeCodepoint(0x2615)
        buffer.updatePreviousCluster(intArrayOf(0x2615, 0xFE0E))
        buffer.writeCodepoint('X'.code)

        val line = buffer.getLine(0)
        val clusterBuf = IntArray(4)
        val clusterLen = line.readCluster(0, clusterBuf)

        assertAll(
            { assertEquals(2, clusterLen) },
            { assertEquals(0x2615, clusterBuf[0]) },
            { assertEquals(0xFE0E, clusterBuf[1]) },
            { assertEquals('X'.code, buffer.getCodepointAt(1, 0), "VS15 must free the old wide spacer") },
            { assertEquals(2, buffer.cursorCol) },
        )
    }

    @Test
    fun `symbolHeavyPasteLine_keepsEveryScalarInOneCellAndCursorAfterLastGlyph`() {
        val buffer = TerminalBuffers.create(width = 120, height = 2)
        val text = "∀∂∈ℝ∧∪≡∞ ↑↗↨↻⇣ ┐┼╔╘░►☺♀ ﬁ�⑀₂ἠḂӥẄɐː⍎אԱა"
        val expectedCodepoints = IntArray(text.codePointCount(0, text.length))
        var charIndex = 0
        var codepointIndex = 0
        while (charIndex < text.length) {
            val codepoint = text.codePointAt(charIndex)
            expectedCodepoints[codepointIndex++] = codepoint
            charIndex += Character.charCount(codepoint)
        }

        buffer.writeText(text)

        assertEquals(expectedCodepoints.size, buffer.cursorCol, "Cursor must land immediately after the pasted text")
        for (index in expectedCodepoints.indices) {
            assertEquals(expectedCodepoints[index], buffer.getCodepointAt(index, 0), "Codepoint at cell $index drifted")
        }
        assertEquals(0, buffer.getCodepointAt(expectedCodepoints.size, 0), "No wide spacer should follow the sample")
    }

    @Test
    fun `junieFooterTextFlagWidthDoesNotPushRedrawCursorOneRowLow`() {
        val buffer = TerminalBuffers.create(width = 100, height = 30)
        val footer = "  ~  \u2691 Brave off ctrl + b"

        buffer.positionCursor(col = 0, row = 20)
        repeat(4) {
            buffer.carriageReturn()
            buffer.newLine()
        }
        buffer.writeText(footer)
        repeat(100 - footer.length) {
            buffer.writeCodepoint(' '.code)
        }

        buffer.carriageReturn()
        buffer.cursorUp(4)
        buffer.cursorRight(6)
        buffer.writeCodepoint('l'.code)

        assertAll(
            { assertEquals('l'.code, buffer.getCodepointAt(6, 20)) },
            { assertEquals(20, buffer.cursorRow) },
            { assertEquals(7, buffer.cursorCol) },
        )
    }

    @Test
    fun `writeCluster_ambiguousWidthCluster_usesCoreModePolicy`() {
        val narrow = TerminalBuffers.create(width = 6, height = 2)
        val wide = TerminalBuffers.create(width = 6, height = 2)

        narrow.setTreatAmbiguousAsWide(false)
        wide.setTreatAmbiguousAsWide(true)

        narrow.writeCluster(intArrayOf(0x20AC, 0x0301))
        narrow.writeCodepoint('X'.code)

        wide.writeCluster(intArrayOf(0x20AC, 0x0301))
        wide.writeCodepoint('X'.code)

        assertAll(
            { assertEquals('X'.code, narrow.getCodepointAt(1, 0), "Narrow ambiguous mode keeps the next cell available") },
            { assertEquals(2, narrow.cursorCol) },
            { assertEquals(-1, wide.getCodepointAt(1, 0), "Wide ambiguous mode reserves a spacer cell") },
            { assertEquals('X'.code, wide.getCodepointAt(2, 0)) },
            { assertEquals(3, wide.cursorCol) },
        )
    }

    @Test
    fun `ambiguousWideMode_keepsTerminalCellGraphicsSingleWidth`() {
        val buffer = TerminalBuffers.create(width = 8, height = 2)

        buffer.setTreatAmbiguousAsWide(true)
        buffer.writeCodepoint(0x2500)
        buffer.writeCodepoint(0x2588)
        buffer.writeCodepoint(0x2591)
        buffer.writeCodepoint('X'.code)

        assertAll(
            { assertEquals(0x2500, buffer.getCodepointAt(0, 0), "Box drawing must remain one cell") },
            { assertEquals(0x2588, buffer.getCodepointAt(1, 0), "Block element must remain one cell") },
            { assertEquals(0x2591, buffer.getCodepointAt(2, 0), "Shade block must remain one cell") },
            { assertEquals('X'.code, buffer.getCodepointAt(3, 0)) },
            { assertEquals(4, buffer.cursorCol) },
        )
    }
}
