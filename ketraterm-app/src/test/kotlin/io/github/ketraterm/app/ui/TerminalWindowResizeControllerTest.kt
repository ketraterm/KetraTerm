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
package io.github.ketraterm.app.ui

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.transport.TerminalConnectorListener
import io.github.ketraterm.ui.swing.api.SwingTerminal
import io.github.ketraterm.ui.swing.settings.SwingSettings
import org.junit.jupiter.api.Test
import java.awt.Font
import java.awt.Rectangle
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalWindowResizeControllerTest {
    @Test
    fun `requests resize only the eligible visible session and stop after disposal`() {
        withWindow { window, terminal, session, controller, updates ->
            TerminalSession.create(TerminalBuffers.create(80, 10), NoopConnector).use { background ->
                assertFalse(controller.request(background, 10, 80))
            }
            onEdt { controller.clearTarget() }
            assertFalse(controller.request(session, 10, 80))
            assertTrue(updates.isEmpty())
            onEdt { controller.setTarget(session, terminal) }
            for (columns in listOf(132, 80)) {
                assertTrue(controller.request(session, 10, columns))
                assertEquals(1, updates.size)
                onEdt {
                    updates.removeFirst().invoke()
                    assertEquals(Rectangle(0, 0, columns * 8 + 20, 200), window.bounds.last())
                }
            }
            onEdt {
                controller.close()
                controller.setTarget(session, terminal)
            }
            assertFalse(controller.request(session, 10, 132))
            assertEquals(1, window.observationClosed)
            assertTrue(updates.isEmpty())
        }
    }

    @Test
    fun `column round trip grows back by moving within the work area`() {
        withWindow { window, _, session, controller, updates ->
            onEdt {
                window.geometry = checkNotNull(window.geometry).copy(windowX = 800, windowY = 100)
                window.refresh()
                assertTrue(controller.request(session, 10, 80, preserveGrid = true))
                updates.removeFirst().invoke()
                assertEquals(Rectangle(800, 100, 660, 200), window.bounds.last())
                assertTrue(controller.request(session, 10, 132, preserveGrid = true))
                updates.removeFirst().invoke()
                assertEquals(Rectangle(524, 100, 1076, 200), window.bounds.last())
                assertTrue(controller.request(session, 10, 80, preserveGrid = true))
                updates.removeFirst().invoke()
                assertEquals(Rectangle(524, 100, 660, 200), window.bounds.last())
            }
        }
    }

    @Test
    fun `queued resize uses the current monitor work area and position`() {
        withWindow { window, _, session, controller, updates ->
            onEdt {
                assertTrue(controller.request(session, 10, 132, preserveGrid = true))
                window.geometry =
                    checkNotNull(window.geometry).copy(
                        windowX = -700,
                        windowY = 10,
                        availableX = -1600,
                        availableY = 30,
                    )
                updates.removeFirst().invoke()
                assertEquals(listOf(Rectangle(-1076, 30, 1076, 200)), window.bounds)
            }
        }
    }

    @Test
    fun `ineligible geometry rejects requests and layout changes cancel queued window updates`() {
        withWindow { window, _, session, controller, updates ->
            val geometry = window.geometry
            onEdt {
                window.geometry = null
                window.refresh()
            }
            assertFalse(controller.request(session, 10, 132))
            assertTrue(updates.isEmpty())
            onEdt {
                window.geometry = geometry
                window.refresh()
                assertTrue(controller.request(session, 10, 132))
                // A split or tab selection changes eligibility before the queued update.
                controller.clearTarget()
                updates.removeFirst().invoke()
                assertTrue(window.bounds.isEmpty())
            }
        }
    }

    @Test
    fun `rejected queued resize restores the session to its actual visible grid`() {
        withWindow { window, terminal, session, controller, updates ->
            onEdt {
                val visible = terminal.visibleGridSize()
                assertTrue(controller.request(session, 10, 132))
                session.resize(132, 10)
                window.geometry = null
                updates.removeFirst().invoke()
                assertTrue(window.bounds.isEmpty())
                assertEquals(visible.width, session.terminal.width)
                assertEquals(visible.height, session.terminal.height)
            }
        }
    }

    @Test
    fun `cancelled window resize preserves a logical column switch`() {
        withWindow { window, _, session, controller, updates ->
            onEdt {
                assertTrue(controller.request(session, 10, 132, preserveGrid = true))
                session.resize(132, 10)
                controller.clearTarget()
                updates.removeFirst().invoke()
                assertTrue(window.bounds.isEmpty())
                assertEquals(132, session.terminal.width)
                assertEquals(10, session.terminal.height)
            }
        }
    }

    @Test
    fun `geometry refresh keeps the previous complete snapshot available to requests`() {
        withWindow { window, _, session, controller, updates ->
            onEdt {
                window.beforeRead = { assertTrue(controller.request(session, 10, 132)) }
                window.refresh()
                window.beforeRead = {}
                assertEquals(1, updates.size)
                updates.removeFirst().invoke()
                assertEquals(listOf(Rectangle(0, 0, 1076, 200)), window.bounds)
            }
        }
    }

    @Test
    fun `closing the controller or session cancels accepted window updates`() {
        withWindow { window, _, session, controller, updates ->
            assertTrue(controller.request(session, 10, 132))
            onEdt {
                controller.close()
                updates.removeFirst().invoke()
                assertTrue(window.bounds.isEmpty())
            }
        }
        withWindow { window, _, session, controller, updates ->
            assertTrue(controller.request(session, 10, 132))
            session.close()
            onEdt {
                updates.removeFirst().invoke()
                assertTrue(window.bounds.isEmpty())
            }
        }
    }

    private fun withWindow(
        action: (TestWindow, SwingTerminal, TerminalSession, TerminalWindowResizeController, ArrayDeque<() -> Unit>) -> Unit,
    ) {
        val session = TerminalSession.create(TerminalBuffers.create(80, 10), NoopConnector)
        val window = TestWindow()
        val updates = ArrayDeque<() -> Unit>()
        var ownedTerminal: SwingTerminal? = null
        var ownedController: TerminalWindowResizeController? = null
        try {
            onEdt {
                val terminal = SwingTerminal(settingsProvider = { SwingSettings(font = Font(Font.MONOSPACED, Font.PLAIN, 10)) })
                ownedTerminal = terminal
                terminal.bind(session)
                terminal.setSize(640, 160)
                val controller = TerminalWindowResizeController(window, updates::addLast)
                ownedController = controller
                controller.setTarget(session, terminal)
            }
            action(window, requireNotNull(ownedTerminal), session, requireNotNull(ownedController), updates)
        } finally {
            onEdt {
                ownedController?.close()
                ownedTerminal?.dispose()
            }
            session.close()
        }
    }

    private class TestWindow : WindowResizeHost {
        var geometry: WindowResizeGeometry? =
            WindowResizeGeometry(
                cellWidth = 8,
                cellHeight = 16,
                windowExtraWidth = 20,
                windowExtraHeight = 40,
                primaryInsetWidth = 0,
                primaryInsetHeight = 0,
                alternateInsetWidth = 0,
                alternateInsetHeight = 0,
                minimumWidth = 100,
                minimumHeight = 100,
                availableWidth = 1600,
                availableHeight = 1000,
                windowX = 0,
                windowY = 0,
                availableX = 0,
                availableY = 0,
            )
        val bounds = mutableListOf<Rectangle>()
        var beforeRead: () -> Unit = {}
        var refresh: () -> Unit = {}
        var observationClosed = 0

        override fun readGeometry(terminal: SwingTerminal): WindowResizeGeometry? {
            check(SwingUtilities.isEventDispatchThread())
            beforeRead()
            return geometry
        }

        override fun resize(bounds: Rectangle) {
            check(SwingUtilities.isEventDispatchThread())
            this.bounds.add(bounds)
            geometry = geometry?.copy(windowX = bounds.x, windowY = bounds.y)
            refresh()
        }

        override fun observeGeometryChanges(refresh: () -> Unit): AutoCloseable {
            check(SwingUtilities.isEventDispatchThread())
            this.refresh = refresh
            return AutoCloseable {
                check(SwingUtilities.isEventDispatchThread())
                this.refresh = {}
                observationClosed++
            }
        }
    }

    private fun <T> onEdt(action: () -> T): T {
        val task = FutureTask(action)
        SwingUtilities.invokeLater(task)
        return task.get(10, TimeUnit.SECONDS)
    }

    private object NoopConnector : TerminalConnector {
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
