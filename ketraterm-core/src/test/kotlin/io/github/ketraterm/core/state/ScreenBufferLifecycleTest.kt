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
package io.github.ketraterm.core.state

import io.github.ketraterm.core.model.TerminalConstants
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class ScreenBufferLifecycleTest {
    @ParameterizedTest
    @ValueSource(ints = [5, 260])
    fun `scalar history destruction leaves spare cells and attributes for deferred initialization`(maxHistory: Int) {
        val buffer = populatedBuffer(maxHistory, extraPushes = 3)
        val history = List(maxHistory) { buffer.ring[it] }
        for (line in history) line.fill('Q'.code, 17L, 23L)
        val visible = List(3) { buffer.ring[maxHistory + it] }

        buffer.clearHistory(viewportHeight = 3)

        assertEquals(3, buffer.ring.size)
        visible.forEachIndexed { index, line -> assertSame(line, buffer.ring[index]) }
        for (line in history) {
            repeat(line.width) { column ->
                assertEquals('Q'.code, line.rawCodepoint(column))
                assertEquals(17L, line.getPackedAttr(column))
                assertEquals(23L, line.getPackedExtendedAttr(column))
            }
        }
    }

    @ParameterizedTest
    @CsvSource("5, 0", "5, 3", "260, 0", "260, 137")
    fun `grid destruction initializes only visible rows and defers spare row initialization`(
        maxHistory: Int,
        extraPushes: Int,
    ) {
        val buffer = populatedBuffer(maxHistory, extraPushes)
        val ring = buffer.ring
        val oldLines = List(ring.size) { ring[it] }
        val oldIds = oldLines.map { it.lineId }.toSet()
        val historyGeneration = buffer.historyContentGeneration

        buffer.clearGrid(31L, 47L, 3)

        assertSame(ring, buffer.ring)
        assertEquals(3, ring.size)
        assertEquals(0L, ring.discardedCount)
        assertEquals(historyGeneration + 1, buffer.historyContentGeneration)
        val visible = List(3) { ring[it] }
        for (line in visible) {
            repeat(line.width) { column ->
                assertEquals(TerminalConstants.EMPTY, line.rawCodepoint(column))
                assertEquals(31L, line.getPackedAttr(column))
                assertEquals(47L, line.getPackedExtendedAttr(column))
            }
            assertFalse(line.lineId in oldIds)
            assertFalse(line.wrapped)
            assertFalse(line.endsWithWrapPadding)
            assertFalse(line.hasOutput)
        }
        for (line in oldLines) {
            if (line in visible) continue
            assertEquals('A'.code, line.getCodepoint(0))
            assertEquals('e'.code, line.getCodepoint(2))
            assertEquals(17L, line.getPackedAttr(0))
            assertEquals(23L, line.getPackedExtendedAttr(0))
        }
    }

    @ParameterizedTest
    @CsvSource("5, 0", "5, 3", "260, 0", "260, 137")
    fun `height range retention discards both ends while preserving live row storage`(
        maxHistory: Int,
        extraPushes: Int,
    ) {
        val buffer = populatedBuffer(maxHistory, extraPushes)
        val ring = buffer.ring
        val oldSize = ring.size
        val retained = List(oldSize - 4) { ring[it + 2] }
        val dropped = listOf(ring[0], ring[1], ring[oldSize - 2], ring[oldSize - 1])
        val handles = retained.map { it.rawCodepoint(2) }
        val historyGeneration = buffer.historyContentGeneration

        buffer.resizeRows(newHeight = 2, fromLogical = 2, untilLogical = oldSize - 2)

        assertSame(ring, buffer.ring)
        assertEquals(maxHistory + 2, ring.capacity)
        assertEquals(retained.size, ring.size)
        assertEquals(2L, ring.discardedCount)
        assertEquals(historyGeneration + 1, buffer.historyContentGeneration)
        val clusterCopy = IntArray(2)
        retained.forEachIndexed { index, line ->
            val oldIndex = index + 2
            assertSame(line, ring[index])
            assertEquals(1000L + oldIndex, line.lineId)
            assertEquals(100L + oldIndex, line.renderGeneration)
            assertTrue(line.hasOutput)
            assertEquals(oldIndex % 2 == 0, line.wrapped)
            assertEquals(oldIndex % 2 == 0, line.endsWithWrapPadding)
            assertEquals('A'.code, line.rawCodepoint(0))
            assertEquals(handles[index], line.rawCodepoint(2))
            assertEquals('界'.code, line.rawCodepoint(3))
            assertEquals(TerminalConstants.WIDE_CHAR_SPACER, line.rawCodepoint(4))
            repeat(line.width) { column ->
                assertEquals(17L, line.getPackedAttr(column))
                assertEquals(23L, line.getPackedExtendedAttr(column))
            }
            assertEquals(2, line.readCluster(2, clusterCopy))
            assertArrayEquals(intArrayOf('e'.code, 0x0301), clusterCopy)
        }
        // Discarded rows are independent spares; clearing them cannot change retained payloads.
        dropped.forEach { it.clear(0L) }
        retained.forEach { line ->
            assertEquals(2, line.readCluster(2, clusterCopy))
            assertArrayEquals(intArrayOf('e'.code, 0x0301), clusterCopy)
        }
    }

    private fun populatedBuffer(
        maxHistory: Int,
        extraPushes: Int,
    ): ScreenBuffer {
        val buffer = ScreenBuffer(initialWidth = 6, initialHeight = 3, maxHistory = maxHistory)
        repeat(buffer.ring.capacity + extraPushes) { buffer.ring.push().clear(0L) }
        repeat(buffer.ring.size) { index ->
            val line = buffer.ring[index]
            line.assignLineId(1000L + index)
            line.fill(TerminalConstants.EMPTY, 17L, 23L)
            line.setCell(0, 'A'.code, 17L, 23L)
            line.setCluster(2, intArrayOf('e'.code, 0x0301), 2, 17L, 23L)
            line.setCell(3, '界'.code, 17L, 23L)
            line.setCell(4, TerminalConstants.WIDE_CHAR_SPACER, 17L, 23L)
            line.wrapped = index % 2 == 0
            line.endsWithWrapPadding = line.wrapped
            line.hasOutput = true
            line.renderGeneration = 100L + index
        }
        return buffer
    }

    @Test
    fun `clearing discarded history cannot invalidate the new viewport`() {
        val buffer = ScreenBuffer(initialWidth = 4, initialHeight = 2, maxHistory = 3)
        buffer.clearGrid(penAttr = 0, viewportHeight = 2)
        repeat(3) { buffer.ring.push().clear(0) }
        val backlogLine = buffer.ring[4]
        backlogLine.setCluster(0, intArrayOf('A'.code, 0x0301), 2, 0)
        buffer.clearGrid(penAttr = 0, viewportHeight = 2)
        buffer.ring[0].setCluster(0, intArrayOf('B'.code, 0x0301), 2, 0)
        backlogLine.clear(0)
        assertEquals('B'.code, buffer.ring[0].getCodepoint(0))
        val copied = IntArray(2)
        assertEquals(2, buffer.ring[0].readCluster(0, copied))
        assertArrayEquals(intArrayOf('B'.code, 0x0301), copied)
    }
}
