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
import org.junit.jupiter.params.provider.ValueSource
import java.awt.Rectangle
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SwingTerminalCellBoundsTest {
    @Test
    fun `unavailable geometry clears caller bounds and rejects off EDT access`() {
        val destination = Rectangle(1, 2, 3, 4)
        lateinit var terminal: SwingTerminal
        SwingUtilities.invokeAndWait {
            terminal = SwingTerminal()
            assertFalse(terminal.copyCellBounds(0, 0, destination))
            assertEquals(Rectangle(), destination)
            terminal.dispose()
            assertFalse(terminal.copyCellBounds(0, 0, destination))
        }
        assertFailsWith<IllegalStateException> { terminal.copyCellBounds(0, 0, destination) }
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
                val left = if (alternate) 4 else 16
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
                for ((column, row) in listOf(-1 to 0, 0 to -1, 10 to 0, 0 to 3, Int.MAX_VALUE to Int.MAX_VALUE)) {
                    assertFalse(terminal.copyCellBounds(column, row, bounds))
                    assertEquals(Rectangle(), bounds)
                }
                terminal.setSize(left + cellWidth / 2 + right, top + cellHeight / 2 + bottom)
                assertTrue(terminal.copyCellBounds(0, 0, bounds))
                assertEquals(Rectangle(left, top, cellWidth / 2, cellHeight / 2), bounds)
                assertFalse(terminal.copyCellBounds(1, 0, bounds))
                terminal.unbind()
                assertFalse(terminal.copyCellBounds(0, 0, bounds))
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
