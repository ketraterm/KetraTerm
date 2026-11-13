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

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.render.api.*
import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.session.TerminalShellIntegrationFactory
import io.github.ketraterm.session.TerminalShellIntegrationState
import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.transport.TerminalConnectorListener
import io.github.ketraterm.ui.swing.settings.SwingMetrics
import io.github.ketraterm.ui.swing.settings.SwingSettings
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Test
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TerminalHistoryInteractionTest {
    @Test
    fun `navigation and command selection scan identities without copying historical cells`() =
        onEdt {
            for (history in listOf(100, 10_000)) {
                Fixture(history).use { fixture ->
                    val controller = TerminalCommandInteractionController(fixture)
                    fixture.reader.resetVolume()
                    assertTrue(controller.revealCommand(fixture.recordId))
                    assertEquals(history / 2L, fixture.revealedRow)
                    assertEquals(0, fixture.reader.fullRows)
                    assertEquals(0, fixture.reader.textRows)
                    assertTrue(fixture.reader.identityReads <= fixture.reader.totalRows)

                    fixture.reader.resetVolume()
                    assertTrue(controller.selectCommandOutput(fixture.recordId))
                    assertEquals(history / 2L to history / 2L + 11, fixture.selectedRows)
                    assertTrue(controller.selectCommandBlock(fixture.recordId))
                    assertEquals(history / 2L to history / 2L + 11, fixture.selectedRows)
                    assertEquals(0, fixture.reader.fullRows)
                    assertEquals(0, fixture.reader.textRows)
                    assertTrue(fixture.reader.identityReads <= fixture.reader.totalRows * 8)
                }
            }
        }

    @Test
    fun `command copy reads only output cells even with ten thousand unrelated history rows`() =
        onEdt {
            Fixture(10_000).use { fixture ->
                val controller = TerminalCommandInteractionController(fixture)
                fixture.reader.resetVolume()
                assertEquals(List(12) { "abcdefghij" }.joinToString("\n"), controller.commandOutputText(fixture.recordId))
                assertEquals(0, fixture.reader.fullRows)
                assertEquals(12, fixture.reader.textRows)
                assertEquals(1200, fixture.reader.textCells)
                assertTrue(fixture.reader.maxTextDestinationCells <= 4096)
            }
        }

    @Test
    fun `linear and block copies use bounded text workspace for the entire retained selection`() =
        onEdt {
            for (block in listOf(false, true)) {
                Fixture(10_000).use { fixture ->
                    val controller = fixture.selection(block, fixture.reader.totalRows - 1L)
                    fixture.reader.resetVolume()
                    val expectedLine = if (block) "cdefg" else "abcdefghij"
                    assertEquals(
                        List(fixture.reader.totalRows) { expectedLine }.joinToString("\n"),
                        controller.getSelectedText(fixture.reader),
                    )
                    assertEquals(0, fixture.reader.fullRows)
                    assertEquals(fixture.reader.totalRows, fixture.reader.textRows)
                    assertEquals(fixture.reader.totalRows * 100, fixture.reader.textCells)
                    assertTrue(fixture.reader.maxTextDestinationCells <= 4096)
                    assertTrue(fixture.reader.maxTextRowsPerRead <= 64)
                }
            }
        }

    @Test
    fun `chunk boundaries preserve soft wraps bidi wide cells clusters and backward ranges`() =
        onEdt {
            Fixture(100).use { fixture ->
                fixture.reader.unicode = true
                fixture.reader.oneLogicalLine = true
                val reference = TerminalRenderCache(100, 82)
                fixture.reader.readRenderFrameForAbsoluteRange(0, 81, reference)
                for (block in listOf(false, true)) {
                    for (backward in listOf(false, true)) {
                        val start = if (block) 2 else 0
                        val end = if (block) 7 else 100
                        val selection = if (backward) CellSelection(end, 81, start, 0, block) else CellSelection(start, 0, end, 81, block)
                        val expected = TerminalSelectionTextExtractor().selectedText(reference, selection, joinSoftWrappedRows = !block)
                        val actual =
                            TerminalSelectionTextReader().read(
                                fixture.reader,
                                if (backward) 81 else 0,
                                if (backward) end else start,
                                if (backward) 0 else 81,
                                if (backward) start else end,
                                block,
                            )
                        assertEquals(expected, actual, "block=$block backward=$backward")
                        if (!block) {
                            val prefix = "אב界e\u0301 👩‍💻"
                            assertTrue(assertNotNull(actual).contains(prefix + " ".repeat(91) + prefix))
                        }
                    }
                }
            }
        }

    @Test
    fun `copy does not publish a mixture of revisions or layouts across batches`() =
        onEdt {
            for (change in listOf("content", "history", "buffer", "columns")) {
                Fixture(100).use { fixture ->
                    val controller = fixture.selection(false, 81)
                    fixture.reader.afterTextBatch = {
                        when (change) {
                            "content" -> fixture.reader.generation++
                            "history" -> fixture.reader.historyGeneration++
                            "buffer" -> fixture.reader.buffer = TerminalRenderBufferKind.ALTERNATE
                            "columns" -> fixture.reader.width = 80
                        }
                    }
                    assertNull(controller.getSelectedText(fixture.reader), change)
                    fixture.reader.afterTextBatch = null
                    fixture.reader.generation = 1
                    fixture.reader.historyGeneration = 1
                    fixture.reader.buffer = TerminalRenderBufferKind.PRIMARY
                    fixture.reader.width = 100
                    assertEquals(List(82) { "abcdefghij" }.joinToString("\n"), controller.getSelectedText(fixture.reader), change)
                }
            }
        }

    @Test
    fun `command copy rejects mutation between identity lookup and text capture`() =
        onEdt {
            Fixture(100).use { fixture ->
                val controller = TerminalCommandInteractionController(fixture)
                fixture.reader.resetVolume()
                fixture.reader.afterMetadataRead = {
                    fixture.reader.afterMetadataRead = null
                    fixture.reader.generation++
                }
                assertNull(controller.commandOutputText(fixture.recordId))
                assertEquals(0, fixture.reader.textRows)
                assertEquals(0, fixture.reader.fullRows)
                assertEquals(List(12) { "abcdefghij" }.joinToString("\n"), controller.commandOutputText(fixture.recordId))
            }
        }

    private fun onEdt(action: () -> Unit) = SwingUtilities.invokeAndWait(action)

    private class Fixture(
        history: Int,
    ) : TerminalSelectionHost,
        TerminalCommandInteractionHost,
        AutoCloseable {
        val reader = CountingReader(history)
        private val state =
            TerminalShellIntegrationState().apply {
                recordPromptStart(history / 2L + 1)
                recordCommandStart(history / 2L + 1, includeLine = true)
                recordCommandFinished(history / 2L + 12, exitCode = 0)
            }
        val recordId = state.latestCommandRecordId()
        override val session =
            TerminalSession.create(
                TerminalBuffers.create(100, 28),
                reader,
                NoOpConnector,
                shellIntegration = TerminalShellIntegrationFactory.host(state),
                workerDispatcher = Dispatchers.Unconfined,
                ioDispatcher = Dispatchers.Unconfined,
                cleanupDispatcher = Dispatchers.Unconfined,
            )
        override val renderCache = TerminalRenderCache(100, 28).also { it.updateFrom(session) }
        override val settings = SwingSettings.create {}
        override val metrics: SwingMetrics get() = error("not used")
        override val contentYOffset = 0.0
        override val componentWidth = 1000
        override val componentHeight = 560
        var revealedRow = -1L
        var selectedRows: Pair<Long, Long>? = null

        fun selection(
            block: Boolean,
            last: Long,
        ): TerminalSelectionController =
            TerminalSelectionController(this).also { controller ->
                controller.updateFrame(renderCache)
                session.readRenderFrame { frame ->
                    val range = assertNotNull(controller.createRange(frame, if (block) 2 else 0, 0, if (block) 7 else 100, last, block))
                    assertTrue(controller.setRange(range, frame))
                }
            }

        override fun cellAt(
            x: Int,
            y: Int,
        ): Long = error("not used")

        override fun cellAt(
            x: Int,
            y: Int,
            cache: TerminalRenderCache,
        ): Long = error("not used")

        override fun visualCellAt(
            x: Int,
            y: Int,
        ): Long = error("not used")

        override fun scrollViewportByRows(deltaRows: Int) = false

        override fun repaint() = Unit

        override fun requestFocusInWindow() = false

        override fun copySelection() = Unit

        override fun commandNavigationAnchorRow() = 0

        override fun refreshRenderCacheFromSession(session: TerminalSession) = Unit

        override fun refreshShellIntegrationDecorations(session: TerminalSession) = false

        override fun selectAbsoluteRows(
            startAbsoluteRow: Long,
            endAbsoluteRow: Long,
            columns: Int,
        ) {
            selectedRows =
                startAbsoluteRow to endAbsoluteRow
        }

        override fun scrollToAbsoluteRow(
            row: Long,
            center: Boolean,
        ): Boolean {
            revealedRow = row
            return true
        }

        override fun close() = session.close()
    }

    @Test
    fun `a row wider than the cell budget is copied one row at a time`() =
        onEdt {
            Fixture(100).use { fixture ->
                fixture.reader.width = 8000
                fixture.reader.resetVolume()
                assertEquals(
                    List(82) { "abcdefghij" }.joinToString("\n"),
                    TerminalSelectionTextReader().read(fixture.reader, 0, 0, 81, 8000),
                )
                assertEquals(82, fixture.reader.textRows)
                assertEquals(0, fixture.reader.fullRows)
                assertEquals(8000, fixture.reader.maxTextDestinationCells)
                assertEquals(1, fixture.reader.maxTextRowsPerRead)
            }
        }

    private class CountingReader(
        val history: Int,
    ) : TerminalRenderFrameReader {
        val totalRows = history + 28
        var width = 100
        var generation = 1L
        var historyGeneration = 1L
        var buffer = TerminalRenderBufferKind.PRIMARY
        var unicode = false
        var oneLogicalLine = false
        var afterTextBatch: (() -> Unit)? = null
        var afterMetadataRead: (() -> Unit)? = null
        var fullRows = 0
        var textRows = 0
        var textCells = 0
        var maxTextDestinationCells = 0
        var maxTextRowsPerRead = 0
        var identityReads = 0

        fun resetVolume() {
            fullRows = 0
            textRows = 0
            textCells = 0
            maxTextDestinationCells = 0
            maxTextRowsPerRead = 0
            identityReads =
                0
        }

        override fun readRenderFrame(consumer: TerminalRenderFrameConsumer) = expose(history, 28, consumer)

        override fun readRenderFrameForAbsoluteRange(
            startAbsoluteRow: Long,
            endAbsoluteRow: Long,
            consumer: TerminalRenderFrameConsumer,
        ) {
            val start = startAbsoluteRow.coerceIn(0, totalRows - 1L)
            val end = endAbsoluteRow.coerceIn(start, totalRows - 1L)
            val top = minOf(start, history.toLong()).toInt()
            expose(top, (end - top + 1).toInt(), consumer)
        }

        private fun expose(
            top: Int,
            count: Int,
            consumer: TerminalRenderFrameConsumer,
        ) {
            val before = textRows
            consumer.accept(
                object : TerminalRenderFrame {
                    override val columns = width
                    override val rows = count
                    override val historySize = history
                    override val scrollbackOffset = history - top
                    override val frameGeneration = generation
                    override val contentGeneration = generation
                    override val historyContentGeneration = historyGeneration
                    override val structureGeneration = 1L
                    override val activeBuffer = buffer
                    override val cursor = TerminalRenderCursor(0, 0, false, false, TerminalRenderCursorShape.BLOCK, 0)

                    override fun lineId(row: Int): Long {
                        identityReads++
                        return if (oneLogicalLine) 1L else top + row + 1L
                    }

                    override fun lineGeneration(row: Int) = 1L

                    override fun lineWrapped(row: Int) = if (oneLogicalLine) top + row < 81 else unicode && top + row == 39

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
                        fullRows++
                        writeText(row, codeWords, codeOffset, flags, flagOffset, clusterDataSink)
                        attrWords.fill(TerminalRenderAttrs.DEFAULT, attrOffset, attrOffset + columns)
                    }

                    override fun copyTextLine(
                        row: Int,
                        codeWords: IntArray,
                        codeOffset: Int,
                        flags: IntArray,
                        flagOffset: Int,
                        clusterDataSink: TerminalRenderClusterDataSink?,
                    ) {
                        textRows++
                        textCells += columns
                        maxTextDestinationCells = maxOf(maxTextDestinationCells, codeWords.size, flags.size)
                        writeText(row, codeWords, codeOffset, flags, flagOffset, clusterDataSink)
                    }

                    private fun writeText(
                        row: Int,
                        codes: IntArray,
                        offset: Int,
                        flags: IntArray,
                        flagOffset: Int,
                        sink: TerminalRenderClusterDataSink?,
                    ) {
                        codes.fill(0, offset, offset + columns)
                        flags.fill(TerminalRenderCellFlags.EMPTY, flagOffset, flagOffset + columns)
                        if (unicode && top + row in 39..40) {
                            codes[offset] = 'א'.code
                            codes[offset + 1] = 'ב'.code
                            codes[offset + 2] = '界'.code
                            flags[flagOffset] = TerminalRenderCellFlags.CODEPOINT
                            flags[flagOffset + 1] = TerminalRenderCellFlags.CODEPOINT
                            flags[flagOffset + 2] = TerminalRenderCellFlags.CODEPOINT or TerminalRenderCellFlags.WIDE_LEADING
                            flags[flagOffset + 3] = TerminalRenderCellFlags.WIDE_TRAILING
                            flags[flagOffset + 4] = TerminalRenderCellFlags.CLUSTER
                            sink?.onCluster(4, intArrayOf('e'.code, 0x301), 0, 2)
                            flags[flagOffset + 6] = TerminalRenderCellFlags.CLUSTER or TerminalRenderCellFlags.WIDE_LEADING
                            flags[flagOffset + 7] = TerminalRenderCellFlags.WIDE_TRAILING
                            sink?.onCluster(6, intArrayOf(0x1F469, 0x200D, 0x1F4BB), 0, 3)
                            if (top + row == 39) {
                                flags[flagOffset + columns - 1] = TerminalRenderCellFlags.EMPTY or TerminalRenderCellFlags.WRAP_PADDING
                            }
                        } else {
                            for (column in 0..9) {
                                codes[offset + column] = 'a'.code + column
                                flags[flagOffset + column] =
                                    TerminalRenderCellFlags.CODEPOINT
                            }
                        }
                    }
                },
            )
            val copied = textRows - before
            maxTextRowsPerRead = maxOf(maxTextRowsPerRead, copied)
            if (copied > 0) afterTextBatch?.invoke() else afterMetadataRead?.invoke()
        }
    }

    private object NoOpConnector : TerminalConnector {
        override fun start(listener: TerminalConnectorListener) = Unit

        override fun write(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) = Unit

        override fun resize(
            columns: Int,
            rows: Int,
        ) = Unit

        override fun close() = Unit
    }
}
