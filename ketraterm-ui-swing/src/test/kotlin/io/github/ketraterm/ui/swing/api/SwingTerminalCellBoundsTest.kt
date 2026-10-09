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
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.transport.TerminalConnectorListener
import io.github.ketraterm.ui.swing.settings.SwingPadding
import io.github.ketraterm.ui.swing.settings.SwingSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.awt.Point
import java.awt.Rectangle
import java.awt.image.BufferedImage
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SwingTerminalCellBoundsTest {
    @ParameterizedTest
    @CsvSource("1.0, 3", "1.25, 3", "1.5, 3", "1.75, 3", "2.0, 3", "1.0, -1", "1.25, -1", "1.5, -1", "1.75, -1", "2.0, -1")
    fun `hit testing uses component pixels through fractional paint scales and font metrics`(
        scale: Double,
        columnSpacing: Int,
    ) {
        SwingUtilities.invokeAndWait {
            val buffer = TerminalBuffers.create(7, 2)
            buffer.writeText("Aאב界")
            val dispatcher = StandardTestDispatcher()
            val session = TerminalSession.create(buffer, Connector, workerDispatcher = dispatcher, ioDispatcher = dispatcher)
            val settings =
                SwingSettings.create {
                    it.font = it.font.deriveFont(13.25f)
                    it.columnSpacing = columnSpacing
                    it.padding = SwingPadding(3, 5, 7, 9)
                    it.useSystemFallbackFonts = false
                    it.cursorBlinkMillis = 0
                }
            val terminal = SwingTerminal(settingsProvider = { settings })
            try {
                terminal.size = terminal.preferredGridSize(7, 2)
                session.requestRender(0)
                dispatcher.scheduler.runCurrent()
                terminal.bind(session)
                val image =
                    BufferedImage(
                        kotlin.math.ceil(terminal.width * scale).toInt(),
                        kotlin.math.ceil(terminal.height * scale).toInt(),
                        BufferedImage.TYPE_INT_ARGB,
                    )
                val graphics = image.createGraphics()
                try {
                    graphics.scale(scale, scale)
                    terminal.paint(graphics)
                } finally {
                    graphics.dispose()
                }
                val bounds = Rectangle()
                val destination = Point()
                for (row in 0 until 2) {
                    for (column in 0 until 7) {
                        assertTrue(terminal.copyCellBounds(column, row, bounds))
                        assertTrue(terminal.copyCellPositionAt(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2, destination))
                        assertEquals(Point(column, row), destination)
                    }
                }
            } finally {
                terminal.dispose()
                session.close()
            }
        }
    }

    @Test
    fun `unavailable geometry clears caller bounds and rejects off EDT access`() {
        val destination = Rectangle(1, 2, 3, 4)
        val cell = Point(7, 8)
        lateinit var terminal: SwingTerminal
        SwingUtilities.invokeAndWait {
            terminal = SwingTerminal()
            assertFalse(terminal.copyCellBounds(0, 0, destination))
            assertEquals(Rectangle(), destination)
            assertFalse(terminal.copyCellPositionAt(0, 0, cell))
            assertEquals(Point(-1, -1), cell)
            terminal.dispose()
            assertFalse(terminal.copyCellBounds(0, 0, destination))
            cell.setLocation(7, 8)
            assertFalse(terminal.copyCellPositionAt(0, 0, cell))
            assertEquals(Point(-1, -1), cell)
        }
        assertFailsWith<IllegalStateException> { terminal.copyCellBounds(0, 0, destination) }
        assertFailsWith<IllegalStateException> { terminal.copyCellPositionAt(0, 0, cell) }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `component bounds follow active padding gutter and resize clipping`(alternate: Boolean) {
        SwingUtilities.invokeAndWait {
            val buffer = TerminalBuffers.create(10, 3)
            if (alternate) buffer.enterAltBuffer()
            val dispatcher = StandardTestDispatcher()
            val session = TerminalSession.create(buffer, Connector, workerDispatcher = dispatcher, ioDispatcher = dispatcher)
            val settings =
                SwingSettings.create { draft ->
                    draft.padding = SwingPadding(5, 7, 11, 13)
                    draft.alternateScreenPadding = SwingPadding(3, 4, 8, 10)
                    draft.shellIntegrationDecorationGutterWidth = 9
                }
            val terminal = SwingTerminal(settingsProvider = { settings })
            try {
                terminal.size = terminal.preferredGridSize(10, 3)
                session.requestRender(0)
                dispatcher.scheduler.runCurrent()
                terminal.bind(session)
                val left = if (alternate) 4 else 7
                val top = if (alternate) 3 else 5
                val right = if (alternate) 10 else 13
                val bottom = if (alternate) 8 else 11
                val bounds = Rectangle()
                assertTrue(terminal.copyCellBounds(0, 0, bounds))
                assertEquals(left, bounds.x)
                assertEquals(top, bounds.y)
                val cellWidth = bounds.width
                val cellHeight = bounds.height
                assertTrue(terminal.copyCellBounds(2, 1, bounds))
                assertEquals(Rectangle(left + 2 * cellWidth, top + cellHeight, cellWidth, cellHeight), bounds)
                val cell = Point()
                assertTrue(terminal.copyCellPositionAt(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2, cell))
                assertEquals(Point(2, 1), cell)
                for ((x, y) in listOf(
                    left - 1 to top,
                    left to top - 1,
                    terminal.width - right to top,
                    left to terminal.height - bottom,
                    Int.MIN_VALUE to Int.MIN_VALUE,
                    Int.MAX_VALUE to Int.MAX_VALUE,
                )) {
                    cell.setLocation(7, 8)
                    assertFalse(terminal.copyCellPositionAt(x, y, cell))
                    assertEquals(Point(-1, -1), cell)
                }
                for ((column, row) in listOf(
                    -1 to 0,
                    0 to -1,
                    terminal.visibleGridSize().width to 0,
                    0 to 3,
                    Int.MAX_VALUE to Int.MAX_VALUE,
                )) {
                    assertFalse(terminal.copyCellBounds(column, row, bounds))
                    assertEquals(Rectangle(), bounds)
                }
                terminal.setSize(left + cellWidth / 2 + right, top + cellHeight / 2 + bottom)
                assertTrue(terminal.copyCellBounds(0, 0, bounds))
                assertEquals(Rectangle(left, top, cellWidth / 2, cellHeight / 2), bounds)
                assertTrue(terminal.copyCellPositionAt(bounds.x + bounds.width - 1, bounds.y + bounds.height - 1, cell))
                assertEquals(Point(0, 0), cell)
                assertFalse(terminal.copyCellBounds(1, 0, bounds))
                terminal.unbind()
                assertFalse(terminal.copyCellBounds(0, 0, bounds))
                assertFalse(terminal.copyCellPositionAt(left, top, cell))
                assertEquals(Point(-1, -1), cell)
            } finally {
                terminal.dispose()
                session.close()
            }
        }
    }

    private object Connector : TerminalConnector {
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
