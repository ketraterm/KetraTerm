package com.gagik.core.buffer

import com.gagik.core.TerminalBuffers
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
            { assertEquals(2, buffer.cursorCol) }
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
            intArrayOf(0x1F468, 0x200D, 0x1F469, 0x200D, 0x1F467, 0x200D, 0x1F466)
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
            { assertEquals(3, buffer.cursorCol) }
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
            { assertEquals(3, buffer.cursorCol) }
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
            { assertEquals(3, wide.cursorCol) }
        )
    }
}
