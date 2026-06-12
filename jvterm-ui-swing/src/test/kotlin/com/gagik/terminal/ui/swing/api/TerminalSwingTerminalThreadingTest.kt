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
package com.gagik.terminal.ui.swing.api

import com.gagik.core.TerminalBuffers
import com.gagik.parser.api.TerminalOutputParser
import com.gagik.terminal.input.api.TerminalInputEncoder
import com.gagik.terminal.input.event.TerminalFocusEvent
import com.gagik.terminal.input.event.TerminalKeyEvent
import com.gagik.terminal.input.event.TerminalMouseEvent
import com.gagik.terminal.input.event.TerminalPasteEvent
import com.gagik.terminal.render.api.TerminalRenderFrameReader
import com.gagik.terminal.render.cache.TerminalRenderPublisher
import com.gagik.terminal.session.TerminalSession
import com.gagik.terminal.transport.TerminalConnector
import com.gagik.terminal.transport.TerminalConnectorListener
import com.gagik.terminal.ui.swing.settings.TerminalSwingSettings
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.Dimension
import java.awt.Font
import java.awt.event.ComponentEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import kotlin.concurrent.thread

class TerminalSwingTerminalThreadingTest {
    @Test
    fun `bind and unbind may be called from a background thread`() {
        val session = testSession()
        val component = TerminalSwingTerminal()

        runOffEdt {
            component.bind(session)
        }
        drainEdt()
        assertNotNull(session.onDirty)

        runOffEdt {
            component.unbind()
        }
        drainEdt()

        assertNull(session.onDirty)
        session.close()
    }

    @Test
    fun `off EDT lifecycle work uses injected UI dispatcher`() {
        val session = testSession()
        val dispatcher = RecordingDispatcher()
        val component =
            TerminalSwingTerminal(
                hostServices =
                    TerminalSwingHostServices(
                        uiDispatcher = dispatcher,
                    ),
            )

        runOffEdt {
            component.bind(session)
            component.reloadSettings()
            component.unbind()
        }
        drainEdt()

        assertEquals(3, dispatcher.dispatchCount.get())
        assertNull(session.onDirty)
        session.close()
    }

