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
package io.github.ketraterm.ui.swing.render

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.core.api.TerminalRenderBuffer
import io.github.ketraterm.render.api.*
import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.session.TerminalShellIntegrationState
import io.github.ketraterm.ui.swing.settings.SwingMetrics
import org.junit.jupiter.api.Test
import java.awt.Rectangle
import kotlin.test.*

class PromptDividerLayoutTest {
    @Test
    fun `cursor and command lifecycle updates reuse resolved prompt positions`() {
        val buffer = TerminalBuffers.create(8, 3)
        buffer.writeText(">")
        val state = TerminalShellIntegrationState()
        val id = lineId(buffer, 0)
        state.recordPromptStart(id)
        val reader = CountingReader(buffer)
        val dividers = PromptDividerLayout()
        dividers.updateFrom(reader, state, 3)
        reader.lineReads = 0
        buffer.positionCursor(2, 0)
        dividers.updateFrom(reader, state, 3)
        assertEquals(0, reader.lineReads)
        state.recordPromptEnd(id)
        dividers.updateFrom(reader, state, 3)
        assertEquals(0, reader.lineReads)
    }

    @Test
    fun `adding a live prompt does not rescan history for an already evicted prompt`() {
        val buffer = TerminalBuffers.create(8, 3, maxHistory = 100)
        val state = TerminalShellIntegrationState()
        state.recordPromptStart(lineId(buffer, 0))
        repeat(200) {
            buffer.writeText("x")
            buffer.carriageReturn()
            buffer.newLine()
        }
        val reader = CountingReader(buffer)
        val dividers = PromptDividerLayout()
        dividers.updateFrom(reader, state, 3)
        reader.lineReads = 0
        state.recordPromptStart(lineId(buffer, 2))
        dividers.updateFrom(reader, state, 3)
        assertEquals(3, reader.lineReads)
    }

    @Test
    fun `initial prompt preserves top placement and consumes one visual row`() {
        val buffer = TerminalBuffers.create(8, 4)
        buffer.writeText(">")
        val state = TerminalShellIntegrationState()
        state.recordPromptStart(lineId(buffer, 0))
        val dividers = PromptDividerLayout()
        dividers.updateFrom(buffer, state, 4)
        assertEquals(0, dividers.scrollRange)
        val cache = TerminalRenderCache(8, 4)
        cache.updateFrom(buffer)
        val geometry = TerminalVisualViewportGeometry()
        geometry.updateLayout(METRICS, 4, 64, dividers)
        assertEquals(16, geometry.rowTop(0))
        assertEquals(80, geometry.visualHeight)
        assertEquals(0, geometry.dividerRowAtComponentY(8, 0))
        assertEquals(-1, geometry.dividerRowAtComponentY(16, 0))
        assertEquals(0, geometry.rowAt(16))
        assertEquals(4, geometry.terminalPixelYAtComponentY(20, 0))
        val bounds = Rectangle()
        assertTrue(geometry.copyCellBounds(cache, METRICS, 0, 0, 0, 0, 64, 64, bounds))
        assertEquals(Rectangle(0, 16, 8, 16), bounds)
    }

    @Test
    fun `dense prompts expose displaced live rows without core scrollback`() {
        val buffer = TerminalBuffers.create(8, 3)
        val state = TerminalShellIntegrationState()
        for (row in 0..2) {
            buffer.positionCursor(0, row)
            buffer.writeText(">")
            state.recordPromptStart(lineId(buffer, row))
        }
        val dividers = PromptDividerLayout()
        dividers.updateFrom(buffer, state, 3)
        assertEquals(3, dividers.scrollRange)
        assertEquals(0, dividers.renderOffset(0.0))
        assertEquals(0, dividers.renderOffset(3.0))
        assertEquals(0L, dividers.rowAt(0.0))
        assertEquals(1L, dividers.rowAt(3.0))
        assertEquals(2L, dividers.rowAt(5.0))
        buffer.readRenderFrame { assertEquals(0, it.historySize) }
        val geometry = TerminalVisualViewportGeometry()
        geometry.updateLayout(METRICS, 3, 48, dividers)
        geometry.updateContentOrigin(-48.0)
        assertEquals(1, geometry.rowAtComponentY(0, 0))
        assertEquals(2, geometry.dividerRowAtComponentY(24, 0))
        assertEquals(1, geometry.firstFullyVisibleRow())
        assertEquals(16, geometry.terminalPixelYAtComponentY(0, 0))
    }

