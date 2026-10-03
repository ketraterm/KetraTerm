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
import io.github.ketraterm.core.api.TerminalRenderBuffer
import io.github.ketraterm.host.*
import io.github.ketraterm.input.api.TerminalInputEncoder
import io.github.ketraterm.input.event.*
import io.github.ketraterm.input.policy.TerminalInputPolicy
import io.github.ketraterm.parser.api.TerminalOutputParser
import io.github.ketraterm.parser.api.TerminalParsers
import io.github.ketraterm.protocol.keyboard.KittyKeyboardProgressiveFlag
import io.github.ketraterm.render.api.*
import io.github.ketraterm.render.cache.TerminalRenderPublisher
import io.github.ketraterm.render.cache.TerminalRenderRangeCopy
import io.github.ketraterm.testkit.MockConnector
import io.github.ketraterm.transport.TerminalConnector
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalSessionTest {
    @ParameterizedTest
    @ValueSource(strings = ["start", "close"])
    fun `concurrent startup and lifecycle operations remain serialized`(operation: String) =
        runTest {
            val recorded = MockConnector()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val connector =
                object : TerminalConnector by recorded {
                    override fun start(listener: io.github.ketraterm.transport.TerminalConnectorListener) {
                        recorded.start(listener)
                        entered.countDown()
                        check(release.await(SESSION_THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "Startup was not released" }
                    }
                }
            val dispatcher = StandardTestDispatcher(testScheduler)
            TerminalSession
                .create(
                    TerminalBuffers.create(10, 3),
                    connector,
                    workerDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                ).use { session ->
                    SessionTestThread("session-start") { session.start(10, 3) }.use { starter ->
                        try {
                            assertTrue(entered.await(SESSION_THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                            assertSame(TerminalSessionState.Created, session.state.value)
                            session.encodeKey(TerminalKeyEvent.codepoint('x'.code))
                            session.encodePaste(TerminalPasteEvent("x"))
                            session.encodeTextReplacement(TerminalTextReplacementEvent(0, 0, "x"))
                            SessionTestThread("session-$operation") {
                                if (operation == "start") {
                                    assertThrows(IllegalStateException::class.java) { session.start(10, 3) }
                                } else {
                                    session.close()
                                }
                            }.use { contender ->
                                try {
                                    contender.awaitBlockedBy(starter)
                                    assertEquals(0, recorded.closeCount)
                                    if (operation == "close") assertTrue(session.isClosed)
                                } finally {
                                    release.countDown()
                                }
                                starter.awaitCompletion()
                                contender.awaitCompletion()
                            }
                        } finally {
                            release.countDown()
                        }
                    }
                    runCurrent()
                    assertEquals(1, recorded.startCount)
                    assertEquals("", recorded.writtenBytes.asciiText())
                    if (operation == "start") {
                        assertSame(TerminalSessionState.Running, session.state.value)
                    } else {
                        assertTrue((session.state.value as TerminalSessionState.Closed).event.locallyRequested)
                        assertEquals(1, recorded.closeCount)
                        assertFalse(session.isCoroutineScopeActive)
                    }
                }
        }

    @ParameterizedTest
    @ValueSource(strings = ["close", "remote", "error", "throw"])
    fun `termination during connector startup never publishes running or writes queued replies`(termination: String) =
        runTest {
            val recorded = MockConnector()
            val failure = IllegalStateException("startup failed")
            var runningObserved = false
            lateinit var session: TerminalSession
            val connector =
                object : TerminalConnector by recorded {
                    override fun start(listener: io.github.ketraterm.transport.TerminalConnectorListener) {
                        recorded.start(listener)
                        recorded.feedFromHost("\u001B[5n".ascii())
                        when (termination) {
                            "close" -> session.close()
                            "remote" -> listener.onClosed(7)
                            "error" -> listener.onError(failure)
                            "throw" -> throw failure
                        }
                    }
                }
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            session =
                TerminalSession.create(
                    TerminalBuffers.create(10, 3),
                    connector,
                    workerDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                )
            session.use {
                backgroundScope.launch(dispatcher) {
                    session.state.first { it === TerminalSessionState.Running }
                    runningObserved = true
                }
                if (termination == "throw") {
                    assertSame(failure, assertThrows(IllegalStateException::class.java) { session.start(10, 3) })
                } else {
                    session.start(10, 3)
                }
                runCurrent()
                assertFalse(runningObserved)
                val event = (session.state.value as TerminalSessionState.Closed).event
                assertEquals(termination == "close", event.locallyRequested)
                assertEquals(if (termination == "remote") 7 else null, event.exitCode)
                assertSame(if (termination == "error" || termination == "throw") failure else null, event.failure)
                assertEquals("", recorded.writtenBytes.asciiText())
                assertEquals(1, recorded.closeCount)
                assertFalse(session.isCoroutineScopeActive)
                assertThrows(IllegalStateException::class.java) { session.start(10, 3) }
                assertEquals(1, recorded.startCount)
            }
        }

    @ParameterizedTest
    @ValueSource(strings = ["resize", "start", "running"])
    fun `reentrant start is rejected throughout startup`(stage: String) =
        runTest {
            val recorded = MockConnector()
            var rejected = false
            lateinit var session: TerminalSession

            fun attemptAgain() {
                assertThrows(IllegalStateException::class.java) { session.start(10, 3) }
                rejected = true
            }
            val connector =
                object : TerminalConnector by recorded {
                    override fun resize(
                        columns: Int,
                        rows: Int,
                    ) {
                        recorded.resize(columns, rows)
                        if (stage == "resize") attemptAgain()
                    }

                    override fun start(listener: io.github.ketraterm.transport.TerminalConnectorListener) {
                        recorded.start(listener)
                        if (stage == "start") attemptAgain()
                    }
                }
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            session =
                TerminalSession.create(
                    TerminalBuffers.create(10, 3),
                    connector,
                    workerDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                )
            session.use {
                if (stage == "running") {
                    backgroundScope.launch(dispatcher) {
                        session.state.first { it === TerminalSessionState.Running }
                        attemptAgain()
                    }
                }
                session.start(10, 3)
                assertTrue(rejected)
                assertEquals(1, recorded.startCount)
                assertSame(TerminalSessionState.Running, session.state.value)
            }
        }

    @Test
    fun `startup output replies wait for connector start to return and precede observer input`() =
        runTest {
            val recorded = MockConnector()
            var starting = true
            val earlyWrites = mutableListOf<ByteArray>()
            lateinit var session: TerminalSession
            val connector =
                object : TerminalConnector by recorded {
                    override fun start(listener: io.github.ketraterm.transport.TerminalConnectorListener) {
                        recorded.start(listener)
                        recorded.feedFromHost("\u001B[5n".ascii())
                        session.encodeKey(TerminalKeyEvent.codepoint('x'.code))
                        session.encodePaste(TerminalPasteEvent("x"))
                        session.encodeTextReplacement(TerminalTextReplacementEvent(0, 0, "x"))
                        starting = false
                    }

                    override fun write(
                        bytes: ByteArray,
                        offset: Int,
                        length: Int,
                    ) {
                        if (starting) earlyWrites += bytes.copyOfRange(offset, offset + length)
                        recorded.write(bytes, offset, length)
                    }
                }
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            session =
                TerminalSession.create(
                    TerminalBuffers.create(10, 3),
                    connector,
                    workerDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                )
            session.use {
                backgroundScope.launch(dispatcher) {
                    session.state.first { it === TerminalSessionState.Running }
                    session.encodePaste(TerminalPasteEvent("y"))
                }
                session.start(10, 3)
                runCurrent()
                assertAll(
                    { assertTrue(earlyWrites.isEmpty(), "Startup replies must wait for transport readiness") },
                    { assertEquals("\u001B[0ny", recorded.writtenBytes.asciiText()) },
                    { assertSame(TerminalSessionState.Running, session.state.value) },
                )
            }
        }

    @ParameterizedTest
    @ValueSource(strings = ["key", "paste", "replacement"])
    fun `input from a running state observer cannot reach an unstarted connector`(input: String) =
        runTest {
            val recorded = MockConnector()
            var transportStarted = false
            var runningObservedAfterTransportStart: Boolean? = null
            val writesBeforeTransportStart = mutableListOf<ByteArray>()
            val connector =
                object : TerminalConnector by recorded {
                    override fun start(listener: io.github.ketraterm.transport.TerminalConnectorListener) {
                        transportStarted = true
                        recorded.start(listener)
                    }

                    override fun write(
                        bytes: ByteArray,
                        offset: Int,
                        length: Int,
                    ) {
                        if (!transportStarted) writesBeforeTransportStart += bytes.copyOfRange(offset, offset + length)
                        recorded.write(bytes, offset, length)
                    }
                }
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            TerminalSession
                .create(
                    terminal = TerminalBuffers.create(10, 3),
                    connector = connector,
                    workerDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                ).use { session ->
                    backgroundScope.launch(dispatcher) {
                        session.state.first { it === TerminalSessionState.Running }
                        runningObservedAfterTransportStart = transportStarted
                        when (input) {
                            "key" -> session.encodeKey(TerminalKeyEvent.codepoint('x'.code))
                            "paste" -> session.encodePaste(TerminalPasteEvent("x"))
                            "replacement" -> session.encodeTextReplacement(TerminalTextReplacementEvent(0, 0, "x"))
                        }
                    }

                    session.start(10, 3)
                    runCurrent()

                    session.encodeKey(TerminalKeyEvent.codepoint('y'.code))
                    runCurrent()
                    assertAll(
                        { assertEquals(true, runningObservedAfterTransportStart, "Running must mean the connector has started") },
                        { assertTrue(writesBeforeTransportStart.isEmpty(), "No native write may precede connector startup") },
                        { assertFalse(session.isClosed, "Input accepted in Running must not fail an otherwise healthy session") },
                        { assertEquals(1, recorded.startCount) },
                        {
                            assertEquals(
                                "xy",
                                recorded.writtenBytes.decodeToString(),
                                "Running and later input must both be accepted in order",
                            )
                        },
                    )
                }
        }

    @ParameterizedTest
    @ValueSource(strings = ["running", "resize"])
    fun `reentrant shutdown during startup cannot start a disposed connector`(stage: String) =
        runTest {
            val recorded = MockConnector()
            var starts = 0
            lateinit var session: TerminalSession
            val connector =
                object : TerminalConnector by recorded {
                    override fun resize(
                        columns: Int,
                        rows: Int,
                    ) {
                        recorded.resize(columns, rows)
                        if (stage == "resize") session.close()
                    }

                    override fun start(listener: io.github.ketraterm.transport.TerminalConnectorListener) {
                        assertEquals(0, recorded.closeCount, "A disposed connector must never be started")
                        starts++
                        recorded.start(listener)
                    }
                }
            session =
                TerminalSession.create(
                    terminal = TerminalBuffers.create(10, 3),
                    connector = connector,
                    workerDispatcher = StandardTestDispatcher(testScheduler),
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )
            if (stage == "running") {
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    session.state.first { it === TerminalSessionState.Running }
                    session.close()
                }
            }
            session.use {
                session.start(10, 3)
                assertEquals(if (stage == "running") 1 else 0, starts)
                assertEquals(1, recorded.closeCount)
                assertTrue((session.state.value as TerminalSessionState.Closed).event.locallyRequested)
                assertFalse(session.isCoroutineScopeActive)
            }
        }

    @Test
    fun `close waits for an in flight publication then retains the EOF frame`() {
        val copied = CountDownLatch(1)
        val release = CountDownLatch(1)
        val transportClosed = CountDownLatch(1)
        val terminal = TerminalBuffers.create(10, 3)
        val source = terminal as TerminalRenderFrameReader
        val reads = AtomicInteger()
        val renderReader =
            object : TerminalRenderFrameReader by source {
                override fun readRenderFrame(
                    scrollbackOffset: Int,
                    consumer: TerminalRenderFrameConsumer,
                ) {
                    readRenderFrame(scrollbackOffset, terminal.height, consumer)
                }

                override fun readRenderFrame(
                    scrollbackOffset: Int,
                    viewportRows: Int,
                    consumer: TerminalRenderFrameConsumer,
                ) {
                    source.readRenderFrame(scrollbackOffset, viewportRows, consumer)
                    if (reads.incrementAndGet() == 1) {
                        copied.countDown()
                        release.await()
                    }
                }
            }
        val recorded = MockConnector()
        val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val session =
            TerminalSession(
                terminal = terminal,
                renderPublisher = TerminalRenderPublisher(10, 3),
                renderReader = renderReader,
                responseReader = terminal,
                connector =
                    object : TerminalConnector by recorded {
                        override fun close() {
                            recorded.close()
                            transportClosed.countDown()
                        }
                    },
                parser = TerminalParsers.create(HostCommandAdapter(terminal)),
                workerDispatcher = worker,
                ioDispatcher = worker,
            )
        try {
            session.start(10, 3)
            recorded.feedFromHost(byteArrayOf('A'.code.toByte(), 0xE2.toByte(), 0x82.toByte()))
            assertTrue(copied.await(10, TimeUnit.SECONDS), "publication did not copy the pre-EOF frame")
            val closing = CompletableFuture.runAsync(session::close)
            try {
                assertTrue(transportClosed.await(10, TimeUnit.SECONDS), "close did not reach transport disposal")
                assertFalse(session.state.value is TerminalSessionState.Closed)
            } finally {
                release.countDown()
                closing.get(10, TimeUnit.SECONDS)
            }
            assertArrayEquals(
                intArrayOf('A'.code, 0xFFFD),
                session.readPublishedFrame { it.codeWords.copyOf(2) },
            )
            assertTrue(session.state.value is TerminalSessionState.Closed)
            assertFalse(session.isCoroutineScopeActive)
        } finally {
            release.countDown()
            session.close()
            worker.close()
        }
    }

    @Test
    fun `final publication preserves an active reader lease`() =
        runTest {
            val connector = MockConnector()
            val session =
                TerminalSession.create(
                    terminal = TerminalBuffers.create(10, 3),
                    connector = connector,
                    workerDispatcher = StandardTestDispatcher(testScheduler),
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )
            val leased = CountDownLatch(1)
            val release = CountDownLatch(1)
            session.use {
                session.start(10, 3)
                connector.feedFromHost("OLD".ascii())
                runCurrent()
                val reader =
                    CompletableFuture.runAsync {
                        session.readPublishedFrame { frame ->
                            assertEquals('O'.code, frame.codeWords[0])
                            leased.countDown()
                            release.await()
                            assertEquals('O'.code, frame.codeWords[0])
                        }
                    }
                try {
                    assertTrue(leased.await(10, TimeUnit.SECONDS))
                    connector.feedFromHost("\rFINAL".ascii())
                    session.close()
                    assertEquals(
                        'F'.code,
                        session.readPublishedFrame { it.codeWords[0] },
                    )
                    assertTrue(session.state.value is TerminalSessionState.Closed)
                } finally {
                    release.countDown()
                    reader.get(10, TimeUnit.SECONDS)
                }
            }
        }

    @Test
    fun `cleanup failures cannot skip EOF publication or scope cancellation`() =
        runTest {
            val connectorFailure = IllegalStateException("connector close failed")
            val eofFailure = IllegalStateException("EOF failed")
            val recorded = MockConnector()
            val terminal = TerminalBuffers.create(10, 3)
            terminal.writeText("FINAL")
            val session =
                TerminalSession(
                    terminal = terminal,
                    renderPublisher = TerminalRenderPublisher(10, 3),
                    renderReader = terminal as TerminalRenderFrameReader,
                    responseReader = terminal,
                    connector =
                        object : TerminalConnector by recorded {
                            override fun close() {
                                recorded.close()
                                throw connectorFailure
                            }
                        },
                    parser =
                        object : TerminalOutputParser by RecordingParser() {
                            override fun endOfInput(): Unit = throw eofFailure
                        },
                    workerDispatcher = StandardTestDispatcher(testScheduler),
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )
            assertSame(connectorFailure, assertThrows(IllegalStateException::class.java) { session.close() })
            assertTrue(eofFailure in connectorFailure.suppressed)
            assertEquals(
                'F'.code,
                session.readPublishedFrame { it.codeWords[0] },
            )
            assertTrue(session.isClosed)
            assertFalse(session.isCoroutineScopeActive)
            session.close()
            assertEquals(1, recorded.closeCount)
        }

    @ParameterizedTest
    @ValueSource(strings = ["resize", "start"])
    fun `startup failure closes the connector and retains its cause`(stage: String) =
        runTest {
            val failure = IllegalStateException("startup $stage failed")
            val recorded = MockConnector()
            val connector =
                object : TerminalConnector by recorded {
                    override fun resize(
                        columns: Int,
                        rows: Int,
                    ) {
                        if (stage == "resize") throw failure
                        recorded.resize(columns, rows)
                    }

                    override fun start(listener: io.github.ketraterm.transport.TerminalConnectorListener): Unit = throw failure
                }
            val session =
                TerminalSession.create(
                    terminal = TerminalBuffers.create(10, 3),
                    connector = connector,
                    workerDispatcher = StandardTestDispatcher(testScheduler),
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )
            session.use {
                assertSame(failure, assertThrows(IllegalStateException::class.java) { session.start(10, 3) })
                assertTrue(session.isClosed)
                assertSame(failure, session.failure)
                assertEquals(1, recorded.closeCount)
                assertFalse(session.isCoroutineScopeActive)
            }
            assertEquals(1, recorded.closeCount)
        }

    @Test
    fun `closed observers see the final frame and released transport immediately`() =
        runTest {
            val connector = MockConnector()
            val session =
                TerminalSession.create(
                    terminal = TerminalBuffers.create(10, 3),
                    connector = connector,
                    workerDispatcher = StandardTestDispatcher(testScheduler),
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )
            session.use {
                session.start(10, 3)
                session.requestRender(0)
                runCurrent()
                val observed = mutableListOf<Pair<Int?, Int>>()
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    session.state.first { it is TerminalSessionState.Closed }
                    observed += session.readPublishedFrame { it.codeWords[0] } to connector.closeCount
                }
                connector.feedFromHost("LAST".ascii())
                connector.simulateClosed(7)
                assertEquals(listOf('L'.code to 1), observed)
                assertEquals(session.state.value, session.state.first())
                assertEquals(
                    'L'.code,
                    session.readPublishedFrame { it.codeWords[0] },
                )
            }
        }

    @Test
    fun `xterm resource queries and negotiated key bytes share the session output`() {
        val connector = MockConnector()
        createStartedSession(connector).use { session ->
            connector.feedFromHost("\u001B[>1;3m\u001B[?1m".ascii())
            session.encodeKey(TerminalKeyEvent.key(TerminalKey.UP, TerminalModifiers.CTRL))
            connector.feedFromHost("\u001B[>1;4m\u001B[>1;1f\u001B[?1g".ascii())
            session.encodeKey(TerminalKeyEvent.key(TerminalKey.UP))
            connector.feedFromHost("\u001B[>1u".ascii())
            session.encodeKey(TerminalKeyEvent.key(TerminalKey.UP, TerminalModifiers.CTRL))
            connector.feedFromHost("\u001B[<u\u001B[>m\u001B[>f\u001B[?1m\u001B[?1g".ascii())
            session.encodeKey(TerminalKeyEvent.key(TerminalKey.UP, TerminalModifiers.CTRL))
            assertArrayEquals(
                (
                    "\u001B[>1;3m\u001B[>1;5A\u001B[>1;1f\u001B[57938;1u\u001B[1;5A" +
                        "\u001B[>1;2m\u001B[>1;0f\u001B[1;5A"
                ).ascii(),
                connector.writtenBytes,
            )
        }
    }

    @Test
    fun `large clipboard writes follow session permissions through real session parsing`() =
        runTest {
            val text = "é🙂".repeat(1024)
            val bytes = ("\u001B]52;c;" + Base64.getEncoder().encodeToString(text.encodeToByteArray()) + "\u001B\\").ascii()
            for (permission in TerminalClipboardPermission.entries) {
                val connector = MockConnector()
                val events = RecordingHostEvents()
                val writeAllowed =
                    permission == TerminalClipboardPermission.ALLOW
                val policy =
                    TerminalClipboardPolicy(
                        writePermission = permission,
                    )
                TerminalSession
                    .create(
                        TerminalBuffers.create(10, 3),
                        connector,
                        events,
                        HostPolicy(clipboardPolicy = policy),
                        workerDispatcher = StandardTestDispatcher(testScheduler),
                        ioDispatcher = UnconfinedTestDispatcher(),
                    ).use { session ->
                        session.start(10, 3)
                        for (offset in bytes.indices step 511) {
                            connector.feedFromHost(bytes, offset, minOf(511, bytes.size - offset))
                        }
                        when {
                            permission == TerminalClipboardPermission.PROMPT -> {
                                assertEquals(text, events.clipboardPrompts.single().text)
                                assertTrue(events.clipboardWrites.isEmpty())
                                assertEquals(
                                    TerminalClipboardDecision.PROMPT_REQUIRED,
                                    events.clipboardAudits.single().decision,
                                )
                            }
                            writeAllowed -> {
                                assertEquals(text, events.clipboardWrites.single().text)
                                assertTrue(events.clipboardPrompts.isEmpty())
                                assertEquals(
                                    TerminalClipboardDecision.ALLOWED_BY_POLICY,
                                    events.clipboardAudits.single().decision,
                                )
                            }
                            else -> {
                                assertTrue(events.clipboardAudits.isEmpty())
                                assertTrue(events.clipboardWrites.isEmpty())
                                assertTrue(events.clipboardPrompts.isEmpty())
                            }
                        }
                        assertTrue(connector.writtenBytes.isEmpty())
                    }
            }
        }

    @Test
    fun `clipboard decoded boundary is enforced before callbacks and malformed large transfers recover`() =
        runTest {
            val connector = MockConnector()
            val events = RecordingHostEvents()
            val policy =
                HostPolicy(
                    clipboardPolicy =
                        TerminalClipboardPolicy(
                            writePermission = TerminalClipboardPermission.ALLOW,
                            maxDecodedBytes = 8192,
                        ),
                )
            TerminalSession
                .create(
                    TerminalBuffers.create(10, 3),
                    connector,
                    events,
                    policy,
                    workerDispatcher = StandardTestDispatcher(testScheduler),
                    ioDispatcher = UnconfinedTestDispatcher(),
                ).use { session ->
                    session.start(10, 3)
                    for (count in intArrayOf(8191, 8192, 8193, 8194)) {
                        val encoded = Base64.getEncoder().encodeToString(ByteArray(count) { 'a'.code.toByte() })
                        connector.feedFromHost("\u001B]52;c;$encoded\u0007".ascii())
                    }
                    assertEquals(listOf("a".repeat(8191), "a".repeat(8192)), events.clipboardWrites.map { it.text })
                    // 8193 shares a Base64-size bucket with 8192; 8194 exceeds the parser's encoded budget.
                    assertEquals(
                        listOf(
                            TerminalClipboardDecision.ALLOWED_BY_POLICY,
                            TerminalClipboardDecision.ALLOWED_BY_POLICY,
                            TerminalClipboardDecision.DENIED_PAYLOAD_TOO_LARGE,
                        ),
                        events.clipboardAudits.map { it.decision },
                    )
                    val invalidUtf8 = ByteArray(6000) { 'a'.code.toByte() }.also { it[it.lastIndex] = 0xFF.toByte() }
                    for (encoded in listOf(Base64.getEncoder().encodeToString(invalidUtf8), "YWFh".repeat(1500) + "!")) {
                        connector.feedFromHost("\u001B]52;c;$encoded\u0007".ascii())
                        assertEquals(TerminalClipboardDecision.DENIED_MALFORMED_PAYLOAD, events.clipboardAudits.last().decision)
                    }
                    assertEquals(2, events.clipboardWrites.size)
                    connector.feedFromHost("\u001B]52;c;Yg\u0007\u001B]52;c;\u0007".ascii())
                    assertEquals(listOf("b", ""), events.clipboardWrites.takeLast(2).map { it.text })
                    assertTrue(events.clipboardPrompts.isEmpty())
                }
        }

    @Test
    fun `inflight clipboard writes recheck changed permissions and decoded limits`() =
        runTest {
            val original =
                HostPolicy(
                    clipboardPolicy =
                        TerminalClipboardPolicy(
                            writePermission = TerminalClipboardPermission.ALLOW,
                            maxDecodedBytes = 16384,
                        ),
                )
            val denied = original.clipboardPolicy.copy(writePermission = TerminalClipboardPermission.DENY)
            val changes =
                listOf(
                    original.copy(clipboardPolicy = denied) to
                        TerminalClipboardDecision.DENIED_BY_POLICY,
                    original.copy(clipboardPolicy = original.clipboardPolicy.copy(maxDecodedBytes = 4096)) to
                        TerminalClipboardDecision.DENIED_PAYLOAD_TOO_LARGE,
                )
            for ((updated, expected) in changes) {
                val connector = MockConnector()
                val events = RecordingHostEvents()
                TerminalSession
                    .create(
                        TerminalBuffers.create(10, 3),
                        connector,
                        events,
                        original,
                        workerDispatcher = StandardTestDispatcher(testScheduler),
                        ioDispatcher = UnconfinedTestDispatcher(),
                    ).use { session ->
                        session.start(10, 3)
                        connector.feedFromHost(("\u001B]52;c;" + "YWFh".repeat(2048)).ascii())
                        session.setHostPolicy(updated)
                        connector.feedFromHost("\u0007".ascii())
                        assertEquals(expected, events.clipboardAudits.single().decision)
                        assertTrue(events.clipboardWrites.isEmpty())
                        assertTrue(events.clipboardPrompts.isEmpty())
                        session.setHostPolicy(original)
                        connector.feedFromHost(("\u001B]52;c;" + "YWFh".repeat(2048) + "\u0007").ascii())
                        assertEquals("aaa".repeat(2048), events.clipboardWrites.single().text)
                    }
            }
        }

    @Test
    fun `default clipboard budget accepts one MiB and session shutdown discards unfinished large writes`() =
        runTest {
            for (shutdown in listOf("local", "remote", "error")) {
                val connector = MockConnector()
                val events = RecordingHostEvents()
                val text = "a".repeat(1024 * 1024)
                val bytes = ("\u001B]52;c;" + Base64.getEncoder().encodeToString(text.ascii()) + "\u001B\\").ascii()
                TerminalSession
                    .create(
                        TerminalBuffers.create(10, 3),
                        connector,
                        events,
                        HostPolicy(clipboardPolicy = TerminalClipboardPolicy(writePermission = TerminalClipboardPermission.ALLOW)),
                        workerDispatcher = StandardTestDispatcher(testScheduler),
                        ioDispatcher = UnconfinedTestDispatcher(),
                    ).use { session ->
                        session.start(10, 3)
                        for (offset in bytes.indices step 8191) {
                            connector.feedFromHost(bytes, offset, minOf(8191, bytes.size - offset))
                        }
                        assertEquals(text, events.clipboardWrites.single().text)
                        connector.feedFromHost(bytes, 0, bytes.size - 2)
                        when (shutdown) {
                            "local" -> session.close()
                            "remote" -> connector.simulateClosed(0)
                            else -> connector.simulateCrash(IllegalStateException("transport failed"))
                        }
                        assertEquals(1, events.clipboardWrites.size)
                        assertEquals(1, events.clipboardAudits.size)
                        assertTrue(session.isClosed)
                    }
            }
        }

    @Test
    fun `published cursor presentation follows application style and restores host defaults`() =
        runTest {
            val connector = MockConnector()
            TerminalSession
                .create(
                    terminal = TerminalBuffers.create(10, 3),
                    connector = connector,
                    workerDispatcher = StandardTestDispatcher(testScheduler),
                    ioDispatcher = UnconfinedTestDispatcher(),
                ).use { session ->
                    session.setCursorShape(TerminalRenderCursorShape.BAR)
                    session.start(10, 3)
                    connector.feedFromHost("\u001B[?1049h\u001B[2 q".ascii())
                    session.requestRender(0)
                    runCurrent()
                    requireNotNull(
                        session.readPublishedFrame { alternate ->
                            assertEquals(TerminalRenderCursorShape.BLOCK, alternate.cursorShape)
                            assertFalse(alternate.cursorBlinking)
                        },
                    )
                    val generation = session.renderGeneration.value

                    connector.feedFromHost("\u001B[?1049l".ascii())
                    session.requestRender(0)
                    advanceTimeBy(TerminalSession.RENDER_PUBLICATION_INTERVAL_MS.milliseconds)
                    runCurrent()
                    assertTrue(session.renderGeneration.value > generation)
                    requireNotNull(
                        session.readPublishedFrame { primary ->
                            assertEquals(TerminalRenderCursorShape.BAR, primary.cursorShape)
                            assertTrue(primary.cursorBlinking)
                        },
                    )

                    connector.feedFromHost("\u001B[2 q\u001B[0 q".ascii())
                    session.requestRender(0)
                    advanceTimeBy(TerminalSession.RENDER_PUBLICATION_INTERVAL_MS.milliseconds)
                    runCurrent()
                    requireNotNull(
                        session.readPublishedFrame { reset ->
                            assertEquals(TerminalRenderCursorShape.BAR, reset.cursorShape)
                            assertTrue(reset.cursorBlinking)
                        },
                    )
                }
        }

    @Test
    fun `DECCOLM synchronizes connector and core before notifying hosts and following output`() {
        val stream = "old\u001B[?3hwide\u001B[18t\u001B[?3lnarrow\u001B[18t"
        for (split in 0..stream.length) {
            val connector = MockConnector()
            val terminal = TerminalBuffers.create(80, 3)
            val requests = mutableListOf<Pair<Int, Int>>()
            val events =
                object : HostEventSink by HostEventSink.NONE {
                    override fun columnModeChanged(
                        rows: Int,
                        columns: Int,
                    ) {
                        assertEquals(columns to rows, connector.resizeCalls.last())
                        assertEquals(columns, terminal.width)
                        assertEquals("", terminal.getLineAsString(0))
                        requests += columns to rows
                    }
                }
            val session = TerminalSession.create(terminal, connector, hostEvents = events, ioDispatcher = UnconfinedTestDispatcher())
            session.start(80, 3)
            try {
                connector.feedFromHost(stream.take(split).ascii())
                connector.feedFromHost(stream.drop(split).ascii())
                assertEquals(listOf(132 to 3, 80 to 3), requests)
                assertEquals(listOf(80 to 3, 132 to 3, 80 to 3), connector.resizeCalls)
                assertEquals("\u001B[8;3;132t\u001B[8;3;80t", connector.writtenBytes.asciiText())
                assertEquals(80, terminal.width)
                assertEquals("narrow", terminal.getLineAsString(0))
            } finally {
                session.close()
            }
        }
    }

    @Test
    fun `DECCOLM without a host resizes and wraps independently of window policy`() {
        val stream = "\u001B[?3h\u001B[18t\u001B[?3l" + "x".repeat(80) + "Y\u001B[6n\u001B[18t"
        for (policy in HostControlPolicy.entries) {
            for (split in 0..stream.length) {
                val connector = MockConnector()
                val terminal = TerminalBuffers.create(width = 90, height = 3)
                val session =
                    createStartedSession(
                        connector,
                        columns = 90,
                        rows = 3,
                        hostPolicy = HostPolicy(windowManipulationPolicy = policy),
                        terminal = terminal,
                    )
                try {
                    connector.feedFromHost(stream.take(split).ascii())
                    connector.feedFromHost(stream.drop(split).ascii())
                    assertEquals(listOf(90 to 3, 132 to 3, 80 to 3), connector.resizeCalls)
                    assertEquals(80, terminal.width)
                    assertEquals("x".repeat(80), terminal.getLineAsString(0))
                    assertEquals("Y", terminal.getLineAsString(1))
                    assertEquals("\u001B[8;3;132t\u001B[2;2R\u001B[8;3;80t", connector.writtenBytes.asciiText())
                } finally {
                    session.close()
                }
            }
        }
    }

    @Test
    fun `connector resize failure does not apply destructive DECCOLM reset`() {
        val delegate = MockConnector()
        val failure = IllegalStateException("resize failed")
        val connector =
            object : TerminalConnector by delegate {
                override fun resize(
                    columns: Int,
                    rows: Int,
                ) {
                    if (columns == 132) throw failure
                    delegate.resize(columns, rows)
                }
            }
        val terminal = TerminalBuffers.create(width = 80, height = 3)
        val events =
            object : HostEventSink by HostEventSink.NONE {
                override fun columnModeChanged(
                    rows: Int,
                    columns: Int,
                ) = error("failed connector resize must not notify the host")
            }
        val session = TerminalSession.create(terminal, connector, hostEvents = events, ioDispatcher = UnconfinedTestDispatcher())
        try {
            session.start(80, 3)
            delegate.feedFromHost("keep".ascii())
            assertSame(failure, assertThrows(IllegalStateException::class.java) { delegate.feedFromHost("\u001B[?3h".ascii()) })
            assertEquals(80, terminal.width)
            assertEquals("keep", terminal.getLineAsString(0))
        } finally {
            session.close()
        }
    }

    @Test
    fun `color scheme replies track live host theme and policy updates through transport`() {
        val connector = MockConnector()
        val session = createStartedSession(connector)
        try {
            connector.feedFromHost("\u001B[?996n".ascii())
            session.setThemePalette(
                TerminalColorPalette(defaultForeground = 0xff000000.toInt(), defaultBackground = 0xffffffff.toInt(), isDark = false),
            )
            assertEquals("\u001B[?997;1n", connector.writtenBytes.asciiText())
            connector.feedFromHost("\u001B]11;#000000\u0007\u001B[?996n".ascii())
            assertEquals("\u001B[?997;1n\u001B[?997;2n", connector.writtenBytes.asciiText())

            session.setHostPolicy(HostPolicy(terminalResponsePolicy = HostControlPolicy.DENY))
            connector.feedFromHost("\u001B[?996n".ascii())
            session.setThemePalette(TerminalColorPalette())
            session.setHostPolicy(HostPolicy())
            connector.feedFromHost("\u001B[?996n\u001B[5n".ascii())
            assertEquals("\u001B[?997;1n\u001B[?997;2n\u001B[?997;1n\u001B[0n", connector.writtenBytes.asciiText())
        } finally {
            session.close()
        }
    }

    @Test
    fun `DSR CSI 5 n replies OK status`() {
        val connector = MockConnector()
        val session = createStartedSession(connector)

        connector.feedFromHost("\u001B[5n".ascii())

        assertEquals("\u001B[0n", connector.writtenBytes.asciiText())
        session.close()
    }

    @Test
    fun `rich host Kitty capability reaches parser host core response pipeline`() {
        val connector = MockConnector()
        val terminal = TerminalBuffers.create(width = 10, height = 3)
        val session =
            TerminalSession.create(
                terminal = terminal,
                connector = connector,
                kittyKeyboardSupportedFlags = KittyKeyboardProgressiveFlag.ENCODER_SUPPORTED_MASK,
                ioDispatcher = UnconfinedTestDispatcher(),
            )
        session.start(columns = 10, rows = 3)

        connector.feedFromHost("\u001B[=31u\u001B[?u".ascii())

        assertEquals("\u001B[?31u", connector.writtenBytes.asciiText())
        session.close()
    }

    @Test
    fun `CPR CSI 6 n replies one-based cursor position`() {
        val connector = MockConnector()
        val session = createStartedSession(connector)

        connector.feedFromHost("\u001B[2;3H\u001B[6n".ascii())

        assertEquals("\u001B[2;3R", connector.writtenBytes.asciiText())
        session.close()
    }

    @Test
    fun `close does not set exitCode to zero`() {
        val connector = MockConnector()
        val session = createStartedSession(connector)

        session.close()

        assertNull(session.exitCode)
        assertEquals(1, connector.closeCount)
    }

    @Test
    fun `lifecycle state is retained from creation through closure`() =
        runTest {
            val connector = MockConnector()
            val terminal = TerminalBuffers.create(width = 10, height = 3)
            val session =
                TerminalSession.create(
                    terminal = terminal,
                    connector = connector,
                    workerDispatcher = StandardTestDispatcher(testScheduler),
                    ioDispatcher = UnconfinedTestDispatcher(),
                )

            assertSame(TerminalSessionState.Created, session.state.value)

            session.start(columns = 10, rows = 3)
            assertSame(TerminalSessionState.Running, session.state.value)

            session.close()
            val closed = session.state.value as TerminalSessionState.Closed
            assertTrue(closed.event.locallyRequested)
            assertEquals(closed, session.state.first())
            assertFalse(session.isCoroutineScopeActive)
        }

    @ParameterizedTest
    @ValueSource(strings = ["local", "remote", "error"])
    fun `cleanup disposes transport once and preserves the first termination event`(termination: String) =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val connector = MockConnector()
            val failure = IllegalStateException("transport failed")
            TerminalSession
                .create(
                    terminal = TerminalBuffers.create(10, 3),
                    connector = connector,
                    workerDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                ).use { session ->
                    session.start(10, 3)
                    when (termination) {
                        "local" -> session.close()
                        "remote" -> connector.simulateClosed(7)
                        "error" -> connector.simulateCrash(failure)
                        else -> error("unknown termination: $termination")
                    }
                    val expected =
                        TerminalSessionState.Closed(
                            TerminalSessionCloseEvent(
                                exitCode = if (termination == "remote") 7 else null,
                                failure = if (termination == "error") failure else null,
                                locallyRequested = termination == "local",
                            ),
                        )
                    assertEquals(expected, session.state.value)

                    session.close()
                    session.close()
                    connector.simulateClosed(19)
                    connector.simulateCrash(IllegalStateException("later failure"))
                    runCurrent()

                    assertAll(
                        { assertEquals(1, connector.closeCount) },
                        { assertTrue(connector.isClosed) },
                        { assertEquals(expected, session.state.value) },
                        { assertFalse(session.isCoroutineScopeActive) },
                    )
                }
        }

    @Test
    fun `connector closure callback cannot recursively dispose transport or replace the first event`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val recorded = MockConnector()
            lateinit var session: TerminalSession
            val connector =
                object : TerminalConnector by recorded {
                    override fun close() {
                        recorded.close()
                        session.onClosed(19)
                    }
                }
            session =
                TerminalSession.create(
                    terminal = TerminalBuffers.create(10, 3),
                    connector = connector,
                    workerDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                )
            session.use {
                session.start(10, 3)
                recorded.simulateClosed(7)
                session.close()
                session.close()
                runCurrent()
                assertAll(
                    { assertEquals(1, recorded.closeCount) },
                    {
                        assertEquals(
                            TerminalSessionState.Closed(
                                TerminalSessionCloseEvent(exitCode = 7, failure = null, locallyRequested = false),
                            ),
                            session.state.value,
                        )
                    },
                )
            }
        }

    @ParameterizedTest
    @CsvSource("local,false", "remote,false", "error,false", "local,true", "remote,true", "error,true")
    fun `termination publishes final UTF8 cells when publication is pending`(
        termination: String,
        synchronizedOutput: Boolean,
    ) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val connector = MockConnector()
        val terminal = TerminalBuffers.create(10, 3)
        TerminalSession
            .create(
                terminal = terminal,
                connector = connector,
                workerDispatcher = dispatcher,
                ioDispatcher = dispatcher,
            ).use { session ->
                session.start(10, 3)
                session.requestRender(0)
                runCurrent()
                val before = session.renderGeneration.value
                assertEquals(0, session.readPublishedFrame { it.codeWords[0] })
                if (synchronizedOutput) connector.feedFromHost("\u001B[?2026h".ascii())
                connector.feedFromHost("LAST \u20AC\r\n".encodeToByteArray())
                when (termination) {
                    "local" -> session.close()
                    "remote" -> connector.simulateClosed(0)
                    "error" -> connector.simulateCrash(IllegalStateException("read failed"))
                    else -> error("unknown termination: $termination")
                }
                runCurrent()

                assertEquals("LAST \u20AC", terminal.getLineAsString(0))
                val expected = "LAST \u20AC".map(Char::code).toIntArray()
                assertAll(
                    {
                        assertArrayEquals(
                            expected,
                            session.readPublishedFrame { it.codeWords.copyOf(expected.size) },
                        )
                    },
                    { assertNotEquals(before, session.renderGeneration.value) },
                    { assertTrue(session.isClosed) },
                )
            }
    }

    @ParameterizedTest
    @ValueSource(strings = ["local", "remote", "error"])
    fun `termination publishes EOF replacement for incomplete UTF8 exactly once`(termination: String) =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val connector = MockConnector()
            val terminal = TerminalBuffers.create(10, 3)
            TerminalSession
                .create(
                    terminal = terminal,
                    connector = connector,
                    workerDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                ).use { session ->
                    session.start(10, 3)
                    session.requestRender(0)
                    runCurrent()
                    connector.feedFromHost(byteArrayOf(0xE2.toByte(), 0x82.toByte()))
                    when (termination) {
                        "local" -> session.close()
                        "remote" -> connector.simulateClosed(0)
                        "error" -> connector.simulateCrash(IllegalStateException("read failed"))
                        else -> error("unknown termination: $termination")
                    }
                    session.close()
                    connector.simulateClosed(19)
                    runCurrent()
                    assertEquals(0xFFFD, terminal.getCodepointAt(0, 0))
                    assertEquals(0, terminal.getCodepointAt(1, 0))
                    assertArrayEquals(
                        intArrayOf(0xFFFD, 0),
                        session.readPublishedFrame { it.codeWords.copyOf(2) },
                    )
                    assertTrue(session.isClosed)
                }
        }

    @Test
    fun `remote EOF preserves a final frame that was already published`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val connector = MockConnector()
            TerminalSession
                .create(
                    terminal = TerminalBuffers.create(10, 3),
                    connector = connector,
                    workerDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                ).use { session ->
                    session.start(10, 3)
                    connector.feedFromHost("LAST LINE\r\n".ascii())
                    session.requestRender(0)
                    runCurrent()
                    connector.simulateClosed(0)
                    runCurrent()
                    val expected = "LAST LINE".map(Char::code).toIntArray()
                    assertArrayEquals(
                        expected,
                        session.readPublishedFrame { it.codeWords.copyOf(expected.size) },
                    )
                    assertEquals(0, session.exitCode)
                }
        }

    @Test
    fun `input key writes through connector`() {
        val connector = MockConnector()
        val session = createStartedSession(connector)

        session.encodeKey(TerminalKeyEvent.codepoint('a'.code))

        assertEquals("a", connector.writtenBytes.asciiText())
        session.close()
    }

    @Test
    fun `text-only commits follow negotiated keyboard modes through the connector`() {
        val connector = MockConnector()
        val session = createStartedSession(connector)
        try {
            session.encodeKey(TerminalKeyEvent.text("\u00e9"))
            connector.feedFromHost("\u001B[>1u".ascii())
            session.encodeKey(TerminalKeyEvent.text("\u4e2d"))
            connector.feedFromHost("\u001B[>8u".ascii())
            session.encodeKey(TerminalKeyEvent.text("suppressed without associated text reporting"))
            connector.feedFromHost("\u001B[<u".ascii())
            session.encodeKey(TerminalKeyEvent.text("\uD83D\uDE00"))
            connector.feedFromHost("\u001B[<u".ascii())
            session.encodeKey(TerminalKeyEvent.text("e\u0301", type = TerminalKeyEventType.REPEAT))
            session.encodeKey(TerminalKeyEvent.text("x", type = TerminalKeyEventType.RELEASE))
            assertArrayEquals("\u00e9\u4e2d\uD83D\uDE00e\u0301".encodeToByteArray(), connector.writtenBytes)
        } finally {
            session.close()
        }
    }

    @Test
    fun `ctrl L input writes form feed clear screen request`() {
        val connector = MockConnector()
        val session = createStartedSession(connector)

        session.encodeKey(TerminalKeyEvent.codepoint('L'.code, TerminalModifiers.CTRL))

        assertArrayEquals(byteArrayOf(0x0c), connector.writtenBytes)
        session.close()
    }

    @Test
    fun `text replacement writes delete backspace and paste in order`() {
        val connector = MockConnector()
        val session = createStartedSession(connector)

        session.encodeTextReplacement(
            TerminalTextReplacementEvent(
                deleteAfterCursorCount = 1,
                deleteBeforeCursorCount = 2,
                replacementText = "status",
            ),
        )

        assertArrayEquals("\u001B[3~\u007F\u007Fstatus".ascii(), connector.writtenBytes)
        session.close()
    }

    @Test
    fun `local close emits local lifecycle event once`() {
        val connector = MockConnector()
        val session = createStartedSession(connector)

        session.close()
        session.close()

        assertTrue(session.isClosed)
        assertEquals(
            TerminalSessionState.Closed(
                TerminalSessionCloseEvent(exitCode = null, failure = null, locallyRequested = true),
            ),
            session.state.value,
        )
    }

    @Test
    fun `start can only be called once`() {
        val connector = MockConnector()
        val session = createStartedSession(connector)

        val error =
            assertThrows(IllegalStateException::class.java) {
                session.start(columns = 10, rows = 3)
            }

        assertEquals("session already started or closed", error.message)
        assertEquals(1, connector.startCount)
        session.close()
    }

    @Test
    fun `input before start is ignored`() {
        val connector = MockConnector()
        val terminal = TerminalBuffers.create(width = 10, height = 3)
        val session = TerminalSession.create(terminal, connector, ioDispatcher = UnconfinedTestDispatcher())

        session.encodeKey(TerminalKeyEvent.codepoint('a'.code))
        session.encodeTextReplacement(TerminalTextReplacementEvent(1, 1, "text"))

        assertEquals("", connector.writtenBytes.asciiText())
        session.close()
    }

    @Test
    fun `onBytes rejects invalid ranges`() {
        val connector = MockConnector()
        val session = createStartedSession(connector)
        val bytes = ByteArray(4)

        assertThrows(IllegalArgumentException::class.java) {
            session.onBytes(bytes, offset = 3, length = 2)
        }

        session.close()
    }

    @Test
    fun `mock connector ignores writes and host feed after local close`() {
        val connector = MockConnector()
        val session = createStartedSession(connector)

        session.close()
        connector.write("a".ascii())

        assertEquals("", connector.writtenBytes.asciiText())
        assertThrows(IllegalStateException::class.java) {
            connector.feedFromHost("b".ascii())
        }
    }

    @Test
    fun `resize mutates core and calls connector resize`() {
        val connector = MockConnector()
        val terminal = TerminalBuffers.create(width = 10, height = 3)
        val session = createStartedSession(connector, columns = 10, rows = 3, terminal = terminal)

        val resized = session.resize(columns = 20, rows = 5)

        assertEquals(0 to 0, resized)
        assertEquals(20, terminal.width)
        assertEquals(5, terminal.height)
        assertEquals(listOf(10 to 3, 20 to 5), connector.resizeCalls)
        session.close()
    }

    @ParameterizedTest
    @CsvSource("false, 8, 3", "false, 8, 4", "true, 8, 3", "true, 8, 4")
    fun `resize tolerates a stale primary anchor before alternate publication`(
        usePairResult: Boolean,
        columns: Int,
        rows: Int,
    ) = runTest {
        val connector = MockConnector()
        val terminal = TerminalBuffers.create(width = 8, height = 3, maxHistory = 16)
        val session =
            TerminalSession.create(
                terminal,
                connector,
                workerDispatcher = StandardTestDispatcher(testScheduler),
                ioDispatcher = UnconfinedTestDispatcher(),
            )
        session.start(columns = 8, rows = 3)
        try {
            connector.feedFromHost((0..6).joinToString("\r\n") { "row$it" }.ascii())
            runCurrent()
            val beforeResizeGeneration = session.renderGeneration.value
            assertEquals(TerminalRenderBufferKind.PRIMARY, session.readPublishedFrame { it.activeBuffer })
            assertEquals(4, session.readPublishedFrame { it.historySize })

            connector.feedFromHost("\u001B[?1049h".ascii())
            assertEquals(TerminalRenderBufferKind.PRIMARY, session.readPublishedFrame { it.activeBuffer })

            if (usePairResult) {
                assertEquals(0 to 0, session.resize(columns, rows, oldScrollbackOffset = 2))
            } else {
                assertEquals(
                    TerminalViewportResizeResult(scrollbackOffset = 0, historySize = 0, discardedCount = 0L),
                    session.resizeViewport(columns, rows, oldScrollbackOffset = 2),
                )
            }

            assertEquals(columns, terminal.width)
            assertEquals(rows, terminal.height)
            assertEquals(listOf(8 to 3, columns to rows), connector.resizeCalls)
            advanceTimeBy(TerminalSession.RENDER_PUBLICATION_INTERVAL_MS.milliseconds)
            runCurrent()
            assertTrue(session.renderGeneration.value > beforeResizeGeneration)
            requireNotNull(
                session.readPublishedFrame { published ->
                    assertEquals(TerminalRenderBufferKind.ALTERNATE, published.activeBuffer)
                    assertEquals(columns, published.columns)
                    assertEquals(rows, published.rows)
                    assertEquals(0, published.scrollbackOffset)
                    assertEquals(0, published.historySize)
                },
            )
        } finally {
            session.close()
        }
    }

    @Test
    fun `viewport resize captures the new discarded baseline after bounded history reflow`() {
        val connector = MockConnector()
        val terminal = TerminalBuffers.create(width = 8, height = 3, maxHistory = 4)
        val session = TerminalSession.create(terminal, connector, ioDispatcher = UnconfinedTestDispatcher())
        session.start(columns = 8, rows = 3)
        try {
            connector.feedFromHost((0..6).joinToString("\r\n") { "row${it.toString().repeat(5)}" }.ascii())
            session.readRenderFrame { frame ->
                assertEquals(4, frame.historySize)
                assertEquals(0L, frame.discardedCount)
            }

            val resized = session.resizeViewport(columns = 4, rows = 3, oldScrollbackOffset = 3)

            assertTrue(resized.scrollbackOffset > 0, "Reflow must exercise a retained scrollback anchor")
            assertEquals(4, resized.historySize)
            assertTrue(resized.discardedCount > 0L, "Narrowing must discard reflowed rows at the history limit")
            session.readRenderFrame(resized.scrollbackOffset) { frame ->
                assertEquals(4, frame.columns)
                assertEquals(resized.scrollbackOffset, frame.scrollbackOffset)
                assertEquals(resized.historySize, frame.historySize)
                assertEquals(resized.discardedCount, frame.discardedCount)
            }
            assertEquals(listOf(8 to 3, 4 to 3), connector.resizeCalls)
        } finally {
            session.close()
        }
    }

    @Test
    fun `viewport resize baseline excludes output produced by connector notification`() {
        val backingConnector = MockConnector()
        val connector =
            object : TerminalConnector by backingConnector {
                override fun resize(
                    columns: Int,
                    rows: Int,
                ) {
                    backingConnector.resize(columns, rows)
                    if (columns == 4) backingConnector.feedFromHost("\r\nlate".ascii())
                }
            }
        val terminal = TerminalBuffers.create(width = 8, height = 3, maxHistory = 4)
        val session = TerminalSession.create(terminal, connector, ioDispatcher = UnconfinedTestDispatcher())
        session.start(columns = 8, rows = 3)
        try {
            backingConnector.feedFromHost((0..6).joinToString("\r\n") { "row${it.toString().repeat(5)}" }.ascii())

            val resized = session.resizeViewport(columns = 4, rows = 3, oldScrollbackOffset = 3)

            assertTrue(resized.discardedCount > 0L)
            session.readRenderFrame { frame ->
                assertEquals(resized.historySize, frame.historySize)
                assertEquals(resized.discardedCount + 1L, frame.discardedCount)
            }
            assertEquals("late", terminal.getLineAsString(2))
            assertEquals(listOf(8 to 3, 4 to 3), backingConnector.resizeCalls)
        } finally {
            session.close()
        }
    }

    @Test
    fun `ambiguous width policy applies to future host writes`() {
        val connector = MockConnector()
        val terminal = TerminalBuffers.create(width = 6, height = 2)
        val session = createStartedSession(connector, columns = 6, rows = 2, terminal = terminal)

        session.setTreatAmbiguousAsWide(true)
        connector.feedFromHost("\u20ACX".toByteArray(StandardCharsets.UTF_8))

        assertAll(
            { assertEquals(0x20AC, terminal.getCodepointAt(0, 0)) },
            { assertEquals(-1, terminal.getCodepointAt(1, 0)) },
            { assertEquals('X'.code, terminal.getCodepointAt(2, 0)) },
            { assertTrue(terminal.getModeSnapshot().treatAmbiguousAsWide) },
        )
        session.close()
    }

    @Test
    fun `bytes are consumed synchronously before callback returns`() {
        val connector = MockConnector()
        val terminal = TerminalBuffers.create(width = 10, height = 3)
        val session = createStartedSession(connector, terminal = terminal)
        val bytes = "hello\u001B[5n".ascii()

        connector.feedFromHost(bytes)
        bytes.fill('?'.code.toByte())

        assertEquals("hello", terminal.getLineAsString(0))
        assertEquals("\u001B[0n", connector.writtenBytes.asciiText())
        session.close()
    }

    @Test
    fun `OSC 52 audit and allowed write callbacks are forwarded through session wrapper`() {
        val connector = MockConnector()
        val events = RecordingHostEvents()
        val session =
            createStartedSession(
                connector = connector,
                hostEvents = events,
                hostPolicy =
                    HostPolicy(
                        clipboardPolicy =
                            TerminalClipboardPolicy(
                                writePermission = TerminalClipboardPermission.ALLOW,
                            ),
                    ),
            )

        connector.feedFromHost("\u001B]52;c;SGVsbG8=\u0007".ascii())

        assertAll(
            { assertEquals(1, events.clipboardAudits.size) },
            { assertEquals("Hello", events.clipboardWrites.single().text) },
            { assertEquals(events.clipboardAudits.single(), events.clipboardWrites.single().audit) },
        )
        session.close()
    }

    @Test
    fun `OSC 52 prompt callback is forwarded through session wrapper`() {
        val connector = MockConnector()
        val events = RecordingHostEvents()
        val session =
            createStartedSession(
                connector = connector,
                hostEvents = events,
                hostPolicy =
                    HostPolicy(
                        clipboardPolicy =
                            TerminalClipboardPolicy(
                                writePermission = TerminalClipboardPermission.PROMPT,
                            ),
                    ),
            )

        connector.feedFromHost("\u001B]52;c;SGVsbG8=\u0007".ascii())

        assertAll(
            { assertEquals(1, events.clipboardAudits.size) },
            { assertEquals("Hello", events.clipboardPrompts.single().text) },
            { assertEquals(events.clipboardAudits.single(), events.clipboardPrompts.single().audit) },
            { assertTrue(events.clipboardWrites.isEmpty()) },
        )
        session.close()
    }

    @Test
    fun `parser endOfInput is called once`() {
        val terminal = TerminalBuffers.create(width = 10, height = 3)
        val connector = MockConnector()
        val parser = RecordingParser()
        val session =
            TerminalSession(
                terminal = terminal,
                renderPublisher = TerminalRenderPublisher(terminal.width, terminal.height),
                renderReader = terminal as TerminalRenderFrameReader,
                responseReader = terminal,
                connector = connector,
                parser = parser,
                inputEncoderFactory = noOpInputEncoderFactory,
                ioDispatcher = UnconfinedTestDispatcher(),
            )

        session.start(columns = 10, rows = 3)
        session.close()
        connector.simulateClosed(0)
        session.close()

        assertEquals(1, parser.endOfInputCalls)
    }

    @Test
    fun `readRenderFrame exposes frame through session reader`() {
        val connector = MockConnector()
        val session = createStartedSession(connector, columns = 10, rows = 3)

        session.readRenderFrame { frame ->
            assertAll(
                { assertEquals(10, frame.columns) },
                { assertEquals(3, frame.rows) },
            )
        }

        session.close()
    }

    @Test
    fun `hyperlinkUri delegates to session resolver`() {
        val terminal = TerminalBuffers.create(width = 10, height = 3)
        val connector = MockConnector()
        val session =
            TerminalSession(
                terminal = terminal,
                renderPublisher = TerminalRenderPublisher(terminal.width, terminal.height),
                renderReader = terminal as TerminalRenderFrameReader,
                responseReader = terminal,
                connector = connector,
                parser = RecordingParser(),
                inputEncoderFactory = noOpInputEncoderFactory,
                hyperlinkResolver =
                    { id ->
                        if (id == 42) "https://example.com" else null
                    },
                ioDispatcher = UnconfinedTestDispatcher(),
            )

        assertAll(
            { assertEquals("https://example.com", session.hyperlinkUri(42)) },
            { assertNull(session.hyperlinkUri(7)) },
        )
        session.close()
    }

    @Test
    fun `readRenderFrame forwards caller owned scrollback offset`() {
        val terminal = TerminalBuffers.create(width = 10, height = 3)
        val connector = MockConnector()
        val renderReader = OffsetRecordingRenderReader()
        val session =
            TerminalSession(
                terminal = terminal,
                renderPublisher = TerminalRenderPublisher(terminal.width, terminal.height),
                renderReader = renderReader,
                responseReader = terminal,
                connector = connector,
                parser = RecordingParser(),
                inputEncoderFactory = noOpInputEncoderFactory,
                ioDispatcher = UnconfinedTestDispatcher(),
            )

        session.readRenderFrame(scrollbackOffset = 4) { frame ->
            assertEquals(4, frame.scrollbackOffset)
        }

        assertEquals(4, renderReader.lastOffset)
        session.close()
    }

    @Test
    fun `requestRender publishes caller owned scrollback offset`() =
        runTest {
            val terminal = TerminalBuffers.create(width = 10, height = 3)
            val connector = MockConnector()
            val renderReader = OffsetRecordingRenderReader()
            val session =
                TerminalSession(
                    terminal = terminal,
                    renderPublisher = TerminalRenderPublisher(terminal.width, terminal.height),
                    renderReader = renderReader,
                    responseReader = terminal,
                    connector = connector,
                    parser = RecordingParser(),
                    inputEncoderFactory = noOpInputEncoderFactory,
                    workerDispatcher = StandardTestDispatcher(testScheduler),
                    ioDispatcher = UnconfinedTestDispatcher(),
                )
            session.use {
                val previousGeneration = session.renderGeneration.value
                session.requestRender(scrollbackOffset = 3)

                runCurrent()
                assertTrue(session.renderGeneration.value > previousGeneration)
                assertAll(
                    { assertEquals(3, renderReader.lastOffset) },
                    { assertEquals(3, session.readPublishedFrame { it.scrollbackOffset }) },
                )
            }
        }

    @Test
    fun `synchronized output mode defers rendering and flushes on disable`() =
        runTest {
            val connector = MockConnector()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val terminal = TerminalBuffers.create(width = 10, height = 3)
            val session =
                TerminalSession.create(
                    terminal,
                    connector,
                    workerDispatcher = dispatcher,
                    ioDispatcher = UnconfinedTestDispatcher(),
                )
            session.start(columns = 10, rows = 3)

            // Enable synchronized output mode: CSI ? 2026 h
            connector.feedFromHost("\u001B[?2026h".toByteArray(StandardCharsets.US_ASCII))

            // Write some text to trigger render requests
            connector.feedFromHost("hello".toByteArray(StandardCharsets.US_ASCII))

            runCurrent()
            assertEquals(-1L, session.renderGeneration.value)

            // Disable synchronized output mode: CSI ? 2026 l
            connector.feedFromHost("\u001B[?2026l".toByteArray(StandardCharsets.US_ASCII))

            runCurrent()
            assertTrue(session.renderGeneration.value >= 0L)
            assertEquals("hello", terminal.getLineAsString(0))
            session.close()
        }

    @Test
    fun `synchronized output mode automatically times out and flushes`() =
        runTest {
            val connector = MockConnector()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val terminal = TerminalBuffers.create(width = 10, height = 3)
            val session =
                TerminalSession.create(
                    terminal,
                    connector,
                    workerDispatcher = dispatcher,
                    ioDispatcher = UnconfinedTestDispatcher(),
                )
            session.start(columns = 10, rows = 3)

            // Enable synchronized output mode and write some text
            connector.feedFromHost("\u001B[?2026hhello".toByteArray(StandardCharsets.US_ASCII))

            runCurrent()
            assertEquals(-1L, session.renderGeneration.value)

            advanceTimeBy(100.milliseconds)
            runCurrent()
            assertTrue(session.renderGeneration.value >= 0L)

            // Verify synchronized output mode is turned off in the core
            assertFalse(terminal.getModeSnapshot().isSynchronizedOutput)
            assertEquals("hello", terminal.getLineAsString(0))
            session.close()
        }

    @Test
    fun `render requests coalesce while worker is busy`() =
        runTest {
            val terminal = TerminalBuffers.create(width = 10, height = 3)
            val connector = MockConnector()
            val renderReader = OffsetRecordingRenderReader()
            val session =
                TerminalSession(
                    terminal = terminal,
                    renderPublisher = TerminalRenderPublisher(terminal.width, terminal.height),
                    renderReader = renderReader,
                    responseReader = terminal,
                    connector = connector,
                    parser = RecordingParser(),
                    inputEncoderFactory = noOpInputEncoderFactory,
                    workerDispatcher = StandardTestDispatcher(testScheduler),
                    ioDispatcher = UnconfinedTestDispatcher(),
                )
            session.use {
                renderReader.beforeRead = { call ->
                    if (call == 1) repeat(5) { session.requestRender(scrollbackOffset = 0) }
                }
                session.requestRender(scrollbackOffset = 0)
                runCurrent()

                assertEquals(2, renderReader.readCalls)
                assertEquals(6L, session.renderGeneration.value)
            }
        }

    @Test
    fun `sustained host output publishes only the latest generation once per interval`() =
        runTest {
            val terminal = TerminalBuffers.create(width = 10, height = 3)
            val connector = MockConnector()
            val renderReader = OffsetRecordingRenderReader()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val session =
                TerminalSession(
                    terminal = terminal,
                    renderPublisher = TerminalRenderPublisher(terminal.width, terminal.height),
                    renderReader = renderReader,
                    responseReader = terminal,
                    connector = connector,
                    parser = RecordingParser(),
                    inputEncoderFactory = noOpInputEncoderFactory,
                    workerDispatcher = dispatcher,
                    ioDispatcher = UnconfinedTestDispatcher(),
                )

            session.requestRender(scrollbackOffset = 0)
            runCurrent()
            assertAll(
                { assertEquals(1, renderReader.readCalls) },
                { assertEquals(0, renderReader.lastOffset) },
            )

            repeat(3) {
                session.onBytes(byteArrayOf('x'.code.toByte()), offset = 0, length = 1)
            }
            runCurrent()
            assertEquals(1, renderReader.readCalls)

            advanceTimeBy(TerminalSession.RENDER_PUBLICATION_INTERVAL_MS.milliseconds)
            runCurrent()
            assertAll(
                { assertEquals(2, renderReader.readCalls) },
                { assertEquals(4L, session.renderGeneration.value) },
            )
            session.close()
        }

    @Test
    fun `explicit viewport request interrupts host output publication interval`() =
        runTest {
            val terminal = TerminalBuffers.create(width = 10, height = 3)
            val connector = MockConnector()
            val renderReader = OffsetRecordingRenderReader()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val session =
                TerminalSession(
                    terminal = terminal,
                    renderPublisher = TerminalRenderPublisher(terminal.width, terminal.height),
                    renderReader = renderReader,
                    responseReader = terminal,
                    connector = connector,
                    parser = RecordingParser(),
                    inputEncoderFactory = noOpInputEncoderFactory,
                    workerDispatcher = dispatcher,
                    ioDispatcher = UnconfinedTestDispatcher(),
                )

            session.requestRender(scrollbackOffset = 0)
            runCurrent()
            session.onBytes(byteArrayOf('x'.code.toByte()), offset = 0, length = 1)
            session.requestRender(scrollbackOffset = 5)
            runCurrent()

            assertAll(
                { assertEquals(2, renderReader.readCalls) },
                { assertEquals(5, renderReader.lastOffset) },
                { assertEquals(5, session.readPublishedFrame { it.scrollbackOffset }) },
            )
            session.close()
        }

    @Test
    fun `requestRender publishes latest viewport after in flight render`() =
        runTest {
            val terminal = TerminalBuffers.create(width = 10, height = 3)
            val connector = MockConnector()
            val renderReader = OffsetRecordingRenderReader()
            val session =
                TerminalSession(
                    terminal = terminal,
                    renderPublisher = TerminalRenderPublisher(terminal.width, terminal.height),
                    renderReader = renderReader,
                    responseReader = terminal,
                    connector = connector,
                    parser = RecordingParser(),
                    inputEncoderFactory = noOpInputEncoderFactory,
                    workerDispatcher = StandardTestDispatcher(testScheduler),
                    ioDispatcher = UnconfinedTestDispatcher(),
                )
            session.use {
                renderReader.beforeRead = { call ->
                    if (call == 1) {
                        session.requestRender(scrollbackOffset = 2)
                        session.requestRender(scrollbackOffset = 5)
                    }
                }
                session.requestRender(scrollbackOffset = 1)
                runCurrent()

                assertAll(
                    { assertEquals(2, renderReader.readCalls) },
                    { assertEquals(listOf(1, 5), renderReader.offsets.toList()) },
                    { assertEquals(5, session.readPublishedFrame { it.scrollbackOffset }) },
                )
            }
        }

    @Test
    fun `requestRender waits for a new generation after publish failure`() =
        runTest {
            val terminal = TerminalBuffers.create(width = 10, height = 3)
            val connector = MockConnector()
            val renderReader = OffsetRecordingRenderReader()
            renderReader.beforeRead = { call -> check(call != 1) { "first render fails before publish" } }
            val session =
                TerminalSession(
                    terminal = terminal,
                    renderPublisher = TerminalRenderPublisher(terminal.width, terminal.height),
                    renderReader = renderReader,
                    responseReader = terminal,
                    connector = connector,
                    parser = RecordingParser(),
                    inputEncoderFactory = noOpInputEncoderFactory,
                    workerDispatcher = StandardTestDispatcher(testScheduler),
                    ioDispatcher = UnconfinedTestDispatcher(),
                )
            session.use {
                session.requestRender(scrollbackOffset = 1)
                runCurrent()

                assertAll(
                    { assertEquals(1, renderReader.readCalls) },
                    { assertEquals(-1L, session.renderGeneration.value) },
                    { assertNull(session.readPublishedFrame { true }) },
                )

                session.requestRender(scrollbackOffset = 2)

                runCurrent()
                assertAll(
                    { assertEquals(2, renderReader.readCalls) },
                    { assertEquals(2, session.readPublishedFrame { it.scrollbackOffset }) },
                )
            }
        }

    @Test
    fun `subscriber failure does not stop render publication`() =
        runTest {
            val terminal = TerminalBuffers.create(width = 10, height = 3)
            val connector = MockConnector()
            val renderReader = OffsetRecordingRenderReader()
            val session =
                TerminalSession(
                    terminal = terminal,
                    renderPublisher = TerminalRenderPublisher(terminal.width, terminal.height),
                    renderReader = renderReader,
                    responseReader = terminal,
                    connector = connector,
                    parser = RecordingParser(),
                    inputEncoderFactory = noOpInputEncoderFactory,
                    workerDispatcher = StandardTestDispatcher(testScheduler),
                    ioDispatcher = UnconfinedTestDispatcher(),
                )
            session.use {
                val subscriberFailure = IllegalStateException("subscriber failed")
                var observedFailure: Throwable? = null
                val collectorScope =
                    CoroutineScope(
                        SupervisorJob() +
                            StandardTestDispatcher(testScheduler) +
                            CoroutineExceptionHandler { _, failure -> observedFailure = failure },
                    )
                try {
                    collectorScope.launch {
                        session.renderGeneration.first { it >= 0L }
                        throw subscriberFailure
                    }

                    session.requestRender(scrollbackOffset = 1)
                    runCurrent()
                    val firstGeneration = session.renderGeneration.value
                    assertSame(subscriberFailure, observedFailure)

                    assertAll(
                        { assertEquals(1, renderReader.readCalls) },
                        { assertEquals(1, session.readPublishedFrame { it.scrollbackOffset }) },
                    )

                    session.requestRender(scrollbackOffset = 2)

                    runCurrent()
                    assertTrue(session.renderGeneration.value > firstGeneration)
                    assertAll(
                        { assertEquals(2, renderReader.readCalls) },
                        { assertEquals(2, session.readPublishedFrame { it.scrollbackOffset }) },
                    )
                } finally {
                    collectorScope.cancel()
                }
            }
        }

    @Test
    fun `readRenderFrame blocks host byte mutation until callback returns`() {
        val terminal = TerminalBuffers.create(width = 10, height = 3)
        val connector = MockConnector()
        val parser = RecordingParser()
        val session =
            TerminalSession(
                terminal = terminal,
                renderPublisher = TerminalRenderPublisher(terminal.width, terminal.height),
                renderReader = terminal as TerminalRenderFrameReader,
                responseReader = terminal,
                connector = connector,
                parser = parser,
                inputEncoderFactory = noOpInputEncoderFactory,
                workerDispatcher = StandardTestDispatcher(),
                ioDispatcher = UnconfinedTestDispatcher(),
            )
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val feedCompleted = CountDownLatch(1)

        session.start(columns = 10, rows = 3)

        session.use {
            SessionTestThread("terminal-session-render-lock-test") {
                session.readRenderFrame {
                    callbackEntered.countDown()
                    releaseCallback.await()
                }
            }.use { renderThread ->
                try {
                    assertTrue(callbackEntered.await(SESSION_THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS), "render callback did not start")
                    SessionTestThread("terminal-session-feed-lock-test") {
                        connector.feedFromHost("A".ascii())
                        feedCompleted.countDown()
                    }.use { feedThread ->
                        try {
                            feedThread.awaitBlockedBy(renderThread)
                            assertEquals(1L, feedCompleted.count, "host bytes mutated during render callback")
                            assertEquals(0, parser.acceptCalls)
                        } finally {
                            releaseCallback.countDown()
                        }
                        renderThread.awaitCompletion()
                        feedThread.awaitCompletion()
                    }
                } finally {
                    releaseCallback.countDown()
                }
            }
            assertEquals(0L, feedCompleted.count, "host byte feed did not complete")
            assertEquals(1, parser.acceptCalls)
        }
    }

    @Test
    fun `readRenderFrame blocks resize until callback returns`() {
        val connector = MockConnector()
        val terminal = TerminalBuffers.create(width = 10, height = 3)
        val session = createStartedSession(connector, columns = 10, rows = 3, terminal = terminal)
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val resizeCompleted = CountDownLatch(1)

        session.use {
            SessionTestThread("terminal-session-render-resize-lock-test") {
                session.readRenderFrame {
                    callbackEntered.countDown()
                    releaseCallback.await()
                }
            }.use { renderThread ->
                try {
                    assertTrue(callbackEntered.await(SESSION_THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS), "render callback did not start")
                    SessionTestThread("terminal-session-resize-lock-test") {
                        session.resize(columns = 20, rows = 5)
                        resizeCompleted.countDown()
                    }.use { resizeThread ->
                        try {
                            resizeThread.awaitBlockedBy(renderThread)
                            assertEquals(1L, resizeCompleted.count, "resize completed during render callback")
                            assertEquals(10, terminal.width)
                            assertEquals(3, terminal.height)
                        } finally {
                            releaseCallback.countDown()
                        }
                        renderThread.awaitCompletion()
                        resizeThread.awaitCompletion()
                    }
                } finally {
                    releaseCallback.countDown()
                }
            }
            assertEquals(0L, resizeCompleted.count, "resize did not complete")
            assertEquals(20, terminal.width)
            assertEquals(5, terminal.height)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `onBytes cannot mutate while copyLine is running inside render callback`(boundedAbsoluteCopy: Boolean) {
        val terminal = TerminalBuffers.create(width = 10, height = 3)
        val connector = MockConnector()
        val parser = RecordingParser()
        val copyEntered = CountDownLatch(1)
        val releaseCopy = CountDownLatch(1)
        val feedCompleted = CountDownLatch(1)
        val session =
            TerminalSession(
                terminal = terminal,
                renderPublisher = TerminalRenderPublisher(terminal.width, terminal.height),
                renderReader = BlockingCopyRenderReader(copyEntered, releaseCopy),
                responseReader = terminal,
                connector = connector,
                parser = parser,
                inputEncoderFactory = noOpInputEncoderFactory,
                workerDispatcher = StandardTestDispatcher(),
                ioDispatcher = UnconfinedTestDispatcher(),
            )
        session.start(columns = 10, rows = 3)

        session.use {
            SessionTestThread("terminal-session-copyline-lock-test") {
                if (boundedAbsoluteCopy) {
                    val copy = TerminalRenderRangeCopy()
                    assertTrue(copy.read(session, 0L, Long.MAX_VALUE))
                } else {
                    session.readRenderFrame { frame ->
                        frame.copyLine(
                            row = 0,
                            codeWords = IntArray(frame.columns),
                            attrWords = LongArray(frame.columns),
                            flags = IntArray(frame.columns),
                        )
                    }
                }
            }.use { renderThread ->
                try {
                    assertTrue(copyEntered.await(SESSION_THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS), "copyLine did not start")
                    SessionTestThread("terminal-session-feed-during-copyline-test") {
                        connector.feedFromHost("A".ascii())
                        feedCompleted.countDown()
                    }.use { feedThread ->
                        try {
                            feedThread.awaitBlockedBy(renderThread)
                            assertEquals(1L, feedCompleted.count, "host bytes mutated during copyLine")
                            assertEquals(0, parser.acceptCalls)
                        } finally {
                            releaseCopy.countDown()
                        }
                        renderThread.awaitCompletion()
                        feedThread.awaitCompletion()
                    }
                } finally {
                    releaseCopy.countDown()
                }
            }
            assertEquals(0L, feedCompleted.count, "host byte feed did not complete")
            assertEquals(1, parser.acceptCalls)
        }
    }

    @Test
    fun `UI callback cannot observe half mutated row`() {
        val terminal = TerminalBuffers.create(width = 10, height = 3)
        val connector = MockConnector()
        val firstWriteDone = CountDownLatch(1)
        val releaseSecondWrite = CountDownLatch(1)
        val renderEntered = CountDownLatch(1)
        val parser = HalfRowParser(terminal, firstWriteDone, releaseSecondWrite)
        val session =
            TerminalSession(
                terminal = terminal,
                renderPublisher = TerminalRenderPublisher(terminal.width, terminal.height),
                renderReader = terminal as TerminalRenderFrameReader,
                responseReader = terminal,
                connector = connector,
                parser = parser,
                inputEncoderFactory = noOpInputEncoderFactory,
                workerDispatcher = StandardTestDispatcher(),
                ioDispatcher = UnconfinedTestDispatcher(),
            )
        session.start(columns = 10, rows = 3)

        session.use {
            SessionTestThread("terminal-session-half-row-feed-test") {
                connector.feedFromHost("ignored".ascii())
            }.use { feedThread ->
                try {
                    assertTrue(firstWriteDone.await(SESSION_THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS), "parser did not perform first write")
                    SessionTestThread("terminal-session-half-row-render-test") {
                        session.readRenderFrame { frame ->
                            renderEntered.countDown()
                            val codeWords = IntArray(frame.columns)
                            val attrWords = LongArray(frame.columns)
                            val flags = IntArray(frame.columns)
                            frame.copyLine(
                                row = 0,
                                codeWords = codeWords,
                                attrWords = attrWords,
                                flags = flags,
                            )
                            assertEquals('A'.code, codeWords[0])
                            assertEquals('B'.code, codeWords[1])
                        }
                    }.use { renderThread ->
                        try {
                            renderThread.awaitBlockedBy(feedThread)
                            assertEquals(1L, renderEntered.count, "render callback observed half-mutated row")
                        } finally {
                            releaseSecondWrite.countDown()
                        }
                        feedThread.awaitCompletion()
                        renderThread.awaitCompletion()
                    }
                } finally {
                    releaseSecondWrite.countDown()
                }
            }
            assertEquals(0L, renderEntered.count, "render callback did not run")
        }
    }

    private fun createStartedSession(
        connector: TerminalConnector,
        columns: Int = 10,
        rows: Int = 3,
        hostEvents: HostEventSink = HostEventSink.NONE,
        hostPolicy: HostPolicy = HostPolicy(),
        terminal: TerminalRenderBuffer = TerminalBuffers.create(width = columns, height = rows),
    ): TerminalSession {
        val session =
            TerminalSession.create(
                terminal,
                connector,
                hostEvents = hostEvents,
                hostPolicy = hostPolicy,
                workerDispatcher = StandardTestDispatcher(),
                ioDispatcher = UnconfinedTestDispatcher(),
            )
        session.start(columns, rows)
        return session
    }

    private class RecordingHostEvents : HostEventSink by HostEventSink.NONE {
        val clipboardAudits = mutableListOf<TerminalClipboardAuditEvent>()
        val clipboardWrites = mutableListOf<TerminalClipboardWriteEvent>()
        val clipboardPrompts = mutableListOf<TerminalClipboardPromptEvent>()

        override fun terminalClipboardRequest(event: TerminalClipboardAuditEvent) {
            clipboardAudits += event
        }

        override fun terminalClipboardWrite(event: TerminalClipboardWriteEvent) {
            clipboardWrites += event
        }

        override fun terminalClipboardPrompt(event: TerminalClipboardPromptEvent) {
            clipboardPrompts += event
        }
    }

    private fun String.ascii(): ByteArray = toByteArray(StandardCharsets.US_ASCII)

    private fun ByteArray.asciiText(): String = toString(StandardCharsets.US_ASCII)

    private class RecordingParser : TerminalOutputParser {
        var endOfInputCalls: Int = 0
            private set

        @Volatile
        var acceptCalls: Int = 0
            private set

        override fun accept(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) {
            acceptCalls++
        }

        override fun acceptByte(byteValue: Int) = Unit

        override fun endOfInput() {
            endOfInputCalls++
        }

        override fun reset() = Unit
    }

    private class HalfRowParser(
        private val terminal: TerminalBuffer,
        private val firstWriteDone: CountDownLatch,
        private val releaseSecondWrite: CountDownLatch,
    ) : TerminalOutputParser {
        override fun accept(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) {
            terminal.writeCodepoint('A'.code)
            firstWriteDone.countDown()
            releaseSecondWrite.await()
            terminal.writeCodepoint('B'.code)
        }

        override fun acceptByte(byteValue: Int) = Unit

        override fun endOfInput() = Unit

        override fun reset() = Unit
    }

    private class BlockingCopyRenderReader(
        private val copyEntered: CountDownLatch,
        private val releaseCopy: CountDownLatch,
    ) : TerminalRenderFrameReader {
        override fun readRenderFrame(consumer: TerminalRenderFrameConsumer) {
            consumer.accept(
                object : TerminalRenderFrame {
                    override val columns: Int = 2
                    override val rows: Int = 1
                    override val frameGeneration: Long = 0
                    override val structureGeneration: Long = 0
                    override val activeBuffer: TerminalRenderBufferKind = TerminalRenderBufferKind.PRIMARY
                    override val cursor: TerminalRenderCursor =
                        TerminalRenderCursor(
                            column = 0,
                            row = 0,
                            visible = true,
                            blinking = false,
                            shape = TerminalRenderCursorShape.BLOCK,
                            generation = 0,
                        )

                    override fun lineGeneration(row: Int): Long = 0

                    override fun lineWrapped(row: Int): Boolean = false

                    override fun copyLine(
                        row: Int,
                        codeWords: IntArray,
                        codeOffset: Int,
                        attrWords: LongArray,
                        attrOffset: Int,
                        flags: IntArray,
                        flagOffset: Int,
                        extraAttrWords: LongArray?,
                        extraAttrOffset: Int,
                        hyperlinkIds: IntArray?,
                        hyperlinkOffset: Int,
                        clusterSink: TerminalRenderClusterSink?,
                        clusterDataSink: TerminalRenderClusterDataSink?,
                    ) {
                        copyEntered.countDown()
                        releaseCopy.await()
                        codeWords[codeOffset] = 'X'.code
                        flags[flagOffset] = TerminalRenderCellFlags.CODEPOINT
                    }
                },
            )
        }
    }

    private class OffsetRecordingRenderReader : TerminalRenderFrameReader {
        private val calls = AtomicInteger(0)
        val offsets = mutableListOf<Int>()
        var beforeRead: (Int) -> Unit = {}

        var lastOffset: Int = -1
            private set

        val readCalls: Int
            get() = calls.get()

        override fun readRenderFrame(consumer: TerminalRenderFrameConsumer) {
            readRenderFrame(scrollbackOffset = 0, consumer = consumer)
        }

        override fun readRenderFrame(
            scrollbackOffset: Int,
            consumer: TerminalRenderFrameConsumer,
        ) {
            val call = calls.incrementAndGet()
            lastOffset = scrollbackOffset
            offsets += scrollbackOffset
            beforeRead(call)
            consumer.accept(OffsetRenderFrame(scrollbackOffset))
        }
    }

    private class OffsetRenderFrame(
        override val scrollbackOffset: Int,
    ) : TerminalRenderFrame {
        override val columns: Int = 10
        override val rows: Int = 3
        override val historySize: Int = 10
        override val frameGeneration: Long = 1
        override val structureGeneration: Long = 1
        override val activeBuffer: TerminalRenderBufferKind = TerminalRenderBufferKind.PRIMARY
        override val cursor: TerminalRenderCursor =
            TerminalRenderCursor(
                column = 0,
                row = 0,
                visible = scrollbackOffset == 0,
                blinking = false,
                shape = TerminalRenderCursorShape.BLOCK,
                generation = 1,
            )

        override fun lineGeneration(row: Int): Long = 1

        override fun lineWrapped(row: Int): Boolean = false

        override fun copyLine(
            row: Int,
            codeWords: IntArray,
            codeOffset: Int,
            attrWords: LongArray,
            attrOffset: Int,
            flags: IntArray,
            flagOffset: Int,
            extraAttrWords: LongArray?,
            extraAttrOffset: Int,
            hyperlinkIds: IntArray?,
            hyperlinkOffset: Int,
            clusterSink: TerminalRenderClusterSink?,
            clusterDataSink: TerminalRenderClusterDataSink?,
        ) = Unit
    }

    private val noOpInputEncoderFactory =
        io.github.ketraterm.input.api.TerminalInputEncoderFactory { _, _, _ ->
            object : TerminalInputEncoder {
                override fun encodeKey(event: TerminalKeyEvent) = Unit

                override fun encodePaste(event: TerminalPasteEvent) = Unit

                override fun encodeFocus(event: TerminalFocusEvent) = Unit

                override fun encodeMouse(event: TerminalMouseEvent) = Unit

                override fun setInputPolicy(policy: TerminalInputPolicy) = Unit
            }
        }
}
