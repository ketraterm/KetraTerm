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
import io.github.ketraterm.input.event.TerminalPasteEvent
import io.github.ketraterm.input.policy.PasteControlPolicy
import io.github.ketraterm.session.TerminalInputAdmission
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.transport.TerminalConnectorListener
import io.github.ketraterm.ui.swing.settings.SwingPadding
import io.github.ketraterm.ui.swing.settings.SwingSettings
import io.github.ketraterm.ui.swing.settings.TerminalClipboardHandler
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestion
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionRequest
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.awt.Rectangle
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.io.ByteArrayOutputStream
import javax.swing.SwingUtilities

class SwingTerminalInteractionSettingsTest {
    @Test
    fun `live spacing resize clears selection and preserves output`() =
        fixture {
            assertTrue(view.selectAll())
            settings = settings.copy { it.columnSpacing = 5 }
            view.reloadSettings()
            val visibleColumns = view.visibleGridSize().width
            assertTrue(visibleColumns < 10)
            session.readRenderFrame { assertEquals(visibleColumns, it.columns) }
            assertNull(view.currentSelectionRange())
            assertEquals(0, copies)
            dispatcher.scheduler.runCurrent()
            view.bind(session)
            assertTrue(view.selectAll())
            assertTrue(view.copySelectionToClipboard())
            assertEquals("hello", clipboardText.trimEnd())
        }

    @Test
    fun `empty clicks and cancelled selection gestures do not copy`() =
        fixture {
            click(MouseEvent.BUTTON1)
            assertEquals(0, copies)
            view.addSelectionListener { _, current -> if (current != null) view.clearSelection() }
            click(MouseEvent.BUTTON1, clicks = 2)
            assertEquals(0, copies)
            assertEquals("clipboard", clipboardText)
        }

    @Test
    fun `release in the scrollbar gutter completes the selection gesture`() =
        fixture {
            val press = event(MouseEvent.MOUSE_PRESSED, MouseEvent.BUTTON1, clicks = 2)
            view.mouseListeners.forEach { it.mousePressed(press) }
            val release = event(MouseEvent.MOUSE_RELEASED, MouseEvent.BUTTON1, x = view.width - 1)
            view.mouseListeners.forEach { it.mouseReleased(release) }
            assertEquals(1, copies)
            assertEquals("hello", clipboardText)
        }

    @Test
    fun `middle paste uses bracketed paste and the configured control policy`() =
        fixture {
            feed("\u001b[?2004h")
            clipboardText = "a\u0001b\n"
            click(MouseEvent.BUTTON2)
            dispatcher.scheduler.runCurrent()
            assertEquals("\u001b[200~ab\n\u001b[201~", output.toString(Charsets.UTF_8))
            assertEquals(1, reads)
            assertNull(view.currentSelectionRange())
        }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `paste reports rejection before session startup`(suppliedText: Boolean) =
        fixture(startSession = false) {
            var invalidations = 0
            view.addShellSuggestionInvalidationListener { invalidations++ }

            assertFalse(paste(suppliedText))
            assertEquals(1, invalidations)
            assertEquals(if (suppliedText) 0 else 1, reads)
            dispatcher.scheduler.runCurrent()
            assertEquals(0, output.size())
        }

    @Test
    fun `clear screen reports rejection before session startup`() =
        fixture(startSession = false) {
            assertFalse(view.clearScreen())
            dispatcher.scheduler.runCurrent()
            assertEquals(0, output.size())
        }

    @Test
    fun `clear screen reports rejection after session closure`() =
        fixture {
            session.close()
            assertFalse(view.clearScreen())
            dispatcher.scheduler.runCurrent()
            assertEquals(0, output.size())
        }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `paste reports rejection when the bulk operation queue is full`(suppliedText: Boolean) =
        fixture {
            repeat(16) {
                assertEquals(TerminalInputAdmission.ACCEPTED, session.submitInput(TerminalPasteEvent("queued")))
            }
            assertFalse(paste(suppliedText))
            assertEquals(if (suppliedText) 0 else 1, reads)
            assertTrue(session.isClosed)
            dispatcher.scheduler.runCurrent()
            assertEquals(0, output.size())
        }

    @Test
    fun `clear screen reports rejection when the outbound byte queue is full`() =
        fixture {
            assertEquals(TerminalInputAdmission.ACCEPTED, session.submitBytes(ByteArray(8 * 1024 * 1024)))
            assertFalse(view.clearScreen())
            assertTrue(session.isClosed)
            dispatcher.scheduler.runCurrent()
            assertEquals(0, output.size())
        }

