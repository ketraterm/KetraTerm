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
import io.github.ketraterm.host.HostControlPolicy
import io.github.ketraterm.host.HostPolicy
import io.github.ketraterm.input.TerminalInputEncoders
import io.github.ketraterm.input.api.TerminalInputEncoderFactory
import io.github.ketraterm.input.event.*
import io.github.ketraterm.input.policy.BackspacePolicy
import io.github.ketraterm.input.policy.PasteControlPolicy
import io.github.ketraterm.input.policy.TerminalInputPolicy
import io.github.ketraterm.session.TerminalInputAdmission.*
import io.github.ketraterm.testkit.MockConnector
import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.transport.TerminalConnectorListener
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalSessionAdmissionTest {
    @Test
    fun `exact bytes copy the selected range without paste transformations and preserve queue order`() =
        runTest {
            val connector = MockConnector()
            session(connector).use { session ->
                session.start(10, 3)
                connector.feedFromHost("\u001b[?2004h".toByteArray())
                session.setInputPolicy(TerminalInputPolicy(pasteControlPolicy = PasteControlPolicy.STRIP_C0_EXCEPT_TAB_CR_LF))
                session.encodeKey(TerminalKeyEvent.text("a"))
                val bytes = byteArrayOf(99, 27, 91, 50, 52, 126, 101, 0, -1, 88)
                assertEquals(ACCEPTED, session.submitBytes(bytes, 1, 8))
                bytes.fill(42)
                assertEquals(ACCEPTED, session.submitInput(TerminalPasteEvent("p")))
                connector.feedFromHost("\u001b[5n".toByteArray())
                assertEquals(ACCEPTED, session.submitInput(TerminalTextReplacementEvent(1, 1, "r")))
                assertEquals(ACCEPTED, session.submitBytes(byteArrayOf(122)))
                assertArrayEquals(byteArrayOf(), connector.writtenBytes, "Admission must not require transport completion")
                runCurrent()
                assertArrayEquals(
                    "a\u001b[24~e".toByteArray() + byteArrayOf(0, -1) +
                        "\u001b[200~p\u001b[201~\u001b[0n\u001b[3~\u007f\u001b[200~r\u001b[201~z".toByteArray(),
                    connector.writtenBytes,
                )
            }
        }

    @Test
    fun `slice validation precedes lifecycle and empty input has no output`() =
        runTest {
            val connector = MockConnector()
            session(connector).use { session ->
                val bytes = byteArrayOf(1, 2)

                fun invalidSlices() {
                    for ((offset, length) in listOf(-1 to 0, 0 to -1, 3 to 0, 1 to 2, Int.MAX_VALUE to 1, 1 to Int.MAX_VALUE)) {
                        assertThrows(IllegalArgumentException::class.java) { session.submitBytes(bytes, offset, length) }
                    }
                }
                invalidSlices()
                assertEquals(NOT_RUNNING, session.submitBytes(bytes, 2, 0))
                assertEquals(NOT_RUNNING, session.submitInput(emptyList()))
                session.start(10, 3)
                invalidSlices()
                repeat(32) {
                    assertEquals(ACCEPTED, session.submitBytes(bytes, 2, 0))
                    assertEquals(ACCEPTED, session.submitInput(emptyList()))
                    assertEquals(ACCEPTED, session.submitInput(TerminalPasteEvent("")))
                }
                runCurrent()
                assertFalse(session.isClosed)
                assertArrayEquals(byteArrayOf(), connector.writtenBytes)
                session.close()
                invalidSlices()
                assertEquals(CLOSED, session.submitBytes(bytes, 2, 0))
                assertEquals(CLOSED, session.submitInput(emptyList()))
                assertNull(session.failure)
            }
        }

    @Test
    fun `all semantic event kinds report lifecycle rejection and retain mode suppression`() =
        runTest {
            val connector = MockConnector()
            val events =
                listOf(
                    TerminalKeyEvent.text("é🙂"),
                    TerminalPasteEvent("p"),
                    TerminalTextReplacementEvent(0, 0, "r"),
                    TerminalFocusEvent(true),
                    TerminalMouseEvent(0, 0, TerminalMouseButton.LEFT, TerminalMouseEventType.PRESS),
                )
            session(connector).use { session ->
                for (event in events) assertEquals(NOT_RUNNING, session.submitInput(event))
                assertEquals(NOT_RUNNING, session.submitInput(events))
                session.start(10, 3)
                for (event in events) assertEquals(ACCEPTED, session.submitInput(event))
                runCurrent()
                assertEquals("é🙂pr", connector.writtenBytes.toString(Charsets.UTF_8))
                session.close()
                for (event in events) assertEquals(CLOSED, session.submitInput(event))
                assertEquals(CLOSED, session.submitInput(events))
            }
        }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `compound input copies list and captures semantic modes for all phases`(customEncoder: Boolean) =
        runTest {
            val connector = MockConnector()
            session(connector, customEncoder).use { session ->
                session.start(10, 3)
                connector.feedFromHost("\u001b[?1h\u001b[?2004h\u001b[?1004h\u001b[?1000h\u001b[?1006h".toByteArray())
                val events =
                    mutableListOf<TerminalInputEvent>(
                        TerminalKeyEvent.key(TerminalKey.RIGHT),
                        TerminalTextReplacementEvent(1, 1, "r"),
                        TerminalPasteEvent("p"),
                        TerminalKeyEvent.text("é🙂"),
                        TerminalFocusEvent(true),
                        TerminalMouseEvent(0, 0, TerminalMouseButton.LEFT, TerminalMouseEventType.PRESS),
                        TerminalKeyEvent.key(TerminalKey.LEFT),
                        TerminalKeyEvent.key(TerminalKey.ENTER),
                    )
                assertEquals(ACCEPTED, session.submitInput(events))
                events.clear()
                session.setInputPolicy(TerminalInputPolicy(backspacePolicy = BackspacePolicy.BACKSPACE))
                connector.feedFromHost("\u001b[?1l\u001b[?2004l\u001b[?1004l\u001b[?1000l\u001b[5n".toByteArray())
                assertEquals(ACCEPTED, session.submitInput(TerminalKeyEvent.key(TerminalKey.RIGHT)))
                runCurrent()
                assertEquals(
                    "\u001bOC\u001b[3~\u007f\u001b[200~r\u001b[201~\u001b[200~p\u001b[201~" +
                        "é🙂\u001b[I\u001b[<0;1;1M\u001bOD\r\u001b[0n\u001b[C",
                    connector.writtenBytes.toString(Charsets.UTF_8),
                )
            }
        }

    @ParameterizedTest
    @ValueSource(strings = ["rawOversized", "rawFull", "bulkSlots", "bulkUnits", "compoundCount", "compoundUnits"])
    fun `capacity rejection publishes no prefix and reports failure`(kind: String) =
        runTest {
            val connector = MockConnector()
            session(connector).use { session ->
                session.start(10, 3)
                val result =
                    when (kind) {
                        "rawOversized" -> session.submitBytes(ByteArray(OutboundWriter.MAX_QUEUED_BYTES + 1))
                        "rawFull" -> {
                            assertEquals(ACCEPTED, session.submitBytes(ByteArray(OutboundWriter.MAX_QUEUED_BYTES)))
                            session.submitBytes(byteArrayOf(1))
                        }
                        "bulkSlots" -> {
                            repeat(16) { assertEquals(ACCEPTED, session.submitInput(TerminalPasteEvent("p"))) }
                            session.submitInput(listOf(TerminalKeyEvent.key(TerminalKey.END), TerminalKeyEvent.text("x")))
                        }
                        "bulkUnits" -> session.submitInput(TerminalTextReplacementEvent(Int.MAX_VALUE, Int.MAX_VALUE, "x"))
                        "compoundCount" -> session.submitInput(List(257) { TerminalKeyEvent.text("x") })
                        else ->
                            session.submitInput(
                                listOf(TerminalKeyEvent.key(TerminalKey.END), TerminalTextReplacementEvent(Int.MAX_VALUE, 0, "x")),
                            )
                    }
                assertEquals(CAPACITY_EXCEEDED, result)
                assertInstanceOf(OutboundCapacityException::class.java, session.failure)
                assertTrue(session.state.value is TerminalSessionState.Closed)
                runCurrent()
                assertArrayEquals(byteArrayOf(), connector.writtenBytes)
                assertEquals(CLOSED, session.submitBytes(byteArrayOf(1)))
                assertEquals(1, connector.closeCount)
            }
        }

    @Test
    fun `compound event limit is inclusive and completed reservations are reusable`() =
        runTest {
            val connector = MockConnector()
            session(connector).use { session ->
                session.start(10, 3)
                repeat(2) {
                    repeat(16) { assertEquals(ACCEPTED, session.submitInput(List(256) { TerminalKeyEvent.text("x") })) }
                    runCurrent()
                }
                assertEquals("x".repeat(8192), connector.writtenBytes.toString(Charsets.UTF_8))
                assertFalse(session.isClosed)
            }
        }

    @Test
    fun `concurrent producers keep complete compounds and exact byte ranges contiguous`() =
        runTest {
            val connector = MockConnector()
            session(connector).use { session ->
                session.start(10, 3)
                val gate = CountDownLatch(1)
                val ready = CountDownLatch(3)
                val threads =
                    (0..2).map { producer ->
                        SessionTestThread("admission-$producer") {
                            ready.countDown()
                            gate.await()
                            repeat(4) {
                                val result =
                                    when (producer) {
                                        0 ->
                                            session.submitInput(
                                                listOf(TerminalKeyEvent.text("a"), TerminalPasteEvent("b"), TerminalKeyEvent.text("c")),
                                            )
                                        1 -> session.submitBytes("def".toByteArray())
                                        else ->
                                            session.submitInput(
                                                listOf(
                                                    TerminalKeyEvent.text("g"),
                                                    TerminalTextReplacementEvent(0, 0, "h"),
                                                    TerminalKeyEvent.text("i"),
                                                ),
                                            )
                                    }
                                assertEquals(ACCEPTED, result)
                            }
                        }
                    }
                try {
                    assertTrue(ready.await(10, TimeUnit.SECONDS))
                    gate.countDown()
                    threads.forEach { it.awaitCompletion() }
                    runCurrent()
                    val chunks = connector.writtenBytes.toString(Charsets.UTF_8).chunked(3)
                    assertEquals(12, chunks.size)
                    for (expected in listOf("abc", "def", "ghi")) assertEquals(4, chunks.count { it == expected })
                } finally {
                    gate.countDown()
                    threads.forEach { it.close() }
                }
            }
        }

    @Test
    fun `startup rejects host input until start completes and queues replies first`() =
        runTest {
            val delegate = MockConnector()
            lateinit var session: TerminalSession
            val connector =
                object : TerminalConnector by delegate {
                    override fun start(listener: TerminalConnectorListener) {
                        delegate.start(listener)
                        assertEquals(NOT_RUNNING, session.submitBytes("\u001b[24~e".toByteArray()))
                        assertEquals(NOT_RUNNING, session.submitInput(listOf(TerminalKeyEvent.text("x"))))
                        listener.onBytes("\u001b[5n".toByteArray(), 0, 4)
                    }
                }
            session = session(connector)
            session.use {
                session.start(10, 3)
                assertEquals(ACCEPTED, session.submitBytes("\u001b[24~e".toByteArray()))
                runCurrent()
                assertEquals("\u001b[0n\u001b[24~e", delegate.writtenBytes.toString(Charsets.UTF_8))
            }
        }

    @Test
    fun `resize is not a byte barrier and close discards admitted pending work`() =
        runTest {
            val connector = MockConnector()
            session(connector).use { session ->
                session.start(10, 3)
                assertEquals(ACCEPTED, session.submitBytes(byteArrayOf(1)))
                assertEquals(ACCEPTED, session.submitInput(listOf(TerminalKeyEvent.text("x"))))
                session.resizeViewport(12, 4)
                assertEquals(12 to 4, connector.resizeCalls.last())
                assertArrayEquals(byteArrayOf(), connector.writtenBytes)
                session.close()
                assertEquals(CLOSED, session.submitBytes(byteArrayOf(2)))
                runCurrent()
                assertArrayEquals(byteArrayOf(), connector.writtenBytes)
            }
        }

    @Test
    fun `write failure retains its cause and discards the accepted tail without retry`() =
        runTest {
            val delegate = MockConnector()
            val failure = IOException("failed after prefix")
            val connector =
                object : TerminalConnector by delegate {
                    override fun write(
                        bytes: ByteArray,
                        offset: Int,
                        length: Int,
                    ) {
                        delegate.write(bytes, offset, 1)
                        throw failure
                    }
                }
            session(connector).use { session ->
                session.start(10, 3)
                assertEquals(ACCEPTED, session.submitBytes("\u001b[24~e".toByteArray()))
                assertEquals(ACCEPTED, session.submitInput(listOf(TerminalKeyEvent.text("discard"))))
                runCurrent()
                assertSame(failure, session.failure)
                assertArrayEquals(byteArrayOf(27), delegate.writtenBytes)
                assertEquals(CLOSED, session.submitInput(TerminalKeyEvent.text("later")))
                assertEquals(1, delegate.closeCount)
            }
        }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `active compound stays contiguous across chunks or stops on close`(closeDuringWrite: Boolean) =
        runTest {
            val delegate = MockConnector()
            lateinit var session: TerminalSession
            var firstWrite = true
            val connector =
                object : TerminalConnector by delegate {
                    override fun write(
                        bytes: ByteArray,
                        offset: Int,
                        length: Int,
                    ) {
                        delegate.write(bytes, offset, length)
                        if (firstWrite) {
                            firstWrite = false
                            // Admit competitors after the compound has begun writing.
                            assertEquals(ACCEPTED, session.submitBytes("raw".toByteArray()))
                            assertEquals(ACCEPTED, session.submitInput(TerminalKeyEvent.text("key")))
                            delegate.feedFromHost("\u001b[5n".toByteArray())
                            if (closeDuringWrite) session.close()
                        }
                    }
                }
            session = session(connector)
            session.use {
                session.start(10, 3)
                val text = "x".repeat(40000)
                assertEquals(
                    ACCEPTED,
                    session.submitInput(
                        listOf(
                            TerminalKeyEvent.codepoint('b'.code),
                            TerminalPasteEvent(text),
                            TerminalKeyEvent.text("end"),
                        ),
                    ),
                )
                runCurrent()
                assertEquals(
                    if (closeDuringWrite) "b" else "b" + text + "endrawkey\u001b[0n",
                    delegate.writtenBytes.toString(Charsets.UTF_8),
                )
                assertNull(session.failure)
            }
        }

    @ParameterizedTest
    @ValueSource(strings = ["raw", "key", "compound"])
    fun `eager writer never holds admission monitor during connector calls`(kind: String) =
        runTest {
            val delegate = MockConnector()
            lateinit var session: TerminalSession
            val connector =
                object : TerminalConnector by delegate {
                    override fun write(
                        bytes: ByteArray,
                        offset: Int,
                        length: Int,
                    ) {
                        SessionTestThread("policy-during-write") {
                            session.setInputPolicy(TerminalInputPolicy())
                        }.use { it.awaitCompletion() }
                        delegate.write(bytes, offset, length)
                    }
                }
            session =
                TerminalSession.create(
                    TerminalBuffers.create(10, 3),
                    connector,
                    workerDispatcher = StandardTestDispatcher(testScheduler),
                    ioDispatcher = UnconfinedTestDispatcher(testScheduler),
                )
            session.use {
                session.start(10, 3)
                val result =
                    when (kind) {
                        "raw" -> session.submitBytes("x".toByteArray())
                        "key" -> session.submitInput(TerminalKeyEvent.text("x"))
                        else -> session.submitInput(listOf(TerminalPasteEvent("x")))
                    }
                assertEquals(ACCEPTED, result)
                assertNull(session.failure)
                assertEquals("x", delegate.writtenBytes.toString(Charsets.UTF_8))
            }
        }

    @Test
    fun `shutdown rejects concurrent producers before closed state publication`() =
        runTest {
            val delegate = MockConnector()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val connector =
                object : TerminalConnector by delegate {
                    override fun close() {
                        entered.countDown()
                        release.await()
                        delegate.close()
                    }
                }
            session(connector).use { session ->
                session.start(10, 3)
                val closing = SessionTestThread("closing-session") { session.close() }
                try {
                    assertTrue(entered.await(10, TimeUnit.SECONDS))
                    assertSame(TerminalSessionState.Running, session.state.value)
                    assertEquals(CLOSED, session.submitBytes("x".toByteArray()))
                    assertEquals(CLOSED, session.submitInput(listOf(TerminalKeyEvent.text("x"))))
                } finally {
                    release.countDown()
                    closing.close()
                }
                runCurrent()
                assertTrue(session.state.value is TerminalSessionState.Closed)
                assertArrayEquals(byteArrayOf(), delegate.writtenBytes)
            }
        }

    @Test
    fun `response denial suppresses replies while exact host input stays exact`() =
        runTest {
            val connector = MockConnector()
            session(connector).use { session ->
                session.start(10, 3)
                session.setHostPolicy(HostPolicy(terminalResponsePolicy = HostControlPolicy.DENY))
                connector.feedFromHost("\u001b[5n\u001bP\$qunknown\u001b\\".toByteArray())
                assertEquals(ACCEPTED, session.submitBytes("\u001b[24~e".toByteArray()))
                runCurrent()
                assertEquals("\u001b[24~e", connector.writtenBytes.toString(Charsets.UTF_8))
            }
        }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `empty admissions preserve startup while host input cancels it`(compound: Boolean) =
        runTest {
            val connector = MockConnector()
            val ready = MutableStateFlow(false)
            val dispatcher = StandardTestDispatcher(testScheduler)
            TerminalSession
                .create(
                    TerminalBuffers.create(10, 3),
                    connector,
                    workerDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                    startupCommand = TerminalStartupCommand("startup"),
                    shellIntegration = TerminalShellIntegrationFactory.host(TerminalShellIntegrationState(), promptReady = ready),
                ).use { session ->
                    session.start(10, 3)
                    assertEquals(ACCEPTED, session.submitBytes(byteArrayOf()))
                    assertEquals(ACCEPTED, session.submitInput(emptyList()))
                    assertEquals(TerminalStartupCommandStatus.WAITING, session.startupCommandStatus?.value)
                    val result =
                        if (compound) {
                            session.submitInput(listOf(TerminalKeyEvent.text("x")))
                        } else {
                            session.submitBytes("x".toByteArray())
                        }
                    assertEquals(ACCEPTED, result)
                    assertEquals(TerminalStartupCommandStatus.CANCELLED_BY_INPUT, session.startupCommandStatus?.value)
                    ready.value = true
                    runCurrent()
                    assertEquals("x", connector.writtenBytes.toString(Charsets.UTF_8))
                }
        }

    private fun TestScope.session(
        connector: TerminalConnector,
        customEncoder: Boolean = false,
    ): TerminalSession =
        TerminalSession.create(
            TerminalBuffers.create(10, 3),
            connector,
            inputEncoderFactory = if (customEncoder) TerminalInputEncoderFactory(TerminalInputEncoders::create) else null,
            workerDispatcher = StandardTestDispatcher(testScheduler),
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )
}
