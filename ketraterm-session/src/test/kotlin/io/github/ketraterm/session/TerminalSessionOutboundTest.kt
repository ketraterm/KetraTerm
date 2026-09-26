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
import io.github.ketraterm.input.event.TerminalKey
import io.github.ketraterm.input.event.TerminalKeyEvent
import io.github.ketraterm.input.event.TerminalPasteEvent
import io.github.ketraterm.input.event.TerminalTextReplacementEvent
import io.github.ketraterm.input.policy.BackspacePolicy
import io.github.ketraterm.input.policy.PasteControlPolicy
import io.github.ketraterm.input.policy.PasteLineEndingPolicy
import io.github.ketraterm.input.policy.TerminalInputPolicy
import io.github.ketraterm.testkit.MockConnector
import io.github.ketraterm.transport.TerminalConnector
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalSessionOutboundTest {
    @Test
    fun `queued replacements retain modes policy and Unicode transformations at admission`() =
        runTest {
            val connector = MockConnector()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val policy = TerminalInputPolicy(backspacePolicy = BackspacePolicy.BACKSPACE)
            TerminalSession
                .create(
                    TerminalBuffers.create(10, 3),
                    connector,
                    inputPolicy = policy,
                    workerDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                ).use { session ->
                    session.start(10, 3)
                    connector.feedFromHost("\u001b[?2004h".toByteArray())
                    val prefix = "a".repeat(8191)
                    session.encodeTextReplacement(TerminalTextReplacementEvent(1, 2, prefix + "é🙂\u001b\u0003\u009b\ud800\u0001\r\n"))
                    session.setInputPolicy(
                        policy.copy(
                            backspacePolicy = BackspacePolicy.DELETE,
                            pasteControlPolicy = PasteControlPolicy.STRIP_C0_EXCEPT_TAB_CR_LF,
                            pasteLineEndingPolicy = PasteLineEndingPolicy.LINE_FEED,
                        ),
                    )
                    connector.feedFromHost("\u001b[?2004l\u001b[?67l".toByteArray())
                    session.encodeTextReplacement(TerminalTextReplacementEvent(0, 1, "\u0001Q\r\n"))
                    session.encodeKey(TerminalKeyEvent.key(TerminalKey.BACKSPACE))
                    assertArrayEquals(byteArrayOf(), connector.writtenBytes)
                    runCurrent()
                    assertEquals(
                        "\u001b[3~\u0008\u0008\u001b[200~" + prefix + "é🙂␛␃\\u009b�\u0001\r\n\u001b[201~\u007fQ\n\u007f",
                        connector.writtenBytes.toString(Charsets.UTF_8),
                    )
                }
        }

    @Test
    fun `paste larger than the byte queue streams before later input and replies`() =
        runTest {
            val text = "x".repeat(OutboundWriter.MAX_QUEUED_BYTES + 1)
            val expected = ("\u001b[200~" + text + "\u001b[201~z\u001b[0n").toByteArray()
            val delegate = MockConnector()
            var received = 0
            var largestWrite = 0
            val connector =
                object : TerminalConnector by delegate {
                    override fun write(
                        bytes: ByteArray,
                        offset: Int,
                        length: Int,
                    ) {
                        assertTrue(received + length <= expected.size)
                        assertEquals(-1, java.util.Arrays.mismatch(expected, received, received + length, bytes, offset, offset + length))
                        received += length
                        largestWrite = maxOf(largestWrite, length)
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
                    session.start(10, 3)
                    delegate.feedFromHost("\u001b[?2004h".toByteArray())
                    session.encodePaste(TerminalPasteEvent(text))
                    assertNull(session.failure)
                    session.encodeKey(TerminalKeyEvent.codepoint('z'.code))
                    delegate.feedFromHost("\u001b[5n".toByteArray())
                    assertEquals(0, received)
                    runCurrent()
                    assertEquals(expected.size, received)
                    assertTrue(largestWrite <= 16384)
                    assertFalse(session.isClosed)
                }
        }

    @Test
    fun `input acceptance copies scratch and captures modes before background writing`() =
        runTest {
            val connector = MockConnector()
            val dispatcher = StandardTestDispatcher(testScheduler)
            TerminalSession
                .create(
                    TerminalBuffers.create(10, 3),
                    connector,
                    workerDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                ).use { session ->
                    session.start(10, 3)
                    session.encodeKey(TerminalKeyEvent.codepoint('a'.code))
                    session.encodeKey(TerminalKeyEvent.codepoint('b'.code))
                    connector.feedFromHost("\u001b[?2004h".toByteArray())
                    val text = "é中😀".repeat(5000)
                    session.encodePaste(TerminalPasteEvent(text))
                    session.encodeTextReplacement(TerminalTextReplacementEvent(1, 1, "done"))
                    connector.feedFromHost("\u001b[?2004l\u001b[5n".toByteArray())
                    session.encodeKey(TerminalKeyEvent.codepoint('z'.code))
                    assertArrayEquals(byteArrayOf(), connector.writtenBytes)
                    runCurrent()
                    assertEquals(
                        "ab\u001b[200~" + text + "\u001b[201~\u001b[3~\u007f\u001b[200~done\u001b[201~\u001b[0nz",
                        connector.writtenBytes.toString(Charsets.UTF_8),
                    )
                }
        }

    @Test
    fun `response batches larger than scratch remain contiguous with subsequent input`() =
        runTest {
            val connector = MockConnector()
            val dispatcher = StandardTestDispatcher(testScheduler)
            TerminalSession
                .create(
                    TerminalBuffers.create(10, 3),
                    connector,
                    workerDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                ).use { session ->
                    session.start(10, 3)
                    connector.feedFromHost("\u001b[5n".repeat(800).toByteArray())
                    session.encodeKey(TerminalKeyEvent.codepoint('x'.code))
                    runCurrent()
                    assertEquals("\u001b[0n".repeat(800) + "x", connector.writtenBytes.toString(Charsets.US_ASCII))
                }
        }

    @ParameterizedTest
    @ValueSource(strings = ["paste", "key", "deletions", "operations"])
    fun `admission bounds close the session without publishing partial input`(kind: String) =
        runTest {
            val connector = MockConnector()
            val dispatcher = StandardTestDispatcher(testScheduler)
            TerminalSession
                .create(
                    TerminalBuffers.create(10, 3),
                    connector,
                    workerDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                ).use { session ->
                    session.start(10, 3)
                    connector.feedFromHost("\u001b[?2004h".toByteArray())
                    when (kind) {
                        "paste" -> session.encodePaste(TerminalPasteEvent("x".repeat(OutboundWriter.MAX_BULK_UNITS + 1)))
                        "key" -> session.encodeKey(TerminalKeyEvent.text("x".repeat(OutboundWriter.MAX_QUEUED_BYTES + 1)))
                        "deletions" -> session.encodeTextReplacement(TerminalTextReplacementEvent(Int.MAX_VALUE, Int.MAX_VALUE, ""))
                        "operations" -> repeat(OutboundWriter.MAX_BULK_OPERATIONS + 1) { session.encodePaste(TerminalPasteEvent("x")) }
                    }
                    assertInstanceOf(OutboundCapacityException::class.java, session.failure)
                    assertTrue(session.isClosed)
                    assertEquals(1, connector.closeCount)
                    runCurrent()
                    assertArrayEquals(byteArrayOf(), connector.writtenBytes)
                    session.encodeKey(TerminalKeyEvent.codepoint('a'.code))
                    assertArrayEquals(byteArrayOf(), connector.writtenBytes)
                }
            assertEquals(1, connector.closeCount)
        }

    @Test
    fun `empty text operations do not exhaust admission or emit bytes`() =
        runTest {
            val connector = MockConnector()
            val dispatcher = StandardTestDispatcher(testScheduler)
            TerminalSession
                .create(
                    TerminalBuffers.create(10, 3),
                    connector,
                    workerDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                ).use { session ->
                    session.start(10, 3)
                    repeat(OutboundWriter.MAX_BULK_OPERATIONS + 1) {
                        session.encodePaste(TerminalPasteEvent(""))
                        session.encodeTextReplacement(TerminalTextReplacementEvent(0, 0, ""))
                    }
                    runCurrent()
                    assertFalse(session.isClosed)
                    assertArrayEquals(byteArrayOf(), connector.writtenBytes)
                }
        }

    @Test
    fun `partial transport failure closes once and discards remaining output`() =
        runTest {
            val delegate = MockConnector()
            val failure = IOException("partial write")
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
            val dispatcher = StandardTestDispatcher(testScheduler)
            val session =
                TerminalSession.create(
                    TerminalBuffers.create(10, 3),
                    connector,
                    workerDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                )
            session.use {
                session.start(10, 3)
                session.encodePaste(TerminalPasteEvent("x".repeat(40000)))
                session.encodeKey(TerminalKeyEvent.codepoint('z'.code))
                runCurrent()
                assertSame(failure, session.failure)
                assertEquals("x", delegate.writtenBytes.toString(Charsets.US_ASCII))
                assertEquals(1, delegate.closeCount)
                assertFalse(session.isCoroutineScopeActive)
            }
            assertEquals(1, delegate.closeCount)
        }

    @Test
    fun `close discards output that has not started`() =
        runTest {
            val connector = MockConnector()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val session =
                TerminalSession.create(
                    TerminalBuffers.create(10, 3),
                    connector,
                    workerDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                )
            session.start(10, 3)
            session.encodeKey(TerminalKeyEvent.codepoint('a'.code))
            session.close()
            runCurrent()
            assertArrayEquals(byteArrayOf(), connector.writtenBytes)
            assertEquals(1, connector.closeCount)
        }

    @Test
    fun `blocked transport permits input parser and policy work and preserves operation order`() {
        val text = "x".repeat(40000)
        val expected = "a\u001b[200~" + text + "\u001b[201~" + "\u001b[0n".repeat(800) + "\u001b[?67;1\$y\u0008z"
        val connector = BlockingConnector(expected.length)
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { io ->
            val session =
                TerminalSession.create(
                    TerminalBuffers.create(10, 3),
                    connector,
                    workerDispatcher = StandardTestDispatcher(),
                    ioDispatcher = io,
                )
            session.use {
                try {
                    session.start(10, 3)
                    session.encodeKey(TerminalKeyEvent.codepoint('a'.code))
                    connector.entered.awaitEvent()
                    SessionTestThread("output-producer") {
                        connector.delegate.feedFromHost("\u001b[?2004h".toByteArray())
                        session.encodePaste(TerminalPasteEvent(text))
                        connector.delegate.feedFromHost(("hello" + "\u001b[5n".repeat(800)).toByteArray())
                        session.setPasteControlPolicy(PasteControlPolicy.STRIP_C0_EXCEPT_TAB_CR_LF)
                        session.setInputPolicy(TerminalInputPolicy(backspacePolicy = BackspacePolicy.BACKSPACE))
                        connector.delegate.feedFromHost("\u001b[?67\$p".toByteArray())
                        session.encodeKey(TerminalKeyEvent.key(TerminalKey.BACKSPACE))
                        session.encodeKey(TerminalKeyEvent.codepoint('z'.code))
                    }.use { it.awaitCompletion() }
                    assertEquals("hello", session.terminal.getLineAsString(0))
                    assertEquals(1L, connector.release.count)
                    connector.release.countDown()
                    connector.completed.awaitEvent()
                    assertEquals(expected, connector.writtenText())
                    assertNull(session.failure)
                } finally {
                    connector.release.countDown()
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `close and queue overflow reach connector while its write is blocked`(overflow: Boolean) {
        val connector = BlockingConnector(1)
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { io ->
            val session =
                TerminalSession.create(
                    TerminalBuffers.create(10, 3),
                    connector,
                    workerDispatcher = StandardTestDispatcher(),
                    ioDispatcher = io,
                )
            try {
                session.start(10, 3)
                session.encodeKey(TerminalKeyEvent.codepoint('a'.code))
                connector.entered.awaitEvent()
                SessionTestThread("output-close") {
                    if (overflow) {
                        session.encodePaste(TerminalPasteEvent("x".repeat(OutboundWriter.MAX_BULK_UNITS + 1)))
                    } else {
                        session.close()
                    }
                }.use { it.awaitCompletion() }
                if (overflow) assertInstanceOf(OutboundCapacityException::class.java, session.failure)
                connector.completed.awaitEvent()
                assertTrue(session.isClosed)
                assertEquals(1, connector.delegate.closeCount)
                assertEquals("", connector.writtenText())
            } finally {
                connector.release.countDown()
                session.close()
            }
        }
    }

    @Test
    fun `active bulk write permits producers and preserves its captured framing`() {
        val text = "x".repeat(40000)
        val expected = "\u001b[200~" + text + "\u001b[201~\u001b[0ntailz"
        val connector = BlockingConnector(expected.length)
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { io ->
            val session =
                TerminalSession.create(
                    TerminalBuffers.create(10, 3),
                    connector,
                    workerDispatcher = StandardTestDispatcher(),
                    ioDispatcher = io,
                )
            session.use {
                try {
                    session.start(10, 3)
                    connector.delegate.feedFromHost("\u001b[?2004h".toByteArray())
                    session.encodePaste(TerminalPasteEvent(text))
                    connector.entered.awaitEvent()
                    SessionTestThread("bulk-producer") {
                        connector.delegate.feedFromHost("hello\u001b[?2004l\u001b[5n".toByteArray())
                        session.setPasteControlPolicy(PasteControlPolicy.STRIP_C0_EXCEPT_TAB_CR_LF)
                        session.encodePaste(TerminalPasteEvent("\u0001tail"))
                        session.encodeKey(TerminalKeyEvent.codepoint('z'.code))
                    }.use { it.awaitCompletion() }
                    assertEquals("hello", session.terminal.getLineAsString(0))
                    assertEquals(1L, connector.release.count)
                    connector.release.countDown()
                    connector.completed.awaitEvent()
                    assertEquals(expected, connector.writtenText())
                    assertNull(session.failure)
                } finally {
                    connector.release.countDown()
                }
            }
        }
    }

    @Test
    fun `close stops active bulk encoding and discards following operations`() {
        val connector = BlockingConnector(1)
        val executor = Executors.newSingleThreadExecutor()
        executor.asCoroutineDispatcher().use { io ->
            val session =
                TerminalSession.create(
                    TerminalBuffers.create(10, 3),
                    connector,
                    workerDispatcher = StandardTestDispatcher(),
                    ioDispatcher = io,
                )
            try {
                session.start(10, 3)
                session.encodePaste(TerminalPasteEvent("x".repeat(40000)))
                connector.entered.awaitEvent()
                session.encodePaste(TerminalPasteEvent("later"))
                session.encodeKey(TerminalKeyEvent.codepoint('z'.code))
                SessionTestThread("bulk-close") { session.close() }.use { it.awaitCompletion() }
                connector.completed.awaitEvent()
                executor.submit {}.get(SESSION_THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                assertEquals(1, connector.writeCount)
                assertEquals("", connector.writtenText())
                assertEquals(1, connector.delegate.closeCount)
                assertFalse(session.isCoroutineScopeActive)
            } finally {
                connector.release.countDown()
                session.close()
            }
        }
    }

    private class BlockingConnector(
        private val expectedBytes: Int,
        val delegate: MockConnector = MockConnector(),
    ) : TerminalConnector by delegate {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(1)
        private val output = ByteArrayOutputStream()
        var writeCount = 0
            private set

        @Volatile private var closed = false

        override fun write(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) {
            writeCount++
            entered.countDown()
            release.awaitEvent()
            synchronized(output) {
                if (!closed) output.write(bytes, offset, length)
                if (closed || output.size() == expectedBytes) completed.countDown()
            }
        }

        override fun close() {
            closed = true
            delegate.close()
            release.countDown()
        }

        fun writtenText(): String = synchronized(output) { output.toString(Charsets.UTF_8) }
    }
}

private fun CountDownLatch.awaitEvent() {
    check(await(SESSION_THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "Expected output event did not occur" }
}
