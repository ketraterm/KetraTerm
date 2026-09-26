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
import io.github.ketraterm.input.event.TerminalKeyEvent
import io.github.ketraterm.testkit.MockConnector
import io.github.ketraterm.transport.TerminalConnector
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalSessionClipboardOrderingTest {
    @ParameterizedTest
    @ValueSource(strings = ["deny", "responses", "limit", "timeout", "close"])
    fun `queued clipboard data is discarded before the first reply byte`(change: String) =
        runTest {
            val connector = GatedConnector(expectedBytes = 2)
            val providerEntered = CountDownLatch(1)
            val audited = CountDownLatch(1)
            val outcome = AtomicReference<TerminalClipboardReadOutcome>()
            val policy = HostPolicy(clipboardPolicy = TerminalClipboardPolicy(readPermission = TerminalClipboardPermission.ALLOW))
            val executor = Executors.newFixedThreadPool(2)
            executor.asCoroutineDispatcher().use { io ->
                val session =
                    TerminalSession.create(
                        TerminalBuffers.create(10, 3),
                        connector,
                        hostPolicy = policy,
                        hostEvents =
                            object : HostEventSink by HostEventSink.NONE {
                                override fun terminalClipboardReadCompleted(event: TerminalClipboardReadAuditEvent) {
                                    outcome.set(event.outcome)
                                    audited.countDown()
                                }
                            },
                        workerDispatcher = StandardTestDispatcher(testScheduler),
                        ioDispatcher = io,
                        clipboardReader =
                            TerminalClipboardReader {
                                providerEntered.countDown()
                                TerminalClipboardReadResult.Text("secret")
                            },
                        clipboardReadTimeSource = testScheduler.timeSource,
                    )
                try {
                    session.start(10, 3)
                    session.encodeKey(TerminalKeyEvent.codepoint('x'.code))
                    connector.entered.awaitEvent()
                    connector.delegate.feedFromHost("\u001b]52;c;?\u0007".toByteArray())
                    providerEntered.awaitEvent()
                    // One executor thread remains inside the gated write. The other
                    // completes the provider/encoding turn before this queued barrier.
                    executor.submit {}.get(SESSION_THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    when (change) {
                        "timeout" -> {
                            advanceTimeBy(8000.milliseconds)
                            runCurrent()
                        }
                        "close" -> SessionTestThread("clipboard-close") { session.close() }.use { it.awaitCompletion() }
                        else -> {
                            val changed =
                                when (change) {
                                    "responses" -> policy.copy(terminalResponsePolicy = HostControlPolicy.DENY)
                                    "limit" -> policy.copy(clipboardPolicy = policy.clipboardPolicy.copy(maxDecodedBytes = 1))
                                    else -> {
                                        val clipboard = policy.clipboardPolicy.copy(readPermission = TerminalClipboardPermission.DENY)
                                        policy.copy(clipboardPolicy = clipboard)
                                    }
                                }
                            SessionTestThread("clipboard-revoke") { session.setHostPolicy(changed) }.use { it.awaitCompletion() }
                            session.setHostPolicy(policy)
                        }
                    }
                    audited.awaitEvent()
                    assertEquals(
                        if (change == "timeout") TerminalClipboardReadOutcome.TIMED_OUT else TerminalClipboardReadOutcome.CANCELLED,
                        outcome.get(),
                    )
                    session.encodeKey(TerminalKeyEvent.codepoint('z'.code))
                    connector.release.countDown()
                    connector.completed.awaitEvent()
                    assertEquals(if (change == "close") "" else "xz", connector.text())
                    assertNull(session.failure)
                } finally {
                    connector.release.countDown()
                    session.close()
                }
            }
        }

    @ParameterizedTest
    @ValueSource(strings = ["deny", "responses", "limit"])
    fun `committed reply completes its framing after policy revocation`(change: String) =
        runTest {
            val text = "x".repeat(65536)
            val expected = "\u001b]52;c;" + Base64.getEncoder().encodeToString(text.toByteArray()) + "\u001b\\z"
            val connector = GatedConnector(expected.length)
            val audited = CountDownLatch(1)
            val outcome = AtomicReference<TerminalClipboardReadOutcome>()
            val policy = HostPolicy(clipboardPolicy = TerminalClipboardPolicy(readPermission = TerminalClipboardPermission.ALLOW))
            Executors.newFixedThreadPool(2).asCoroutineDispatcher().use { io ->
                val session =
                    TerminalSession.create(
                        TerminalBuffers.create(10, 3),
                        connector,
                        hostPolicy = policy,
                        hostEvents =
                            object : HostEventSink by HostEventSink.NONE {
                                override fun terminalClipboardReadCompleted(event: TerminalClipboardReadAuditEvent) {
                                    outcome.set(event.outcome)
                                    audited.countDown()
                                }
                            },
                        workerDispatcher = StandardTestDispatcher(testScheduler),
                        ioDispatcher = io,
                        clipboardReader = TerminalClipboardReader { TerminalClipboardReadResult.Text(text) },
                        clipboardReadTimeSource = testScheduler.timeSource,
                    )
                try {
                    session.start(10, 3)
                    connector.delegate.feedFromHost("\u001b]52;c;?\u0007".toByteArray())
                    connector.entered.awaitEvent()
                    val changed =
                        when (change) {
                            "responses" -> policy.copy(terminalResponsePolicy = HostControlPolicy.DENY)
                            "limit" -> policy.copy(clipboardPolicy = policy.clipboardPolicy.copy(maxDecodedBytes = 1))
                            else -> {
                                val clipboard = policy.clipboardPolicy.copy(readPermission = TerminalClipboardPermission.DENY)
                                policy.copy(clipboardPolicy = clipboard)
                            }
                        }
                    SessionTestThread("clipboard-committed-policy") {
                        session.setHostPolicy(changed)
                        session.encodeKey(TerminalKeyEvent.codepoint('z'.code))
                    }.use { it.awaitCompletion() }
                    advanceTimeBy(9000.milliseconds)
                    runCurrent()
                    connector.release.countDown()
                    connector.completed.awaitEvent()
                    audited.awaitEvent()
                    assertEquals(expected, connector.text())
                    assertEquals(TerminalClipboardReadOutcome.SENT, outcome.get())
                    assertFalse(session.isClosed)
                } finally {
                    connector.release.countDown()
                    session.close()
                }
            }
        }

    @ParameterizedTest
    @ValueSource(strings = ["failure", "close"])
    fun `interrupted clipboard transport never emits later payload chunks or input`(action: String) =
        runTest {
            val delegate = MockConnector()
            val failure = IOException("transport failed")
            var writes = 0
            val outcomes = mutableListOf<TerminalClipboardReadOutcome>()
            var session: TerminalSession? = null
            val connector =
                object : TerminalConnector by delegate {
                    override fun write(
                        bytes: ByteArray,
                        offset: Int,
                        length: Int,
                    ) {
                        writes++
                        delegate.write(bytes, offset, 1)
                        if (action == "failure") throw failure
                        checkNotNull(session).close()
                    }
                }
            val active =
                TerminalSession.create(
                    TerminalBuffers.create(10, 3),
                    connector,
                    hostPolicy = HostPolicy(clipboardPolicy = TerminalClipboardPolicy(readPermission = TerminalClipboardPermission.ALLOW)),
                    hostEvents =
                        object : HostEventSink by HostEventSink.NONE {
                            override fun terminalClipboardReadCompleted(event: TerminalClipboardReadAuditEvent) {
                                outcomes += event.outcome
                            }
                        },
                    workerDispatcher = StandardTestDispatcher(testScheduler),
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                    clipboardReader = TerminalClipboardReader { TerminalClipboardReadResult.Text("x".repeat(65536)) },
                    clipboardReadTimeSource = testScheduler.timeSource,
                )
            session = active
            active.use {
                it.start(10, 3)
                delegate.feedFromHost("\u001b]52;c;?\u0007".toByteArray())
                runCurrent()
                it.encodeKey(TerminalKeyEvent.codepoint('z'.code))
                runCurrent()
                assertEquals(1, writes)
                assertArrayEquals(byteArrayOf(0x1b), delegate.writtenBytes)
                assertTrue(it.isClosed)
                assertEquals(1, delegate.closeCount)
                if (action == "failure") assertSame(failure, it.failure) else assertNull(it.failure)
                assertEquals(
                    listOf(if (action == "failure") TerminalClipboardReadOutcome.FAILED else TerminalClipboardReadOutcome.CANCELLED),
                    outcomes,
                )
            }
        }

    private class GatedConnector(
        private val expectedBytes: Int,
    ) : TerminalConnector {
        val delegate = MockConnector()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(1)
        private val output = ByteArrayOutputStream()
        private var first = true

        @Volatile private var closed = false

        override fun start(listener: io.github.ketraterm.transport.TerminalConnectorListener) = delegate.start(listener)

        override fun resize(
            columns: Int,
            rows: Int,
        ) = delegate.resize(columns, rows)

        override fun write(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) {
            if (first) {
                first = false
                entered.countDown()
                release.awaitEvent()
            }
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

        fun text(): String = synchronized(output) { output.toString(Charsets.US_ASCII) }
    }

    private companion object {
        fun CountDownLatch.awaitEvent() {
            check(await(SESSION_THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "Clipboard output event did not occur" }
        }
    }
}
