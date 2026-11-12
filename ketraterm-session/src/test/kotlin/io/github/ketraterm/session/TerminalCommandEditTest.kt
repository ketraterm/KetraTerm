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
package io.github.ketraterm.session

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.input.event.*
import io.github.ketraterm.protocol.keyboard.KittyKeyboardProgressiveFlag
import io.github.ketraterm.session.TerminalInputAdmission.*
import io.github.ketraterm.testkit.MockConnector
import io.github.ketraterm.transport.TerminalConnector
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalCommandEditTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `matching echo during a blocked write waits for retirement or rejects intervening input`(interveningInput: Boolean) =
        runTest {
            val entered = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            val writes = MockConnector()
            val connector =
                object : TerminalConnector by writes {
                    override fun write(
                        bytes: ByteArray,
                        offset: Int,
                        length: Int,
                    ) {
                        entered.complete(Unit)
                        release.await()
                        writes.write(bytes, offset, length)
                    }
                }
            val model = TerminalShellCommandLineState(snapshot())
            val dispatcher = StandardTestDispatcher(testScheduler)
            Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { io ->
                TerminalSession
                    .create(
                        TerminalBuffers.create(20, 3),
                        connector,
                        shellIntegration = TerminalShellIntegrationFactory.host(TerminalShellIntegrationState(), model),
                        workerDispatcher = dispatcher,
                        ioDispatcher = io,
                        cleanupDispatcher = dispatcher,
                    ).use { session ->
                        try {
                            session.start(20, 3)
                            val original = checkNotNull(session.captureCommandEdit())
                            assertEquals(ACCEPTED, session.submitInput(original, listOf(TerminalTextReplacementEvent(0, 0, "tatus"))))
                            entered.await()
                            model.value = snapshot().copy(commandText = "git status", cursorOffset = 10, cursorColumn = 10)
                            val next = async { session.awaitCommandEditAfterInput(original, "git status") }
                            runCurrent()

                            assertFalse(next.isCompleted, "An exact matching echo cannot bypass an active writer")
                            assertNull(session.captureCommandEdit())
                            if (interveningInput) {
                                assertEquals(ACCEPTED, session.submitBytes("x".toByteArray()))
                                runCurrent()
                                assertTrue(next.isCompleted, "Admission must invalidate the receipt before the blocked writer finishes")
                                assertNull(next.await())
                            } else {
                                release.countDown()
                                val captured = checkNotNull(next.await())
                                assertEquals("git status", captured.commandLine.commandText)
                                assertTrue(session.isCommandEditCurrent(captured))
                                assertEquals("tatus", writes.writtenBytes.toString(Charsets.UTF_8))
                                captured.cancel()
                            }
                        } finally {
                            release.countDown()
                        }
                    }
            }
        }

    @Test
    fun `unchanged preadmission text is not an acknowledgement`() =
        runTest {
            val connector = MockConnector()
            val model = TerminalShellCommandLineState(snapshot())
            session(connector, model).use { session ->
                session.start(10, 3)
                val original = checkNotNull(session.captureCommandEdit())
                assertEquals(ACCEPTED, session.submitInput(original, listOf(TerminalKeyEvent.key(TerminalKey.END))))
                val next = async { session.awaitCommandEditAfterInput(original, "git s") }
                runCurrent()
                assertFalse(next.isCompleted)
                model.value = snapshot()
                runCurrent()
                assertNotNull(next.await())
            }
        }

    @Test
    fun `follow up waits for exact producer echo and returns fresh editing authority`() =
        runTest {
            val connector = MockConnector()
            val model = TerminalShellCommandLineState(snapshot())
            session(connector, model).use { session ->
                session.start(10, 3)
                val original = checkNotNull(session.captureCommandEdit())
                assertEquals(ACCEPTED, session.submitInput(original, listOf(TerminalTextReplacementEvent(0, 0, "tatus"))))
                original.cancel()
                val next = async { session.awaitCommandEditAfterInput(original, "git status") }
                runCurrent()
                assertFalse(next.isCompleted, "Writer idleness is not shell acknowledgement")
                model.value = snapshot().copy(commandText = "git stat", cursorOffset = 8)
                runCurrent()
                assertFalse(next.isCompleted)
                model.value = snapshot().copy(commandText = "git status", cursorOffset = 9)
                runCurrent()
                assertFalse(next.isCompleted, "The cursor must also match")
                model.value = snapshot().copy(commandText = "git status", cursorOffset = 10)
                runCurrent()
                val captured = checkNotNull(next.await())
                assertTrue(session.isCommandEditCurrent(captured))
                assertEquals(ACCEPTED, session.submitInput(captured, listOf(TerminalTextReplacementEvent(0, 0, " --short"))))
                runCurrent()
                assertEquals("tatus --short", connector.writtenBytes.toString(Charsets.UTF_8))
            }
        }

    @ParameterizedTest
    @ValueSource(strings = ["bytes", "input", "resize", "prompt", "close"])
    fun `follow up cannot survive intervening input geometry metadata or shutdown`(change: String) =
        runTest {
            val connector = MockConnector()
            val model = TerminalShellCommandLineState(snapshot())
            val metadata = TerminalShellIntegrationState()
            session(connector, model, TerminalShellIntegrationFactory.host(metadata, model)).use { session ->
                session.start(10, 3)
                val original = checkNotNull(session.captureCommandEdit())
                assertEquals(ACCEPTED, session.submitInput(original, listOf(TerminalTextReplacementEvent(0, 0, "tatus"))))
                val next = async { session.awaitCommandEditAfterInput(original, "git status") }
                runCurrent()
                assertFalse(next.isCompleted)
                when (change) {
                    "bytes" -> session.submitBytes("x".toByteArray())
                    "input" -> session.submitInput(TerminalKeyEvent.text("x"))
                    "resize" -> session.resize(11, 3)
                    "prompt" -> metadata.recordPromptStart(1)
                    "close" -> session.close()
                }
                // Deliberately conflate departure and matching text: the receipt must still reject it.
                model.value = snapshot().copy(commandText = "git status", cursorOffset = 10)
                runCurrent()
                assertNull(next.await())
            }
        }

    @Test
    fun `follow up requires a nonempty successful conditional admission`() =
        runTest {
            val connector = MockConnector()
            val model = TerminalShellCommandLineState(snapshot())
            session(connector, model).use { session ->
                session.start(10, 3)
                val original = checkNotNull(session.captureCommandEdit())
                assertNull(session.awaitCommandEditAfterInput(original, "git s"))
                assertEquals(ACCEPTED, session.submitInput(original, emptyList()))
                assertNull(session.awaitCommandEditAfterInput(original, "git s"))
            }
        }

    @Test
    fun `provisional command text cannot admit an edit without native confirmation`() =
        runTest {
            val connector = MockConnector()
            val model = TerminalShellCommandLineState(snapshot().copy(requiresShellConfirmation = true))
            session(connector, model).use { session ->
                session.start(10, 3)
                val expected = checkNotNull(session.captureCommandEdit())

                assertNull(session.requestShellCompletions("git s", 5))
                assertEquals(STALE_CONTEXT, session.submitInput(expected, listOf(TerminalKeyEvent.text("never"))))
                runCurrent()
                assertArrayEquals(byteArrayOf(), connector.writtenBytes)
            }
        }

    @ParameterizedTest(name = "{0}")
    @MethodSource("synchronousInputCases")
    fun `synchronous input invalidates a fresh edit only when sent to the shell`(
        scenario: String,
        modes: String,
        event: TerminalInputEvent,
        emitted: String,
    ) = runTest {
        val connector = MockConnector()
        val model = TerminalShellCommandLineState(snapshot())
        session(connector, model).use { session ->
            session.start(10, 3)
            connector.feedFromHost(modes.toByteArray())
            val expected = checkNotNull(session.captureCommandEdit())
            assertEquals(ACCEPTED, session.submitInput(event))
            runCurrent()
            assertEquals(emitted, connector.writtenBytes.toString(Charsets.UTF_8), scenario)
            assertEquals(emitted.isEmpty(), session.isCommandEditCurrent(expected), scenario)
            assertEquals(
                if (emitted.isEmpty()) ACCEPTED else STALE_CONTEXT,
                session.submitInput(expected, listOf(TerminalTextReplacementEvent(0, 1, "status"))),
                scenario,
            )
            runCurrent()
            assertEquals(
                if (emitted.isEmpty()) "\u007fstatus" else emitted,
                connector.writtenBytes.toString(Charsets.UTF_8),
                scenario,
            )
        }
    }

    @Test
    fun `fresh context admits one compound edit and cannot admit another edit`() =
        runTest {
            val connector = MockConnector()
            val model = TerminalShellCommandLineState(snapshot())
            session(connector, model).use { session ->
                assertNull(session.captureCommandEdit())
                session.start(10, 3)
                val expected = checkNotNull(session.captureCommandEdit())
                assertEquals(snapshot(), expected.commandLine)
                assertTrue(expected.prefixResetKeys.isEmpty())
                val events =
                    listOf(
                        TerminalKeyEvent.key(TerminalKey.END),
                        TerminalTextReplacementEvent(0, 1, "status"),
                        TerminalKeyEvent.key(TerminalKey.ENTER),
                    )
                assertEquals(ACCEPTED, session.submitInput(expected, events))
                assertEquals(STALE_CONTEXT, session.submitInput(expected, events))
                assertNull(session.captureCommandEdit(), "Pending input is not a fresh shell editing snapshot")
                runCurrent()
                assertEquals("\u001b[F\u007fstatus\r", connector.writtenBytes.toString(Charsets.UTF_8))
            }
        }

    @Test
    fun `versioned host detaches its prefix reset keys before returning the factory`() =
        runTest {
            val connector = MockConnector()
            val model = TerminalShellCommandLineState(snapshot())
            val keys = mutableListOf(TerminalKeyEvent.key(TerminalKey.F12), TerminalKeyEvent.text("r"))
            val originalKeys = keys.toList()
            val factory =
                TerminalShellIntegrationFactory.host(
                    TerminalShellIntegrationState(),
                    model,
                    keys,
                )
            keys.clear()
            session(connector, model, factory).use { session ->
                session.start(10, 3)
                val expected = checkNotNull(session.captureCommandEdit())

                assertEquals(snapshot(), expected.commandLine)
                assertEquals(originalKeys, expected.prefixResetKeys)
                assertEquals(ACCEPTED, session.submitInput(expected, listOf(TerminalTextReplacementEvent(0, 1, "status"))))
                runCurrent()
                assertEquals("\u007fstatus", connector.writtenBytes.toString(Charsets.UTF_8))
            }
        }

    @Test
    fun `captured reset keys are detached and a changed sequence rejects the edit before any prefix`() =
        runTest {
            val connector = MockConnector()
            val model = TerminalShellCommandLineState(snapshot())
            val keys = mutableListOf(TerminalKeyEvent.key(TerminalKey.F12), TerminalKeyEvent.text("r"))
            val originalKeys = keys.toList()
            val host = TerminalShellIntegrationFactory.host(TerminalShellIntegrationState(), model)
            val factory =
                TerminalShellIntegrationFactory { context ->
                    object : TerminalShellIntegration by host.create(context) {
                        override val commandLinePrefixResetKeys: List<TerminalKeyEvent> get() = keys
                    }
                }
            session(connector, model, factory).use { session ->
                session.start(10, 3)
                val expected = checkNotNull(session.captureCommandEdit())
                model.withCommandLine { _, _ -> keys[1] = TerminalKeyEvent.text("x") }

                assertEquals(originalKeys, expected.prefixResetKeys)
                assertEquals(STALE_CONTEXT, session.submitInput(expected, listOf(TerminalTextReplacementEvent(0, 1, "never"))))
                runCurrent()
                assertArrayEquals(byteArrayOf(), connector.writtenBytes)

                val fresh = checkNotNull(session.captureCommandEdit())
                assertEquals(expected.commandLine, fresh.commandLine)
                assertEquals(keys, fresh.prefixResetKeys)
                assertEquals(ACCEPTED, session.submitInput(fresh, listOf(TerminalTextReplacementEvent(0, 1, "status"))))
                runCurrent()
                assertEquals("\u007fstatus", connector.writtenBytes.toString(Charsets.UTF_8))
            }
        }

    @Test
    fun `prefix reset key sequences are bounded at factory construction and capture`() =
        runTest {
            val model = TerminalShellCommandLineState(snapshot())
            val excessiveKeys = List(5) { TerminalKeyEvent.key(TerminalKey.F12) }
            assertThrows(IllegalArgumentException::class.java) {
                TerminalShellIntegrationFactory.host(TerminalShellIntegrationState(), model, excessiveKeys)
            }
            val connector = MockConnector()
            val host = TerminalShellIntegrationFactory.host(TerminalShellIntegrationState(), model)
            val factory =
                TerminalShellIntegrationFactory { context ->
                    object : TerminalShellIntegration by host.create(context) {
                        override val commandLinePrefixResetKeys: List<TerminalKeyEvent> get() = excessiveKeys
                    }
                }
            session(connector, model, factory).use { session ->
                session.start(10, 3)
                assertThrows(IllegalArgumentException::class.java) { session.captureCommandEdit() }
                assertFalse(session.isClosed)
                runCurrent()
                assertArrayEquals(byteArrayOf(), connector.writtenBytes)
            }
        }

    @ParameterizedTest
    @ValueSource(strings = ["text", "cursor", "anchor", "unavailable", "equal", "aba", "raw", "key", "paste", "resize"])
    fun `intervening state rejects an entire edit without publishing its prefix`(change: String) =
        runTest {
            val connector = MockConnector()
            val model = TerminalShellCommandLineState(snapshot())
            session(connector, model).use { session ->
                session.start(10, 3)
                val expected = checkNotNull(session.captureCommandEdit())
                var allowedBytes = ""
                when (change) {
                    "text" -> model.value = snapshot().copy(commandText = "git st")
                    "cursor" -> model.value = snapshot().copy(cursorOffset = 3)
                    "anchor" -> model.value = snapshot().copy(cursorRow = 1)
                    "unavailable" -> model.value = null
                    "equal" -> model.value = snapshot()
                    "aba" -> {
                        model.value = null
                        model.value = snapshot()
                    }
                    "raw" -> {
                        session.submitBytes("x".toByteArray())
                        allowedBytes = "x"
                    }
                    "key" -> {
                        session.encodeKey(TerminalKeyEvent.text("x"))
                        allowedBytes = "x"
                    }
                    "paste" -> {
                        session.encodePaste(TerminalPasteEvent("x"))
                        allowedBytes = "x"
                    }
                    "resize" -> session.resizeViewport(11, 3)
                }
                // A stale check must precede work/budget validation too.
                assertEquals(STALE_CONTEXT, session.submitInput(expected, List(257) { TerminalKeyEvent.text("never") }))
                assertFalse(session.isClosed)
                runCurrent()
                assertEquals(allowedBytes, connector.writtenBytes.toString(Charsets.UTF_8))
            }
        }

    @ParameterizedTest
    @ValueSource(strings = ["\u001b[?2004h", "\u001b]0;changed title\u0007", "\u001b]633;ignored\u0007", "paint"])
    fun `terminal output does not revoke authoritative host editing state`(output: String) =
        runTest {
            val connector = MockConnector()
            session(connector, TerminalShellCommandLineState(snapshot())).use { session ->
                session.start(10, 3)
                val expected = checkNotNull(session.captureCommandEdit())
                connector.feedFromHost(output.toByteArray())
                assertTrue(session.isCommandEditCurrent(expected))
                assertEquals(ACCEPTED, session.submitInput(expected, listOf(TerminalKeyEvent.text("x"))))
                runCurrent()
                assertEquals("x", connector.writtenBytes.toString(Charsets.UTF_8))
            }
        }

    @Test
    fun `cancellation and closure reject before any edit prefix`() =
        runTest {
            val connector = MockConnector()
            session(connector, TerminalShellCommandLineState(snapshot())).use { session ->
                session.start(10, 3)
                val expected = checkNotNull(session.captureCommandEdit())
                expected.cancel()
                assertTrue(expected.isCancelled)
                assertEquals(CANCELLED, session.submitInput(expected, listOf(TerminalKeyEvent.text("x"))))
                session.close()
                assertEquals(CLOSED, session.submitInput(expected, listOf(TerminalKeyEvent.text("x"))))
                assertNull(session.captureCommandEdit())
                runCurrent()
                assertArrayEquals(byteArrayOf(), connector.writtenBytes)
            }
        }

    @Test
    fun `contexts are session specific and legacy host projections cannot admit edits`() =
        runTest {
            val connector = MockConnector()
            val model = TerminalShellCommandLineState(snapshot())
            session(connector, model).use { first ->
                first.start(10, 3)
                val context = checkNotNull(first.captureCommandEdit())
                session(MockConnector(), model).use { second ->
                    second.start(10, 3)
                    assertEquals(STALE_CONTEXT, second.submitInput(context, listOf(TerminalKeyEvent.text("x"))))
                }
                val dispatcher = StandardTestDispatcher(testScheduler)
                TerminalSession
                    .create(
                        TerminalBuffers.create(10, 3),
                        MockConnector(),
                        shellIntegration =
                            TerminalShellIntegrationFactory.host(
                                TerminalShellIntegrationState(),
                                MutableStateFlow(snapshot()),
                            ),
                        workerDispatcher = dispatcher,
                        ioDispatcher = dispatcher,
                        cleanupDispatcher = dispatcher,
                    ).use { legacy ->
                        legacy.start(10, 3)
                        assertEquals(snapshot(), legacy.activeShellCommandLine())
                        assertNull(legacy.captureCommandEdit())
                        assertEquals(UNSUPPORTED_CONTEXT, legacy.submitInput(context, listOf(TerminalKeyEvent.text("x"))))
                    }
                runCurrent()
                assertArrayEquals(byteArrayOf(), connector.writtenBytes)
            }
        }

    @Test
    fun `unavailable and pending writer state cannot be captured`() =
        runTest {
            val connector = MockConnector()
            val model = TerminalShellCommandLineState()
            session(connector, model).use { session ->
                session.start(10, 3)
                assertNull(session.captureCommandEdit())
                model.value = snapshot()
                assertNotNull(session.captureCommandEdit())
                session.submitBytes("x".toByteArray())
                assertNull(session.captureCommandEdit())
                runCurrent()
                model.value = snapshot().copy(commandText = "git sx", cursorOffset = 6)
                assertEquals(model.value, session.captureCommandEdit()?.commandLine)
            }
        }

    @Test
    fun `producer updates input and output serialize with the final edit reservation`() =
        runTest {
            val connector = MockConnector()
            val model = TerminalShellCommandLineState(snapshot())
            session(connector, model).use { session ->
                session.start(10, 3)
                val expected = checkNotNull(session.captureCommandEdit())
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                val events =
                    object : AbstractList<TerminalInputEvent>() {
                        override val size = 1

                        override fun get(index: Int): TerminalInputEvent {
                            entered.countDown()
                            release.await()
                            return TerminalTextReplacementEvent(0, 1, "status")
                        }
                    }
                val admission = SessionTestThread("conditional-edit") { assertEquals(ACCEPTED, session.submitInput(expected, events)) }
                val contenders = ArrayList<SessionTestThread>()
                try {
                    assertTrue(entered.await(10, TimeUnit.SECONDS))
                    contenders += SessionTestThread("metadata-update") { model.value = snapshot().copy(commandText = "changed") }
                    contenders += SessionTestThread("other-input") { assertEquals(ACCEPTED, session.submitBytes("x".toByteArray())) }
                    contenders += SessionTestThread("other-output") { connector.feedFromHost("paint".toByteArray()) }
                    contenders.forEach { it.awaitBlockedBy(admission) }
                    release.countDown()
                    admission.awaitCompletion()
                    contenders.forEach { it.awaitCompletion() }
                    runCurrent()
                    assertEquals("\u007fstatusx", connector.writtenBytes.toString(Charsets.UTF_8))
                    assertEquals(STALE_CONTEXT, session.submitInput(expected, listOf(TerminalKeyEvent.text("never"))))
                } finally {
                    release.countDown()
                    admission.close()
                    contenders.forEach { it.close() }
                }
            }
        }

    private fun TestScope.session(
        connector: MockConnector,
        model: TerminalShellCommandLineState,
        shellIntegration: TerminalShellIntegrationFactory = TerminalShellIntegrationFactory.host(TerminalShellIntegrationState(), model),
    ): TerminalSession {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return TerminalSession.create(
            TerminalBuffers.create(10, 3),
            connector,
            shellIntegration = shellIntegration,
            kittyKeyboardSupportedFlags = KittyKeyboardProgressiveFlag.ENCODER_SUPPORTED_MASK,
            workerDispatcher = dispatcher,
            ioDispatcher = dispatcher,
            cleanupDispatcher = dispatcher,
        )
    }

    private fun snapshot(): TerminalShellCommandLineSnapshot = TerminalShellCommandLineSnapshot("git s", 5, 5, 0)

    companion object {
        @JvmStatic
        fun synchronousInputCases(): List<Arguments> {
            val release = TerminalKeyEvent.key(TerminalKey.BACKSPACE, type = TerminalKeyEventType.RELEASE)
            val shift = TerminalKeyEvent.key(TerminalKey.LEFT_SHIFT, TerminalModifiers.SHIFT)
            val control = TerminalKeyEvent.key(TerminalKey.LEFT_CONTROL, TerminalModifiers.CTRL)
            val alt = TerminalKeyEvent.key(TerminalKey.LEFT_ALT, TerminalModifiers.ALT)
            val focusIn = TerminalFocusEvent(focused = true)
            val focusOut = TerminalFocusEvent(focused = false)
            val motion = TerminalMouseEvent(1, 1, TerminalMouseButton.NONE, TerminalMouseEventType.MOTION)
            val drag = motion.copy(button = TerminalMouseButton.LEFT)
            return listOf(
                Arguments.of("legacy key release", "", release, ""),
                Arguments.of("reported key release", "\u001b[=11u", release, "\u001b[127;1:3u"),
                Arguments.of("legacy shift press", "", shift, ""),
                Arguments.of("reported shift press", "\u001b[=9u", shift, "\u001b[57441;2u"),
                Arguments.of("legacy control press", "", control, ""),
                Arguments.of("reported control press", "\u001b[=9u", control, "\u001b[57442;5u"),
                Arguments.of("legacy alt press", "", alt, ""),
                Arguments.of("reported alt press", "\u001b[=9u", alt, "\u001b[57443;3u"),
                Arguments.of("unreported focus gain", "", focusIn, ""),
                Arguments.of("reported focus gain", "\u001b[?1004h", focusIn, "\u001b[I"),
                Arguments.of("unreported focus loss", "", focusOut, ""),
                Arguments.of("reported focus loss", "\u001b[?1004h", focusOut, "\u001b[O"),
                Arguments.of("disabled mouse tracking", "", motion, ""),
                Arguments.of("normal mouse motion", "\u001b[?1000;1006h", motion, ""),
                Arguments.of("button mouse motion without a button", "\u001b[?1002;1006h", motion, ""),
                Arguments.of("reported mouse motion", "\u001b[?1003;1006h", motion, "\u001b[<35;2;2M"),
                Arguments.of("reported mouse drag", "\u001b[?1002;1006h", drag, "\u001b[<32;2;2M"),
            )
        }
    }
}