    @Test
    fun `clear screen reports admission and sends Ctrl L`() =
        fixture {
            assertTrue(view.clearScreen())
            dispatcher.scheduler.runCurrent()
            assertEquals("\u000c", output.toString(Charsets.UTF_8))
        }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `paste invalidates before admission and preserves bracketed paste policy`(suppliedText: Boolean) =
        fixture {
            settings = settings.copy { it.smartSuggestionsEnabled = true }
            view.reloadSettings()
            feed("\u001b[?2004h")
            clipboardText = "a\u0001b\n"
            view.showSuggestions(
                SwingShellSuggestionRequest("git s", 5),
                listOf(SwingShellSuggestion("status", 4, 5, "test", "SUBCOMMAND")),
            )
            assertTrue(view.currentShellSuggestionState().visible)
            var invalidations = 0
            view.addShellSuggestionInvalidationListener {
                invalidations++
                assertFalse(view.currentShellSuggestionState().visible)
                dispatcher.scheduler.runCurrent()
                assertEquals(0, output.size())
            }

            assertTrue(paste(suppliedText))
            assertEquals(1, invalidations)
            dispatcher.scheduler.runCurrent()
            assertEquals("\u001b[200~ab\n\u001b[201~", output.toString(Charsets.UTF_8))
            assertEquals(if (suppliedText) 0 else 1, reads)
        }

    @Test
    fun `empty supplied text and empty or unavailable clipboard text do not invalidate`() =
        fixture {
            var invalidations = 0
            view.addShellSuggestionInvalidationListener { invalidations++ }

            assertFalse(view.pasteText(""))
            assertEquals(0, reads)
            clipboardText = ""
            assertFalse(view.pasteClipboardText())
            clipboardAvailable = false
            clipboardText = "unavailable"
            assertFalse(view.pasteClipboardText())

            assertEquals(0, invalidations)
            assertEquals(2, reads)
            dispatcher.scheduler.runCurrent()
            assertEquals(0, output.size())
        }

    @ParameterizedTest
    @ValueSource(strings = ["unbound", "closed", "disposed"])
    fun `unavailable sessions reject paste before clipboard access and invalidation`(state: String) =
        fixture {
            var invalidations = 0
            view.addShellSuggestionInvalidationListener { invalidations++ }
            when (state) {
                "unbound" -> view.unbind()
                "closed" -> session.close()
                "disposed" -> view.dispose()
            }

            assertFalse(view.pasteText("candidate"))
            assertFalse(view.pasteClipboardText())
            assertEquals(0, invalidations)
            assertEquals(0, reads)
            dispatcher.scheduler.runCurrent()
            assertEquals(0, output.size())
        }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `paste invalidation failures propagate without submitting input`(suppliedText: Boolean) =
        fixture {
            val failure = IllegalStateException("invalidation failed")
            var invalidations = 0
            view.addShellSuggestionInvalidationListener {
                invalidations++
                dispatcher.scheduler.runCurrent()
                assertEquals(0, output.size())
                throw failure
            }

            assertSame(failure, assertThrows(IllegalStateException::class.java) { paste(suppliedText) })
            assertEquals(1, invalidations)
            assertEquals(if (suppliedText) 0 else 1, reads)
            dispatcher.scheduler.runCurrent()
            assertEquals(0, output.size())
        }

    @ParameterizedTest
    @ValueSource(strings = ["unbind", "close", "dispose", "rebind"])
    fun `paste rechecks session after invalidation callbacks`(change: String) {
        for (suppliedText in listOf(false, true)) {
            fixture {
                val replacementOutput = ByteArrayOutputStream()
                val replacement = if (change == "rebind") createSession(replacementOutput) else null
                try {
                    replacement?.start(10, 2)
                    dispatcher.scheduler.runCurrent()
                    var invalidations = 0
                    view.addShellSuggestionInvalidationListener {
                        invalidations++
                        when (change) {
                            "unbind" -> view.unbind()
                            "close" -> session.close()
                            "dispose" -> view.dispose()
                            "rebind" -> view.bind(requireNotNull(replacement))
                        }
                    }

                    assertFalse(paste(suppliedText), "suppliedText=$suppliedText")
                    assertEquals(1, invalidations)
                    assertEquals(if (suppliedText) 0 else 1, reads)
                    dispatcher.scheduler.runCurrent()
                    assertEquals(0, output.size())
                    assertEquals(0, replacementOutput.size())
                } finally {
                    replacement?.close()
                }
            }
        }
    }

