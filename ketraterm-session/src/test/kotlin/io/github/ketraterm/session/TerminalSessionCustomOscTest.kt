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
import io.github.ketraterm.host.*
import io.github.ketraterm.parser.api.TerminalOutputParserFactory
import io.github.ketraterm.parser.api.TerminalParsers
import io.github.ketraterm.testkit.MockConnector
import io.github.ketraterm.transport.TerminalConnector
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.*
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalSessionCustomOscTest {
    @Test
    fun `host metadata anchors precede later bytes in every chunk arrangement`() =
        runTest {
            val bytes = "pre\r\n\u001b]1341;prompt\u001b\\later\r\nlast\r\n\u001b[5n".encodeToByteArray()
            for (split in -1..bytes.size) {
                val connector = MockConnector()
                val dispatcher = StandardTestDispatcher(testScheduler)
                val model = TerminalShellIntegrationState()
                val ready = MutableStateFlow(false)
                var anchor = 0L
                var calls = 0
                lateinit var session: TerminalSession
                session =
                    TerminalSession.create(
                        TerminalBuffers.create(10, 2),
                        connector,
                        shellIntegration = TerminalShellIntegrationFactory.host(model, MutableStateFlow(null), ready),
                        startupCommand = TerminalStartupCommand("hello"),
                        workerDispatcher = dispatcher,
                        ioDispatcher = dispatcher,
                        parserFactory =
                            TerminalOutputParserFactory { sink, budget ->
                                TerminalParsers.create(sink, budget) { command, payload, offset, length ->
                                    assertEquals(1341, command)
                                    assertEquals("prompt", payload.decodeToString(offset, offset + length))
                                    calls++
                                    session.readRenderFrame { frame ->
                                        assertEquals(1, frame.cursor.row)
                                        assertEquals(0, frame.cursor.column)
                                        anchor = frame.lineId(frame.cursor.row)
                                    }
                                    model.recordPromptStart(anchor)
                                    model.recordCommandStart(anchor, includeLine = true, commandText = "host-command")
                                    ready.value = true
                                }
                            },
                    )
                session.use {
                    session.start(10, 2)
                    if (split == -1) {
                        for (byte in bytes) connector.feedFromHost(byteArrayOf(byte))
                    } else {
                        connector.feedFromHost(bytes.copyOfRange(0, split))
                        connector.feedFromHost(bytes.copyOfRange(split, bytes.size))
                    }
                    assertEquals(1, calls, "split=$split")
                    assertTrue(anchor > 0)
                    assertSame(model, session.shellIntegrationState)
                    assertEquals(anchor, model.commandAnchorLineId(model.latestCommandRecordId()))
                    session.readRenderFrame { frame -> assertNotEquals(anchor, frame.lineId(frame.cursor.row)) }
                    runCurrent()
                    // Startup readiness can be observed between chunks, but never inside a callback.
                    val written = connector.writtenBytes.decodeToString()
                    assertTrue(written.contains("hello\r"))
                    assertTrue(written.contains("\u001b[0n"))
                    if (split == bytes.size) assertEquals("\u001b[0nhello\r", written)
                }
                assertEquals(1, model.recordCount())
                model.recordCurrentWorkingDirectory("file:///host/still-owned")
                assertEquals("file:///host/still-owned", model.currentWorkingDirectoryUri())
            }
        }

    @Test
    fun `custom parsing retains clipboard services and live policy gates`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val connector = MockConnector()
            val writes = mutableListOf<String>()
            val custom = mutableListOf<Int>()
            var reads = 0
            val clipboard =
                TerminalClipboardPolicy(
                    readPermission = TerminalClipboardPermission.ALLOW,
                    writePermission = TerminalClipboardPermission.ALLOW,
                    maxDecodedBytes = 6000,
                )
            val policy = HostPolicy(clipboardPolicy = clipboard)
            TerminalSession
                .create(
                    TerminalBuffers.create(10, 2),
                    connector,
                    workerDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                    hostPolicy = policy,
                    hostEvents =
                        object : HostEventSink by HostEventSink.NONE {
                            override fun terminalClipboardWrite(event: TerminalClipboardWriteEvent) {
                                writes += event.text
                            }
                        },
                    clipboardReader =
                        TerminalClipboardReader {
                            reads++
                            TerminalClipboardReadResult.Text("ok")
                        },
                    parserFactory =
                        TerminalOutputParserFactory { sink, budget ->
                            TerminalParsers.create(sink, budget, 12000) { command, _, _, _ -> custom += command }
                        },
                ).use { session ->
                    session.start(10, 2)
                    val text = "x".repeat(6000)
                    val write = "\u001b]52;c;" + Base64.getEncoder().encodeToString(text.encodeToByteArray()) + "\u0007"
                    connector.feedFromHost("$write\u001b]52;c;?\u0007\u001b]1341;$text\u0007".encodeToByteArray())
                    runCurrent()
                    assertEquals(listOf(text), writes)
                    assertEquals(1, reads)
                    assertEquals("\u001b]52;c;b2s=\u001b\\", connector.writtenBytes.decodeToString())
                    session.setHostPolicy(
                        policy.copy(
                            terminalResponsePolicy = HostControlPolicy.DENY,
                            palettePolicy = HostControlPolicy.DENY,
                            clipboardPolicy = clipboard.copy(writePermission = TerminalClipboardPermission.DENY),
                        ),
                    )
                    val deniedQueries = "\u001b]52;c;?\u0007\u001b]10;?\u0007\u001b[5n\u001b]1341;ok\u0007"
                    connector.feedFromHost((write + deniedQueries).encodeToByteArray())
                    runCurrent()
                    assertEquals(listOf(text), writes)
                    assertEquals(1, reads)
                    assertEquals("\u001b]52;c;b2s=\u001b\\", connector.writtenBytes.decodeToString())
                    assertEquals(listOf(1341, 1341), custom)
                    session.setHostPolicy(policy.copy(clipboardPolicy = clipboard.copy(maxDecodedBytes = 5999)))
                    connector.feedFromHost(write.encodeToByteArray())
                    assertEquals(listOf(text), writes)
                    session.setHostPolicy(policy)
                    connector.feedFromHost(write.encodeToByteArray())
                    assertEquals(listOf(text, text), writes)
                    connector.feedFromHost("\u001b[?3hwide".encodeToByteArray())
                    assertEquals(132 to 2, connector.resizeCalls.last())
                }
        }

    @Test
    fun `callback exceptions propagate and reported failures close once`() =
        runTest {
            for (failure in listOf(IllegalArgumentException("host failure"), CancellationException("host cancelled"))) {
                val connector = MockConnector()
                val dispatcher = StandardTestDispatcher(testScheduler)
                val terminal = TerminalBuffers.create(20, 2)
                val session =
                    TerminalSession.create(
                        terminal,
                        connector,
                        workerDispatcher = dispatcher,
                        ioDispatcher = dispatcher,
                        parserFactory =
                            TerminalOutputParserFactory { sink, budget ->
                                TerminalParsers.create(sink, budget) { _, _, _, _ -> throw failure }
                            },
                    )
                session.use {
                    session.start(20, 2)
                    assertSame(
                        failure,
                        assertThrows(failure.javaClass) {
                            connector.feedFromHost("before\u001b]1341;fail\u0007after".encodeToByteArray())
                        },
                    )
                    connector.simulateCrash(failure)
                    val closed = session.state.value as TerminalSessionState.Closed
                    assertSame(failure, closed.event.failure)
                    assertEquals("before", terminal.getLineAsString(0))
                    assertEquals(1, connector.closeCount)
                    assertFalse(session.isCoroutineScopeActive)
                    session.onBytes("late".encodeToByteArray(), 0, 4)
                    assertEquals("before", terminal.getLineAsString(0))
                }
                assertEquals(1, connector.closeCount)
            }
        }

    @Test
    fun `recursive output rejects mutation before failure reporting`() =
        runTest {
            val connector = MockConnector()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val terminal = TerminalBuffers.create(20, 2)
            lateinit var session: TerminalSession
            session =
                TerminalSession.create(
                    terminal,
                    connector,
                    workerDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                    parserFactory =
                        TerminalOutputParserFactory { sink, budget ->
                            TerminalParsers.create(sink, budget) { _, _, _, _ ->
                                session.onBytes(byteArrayOf(65), 0, 1)
                            }
                        },
                )
            session.use {
                session.start(20, 2)
                val failure =
                    assertThrows(IllegalStateException::class.java) {
                        connector.feedFromHost("before\u001b]1341;x\u0007after".encodeToByteArray())
                    }
                connector.simulateCrash(failure)
                assertSame(failure, (session.state.value as TerminalSessionState.Closed).event.failure)
                assertEquals("before", terminal.getLineAsString(0))
                assertEquals(1, connector.closeCount)
                assertTrue(failure.suppressed.isEmpty())
            }
        }

    @Test
    fun `close waits for admitted custom callback and EOF never dispatches a partial command`() =
        runTest {
            val connector = MockConnector()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val connectorClosed = CountDownLatch(1)
            val closingConnector =
                object : TerminalConnector by connector {
                    override fun close() {
                        connector.close()
                        connectorClosed.countDown()
                    }
                }
            val executor = Executors.newFixedThreadPool(2)
            var calls = 0
            val terminal = TerminalBuffers.create(20, 2)
            val session =
                TerminalSession.create(
                    terminal,
                    closingConnector,
                    workerDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                    parserFactory =
                        TerminalOutputParserFactory { sink, budget ->
                            TerminalParsers.create(sink, budget) { _, _, _, _ ->
                                calls++
                                entered.countDown()
                                assertTrue(release.await(10, TimeUnit.SECONDS))
                            }
                        },
                )
            try {
                session.start(20, 2)
                val output =
                    executor.submit {
                        connector.feedFromHost("before\u001b]1341;x\u0007after\u001b]1341;partial".encodeToByteArray())
                    }
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                val close =
                    executor.submit {
                        session.close()
                    }
                assertTrue(connectorClosed.await(10, TimeUnit.SECONDS))
                release.countDown()
                output.get(10, TimeUnit.SECONDS)
                close.get(10, TimeUnit.SECONDS)
                assertEquals(1, calls)
                assertEquals("beforeafter", terminal.getLineAsString(0))
                assertTrue(session.state.value is TerminalSessionState.Closed)
                assertEquals(1, connector.closeCount)
            } finally {
                release.countDown()
                session.close()
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
            }
        }
}
