package com.gagik.terminal.ui.swing.api

import com.gagik.core.TerminalBuffers
import com.gagik.parser.api.TerminalOutputParser
import com.gagik.terminal.input.api.TerminalInputEncoder
import com.gagik.terminal.input.event.TerminalFocusEvent
import com.gagik.terminal.input.event.TerminalKeyEvent
import com.gagik.terminal.input.event.TerminalMouseEvent
import com.gagik.terminal.input.event.TerminalPasteEvent
import com.gagik.terminal.render.api.TerminalRenderFrame
import com.gagik.terminal.render.api.TerminalRenderFrameConsumer
import com.gagik.terminal.render.api.TerminalRenderFrameReader
import com.gagik.terminal.render.cache.TerminalRenderPublisher
import com.gagik.terminal.session.TerminalSession
import com.gagik.terminal.transport.TerminalConnector
import com.gagik.terminal.transport.TerminalConnectorListener
import com.gagik.terminal.ui.swing.render.TestRenderFrame
import com.gagik.terminal.ui.swing.settings.TerminalClipboardHandler
import com.gagik.terminal.ui.swing.settings.TerminalClipboardShortcuts
import com.gagik.terminal.ui.swing.settings.TerminalSwingSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities

class TerminalSwingTerminalSelectionTest {
    @Test
    fun `single click clears selection without selecting the clicked cell`() {
        val frame = TestRenderFrame.text("hello")
        val session = testSession(frame = frame)
        val component = TerminalSwingTerminal()

        SwingUtilities.invokeAndWait {
            component.setSize(300, 80)
            component.bind(session)
            session.publisher.updateAndPublish(StaticFrameReader(frame))
            component.mouseListeners.forEach { it.mousePressed(mousePressed(component, x = 8, y = 8, clickCount = 1)) }
        }

        assertNull(component.currentSelection())
        session.close()
    }

    @Test
    fun `drag after single click creates selection`() {
        val frame = TestRenderFrame.text("hello")
        val session = testSession(frame = frame)
        val component = TerminalSwingTerminal()

        SwingUtilities.invokeAndWait {
            component.setSize(300, 80)
            component.bind(session)
            session.publisher.updateAndPublish(StaticFrameReader(frame))
            component.mouseListeners.forEach { it.mousePressed(mousePressed(component, x = 8, y = 8, clickCount = 1)) }
            component.mouseMotionListeners.forEach { it.mouseDragged(mouseDragged(component, x = 299, y = 8)) }
        }

        assertEquals(CellSelection(0, 0, 5, 0), component.currentSelection())
        session.close()
    }

    @Test
    fun `windows native ctrl c copies selected text to clipboard`() {
        val clipboard = RecordingClipboard()
        val frame = TestRenderFrame.text("hello world")
        val session = testSession(frame = frame)
        val component = TerminalSwingTerminal {
            TerminalSwingSettings(
                clipboardHandler = clipboard,
                clipboardShortcuts = TerminalClipboardShortcuts.windows(),
            )
        }

        SwingUtilities.invokeAndWait {
            component.setSize(300, 80)
            component.bind(session)
            session.publisher.updateAndPublish(StaticFrameReader(frame))
            component.mouseListeners.forEach { it.mousePressed(mousePressed(component, x = 8, y = 8, clickCount = 2)) }
            component.keyListeners.forEach { it.keyPressed(copyKey(component, InputEvent.CTRL_DOWN_MASK)) }
        }

        assertEquals("hello", clipboard.copied.get())
        session.close()
    }

    @Test
    fun `linux terminal ctrl shift c copies selected text to clipboard`() {
        val clipboard = RecordingClipboard()
        val frame = TestRenderFrame.text("hello world")
        val session = testSession(frame = frame)
        val component = TerminalSwingTerminal {
            TerminalSwingSettings(
                clipboardHandler = clipboard,
                clipboardShortcuts = TerminalClipboardShortcuts.linuxAndUnix(),
            )
        }

        SwingUtilities.invokeAndWait {
            component.setSize(300, 80)
            component.bind(session)
            session.publisher.updateAndPublish(StaticFrameReader(frame))
            component.mouseListeners.forEach { it.mousePressed(mousePressed(component, x = 8, y = 8, clickCount = 2)) }
            component.keyListeners.forEach {
                it.keyPressed(copyKey(component, InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK))
            }
        }

        assertEquals("hello", clipboard.copied.get())
        session.close()
    }

