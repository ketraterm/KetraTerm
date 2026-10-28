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
import io.github.ketraterm.render.api.TerminalColorPalette
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.session.TerminalSessionState
import io.github.ketraterm.session.TerminalShellIntegrationFactory
import io.github.ketraterm.session.TerminalShellIntegrationState
import io.github.ketraterm.shell.integration.OscShellIntegration
import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.transport.TerminalConnectorListener
import io.github.ketraterm.ui.swing.settings.SwingPadding
import io.github.ketraterm.ui.swing.settings.SwingPromptDecoration
import io.github.ketraterm.ui.swing.settings.SwingSettings
import io.github.ketraterm.ui.swing.settings.TerminalClipboardHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.ComponentEvent
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SwingTerminalPromptDividerTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `binding preferred size matches the configured grid and actual shell gutter`(promptMarkersExpected: Boolean) =
        edt {
            Fixture(SwingPromptDecoration.GUTTER, OscShellIntegration.configured(promptMarkersExpected)).use { f ->
                assertEquals(promptMarkersExpected, f.session.promptMarkersExpected)
                assertEquals(f.component.preferredGridSize(f.settings.columns, f.settings.rows), f.component.preferredSize)
                f.component.size = f.component.preferredSize
                assertEquals(f.settings.columns, f.component.visibleGridSize().width)
                assertEquals(f.settings.rows, f.component.visibleGridSize().height)
            }
        }

    @ParameterizedTest
    @EnumSource(SwingPromptDecoration::class)
    fun `public cell hit tests reject prompt decorations and preserve logical cell identity`(mode: SwingPromptDecoration) =
        edt {
            Fixture(mode).use { f ->
                f.prompt("אב界")
                val bounds = Rectangle()
                val destination = Point()
                for (column in 0 until f.component.visibleGridSize().width) {
                    assertTrue(f.component.copyCellBounds(column, 0, bounds))
                    assertTrue(f.component.copyCellPositionAt(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2, destination))
                    assertEquals(Point(column, 0), destination)
                }
                assertTrue(f.component.copyCellBounds(0, 0, bounds))
                when (mode) {
                    SwingPromptDecoration.GUTTER -> {
                        assertFalse(
                            f.component.copyCellPositionAt(f.settings.shellIntegrationDecorationGutterWidth - 1, bounds.y, destination),
                        )
                        assertEquals(Point(-1, -1), destination)
                    }
                    SwingPromptDecoration.DIVIDER -> {
                        assertFalse(f.component.copyCellPositionAt(bounds.x, bounds.y - f.cellHeight / 2, destination))
                        assertEquals(Point(-1, -1), destination)
                    }
                    SwingPromptDecoration.NONE -> Unit
                }
                val writes = f.connector.writes.toList()
                val resizes = f.connector.resizes.toList()
                repeat(20) {
                    assertTrue(f.component.copyCellPositionAt(bounds.x, bounds.y, destination))
                    assertEquals(Point(0, 0), destination)
                }
                assertEquals(writes, f.connector.writes)
                assertEquals(resizes, f.connector.resizes)
            }
        }

    @ParameterizedTest
    @EnumSource(SwingPromptDecoration::class)
    fun `configured prompt layout precedes OSC metadata without a first prompt resize`(mode: SwingPromptDecoration) =
        edt {
            Fixture(mode, OscShellIntegration.configured(promptMarkersExpected = true)).use { f ->
                assertTrue(f.session.promptMarkersExpected)
                assertEquals(0, f.session.shellIntegrationState.recordCount())
                val bounds = Rectangle()
                assertTrue(f.component.copyCellBounds(0, 0, bounds))
                val expectedLeft = if (mode == SwingPromptDecoration.GUTTER) f.settings.shellIntegrationDecorationGutterWidth else 0
                assertEquals(expectedLeft, bounds.x)
                assertEquals(8, f.component.visibleGridSize().width)
                val resizes = f.connector.resizes.toList()
                var promptLineId = 0L
                f.session.readRenderFrame { promptLineId = it.lineId(0) }
                f.feed("\u001B]133;A\u0007")
                assertEquals(resizes, f.connector.resizes)
                f.feed(">first\u001B]133;B\u0007")
                f.session.readRenderFrame { assertEquals(promptLineId, it.lineId(0)) }
                assertTrue(f.component.copyCellBounds(0, 0, bounds))
                assertEquals(expectedLeft, bounds.x)
                if (mode == SwingPromptDecoration.GUTTER) {
                    val image = BufferedImage(f.component.width, f.component.height, BufferedImage.TYPE_INT_ARGB)
                    val graphics = image.createGraphics()
                    try {
                        f.component.paint(graphics)
                    } finally {
                        graphics.dispose()
                    }
                    assertNotEquals(BLACK, image.getRGB(bounds.x / 2, bounds.y + f.cellHeight / 2))
                }
                f.feed("\u001B[?1049hALT")
                assertTrue(f.component.copyCellBounds(0, 0, bounds))
                assertEquals((f.component.width - 8 * bounds.width) / 2, bounds.x)
                assertEquals(0, bounds.y)
                f.feed("\u001B[?1049l")
                assertEquals(resizes, f.connector.resizes)
                assertTrue(f.component.copyCellBounds(0, 0, bounds))
                assertEquals(expectedLeft, bounds.x)
            }
        }

    @Test
    fun `divider painting and cursor bounds share reserved band geometry`() =
        edt {
            Fixture().use { f ->
                f.prompt(">one")
                val h = f.cellHeight
                val bounds = Rectangle()
                assertTrue(f.component.copyCellBounds(0, 0, bounds))
                assertEquals(h, bounds.y)
                assertEquals(3, f.component.visibleGridSize().height)
                f.session.readRenderFrame {
                    assertEquals(3, it.rows)
                    assertEquals(0, it.historySize)
                }
                val image = BufferedImage(f.component.width, f.component.height, BufferedImage.TYPE_INT_ARGB)
                val graphics = image.createGraphics()
                try {
                    f.component.paint(graphics)
                } finally {
                    graphics.dispose()
                }
                val ruleTop = (h - 2) / 2
                assertNotEquals(BLACK, image.getRGB(1, ruleTop))
                assertNotEquals(BLACK, image.getRGB(1, ruleTop + 1))
                assertEquals(BLACK, image.getRGB(1, ruleTop - 1))
                assertEquals(BLACK, image.getRGB(1, ruleTop + 2))
                assertEquals(BLACK, image.getRGB(1, 0))
                assertEquals(BLACK, image.getRGB(1, h - 1))
                assertEquals(BLACK, image.getRGB(1, h * 2 - 1))
            }
        }

    @Test
    fun `scrollbar reaches rows displaced from live grid and copying excludes bands`() =
        edt {
            Fixture().use { f ->
                f.prompt(">one")
                f.feed("\r\n")
                f.prompt(">two")
                f.feed("\r\n")
                f.prompt(">three")
                val h = f.cellHeight
                assertEquals(3, f.component.viewportState().historySize)
                assertEquals(3 * h, f.component.viewportState().visualScrollRangePixels)
                f.session.readRenderFrame {
                    assertEquals(0, it.historySize)
                    assertEquals(3, it.rows)
                }
                val bounds = Rectangle()
                assertFalse(f.component.copyCellBounds(0, 0, bounds))
                assertTrue(f.component.copyCellBounds(0, 2, bounds))
                assertEquals(2 * h, bounds.y)
                f.component.scrollFromScrollbar(3, true)
                f.flush()
                assertTrue(f.component.copyCellBounds(0, 0, bounds))
                assertEquals(h, bounds.y)
                assertTrue(f.component.selectAll())
                assertTrue(f.component.copySelectionToClipboard())
                assertEquals(">one\n>two\n>three", f.clipboard.trimEnd())
                f.component.scrollFromScrollbar(0, true)
                f.flush()
                assertTrue(f.component.copyCellBounds(0, 2, bounds))
                assertEquals(2 * h, bounds.y)
            }
        }

    @Test
    fun `alternate screen suppresses divider bands without resizing the terminal`() =
        edt {
            Fixture().use { f ->
                f.prompt(">one")
                val resizes = f.connector.resizes.toList()
                f.feed("\u001B[?1049hALT")
                val bounds = Rectangle()
                assertTrue(f.component.copyCellBounds(0, 0, bounds))
                assertEquals(0, bounds.y)
                assertEquals(0, f.component.viewportState().historySize)
                f.feed("\u001B[?1049l")
                assertTrue(f.component.copyCellBounds(0, 0, bounds))
                assertEquals(f.cellHeight, bounds.y)
                assertEquals(resizes, f.connector.resizes)
            }
        }

    @Test
    fun `none removes decoration space while retaining shell commands`() =
        edt {
            Fixture().use { f ->
                f.prompt(">one")
                f.session.readRenderFrame { f.state.recordCommandStart(it.lineId(0), includeLine = true) }
                f.flush()
                val record = f.state.latestCommandRecordId()
                assertTrue(record != 0)
                f.settings = f.settings.copy { it.promptDecoration = SwingPromptDecoration.NONE }
                f.component.reloadSettings()
                f.flush()
                val bounds = Rectangle()
                assertTrue(f.component.copyCellBounds(0, 0, bounds))
                assertEquals(0, bounds.y)
                assertEquals(record, f.component.commandRecordAt(1, 1))
                assertEquals(3, f.component.visibleGridSize().height)
                f.settings = f.settings.copy { it.promptDecoration = SwingPromptDecoration.DIVIDER }
                f.component.reloadSettings()
                f.flush()
                assertTrue(f.component.copyCellBounds(0, 0, bounds))
                assertEquals(f.cellHeight, bounds.y)
            }
        }

    @Test
    fun `divider gestures never emit application mouse coordinates`() =
        edt {
            Fixture().use { f ->
                f.prompt(">one")
                f.feed("\u001B[?1000h\u001B[?1006h")
                f.connector.writes.clear()
                f.mouse(MouseEvent.MOUSE_PRESSED, 1, f.cellHeight / 2)
                f.mouse(MouseEvent.MOUSE_DRAGGED, 1, f.cellHeight + 1)
                f.mouse(MouseEvent.MOUSE_RELEASED, 1, f.cellHeight + 1)
                f.flush()
                assertEquals(emptyList(), f.connector.writes)
                f.mouse(MouseEvent.MOUSE_PRESSED, 1, f.cellHeight + 1)
                f.mouse(MouseEvent.MOUSE_RELEASED, 1, f.cellHeight / 2)
                f.flush()
                assertEquals("\u001B[<0;1;1M\u001B[<0;1;1m", f.connector.writes.joinToString(""))
            }
        }

    @Test
    fun `closed divider viewport preserves source grid through height changes`() =
        edt {
            Fixture().use { f ->
                f.prompt(">one")
                f.feed("\r\n")
                f.prompt(">two")
                f.session.onClosed(0)
                f.flush()
                val resizes = f.connector.resizes.toList()
                f.component.size = f.component.preferredGridSize(8, 1)
                f.component.componentListeners.forEach {
                    it.componentResized(ComponentEvent(f.component, ComponentEvent.COMPONENT_RESIZED))
                }
                f.flush()
                assertEquals(4, f.component.viewportState().historySize)
                f.component.scrollFromScrollbar(4, true)
                f.flush()
                // The first slot is the divider; scrolling one slot forward reveals its prompt.
                f.component.scrollFromScrollbar(3, true)
                f.flush()
                val bounds = Rectangle()
                assertTrue(f.component.copyCellBounds(0, 0, bounds))
                assertEquals(0, bounds.y)
                assertEquals(resizes, f.connector.resizes)
                f.session.readRenderFrame { assertEquals(3, it.rows) }
            }
        }

    @Test
    fun `gutter mode reserves no space without shell command metadata on either screen`() =
        edt {
            Fixture(SwingPromptDecoration.GUTTER).use { f ->
                val bounds = Rectangle()
                assertTrue(f.component.copyCellBounds(0, 0, bounds))
                assertEquals(0, bounds.x)
                val columns = f.component.visibleGridSize().width
                assertTrue(columns > 8)
                val resizes = f.connector.resizes.toList()
                f.feed("C:>")
                f.state.recordCurrentWorkingDirectory("file:///C:/")
                f.flush()
                assertTrue(f.component.copyCellBounds(0, 0, bounds))
                assertEquals(0, bounds.x)
                val cellWidth = bounds.width
                f.feed("\u001B[?1049hALT")
                assertTrue(f.component.copyCellBounds(0, 0, bounds))
                assertEquals((f.component.width - columns * cellWidth) / 2, bounds.x)
                assertEquals(columns, f.component.visibleGridSize().width)
                f.feed("\u001B[?1049l")
                assertEquals(resizes, f.connector.resizes)
            }
        }

    @Test
    fun `late prompt metadata activates gutter once and clearing retains its width`() =
        edt {
            Fixture(SwingPromptDecoration.GUTTER).use { f ->
                f.prompt(">one")
                val bounds = Rectangle()
                assertTrue(f.component.copyCellBounds(0, 0, bounds))
                assertEquals(f.settings.shellIntegrationDecorationGutterWidth, bounds.x)
                assertEquals(8, f.component.visibleGridSize().width)
                assertEquals(f.state.latestCommandRecordId(), f.component.commandRecordAt(1, f.cellHeight / 2))
                val image = BufferedImage(f.component.width, f.component.height, BufferedImage.TYPE_INT_ARGB)
                val graphics = image.createGraphics()
                try {
                    f.component.paint(graphics)
                } finally {
                    graphics.dispose()
                }
                assertNotEquals(BLACK, image.getRGB(bounds.x / 2, f.cellHeight / 2))
                val resizes = f.connector.resizes.toList()
                f.state.clear()
                f.flush()
                assertTrue(f.component.copyCellBounds(0, 0, bounds))
                assertEquals(f.settings.shellIntegrationDecorationGutterWidth, bounds.x)
                assertEquals(resizes, f.connector.resizes)
                f.settings = f.settings.copy { it.promptDecoration = SwingPromptDecoration.NONE }
                f.component.reloadSettings()
                f.flush()
                assertTrue(f.component.copyCellBounds(0, 0, bounds))
                assertEquals(0, bounds.x)
                f.settings = f.settings.copy { it.promptDecoration = SwingPromptDecoration.GUTTER }
                f.component.reloadSettings()
                f.flush()
                assertTrue(f.component.copyCellBounds(0, 0, bounds))
                assertEquals(f.settings.shellIntegrationDecorationGutterWidth, bounds.x)
            }
        }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `first prompt gutter survives metadata arriving before prompt text`(startupBanner: Boolean) =
        edt {
            Fixture(SwingPromptDecoration.GUTTER).use { f ->
                if (startupBanner) f.feed("shell\r\n")
                val row = if (startupBanner) 1 else 0
                val resizes = f.connector.resizes.toList()
                var promptLineId = 0L
                f.session.readRenderFrame { frame ->
                    promptLineId = frame.lineId(frame.cursor.row)
                    f.state.recordPromptStart(promptLineId)
                }
                f.flush()
                assertEquals(resizes, f.connector.resizes)
                f.session.readRenderFrame { assertEquals(promptLineId, it.lineId(row)) }
                f.feed(">first")
                f.session.readRenderFrame { assertEquals(promptLineId, it.lineId(row)) }
                val bounds = Rectangle()
                assertTrue(f.component.copyCellBounds(0, row, bounds))
                assertEquals(f.settings.shellIntegrationDecorationGutterWidth, bounds.x)
                assertEquals(f.state.latestCommandRecordId(), f.component.commandRecordAt(1, bounds.y + f.cellHeight / 2))
                val image = BufferedImage(f.component.width, f.component.height, BufferedImage.TYPE_INT_ARGB)
                val graphics = image.createGraphics()
                try {
                    f.component.paint(graphics)
                } finally {
                    graphics.dispose()
                }
                assertNotEquals(BLACK, image.getRGB(bounds.x / 2, bounds.y + f.cellHeight / 2))
            }
        }

    @Test
    fun `rebinding resets gutter availability for a session without integration`() =
        edt {
            Fixture(SwingPromptDecoration.GUTTER).use { f ->
                f.prompt(">one")
                val worker = StandardTestDispatcher()
                val replacement =
                    TerminalSession.create(
                        TerminalBuffers.create(8, 3),
                        Connector(),
                        workerDispatcher = worker,
                        ioDispatcher = worker,
                        cleanupDispatcher = worker,
                    )
                try {
                    replacement.start(8, 3)
                    f.component.bind(replacement)
                    worker.scheduler.runCurrent()
                    f.flush()
                    val bounds = Rectangle()
                    assertTrue(f.component.copyCellBounds(0, 0, bounds))
                    assertEquals(0, bounds.x)
                    assertTrue(f.component.visibleGridSize().width > 8)
                } finally {
                    replacement.close()
                    worker.scheduler.runCurrent()
                    f.flush()
                }
            }
        }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `alternate screen centers the existing grid with shared paint and mouse offsets`(metadataAvailable: Boolean) =
        edt {
            Fixture(SwingPromptDecoration.GUTTER).use { f ->
                if (metadataAvailable) f.prompt(">one")
                f.component.setSize(f.component.width + 3, f.component.height + 7)
                f.component.componentListeners.forEach {
                    it.componentResized(ComponentEvent(f.component, ComponentEvent.COMPONENT_RESIZED))
                }
                f.flush()
                var columns = 0
                var rows = 0
                f.session.readRenderFrame {
                    columns = it.columns
                    rows = it.rows
                }
                val resizes = f.connector.resizes.toList()
                f.feed("\u001B[?1049h\u001B[41m \u001B[0m\u001B[?1000h\u001B[?1006h")
                val first = Rectangle()
                val last = Rectangle()
                assertTrue(f.component.copyCellBounds(0, 0, first))
                assertTrue(f.component.copyCellBounds(columns - 1, rows - 1, last))
                assertEquals((f.component.width - columns * first.width) / 2, first.x)
                assertEquals((f.component.height - rows * first.height) / 2, first.y)
                assertTrue(kotlin.math.abs(first.x - (f.component.width - last.x - last.width)) <= 1)
                assertTrue(kotlin.math.abs(first.y - (f.component.height - last.y - last.height)) <= 1)
                f.session.readRenderFrame {
                    assertEquals(columns, it.columns)
                    assertEquals(rows, it.rows)
                }
                val image = BufferedImage(f.component.width, f.component.height, BufferedImage.TYPE_INT_ARGB)
                val graphics = image.createGraphics()
                try {
                    f.component.paint(graphics)
                } finally {
                    graphics.dispose()
                }
                assertNotEquals(BLACK, image.getRGB(first.x + 1, first.y + 1))
                assertEquals(BLACK, image.getRGB(0, 0))
                f.connector.writes.clear()
                f.mouse(MouseEvent.MOUSE_PRESSED, first.x + 1, first.y + 1)
                f.mouse(MouseEvent.MOUSE_RELEASED, first.x + 1, first.y + 1)
                f.flush()
                assertEquals("\u001B[<0;1;1M\u001B[<0;1;1m", f.connector.writes.joinToString(""))
                f.feed("\u001B[?1049l")
                assertEquals(resizes, f.connector.resizes)
                assertTrue(f.component.copyCellBounds(0, 0, first))
                assertEquals(if (metadataAvailable) f.settings.shellIntegrationDecorationGutterWidth else 0, first.x)
                assertEquals(0, first.y)
            }
        }

    private fun edt(block: () -> Unit) {
        SwingUtilities.invokeAndWait(block)
    }

    @Test
    fun `switching decoration modes preserves the viewed history line`() =
        edt {
            Fixture().use { f ->
                repeat(8) { index ->
                    f.prompt(">$index")
                    f.feed("\r\n")
                }
                f.component.scrollFromScrollbar(8, true)
                f.flush()
                val nativeOffset = f.component.viewportState().renderOffset
                assertTrue(nativeOffset > 0)
                f.settings = f.settings.copy { it.promptDecoration = SwingPromptDecoration.NONE }
                f.component.reloadSettings()
                f.flush()
                assertEquals(nativeOffset, f.component.viewportState().renderOffset)
                f.settings = f.settings.copy { it.promptDecoration = SwingPromptDecoration.DIVIDER }
                f.component.reloadSettings()
                f.flush()
                assertEquals(nativeOffset, f.component.viewportState().renderOffset)
            }
        }

    @Test
    fun `resizing a scrolled divider viewport preserves the viewed line identity`() =
        edt {
            Fixture().use { f ->
                repeat(8) { index ->
                    f.prompt(">$index")
                    f.feed("\r\n")
                }
                f.component.scrollFromScrollbar(8, true)
                f.flush()
                val offset = f.component.viewportState().renderOffset
                var lineId = 0L
                f.session.readRenderFrame(offset, 3) { lineId = it.lineId(0) }
                f.resize(8, 4)
                f.flush()
                f.session.readRenderFrame(f.component.viewportState().renderOffset, 4) {
                    assertEquals(lineId, it.lineId(0))
                }
            }
        }

    @ParameterizedTest
    @EnumSource(SwingPromptDecoration::class)
    fun `narrowing a scrolled viewport preserves the viewed line identity`(mode: SwingPromptDecoration) =
        edt {
            Fixture(mode).use { f ->
                repeat(8) { index ->
                    f.prompt(">$index abc")
                    f.feed("\r\n")
                }
                f.component.scrollFromScrollbar(8, true)
                f.flush()
                val offset = f.component.viewportState().renderOffset
                var lineId = 0L
                f.session.readRenderFrame(offset, 3) { lineId = it.lineId(0) }
                assertTrue(offset > 0)
                for ((columns, rows) in listOf(4 to 3, 8 to 3, 5 to 2, 8 to 4)) {
                    f.resize(columns, rows)
                    f.flush()
                    assertTrue(f.component.viewportState().renderOffset > 0, "mode=$mode grid=$columns,$rows")
                    f.session.readRenderFrame(f.component.viewportState().renderOffset, rows) {
                        assertEquals(lineId, it.lineId(0), "mode=$mode grid=$columns,$rows")
                    }
                }
                // Real output after resize must still honor the default scroll-on-output policy.
                f.feed("new output\r\nnext output\r\n")
                assertEquals(0, f.component.viewportState().renderOffset)
            }
        }

    @ParameterizedTest
    @CsvSource("NONE,false", "GUTTER,false", "DIVIDER,false", "NONE,true", "GUTTER,true", "DIVIDER,true")
    fun `resize bursts preserve the viewed line when an earlier reflow awaits installation`(
        mode: SwingPromptDecoration,
        admitFirstResize: Boolean,
    ) = edt {
        Fixture(mode).use { f ->
            repeat(8) { index ->
                f.prompt(">$index abc")
                f.feed("\r\n")
            }
            f.component.scrollFromScrollbar(8, true)
            f.flush()
            val originalOffset = f.component.viewportState().renderOffset
            assertTrue(originalOffset > 0)
            var originalLine = 0L
            f.session.readRenderFrame(originalOffset, 3) { originalLine = it.lineId(0) }

            f.resize(4, 3)
            if (admitFirstResize) {
                // Reflow has committed, but the view still uses the pre-resize frame and anchor.
                f.runWorkerBeforeUiInstallation()
                f.session.readRenderFrame { assertEquals(4, it.columns) }
            }
            f.resize(8, 3)
            f.flush()

            val finalOffset = f.component.viewportState().renderOffset
            assertTrue(finalOffset > 0)
            f.session.readRenderFrame(finalOffset, 3) {
                assertEquals(8, it.columns)
                assertEquals(originalLine, it.lineId(0))
            }
        }
    }

    @ParameterizedTest
    @EnumSource(SwingPromptDecoration::class)
    fun `scrolling after reflow overrides the pending resize anchor before completion`(mode: SwingPromptDecoration) =
        edt {
            Fixture(mode).use { f ->
                repeat(8) { index ->
                    f.prompt(">$index abc")
                    f.feed("\r\n")
                }
                f.component.scrollFromScrollbar(8, true)
                f.flush()
                var originalLine = 0L
                f.session.readRenderFrame(f.component.viewportState().renderOffset, 3) { originalLine = it.lineId(0) }
                var laterLine = 0L
                var completions = 0
                var completionFailure: Throwable? = null
                var completionLine = 0L
                var completionColumns = 0

                f.component.size = f.component.preferredGridSize(4, 3)
                f.component.resizeSessionToVisibleGrid { failure ->
                    completionFailure = failure
                    completions++
                    f.session.readRenderFrame(f.component.viewportState().renderOffset, 3) {
                        completionColumns = it.columns
                        completionLine = it.lineId(0)
                    }
                }
                f.runWorkerBeforeUiInstallation()
                f.session.readRenderFrame { assertEquals(4, it.columns) }
                assertEquals(0, completions, "The resize result still awaits EDT installation")

                f.component.scrollFromScrollbar(2, true)
                f.session.readRenderFrame(f.component.viewportState().renderOffset, 3) { laterLine = it.lineId(0) }
                assertNotEquals(originalLine, laterLine)
                f.flush()

                assertEquals(1, completions)
                assertNull(completionFailure)
                assertEquals(4, completionColumns)
                assertEquals(laterLine, completionLine, "Completion must follow the later scroll's reconciliation")
                f.session.readRenderFrame(f.component.viewportState().renderOffset, 3) { assertEquals(laterLine, it.lineId(0)) }
                f.flush()
                assertEquals(1, completions)
            }
        }

    @Test
    fun `viewport notification failure reaches the resize completion before anchor retry ownership transfers`() =
        edt {
            val expectedFailure = IllegalStateException("Viewport notification failed")
            var notificationsUntilFailure = 0
            val listener =
                TerminalViewportListener { _, _, _, _, _ ->
                    if (notificationsUntilFailure > 0 && --notificationsUntilFailure == 0) throw expectedFailure
                }
            Fixture(SwingPromptDecoration.NONE, viewportListener = listener).use { f ->
                repeat(8) { index ->
                    f.prompt(">$index abc")
                    f.feed("\r\n")
                }
                f.component.scrollFromScrollbar(8, true)
                f.flush()
                var completions = 0
                var completionFailure: Throwable? = null
                f.component.size = f.component.preferredGridSize(4, 3)
                f.component.resizeSessionToVisibleGrid {
                    completions++
                    completionFailure = it
                }
                f.runWorkerBeforeUiInstallation()
                assertEquals(0, completions)
                f.component.scrollFromScrollbar(2, true)
                // Exercise both the original installer notification and the retry's capture notification.
                notificationsUntilFailure = 2
                f.flush()

                assertEquals(0, notificationsUntilFailure)
                assertEquals(1, completions)
                assertSame(expectedFailure, completionFailure)
                var nextCompletions = 0
                var nextFailure: Throwable? = null
                f.component.size = f.component.preferredGridSize(8, 3)
                f.component.resizeSessionToVisibleGrid {
                    nextCompletions++
                    nextFailure = it
                }
                f.flush()
                assertEquals(1, completions)
                assertEquals(1, nextCompletions)
                assertNull(nextFailure)
                f.session.readRenderFrame { assertEquals(8, it.columns) }
            }
        }

    @ParameterizedTest
    @EnumSource(SwingPromptDecoration::class)
    fun `closure before anchor retry admission releases the retained frame`(mode: SwingPromptDecoration) =
        edt {
            Fixture(mode).use { f ->
                repeat(8) { index ->
                    f.prompt(">$index abc")
                    f.feed("\r\n")
                }
                f.component.scrollFromScrollbar(8, true)
                f.flush()
                val previousHistory = f.component.viewportState().historySize
                var completions = 0
                var completionFailure: Throwable? = null
                f.component.size = f.component.preferredGridSize(4, 3)
                f.component.resizeSessionToVisibleGrid {
                    completions++
                    completionFailure = it
                }
                f.runWorkerBeforeUiInstallation()
                f.component.scrollFromScrollbar(2, true)
                f.runUiInstallations()
                assertEquals(0, completions, "The accepted anchor retry has not entered session admission")
                val resizes = f.connector.resizes.toList()
                val finalOutput = ("\r\n".repeat(20) + "DONE").encodeToByteArray()
                f.session.onBytes(finalOutput, 0, finalOutput.size)
                f.session.close()
                f.flush()

                assertTrue(f.session.state.value is TerminalSessionState.Closed)
                assertEquals(1, completions)
                assertTrue(completionFailure is CancellationException)
                assertEquals(resizes, f.connector.resizes)
                assertTrue(
                    f.component.viewportState().historySize > previousHistory,
                    "The final retained frame must replace pre-resize presentation",
                )
                f.component.scrollToLiveViewport()
                f.flush()
                assertEquals(0, f.component.viewportState().renderOffset)
            }
        }

    private class Fixture(
        mode: SwingPromptDecoration = SwingPromptDecoration.DIVIDER,
        shellIntegration: TerminalShellIntegrationFactory? = null,
        viewportListener: TerminalViewportListener = TerminalViewportListener.NONE,
    ) : AutoCloseable {
        private val worker = StandardTestDispatcher()
        private val dispatches = LinkedBlockingQueue<Runnable>()
        val state = TerminalShellIntegrationState()
        val connector = Connector()
        val session =
            TerminalSession.create(
                TerminalBuffers.create(8, 3, 100),
                connector,
                workerDispatcher = worker,
                ioDispatcher = worker,
                cleanupDispatcher = worker,
                shellIntegration = shellIntegration ?: TerminalShellIntegrationFactory.host(state),
            )
        var settings =
            SwingSettings.create {
                it.padding = SwingPadding(0, 0, 0, 0)
                it.promptDecoration = mode
                it.cursorBlinkMillis = 0
                it.useSystemFallbackFonts = false
                it.palette = TerminalColorPalette(defaultForeground = WHITE, defaultBackground = BLACK)
            }
        var clipboard = ""
        val component =
            SwingTerminal(
                { settings },
                SwingHostServices.create {
                    it.uiDispatcher = { action -> dispatches += action }
                    it.viewportListener = viewportListener
                    it.clipboardHandler =
                        object : TerminalClipboardHandler {
                            override fun copyText(text: String) {
                                clipboard = text
                            }

                            override fun readText(): String? = null
                        }
                },
                searchDispatcher = worker,
                hyperlinkDispatcher = worker,
                mutationDispatcher = worker,
            )
        val cellHeight get() = (component.preferredGridSize(8, 3).height / 3)

        init {
            session.start(8, 3)
            component.size = component.preferredGridSize(8, 3)
            component.bind(session)
            flush()
        }

        fun prompt(text: String) {
            feed(text)
            session.readRenderFrame { frame -> state.recordPromptStart(frame.lineId(frame.cursor.row)) }
            flush()
        }

        fun feed(text: String) {
            val bytes = text.toByteArray()
            session.onBytes(bytes, 0, bytes.size)
            session.requestRender(component.viewportState().renderOffset, component.viewportState().requestedRows)
            flush()
        }

        fun mouse(
            id: Int,
            x: Int,
            y: Int,
        ) {
            val event = MouseEvent(component, id, 0, 0, x, y, 1, false, MouseEvent.BUTTON1)
            when (id) {
                MouseEvent.MOUSE_PRESSED -> component.mouseListeners.forEach { it.mousePressed(event) }
                MouseEvent.MOUSE_RELEASED -> component.mouseListeners.forEach { it.mouseReleased(event) }
                MouseEvent.MOUSE_DRAGGED -> component.mouseMotionListeners.forEach { it.mouseDragged(event) }
            }
        }

        fun resize(
            columns: Int,
            rows: Int,
        ) {
            component.size = component.preferredGridSize(columns, rows)
            val event = ComponentEvent(component, ComponentEvent.COMPONENT_RESIZED)
            component.componentListeners.forEach { it.componentResized(event) }
        }

        fun runWorker() = worker.scheduler.runCurrent()

        fun runUiInstallations() {
            while (true) (dispatches.poll() ?: break).run()
        }

        /** Runs worker jobs off the EDT so their UI installations remain queued. */
        fun runWorkerBeforeUiInstallation() {
            val task = FutureTask { runWorker() }
            val thread =
                Thread
                    .ofPlatform()
                    .daemon()
                    .name("prompt-divider-worker")
                    .start(task)
            try {
                task.get(10, TimeUnit.SECONDS)
            } finally {
                if (!task.isDone) task.cancel(true)
                thread.join(TimeUnit.SECONDS.toMillis(10))
                check(!thread.isAlive) { "Controlled worker did not terminate" }
            }
        }

        fun flush() {
            do {
                runWorker()
                var dispatched = false
                while (true) {
                    val action = dispatches.poll() ?: break
                    dispatched = true
                    action.run()
                }
            } while (dispatched)
        }

        override fun close() {
            component.dispose()
            session.close()
            flush()
        }
    }

    private class Connector : TerminalConnector {
        val resizes = mutableListOf<Pair<Int, Int>>()
        val writes = mutableListOf<String>()

        override fun start(listener: TerminalConnectorListener) = Unit

        override fun write(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) {
            writes += String(bytes, offset, length)
        }

        override fun resize(
            columns: Int,
            rows: Int,
        ) {
            resizes += columns to rows
        }

        override fun close() = Unit
    }

    private companion object {
        const val BLACK = -0x1000000
        const val WHITE = -1
    }
}