    @Test
    fun `clipboard read reentry cannot paste into a replacement session`() =
        fixture {
            val replacementOutput = ByteArrayOutputStream()
            val replacement = createSession(replacementOutput)
            try {
                replacement.start(10, 2)
                dispatcher.scheduler.runCurrent()
                var invalidations = 0
                view.addShellSuggestionInvalidationListener { invalidations++ }
                onRead = { view.bind(replacement) }

                assertFalse(view.pasteClipboardText())
                assertEquals(1, reads)
                assertEquals(0, invalidations)
                dispatcher.scheduler.runCurrent()
                assertEquals(0, output.size())
                assertEquals(0, replacementOutput.size())
            } finally {
                replacement.close()
            }
        }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `paste rejects calls outside the EDT without side effects`(suppliedText: Boolean) {
        lateinit var fixture: Fixture
        var invalidations = 0
        SwingUtilities.invokeAndWait {
            fixture = Fixture(startSession = true)
            fixture.view.addShellSuggestionInvalidationListener { invalidations++ }
        }
        try {
            assertFalse(SwingUtilities.isEventDispatchThread())
            assertFalse(fixture.paste(suppliedText))

            SwingUtilities.invokeAndWait {
                fixture.dispatcher.scheduler.runCurrent()
                assertEquals(0, invalidations)
                assertEquals(0, fixture.reads)
                assertEquals(0, fixture.output.size())
            }
        } finally {
            SwingUtilities.invokeAndWait {
                fixture.view.dispose()
                fixture.session.close()
                fixture.dispatcher.scheduler.runCurrent()
            }
        }
    }

    @Test
    fun `mouse reporting can be disabled without changing application modes`() =
        fixture {
            feed("\u001b[?1000h\u001b[?1006h")
            click(MouseEvent.BUTTON2)
            dispatcher.scheduler.runCurrent()
            assertEquals(0, reads)
            assertTrue(output.size() > 0)
            output.reset()
            settings = settings.copy { it.mouseReportingEnabled = false }
            view.reloadSettings()
            click(MouseEvent.BUTTON2)
            dispatcher.scheduler.runCurrent()
            assertEquals("clipboard", output.toString(Charsets.UTF_8))
            output.reset()
            settings = settings.copy { it.mouseReportingEnabled = true }
            view.reloadSettings()
            click(MouseEvent.BUTTON2)
            dispatcher.scheduler.runCurrent()
            assertTrue(output.toString(Charsets.UTF_8).startsWith("\u001b[<1;"))
            assertEquals(1, reads)
            output.reset()
            click(MouseEvent.BUTTON2, modifiers = InputEvent.SHIFT_DOWN_MASK)
            dispatcher.scheduler.runCurrent()
            assertEquals("clipboard", output.toString(Charsets.UTF_8))
            assertEquals(2, reads)
        }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `copy on selection works with live and closed output and ignores host selection changes`(closed: Boolean) =
        fixture {
            if (closed) session.onClosed(0)
            view.bind(session)
            val range = requireNotNull(view.createSelectionRange(0, 0, 5, 0))
            assertTrue(view.setSelection(range))
            view.clearSelection()
            assertEquals(0, copies)
            click(MouseEvent.BUTTON1, clicks = 2)
            assertEquals("hello", clipboardText)
            assertEquals(1, copies)
            if (closed) {
                click(MouseEvent.BUTTON2)
                assertFalse(view.pasteClipboardText())
                assertEquals(0, reads)
            }
            view.dispose()
            click(MouseEvent.BUTTON2)
            assertEquals(0, reads)
            assertEquals(1, copies)
        }

    @Test
    fun `clipboard reentry cannot send to an unbound session`() =
        fixture {
            onRead = { view.unbind() }
            click(MouseEvent.BUTTON2)
            dispatcher.scheduler.runCurrent()
            assertEquals(1, reads)
            assertEquals(0, output.size())
        }

    @Test
    fun `copy failure ends the gesture and propagates to the host`() =
        fixture {
            val failure = IllegalStateException("clipboard unavailable")
            onCopy = { throw failure }
            assertSame(failure, assertThrows(IllegalStateException::class.java) { click(MouseEvent.BUTTON1, clicks = 2) })
            onCopy = {}
            release(MouseEvent.BUTTON1)
            assertEquals(1, copies)
            click(MouseEvent.BUTTON1, clicks = 2)
            assertEquals(2, copies)
        }

