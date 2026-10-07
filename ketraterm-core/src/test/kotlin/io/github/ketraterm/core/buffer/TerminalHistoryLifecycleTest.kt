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

import io.github.ketraterm.core.engine.MutationEngine
import io.github.ketraterm.core.model.TerminalConstants
import io.github.ketraterm.core.state.ScreenBuffer
import io.github.ketraterm.core.state.TerminalState
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class TerminalHistoryLifecycleTest {
    @ParameterizedTest
    @CsvSource("0, 0", "0, 20", "2, 3", "129, 1", "259, 520", "1000, 260")
    fun `ED3 reuses its arena and visible rows while freeing only history clusters`(
        maxHistory: Int,
        scrolls: Int,
    ) {
        val state = TerminalState(initialWidth = 5, initialHeight = 3, maxHistory = maxHistory)
        val mutation = MutationEngine(state)
        repeat(scrolls) { mutation.scrollUp() }
        val ring = state.ring
        val store = state.activeBuffer.store
        val historySize = state.historySize
        val history = List(historySize) { ring[it] }
        val visible = List(3) { ring[historySize + it] }
        val historyCluster = intArrayOf('A'.code, 0x0301)
        for (line in history) line.setCluster(0, historyCluster, 2, 17L, 23L)
        val reusableHandle = history.lastOrNull()?.rawCodepoint(0)
        val visibleCluster = intArrayOf('e'.code, 0x0301)
        for (line in visible) {
            line.setCell(0, '界'.code, 31L, 47L)
            line.setRawCell(1, TerminalConstants.WIDE_CHAR_SPACER, 31L, 47L)
            line.setCluster(2, visibleCluster, 2, 31L, 47L)
            line.wrapped = true
            line.endsWithWrapPadding = true
            line.hasOutput = true
        }
        val handles = visible.map { it.rawCodepoint(2) }
        val lineIds = visible.map { it.lineId }
        state.cursor.col = 3
        state.cursor.row = 1
        val clusterCopy = IntArray(2)

        repeat(2) {
            val historyGeneration = state.activeBuffer.historyContentGeneration
            val structureGeneration = state.structureGeneration
            mutation.eraseScreenAndHistory()
            assertSame(ring, state.ring)
            assertSame(store, state.activeBuffer.store)
            assertEquals(0, state.historySize)
            assertEquals(0L, ring.discardedCount)
            assertNotEquals(historyGeneration, state.activeBuffer.historyContentGeneration)
            assertNotEquals(structureGeneration, state.structureGeneration)
            assertEquals(3, state.cursor.col)
            assertEquals(1, state.cursor.row)
            visible.forEachIndexed { index, line ->
                assertSame(line, ring[index])
                assertEquals(lineIds[index], line.lineId)
                assertEquals(handles[index], line.rawCodepoint(2))
                assertEquals('界'.code, line.rawCodepoint(0))
                assertEquals(TerminalConstants.WIDE_CHAR_SPACER, line.rawCodepoint(1))
                assertEquals(2, line.readCluster(2, clusterCopy))
                assertArrayEquals(visibleCluster, clusterCopy)
                assertEquals(31L, line.getPackedAttr(2))
                assertEquals(47L, line.getPackedExtendedAttr(2))
                assertEquals(true, line.wrapped)
                assertEquals(true, line.endsWithWrapPadding)
                assertEquals(true, line.hasOutput)
            }
        }

        for (line in history) {
            for (column in 0 until line.width) {
                assertEquals(0, line.rawCodepoint(column))
                assertEquals(0L, line.getPackedAttr(column))
                assertEquals(0L, line.getPackedExtendedAttr(column))
            }
        }
        if (reusableHandle != null) {
            visible[0].setCluster(3, historyCluster, 2, 0L)
            assertEquals(reusableHandle, visible[0].rawCodepoint(3))
        }
        // Refilling and then clearing exercises each reused row's cluster lifecycle again.
        repeat(scrolls + 3) { mutation.scrollUp() }
        mutation.clearAllHistory()
        assertSame(ring, state.ring)
        assertSame(store, state.activeBuffer.store)
        assertEquals(0, state.historySize)
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 127, 128, 129, 259, 1000])
    fun `batched history preserves Unicode through eviction resize alternate and clearing`(maxHistory: Int) {
        val buffer = DefaultTerminalBuffer(initialWidth = 16, initialHeight = 4, maxHistory = maxHistory)
        val cluster = intArrayOf('e'.code, 0x0301)
        val expectedHistory = minOf(maxHistory, 526)
        val expected = (530 - expectedHistory - 4 until 530).joinToString("\n") { "$it 界e\u0301" }

        repeat(2) {
            buffer.positionCursor(0, 0)
            repeat(530) { row ->
                if (row > 0) {
                    buffer.carriageReturn()
                    buffer.newLine()
                }
                buffer.writeText("$row ")
                buffer.writeCodepoint('界'.code)
                buffer.writeCluster(cluster)
            }
            assertEquals(expectedHistory, buffer.historySize)
            assertEquals(expected, buffer.getAllAsString())

            buffer.enterAltBuffer()
            repeat(300) {
                buffer.writeCluster(cluster)
                buffer.carriageReturn()
                buffer.newLine()
            }
            assertEquals(0, buffer.historySize)
            buffer.exitAltBuffer()
            assertEquals(expected, buffer.getAllAsString())

            buffer.resize(newWidth = 18 + it, newHeight = 4)
            assertEquals(expectedHistory, buffer.historySize)
            assertEquals(expected, buffer.getAllAsString())

            val visible = buffer.getScreenAsString()
            buffer.eraseScreenAndHistory()
            assertEquals(0, buffer.historySize)
            assertEquals(visible, buffer.getScreenAsString())
            buffer.clearAll()
        }
    }

    @Test
    fun `narrowing grows new history batches without splitting wide cells or clusters`() {
        val buffer = DefaultTerminalBuffer(initialWidth = 8, initialHeight = 4, maxHistory = 1000)
        val cluster = intArrayOf('e'.code, 0x0301)
        repeat(130) { row ->
            if (row > 0) {
                buffer.carriageReturn()
                buffer.newLine()
            }
            buffer.writeText("abc界")
            buffer.writeCluster(cluster)
        }

        buffer.resize(newWidth = 4, newHeight = 4)

        assertEquals(256, buffer.historySize)
        assertEquals(List(130) { "abc\n界e\u0301" }.joinToString("\n"), buffer.getAllAsString())

        buffer.resize(newWidth = 8, newHeight = 4)
        // Widening preserves the live top (logical row 128), padding the screen below its two remaining rows.
        assertEquals(128, buffer.historySize)
        assertEquals(List(130) { "abc界e\u0301" }.joinToString("\n") + "\n\n", buffer.getAllAsString())
        assertEquals(1, buffer.cursorRow)
    }

    @Test
    fun `deferred rows keep their original arena after screen storage replacement`() {
        val screen = ScreenBuffer(initialWidth = 4, initialHeight = 2, maxHistory = 300)
        val oldRing = screen.ring
        val oldStore = screen.store
        screen.replaceStorage(newWidth = 8, newHeight = 3, penAttr = 0L)

        repeat(259) {
            val oldLine = oldRing.push()
            assertEquals(4, oldLine.width)
            assertSame(oldStore, oldLine.store)
        }
        repeat(259) {
            val newLine = screen.ring.push()
            assertEquals(8, newLine.width)
            assertSame(screen.store, newLine.store)
        }
    }

    @Test
    fun `clearAllHistory_releasesClusterStoreSlots`() {
        val state = TerminalState(initialWidth = 4, initialHeight = 2, maxHistory = 3)
        val mutation = MutationEngine(state)

        repeat(3) { state.ring.push().clear(state.pen.currentAttr) }
        val leakedLine = state.ring[4]
        leakedLine.setCluster(0, intArrayOf('A'.code, 0x0301), 2, 0)
        val originalHandle = leakedLine.rawCodepoint(0)

        mutation.clearAllHistory()
        state.ring[0].setCluster(0, intArrayOf('B'.code, 0x0301), 2, 0)

        assertEquals(
            originalHandle,
            state.ring[0].rawCodepoint(0),
            "Clearing history must release all cluster slots so the next allocation can reuse them",
        )
    }

    @Test
    fun `scrollback eviction releases clusters retained by the oldest history line`() {
        val state = TerminalState(initialWidth = 4, initialHeight = 2, maxHistory = 1)
        val mutation = MutationEngine(state)
        val oldestVisibleLine = state.ring[0]
        oldestVisibleLine.setCluster(0, intArrayOf('A'.code, 0x0301), 2, 0)
        val originalHandle = oldestVisibleLine.rawCodepoint(0)

        mutation.scrollUp()
        mutation.scrollUp()

        val newBottomLine = state.ring[state.ring.size - 1]
        newBottomLine.setCluster(0, intArrayOf('B'.code, 0x0301), 2, 0)

        assertEquals(
            originalHandle,
            newBottomLine.rawCodepoint(0),
            "Evicting the oldest scrollback line must release its cluster slot for reuse",
        )
    }

    @Test
    fun `reset_releasesAllPrimaryHistoryClusterSlots`() {
        val buffer = DefaultTerminalBuffer(initialWidth = 4, initialHeight = 2, maxHistory = 3)
        val state =
            DefaultTerminalBufferResizeTest().run {
                val componentsField = DefaultTerminalBuffer::class.java.getDeclaredField("components")
                componentsField.isAccessible = true
                val components = componentsField.get(buffer)
                val stateField = components.javaClass.getDeclaredField("state")
                stateField.isAccessible = true
                stateField.get(components) as TerminalState
            }

        repeat(3) {
            state.primaryBuffer.ring
                .push()
                .clear(state.pen.currentAttr)
        }
        val leakedLine = state.primaryBuffer.ring[4]
        leakedLine.setCluster(0, intArrayOf('C'.code, 0x0301), 2, 0)
        val originalHandle = leakedLine.rawCodepoint(0)

        buffer.reset()
        state.primaryBuffer.ring[0].setCluster(0, intArrayOf('D'.code, 0x0301), 2, 0)

        assertEquals(
            originalHandle,
            state.primaryBuffer.ring[0].rawCodepoint(0),
            "Reset must release cluster slots retained in primary scrollback",
        )
    }
}