    @Test
    fun `bind called off EDT does not wait for EDT execution`() {
        val session = testSession()
        val component = TerminalSwingTerminal()
        val edtBlocked = CountDownLatch(1)
        val releaseEdt = CountDownLatch(1)
        val bindReturned = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>()

        SwingUtilities.invokeLater {
            try {
                edtBlocked.countDown()
                if (!releaseEdt.await(1, TimeUnit.SECONDS)) {
                    failure.set(AssertionError("EDT blocker was not released"))
                }
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        assertTrue(edtBlocked.await(1, TimeUnit.SECONDS), "EDT blocker did not start")

        val worker =
            thread(start = true) {
                try {
                    component.bind(session)
                    bindReturned.set(true)
                } catch (error: Throwable) {
                    failure.set(error)
                }
            }

        worker.join(500)
        assertTrue(bindReturned.get(), "bind should post to EDT and return without waiting")

        releaseEdt.countDown()
        worker.join(1_000)
        failure.get()?.let { throw it }
        drainEdt()
        assertNotNull(session.onDirty)
        session.close()
    }

    @Test
    fun `reloadSettings called off EDT rebuilds component state on EDT`() {
        val reloadCalledOnEdt = AtomicBoolean(false)
        val calls = AtomicInteger()
        val component =
            TerminalSwingTerminal(settingsProvider = {
                if (calls.incrementAndGet() > 1) {
                    reloadCalledOnEdt.set(SwingUtilities.isEventDispatchThread())
                }
                TerminalSwingSettings(
                    font = Font(Font.MONOSPACED, Font.PLAIN, 18),
                    columns = 100,
                    rows = 30,
                )
            })

        runOffEdt {
            component.reloadSettings()
        }

        val preferredSize = edtCall { component.preferredSize }
        assertAll(
            { assertTrue(reloadCalledOnEdt.get()) },
            { assertTrue(preferredSize.width > 0) },
            { assertTrue(preferredSize.height > 0) },
        )
    }

    @Test
    fun `visibleGridSize called off EDT reads cached grid size without waiting for EDT`() {
        val component = TerminalSwingTerminal()
        val expected =
            edtCall {
                component.size = Dimension(160, 80)
                component.visibleGridSize()
            }
        val visibleFromBackground = AtomicReference<Dimension>()
        val edtBlocked = CountDownLatch(1)
        val releaseEdt = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()

        SwingUtilities.invokeLater {
            try {
                edtBlocked.countDown()
                if (!releaseEdt.await(1, TimeUnit.SECONDS)) {
                    failure.set(AssertionError("EDT blocker was not released"))
                }
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        assertTrue(edtBlocked.await(1, TimeUnit.SECONDS), "EDT blocker did not start")

        val worker =
            thread(start = true) {
                try {
                    visibleFromBackground.set(component.visibleGridSize())
                    returned.countDown()
                } catch (error: Throwable) {
                    failure.set(error)
                }
            }

        val completedWhileEdtBlocked = returned.await(500, TimeUnit.MILLISECONDS)
        releaseEdt.countDown()
        worker.join(1_000)
        failure.get()?.let { throw it }
        assertTrue(completedWhileEdtBlocked, "visibleGridSize should not wait for the EDT")
        assertEquals(expected, visibleFromBackground.get())
    }

    @Test
    fun `bind resizes session to current visible grid when component has bounds`() {
        val connector = RecordingConnector()
        val session = testSession(connector)
        val component = TerminalSwingTerminal()

        val expected =
            edtCall {
                component.size = Dimension(160, 80)
                component.visibleGridSize()
            }

        component.bind(session)
        drainEdt()

        assertAll(
            { assertEquals(expected.width, connector.lastColumns.get()) },
            { assertEquals(expected.height, connector.lastRows.get()) },
            { assertEquals(1, connector.resizeCount.get()) },
        )
        session.close()
    }

    @Test
    fun `bind applies ambiguous width setting to session core`() {
        val session = testSession()
        val component =
            TerminalSwingTerminal(settingsProvider = {
                TerminalSwingSettings(treatAmbiguousAsWide = true)
            })

        component.bind(session)
        drainEdt()

        assertTrue(session.terminal.getModeSnapshot().treatAmbiguousAsWide)
        session.close()
    }

    @Test
    fun `reloadSettings updates ambiguous width setting on bound session`() {
        val session = testSession()
        var ambiguousAsWide = false
        val component =
            TerminalSwingTerminal(settingsProvider = {
                TerminalSwingSettings(treatAmbiguousAsWide = ambiguousAsWide)
            })

        component.bind(session)
        drainEdt()
        assertFalse(session.terminal.getModeSnapshot().treatAmbiguousAsWide)

        ambiguousAsWide = true
        component.reloadSettings()
        drainEdt()

        assertTrue(session.terminal.getModeSnapshot().treatAmbiguousAsWide)
        session.close()
    }

    @Test
    fun `component resize updates session only when visible grid changes`() {
        val connector = RecordingConnector()
        val session = testSession(connector)
        val component = TerminalSwingTerminal()

        edtCall {
            component.size = Dimension(160, 80)
        }
        component.bind(session)
        drainEdt()
        connector.reset()

        val expected =
            edtCall {
                component.size = Dimension(320, 160)
                component.dispatchEvent(ComponentEvent(component, ComponentEvent.COMPONENT_RESIZED))
                component.visibleGridSize()
            }

        assertAll(
            { assertEquals(expected.width, connector.lastColumns.get()) },
            { assertEquals(expected.height, connector.lastRows.get()) },
            { assertEquals(1, connector.resizeCount.get()) },
        )

        edtCall {
            component.dispatchEvent(ComponentEvent(component, ComponentEvent.COMPONENT_RESIZED))
        }

        assertEquals(1, connector.resizeCount.get())
        session.close()
    }

    private fun testSession(connector: TerminalConnector = NoOpConnector): TerminalSession {
        val terminal = TerminalBuffers.create(width = 3, height = 1, maxHistory = 5)
        return TerminalSession(
            terminal = terminal,
            publisher = TerminalRenderPublisher(3, 1),
            renderReader = terminal as TerminalRenderFrameReader,
            responseReader = terminal,
            connector = connector,
            parser = NoOpParser,
            inputEncoder = NoOpInputEncoder,
        )
    }

    private fun runOffEdt(action: () -> Unit) {
        assertFalse(SwingUtilities.isEventDispatchThread())
        val failure = AtomicReference<Throwable?>()
        val worker =
            thread(start = true) {
                try {
                    assertFalse(SwingUtilities.isEventDispatchThread())
                    action()
                } catch (error: Throwable) {
                    failure.set(error)
                }
            }
        worker.join()
        failure.get()?.let { throw it }
    }

    private fun <T> edtCall(action: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return action()

        val result = AtomicReference<T>()
        SwingUtilities.invokeAndWait {
            result.set(action())
        }
        return result.get()
    }

    private fun drainEdt() {
        edtCall { }
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

    private class RecordingDispatcher : TerminalUiDispatcher {
        val dispatchCount = AtomicInteger()

        override fun dispatch(runnable: Runnable) {
            dispatchCount.incrementAndGet()
            SwingUtilities.invokeLater(runnable)
        }
    }

    private class RecordingConnector : TerminalConnector {
        val resizeCount = AtomicInteger()
        val lastColumns = AtomicInteger()
        val lastRows = AtomicInteger()

        override fun start(listener: TerminalConnectorListener) = Unit

        override fun write(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) = Unit

        override fun resize(
            columns: Int,
            rows: Int,
        ) {
            lastColumns.set(columns)
            lastRows.set(rows)
            resizeCount.incrementAndGet()
        }

        override fun close() = Unit

        fun reset() {
            resizeCount.set(0)
            lastColumns.set(0)
            lastRows.set(0)
        }
    }

    private object NoOpParser : TerminalOutputParser {
        override fun accept(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) = Unit

        override fun acceptByte(byteValue: Int) = Unit

        override fun endOfInput() = Unit

        override fun reset() = Unit
    }

    private object NoOpInputEncoder : TerminalInputEncoder {
        override fun encodeKey(event: TerminalKeyEvent) = Unit

        override fun encodePaste(event: TerminalPasteEvent) = Unit

        override fun encodeFocus(event: TerminalFocusEvent) = Unit

        override fun encodeMouse(event: TerminalMouseEvent) = Unit
    }
}
