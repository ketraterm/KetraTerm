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
import io.github.ketraterm.core.api.TerminalBuffer
import io.github.ketraterm.host.HostCommandAdapter
import io.github.ketraterm.parser.api.TerminalParsers
import io.github.ketraterm.render.api.TerminalRenderFrameReader
import io.github.ketraterm.render.cache.TerminalRenderPublisher
import io.github.ketraterm.testkit.MockConnector
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalShellCommandLineSourceTest {
    @Test
    fun `host snapshot reads do not wait for parser and grid mutation serialization`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
                val expected = snapshot("host context")
                fixture.source.value = expected
                val frameEntered = CountDownLatch(1)
                val releaseFrame = CountDownLatch(1)
                SessionTestThread("terminal-source-held-frame") {
                    fixture.session.readRenderFrame {
                        frameEntered.countDown()
                        check(releaseFrame.await(SESSION_THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                            "Frame release was not signalled"
                        }
                    }
                }.use { frameThread ->
                    try {
                        assertTrue(frameEntered.await(SESSION_THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS), "Frame callback did not start")
                        SessionTestThread("terminal-source-read-during-frame") {
                            assertEquals(expected, fixture.session.activeShellCommandLine())
                        }.use { readThread ->
                            try {
                                readThread.awaitCompletion()
                            } finally {
                                releaseFrame.countDown()
                            }
                        }
                        frameThread.awaitCompletion()
                    } finally {
                        releaseFrame.countDown()
                    }
                }
            }
        }

    @Test
    fun `source snapshots are available immediately without starting observation or transport`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler), start = false).use { fixture ->
                val snapshot = snapshot("git \uD83D\uDE00 status", cursorOffset = 6)
                fixture.source.value = snapshot

                assertEquals(snapshot, fixture.session.activeShellCommandLine())
                assertEquals(0, fixture.connector.startCount)
                assertEquals(-1L, fixture.session.renderGeneration.value)
                assertEquals(-1L, fixture.session.activeShellCommandLineRevision.value)
                runCurrent()
                assertEquals(0, fixture.source.subscriptionCount.value)
                assertEquals(-1L, fixture.session.activeShellCommandLineRevision.value)
            }
        }

    @Test
    fun `first subscriber samples an existing source without terminal output or a rendered frame`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
                fixture.source.value = snapshot("git status")
                backgroundScope.launch { fixture.session.activeShellCommandLineRevision.collect {} }
                runCurrent()

                assertTrue(fixture.session.activeShellCommandLineRevision.value >= 0L)
                assertEquals(-1L, fixture.session.renderGeneration.value)
                assertEquals(1, fixture.source.subscriptionCount.value)
                assertEquals("", fixture.connector.writtenBytes.decodeToString())
                assertNull(fixture.session.startupCommandStatus)
            }
        }

    @Test
    fun `text cursor offset anchor and availability changes produce revisions without rendering`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
                backgroundScope.launch { fixture.session.activeShellCommandLineRevision.collect {} }
                runCurrent()
                assertEquals(0L, fixture.session.activeShellCommandLineRevision.value)
                val initial = snapshot("git \uD83D\uDE00 status", cursorOffset = 6)
                val states =
                    listOf(
                        initial,
                        initial.copy(commandText = "git \uD83D\uDE00 log"),
                        initial.copy(cursorOffset = 0),
                        initial.copy(cursorColumn = 0),
                        initial.copy(cursorRow = 2),
                        null,
                        initial,
                    )
                var previousRevision = fixture.session.activeShellCommandLineRevision.value
                for (state in states) {
                    fixture.source.value = state
                    runCurrent()
                    assertEquals(state, fixture.session.activeShellCommandLine())
                    val revision = fixture.session.activeShellCommandLineRevision.value
                    assertTrue(revision > previousRevision, "No revision for $state")
                    previousRevision = revision
                    assertEquals(-1L, fixture.session.renderGeneration.value)
                }
            }
        }

    @Test
    fun `equal values and unrelated output do not produce source revisions`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
                val snapshot = snapshot("git status")
                fixture.source.value = snapshot
                backgroundScope.launch { fixture.session.activeShellCommandLineRevision.collect {} }
                runCurrent()
                val revision = fixture.session.activeShellCommandLineRevision.value

                fixture.source.value = snapshot.copy()
                fixture.feed(PROMPT + "osc command")
                runCurrent()

                assertEquals(revision, fixture.session.activeShellCommandLineRevision.value)
                assertEquals(snapshot, fixture.session.activeShellCommandLine())
                assertTrue(fixture.session.renderGeneration.value >= 0L)
            }
        }

    @Test
    fun `host editing and command timeline never fall back to OSC`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
                backgroundScope.launch { fixture.session.activeShellCommandLineRevision.collect {} }
                fixture.feed(PROMPT + "echo timeline")
                runCurrent()
                assertNull(fixture.session.activeShellCommandLine())
                assertEquals(0L, fixture.session.activeShellCommandLineRevision.value)

                fixture.source.value = snapshot("host edit")
                fixture.feed("\u001B]133;C\u0007\r\noutput\r\n\u001B]133;D;0\u0007")
                runCurrent()
                assertEquals("host edit", fixture.session.activeShellCommandLine()?.commandText)
                assertEquals(0, fixture.session.shellIntegrationState.recordCount())
                assertNull(fixture.session.currentWorkingDirectoryUri())

                fixture.source.value = null
                fixture.feed(PROMPT + "another osc command")
                runCurrent()
                assertNull(fixture.session.activeShellCommandLine())
                assertTrue(fixture.session.activeShellCommandLineRevision.value >= 0L)
            }
        }

    @Test
    fun `source updates remain observable while the published viewport is scrolled away from live output`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
                fixture.feed("one\r\ntwo\r\nthree\r\nfour\r\nfive")
                fixture.session.requestRender(1)
                runCurrent()
                val rendered = fixture.session.renderGeneration.value
                backgroundScope.launch { fixture.session.activeShellCommandLineRevision.collect {} }
                runCurrent()

                fixture.source.value = snapshot("host edit")
                runCurrent()

                assertTrue(fixture.session.activeShellCommandLineRevision.value >= 0L)
                assertEquals("host edit", fixture.session.activeShellCommandLine()?.commandText)
                assertEquals(rendered, fixture.session.renderGeneration.value)
            }
        }

    @Test
    fun `observers share one source subscription and the last cancellation stops observation`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
                fixture.source.value = snapshot("first")
                val first = backgroundScope.launch { fixture.session.activeShellCommandLineRevision.collect {} }
                val second = backgroundScope.launch { fixture.session.activeShellCommandLineRevision.collect {} }
                runCurrent()
                assertEquals(1, fixture.source.subscriptionCount.value)

                first.cancelAndJoin()
                runCurrent()
                assertEquals(1, fixture.source.subscriptionCount.value)
                second.cancelAndJoin()
                runCurrent()
                assertEquals(0, fixture.source.subscriptionCount.value)
                assertEquals(-1L, fixture.session.activeShellCommandLineRevision.value)

                fixture.source.value = snapshot("while unobserved")
                runCurrent()
                assertEquals("while unobserved", fixture.session.activeShellCommandLine()?.commandText)
                assertEquals(-1L, fixture.session.activeShellCommandLineRevision.value)
            }
        }

    @Test
    fun `resubscription samples current state and cannot reuse a stale revision`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
                fixture.source.value = snapshot("first")
                val first = backgroundScope.launch { fixture.session.activeShellCommandLineRevision.collect {} }
                runCurrent()
                val firstRevision = fixture.session.activeShellCommandLineRevision.value
                first.cancelAndJoin()
                runCurrent()

                fixture.source.value = snapshot("second")
                backgroundScope.launch { fixture.session.activeShellCommandLineRevision.collect {} }
                runCurrent()
                assertEquals("second", fixture.session.activeShellCommandLine()?.commandText)
                assertTrue(fixture.session.activeShellCommandLineRevision.value > firstRevision)
                assertEquals(1, fixture.source.subscriptionCount.value)
            }
        }

    @Test
    fun `resubscription after context disappeared publishes an unavailable context revision`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
                fixture.source.value = snapshot("first")
                val first = backgroundScope.launch { fixture.session.activeShellCommandLineRevision.collect {} }
                runCurrent()
                first.cancelAndJoin()
                runCurrent()
                fixture.source.value = null

                backgroundScope.launch { fixture.session.activeShellCommandLineRevision.collect {} }
                runCurrent()

                assertNull(fixture.session.activeShellCommandLine())
                assertEquals(1L, fixture.session.activeShellCommandLineRevision.value)
            }
        }

    @Test
    fun `slow consumers do not delay newer source state or session rendering`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
                fixture.source.value = snapshot("first")
                val received = CompletableDeferred<Long>()
                val release = CompletableDeferred<Unit>()
                backgroundScope.launch {
                    fixture.session.activeShellCommandLineRevision.collect { revision ->
                        if (revision >= 0L) {
                            received.complete(revision)
                            release.await()
                        }
                    }
                }
                runCurrent()
                val previousRevision = received.await()
                fixture.source.value = snapshot("second")
                fixture.source.value = snapshot("latest")
                fixture.feed("output")
                runCurrent()

                assertEquals("latest", fixture.session.activeShellCommandLine()?.commandText)
                assertTrue(fixture.session.activeShellCommandLineRevision.value > previousRevision)
                assertTrue(fixture.session.renderGeneration.value >= 0L)
                release.complete(Unit)
            }
        }

    @ParameterizedTest
    @ValueSource(strings = ["local", "remote", "failure"])
    fun `every close path stops session observation without closing the host source`(termination: String) =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
                fixture.source.value = snapshot("first")
                var hostObserved: TerminalShellCommandLineSnapshot? = null
                backgroundScope.launch { fixture.source.collect { hostObserved = it } }
                backgroundScope.launch { fixture.session.activeShellCommandLineRevision.collect {} }
                runCurrent()
                assertEquals(2, fixture.source.subscriptionCount.value)
                val revision = fixture.session.activeShellCommandLineRevision.value
                val failure = IOException("Transport failed")

                when (termination) {
                    "local" -> fixture.session.close()
                    "remote" -> fixture.connector.simulateClosed(7)
                    "failure" -> fixture.connector.simulateCrash(failure)
                }
                assertNull(fixture.session.activeShellCommandLine())
                runCurrent()
                assertEquals(1, fixture.source.subscriptionCount.value)
                val afterClose = snapshot("host continues")
                fixture.source.value = afterClose
                runCurrent()

                assertEquals(afterClose, hostObserved)
                assertNull(fixture.session.activeShellCommandLine())
                assertEquals(revision, fixture.session.activeShellCommandLineRevision.value)
                assertEquals(1, fixture.connector.closeCount)
                assertFalse(fixture.session.isCoroutineScopeActive)
                if (termination == "remote") assertEquals(7, fixture.session.exitCode)
                if (termination == "failure") assertSame(failure, fixture.session.failure)
            }
        }

    @Test
    fun `a revision property first accessed after closure never subscribes to the host`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
                fixture.source.value = snapshot("first")
                fixture.session.close()
                backgroundScope.launch { fixture.session.activeShellCommandLineRevision.collect {} }
                runCurrent()

                assertEquals(0, fixture.source.subscriptionCount.value)
                assertEquals(-1L, fixture.session.activeShellCommandLineRevision.value)
                assertNull(fixture.session.activeShellCommandLine())
            }
        }

    @Test
    fun `startup submission without a selected integration is rejected before assembly`() =
        runTest {
            val connector = MockConnector()
            val renderReader = TerminalBuffers.create(30, 3)
            val terminal = object : TerminalBuffer by renderReader {}
            val dispatcher = StandardTestDispatcher(testScheduler)
            val failure =
                assertThrows(IllegalArgumentException::class.java) {
                    TerminalSession.create(
                        terminal = terminal,
                        renderReader = renderReader,
                        connector = connector,
                        startupCommand = TerminalStartupCommand("echo startup"),
                        workerDispatcher = dispatcher,
                        ioDispatcher = dispatcher,
                    )
                }
            runCurrent()
            assertTrue(failure.message.orEmpty().contains("startupCommand"))
            assertEquals(0, connector.startCount)
            assertEquals(0, connector.closeCount)
            assertTrue(connector.resizeCalls.isEmpty())
        }

    @Test
    fun `low level session construction supports the same host context and revision lifecycle`() =
        runTest {
            val terminal = TerminalBuffers.create(30, 3)
            val connector = MockConnector()
            val source = MutableStateFlow<TerminalShellCommandLineSnapshot?>(snapshot("host context"))
            val dispatcher = StandardTestDispatcher(testScheduler)
            TerminalSession(
                terminal = terminal,
                renderPublisher = TerminalRenderPublisher(30, 3),
                renderReader = terminal as TerminalRenderFrameReader,
                responseReader = terminal,
                connector = connector,
                parser = TerminalParsers.create(HostCommandAdapter(terminal)),
                workerDispatcher = dispatcher,
                ioDispatcher = dispatcher,
                shellIntegration = TerminalShellIntegrationFactory.host(TerminalShellIntegrationState(), source),
            ).use { session ->
                session.start(30, 3)
                backgroundScope.launch { session.activeShellCommandLineRevision.collect {} }
                runCurrent()
                assertEquals(source.value, session.activeShellCommandLine())
                assertTrue(session.activeShellCommandLineRevision.value >= 0L)

                connector.feedFromHost("plain output".toByteArray())
                source.value = null
                runCurrent()
                assertEquals("plain output", terminal.getLineAsString(0))
                assertNull(session.activeShellCommandLine())
                assertEquals(1, source.subscriptionCount.value)
            }
            runCurrent()
            assertEquals(0, source.subscriptionCount.value)
        }

    @Test
    fun `host directory and command metadata remain authoritative even when OSC is allowed`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
                val state = fixture.model
                state.recordCurrentWorkingDirectory("file:///host/project")
                var lineId = 0L
                fixture.session.readRenderFrame { lineId = it.lineId(0) }
                state.recordPromptStart(lineId)
                state.recordPromptEnd(lineId)
                state.recordCommandStart(
                    lineId,
                    includeLine = false,
                    commandText = "host command",
                    workingDirectoryUri = state.currentWorkingDirectoryUri(),
                )
                val recordId = state.latestCommandRecordId()
                fixture.source.value = snapshot("host command")
                fixture.feed("\u001B]7;file:///remote/path\u0007" + PROMPT + "echo remote\u001B]133;C\u0007\u001B]133;D;7\u0007")
                runCurrent()

                assertEquals("file:///host/project", fixture.session.currentWorkingDirectoryUri())
                assertEquals(1, state.recordCount())
                assertEquals(recordId, state.latestCommandRecordId())
                assertEquals("file:///host/project", state.commandWorkingDirectoryUri(recordId))
                assertEquals("host command", state.commandText(recordId))
                assertEquals(TerminalShellIntegrationCommandLifecycle.RUNNING, state.commandMetadata(recordId)?.lifecycle)
                assertEquals("host command", fixture.session.activeShellCommandLine()?.commandText)
            }
        }

    private class Fixture(
        dispatcher: CoroutineDispatcher,
        start: Boolean = true,
    ) : AutoCloseable {
        val model = TerminalShellIntegrationState()
        val source = MutableStateFlow<TerminalShellCommandLineSnapshot?>(null)
        val connector = MockConnector()
        val session =
            TerminalSession.create(
                terminal = TerminalBuffers.create(30, 3),
                connector = connector,
                workerDispatcher = dispatcher,
                ioDispatcher = dispatcher,
                shellIntegration = TerminalShellIntegrationFactory.host(model, source),
            )

        init {
            if (start) session.start(30, 3)
        }

        fun feed(text: String) {
            connector.feedFromHost(text.toByteArray())
            session.requestRender(0)
        }

        override fun close() = session.close()
    }

    private companion object {
        private const val PROMPT = "\u001B]133;A\u0007> \u001B]133;B\u0007"

        private fun snapshot(
            text: String,
            cursorOffset: Int = text.length,
        ): TerminalShellCommandLineSnapshot =
            TerminalShellCommandLineSnapshot(
                commandText = text,
                cursorOffset = cursorOffset,
                cursorColumn = 5,
                cursorRow = 1,
            )
    }
}
