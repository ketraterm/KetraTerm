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
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import java.awt.Font
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalWindowResizeControllerTest {
    @Test
    fun `requests resize only the eligible visible session and stop after disposal`() {
        withWindow { frame, terminal, session, controller ->
            TerminalSession.create(TerminalBuffers.create(80, 10), NoopConnector).use { background ->
                assertFalse(controller.request(background, 10, 80))
            }
            val original =
                onEdt {
                    controller.clearTarget()
                    frame.size
                }
            assertFalse(controller.request(session, 10, 80))
            assertEquals(original, onEdt { frame.size })
            onEdt { controller.setTarget(session, terminal) }
            for (columns in listOf(132, 80)) {
                assertTrue(controller.request(session, 10, columns))
                onEdt {
                    val visible = terminal.visibleGridSize()
                    assertEquals(columns, visible.width)
                    assertEquals(10, visible.height)
                }
            }
            onEdt {
                controller.close()
                controller.setTarget(session, terminal)
            }
            assertFalse(controller.request(session, 10, 132))
        }
    }

    @Test
    fun `non-normal windows reject requests and layout changes cancel queued window updates`() {
        withWindow { frame, terminal, session, controller ->
            for (state in listOf(Frame.MAXIMIZED_BOTH, Frame.MAXIMIZED_HORIZ, Frame.MAXIMIZED_VERT, Frame.ICONIFIED)) {
                onEdt {
                    frame.reportedState = state
                    controller.setTarget(session, terminal)
                }
                assertFalse(controller.request(session, 10, 132))
            }
            val original =
                onEdt {
                    frame.reportedState = Frame.NORMAL
                    controller.setTarget(session, terminal)
                    val original = frame.size
                    assertTrue(controller.request(session, 10, 132))
                    // A split or tab selection changes eligibility before the queued update.
                    controller.clearTarget()
                    original
                }
            onEdt {
                assertEquals(original, frame.size)
                assertEquals(terminal.visibleGridSize().width, session.terminal.width)
            }
        }
    }

    private fun withWindow(action: (TestFrame, SwingTerminal, TerminalSession, TerminalWindowResizeController) -> Unit) {
        assumeFalse(GraphicsEnvironment.isHeadless())
        val session = TerminalSession.create(TerminalBuffers.create(80, 10), NoopConnector)
        var ownedTerminal: SwingTerminal? = null
        var ownedFrame: TestFrame? = null
        var ownedController: TerminalWindowResizeController? = null
        try {
            onEdt {
                val terminal = SwingTerminal(settingsProvider = { SwingSettings(font = Font(Font.MONOSPACED, Font.PLAIN, 10)) })
                ownedTerminal = terminal
                terminal.bind(session)
                val frame = TestFrame()
                ownedFrame = frame
                frame.contentPane.add(terminal)
                frame.setSize(600, 300)
                val bounds = frame.graphicsConfiguration.bounds
                val insets = frame.toolkit.getScreenInsets(frame.graphicsConfiguration)
                frame.setLocation(bounds.x + insets.left, bounds.y + insets.top)
                val controller = TerminalWindowResizeController(frame)
                ownedController = controller
                frame.isVisible = true
                controller.setTarget(session, terminal)
            }
            action(requireNotNull(ownedFrame), requireNotNull(ownedTerminal), session, requireNotNull(ownedController))
        } finally {
            onEdt {
                ownedController?.close()
                ownedTerminal?.dispose()
                ownedFrame?.dispose()
            }
            session.close()
        }
    }

    private class TestFrame : JFrame() {
        var reportedState = NORMAL

        override fun getExtendedState(): Int = reportedState
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