    @Test
    fun `windows native ctrl v reads clipboard and sends paste event through session`() {
        val clipboard = RecordingClipboard(readValue = "pasted text")
        val input = RecordingInputEncoder()
        val frame = TestRenderFrame.text("ready")
        val session = testSession(frame = frame, inputEncoder = input)
        val component = TerminalSwingTerminal {
            TerminalSwingSettings(
                clipboardHandler = clipboard,
                clipboardShortcuts = TerminalClipboardShortcuts.windows(),
            )
        }

        session.start(columns = 5, rows = 1)
        SwingUtilities.invokeAndWait {
            component.setSize(300, 80)
            component.bind(session)
            component.keyListeners.forEach { it.keyPressed(pasteKey(component, InputEvent.CTRL_DOWN_MASK)) }
        }

        assertEquals("pasted text", input.pasteText.get())
        session.close()
    }

    @Test
    fun `copy shortcut without selection falls through to terminal input`() {
        val clipboard = RecordingClipboard()
        val input = RecordingInputEncoder()
        val frame = TestRenderFrame.text("ready")
        val session = testSession(frame = frame, inputEncoder = input)
        val component = TerminalSwingTerminal {
            TerminalSwingSettings(
                clipboardHandler = clipboard,
                clipboardShortcuts = TerminalClipboardShortcuts.windows(),
            )
        }

        session.start(columns = 5, rows = 1)
        SwingUtilities.invokeAndWait {
            component.setSize(300, 80)
            component.bind(session)
            component.keyListeners.forEach { it.keyPressed(copyKey(component, InputEvent.CTRL_DOWN_MASK)) }
        }

        assertEquals(1, input.keyCount)
        assertEquals(null, clipboard.copied.get())
        session.close()
    }

    private fun testSession(
        frame: TerminalRenderFrame,
        inputEncoder: TerminalInputEncoder = NoOpInputEncoder,
    ): TerminalSession {
        val terminal = TerminalBuffers.create(width = frame.columns, height = frame.rows, maxHistory = 5)
        return TerminalSession(
            terminal = terminal,
            publisher = TerminalRenderPublisher(frame.columns, frame.rows),
            renderReader = StaticFrameReader(frame),
            responseReader = terminal,
            connector = NoOpConnector,
            parser = NoOpParser,
            inputEncoder = inputEncoder,
        )
    }

    private fun mousePressed(
        component: TerminalSwingTerminal,
        x: Int,
        y: Int,
        clickCount: Int,
    ): MouseEvent {
        return MouseEvent(
            component,
            MouseEvent.MOUSE_PRESSED,
            System.currentTimeMillis(),
            InputEvent.BUTTON1_DOWN_MASK,
            x,
            y,
            clickCount,
            false,
            MouseEvent.BUTTON1,
        )
    }

    private fun mouseDragged(
        component: TerminalSwingTerminal,
        x: Int,
        y: Int,
    ): MouseEvent {
        return MouseEvent(
            component,
            MouseEvent.MOUSE_DRAGGED,
            System.currentTimeMillis(),
            InputEvent.BUTTON1_DOWN_MASK,
            x,
            y,
            0,
            false,
            MouseEvent.BUTTON1,
        )
    }

    private fun copyKey(component: TerminalSwingTerminal, modifiers: Int): KeyEvent {
        return clipboardKey(component, KeyEvent.VK_C, modifiers)
    }

    private fun pasteKey(component: TerminalSwingTerminal, modifiers: Int): KeyEvent {
        return clipboardKey(component, KeyEvent.VK_V, modifiers)
    }

    private fun clipboardKey(component: TerminalSwingTerminal, keyCode: Int, modifiers: Int): KeyEvent {
        return KeyEvent(
            component,
            KeyEvent.KEY_PRESSED,
            System.currentTimeMillis(),
            modifiers,
            keyCode,
            KeyEvent.CHAR_UNDEFINED,
        )
    }

    private class StaticFrameReader(
        private val frame: TerminalRenderFrame,
    ) : TerminalRenderFrameReader {
        override fun readRenderFrame(consumer: TerminalRenderFrameConsumer) {
            consumer.accept(frame)
        }
    }

    private class RecordingClipboard(
        private val readValue: String? = null,
    ) : TerminalClipboardHandler {
        val copied = AtomicReference<String?>()

        override fun copyText(text: String) {
            copied.set(text)
        }

        override fun readText(): String? = readValue
    }

    private class RecordingInputEncoder : TerminalInputEncoder {
        val pasteText = AtomicReference<String?>()
        var keyCount: Int = 0
            private set

        override fun encodeKey(event: TerminalKeyEvent) {
            keyCount++
        }

        override fun encodePaste(event: TerminalPasteEvent) {
            pasteText.set(event.text)
        }

        override fun encodeFocus(event: TerminalFocusEvent) = Unit

        override fun encodeMouse(event: TerminalMouseEvent) = Unit
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
