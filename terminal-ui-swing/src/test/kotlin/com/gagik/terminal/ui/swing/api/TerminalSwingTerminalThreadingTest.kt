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
        assertNotNull(session.onDirty)

        runOffEdt {
            component.unbind()
        }

        assertNull(session.onDirty)
        session.close()
    }

    @Test
    fun `reloadSettings called off EDT rebuilds component state on EDT`() {
        val reloadCalledOnEdt = AtomicBoolean(false)
        val calls = AtomicInteger()
        val component = TerminalSwingTerminal {
            if (calls.incrementAndGet() > 1) {
                reloadCalledOnEdt.set(SwingUtilities.isEventDispatchThread())
            }
            TerminalSwingSettings(
                font = Font(Font.MONOSPACED, Font.PLAIN, 18),
                columns = 100,
                rows = 30,
            )
        }

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
    fun `visibleGridSize called off EDT reads component state on EDT`() {
        val component = TerminalSwingTerminal()
        edtCall {
            component.size = Dimension(160, 80)
        }

        val visibleFromEdt = edtCall { component.visibleGridSize() }
        val visibleFromBackground = AtomicReference<Dimension>()

        runOffEdt {
            visibleFromBackground.set(component.visibleGridSize())
        }

        assertEquals(visibleFromEdt, visibleFromBackground.get())
    }

    private fun testSession(): TerminalSession {
        val terminal = TerminalBuffers.create(width = 3, height = 1, maxHistory = 5)
        return TerminalSession(
            terminal = terminal,
            publisher = TerminalRenderPublisher(3, 1),
            renderReader = terminal as TerminalRenderFrameReader,
            responseReader = terminal,
            connector = NoOpConnector,
            parser = NoOpParser,
            inputEncoder = NoOpInputEncoder,
        )
    }

    private fun runOffEdt(action: () -> Unit) {
        assertFalse(SwingUtilities.isEventDispatchThread())
        val failure = AtomicReference<Throwable?>()
        val worker = thread(start = true) {
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

    private object NoOpConnector : TerminalConnector {
        override fun start(listener: TerminalConnectorListener) = Unit

        override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit

        override fun resize(columns: Int, rows: Int) = Unit

        override fun close() = Unit
    }

    private object NoOpParser : TerminalOutputParser {
        override fun accept(bytes: ByteArray, offset: Int, length: Int) = Unit

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
