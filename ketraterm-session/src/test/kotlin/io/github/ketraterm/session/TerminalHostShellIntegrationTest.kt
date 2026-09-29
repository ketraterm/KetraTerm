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
import io.github.ketraterm.host.HostEventSink
import io.github.ketraterm.input.event.TerminalKeyEvent
import io.github.ketraterm.input.event.TerminalPasteEvent
import io.github.ketraterm.input.event.TerminalTextReplacementEvent
import io.github.ketraterm.protocol.ShellIntegrationEvent
import io.github.ketraterm.testkit.MockConnector
import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.transport.TerminalConnectorListener
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalHostShellIntegrationTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `host observation publishes unavailable context on subscription and restart`(initiallyAvailable: Boolean) =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler), command = null).use { fixture ->
                if (initiallyAvailable) {
                    fixture.commandLine.value = TerminalShellCommandLineSnapshot("git s", 5, 5, 0)
                }
                val revisions = mutableListOf<Long>()
                val observer =
                    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                        fixture.session.activeShellCommandLineRevision.collect { revisions += it }
                    }
                // The request has captured context, but the host collector has not started.
                fixture.commandLine.value = null
                runCurrent()
                assertNull(fixture.session.activeShellCommandLine())
                assertTrue(revisions.last() >= 0, "unavailable context must trigger revalidation")
                val firstRevision = revisions.last()
                observer.cancel()
                runCurrent()
                assertEquals(0, fixture.commandLine.subscriptionCount.value)
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    fixture.session.activeShellCommandLineRevision.collect { revisions += it }
                }
                runCurrent()
                assertTrue(revisions.last() > firstRevision, "resubscription must revalidate even unchanged null context")
            }
        }

    @Test
    fun `session without integration forwards protocol events but creates no shell records`() =
        runTest {
            val connector = MockConnector()
            val events = mutableListOf<ShellIntegrationEvent>()
            val directories = mutableListOf<String>()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val host =
                object : HostEventSink by HostEventSink.NONE {
                    override fun shellIntegrationMarker(event: ShellIntegrationEvent) {
                        events += event
                    }

                    override fun currentWorkingDirectoryChanged(uri: String) {
                        directories += uri
                    }
                }
            TerminalSession
                .create(
                    terminal = TerminalBuffers.create(40, 4),
                    connector = connector,
                    hostEvents = host,
                    workerDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                ).use { session ->
                    session.start(40, 4)
                    connector.feedFromHost(
                        (
                            "\u001B]7;file:///remote/project\u0007" + PROMPT +
                                "echo wire\u001B]133;C\u0007\r\nresult\u001B]133;D;0\u0007"
                        ).toByteArray(),
                    )
                    backgroundScope.launch { session.activeShellCommandLineRevision.collect {} }
                    runCurrent()
                    assertEquals(4, events.size)
                    assertEquals(listOf("file:///remote/project"), directories)
                    assertEquals(0, session.shellIntegrationState.recordCount())
                    assertNull(session.currentWorkingDirectoryUri())
                    assertNull(session.activeShellCommandLine())
                    assertEquals(-1L, session.activeShellCommandLineRevision.value)
                    assertEquals("", connector.writtenBytes.decodeToString())
                }
        }

    @Test
    fun `host semantic records drive history ranges lifecycle and completion without OSC`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler), command = null).use { fixture ->
                val model = fixture.model
                assertSame(model, fixture.session.shellIntegrationState)
                fixture.connector.feedFromHost("host> build\r\noutput\r\n".toByteArray())
                var promptLine = 0L
                var outputLine = 0L
                fixture.session.readRenderFrame { frame ->
                    promptLine = frame.lineId(0)
                    outputLine = frame.lineId(1)
                }
                val completed = mutableListOf<TerminalShellIntegrationCommandMetadata>()
                model
                    .addCommandFinishedListener { metadata ->
                        assertFalse(model.hasRunningCommand())
                        assertEquals(metadata, model.commandMetadata(metadata.recordId))
                        completed += metadata
                    }.use {
                        model.recordCurrentWorkingDirectory("file:///host/project")
                        model.recordPromptStart(promptLine)
                        model.recordPromptEnd(promptLine)
                        model.recordCommandStart(
                            outputLine,
                            includeLine = true,
                            commandText = "build",
                            workingDirectoryUri = model.currentWorkingDirectoryUri(),
                        )
                        val recordId = model.latestCommandRecordId()
                        assertTrue(model.hasRunningCommand())
                        assertEquals(promptLine, model.commandAnchorLineId(recordId))
                        model.recordCommandFinished(outputLine, 7)
                        model.recordCommandFinished(outputLine, 0)
                        assertEquals(1, completed.size)
                        assertEquals("build", completed.single().commandText)
                        assertEquals("file:///host/project", completed.single().workingDirectoryUri)
                        assertEquals(7, completed.single().exitCode)
                        assertEquals(TerminalShellIntegrationCommandLifecycle.FAILED, completed.single().lifecycle)
                        val range = LongArray(TerminalShellIntegrationCommandOutputRange.REQUIRED_LONGS)
                        assertTrue(model.copyCommandOutputRange(recordId, range))
                        assertArrayEquals(longArrayOf(outputLine, outputLine, 1L), range)
                    }
                assertEquals("file:///host/project", fixture.session.currentWorkingDirectoryUri())
                fixture.session.close()
                assertEquals(1, model.recordCount())
                model.recordCurrentWorkingDirectory("file:///host/after-close")
                assertEquals("file:///host/after-close", model.currentWorkingDirectoryUri())
            }
        }

    @Test
    fun `host readiness submits without output while active editing and OSC never imply readiness`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
                fixture.commandLine.value = TerminalShellCommandLineSnapshot("editable", 4, 4, 0)
                fixture.connector.feedFromHost(PROMPT.toByteArray())
                runCurrent()
                assertEquals(TerminalStartupCommandStatus.WAITING, fixture.session.startupCommandStatus?.value)
                assertEquals("", fixture.connector.writtenBytes.decodeToString())
                fixture.ready.value = true
                runCurrent()
                assertEquals("echo ready\r", fixture.connector.writtenBytes.decodeToString())
                assertEquals(TerminalStartupCommandStatus.SUBMITTED, fixture.session.startupCommandStatus?.value)
                assertEquals(0, fixture.ready.subscriptionCount.value)
                fixture.ready.value = false
                runCurrent()
                fixture.ready.value = true
                runCurrent()
                assertEquals("echo ready\r", fixture.connector.writtenBytes.decodeToString())
            }
        }

    @Test
    fun `initial readiness waits for transport start before queuing startup input`() =
        runTest {
            val connector = MockConnector()
            var started = false
            val transport =
                object : TerminalConnector by connector {
                    override fun start(listener: TerminalConnectorListener) {
                        connector.start(listener)
                        started = true
                    }

                    override fun write(
                        bytes: ByteArray,
                        offset: Int,
                        length: Int,
                    ) {
                        assertTrue(started, "Startup input reached the connector before start completed")
                        connector.write(bytes, offset, length)
                    }
                }
            val ready = MutableStateFlow(true)
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            TerminalSession
                .create(
                    TerminalBuffers.create(40, 4),
                    transport,
                    shellIntegration = TerminalShellIntegrationFactory.host(TerminalShellIntegrationState(), promptReady = ready),
                    startupCommand = TerminalStartupCommand("echo ready"),
                    workerDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                ).use { session ->
                    runCurrent()
                    assertEquals("", connector.writtenBytes.decodeToString())
                    assertEquals(TerminalStartupCommandStatus.WAITING, session.startupCommandStatus?.value)
                    session.start(40, 4)
                    runCurrent()
                    assertEquals("echo ready\r", connector.writtenBytes.decodeToString())
                    assertFalse(session.isClosed)
                }
        }

    @Test
    fun `readiness published during parsing waits for final modes and pending responses`() =
        runTest {
            val ready = MutableStateFlow(false)
            val events =
                object : HostEventSink by HostEventSink.NONE {
                    override fun windowTitleChanged(title: String) {
                        ready.value = title == "ready"
                    }
                }
            Fixture(UnconfinedTestDispatcher(testScheduler), ready = ready, hostEvents = events).use { fixture ->
                fixture.connector.feedFromHost("\u001B]2;ready\u0007\u001B[?2004h\u001B[5n".toByteArray())
                runCurrent()
                assertEquals("\u001B[0n\u001B[200~echo ready\u001B[201~\r", fixture.connector.writtenBytes.decodeToString())
            }
        }

    @Test
    fun `readiness revoked later in the same batch prevents startup submission`() =
        runTest {
            val ready = MutableStateFlow(false)
            val events =
                object : HostEventSink by HostEventSink.NONE {
                    override fun windowTitleChanged(title: String) {
                        ready.value = title == "ready"
                    }
                }
            Fixture(UnconfinedTestDispatcher(testScheduler), ready = ready, hostEvents = events).use { fixture ->
                fixture.connector.feedFromHost("\u001B]2;ready\u0007\u001B]2;busy\u0007\u001B[5n".toByteArray())
                runCurrent()
                assertEquals("\u001B[0n", fixture.connector.writtenBytes.decodeToString())
                assertEquals(TerminalStartupCommandStatus.WAITING, fixture.session.startupCommandStatus?.value)
                ready.value = true
                runCurrent()
                assertEquals("\u001B[0necho ready\r", fixture.connector.writtenBytes.decodeToString())
            }
        }

    @Test
    fun `alternate screen blocks startup until a primary prompt is ready`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
                fixture.connector.feedFromHost("\u001B[?1049h".toByteArray())
                fixture.ready.value = true
                runCurrent()
                assertEquals("", fixture.connector.writtenBytes.decodeToString())
                fixture.ready.value = false
                fixture.connector.feedFromHost("\u001B[?1049l".toByteArray())
                runCurrent()
                assertEquals("", fixture.connector.writtenBytes.decodeToString())
                fixture.ready.value = true
                runCurrent()
                assertEquals("echo ready\r", fixture.connector.writtenBytes.decodeToString())
            }
        }

    @ParameterizedTest
    @ValueSource(strings = ["key", "paste", "replacement"])
    fun `user input cancels host-ready startup and stops readiness observation`(input: String) =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
                runCurrent()
                assertEquals(1, fixture.ready.subscriptionCount.value)
                when (input) {
                    "key" -> fixture.session.encodeKey(TerminalKeyEvent(codepoint = 'x'.code))
                    "paste" -> fixture.session.encodePaste(TerminalPasteEvent("x"))
                    "replacement" -> fixture.session.encodeTextReplacement(TerminalTextReplacementEvent(0, 0, "x"))
                }
                fixture.ready.value = true
                runCurrent()
                assertEquals("x", fixture.connector.writtenBytes.decodeToString())
                assertEquals(TerminalStartupCommandStatus.CANCELLED_BY_INPUT, fixture.session.startupCommandStatus?.value)
                assertEquals(0, fixture.ready.subscriptionCount.value)
            }
        }

    @ParameterizedTest
    @ValueSource(strings = ["local", "remote", "failure"])
    fun `closure discards pending startup without taking ownership of host readiness`(termination: String) =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
                var hostReady = false
                backgroundScope.launch { fixture.ready.collect { hostReady = it } }
                runCurrent()
                assertEquals(2, fixture.ready.subscriptionCount.value)
                when (termination) {
                    "local" -> fixture.session.close()
                    "remote" -> fixture.connector.simulateClosed(0)
                    "failure" -> fixture.connector.simulateCrash(IOException("Transport failed"))
                }
                fixture.ready.value = true
                runCurrent()
                assertEquals(TerminalStartupCommandStatus.CLOSED, fixture.session.startupCommandStatus?.value)
                assertTrue(hostReady)
                assertEquals(1, fixture.ready.subscriptionCount.value)
                assertEquals("", fixture.connector.writtenBytes.decodeToString())
                assertEquals(1, fixture.connector.closeCount)
            }
        }

    @Test
    fun `sessions without startup commands never subscribe to host readiness`() =
        runTest {
            Fixture(StandardTestDispatcher(testScheduler), command = null).use { fixture ->
                runCurrent()
                fixture.ready.value = true
                runCurrent()
                assertEquals(0, fixture.ready.subscriptionCount.value)
                assertNull(fixture.session.startupCommandStatus)
                assertEquals("", fixture.connector.writtenBytes.decodeToString())
            }
        }

    private class Fixture(
        dispatcher: CoroutineDispatcher,
        command: String? = "echo ready",
        val ready: MutableStateFlow<Boolean> = MutableStateFlow(false),
        hostEvents: HostEventSink = HostEventSink.NONE,
    ) : AutoCloseable {
        val connector = MockConnector()
        val model = TerminalShellIntegrationState()
        val commandLine = MutableStateFlow<TerminalShellCommandLineSnapshot?>(null)
        val session =
            TerminalSession.create(
                terminal = TerminalBuffers.create(40, 4),
                connector = connector,
                hostEvents = hostEvents,
                shellIntegration = TerminalShellIntegrationFactory.host(model, commandLine, ready),
                startupCommand = command?.let(::TerminalStartupCommand),
                workerDispatcher = dispatcher,
                ioDispatcher = dispatcher,
            )

        init {
            session.start(40, 4)
        }

        override fun close() = session.close()
    }

    private companion object {
        const val PROMPT = "\u001B]133;A\u0007> \u001B]133;B\u0007"
    }
}