    @Test
    fun `moving the cursor above displaced output keeps its text row visible`() {
        val buffer = TerminalBuffers.create(8, 3)
        val state = TerminalShellIntegrationState()
        for (row in 0..2) {
            buffer.positionCursor(0, row)
            buffer.writeText(">")
            state.recordPromptStart(lineId(buffer, row))
        }
        val dividers = PromptDividerLayout()
        dividers.updateFrom(buffer, state, 3)
        assertEquals(3L, dividers.liveOrigin)
        buffer.positionCursor(0, 0)
        dividers.updateFrom(buffer, state, 3)
        val cursorTop = dividers.rowBoundary(0) + 1 - dividers.liveOrigin
        assertTrue(cursorTop in 0 until 3)
    }

    @Test
    fun `ordinary output indexes new history and live rows without rescanning retained history`() {
        val buffer = TerminalBuffers.create(8, 3, maxHistory = 1000)
        repeat(100) {
            buffer.writeText("x")
            buffer.carriageReturn()
            buffer.newLine()
        }
        val state = TerminalShellIntegrationState()
        state.recordPromptStart(lineId(buffer, 2))
        val reader = CountingReader(buffer)
        val dividers = PromptDividerLayout()
        dividers.updateFrom(reader, state, 3)
        reader.lineReads = 0
        buffer.writeText("x")
        buffer.carriageReturn()
        buffer.newLine()
        dividers.updateFrom(reader, state, 3)
        assertTrue(reader.lineReads <= 4, "read ${reader.lineReads} identities for one admitted row")
    }

    @Test
    fun `history eviction removes divider height and missing metadata creates no bands`() {
        val buffer = TerminalBuffers.create(8, 2, maxHistory = 1)
        buffer.writeText(">")
        val state = TerminalShellIntegrationState()
        state.recordPromptStart(lineId(buffer, 0))
        val dividers = PromptDividerLayout()
        dividers.updateFrom(buffer, state, 2)
        repeat(3) {
            buffer.carriageReturn()
            buffer.newLine()
            buffer.writeText("x")
        }
        dividers.updateFrom(buffer, state, 2)
        assertFalse(dividers.hasDividerAt(dividers.discardedCount))
        assertEquals(1, dividers.scrollRange)
        assertEquals(2L, dividers.visualDiscardedCount)
        assertEquals(0L, dividers.rowBoundary(dividers.discardedCount))
    }

    @Test
    fun `reflow rebuilds prompt positions without creating bands on continuation rows`() {
        val buffer = TerminalBuffers.create(8, 4)
        buffer.writeText("abcdefgh")
        val state = TerminalShellIntegrationState()
        val promptId = lineId(buffer, 0)
        state.recordPromptStart(promptId)
        val dividers = PromptDividerLayout()
        dividers.updateFrom(buffer, state, 4)
        buffer.resize(4, 4)
        dividers.updateFrom(buffer, state, 4)
        val geometry = TerminalVisualViewportGeometry()
        geometry.updateLayout(METRICS, 4, 64, dividers, dividers.liveTop)
        var bands = 0
        for (row in 0..3) if (geometry.hasDividerBefore(row)) bands++
        assertEquals(1, bands)
    }

    @Test
    fun `closed projection includes live rows hidden by a shorter component`() {
        val buffer = TerminalBuffers.create(8, 4)
        buffer.writeText(">")
        val state = TerminalShellIntegrationState()
        state.recordPromptStart(lineId(buffer, 0))
        val dividers = PromptDividerLayout()
        dividers.updateFrom(buffer, state, 2, retainedOutput = true)
        assertEquals(3, dividers.scrollRange)
        assertEquals(4, dividers.gridRows)
        assertEquals(0, dividers.renderOffset(0.0))
        assertEquals(0L, dividers.rowAt(0.0))
        assertEquals(2L, dividers.rowAt(3.0))
    }

    private fun lineId(
        buffer: TerminalRenderBuffer,
        row: Int,
    ): Long {
        var id = 0L
        buffer.readRenderFrame { id = it.lineId(row) }
        return id
    }

    private class CountingReader(
        private val buffer: TerminalRenderBuffer,
    ) : TerminalRenderFrameReader by buffer {
        var lineReads = 0

        override fun readRenderFrameForAbsoluteRange(
            startAbsoluteRow: Long,
            endAbsoluteRow: Long,
            consumer: TerminalRenderFrameConsumer,
        ) {
            buffer.readRenderFrameForAbsoluteRange(startAbsoluteRow, endAbsoluteRow) { frame ->
                consumer.accept(
                    object : TerminalRenderFrame by frame {
                        override fun lineId(row: Int): Long {
                            lineReads++
                            return frame.lineId(row)
                        }
                    },
                )
            }
        }
    }

    private companion object {
        val METRICS = SwingMetrics(8, 16, 12, 13, 8, 0, 1)
    }
}
