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
import io.github.ketraterm.host.HostEventSink
import io.github.ketraterm.parser.api.TerminalParsers
import io.github.ketraterm.render.cache.TerminalRenderPublisher
import io.github.ketraterm.testkit.MockConnector
import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.transport.TerminalConnectorListener
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class TerminalSessionResizeAdmissionTest {
    private val dispatcher = StandardTestDispatcher()

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `closure before admitted DECCOLM suppresses transport resize and finishes core mutation`(remoteClosure: Boolean) {
        val recorded = MockConnector()
        val bellEntered = CountDownLatch(1)
        val releaseBell = CountDownLatch(1)
        val connectorClosed = CountDownLatch(1)
        val terminal = TerminalBuffers.create(80, 3)
        val connector =
            object : TerminalConnector by recorded {
                override fun resize(
                    columns: Int,
                    rows: Int,
                ) {
                    assertFalse(recorded.isClosed, "Resize entered a disposed connector")
                    recorded.resize(columns, rows)
                }

                override fun close() {
                    recorded.close()
                    connectorClosed.countDown()
                }
            }
        val events =
            object : HostEventSink by HostEventSink.NONE {
                override fun bell() {
                    bellEntered.countDown()
                    check(releaseBell.await(SESSION_THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "Bell callback was not released" }
                }
            }
        try {
            TerminalSession
                .create(
                    terminal,
                    connector,
                    hostEvents = events,
                    workerDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                ).use { session ->
                    session.start(80, 3)
                    SessionTestThread("session-deccolm-after-close") {
                        recorded.feedFromHost("\u0007\u001B[?3hafter".encodeToByteArray())
                    }.use { output ->
                        try {
                            assertTrue(bellEntered.await(SESSION_THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                            SessionTestThread("session-close-before-deccolm") {
                                if (remoteClosure) recorded.simulateClosed(17) else session.close()
                            }.use { close ->
                                try {
                                    assertTrue(connectorClosed.await(SESSION_THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                                    close.awaitBlockedBy(output)
                                    assertTrue(session.isClosed)
                                } finally {
                                    releaseBell.countDown()
                                }
                                output.awaitCompletion()
                                close.awaitCompletion()
                            }
                        } finally {
                            releaseBell.countDown()
                        }
                    }
                    assertEquals(listOf(80 to 3), recorded.resizeCalls)
                    assertEquals(1, recorded.closeCount)
                    assertEquals(132, terminal.width)
                    assertEquals("after", terminal.getLineAsString(0))
                    val closed = session.state.value as TerminalSessionState.Closed
                    assertEquals(!remoteClosure, closed.event.locallyRequested)
                    assertEquals(if (remoteClosure) 17 else null, closed.event.exitCode)
                }
        } finally {
            releaseBell.countDown()
            dispatcher.scheduler.runCurrent()
        }
    }

    @Test
    fun `connector cleanup waits for admitted DECCOLM transport resize before final core publication`() {
        val recorded = MockConnector()
        val resizeEntered = CountDownLatch(1)
        val releaseResize = CountDownLatch(1)
        val connectorClosed = CountDownLatch(1)
        val terminal = TerminalBuffers.create(80, 3)
        val events = Collections.synchronizedList(mutableListOf<String>())
        val connector =
            object : TerminalConnector by recorded {
                override fun resize(
                    columns: Int,
                    rows: Int,
                ) {
                    if (columns == 132) {
                        assertEquals(80, terminal.width, "Transport resize must precede the destructive core reset")
                        assertEquals("before", terminal.getLineAsString(0))
                        events += "resize entered"
                        resizeEntered.countDown()
                        check(releaseResize.await(SESSION_THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "Connector resize was not released" }
                    }
                    recorded.resize(columns, rows)
                    if (columns == 132) events += "resize completed"
                }

                override fun close() {
                    events += "close"
                    recorded.close()
                    connectorClosed.countDown()
                }
            }
        try {
            TerminalSession
                .create(
                    terminal,
                    connector,
                    workerDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                ).use { session ->
                    session.start(80, 3)
                    recorded.feedFromHost("before".encodeToByteArray())
                    SessionTestThread("session-deccolm-resize") {
                        recorded.feedFromHost("\u001B[?3hafter".encodeToByteArray())
                    }.use { output ->
                        try {
                            assertTrue(resizeEntered.await(SESSION_THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                            SessionTestThread("session-close-during-deccolm-resize") { session.close() }.use { close ->
                                try {
                                    close.awaitBlockedBy(output)
                                    assertTrue(session.isClosed)
                                    assertEquals(1L, connectorClosed.count, "Connector cleanup overlapped its active resize")
                                    assertEquals(listOf("resize entered"), events)
                                } finally {
                                    releaseResize.countDown()
                                }
                                output.awaitCompletion()
                                close.awaitCompletion()
                            }
                        } finally {
                            releaseResize.countDown()
                        }
                    }
                    assertEquals(listOf("resize entered", "resize completed", "close"), events)
                    assertEquals(listOf(80 to 3, 132 to 3), recorded.resizeCalls)
                    assertEquals(1, recorded.closeCount)
                    assertTrue(session.state.value is TerminalSessionState.Closed)
                    assertEquals(132, terminal.width)
                    assertEquals("after", terminal.getLineAsString(0))
                    assertEquals(132, session.readPublishedFrame { it.columns })
                }
        } finally {
            releaseResize.countDown()
            dispatcher.scheduler.runCurrent()
        }
    }

    @Test
    fun `closure during core reflow suppresses connector resize without blocking connector cleanup`() {
        val resizeEntered = CountDownLatch(1)
        val releaseResize = CountDownLatch(1)
        val connectorClosed = CountDownLatch(1)
        val core = TerminalBuffers.create(10, 3)
        var gateResize = false
        val terminal =
            object : TerminalBuffer by core {
                override fun resize(
                    newWidth: Int,
                    newHeight: Int,
                    oldScrollbackOffset: Int,
                ): Pair<Int, Int> {
                    if (gateResize) {
                        resizeEntered.countDown()
                        check(releaseResize.await(10, TimeUnit.SECONDS))
                    }
                    return core.resize(newWidth, newHeight, oldScrollbackOffset)
                }
            }
        val calls = mutableListOf<Pair<Int, Int>>()
        val connector =
            object : TerminalConnector {
                override fun start(listener: TerminalConnectorListener) {}

                override fun resize(
                    columns: Int,
                    rows: Int,
                ) {
                    check(connectorClosed.count != 0L)
                    calls += columns to rows
                }

                override fun write(
                    bytes: ByteArray,
                    offset: Int,
                    length: Int,
                ) = error("unexpected output")

                override fun close() {
                    connectorClosed.countDown()
                }
            }
        val session =
            TerminalSession(
                terminal,
                TerminalRenderPublisher(10, 3),
                core,
                core,
                connector,
                TerminalParsers.create(HostCommandAdapter(terminal)),
                workerDispatcher = dispatcher,
                ioDispatcher = dispatcher,
            )
        session.start(10, 3)
        gateResize = true
        val resize = FutureTask { session.tryResizeViewport(8, 2) }
        val close = FutureTask { session.close() }
        thread { resize.run() }
        try {
            assertTrue(resizeEntered.await(10, TimeUnit.SECONDS))
            thread { close.run() }
            assertTrue(connectorClosed.await(10, TimeUnit.SECONDS))
            assertNull(session.tryResizeViewport(20, 6))
        } finally {
            releaseResize.countDown()
            assertNotNull(resize.get(10, TimeUnit.SECONDS))
            close.get(10, TimeUnit.SECONDS)
            dispatcher.scheduler.runCurrent()
        }
        assertEquals(listOf(10 to 3), calls)
        session.readRenderFrame {
            assertEquals(8, it.columns)
            assertEquals(2, it.rows)
        }
    }

    @Test
    fun `resize admission preserves validation and collaborator failures`() {
        val failure = IllegalStateException("connector resize failed")
        val connector =
            object : TerminalConnector {
                override fun start(listener: TerminalConnectorListener) {}

                override fun resize(
                    columns: Int,
                    rows: Int,
                ): Unit = throw failure

                override fun write(
                    bytes: ByteArray,
                    offset: Int,
                    length: Int,
                ) {}

                override fun close() {}
            }
        val session =
            TerminalSession.create(
                TerminalBuffers.create(10, 3),
                connector,
                workerDispatcher = dispatcher,
                ioDispatcher = dispatcher,
            )
        session.use {
            assertSame(failure, assertThrows(IllegalStateException::class.java) { session.tryResizeViewport(8, 2) })
        }
        assertNull(session.tryResizeViewport(8, 2))
        for ((columns, rows) in listOf(0 to 1, 1 to 0, Int.MIN_VALUE to 1, 1 to Int.MIN_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { session.tryResizeViewport(columns, rows) }
        }
        assertThrows(IllegalStateException::class.java) { session.resizeViewport(8, 2) }
        dispatcher.scheduler.runCurrent()
    }
}
