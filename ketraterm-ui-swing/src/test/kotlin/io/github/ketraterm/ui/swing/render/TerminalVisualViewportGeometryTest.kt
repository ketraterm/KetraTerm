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

import io.github.ketraterm.render.api.*
import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.session.TerminalShellIntegrationState
import io.github.ketraterm.ui.swing.settings.SwingMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TerminalVisualViewportGeometryTest {
    @Test
    fun `command gutter guides do not change fixed row pitch or visible height`() {
        val cache = TerminalRenderCache(columns = 3, rows = 3)
        cache.updateFrom(TextRowsFrame(lineIds = longArrayOf(1, 2, 3)))
        val state = TerminalShellIntegrationState()
        state.recordPromptStart(1)
        state.recordPromptStart(2)
        state.recordPromptStart(3)
        val decorations = TerminalShellIntegrationViewportDecorations()
        decorations.updateFrom(state, cache)
        val layout = TerminalVisualViewportGeometry()

        assertTrue(
            layout.updateLayout(
                metrics = METRICS,
                rows = cache.rows,
                viewportPixelHeight = METRICS.cellHeight * 3,
            ),
        )

        assertEquals(0, layout.rowTop(0))
        assertEquals(METRICS.cellHeight, layout.rowTop(1))
        assertEquals(METRICS.cellHeight * 2, layout.rowTop(2))
        assertEquals(METRICS.cellHeight * 3, layout.visualHeight)
        assertEquals(METRICS.cellHeight * 3, layout.visualHeightForRows(3))
    }

    @Test
    fun `pixel to row mapping remains exact with consecutive prompt rows`() {
        val layout = fixedPromptLayout()

        assertEquals(0, layout.rowAt(0))
        assertEquals(0, layout.rowAt(METRICS.cellHeight - 1))
        assertEquals(1, layout.rowAt(METRICS.cellHeight))
        assertEquals(1, layout.rowAt(METRICS.cellHeight * 2 - 1))
        assertEquals(2, layout.rowAt(METRICS.cellHeight * 2))
    }

    @Test
    fun `terminal pixel y is unchanged by prompt decorations`() {
        val layout = fixedPromptLayout()

        assertEquals(METRICS.cellHeight, layout.terminalPixelY(METRICS.cellHeight, row = 1))
        assertEquals(METRICS.cellHeight + 3, layout.terminalPixelY(METRICS.cellHeight + 3, row = 1))
        assertEquals(METRICS.cellHeight * 2 + 4, layout.terminalPixelY(METRICS.cellHeight * 2 + 4, row = 2))
    }

    @Test
    fun `component hit testing subtracts content origin before fixed row lookup`() {
        val layout = fixedPromptLayout()

        assertTrue(layout.updateContentOrigin(-6.0))

        val componentY = METRICS.cellHeight - 6
        assertEquals(1, layout.rowAtComponentY(y = componentY, paddingTop = 0))
        assertEquals(METRICS.cellHeight, layout.terminalPixelYAtComponentY(y = componentY, paddingTop = 0))
    }

    @Test
    fun `top retained row keeps prompt dot because it represents the row itself`() {
        val cache = TerminalRenderCache(columns = 3, rows = 2)
        cache.updateFrom(TextRowsFrame(lineIds = longArrayOf(10, 11), historySize = 0, scrollbackOffset = 0))
        val state = TerminalShellIntegrationState()
        state.recordPromptStart(99)
        state.recordPromptStart(10)
        val decorations = TerminalShellIntegrationViewportDecorations()

        assertTrue(decorations.updateFrom(state, cache))

        assertTrue(decorations.hasPromptStartAt(0))
    }

    @Test
    fun `live viewport keeps row zero prompt dot when retained history has a previous row`() {
        val cache = TerminalRenderCache(columns = 3, rows = 2)
        cache.updateFrom(TextRowsFrame(lineIds = longArrayOf(10, 11), historySize = 5, scrollbackOffset = 0))
        val state = TerminalShellIntegrationState()
        state.recordPromptStart(99)
        state.recordPromptStart(10)
        val decorations = TerminalShellIntegrationViewportDecorations()

        assertTrue(decorations.updateFrom(state, cache))

        assertTrue(decorations.hasPromptStartAt(0))
    }

    private fun fixedPromptLayout(): TerminalVisualViewportGeometry {
        val cache = TerminalRenderCache(columns = 3, rows = 3)
        cache.updateFrom(TextRowsFrame(lineIds = longArrayOf(1, 2, 3)))
        val state = TerminalShellIntegrationState()
        state.recordPromptStart(1)
        state.recordPromptStart(2)
        state.recordPromptStart(3)
        val decorations = TerminalShellIntegrationViewportDecorations()
        decorations.updateFrom(state, cache)
        return TerminalVisualViewportGeometry().also {
            it.updateLayout(
                metrics = METRICS,
                rows = cache.rows,
                viewportPixelHeight = METRICS.cellHeight * 3,
            )
        }
    }

    private class TextRowsFrame(
        private val lineIds: LongArray,
        override val historySize: Int = 0,
        override val scrollbackOffset: Int = 0,
    ) : TerminalRenderFrameReader,
        TerminalRenderFrame {
        override val columns: Int = 3
        override val rows: Int = lineIds.size
        override val frameGeneration: Long = 1
        override val structureGeneration: Long = 1
        override val activeBuffer: TerminalRenderBufferKind = TerminalRenderBufferKind.PRIMARY
        override val cursor: TerminalRenderCursor =
            TerminalRenderCursor(
                column = 0,
                row = 0,
                visible = false,
                blinking = false,
                shape = TerminalRenderCursorShape.BLOCK,
                generation = 1,
            )

        override fun readRenderFrame(consumer: TerminalRenderFrameConsumer) {
            consumer.accept(this)
        }

        override fun lineGeneration(row: Int): Long = 1

        override fun lineId(row: Int): Long = lineIds[row]

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
                flags[flagOffset + column] = TerminalRenderCellFlags.EMPTY
                extraAttrWords?.set(extraAttrOffset + column, TerminalRenderExtraAttrs.DEFAULT)
                hyperlinkIds?.set(hyperlinkOffset + column, 0)
                column++
            }
        }
    }

    private companion object {
        private val METRICS =
            SwingMetrics(
                cellWidth = 8,
                cellHeight = 16,
                baseline = 12,
                underlineY = 13,
                strikethroughY = 8,
                overlineY = 0,
                cursorStrokeWidth = 1,
            )
    }
}
