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
import io.github.ketraterm.session.TerminalInputAdmission.*
import io.github.ketraterm.testkit.MockConnector
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalCommandEditTest {
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

    @ParameterizedTest
    @ValueSource(strings = ["text", "cursor", "anchor", "unavailable", "equal", "aba", "raw", "key", "paste", "output", "resize"])
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
                    "output" -> connector.feedFromHost("\u001b[?2004h".toByteArray())
                    "resize" -> session.resizeViewport(11, 3)
                }
                // A stale check must precede work/budget validation too.
                assertEquals(STALE_CONTEXT, session.submitInput(expected, List(257) { TerminalKeyEvent.text("never") }))
                assertFalse(session.isClosed)
                runCurrent()
                assertEquals(allowedBytes, connector.writtenBytes.toString(Charsets.UTF_8))
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
    ): TerminalSession {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return TerminalSession.create(
            TerminalBuffers.create(10, 3),
            connector,
            shellIntegration = TerminalShellIntegrationFactory.host(TerminalShellIntegrationState(), model),
            workerDispatcher = dispatcher,
            ioDispatcher = dispatcher,
        )
    }

    private fun snapshot(): TerminalShellCommandLineSnapshot = TerminalShellCommandLineSnapshot("git s", 5, 5, 0)
}
