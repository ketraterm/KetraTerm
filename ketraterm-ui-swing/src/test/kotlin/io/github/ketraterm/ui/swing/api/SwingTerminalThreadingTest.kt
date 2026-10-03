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
import io.github.ketraterm.core.api.TerminalBuffer
import io.github.ketraterm.input.api.TerminalInputEncoder
import io.github.ketraterm.input.api.TerminalInputEncoderFactory
import io.github.ketraterm.input.event.TerminalFocusEvent
import io.github.ketraterm.input.event.TerminalKeyEvent
import io.github.ketraterm.input.event.TerminalMouseEvent
import io.github.ketraterm.input.event.TerminalPasteEvent
import io.github.ketraterm.input.policy.PasteControlPolicy
import io.github.ketraterm.input.policy.PasteLineEndingPolicy
import io.github.ketraterm.input.policy.TerminalInputPolicy
import io.github.ketraterm.parser.api.TerminalOutputParser
import io.github.ketraterm.render.api.TerminalColorPalette
import io.github.ketraterm.render.api.TerminalRenderCursorShape
import io.github.ketraterm.render.api.TerminalRenderFrameConsumer
import io.github.ketraterm.render.api.TerminalRenderFrameReader
import io.github.ketraterm.render.cache.TerminalRenderPublisher
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.transport.TerminalConnectorListener
import io.github.ketraterm.ui.swing.settings.SwingSettings
import io.github.ketraterm.ui.swing.settings.TerminalClipboardHandler
import io.github.ketraterm.ui.swing.settings.TerminalTheme
import io.github.ketraterm.ui.swing.suggestion.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.awt.*
import java.awt.event.ComponentEvent
import java.awt.event.FocusEvent
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.concurrent.thread

@OptIn(ExperimentalCoroutinesApi::class)
class SwingTerminalThreadingTest {
    private val dispatcher = StandardTestDispatcher()

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `focus reports use the mode at each transition before outbound writes run`(temporary: Boolean) {
        val output = ByteArrayOutputStream()
        val session = focusSession(output)
        val component = edtCall { SwingTerminal() }
        try {
            edtCall {
                component.bind(session)
                val gained = FocusEvent(component, FocusEvent.FOCUS_GAINED, temporary)
                val lost = FocusEvent(component, FocusEvent.FOCUS_LOST, temporary)
                val enable = "\u001B[?1004h".toByteArray(Charsets.US_ASCII)
                val disable = "\u001B[?1004l".toByteArray(Charsets.US_ASCII)

                component.focusListeners.forEach { it.focusGained(gained) }
                session.onBytes(enable, 0, enable.size)
                component.focusListeners.forEach { it.focusGained(gained) }
                session.onBytes(disable, 0, disable.size)
                component.focusListeners.forEach { it.focusLost(lost) }
                session.onBytes(enable, 0, enable.size)
                component.focusListeners.forEach { it.focusLost(lost) }
                session.onBytes(disable, 0, disable.size)
            }
            assertEquals(0, output.size(), "focus callbacks queue output without writing on the EDT")
            dispatcher.scheduler.runCurrent()
            assertEquals("\u001B[I\u001B[O", output.toString(Charsets.US_ASCII))
        } finally {
            edtCall { component.dispose() }
            session.close()
            dispatcher.scheduler.runCurrent()
        }
    }

