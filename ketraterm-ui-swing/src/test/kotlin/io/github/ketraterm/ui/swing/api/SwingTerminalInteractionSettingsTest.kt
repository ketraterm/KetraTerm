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
import io.github.ketraterm.ui.swing.settings.SwingPasteSource
import io.github.ketraterm.ui.swing.settings.SwingSettings
import io.github.ketraterm.ui.swing.settings.TerminalClipboardHandler
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestion
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionRequest
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities

class SwingTerminalInteractionSettingsTest {
    @ParameterizedTest
    @ValueSource(ints = [-3, 5])
    fun `live spacing resize clears selection and preserves output`(spacing: Int) =
        fixture {
            assertTrue(view.selectAll())
            val originalSize = view.preferredGridSize(10, 2)
            val originalFont = view.font
            settings = settings.copy { it.columnSpacing = spacing }
            view.reloadSettings()
            val visibleColumns = view.visibleGridSize().width
            assertEquals(originalSize.width + 10 * spacing, view.preferredGridSize(10, 2).width)
            assertEquals(originalSize.height, view.preferredGridSize(10, 2).height)
            assertEquals(originalFont, view.font)
            if (spacing < 0) assertTrue(visibleColumns > 10) else assertTrue(visibleColumns < 10)
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

    @Test
    fun `custom middle paste receives one local request without accessing either clipboard`() {
        val requests = mutableListOf<SwingTerminalMiddleClickPasteRequest>()
        fixture(middleClickPasteHandler = { requests += it }) {
            settings = settings.copy { it.middleClickPasteSource = SwingPasteSource.PRIMARY_SELECTION }
            view.reloadSettings()
            val press = event(MouseEvent.MOUSE_PRESSED, MouseEvent.BUTTON2, x = 3)
            view.mouseListeners.forEach { it.mousePressed(press) }
            release(MouseEvent.BUTTON2)
            release(MouseEvent.BUTTON2)

            assertTrue(press.isConsumed)
            val request = requests.single()
            assertSame(view, request.terminal)
            assertEquals(SwingPasteSource.PRIMARY_SELECTION, request.source)
            assertEquals(3, request.x)
            assertEquals(1, request.y)
            assertFalse(request.forcedByShift)
            assertEquals(0, reads)
            assertEquals(0, primaryReads)
            dispatcher.scheduler.runCurrent()
            assertEquals(0, output.size())
        }
    }

    @Test
    fun `deferred middle paste captures its source and uses completion time paste policy and modes`() {
        lateinit var request: SwingTerminalMiddleClickPasteRequest
        fixture(middleClickPasteHandler = { request = it }) {
            settings = settings.copy { it.middleClickPasteSource = SwingPasteSource.PRIMARY_SELECTION }
            view.reloadSettings()
            click(MouseEvent.BUTTON2)
            settings =
                settings.copy {
                    it.middleClickPasteSource = SwingPasteSource.CLIPBOARD
                    it.middleClickPaste = false
                    it.pasteControlPolicy = PasteControlPolicy.PRESERVE
                }
            view.reloadSettings()
            feed("\u001b[?2004h")

            assertEquals(SwingPasteSource.PRIMARY_SELECTION, request.source)
            assertEquals(0, output.size())
            assertTrue(request.complete("a\u0001b\n"))
            assertFalse(request.complete("duplicate"))
            dispatcher.scheduler.runCurrent()
            assertEquals("\u001b[200~a\u0001b\n\u001b[201~", output.toString(Charsets.UTF_8))
            assertEquals(0, reads)
            assertEquals(0, primaryReads)
        }
    }

    @Test
    fun `deferred middle paste preserves bracketed paste and control stripping`() {
        lateinit var request: SwingTerminalMiddleClickPasteRequest
        fixture(middleClickPasteHandler = { request = it }) {
            feed("\u001b[?2004h")
            click(MouseEvent.BUTTON2)
            assertTrue(request.complete("a\u0001b\n"))
            dispatcher.scheduler.runCurrent()
            assertEquals("\u001b[200~ab\n\u001b[201~", output.toString(Charsets.UTF_8))
        }
    }

    @Test
    fun `primary middle paste reads only the explicitly selected source`() =
        fixture {
            settings = settings.copy { it.middleClickPasteSource = SwingPasteSource.PRIMARY_SELECTION }
            view.reloadSettings()
            primaryText = "primary text"
            click(MouseEvent.BUTTON2)
            dispatcher.scheduler.runCurrent()
            assertEquals("primary text", output.toString(Charsets.UTF_8))
            assertEquals(0, reads)
            assertEquals(1, primaryReads)
        }

    @ParameterizedTest
    @ValueSource(strings = ["empty", "unavailable"])
    fun `empty or unavailable primary selection never falls back to the clipboard`(sourceState: String) =
        fixture {
            settings = settings.copy { it.middleClickPasteSource = SwingPasteSource.PRIMARY_SELECTION }
            view.reloadSettings()
            primaryText = if (sourceState == "empty") "" else null
            var invalidations = 0
            view.addShellSuggestionInvalidationListener { invalidations++ }
            click(MouseEvent.BUTTON2)
            dispatcher.scheduler.runCurrent()
            assertEquals(0, output.size())
            assertEquals(0, reads)
            assertEquals(1, primaryReads)
            assertEquals(0, invalidations)
        }

    @ParameterizedTest
    @ValueSource(strings = ["disabled", "unbound", "closed", "disposed", "no-frame"])
    fun `unavailable local middle paste does not notify the host`(state: String) {
        var requests = 0
        fixture(startSession = state != "no-frame", middleClickPasteHandler = { requests++ }) {
            when (state) {
                "disabled" -> {
                    settings = settings.copy { it.middleClickPaste = false }
                    view.reloadSettings()
                }
                "unbound" -> view.unbind()
                "closed" -> session.close()
                "disposed" -> view.dispose()
            }
            click(MouseEvent.BUTTON2)
            dispatcher.scheduler.runCurrent()
            assertEquals(0, requests)
            assertEquals(0, reads)
            assertEquals(0, primaryReads)
            assertEquals(0, output.size())
        }
    }

    @Test
    fun `application mouse reporting takes precedence and Shift restores the local paste hook`() {
        val requests = mutableListOf<SwingTerminalMiddleClickPasteRequest>()
        fixture(middleClickPasteHandler = { requests += it }) {
            feed("\u001b[?1000h\u001b[?1006h")
            click(MouseEvent.BUTTON2)
            dispatcher.scheduler.runCurrent()
            assertEquals("\u001b[<1;1;1M\u001b[<1;1;1m", output.toString(Charsets.UTF_8))
            assertTrue(requests.isEmpty())
            output.reset()

            click(MouseEvent.BUTTON2, modifiers = InputEvent.SHIFT_DOWN_MASK)
            assertTrue(requests.single().forcedByShift)
            assertTrue(requests.single().complete("shift"))
            dispatcher.scheduler.runCurrent()
            assertEquals("shift", output.toString(Charsets.UTF_8))
            output.reset()

            settings = settings.copy { it.mouseReportingEnabled = false }
            view.reloadSettings()
            click(MouseEvent.BUTTON2)
            assertEquals(2, requests.size)
            assertFalse(requests.last().forcedByShift)
            assertTrue(requests.last().complete("local"))
            dispatcher.scheduler.runCurrent()
            assertEquals("local", output.toString(Charsets.UTF_8))
            assertEquals(0, reads)
            assertEquals(0, primaryReads)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["unbind", "close", "dispose", "rebind", "same-session", "unbind-rebind"])
    fun `deferred middle paste rejects an expired original binding`(change: String) {
        lateinit var request: SwingTerminalMiddleClickPasteRequest
        fixture(middleClickPasteHandler = { request = it }) {
            val replacementOutput = ByteArrayOutputStream()
            val replacement = if (change == "rebind") createSession(replacementOutput) else null
            try {
                replacement?.start(10, 2)
                dispatcher.scheduler.runCurrent()
                click(MouseEvent.BUTTON2)
                var invalidations = 0
                view.addShellSuggestionInvalidationListener { invalidations++ }
                when (change) {
                    "unbind" -> view.unbind()
                    "close" -> session.close()
                    "dispose" -> view.dispose()
                    "rebind" -> view.bind(requireNotNull(replacement))
                    "same-session" -> view.bind(session)
                    "unbind-rebind" -> {
                        view.unbind()
                        view.bind(session)
                    }
                }
                assertFalse(request.complete("stale"))
                assertFalse(request.complete("duplicate"))
                dispatcher.scheduler.runCurrent()
                assertEquals(0, invalidations)
                assertEquals(0, output.size())
                assertEquals(0, replacementOutput.size())
            } finally {
                replacement?.close()
            }
        }
    }

    @Test
    fun `middle paste cancellation and empty completion consume requests without invalidation`() {
        val requests = mutableListOf<SwingTerminalMiddleClickPasteRequest>()
        fixture(middleClickPasteHandler = { requests += it }) {
            var invalidations = 0
            view.addShellSuggestionInvalidationListener { invalidations++ }
            click(MouseEvent.BUTTON2)
            val cancelled = requests.last()
            cancelled.cancel()
            cancelled.cancel()
            assertFalse(cancelled.complete("cancelled"))
            click(MouseEvent.BUTTON2)
            val empty = requests.last()
            assertFalse(empty.complete(""))
            assertFalse(empty.complete("later"))
            dispatcher.scheduler.runCurrent()
            assertEquals(0, invalidations)
            assertEquals(0, output.size())
        }
    }

    @Test
    fun `middle paste requests remain independent and admission follows completion order`() {
        val requests = mutableListOf<SwingTerminalMiddleClickPasteRequest>()
        fixture(middleClickPasteHandler = { requests += it }) {
            click(MouseEvent.BUTTON2)
            click(MouseEvent.BUTTON2)
            assertEquals(2, requests.size)
            assertTrue(requests[1].complete("second"))
            assertTrue(requests[0].complete("first"))
            dispatcher.scheduler.runCurrent()
            assertEquals("secondfirst", output.toString(Charsets.UTF_8))
        }
    }

    @Test
    fun `middle paste completion reports rejection before writer startup`() {
        lateinit var request: SwingTerminalMiddleClickPasteRequest
        fixture(startSession = false, middleClickPasteHandler = { request = it }) {
            feed("hello")
            view.bind(session)
            click(MouseEvent.BUTTON2)
            var invalidations = 0
            view.addShellSuggestionInvalidationListener { invalidations++ }
            assertFalse(request.complete("candidate"))
            assertFalse(request.complete("retry"))
            assertEquals(1, invalidations)
            dispatcher.scheduler.runCurrent()
            assertEquals(0, output.size())
        }
    }

    @Test
    fun `middle paste completion reports bulk queue rejection without duplicate submission`() {
        lateinit var request: SwingTerminalMiddleClickPasteRequest
        fixture(middleClickPasteHandler = { request = it }) {
            click(MouseEvent.BUTTON2)
            repeat(16) {
                assertEquals(TerminalInputAdmission.ACCEPTED, session.submitInput(TerminalPasteEvent("queued")))
            }
            assertFalse(request.complete("candidate"))
            assertFalse(request.complete("retry"))
            assertTrue(session.isClosed)
            dispatcher.scheduler.runCurrent()
            assertEquals(0, output.size())
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["unbind", "close", "dispose", "same-session", "unbind-rebind"])
    fun `middle paste completion rechecks its binding after invalidation callbacks`(change: String) {
        lateinit var request: SwingTerminalMiddleClickPasteRequest
        fixture(middleClickPasteHandler = { request = it }) {
            click(MouseEvent.BUTTON2)
            var invalidations = 0
            view.addShellSuggestionInvalidationListener {
                invalidations++
                when (change) {
                    "unbind" -> view.unbind()
                    "close" -> session.close()
                    "dispose" -> view.dispose()
                    "same-session" -> view.bind(session)
                    "unbind-rebind" -> {
                        view.unbind()
                        view.bind(session)
                    }
                }
            }
            assertFalse(request.complete("candidate"))
            assertFalse(request.complete("retry"))
            assertEquals(1, invalidations)
            dispatcher.scheduler.runCurrent()
            assertEquals(0, output.size())
        }
    }

    @Test
    fun `middle paste completion is consumed before invalidation reentry`() {
        lateinit var request: SwingTerminalMiddleClickPasteRequest
        fixture(middleClickPasteHandler = { request = it }) {
            click(MouseEvent.BUTTON2)
            var invalidations = 0
            view.addShellSuggestionInvalidationListener {
                invalidations++
                assertFalse(request.complete("reentrant"))
                request.cancel()
            }
            assertTrue(request.complete("outer"))
            assertEquals(1, invalidations)
            dispatcher.scheduler.runCurrent()
            assertEquals("outer", output.toString(Charsets.UTF_8))
        }
    }

    @Test
    fun `middle paste invalidation failure propagates and consumes the request`() {
        lateinit var request: SwingTerminalMiddleClickPasteRequest
        fixture(middleClickPasteHandler = { request = it }) {
            click(MouseEvent.BUTTON2)
            val failure = IllegalStateException("invalidation failed")
            view.addShellSuggestionInvalidationListener { throw failure }
            assertSame(failure, assertThrows(IllegalStateException::class.java) { request.complete("candidate") })
            assertFalse(request.complete("retry"))
            dispatcher.scheduler.runCurrent()
            assertEquals(0, output.size())
        }
    }

    @Test
    fun `middle paste handler failure cancels unresolved work and propagates`() {
        lateinit var request: SwingTerminalMiddleClickPasteRequest
        val failure = IllegalStateException("host read failed")
        fixture(middleClickPasteHandler = {
            request = it
            throw failure
        }) {
            val press = event(MouseEvent.MOUSE_PRESSED, MouseEvent.BUTTON2)
            assertSame(failure, assertThrows(IllegalStateException::class.java) { view.mouseListeners.forEach { it.mousePressed(press) } })
            release(MouseEvent.BUTTON2)
            assertTrue(press.isConsumed)
            assertFalse(request.complete("late"))
            dispatcher.scheduler.runCurrent()
            assertEquals(0, reads)
            assertEquals(0, primaryReads)
            assertEquals(0, output.size())
        }
    }

    @Test
    fun `middle paste handler failure cannot retract an already admitted paste`() {
        lateinit var request: SwingTerminalMiddleClickPasteRequest
        val failure = IllegalStateException("host failed after admission")
        fixture(middleClickPasteHandler = {
            request = it
            assertTrue(it.complete("admitted"))
            throw failure
        }) {
            val press = event(MouseEvent.MOUSE_PRESSED, MouseEvent.BUTTON2)
            assertSame(failure, assertThrows(IllegalStateException::class.java) { view.mouseListeners.forEach { it.mousePressed(press) } })
            release(MouseEvent.BUTTON2)
            assertFalse(request.complete("late"))
            dispatcher.scheduler.runCurrent()
            assertEquals("admitted", output.toString(Charsets.UTF_8))
        }
    }

    @Test
    fun `primary selection read reentry cannot paste after rebinding the same session`() =
        fixture {
            settings = settings.copy { it.middleClickPasteSource = SwingPasteSource.PRIMARY_SELECTION }
            view.reloadSettings()
            onRead = { view.bind(session) }
            click(MouseEvent.BUTTON2)
            dispatcher.scheduler.runCurrent()
            assertEquals(0, reads)
            assertEquals(1, primaryReads)
            assertEquals(0, output.size())
        }

    @Test
    fun `off EDT middle paste attempts leave the request available for the EDT`() {
        lateinit var fixture: Fixture
        lateinit var request: SwingTerminalMiddleClickPasteRequest
        SwingUtilities.invokeAndWait {
            fixture = Fixture(startSession = true, middleClickPasteHandler = { request = it })
            fixture.click(MouseEvent.BUTTON2)
        }
        try {
            assertFalse(SwingUtilities.isEventDispatchThread())
            assertFalse(request.complete("wrong thread"))
            assertThrows(IllegalStateException::class.java) { request.cancel() }
            SwingUtilities.invokeAndWait {
                assertTrue(request.complete("EDT"))
                fixture.dispatcher.scheduler.runCurrent()
                assertEquals("EDT", fixture.output.toString(Charsets.UTF_8))
                assertEquals(0, fixture.reads)
                assertEquals(0, fixture.primaryReads)
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
    fun `host can obtain middle paste text on a worker and complete on the EDT`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        lateinit var fixture: Fixture
        lateinit var worker: Thread
        SwingUtilities.invokeAndWait {
            fixture =
                Fixture(startSession = true, middleClickPasteHandler = { request ->
                    assertTrue(SwingUtilities.isEventDispatchThread())
                    worker =
                        Thread({
                            try {
                                assertFalse(SwingUtilities.isEventDispatchThread())
                                entered.countDown()
                                release.await()
                                val text = "deferred host text"
                                SwingUtilities.invokeAndWait { assertTrue(request.complete(text)) }
                            } catch (problem: Throwable) {
                                failure.set(problem)
                            } finally {
                                completed.countDown()
                            }
                        }, "api09-clipboard-reader")
                    worker.start()
                })
            fixture.click(MouseEvent.BUTTON2)
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS), "clipboard worker did not enter")
            SwingUtilities.invokeAndWait {
                fixture.dispatcher.scheduler.runCurrent()
                assertEquals(0, fixture.output.size())
                assertEquals(0, fixture.reads)
                assertEquals(0, fixture.primaryReads)
            }
            release.countDown()
            assertTrue(completed.await(5, TimeUnit.SECONDS), "clipboard worker did not complete")
            failure.get()?.let { throw AssertionError("clipboard worker failed", it) }
            SwingUtilities.invokeAndWait {
                fixture.dispatcher.scheduler.runCurrent()
                assertEquals("deferred host text", fixture.output.toString(Charsets.UTF_8))
            }
        } finally {
            release.countDown()
            worker.join(5000)
            SwingUtilities.invokeAndWait {
                fixture.view.dispose()
                fixture.session.close()
                fixture.dispatcher.scheduler.runCurrent()
            }
        }
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
    @ValueSource(ints = [-3, 0, 5])
    fun `spaced cell bounds and mouse selection use the same columns`(spacing: Int) =
        fixture {
            settings = settings.copy { it.columnSpacing = spacing }
            view.reloadSettings()
            view.size = view.preferredGridSize(10, 2)
            view.bind(session)
            feed("\u001b[Habcdefghij")
            view.bind(session)
            val bounds = Rectangle()
            val position = Point()
            assertTrue(view.copyCellBounds(3, 0, bounds))
            val startX = bounds.x + bounds.width / 2
            assertTrue(view.copyCellPositionAt(startX, 1, position))
            assertEquals(Point(3, 0), position)
            val press = event(MouseEvent.MOUSE_PRESSED, MouseEvent.BUTTON1, x = startX)
            view.mouseListeners.forEach { it.mousePressed(press) }
            assertTrue(view.copyCellBounds(6, 0, bounds))
            val endX = bounds.x + bounds.width / 2
            assertTrue(view.copyCellPositionAt(endX, 1, position))
            assertEquals(Point(6, 0), position)
            val drag = event(MouseEvent.MOUSE_DRAGGED, MouseEvent.BUTTON1, modifiers = InputEvent.BUTTON1_DOWN_MASK, x = endX)
            view.mouseMotionListeners.forEach { it.mouseDragged(drag) }
            release(MouseEvent.BUTTON1)
            assertEquals("defg", clipboardText)
        }

    @ParameterizedTest
    @ValueSource(ints = [-3, 0, 5])
    fun `cell spacing maps pointer positions to terminal mouse coordinates`(spacing: Int) =
        fixture {
            settings = settings.copy { it.columnSpacing = spacing }
            view.reloadSettings()
            view.size = view.preferredGridSize(10, 2)
            view.bind(session)
            feed("\u001b[?1000h\u001b[?1006h")
            view.bind(session)
            val bounds = Rectangle()
            assertTrue(view.copyCellBounds(7, 0, bounds))
            val x = bounds.x + bounds.width / 2
            val position = Point()
            assertTrue(view.copyCellPositionAt(x, 1, position))
            assertEquals(Point(7, 0), position)

            val press = event(MouseEvent.MOUSE_PRESSED, MouseEvent.BUTTON1, x = x)
            view.mouseListeners.forEach { it.mousePressed(press) }
            val release = event(MouseEvent.MOUSE_RELEASED, MouseEvent.BUTTON1, x = x)
            view.mouseListeners.forEach { it.mouseReleased(release) }
            dispatcher.scheduler.runCurrent()

            assertEquals("\u001b[<0;8;1M\u001b[<0;8;1m", output.toString(Charsets.UTF_8))
            assertEquals(0, copies)
        }

    @Test
    fun `one pixel cells remain distinct in bounds and hit testing`() =
        fixture {
            val originalBounds = Rectangle()
            assertTrue(view.copyCellBounds(0, 0, originalBounds))
            val originalFont = view.font
            settings = settings.copy { it.columnSpacing = 1 - originalBounds.width }
            view.reloadSettings()
            dispatcher.scheduler.runCurrent()
            view.bind(session)
            assertEquals(originalFont, view.font)
            assertEquals(10, view.preferredGridSize(10, 2).width)
            val bounds = Rectangle()
            val position = Point()
            for (column in 0 until 10) {
                assertTrue(view.copyCellBounds(column, 0, bounds))
                assertEquals(Rectangle(column, 0, 1, originalBounds.height), bounds)
                assertTrue(view.copyCellPositionAt(column, 1, position))
                assertEquals(Point(column, 0), position)
            }
        }

    @ParameterizedTest
    @ValueSource(strings = ["zero", "negative", "underflow", "overflow"])
    fun `invalid spacing reload retains the previous view and permits recovery`(invalidWidth: String) =
        fixture {
            feed("\u001b[?1000h\u001b[?1006h")
            view.bind(session)
            assertTrue(view.selectAll())
            val originalSelection = view.currentSelectionRange()
            val originalSettings = settings
            val originalSize = view.preferredGridSize(10, 2)
            val originalVisibleSize = view.visibleGridSize()
            val originalFont = view.font
            val originalBounds = Rectangle()
            assertTrue(view.copyCellBounds(3, 0, originalBounds))
            val replacementFont = originalFont.deriveFont(originalFont.size2D + 2f)
            val replacementFontWidth = maxOf(1, view.getFontMetrics(replacementFont).charWidth('W'))
            settings =
                settings.copy {
                    it.font = replacementFont
                    it.padding = SwingPadding(3, 7, 2, 5)
                    it.mouseReportingEnabled = false
                    it.columnSpacing =
                        when (invalidWidth) {
                            "zero" -> -replacementFontWidth
                            "negative" -> -replacementFontWidth - 1
                            "underflow" -> Int.MIN_VALUE
                            else -> Int.MAX_VALUE
                        }
                }

            assertThrows(IllegalArgumentException::class.java) { view.reloadSettings() }
            assertEquals(originalFont, view.font)
            assertEquals(originalSize, view.preferredGridSize(10, 2))
            assertEquals(originalVisibleSize, view.visibleGridSize())
            assertSame(originalSelection, view.currentSelectionRange())
            val bounds = Rectangle()
            assertTrue(view.copyCellBounds(3, 0, bounds))
            assertEquals(originalBounds, bounds)
            val x = bounds.x + bounds.width / 2
            val position = Point()
            assertTrue(view.copyCellPositionAt(x, 1, position))
            assertEquals(Point(3, 0), position)
            val press = event(MouseEvent.MOUSE_PRESSED, MouseEvent.BUTTON1, x = x)
            view.mouseListeners.forEach { it.mousePressed(press) }
            val release = event(MouseEvent.MOUSE_RELEASED, MouseEvent.BUTTON1, x = x)
            view.mouseListeners.forEach { it.mouseReleased(release) }
            dispatcher.scheduler.runCurrent()
            assertEquals("\u001b[<0;4;1M\u001b[<0;4;1m", output.toString(Charsets.UTF_8))

            settings = originalSettings.copy { it.columnSpacing = -1 }
            view.reloadSettings()
            dispatcher.scheduler.runCurrent()
            view.bind(session)
            assertEquals(originalSize.width - 10, view.preferredGridSize(10, 2).width)
            assertEquals(originalSize.height, view.preferredGridSize(10, 2).height)
            assertTrue(view.copyCellBounds(3, 0, bounds))
            assertEquals(originalBounds.width - 1, bounds.width)
            assertTrue(view.copyCellPositionAt(bounds.x + bounds.width / 2, 1, position))
            assertEquals(Point(3, 0), position)
        }

    private fun fixture(
        startSession: Boolean = true,
        middleClickPasteHandler: SwingTerminalMiddleClickPasteHandler? = null,
        action: Fixture.() -> Unit,
    ) {
        SwingUtilities.invokeAndWait {
            val fixture = Fixture(startSession, middleClickPasteHandler)
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
        middleClickPasteHandler: SwingTerminalMiddleClickPasteHandler? = null,
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
        var primaryText: String? = "primary"
        var clipboardAvailable = true
        var reads = 0
        var primaryReads = 0
        var copies = 0
        var onRead: () -> Unit = {}
        var onCopy: () -> Unit = {}
        val view =
            SwingTerminal(
                { settings },
                SwingHostServices.create {
                    it.scrollbarOverlayEnabled = true
                    it.middleClickPasteHandler = middleClickPasteHandler
                    it.clipboardHandler =
                        object : TerminalClipboardHandler {
                            override fun readText(): String? {
                                reads++
                                onRead()
                                return clipboardText.takeIf { clipboardAvailable }
                            }

                            override fun readPrimarySelectionText(): String? {
                                primaryReads++
                                onRead()
                                return primaryText
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