    @ParameterizedTest
    @ValueSource(ints = [0, 5])
    fun `spaced cell bounds and mouse selection use the same columns`(spacing: Int) =
        fixture {
            settings = settings.copy { it.columnSpacing = spacing }
            view.reloadSettings()
            view.size = view.preferredGridSize(10, 2)
            view.bind(session)
            feed("\u001b[Habcdefghij")
            view.bind(session)
            val bounds = Rectangle()
            assertTrue(view.copyCellBounds(3, 0, bounds))
            val press = event(MouseEvent.MOUSE_PRESSED, MouseEvent.BUTTON1, x = bounds.x + 1)
            view.mouseListeners.forEach { it.mousePressed(press) }
            assertTrue(view.copyCellBounds(6, 0, bounds))
            val drag = event(MouseEvent.MOUSE_DRAGGED, MouseEvent.BUTTON1, modifiers = InputEvent.BUTTON1_DOWN_MASK, x = bounds.x + 1)
            view.mouseMotionListeners.forEach { it.mouseDragged(drag) }
            release(MouseEvent.BUTTON1)
            assertEquals("defg", clipboardText)
        }

    private fun fixture(
        startSession: Boolean = true,
        action: Fixture.() -> Unit,
    ) {
        SwingUtilities.invokeAndWait {
            val fixture = Fixture(startSession)
            try {
                fixture.action()
            } finally {
                fixture.view.dispose()
                fixture.session.close()
                fixture.dispatcher.scheduler.runCurrent()
            }
        }
    }

    private class Fixture(
        startSession: Boolean,
    ) {
        val dispatcher = StandardTestDispatcher()
        val output = ByteArrayOutputStream()
        val session = createSession(output)

        fun createSession(destination: ByteArrayOutputStream): TerminalSession =
            TerminalSession.create(
                TerminalBuffers.create(10, 2, 10),
                object : TerminalConnector {
                    override fun start(listener: TerminalConnectorListener) = Unit

                    override fun resize(
                        columns: Int,
                        rows: Int,
                    ) = Unit

                    override fun close() = Unit

                    override fun write(
                        bytes: ByteArray,
                        offset: Int,
                        length: Int,
                    ) {
                        destination.write(bytes, offset, length)
                    }
                },
                workerDispatcher = dispatcher,
                ioDispatcher = dispatcher,
            )

        var settings =
            SwingSettings.create {
                it.columns = 10
                it.rows = 2
                it.padding = SwingPadding()
                it.alternateScreenPadding = SwingPadding()
                it.shellIntegrationDecorationGutterWidth = 0
                it.cursorBlinkMillis = 0
                it.middleClickPaste = true
                it.copyOnSelection = true
                it.pasteControlPolicy = PasteControlPolicy.STRIP_C0_EXCEPT_TAB_CR_LF
            }
        var clipboardText = "clipboard"
        var clipboardAvailable = true
        var reads = 0
        var copies = 0
        var onRead: () -> Unit = {}
        var onCopy: () -> Unit = {}
        val view =
            SwingTerminal(
                { settings },
                SwingHostServices.create {
                    it.scrollbarOverlayEnabled = true
                    it.clipboardHandler =
                        object : TerminalClipboardHandler {
                            override fun readText(): String? {
                                reads++
                                onRead()
                                return clipboardText.takeIf { clipboardAvailable }
                            }

                            override fun copyText(text: String) {
                                copies++
                                onCopy()
                                clipboardText = text
                            }
                        }
                },
            )

        init {
            if (startSession) {
                session.start(10, 2)
                feed("hello")
            }
            view.size = view.preferredGridSize(10, 2)
            view.bind(session)
        }

        fun feed(text: String) {
            val bytes = text.toByteArray()
            session.onBytes(bytes, 0, bytes.size)
            session.requestRender(0)
            dispatcher.scheduler.runCurrent()
        }

        fun paste(suppliedText: Boolean): Boolean = if (suppliedText) view.pasteText(clipboardText) else view.pasteClipboardText()

        fun event(
            id: Int,
            button: Int,
            clicks: Int = 1,
            modifiers: Int = 0,
            x: Int = 1,
        ): MouseEvent = MouseEvent(view, id, 0L, modifiers, x, 1, clicks, false, button)

        fun click(
            button: Int,
            clicks: Int = 1,
            modifiers: Int = 0,
        ) {
            val press = event(MouseEvent.MOUSE_PRESSED, button, clicks, modifiers)
            view.mouseListeners.forEach { it.mousePressed(press) }
            release(button, modifiers)
        }

        fun release(
            button: Int,
            modifiers: Int = 0,
        ) {
            val release = event(MouseEvent.MOUSE_RELEASED, button, modifiers = modifiers)
            view.mouseListeners.forEach { it.mouseReleased(release) }
        }
    }
}