    @Test
    fun `focus events after session closure produce no output`() {
        val output = ByteArrayOutputStream()
        val session = focusSession(output)
        val component = edtCall { SwingTerminal() }
        try {
            val enable = "\u001B[?1004h".toByteArray(Charsets.US_ASCII)
            session.onBytes(enable, 0, enable.size)
            edtCall { component.bind(session) }
            session.close()
            edtCall { dispatchFocusCycle(component) }
            dispatcher.scheduler.runCurrent()
            assertTrue(session.isClosed)
            assertEquals("", output.toString(Charsets.US_ASCII))
        } finally {
            edtCall { component.dispose() }
            session.close()
            dispatcher.scheduler.runCurrent()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["default", "enabled", "disabled"])
    fun `Swing focus transitions obey DEC1004 reporting mode`(mode: String) {
        val output = ByteArrayOutputStream()
        val session = focusSession(output)
        val component = edtCall { SwingTerminal() }
        try {
            edtCall { component.bind(session) }
            val control =
                when (mode) {
                    "default" -> ""
                    "enabled" -> "\u001B[?1004h"
                    "disabled" -> "\u001B[?1004h\u001B[?1004l"
                    else -> error("Unexpected mode $mode")
                }.toByteArray(Charsets.US_ASCII)
            session.onBytes(control, 0, control.size)

            edtCall { dispatchFocusCycle(component) }
            dispatcher.scheduler.runCurrent()

            assertEquals(if (mode == "enabled") "\u001B[I\u001B[O" else "", output.toString(Charsets.US_ASCII))
        } finally {
            edtCall { component.dispose() }
            session.close()
            dispatcher.scheduler.runCurrent()
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `unbound and disposed surfaces cannot report focus to their former session`(dispose: Boolean) {
        val output = ByteArrayOutputStream()
        val session = focusSession(output)
        val component = edtCall { SwingTerminal() }
        try {
            val enable = "\u001B[?1004h".toByteArray(Charsets.US_ASCII)
            session.onBytes(enable, 0, enable.size)
            edtCall {
                component.bind(session)
                if (dispose) component.dispose() else component.unbind()
            }
            dispatcher.scheduler.runCurrent()
            output.reset()

            edtCall { dispatchFocusCycle(component) }
            dispatcher.scheduler.runCurrent()

            assertEquals("", output.toString(Charsets.US_ASCII))
            assertFalse(session.isClosed, "The component must leave its host-owned session open")
        } finally {
            edtCall { component.dispose() }
            session.close()
            dispatcher.scheduler.runCurrent()
        }
    }

    @Test
    fun `focus reports after rebinding target only the current session`() {
        val firstOutput = ByteArrayOutputStream()
        val secondOutput = ByteArrayOutputStream()
        val first = focusSession(firstOutput)
        val second = focusSession(secondOutput)
        val component = edtCall { SwingTerminal() }
        try {
            val enable = "\u001B[?1004h".toByteArray(Charsets.US_ASCII)
            first.onBytes(enable, 0, enable.size)
            second.onBytes(enable, 0, enable.size)
            edtCall {
                component.bind(first)
                component.bind(second)
            }
            dispatcher.scheduler.runCurrent()
            firstOutput.reset()
            secondOutput.reset()

            edtCall { dispatchFocusCycle(component) }
            dispatcher.scheduler.runCurrent()

            assertAll(
                { assertEquals("", firstOutput.toString(Charsets.US_ASCII)) },
                { assertEquals("\u001B[I\u001B[O", secondOutput.toString(Charsets.US_ASCII)) },
                { assertFalse(first.isClosed) },
                { assertFalse(second.isClosed) },
            )
        } finally {
            edtCall { component.dispose() }
            first.close()
            second.close()
            dispatcher.scheduler.runCurrent()
        }
    }

    @Test
    fun `clipboard paste larger than byte queue is accepted on EDT and streamed on IO dispatcher`() {
        val text = "x".repeat(9 * 1024 * 1024)
        var written = 0
        val connector =
            object : TerminalConnector by NoOpConnector {
                override fun write(
                    bytes: ByteArray,
                    offset: Int,
                    length: Int,
                ) {
                    assertFalse(SwingUtilities.isEventDispatchThread())
                    assertTrue(length <= 16 * 1024)
                    written += length
                }
            }
        val session =
            TerminalSession.create(
                terminal = TerminalBuffers.create(width = 3, height = 1),
                connector = connector,
                workerDispatcher = dispatcher,
                ioDispatcher = dispatcher,
            )
        val component =
            SwingTerminal(
                hostServices =
                    SwingHostServices(
                        clipboardHandler =
                            object : TerminalClipboardHandler {
                                override fun copyText(text: String) = error("Unexpected clipboard write")

                                override fun readText(): String = text
                            },
                    ),
            )
        try {
            session.start(columns = 3, rows = 1)
            edtCall {
                component.bind(session)
                assertTrue(component.pasteClipboardText())
                assertEquals(0, written)
                assertFalse(session.isClosed)
            }
            dispatcher.scheduler.runCurrent()
            assertNull(session.failure)
            assertEquals(text.length, written)
        } finally {
            edtCall { component.dispose() }
            session.close()
            dispatcher.scheduler.runCurrent()
        }
    }

    @Test
    fun `paste policy applies on binding and reload while preserving transport line endings`() {
        val output = ByteArrayOutputStream()
        val connector =
            object : TerminalConnector by NoOpConnector {
                override fun write(
                    bytes: ByteArray,
                    offset: Int,
                    length: Int,
                ) {
                    output.write(bytes, offset, length)
                }
            }
        val session =
            TerminalSession.create(
                terminal = TerminalBuffers.create(width = 3, height = 1),
                connector = connector,
                inputPolicy = TerminalInputPolicy(pasteLineEndingPolicy = PasteLineEndingPolicy.CARRIAGE_RETURN),
                ioDispatcher = dispatcher,
            )
        var settings = SwingSettings(pasteControlPolicy = PasteControlPolicy.STRIP_C0_EXCEPT_TAB_CR_LF)
        val component = SwingTerminal(settingsProvider = { settings })
        val paste = TerminalPasteEvent("A\u0001\tB\r\nC\nD\rE")
        try {
            session.start(columns = 3, rows = 1)
            edtCall {
                component.bind(session)
                session.encodePaste(paste)
                dispatcher.scheduler.runCurrent()
                assertEquals("A\tB\rC\rD\rE", output.toString(Charsets.UTF_8))
                output.reset()
                val enableBracketed = "\u001B[?2004h".toByteArray(Charsets.US_ASCII)
                session.onBytes(enableBracketed, 0, enableBracketed.size)
                session.encodePaste(paste)
                dispatcher.scheduler.runCurrent()
                assertEquals("\u001B[200~A\tB\r\nC\nD\rE\u001B[201~", output.toString(Charsets.UTF_8))
                settings = settings.copy(pasteControlPolicy = PasteControlPolicy.PRESERVE)
                component.reloadSettings()
                output.reset()
                session.encodePaste(paste)
                dispatcher.scheduler.runCurrent()
                assertEquals("\u001B[200~A\u0001\tB\r\nC\nD\rE\u001B[201~", output.toString(Charsets.UTF_8))
                val disableBracketed = "\u001B[?2004l".toByteArray(Charsets.US_ASCII)
                session.onBytes(disableBracketed, 0, disableBracketed.size)
                output.reset()
                session.encodePaste(paste)
                dispatcher.scheduler.runCurrent()
                assertEquals("A\u0001\tB\rC\rD\rE", output.toString(Charsets.UTF_8))
            }
        } finally {
            edtCall { component.dispose() }
            session.close()
        }
    }

    @Test
    fun `host palette binding and reload update color scheme replies`() {
        val replies = ByteArrayOutputStream()
        val connector =
            object : TerminalConnector by NoOpConnector {
                override fun write(
                    bytes: ByteArray,
                    offset: Int,
                    length: Int,
                ) {
                    replies.write(bytes, offset, length)
                }
            }
        val session =
            TerminalSession.create(
                terminal = TerminalBuffers.create(width = 3, height = 1),
                connector = connector,
                ioDispatcher = dispatcher,
            )
        var settings =
            SwingSettings(
                palette =
                    TerminalColorPalette(
                        defaultForeground = 0xff000000.toInt(),
                        defaultBackground = 0xffffffff.toInt(),
                        isDark = false,
                    ),
            )
        val component = SwingTerminal(settingsProvider = { settings })
        val query = "\u001B[?996n".toByteArray(Charsets.US_ASCII)
        try {
            session.start(3, 1)
            edtCall {
                component.bind(session)
                session.onBytes(query, 0, query.size)
                dispatcher.scheduler.runCurrent()
                assertEquals("\u001B[?997;2n", replies.toString(Charsets.US_ASCII))
                settings = settings.copy(palette = TerminalTheme.NORD.createPalette())
                component.reloadSettings()
                assertEquals("\u001B[?997;2n", replies.toString(Charsets.US_ASCII))
                session.onBytes(query, 0, query.size)
                dispatcher.scheduler.runCurrent()
                assertEquals("\u001B[?997;2n\u001B[?997;1n", replies.toString(Charsets.US_ASCII))
            }
        } finally {
            edtCall { component.dispose() }
            session.close()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["select", "bind", "unbind"])
    fun `currentSelection snapshots state after queued EDT changes`(change: String) {
        val session = testSession()
        session.renderPublisher.updateAndPublish(session)
        val component = SwingTerminal(settingsProvider = { SwingSettings(columns = 3, rows = 1, cursorBlinkMillis = 0) })
        val edtBlocked = CountDownLatch(1)
        val releaseEdt = CountDownLatch(1)
        val snapshotQueuedOrReturned = CountDownLatch(1)
        val result = AtomicReference<CellSelection?>()
        val failure = AtomicReference<Throwable?>()
        val worker =
            thread(start = false, name = "selection-snapshot-reader") {
                try {
                    result.set(component.currentSelection())
                } catch (error: Throwable) {
                    failure.set(error)
                } finally {
                    snapshotQueuedOrReturned.countDown()
                }
            }
        val eventQueue =
            object : EventQueue() {
                override fun postEvent(event: AWTEvent) {
                    super.postEvent(event)
                    if (Thread.currentThread() === worker) snapshotQueuedOrReturned.countDown()
                }

                fun restore() = pop()
            }
        edtCall {
            Toolkit.getDefaultToolkit().systemEventQueue.push(eventQueue)
        }
        try {
            edtCall {
                if (change != "bind") component.bind(session)
                if (change == "unbind") assertTrue(component.selectAll())
            }
            SwingUtilities.invokeLater {
                try {
                    edtBlocked.countDown()
                    check(releaseEdt.await(5, TimeUnit.SECONDS)) { "EDT blocker was not released" }
                    when (change) {
                        "select" -> assertTrue(component.selectAll())
                        "bind" -> {
                            component.bind(session)
                            assertTrue(component.selectAll())
                        }
                        "unbind" -> component.unbind()
                    }
                } catch (error: Throwable) {
                    failure.set(error)
                }
            }
            assertTrue(edtBlocked.await(5, TimeUnit.SECONDS))
            worker.start()
            // Wait for dispatch or an incorrect early return, without racing the EDT mutation.
            assertTrue(snapshotQueuedOrReturned.await(5, TimeUnit.SECONDS))
            releaseEdt.countDown()
            worker.join(5_000)
            assertFalse(worker.isAlive, "Selection snapshot did not complete")
            drainEdt()
            failure.get()?.let { throw it }
            assertEquals(if (change == "unbind") null else CellSelection(0, 0, 3, 0), result.get())
        } finally {
            releaseEdt.countDown()
            edtCall {
                component.dispose()
                eventQueue.restore()
            }
            worker.join(5_000)
            session.close()
        }
    }

    @Test
    fun `dispose cancels the component coroutine scope`() {
        val component = SwingTerminal()

        SwingUtilities.invokeAndWait {
            assertTrue(component.isCoroutineScopeActive)
            component.dispose()
            assertFalse(component.isCoroutineScopeActive)
        }
    }

    @ParameterizedTest(name = "dispose completes cleanup after eligibility callback {0}")
    @ValueSource(strings = ["failure", "cancellation"])
    fun `dispose completes cleanup when an eligibility callback throws`(failureKind: String) {
        val failure =
            when (failureKind) {
                "failure" -> IllegalStateException("host eligibility callback failed")
                "cancellation" -> CancellationException("host eligibility callback cancelled")
                else -> error("Unexpected failure kind $failureKind")
            }
        val session = focusSession(ByteArrayOutputStream())
        val component =
            edtCall {
                SwingTerminal(settingsProvider = {
                    SwingSettings(smartSuggestionsEnabled = true, cursorBlinkMillis = 0, useSystemFallbackFonts = false)
                })
            }
        var notifications = 0
        val listener =
            SwingShellSuggestionEligibilityListener { eligible ->
                assertFalse(eligible)
                notifications++
                throw failure
            }
        try {
            edtCall {
                component.bind(session)
                assertTrue(component.isAutomaticShellSuggestionEligible())
                component.addShellSuggestionEligibilityListener(listener)
                component.cursorTimer.start()
                assertTrue(component.cursorTimer.isRunning)
                assertTrue(component.isCoroutineScopeActive)

                assertSame(failure, assertThrows(RuntimeException::class.java) { component.dispose() })
                assertAll(
                    { assertFalse(component.isCoroutineScopeActive, "Disposal must cancel the component scope despite host failure") },
                    { assertFalse(component.cursorTimer.isRunning, "Disposal must stop the cursor timer despite host failure") },
                    { assertFalse(session.isClosed, "Disposal must preserve the host-owned session") },
                    { assertFalse(component.isAutomaticShellSuggestionEligible()) },
                    { assertEquals(1, notifications) },
                )

                component.dispose()
                assertEquals(1, notifications, "Repeated disposal must not notify the failing listener again")
                assertFalse(component.isCoroutineScopeActive)
                assertFalse(component.cursorTimer.isRunning)
                assertFalse(session.isClosed)
            }
        } finally {
            edtCall {
                component.removeShellSuggestionEligibilityListener(listener)
                component.cursorTimer.stop()
                component.dispose()
            }
            session.close()
            dispatcher.scheduler.runCurrent()
        }
    }

    @Test
    fun `disposal viewport callback observes committed eligibility and snapshot before eligibility notification`() {
        var observeDisposal = false
        val events = ArrayList<String>()
        val cachedEligibility = ArrayList<Boolean>()
        val callbackStates = ArrayList<TerminalViewportState>()
        val observedSnapshots = ArrayList<TerminalViewportState>()
        val eligibilityChanges = ArrayList<Pair<Boolean, Boolean>>()
        lateinit var component: SwingTerminal
        val viewportListener =
            object : TerminalViewportListener {
                override fun viewportChanged(
                    historySize: Int,
                    scrollbackOffset: Double,
                    renderOffset: Int,
                    visibleRows: Int,
                    requestedRows: Int,
                ) = Unit

                override fun viewportStateChanged(state: TerminalViewportState) {
                    if (observeDisposal) {
                        events += "viewport-enter"
                        callbackStates += state
                        cachedEligibility += component.isAutomaticShellSuggestionEligible()
                        observedSnapshots += component.viewportState()
                        events += "viewport-exit"
                    }
                }
            }
        val session = focusSession(ByteArrayOutputStream())
        component =
            edtCall {
                SwingTerminal(
                    settingsProvider = {
                        SwingSettings(smartSuggestionsEnabled = true, cursorBlinkMillis = 0, useSystemFallbackFonts = false)
                    },
                    hostServices = SwingHostServices(viewportListener = viewportListener),
                )
            }
        val eligibilityListener =
            SwingShellSuggestionEligibilityListener { eligible ->
                events += "eligibility"
                eligibilityChanges += eligible to component.isAutomaticShellSuggestionEligible()
            }
        try {
            edtCall {
                component.bind(session)
                assertTrue(component.isAutomaticShellSuggestionEligible())
                component.addShellSuggestionEligibilityListener(eligibilityListener)
                observeDisposal = true

                component.dispose()

                assertAll(
                    { assertEquals(listOf(false), cachedEligibility) },
                    { assertEquals(1, callbackStates.size) },
                    { assertEquals(callbackStates, observedSnapshots) },
                    { assertEquals(listOf("viewport-enter", "viewport-exit", "eligibility"), events) },
                    { assertEquals(listOf(false to false), eligibilityChanges) },
                    { assertFalse(component.isCoroutineScopeActive) },
                    { assertFalse(session.isClosed) },
                )
            }
        } finally {
            edtCall {
                observeDisposal = false
                component.removeShellSuggestionEligibilityListener(eligibilityListener)
                component.dispose()
            }
            session.close()
            dispatcher.scheduler.runCurrent()
        }
    }

    @Test
    fun `reentrant disposal stops stale eligibility notification to later listeners`() {
        var currentSettings = SwingSettings(smartSuggestionsEnabled = false, cursorBlinkMillis = 0, useSystemFallbackFonts = false)
        val firstChanges = ArrayList<Pair<Boolean, Boolean>>()
        val laterChanges = ArrayList<Pair<Boolean, Boolean>>()
        val session = focusSession(ByteArrayOutputStream())
        val component = edtCall { SwingTerminal(settingsProvider = { currentSettings }) }
        val disposingListener =
            SwingShellSuggestionEligibilityListener { eligible ->
                firstChanges += eligible to component.isAutomaticShellSuggestionEligible()
                if (eligible) component.dispose()
            }
        val laterListener =
            SwingShellSuggestionEligibilityListener { eligible ->
                laterChanges += eligible to component.isAutomaticShellSuggestionEligible()
            }
        try {
            edtCall {
                component.bind(session)
                assertFalse(component.isAutomaticShellSuggestionEligible())
                component.addShellSuggestionEligibilityListener(disposingListener)
                component.addShellSuggestionEligibilityListener(laterListener)
                component.cursorTimer.start()
                assertTrue(component.cursorTimer.isRunning)
                currentSettings = currentSettings.copy(smartSuggestionsEnabled = true)

                component.reloadSettings()

                assertAll(
                    { assertEquals(listOf(true to true, false to false), firstChanges) },
                    { assertEquals(listOf(false to false), laterChanges) },
                    { assertFalse(component.isAutomaticShellSuggestionEligible()) },
                    { assertFalse(component.isCoroutineScopeActive) },
                    { assertFalse(component.cursorTimer.isRunning) },
                    { assertFalse(session.isClosed) },
                )
            }
        } finally {
            edtCall {
                component.removeShellSuggestionEligibilityListener(disposingListener)
                component.removeShellSuggestionEligibilityListener(laterListener)
                component.cursorTimer.stop()
                component.dispose()
            }
            session.close()
            dispatcher.scheduler.runCurrent()
        }
    }

    @ParameterizedTest(name = "dispose publishes ineligibility after viewport callback {0}")
    @ValueSource(strings = ["failure", "cancellation"])
    fun `dispose publishes ineligibility when a viewport callback throws`(failureKind: String) {
        val failure =
            when (failureKind) {
                "failure" -> IllegalStateException("host viewport callback failed")
                "cancellation" -> CancellationException("host viewport callback cancelled")
                else -> error("Unexpected failure kind $failureKind")
            }
        var failViewportUpdates = false
        var notifications = 0
        val session = focusSession(ByteArrayOutputStream())
        val component =
            edtCall {
                SwingTerminal(
                    settingsProvider = {
                        SwingSettings(smartSuggestionsEnabled = true, cursorBlinkMillis = 0, useSystemFallbackFonts = false)
                    },
                    hostServices =
                        SwingHostServices(
                            viewportListener =
                                TerminalViewportListener { _, _, _, _, _ ->
                                    if (failViewportUpdates) {
                                        notifications++
                                        throw failure
                                    }
                                },
                        ),
                )
            }
        try {
            edtCall {
                component.bind(session)
                assertTrue(component.isAutomaticShellSuggestionEligible())
                component.cursorTimer.start()
                assertTrue(component.cursorTimer.isRunning)
                assertTrue(component.isCoroutineScopeActive)
                failViewportUpdates = true

                assertSame(failure, assertThrows(RuntimeException::class.java) { component.dispose() })
                assertEquals(1, notifications)
                component.dispose()

                assertAll(
                    {
                        assertFalse(
                            component.isAutomaticShellSuggestionEligible(),
                            "Disposal must publish ineligibility despite viewport failure",
                        )
                    },
                    { assertFalse(component.isCoroutineScopeActive, "Disposal must cancel the component scope despite viewport failure") },
                    { assertFalse(component.cursorTimer.isRunning, "Disposal must stop the cursor timer despite viewport failure") },
                    { assertFalse(session.isClosed, "Disposal must preserve the host-owned session") },
                    { assertEquals(1, notifications, "Repeated disposal must not invoke the failing viewport callback again") },
                    { assertTrue(failure.suppressed.isEmpty()) },
                )
            }
        } finally {
            edtCall {
                failViewportUpdates = false
                component.cursorTimer.stop()
                component.dispose()
            }
            session.close()
            dispatcher.scheduler.runCurrent()
        }
    }

    @Test
    fun `dispose preserves viewport failure and suppresses the first eligibility listener failure`() {
        val viewportFailure = IllegalStateException("host viewport callback failed")
        val eligibilityFailure = IllegalArgumentException("host eligibility callback failed")
        var failViewportUpdates = false
        var laterNotifications = 0
        val events = ArrayList<String>()
        val eligibilityListener =
            SwingShellSuggestionEligibilityListener { eligible ->
                assertFalse(eligible)
                events += "eligibility"
                throw eligibilityFailure
            }
        val laterListener = SwingShellSuggestionEligibilityListener { laterNotifications++ }
        val session = focusSession(ByteArrayOutputStream())
        val component =
            edtCall {
                SwingTerminal(
                    settingsProvider = {
                        SwingSettings(smartSuggestionsEnabled = true, cursorBlinkMillis = 0, useSystemFallbackFonts = false)
                    },
                    hostServices =
                        SwingHostServices(
                            viewportListener =
                                TerminalViewportListener { _, _, _, _, _ ->
                                    if (failViewportUpdates) {
                                        events += "viewport"
                                        throw viewportFailure
                                    }
                                },
                        ),
                )
            }
        try {
            edtCall {
                component.bind(session)
                assertTrue(component.isAutomaticShellSuggestionEligible())
                component.addShellSuggestionEligibilityListener(eligibilityListener)
                component.addShellSuggestionEligibilityListener(laterListener)
                component.cursorTimer.start()
                assertTrue(component.cursorTimer.isRunning)
                assertTrue(component.isCoroutineScopeActive)
                failViewportUpdates = true

                val thrown = assertThrows(IllegalStateException::class.java) { component.dispose() }

                assertSame(viewportFailure, thrown)
                assertAll(
                    { assertEquals(listOf("viewport", "eligibility"), events) },
                    { assertEquals(1, thrown.suppressed.size) },
                    { assertSame(eligibilityFailure, thrown.suppressed.single()) },
                    { assertEquals(0, laterNotifications, "Eligibility dispatch must stop at the first failing listener") },
                    { assertFalse(component.isAutomaticShellSuggestionEligible()) },
                    { assertFalse(component.isCoroutineScopeActive) },
                    { assertFalse(component.cursorTimer.isRunning) },
                    { assertFalse(session.isClosed, "Disposal must preserve the host-owned session") },
                )
            }
        } finally {
            edtCall {
                failViewportUpdates = false
                component.removeShellSuggestionEligibilityListener(eligibilityListener)
                component.removeShellSuggestionEligibilityListener(laterListener)
                component.cursorTimer.stop()
                component.dispose()
            }
            session.close()
            dispatcher.scheduler.runCurrent()
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `dispose releases custom view and scope despite multiple callback failures`(cancelled: Boolean) {
        val first = if (cancelled) CancellationException("hide cancelled") else IllegalStateException("hide failed")
        val eligibilityFailure = IllegalArgumentException("eligibility failed")
        val closeFailure = IllegalStateException("close failed")
        var failUpdates = false
        var closes = 0
        val view =
            object : SwingShellSuggestionView {
                override val component = JPanel()

                override fun update(snapshot: SwingShellSuggestionViewSnapshot) {
                    if (failUpdates) throw first
                }

                override fun close() {
                    closes++
                    throw closeFailure
                }
            }
        val session = focusSession(ByteArrayOutputStream())
        val component =
            edtCall {
                SwingTerminal(
                    settingsProvider = {
                        SwingSettings(smartSuggestionsEnabled = true, cursorBlinkMillis = 0, useSystemFallbackFonts = false)
                    },
                    hostServices = SwingHostServices(shellSuggestionViewFactory = { view }),
                )
            }
        try {
            edtCall {
                component.bind(session)
                component.showShellSuggestions(
                    SwingShellSuggestionRequest.EMPTY,
                    listOf(SwingShellSuggestion("test", 0, 0, "test", "COMMAND")),
                )
                assertTrue(component.currentShellSuggestionState().visible)
                component.addShellSuggestionEligibilityListener { eligible ->
                    assertFalse(eligible)
                    throw eligibilityFailure
                }
                component.cursorTimer.start()
                failUpdates = true

                assertSame(first, assertThrows(RuntimeException::class.java) { component.dispose() })
                assertEquals(2, first.suppressed.size)
                assertSame(eligibilityFailure, first.suppressed[0])
                assertSame(closeFailure, first.suppressed[1])
                assertFalse(component.isCoroutineScopeActive)
                assertFalse(component.cursorTimer.isRunning)
                assertFalse(component.isAutomaticShellSuggestionEligible())
                assertFalse(component.currentShellSuggestionState().visible)
                assertFalse(view.component.isVisible)
                assertFalse(session.isClosed)
                assertEquals(1, closes)

                component.dispose()
                assertEquals(1, closes, "Repeat disposal must not release the custom view twice")
                assertEquals(2, first.suppressed.size)
            }
        } finally {
            edtCall { component.dispose() }
            session.close()
            dispatcher.scheduler.runCurrent()
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `reentrant custom view hiding or disposal supersedes an unfinished show`(dispose: Boolean) {
        lateinit var terminal: SwingTerminal
        var armed = true
        var closes = 0
        var updatesAfterClose = 0
        val view =
            object : SwingShellSuggestionView {
                override val component = JPanel()

                override fun update(snapshot: SwingShellSuggestionViewSnapshot) {
                    if (closes > 0) updatesAfterClose++
                    if (armed && snapshot.visibleSuggestions.isNotEmpty()) {
                        armed = false
                        if (dispose) terminal.dispose() else terminal.hideShellSuggestions()
                    }
                }

                override fun close() {
                    closes++
                }
            }
        terminal =
            edtCall {
                SwingTerminal(
                    settingsProvider = {
                        SwingSettings(smartSuggestionsEnabled = true, cursorBlinkMillis = 0, useSystemFallbackFonts = false)
                    },
                    hostServices = SwingHostServices(shellSuggestionViewFactory = { view }),
                )
            }
        try {
            edtCall {
                val suggestion = SwingShellSuggestion("test", 0, 0, "test", "COMMAND")
                terminal.showShellSuggestions(SwingShellSuggestionRequest.EMPTY, listOf(suggestion))

                assertAll(
                    { assertFalse(armed, "The host view must perform the reentrant transition during show") },
                    { assertFalse(view.component.isVisible, "An older show must not make the hidden or disposed view visible again") },
                    { assertEquals(SwingShellSuggestionState.EMPTY, terminal.currentShellSuggestionState()) },
                    { assertEquals(if (dispose) 1 else 0, closes) },
                    { assertEquals(0, updatesAfterClose, "The disposed host view must not receive later presentation updates") },
                    { assertEquals(!dispose, terminal.isCoroutineScopeActive) },
                )

                terminal.showShellSuggestions(SwingShellSuggestionRequest.EMPTY, listOf(suggestion))
                assertEquals(!dispose, view.component.isVisible, "Hiding permits reuse; disposal rejects later show requests")
                assertEquals(0, updatesAfterClose)
            }
        } finally {
            edtCall { terminal.dispose() }
            dispatcher.scheduler.runCurrent()
        }
    }

    @Test
    fun `unbind clears the session and permits rebinding after a custom view failure`() {
        val failure = IllegalStateException("hide failed")
        var failUpdates = false
        val view =
            object : SwingShellSuggestionView {
                override val component = JPanel()

                override fun update(snapshot: SwingShellSuggestionViewSnapshot) {
                    if (failUpdates) throw failure
                }
            }
        val output = ByteArrayOutputStream()
        val session = focusSession(output)
        val component =
            edtCall {
                SwingTerminal(
                    settingsProvider = {
                        SwingSettings(smartSuggestionsEnabled = true, cursorBlinkMillis = 0, useSystemFallbackFonts = false)
                    },
                    hostServices = SwingHostServices(shellSuggestionViewFactory = { view }),
                )
            }
        try {
            edtCall {
                component.bind(session)
                component.showShellSuggestions(
                    SwingShellSuggestionRequest.EMPTY,
                    listOf(SwingShellSuggestion("test", 0, 0, "test", "COMMAND")),
                )
                failUpdates = true
                assertSame(failure, assertThrows(IllegalStateException::class.java) { component.unbind() })
                assertTrue(component.isCoroutineScopeActive)
                assertFalse(session.isClosed)
                assertFalse(view.component.isVisible, "Unbinding must hide the previous session's popup despite an update failure")
                component.clearScreen()
            }
            dispatcher.scheduler.runCurrent()
            assertEquals(0, output.size(), "An unbound component must no longer send input to the previous session")
            edtCall {
                failUpdates = false
                component.bind(session)
                component.clearScreen()
            }
            dispatcher.scheduler.runCurrent()
            assertArrayEquals(byteArrayOf(0x0C), output.toByteArray(), "Rebinding must restore normal input")
        } finally {
            edtCall {
                failUpdates = false
                component.dispose()
            }
            session.close()
            dispatcher.scheduler.runCurrent()
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `disabling suggestions publishes ineligibility despite a custom view failure`(cancelled: Boolean) {
        val failure = if (cancelled) CancellationException("hide cancelled") else IllegalStateException("hide failed")
        var failUpdates = false
        var currentSettings = SwingSettings(smartSuggestionsEnabled = true, cursorBlinkMillis = 0, useSystemFallbackFonts = false)
        val visibleDuringCallback = ArrayList<Boolean>()
        val eligibilityChanges = ArrayList<Boolean>()
        val view =
            object : SwingShellSuggestionView {
                override val component = JPanel()

                override fun update(snapshot: SwingShellSuggestionViewSnapshot) {
                    if (failUpdates) throw failure
                }
            }
        val component =
            edtCall {
                SwingTerminal(
                    settingsProvider = { currentSettings },
                    hostServices = SwingHostServices(shellSuggestionViewFactory = { view }),
                )
            }
        try {
            edtCall {
                component.showShellSuggestions(
                    SwingShellSuggestionRequest.EMPTY,
                    listOf(SwingShellSuggestion("test", 0, 0, "test", "COMMAND")),
                )
                assertTrue(view.component.isVisible)
                component.addShellSuggestionEligibilityListener { eligible ->
                    assertEquals(eligible, component.isAutomaticShellSuggestionEligible())
                    eligibilityChanges += eligible
                    visibleDuringCallback += view.component.isVisible
                }
                currentSettings = currentSettings.copy(smartSuggestionsEnabled = false)
                failUpdates = true

                assertSame(failure, assertThrows(RuntimeException::class.java) { component.reloadSettings() })

                assertFalse(component.isAutomaticShellSuggestionEligible())
                assertFalse(view.component.isVisible)
                assertEquals(listOf(false), eligibilityChanges)
                assertEquals(listOf(false), visibleDuringCallback, "Physical hiding must precede eligibility notification")
                assertTrue(component.isCoroutineScopeActive)
                assertTrue(failure.suppressed.isEmpty())

                failUpdates = false
                currentSettings = currentSettings.copy(smartSuggestionsEnabled = true)
                component.reloadSettings()
                assertTrue(component.isAutomaticShellSuggestionEligible())
                assertEquals(listOf(false, true), eligibilityChanges)
                assertEquals(listOf(false, false), visibleDuringCallback)
            }
        } finally {
            edtCall {
                failUpdates = false
                component.dispose()
            }
        }
    }

    @Test
    fun `disabling suggestions hides the view before a reentrant viewport query from its empty update`() {
        var currentSettings = SwingSettings(smartSuggestionsEnabled = true, cursorBlinkMillis = 0, useSystemFallbackFonts = false)
        var observeEmptyUpdates = false
        var emptyUpdates = 0
        var nonEmptyUpdates = 0
        var viewportQueries = 0
        var createdViews = 0
        var closes = 0
        val visibleDuringUpdate = ArrayList<Boolean>()
        val eligibilityDuringUpdate = ArrayList<Boolean>()
        lateinit var terminalComponent: SwingTerminal
        val view =
            object : SwingShellSuggestionView {
                override val component = JPanel()

                override fun update(snapshot: SwingShellSuggestionViewSnapshot) {
                    if (snapshot.visibleSuggestions.isNotEmpty()) {
                        nonEmptyUpdates++
                    } else if (observeEmptyUpdates) {
                        emptyUpdates++
                        visibleDuringUpdate += component.isVisible
                        eligibilityDuringUpdate += terminalComponent.isAutomaticShellSuggestionEligible()
                        if (viewportQueries == 0) {
                            viewportQueries++
                            terminalComponent.viewportState()
                        }
                    }
                }

                override fun close() {
                    closes++
                }
            }
        terminalComponent =
            edtCall {
                SwingTerminal(
                    settingsProvider = { currentSettings },
                    hostServices =
                        SwingHostServices(
                            shellSuggestionViewFactory = {
                                createdViews++
                                view
                            },
                        ),
                )
            }
        try {
            edtCall {
                terminalComponent.showShellSuggestions(
                    SwingShellSuggestionRequest.EMPTY,
                    listOf(SwingShellSuggestion("test", 0, 0, "test", "COMMAND")),
                )
                terminalComponent.viewportState()
                assertTrue(view.component.isVisible)
                assertTrue(terminalComponent.isAutomaticShellSuggestionEligible())
                assertEquals(1, nonEmptyUpdates)
                observeEmptyUpdates = true
                currentSettings = currentSettings.copy(smartSuggestionsEnabled = false)

                terminalComponent.reloadSettings()

                assertAll(
                    { assertEquals(1, emptyUpdates, "The reentrant viewport query must not repeat the final empty update") },
                    { assertEquals(1, viewportQueries) },
                    { assertEquals(listOf(false), visibleDuringUpdate) },
                    { assertEquals(listOf(false), eligibilityDuringUpdate) },
                    { assertFalse(view.component.isVisible) },
                    { assertFalse(terminalComponent.isAutomaticShellSuggestionEligible()) },
                    { assertTrue(terminalComponent.isCoroutineScopeActive) },
                    { assertEquals(0, closes, "Disabling suggestions must retain the reusable host view") },
                )

                observeEmptyUpdates = false
                currentSettings = currentSettings.copy(smartSuggestionsEnabled = true)
                terminalComponent.reloadSettings()
                terminalComponent.showShellSuggestions(
                    SwingShellSuggestionRequest.EMPTY,
                    listOf(SwingShellSuggestion("again", 0, 0, "test", "COMMAND")),
                )
                assertTrue(view.component.isVisible)
                assertTrue(terminalComponent.currentShellSuggestionState().visible)
                assertEquals(2, nonEmptyUpdates)
                assertEquals(1, createdViews, "Re-enabling must reuse the same host view")
                assertEquals(0, closes)
                assertTrue(terminalComponent.isCoroutineScopeActive)
            }
        } finally {
            edtCall {
                observeEmptyUpdates = false
                terminalComponent.dispose()
            }
        }
    }

    @Test
    fun `routine frame consumption never reads the session on the EDT`() {
        val terminal = TerminalBuffers.create(width = 3, height = 1, maxHistory = 5)
        val checkingReader = EdtCheckingRenderReader(terminal as TerminalRenderFrameReader)
        val session =
            TerminalSession(
                terminal = terminal,
                renderPublisher = TerminalRenderPublisher(3, 1),
                renderReader = checkingReader,
                workerDispatcher = dispatcher,
                responseReader = terminal,
                connector = NoOpConnector,
                parser = NoOpParser,
                inputEncoderFactory = TerminalInputEncoderFactory { _, _, _ -> object : TerminalInputEncoder by NoOpInputEncoder {} },
            )
        val component = SwingTerminal()

        component.bind(session)
        awaitRenderAfter(session, -1L)
        drainEdt()

        assertFalse(checkingReader.readOnEdt.get())
        session.close()
    }

    @Test
    fun `publication from a previously bound session is ignored`() {
        val terminal = TerminalBuffers.create(width = 3, height = 1, maxHistory = 5)
        repeat(3) {
            terminal.writeCodepoint('x'.code)
            terminal.carriageReturn()
            terminal.newLine()
        }
        val first = testSession(terminal = terminal)
        val second = testSession()
        val component = SwingTerminal()
        edtCall {
            component.size = component.preferredGridSize(3, 1)
        }

        component.bind(first)
        awaitRenderAfter(first, -1L)
        awaitHistorySize(component) { it > 0 }

        component.bind(second)
        awaitRenderAfter(second, -1L)
        awaitHistorySize(component) { it == 0 }

        val previousFirstGeneration = first.renderGeneration.value
        first.requestRender(scrollbackOffset = 0)
        awaitRenderAfter(first, previousFirstGeneration)
        drainEdt()
        assertEquals(0, component.viewportState().historySize)

        first.close()
        second.close()
    }

    @Test
    fun `bind and unbind may be called from a background thread`() {
        val session = testSession()
        val component = SwingTerminal()

        runOffEdt {
            component.bind(session)
        }
        drainEdt()
        assertTrue(component.hasActiveRenderBinding)

        runOffEdt {
            component.unbind()
        }
        drainEdt()

        assertFalse(component.hasActiveRenderBinding)
        session.close()
    }

    @Test
    fun `off EDT lifecycle work uses injected UI dispatcher`() {
        val session = testSession()
        val dispatcher = RecordingDispatcher()
        val component =
            SwingTerminal(
                hostServices =
                    SwingHostServices(
                        uiDispatcher = dispatcher,
                    ),
            )
        withEdtBlocked {
            runOffEdt {
                component.bind(session)
                component.reloadSettings()
                component.unbind()
            }
            assertEquals(3, dispatcher.dispatchCount.get())
        }
        drainEdt()

        assertFalse(component.hasActiveRenderBinding)
        session.close()
    }

    @Test
    fun `bind called off EDT does not wait for EDT execution`() {
        val session = testSession()
        val component = SwingTerminal()
        try {
            withEdtBlocked {
                // Completion is observed while the EDT is still held, not inferred from elapsed time.
                runOffEdt { component.bind(session) }
                assertFalse(component.hasActiveRenderBinding)
            }
            drainEdt()
            assertTrue(component.hasActiveRenderBinding)
        } finally {
            edtCall { component.dispose() }
            session.close()
        }
    }

    @Test
    fun `reloadSettings called off EDT rebuilds component state on EDT`() {
        val reloadCalledOnEdt = AtomicBoolean(false)
        val calls = AtomicInteger()
        val component =
            SwingTerminal(settingsProvider = {
                if (calls.incrementAndGet() > 1) {
                    reloadCalledOnEdt.set(SwingUtilities.isEventDispatchThread())
                }
                SwingSettings(
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
        val component = SwingTerminal()
        val expected =
            edtCall {
                component.size = Dimension(160, 80)
                component.visibleGridSize()
            }
        val visibleFromBackground = AtomicReference<Dimension>()
        try {
            withEdtBlocked {
                runOffEdt { visibleFromBackground.set(component.visibleGridSize()) }
                assertEquals(expected, visibleFromBackground.get())
            }
        } finally {
            edtCall { component.dispose() }
        }
    }

    @Test
    fun `bind resizes session to current visible grid when component has bounds`() {
        val connector = RecordingConnector()
        val session = testSession(connector)
        val component = SwingTerminal()

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
            SwingTerminal(settingsProvider = {
                SwingSettings(treatAmbiguousAsWide = true)
            })

        component.bind(session)
        drainEdt()

        assertTrue(session.modeSnapshot.treatAmbiguousAsWide)
        session.close()
    }

    @Test
    fun `reloadSettings updates ambiguous width setting on bound session`() {
        val session = testSession()
        var ambiguousAsWide = false
        val component =
            SwingTerminal(settingsProvider = {
                SwingSettings(treatAmbiguousAsWide = ambiguousAsWide)
            })

        component.bind(session)
        drainEdt()
        assertFalse(session.modeSnapshot.treatAmbiguousAsWide)

        ambiguousAsWide = true
        component.reloadSettings()
        drainEdt()

        assertTrue(session.modeSnapshot.treatAmbiguousAsWide)
        session.close()
    }

    @Test
    fun `component resize updates session only when visible grid changes`() {
        val connector = RecordingConnector()
        val session = testSession(connector)
        val component = SwingTerminal()

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

    @Test
    fun `unrelated and unchanged settings preserve application palette and cursor`() {
        val session =
            TerminalSession.create(
                terminal = TerminalBuffers.create(width = 3, height = 1, maxHistory = 5),
                connector = NoOpConnector,
            )
        var settings = SwingSettings()
        val component = SwingTerminal(settingsProvider = { settings })
        try {
            edtCall {
                component.bind(session)
                // Apply application overrides through the same synchronization boundary as live output.
                val output = "\u001B[6 q\u001B]4;1;rgb:12/34/56\u0007".toByteArray(Charsets.US_ASCII)
                session.onBytes(output, 0, output.size)

                component.reloadSettings()
                settings = settings.copy(visualBellEnabled = !settings.visualBellEnabled)
                component.reloadSettings()

                session.readRenderFrame { frame ->
                    assertEquals(TerminalRenderCursorShape.BAR, frame.cursor.shape)
                    assertEquals(0xFF123456.toInt(), frame.palette.indexedColor(1))
                }
            }
        } finally {
            edtCall { component.dispose() }
            session.close()
        }
    }

    @Test
    fun `changed theme and cursor preferences replace application overrides`() {
        val session =
            TerminalSession.create(
                terminal = TerminalBuffers.create(width = 3, height = 1, maxHistory = 5),
                connector = NoOpConnector,
            )
        var settings = SwingSettings()
        val component = SwingTerminal(settingsProvider = { settings })
        try {
            edtCall {
                component.bind(session)
                val output = "\u001B[6 q\u001B]4;1;rgb:12/34/56\u0007".toByteArray(Charsets.US_ASCII)
                session.onBytes(output, 0, output.size)
                session.readRenderFrame { frame ->
                    assertEquals(TerminalRenderCursorShape.BAR, frame.cursor.shape)
                    assertEquals(0xFF123456.toInt(), frame.palette.indexedColor(1))
                }
                settings = settings.copy(palette = TerminalTheme.NORD.createPalette(), cursorShape = TerminalRenderCursorShape.UNDERLINE)

                component.reloadSettings()

                session.readRenderFrame { frame ->
                    assertEquals(settings.cursorShape, frame.cursor.shape)
                    assertEquals(settings.palette, frame.palette)
                }
            }
        } finally {
            edtCall { component.dispose() }
            session.close()
        }
    }

    @Test
    fun `font size changes resize the grid but paint preferences do not`() {
        val connector = RecordingConnector()
        val session = testSession(connector)
        var settings = SwingSettings(font = Font(Font.MONOSPACED, Font.PLAIN, 14))
        val component = SwingTerminal(settingsProvider = { settings })
        try {
            edtCall {
                component.size = Dimension(320, 160)
                component.bind(session)
            }
            drainEdt()
            connector.reset()
            edtCall {
                val originalGrid = component.visibleGridSize()
                settings = settings.copy(selectionBackground = 0xFF123456.toInt())
                component.reloadSettings()
                assertEquals(originalGrid, component.visibleGridSize())
                assertEquals(0, connector.resizeCount.get())

                settings = settings.copy(font = settings.font.deriveFont(28f))
                component.reloadSettings()
                assertTrue(component.visibleGridSize().width < originalGrid.width)
                assertTrue(component.visibleGridSize().height < originalGrid.height)
                assertEquals(1, connector.resizeCount.get())
            }
        } finally {
            edtCall { component.dispose() }
            session.close()
        }
    }

    private fun focusSession(output: ByteArrayOutputStream): TerminalSession =
        TerminalSession
            .create(
                terminal = TerminalBuffers.create(width = 3, height = 1),
                connector =
                    object : TerminalConnector by NoOpConnector {
                        override fun write(
                            bytes: ByteArray,
                            offset: Int,
                            length: Int,
                        ) {
                            output.write(bytes, offset, length)
                        }
                    },
                workerDispatcher = dispatcher,
                ioDispatcher = dispatcher,
            ).also { it.start(columns = 3, rows = 1) }

    private fun dispatchFocusCycle(component: SwingTerminal) {
        check(SwingUtilities.isEventDispatchThread())
        val gained = FocusEvent(component, FocusEvent.FOCUS_GAINED)
        val lost = FocusEvent(component, FocusEvent.FOCUS_LOST)
        component.focusListeners.forEach { it.focusGained(gained) }
        component.focusListeners.forEach { it.focusLost(lost) }
    }

    private fun testSession(
        connector: TerminalConnector = NoOpConnector,
        terminal: TerminalBuffer = TerminalBuffers.create(width = 3, height = 1, maxHistory = 5),
    ): TerminalSession =
        TerminalSession(
            terminal = terminal,
            renderPublisher = TerminalRenderPublisher(3, 1),
            renderReader = terminal as TerminalRenderFrameReader,
            responseReader = terminal,
            connector = connector,
            parser = NoOpParser,
            inputEncoderFactory = { _, _, _ -> object : TerminalInputEncoder by NoOpInputEncoder {} },
            workerDispatcher = dispatcher,
        )

    private fun runOffEdt(action: () -> Unit) {
        assertFalse(SwingUtilities.isEventDispatchThread())
        val task = FutureTask(action)
        val worker = thread(isDaemon = true, block = task::run)
        try {
            task.get(10, TimeUnit.SECONDS)
        } finally {
            worker.interrupt()
        }
    }

    private fun withEdtBlocked(action: () -> Unit) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocker =
            FutureTask {
                entered.countDown()
                release.await()
            }
        SwingUtilities.invokeLater(blocker)
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS), "EDT blocker did not start")
            action()
        } finally {
            release.countDown()
            blocker.get(10, TimeUnit.SECONDS)
        }
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

    private fun awaitRenderAfter(
        session: TerminalSession,
        generation: Long,
    ) {
        drainEdt()
        dispatcher.scheduler.runCurrent()
        drainEdt()
        assertTrue(session.renderGeneration.value > generation, "render was not published")
    }

    private fun awaitHistorySize(
        component: SwingTerminal,
        predicate: (Int) -> Boolean,
    ) {
        drainEdt()
        val historySize = component.viewportState().historySize
        assertTrue(predicate(historySize), "unexpected Swing history size: $historySize")
    }

    private class EdtCheckingRenderReader(
        private val delegate: TerminalRenderFrameReader,
    ) : TerminalRenderFrameReader {
        val readOnEdt = AtomicBoolean(false)

        override fun readRenderFrame(consumer: TerminalRenderFrameConsumer) {
            recordThread()
            delegate.readRenderFrame(consumer)
        }

        override fun readRenderFrame(
            scrollbackOffset: Int,
            consumer: TerminalRenderFrameConsumer,
        ) {
            recordThread()
            delegate.readRenderFrame(scrollbackOffset, consumer)
        }

        override fun readRenderFrame(
            scrollbackOffset: Int,
            viewportRows: Int,
            consumer: TerminalRenderFrameConsumer,
        ) {
            recordThread()
            delegate.readRenderFrame(scrollbackOffset, viewportRows, consumer)
        }

        private fun recordThread() {
            if (SwingUtilities.isEventDispatchThread()) readOnEdt.set(true)
        }
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
        override fun setInputPolicy(policy: TerminalInputPolicy) = Unit

        override fun encodeKey(event: TerminalKeyEvent) = Unit

        override fun encodePaste(event: TerminalPasteEvent) = Unit

        override fun encodeFocus(event: TerminalFocusEvent) = Unit

        override fun encodeMouse(event: TerminalMouseEvent) = Unit
    }
}
