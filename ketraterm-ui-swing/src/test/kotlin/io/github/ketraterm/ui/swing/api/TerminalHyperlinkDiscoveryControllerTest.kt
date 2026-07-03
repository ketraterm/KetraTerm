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

import io.github.ketraterm.render.api.*
import io.github.ketraterm.render.cache.TerminalRenderCache
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TerminalHyperlinkDiscoveryControllerTest {
    @Test
    fun `snapshot joins soft-wrapped rows into one logical filter line`() {
        val cache = TerminalRenderCache(5, 2)
        writeText(cache, row = 0, text = "https")
        writeText(cache, row = 1, text = "://a ")
        cache.lineWrapped[0] = true

        val snapshot = TerminalHyperlinkViewportSnapshotBuilder().snapshot(cache)

        assertEquals(1, snapshot.request.lineCount)
        assertEquals("https://a\n", snapshot.request.lineText(0))
        assertEquals(10, snapshot.request.lineEndOffset(0))

        val overlay =
            snapshot.buildOverlay(
                listOf(TerminalDetectedHyperlink(0, 0, 9, SwingHyperlinkAction.NONE)),
            )

        assertNotNull(overlay)
        val ids = overlay!!.hyperlinkIds
        assertEquals(-1, ids[cache.rowOffset(0)])
        assertEquals(-1, ids[cache.rowOffset(0) + 4])
        assertEquals(-1, ids[cache.rowOffset(1)])
        assertEquals(-1, ids[cache.rowOffset(1) + 3])
        assertEquals(0, ids[cache.rowOffset(1) + 4])
    }

    @Test
    fun `discovered links do not overwrite terminal-authored OSC8 ids`() {
        val cache = TerminalRenderCache(4, 1)
        writeText(cache, row = 0, text = "abcd")
        cache.hyperlinkIds[cache.rowOffset(0) + 1] = 7

        val snapshot = TerminalHyperlinkViewportSnapshotBuilder().snapshot(cache)
        val overlay =
            snapshot.buildOverlay(
                listOf(TerminalDetectedHyperlink(0, 0, 4, SwingHyperlinkAction.NONE)),
            )

        assertNotNull(overlay)
        val ids = overlay!!.hyperlinkIds
        assertEquals(-1, ids[0])
        assertEquals(7, ids[1])
        assertEquals(-1, ids[2])
        assertEquals(-1, ids[3])
    }

    @Test
    fun `utf16 offsets map to full wide-cell spans`() {
        val cache = TerminalRenderCache(4, 1)
        writeCodePoint(cache, row = 0, column = 0, codePoint = 'A'.code)
        writeCodePoint(
            cache = cache,
            row = 0,
            column = 1,
            codePoint = 0x4E2D,
            flags = TerminalRenderCellFlags.CODEPOINT or TerminalRenderCellFlags.WIDE_LEADING,
        )
        cache.flags[2] = TerminalRenderCellFlags.WIDE_TRAILING
        writeCodePoint(cache, row = 0, column = 3, codePoint = 'B'.code)

        val snapshot = TerminalHyperlinkViewportSnapshotBuilder().snapshot(cache)

        assertEquals("A\u4E2DB\n", snapshot.request.lineText(0))

        val overlay =
            snapshot.buildOverlay(
                listOf(TerminalDetectedHyperlink(0, 1, 2, SwingHyperlinkAction.NONE)),
            )

        assertNotNull(overlay)
        val ids = overlay!!.hyperlinkIds
        assertEquals(0, ids[0])
        assertEquals(-1, ids[1])
        assertEquals(-1, ids[2])
        assertEquals(0, ids[3])
    }

    @Test
    fun `surrogate pair offsets map to the same terminal cell span`() {
        val cache = TerminalRenderCache(3, 1)
        writeCodePoint(
            cache = cache,
            row = 0,
            column = 0,
            codePoint = 0x1F600,
            flags = TerminalRenderCellFlags.CODEPOINT or TerminalRenderCellFlags.WIDE_LEADING,
        )
        cache.flags[1] = TerminalRenderCellFlags.WIDE_TRAILING
        writeCodePoint(cache, row = 0, column = 2, codePoint = '!'.code)

        val snapshot = TerminalHyperlinkViewportSnapshotBuilder().snapshot(cache)

        assertEquals("\uD83D\uDE00!\n", snapshot.request.lineText(0))

        val overlay =
            snapshot.buildOverlay(
                listOf(TerminalDetectedHyperlink(0, 0, 2, SwingHyperlinkAction.NONE)),
            )

        assertNotNull(overlay)
        val ids = overlay!!.hyperlinkIds
        assertEquals(-1, ids[0])
        assertEquals(-1, ids[1])
        assertEquals(0, ids[2])
    }

    @Test
    fun `combining cluster offsets map to the same terminal cell span`() {
        val cache = TerminalRenderCache(3, 1)
        cache.accept(CombiningClusterFrame())

        val snapshot = TerminalHyperlinkViewportSnapshotBuilder().snapshot(cache)

        assertEquals("e\u0301!\n", snapshot.request.lineText(0))

        val overlay =
            snapshot.buildOverlay(
                listOf(TerminalDetectedHyperlink(0, 1, 2, SwingHyperlinkAction.NONE)),
            )

        assertNotNull(overlay)
        val ids = overlay!!.hyperlinkIds
        assertEquals(-1, ids[0])
        assertEquals(0, ids[1])
        assertEquals(0, ids[2])
    }

    @Test
    fun `line separators do not map outside the viewport`() {
        val cache = TerminalRenderCache(2, 1)
        writeText(cache, row = 0, text = "a ")

        val snapshot = TerminalHyperlinkViewportSnapshotBuilder().snapshot(cache)

        assertEquals("a\n", snapshot.request.lineText(0))

        val overlay =
            snapshot.buildOverlay(
                listOf(TerminalDetectedHyperlink(0, 1, 2, SwingHyperlinkAction.NONE)),
            )

        assertNull(overlay)
    }

    private fun writeText(
        cache: TerminalRenderCache,
        row: Int,
        text: String,
    ) {
        var column = 0
        for (ch in text) {
            writeCodePoint(cache, row, column, ch.code)
            column++
        }
    }

    private fun writeCodePoint(
        cache: TerminalRenderCache,
        row: Int,
        column: Int,
        codePoint: Int,
        flags: Int = TerminalRenderCellFlags.CODEPOINT,
    ) {
        val index = cache.rowOffset(row) + column
        cache.flags[index] = flags
        cache.codeWords[index] = codePoint
    }

    private class CombiningClusterFrame : TerminalRenderFrame {
        override val columns: Int = 3
        override val rows: Int = 1
        override val frameGeneration: Long = 1L
        override val structureGeneration: Long = 1L
        override val activeBuffer: TerminalRenderBufferKind = TerminalRenderBufferKind.PRIMARY
        override val cursor: TerminalRenderCursor =
            TerminalRenderCursor(
                column = 0,
                row = 0,
                visible = false,
                blinking = false,
                shape = TerminalRenderCursorShape.BLOCK,
                generation = 0L,
            )

        override fun lineGeneration(row: Int): Long = 1L

        override fun lineWrapped(row: Int): Boolean = false

        override fun copyLine(
            row: Int,
            codeWords: IntArray,
            codeOffset: Int,
            attrWords: LongArray,
            attrOffset: Int,
            flags: IntArray,
            flagOffset: Int,
            extraAttrWords: LongArray?,
            extraAttrOffset: Int,
            hyperlinkIds: IntArray?,
            hyperlinkOffset: Int,
            clusterSink: TerminalRenderClusterSink?,
            clusterDataSink: TerminalRenderClusterDataSink?,
        ) {
            var column = 0
            while (column < columns) {
                codeWords[codeOffset + column] = 0
                attrWords[attrOffset + column] = TerminalRenderAttrs.DEFAULT
                flags[flagOffset + column] = 0
                extraAttrWords?.set(extraAttrOffset + column, TerminalRenderExtraAttrs.DEFAULT)
                hyperlinkIds?.set(hyperlinkOffset + column, 0)
                column++
            }

            flags[flagOffset] = TerminalRenderCellFlags.CLUSTER
            val clusterCodePoints = intArrayOf('e'.code, 0x0301)
            clusterDataSink?.onCluster(0, clusterCodePoints, 0, clusterCodePoints.size)

            codeWords[codeOffset + 1] = '!'.code
            flags[flagOffset + 1] = TerminalRenderCellFlags.CODEPOINT
        }
    }
}
