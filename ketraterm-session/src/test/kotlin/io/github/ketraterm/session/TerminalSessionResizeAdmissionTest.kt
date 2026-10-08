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
import io.github.ketraterm.render.api.TerminalRenderFrameConsumer
import io.github.ketraterm.render.api.TerminalRenderFrameReader
import io.github.ketraterm.render.cache.TerminalRenderPublisher
import io.github.ketraterm.testkit.MockConnector
import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.transport.TerminalConnectorListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.milliseconds

class TerminalSessionResizeAdmissionTest {
    private val dispatcher = StandardTestDispatcher()

    @OptIn(ExperimentalCoroutinesApi::class)
    @ParameterizedTest
    @CsvSource(
        "resize, false",
        "resizeViewport, false",
        "tryResizeViewport, false",
        "resize, true",
        "resizeViewport, true",
        "tryResizeViewport, true",
    )
    fun `connector resize failure preserves the exception and publishes resized geometry`(
        operation: String,
        cancelled: Boolean,
    ) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val terminal = TerminalBuffers.create(10, 3)
        val recorded = MockConnector()
        val failure =
            if (cancelled) {
                CancellationException(
                    "connector resize cancelled",
                )
            } else {
                IllegalStateException("connector resize failed")
            }
        val connector =
            object : TerminalConnector by recorded {
                override fun resize(
                    columns: Int,
                    rows: Int,
                ) {
                    recorded.resize(columns, rows)
                    if (columns == 8) throw failure
                }
            }
        TerminalSession
            .create(
                terminal,
                connector,
                workerDispatcher = dispatcher,
                ioDispatcher = dispatcher,
            ).use { session ->
                session.start(10, 3)
                session.requestRender(0)
                runCurrent()
                advanceTimeBy(TerminalSession.RENDER_PUBLICATION_INTERVAL_MS.milliseconds)
                runCurrent()
                val before = session.renderGeneration.value
                assertEquals(10 to 3, session.readPublishedFrame { it.columns to it.rows })

                val thrown =
                    assertThrows(IllegalStateException::class.java) {
                        when (operation) {
                            "resize" -> session.resize(8, 2)
                            "resizeViewport" -> session.resizeViewport(8, 2)
                            "tryResizeViewport" -> session.tryResizeViewport(8, 2)
                            else -> error("unknown resize operation: $operation")
                        }
                    }

                assertSame(failure, thrown)
                assertEquals(8, terminal.width)
                assertEquals(2, terminal.height)
                assertEquals(listOf(10 to 3, 8 to 2), recorded.resizeCalls)
                runCurrent()
                assertTrue(session.renderGeneration.value > before)
                assertEquals(8 to 2, session.readPublishedFrame { it.columns to it.rows })
                assertSame(TerminalSessionState.Running, session.state.value)
            }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `resize metadata failure preserves the exception and publishes the admitted core mutation`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val terminal = TerminalBuffers.create(10, 3)
            val connector = MockConnector()
            val failure = IllegalStateException("resize metadata read failed")
            var failNextMetadataRead = false
            val renderReader =
                object : TerminalRenderFrameReader by terminal {
                    override fun readRenderFrame(consumer: TerminalRenderFrameConsumer) {
                        if (failNextMetadataRead) {
                            failNextMetadataRead = false
                            throw failure
                        }
                        terminal.readRenderFrame(consumer)
                    }
                }
            TerminalSession(
                terminal,
                TerminalRenderPublisher(10, 3),
                renderReader,
                terminal,
                connector,
                TerminalParsers.create(HostCommandAdapter(terminal)),
                workerDispatcher = dispatcher,
                ioDispatcher = dispatcher,
            ).use { session ->
                session.start(10, 3)
                session.requestRender(0)
                runCurrent()
                advanceTimeBy(TerminalSession.RENDER_PUBLICATION_INTERVAL_MS.milliseconds)
                runCurrent()
                val before = session.renderGeneration.value
                assertEquals(10 to 3, session.readPublishedFrame { it.columns to it.rows })

                failNextMetadataRead = true
                assertSame(failure, assertThrows(IllegalStateException::class.java) { session.tryResizeViewport(8, 2) })

                assertEquals(8, terminal.width)
                assertEquals(2, terminal.height)
                assertEquals(listOf(10 to 3), connector.resizeCalls)
                runCurrent()
                assertTrue(session.renderGeneration.value > before)
                assertEquals(8 to 2, session.readPublishedFrame { it.columns to it.rows })
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `invalid resize dimensions do not mutate or request publication`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val terminal = TerminalBuffers.create(10, 3)
            val connector = MockConnector()
            TerminalSession
                .create(
                    terminal,
                    connector,
                    workerDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                ).use { session ->
                    session.start(10, 3)
                    session.requestRender(0)
                    runCurrent()
                    advanceTimeBy(TerminalSession.RENDER_PUBLICATION_INTERVAL_MS.milliseconds)
                    runCurrent()
                    val before = session.renderGeneration.value

                    for ((columns, rows) in listOf(0 to 1, 1 to 0, Int.MIN_VALUE to 1, 1 to Int.MIN_VALUE)) {
                        assertThrows(IllegalArgumentException::class.java) { session.tryResizeViewport(columns, rows) }
                    }
                    runCurrent()

                    assertEquals(10, terminal.width)
                    assertEquals(3, terminal.height)
                    assertEquals(listOf(10 to 3), connector.resizeCalls)
                    assertEquals(before, session.renderGeneration.value)
                    assertEquals(10 to 3, session.readPublishedFrame { it.columns to it.rows })
                }
        }

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
