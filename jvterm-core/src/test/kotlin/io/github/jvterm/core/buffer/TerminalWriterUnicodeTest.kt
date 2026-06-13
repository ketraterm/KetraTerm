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

import io.github.jvterm.core.TerminalBuffers
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TerminalWriterUnicodeTest {
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
    fun `appendToPreviousCluster_mergesCombiningMarkWithoutMovingCursor`() {
        val buffer = TerminalBuffers.create(width = 6, height = 2)

        buffer.writeCodepoint('e'.code)
        buffer.appendToPreviousCluster(0x0301)
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
    fun `appendToPreviousCluster_preservesWideSpacerAndPendingWrap`() {
        val buffer = TerminalBuffers.create(width = 2, height = 2)

        buffer.writeCodepoint(0x1F600)
        buffer.appendToPreviousCluster(0xFE0F)

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
    fun `appendToPreviousCluster_textPresentationSelectorShrinksDefaultEmojiToOneCell`() {
        val buffer = TerminalBuffers.create(width = 6, height = 2)

        buffer.writeCodepoint(0x2615)
        buffer.appendToPreviousCluster(0xFE0E)
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
