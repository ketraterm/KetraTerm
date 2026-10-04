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
import io.github.ketraterm.render.api.TerminalRenderBufferKind
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.session.TerminalSessionState
import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.transport.TerminalConnectorListener
import io.github.ketraterm.ui.swing.settings.SwingPadding
import io.github.ketraterm.ui.swing.settings.SwingSettings
import io.github.ketraterm.ui.swing.settings.TerminalClipboardHandler
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.awt.Rectangle
import java.awt.event.ComponentEvent
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import kotlin.concurrent.thread

class SwingTerminalClosedSessionTest {
    private val dispatcher = StandardTestDispatcher()

    @Test
    fun `binding after remote exit retains output without resizing transport`() {
        val connector = RecordingConnector()
        val session = session(connector)
        "first\r\nsecond\r\nthird\r\nlast".toByteArray().let { session.onBytes(it, 0, it.size) }
        session.onClosed(0)
        val calls = connector.resizes.toList()
        edt {
            val component = SwingTerminal()
            try {
                component.size = component.preferredGridSize(6, 2)
                component.bind(session)
                assertTrue(component.selectAll())
                assertNotNull(component.currentSelection())
                assertEquals(calls, connector.resizes)
                assertEquals(1, connector.starts)
                assertEquals(1, connector.closes)
                assertTrue(component.hasActiveRenderBinding)
            } finally {
                component.dispose()
            }
            assertFalse(component.hasActiveRenderBinding)
        }
        dispatcher.scheduler.runCurrent()
    }

    @Test
    fun `resize while connector cleanup is blocked preserves the retained grid`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val connector =
            RecordingConnector {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
        val session = session(connector)
        val component =
            edt {
                SwingTerminal().also {
                    it.size = it.preferredGridSize(10, 3)
                    it.bind(session)
                }
            }
        "final".toByteArray().let { session.onBytes(it, 0, it.size) }
        val close = FutureTask { session.close() }
        thread(name = "closed-session-test") { close.run() }
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            assertTrue(session.isClosed)
            assertSame(TerminalSessionState.Running, session.state.value)
            edt {
                component.size = component.preferredGridSize(6, 2)
                component.componentListeners.forEach {
                    it.componentResized(ComponentEvent(component, ComponentEvent.COMPONENT_RESIZED))
                }
            }
            session.readRenderFrame {
                assertEquals(10, it.columns)
                assertEquals(3, it.rows)
            }
            assertEquals(listOf(10 to 3, 10 to 3), connector.resizes)
        } finally {
            release.countDown()
            close.get(10, TimeUnit.SECONDS)
            edt { component.dispose() }
            dispatcher.scheduler.runCurrent()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["primary", "alternate", "restored"])
    fun `closed output supports scrolling selection font changes and rebinding`(screen: String) {
        val connector = RecordingConnector()
        val session = session(connector)
        feed(session, "oldest\r\nsecond\r\nthird\r\nlast")
        if (screen != "primary") feed(session, "\u001B[?1049h\u001B[?1000hALTONE\r\nALTTWO\r\nALTTHREE")
        if (screen == "restored") feed(session, "\u001B[?1049l")
        session.onClosed(0)
        val alternate = screen == "alternate"
        val buffer = if (alternate) TerminalRenderBufferKind.ALTERNATE else TerminalRenderBufferKind.PRIMARY
        val expected = if (alternate) "ALTONE\nALTTWO\nALTTHREE" else "oldest\nsecond\nthird\nlast"
        val generation = session.renderGeneration.value
        val resizes = connector.resizes.toList()
        val clipboard = Clipboard()
        var settings =
            SwingSettings.create {
                it.padding = SwingPadding(0, 0, 0, 0)
                it.alternateScreenPadding = SwingPadding(0, 0, 0, 0)
                it.shellIntegrationDecorationGutterWidth = 0
            }
        edt {
            val component = SwingTerminal({ settings }, SwingHostServices.create { it.clipboardHandler = clipboard })
            try {
                component.size = component.preferredGridSize(6, 1, buffer)
                component.bind(session)
                assertEquals(if (alternate) 2 else 3, component.viewportState().historySize)
                selectWord(component, clipboard, if (alternate) "ALTTHREE" else "last")

                component.scrollToScrollbackOffset(Int.MAX_VALUE)
                assertEquals(component.viewportState().historySize.toDouble(), component.viewportState().scrollbackOffset)
                selectWord(component, clipboard, if (alternate) "ALTONE" else "oldest")

                assertTrue(component.selectAll())
                assertTrue(component.copySelectionToClipboard())
                assertEquals(expected, clipboard.text.trimEnd())
                resize(component, 4, 2, buffer)
                assertTrue(component.copySelectionToClipboard())
                assertEquals(expected, clipboard.text.trimEnd())
                assertFalse(component.copyCellBounds(8, 0, Rectangle()), "narrow views clip columns")

                settings = settings.copy { it.font = it.font.deriveFont(24f) }
                component.reloadSettings()
                assertTrue(component.copySelectionToClipboard())
                assertEquals(expected, clipboard.text.trimEnd())
                resize(component, 12, 8, buffer)
                assertEquals(0, component.viewportState().historySize)
                assertTrue(component.copyCellBounds(8, 0, Rectangle()))
                assertTrue(component.copySelectionToClipboard())
                assertEquals(expected, clipboard.text.trimEnd())

                val image = BufferedImage(component.width, component.height, BufferedImage.TYPE_INT_ARGB)
                val graphics = image.createGraphics()
                try {
                    component.paint(graphics)
                } finally {
                    graphics.dispose()
                }
                component.unbind()
                assertFalse(component.hasActiveRenderBinding)
                assertNull(component.currentSelection())
                component.bind(session)
                assertTrue(component.selectAll())
                assertTrue(component.copySelectionToClipboard())
                assertEquals(expected, clipboard.text.trimEnd())
            } finally {
                component.dispose()
            }
            component.bind(session)
            component.reloadSettings()
            resize(component, 2, 1, buffer)
            assertFalse(component.hasActiveRenderBinding)
            assertFalse(component.isCoroutineScopeActive)
            assertNull(component.currentSelection())
        }
        assertEquals(resizes, connector.resizes)
        assertEquals(generation, session.renderGeneration.value)
        session.readRenderFrame {
            assertEquals(10, it.columns)
            assertEquals(3, it.rows)
            assertEquals(buffer, it.activeBuffer)
        }
        dispatcher.scheduler.runCurrent()
    }

