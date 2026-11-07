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
import io.github.ketraterm.render.api.TerminalRenderFrameReader
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TerminalEraseTest {
    @Test
    fun `ed3_clearsScrollbackButPreservesViewport_ifTargetingXtermSemantics`() {
        val buffer = TerminalBuffers.create(width = 3, height = 2, maxHistory = 4)
        buffer.writeText("abc")
        buffer.newLine()
        buffer.writeText("def")
        buffer.newLine()
        buffer.writeText("ghi")

        val screenBefore = buffer.getScreenAsString()

        buffer.eraseScreenAndHistory()

        assertAll(
            { assertEquals(screenBefore, buffer.getScreenAsString(), "ED 3 should preserve the visible viewport") },
            { assertEquals(0, buffer.historySize, "ED 3 should clear scrollback history") },
        )
    }

    @Test
    fun `ed3 preserves positive visible line ids while clearing history`() {
        val buffer = TerminalBuffers.create(width = 3, height = 2, maxHistory = 4)
        val reader = buffer as TerminalRenderFrameReader
        buffer.writeText("abc")
        buffer.newLine()
        buffer.writeText("def")
        buffer.newLine()
        buffer.writeText("ghi")

        val before = lineIds(reader)

        buffer.eraseScreenAndHistory()

        val after = lineIds(reader)
        assertAll(
            { assertEquals(before.asList(), after.asList()) },
            { assertTrue(after.all { it > 0L }, "ED 3 must not expose unassigned render line ids") },
        )
    }

    @Test
    fun `ed2 followed by ed3 leaves fresh positive visible line ids`() {
        val buffer = TerminalBuffers.create(width = 3, height = 2, maxHistory = 4)
        val reader = buffer as TerminalRenderFrameReader
        buffer.writeText("abc")
        buffer.newLine()
        buffer.writeText("def")

        val before = lineIds(reader)

        buffer.eraseEntireScreen()
        buffer.eraseScreenAndHistory()

        val after = lineIds(reader)
        assertAll(
            { assertTrue(after.all { it > 0L }, "clear paths must leave every render row with a positive line id") },
            { assertNotEquals(before.asList(), after.asList(), "ED 2 must replace the visible logical lines before ED 3 preserves them") },
        )
    }

    @Test
    fun `ed22 retains occupied rows including interior blanks but omits trailing empty rows`() {
        val buffer = TerminalBuffers.create(width = 4, height = 4, maxHistory = 8)
        buffer.writeText("old")
        buffer.newLine()
        buffer.writeText("A")
        buffer.positionCursor(0, 3)
        buffer.writeText("B")
        buffer.newLine()
        val before = lineIds(buffer as TerminalRenderFrameReader)
        val cursor = buffer.cursorCol to buffer.cursorRow

        buffer.moveScreenToHistory()

        assertEquals(4, buffer.historySize)
        assertEquals("old\n   A\n\nB\n\n\n\n", buffer.getAllAsString())
        assertEquals(cursor, buffer.cursorCol to buffer.cursorRow)
        assertTrue(lineIds(buffer).all { it > before.max() })
        buffer.moveScreenToHistory()
        assertEquals(4, buffer.historySize, "An empty clear must not evict history")
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = [0, 1, 2, 5])
    fun `ed22 respects history capacity and preserves wide clusters through row reuse`(maxHistory: Int) {
        val buffer = TerminalBuffers.create(width = 4, height = 2, maxHistory = maxHistory)
        val cluster = intArrayOf(0x1f469, 0x200d, 0x1f4bb)
        repeat(4) {
            buffer.positionCursor(0, 0)
            buffer.writeCluster(cluster)
            buffer.writeText("abX")
            buffer.moveScreenToHistory()
            assertEquals(minOf(maxHistory, (it + 1) * 2), buffer.historySize)
            assertEquals("\n", buffer.getScreenAsString())
            if (maxHistory >= 2) {
                (buffer as TerminalRenderFrameReader).readRenderFrame(2) { frame ->
                    val codes = IntArray(4)
                    val flags = IntArray(4)
                    var clusters = 0
                    frame.copyLine(
                        0,
                        codes,
                        attrWords = LongArray(4),
                        flags = flags,
                        clusterDataSink = { col, source, offset, length ->
                            assertEquals(0, col)
                            assertArrayEquals(cluster, source.copyOfRange(offset, offset + length))
                            clusters++
                        },
                    )
                    assertEquals(1, clusters)
                    assertTrue(frame.lineWrapped(0))
                    frame.copyLine(1, codes, attrWords = LongArray(4), flags = flags)
                    assertEquals('X'.code, codes[0])
                }
            }
        }
        if (maxHistory >= 2) {
            buffer.resize(8, 2)
            assertTrue(buffer.getAllAsString().contains("👩‍💻abX"))
        }
    }

    @Test
    fun `ed22 matches ed2 state effects and preserves primary on alternate`() {
        val buffer = TerminalBuffers.create(width = 4, height = 3, maxHistory = 8)
        buffer.writeText("keep")
        buffer.newLine()
        buffer.setScrollRegion(2, 3)
        buffer.positionCursor(3, 2)
        buffer.saveCursor()
        buffer.setSelectiveEraseProtection(true)
        buffer.writeText("X")
        buffer.moveScreenToHistory()
        assertEquals(0, buffer.historySize, "Partial-height regions only clear")
        assertEquals("\n\n", buffer.getScreenAsString())
        assertEquals(3 to 2, buffer.cursorCol to buffer.cursorRow)
        buffer.writeText("Y")
        assertEquals('Y'.code, buffer.getCodepointAt(3, 2), "ED 22 cancels pending wrap")
        buffer.restoreCursor()
        assertEquals(3 to 2, buffer.cursorCol to buffer.cursorRow)
        buffer.resetScrollRegion()
        buffer.moveScreenToHistory()
        val primary = buffer.getAllAsString()
        buffer.enterAltBuffer()
        buffer.writeText("alt")
        buffer.moveScreenToHistory()
        assertEquals(0, buffer.historySize)
        assertEquals("\n\n", buffer.getScreenAsString())
        buffer.exitAltBuffer()
        assertEquals(primary, buffer.getAllAsString())
    }

    private fun lineIds(reader: TerminalRenderFrameReader): LongArray {
        var ids = LongArray(0)
        reader.readRenderFrame { frame ->
            ids = LongArray(frame.rows)
            var row = 0
            while (row < frame.rows) {
                ids[row] = frame.lineId(row)
                row++
            }
        }
        return ids
    }
}