    @Test
    fun `closing before startup allows empty presentation without starting transport`() {
        val connector = RecordingConnector()
        val session =
            TerminalSession.create(
                TerminalBuffers.create(10, 3),
                connector,
                workerDispatcher = dispatcher,
                ioDispatcher = dispatcher,
            )
        session.close()
        edt {
            val component = SwingTerminal()
            try {
                component.size = component.preferredGridSize(4, 1)
                component.bind(session)
                resize(component, 12, 6)
                assertTrue(component.copyCellBounds(0, 0, Rectangle()))
                assertFalse(component.copyCellBounds(10, 0, Rectangle()))
                assertEquals(0, connector.starts)
                assertTrue(connector.resizes.isEmpty())
            } finally {
                component.dispose()
            }
        }
        dispatcher.scheduler.runCurrent()
    }

    private fun selectWord(
        component: SwingTerminal,
        clipboard: Clipboard,
        expected: String,
    ) {
        val bounds = Rectangle()
        assertTrue(component.copyCellBounds(0, 0, bounds))
        val event =
            MouseEvent(
                component,
                MouseEvent.MOUSE_PRESSED,
                0L,
                0,
                bounds.x + bounds.width / 2,
                bounds.y + bounds.height / 2,
                2,
                false,
                MouseEvent.BUTTON1,
            )
        component.mouseListeners.forEach { it.mousePressed(event) }
        val release =
            MouseEvent(
                component,
                MouseEvent.MOUSE_RELEASED,
                0L,
                0,
                event.x,
                event.y,
                2,
                false,
                MouseEvent.BUTTON1,
            )
        component.mouseListeners.forEach { it.mouseReleased(release) }
        assertTrue(component.copySelectionToClipboard())
        assertEquals(expected, clipboard.text)
    }

    private fun resize(
        component: SwingTerminal,
        columns: Int,
        rows: Int,
        buffer: TerminalRenderBufferKind = TerminalRenderBufferKind.PRIMARY,
    ) {
        component.size = component.preferredGridSize(columns, rows, buffer)
        component.componentListeners.forEach {
            it.componentResized(ComponentEvent(component, ComponentEvent.COMPONENT_RESIZED))
        }
    }

    private fun feed(
        session: TerminalSession,
        text: String,
    ) {
        val bytes = text.toByteArray()
        session.onBytes(bytes, 0, bytes.size)
    }

    private class Clipboard : TerminalClipboardHandler {
        var text = ""

        override fun copyText(text: String) {
            this.text = text
        }

        override fun readText(): String? = null
    }

    private fun session(connector: RecordingConnector): TerminalSession =
        TerminalSession
            .create(
                TerminalBuffers.create(10, 3, 10),
                connector,
                workerDispatcher = dispatcher,
                ioDispatcher = dispatcher,
            ).also { it.start(10, 3) }

    private class RecordingConnector(
        private val onClose: () -> Unit = {},
    ) : TerminalConnector {
        val resizes = mutableListOf<Pair<Int, Int>>()
        var starts = 0
        var closes = 0

        override fun start(listener: TerminalConnectorListener) {
            starts++
        }

        override fun resize(
            columns: Int,
            rows: Int,
        ) {
            check(closes == 0) { "resize after connector close" }
            resizes += columns to rows
        }

        override fun write(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) = error("unexpected output")

        override fun close() {
            closes++
            onClose()
        }
    }

    private fun <T> edt(action: () -> T): T {
        val task = FutureTask(action)
        SwingUtilities.invokeAndWait(task)
        return task.get()
    }
}
