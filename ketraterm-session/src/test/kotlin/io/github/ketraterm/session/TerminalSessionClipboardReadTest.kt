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
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource
import kotlin.time.TimeSource

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalSessionClipboardReadTest {
    @Test
    fun `UTF-8 byte limit applies to complete replies and rejected reads release the next query`() =
        runTest {
            val cases =
                listOf(
                    "" to "",
                    "\u00e9\ud83d\ude42" to "w6nwn5mC",
                    "\u00e9\ud83d\ude42x" to "",
                    "\ud800" to "",
                    "ok" to "b2s=",
                )
            var reads = 0
            Fixture(this, limit = 6, reader = TerminalClipboardReader { TerminalClipboardReadResult.Text(cases[reads++].first) }).use { f ->
                val expected = StringBuilder()
                for ((text, base64) in cases) {
                    f.query()
                    runCurrent()
                    expected.append("\u001b]52;c;").append(base64).append("\u001b\\")
                    assertEquals(expected.toString(), f.output(), "UTF-16 length ${text.length}")
                }
                assertEquals(cases.size, reads)
                assertEquals(
                    listOf(
                        TerminalClipboardReadOutcome.SENT,
                        TerminalClipboardReadOutcome.SENT,
                        TerminalClipboardReadOutcome.INVALID_DATA,
                        TerminalClipboardReadOutcome.INVALID_DATA,
                        TerminalClipboardReadOutcome.SENT,
                    ),
                    f.outcomes,
                )
                assertNull(f.session.failure)
            }
        }

    @Test
    fun `failed denied and unavailable providers release the slot for the next successful read`() =
        runTest {
            var reads = 0
            Fixture(
                this,
                reader =
                    TerminalClipboardReader {
                        when (reads++) {
                            0 -> throw IllegalStateException("provider-secret")
                            1 -> TerminalClipboardReadResult.Denied
                            2 -> TerminalClipboardReadResult.Unavailable
                            else -> TerminalClipboardReadResult.Text("ok")
                        }
                    },
            ).use { f ->
                repeat(4) {
                    f.query()
                    runCurrent()
                }
                assertEquals(4, reads)
                assertEquals("\u001b]52;c;\u001b\\".repeat(3) + "\u001b]52;c;b2s=\u001b\\", f.output())
                assertEquals(
                    listOf(
                        TerminalClipboardReadOutcome.FAILED,
                        TerminalClipboardReadOutcome.DENIED,
                        TerminalClipboardReadOutcome.UNAVAILABLE,
                        TerminalClipboardReadOutcome.SENT,
                    ),
                    f.outcomes,
                )
                assertFalse(f.audits.toString().contains("provider-secret"))
                assertNull(f.session.failure)
            }
        }

    @Test
    fun `real OSC queries preserve normalized selectors and accept every byte split`() =
        runTest {
            for (query in listOf("\u001b]52;ppccsp;?\u0007", "\u001b]52;;?\u001b\\")) {
                val bytes = query.toByteArray()
                for (split in 0..bytes.size) {
                    val requests = mutableListOf<TerminalClipboardReadRequest>()
                    Fixture(
                        this,
                        reader =
                            TerminalClipboardReader {
                                requests += it
                                TerminalClipboardReadResult.Text("é🙂\r\n\u001b\u0000")
                            },
                    ).use { f ->
                        f.connector.feedFromHost(bytes, 0, split)
                        f.connector.feedFromHost(bytes, split, bytes.size - split)
                        assertEquals("", f.output())
                        runCurrent()
                        val selectors = if (query.contains("ppccsp")) "pcs" else "c"
                        assertEquals("\u001b]52;" + selectors + ";w6nwn5mCDQobAA==\u001b\\", f.output())
                        assertEquals(selectors, requests.single().selection.value)
                        assertEquals(listOf(TerminalClipboardReadOutcome.SENT), f.outcomes)
                    }
                }
            }
        }

    @Test
    fun `permissions and invalid selectors gate native access and all failure replies`() =
        runTest {
            for (response in HostControlPolicy.entries) {
                for (permission in TerminalClipboardPermission.entries) {
                    var reads = 0
                    Fixture(
                        this,
                        permission,
                        response,
                        reader =
                            TerminalClipboardReader {
                                reads++
                                TerminalClipboardReadResult.Text("a")
                            },
                    ).use { f ->
                        f.query()
                        runCurrent()
                        val allowed = response == HostControlPolicy.ALLOW
                        assertEquals(if (allowed && permission != TerminalClipboardPermission.DENY) 1 else 0, reads)
                        val expected =
                            when {
                                !allowed -> ""
                                permission == TerminalClipboardPermission.DENY -> "\u001b]52;c;\u001b\\"
                                else -> "\u001b]52;c;YQ==\u001b\\"
                            }
                        assertEquals(expected, f.output())
                    }
                }
            }
            Fixture(this, reader = TerminalClipboardReader { fail("Malformed query reached provider") }).use { f ->
                f.connector.feedFromHost("\u001b]52;cx;?\u0007".toByteArray())
                runCurrent()
                assertEquals("", f.output())
                assertTrue(f.outcomes.isEmpty())
                assertEquals(TerminalClipboardDecision.DENIED_MALFORMED_PAYLOAD, f.admissions.single().decision)
            }
        }

    @Test
    fun `unavailable denied oversized malformed and failed reads emit only empty replies`() =
        runTest {
            val results =
                listOf(
                    TerminalClipboardReadResult.Unavailable to TerminalClipboardReadOutcome.UNAVAILABLE,
                    TerminalClipboardReadResult.Denied to TerminalClipboardReadOutcome.DENIED,
                    TerminalClipboardReadResult.Text("abcd") to TerminalClipboardReadOutcome.INVALID_DATA,
                    TerminalClipboardReadResult.Text("\ud800") to TerminalClipboardReadOutcome.INVALID_DATA,
                    TerminalClipboardReadResult.Text("") to TerminalClipboardReadOutcome.SENT,
                )
            for ((result, outcome) in results) {
                Fixture(this, limit = 3, reader = TerminalClipboardReader { result }).use { f ->
                    f.query()
                    runCurrent()
                    assertEquals("\u001b]52;c;\u001b\\", f.output())
                    assertEquals(listOf(outcome), f.outcomes)
                }
            }
            for (reader in listOf(null, TerminalClipboardReader { throw IllegalStateException("clipboard-secret") })) {
                Fixture(this, reader = reader).use { f ->
                    f.query()
                    runCurrent()
                    assertEquals("\u001b]52;c;\u001b\\", f.output())
                    val expected =
                        if (reader == null) {
                            TerminalClipboardReadOutcome.UNAVAILABLE
                        } else {
                            TerminalClipboardReadOutcome.FAILED
                        }
                    assertEquals(listOf(expected), f.outcomes)
                    assertNull(f.session.failure)
                    assertFalse(f.audits.toString().contains("clipboard-secret"))
                }
            }
        }

    @Test
    fun `consent remains pending while keys and core replies continue and excess reads are busy`() =
        runTest {
            val result = CompletableDeferred<TerminalClipboardReadResult>()
            var reads = 0
            Fixture(
                this,
                TerminalClipboardPermission.PROMPT,
                reader =
                    TerminalClipboardReader {
                        assertEquals(TerminalClipboardPermission.PROMPT, it.permission)
                        reads++
                        result.await()
                    },
            ).use { f ->
                f.query()
                runCurrent()
                repeat(10) { f.query() }
                f.session.encodeKey(TerminalKeyEvent.codepoint('x'.code))
                f.connector.feedFromHost("\u001b[5n".toByteArray())
                runCurrent()
                assertEquals(1, reads)
                assertEquals("x\u001b[0n", f.output())
                assertEquals(List(10) { TerminalClipboardReadOutcome.BUSY }, f.outcomes)
                result.complete(TerminalClipboardReadResult.Text("a"))
                runCurrent()
                assertEquals("x\u001b[0n\u001b]52;c;YQ==\u001b\\", f.output())
            }
        }

    @ParameterizedTest
    @ValueSource(strings = ["deny", "ask", "responses", "limit", "close"])
    fun `revocation and close cancel pending access and later allow cannot revive it`(change: String) =
        runTest {
            val result = CompletableDeferred<TerminalClipboardReadResult>()
            var cancelled = false
            Fixture(
                this,
                reader =
                    TerminalClipboardReader {
                        try {
                            result.await()
                        } finally {
                            cancelled = !currentCoroutineContext().isActive
                        }
                    },
            ).use { f ->
                f.query()
                runCurrent()
                when (change) {
                    "deny", "ask" -> {
                        val permission = if (change == "deny") TerminalClipboardPermission.DENY else TerminalClipboardPermission.PROMPT
                        val clipboard = f.policy.clipboardPolicy.copy(readPermission = permission)
                        f.session.setHostPolicy(f.policy.copy(clipboardPolicy = clipboard))
                    }
                    "responses" -> f.session.setHostPolicy(f.policy.copy(terminalResponsePolicy = HostControlPolicy.DENY))
                    "limit" -> f.session.setHostPolicy(f.policy.copy(clipboardPolicy = f.policy.clipboardPolicy.copy(maxDecodedBytes = 1)))
                    "close" -> f.session.close()
                }
                f.session.setHostPolicy(f.policy)
                result.complete(TerminalClipboardReadResult.Text("secret"))
                runCurrent()
                assertTrue(cancelled)
                assertEquals("", f.output())
                assertEquals(listOf(TerminalClipboardReadOutcome.CANCELLED), f.outcomes)
            }
        }

    @Test
    fun `deadline retires data while cancelled native work continues to occupy the slot`() =
        runTest {
            val releaseNative = CompletableDeferred<Unit>()
            var reads = 0
            Fixture(
                this,
                reader =
                    TerminalClipboardReader {
                        reads++
                        withContext(NonCancellable) { releaseNative.await() }
                        TerminalClipboardReadResult.Text("late-secret")
                    },
            ).use { f ->
                try {
                    f.query()
                    runCurrent()
                    advanceTimeBy(7999.milliseconds)
                    runCurrent()
                    assertEquals("", f.output())
                    advanceTimeBy(1.milliseconds)
                    runCurrent()
                    assertEquals("\u001b]52;c;\u001b\\", f.output())
                    assertEquals(listOf(TerminalClipboardReadOutcome.TIMED_OUT), f.outcomes)
                    f.query()
                    runCurrent()
                    assertEquals(1, reads)
                    assertEquals(TerminalClipboardReadOutcome.BUSY, f.outcomes.last())
                    releaseNative.complete(Unit)
                    runCurrent()
                    assertEquals("\u001b]52;c;\u001b\\", f.output())
                    f.query()
                    runCurrent()
                    assertEquals(2, reads)
                } finally {
                    releaseNative.complete(Unit)
                }
            }
        }

    @ParameterizedTest
    @ValueSource(longs = [8099, 8100, 30000])
    fun `timeout reply expires while the idle writer waits for its dispatcher`(resumeAt: Long) =
        runTest {
            val io = StandardTestDispatcher(TestCoroutineScheduler())
            val releaseNative = CompletableDeferred<Unit>()
            var reads = 0
            Fixture(
                this,
                reader =
                    TerminalClipboardReader {
                        reads++
                        withContext(NonCancellable) { releaseNative.await() }
                        TerminalClipboardReadResult.Text("late-secret")
                    },
                ioDispatcher = io,
            ).use { f ->
                try {
                    f.query()
                    io.scheduler.runCurrent()
                    assertEquals(1, reads)
                    advanceTimeBy(8000.milliseconds)
                    runCurrent()
                    assertEquals("", f.output())
                    advanceTimeBy((resumeAt - 8000).milliseconds)
                    releaseNative.complete(Unit)
                    io.scheduler.runCurrent()
                    assertEquals(if (resumeAt < 8100) "\u001b]52;c;\u001b\\" else "", f.output())
                    assertEquals(listOf(TerminalClipboardReadOutcome.TIMED_OUT), f.outcomes)
                } finally {
                    releaseNative.complete(Unit)
                    io.scheduler.runCurrent()
                }
            }
        }

    @ParameterizedTest
    @ValueSource(longs = [8000, 8100, 30000])
    fun `deadline is checked before access when the timer dispatcher has not resumed`(resumeAt: Long) =
        runTest {
            val io = StandardTestDispatcher(TestCoroutineScheduler())
            val clock = TestTimeSource()
            var reads = 0
            Fixture(
                this,
                reader =
                    TerminalClipboardReader {
                        reads++
                        TerminalClipboardReadResult.Text("late")
                    },
                ioDispatcher = io,
                timeSource = clock,
            ).use { f ->
                f.query()
                clock += resumeAt.milliseconds
                io.scheduler.runCurrent()
                assertEquals(0, reads)
                assertEquals(if (resumeAt < 8100) "\u001b]52;c;\u001b\\" else "", f.output())
                assertEquals(listOf(TerminalClipboardReadOutcome.TIMED_OUT), f.outcomes)
                runCurrent()
            }
        }

    @Test
    fun `clipboard reply cannot overtake earlier core replies in the same input chunk`() =
        runTest {
            Fixture(this, permission = TerminalClipboardPermission.DENY).use { f ->
                f.connector.feedFromHost("\u001b[5n\u001b]52;c;?\u0007".toByteArray())
                runCurrent()
                assertEquals("\u001b[0n\u001b]52;c;\u001b\\", f.output())
            }
        }

    @Test
    fun `late provider completion after close cannot reach a replacement session`() =
        runTest {
            val release = CompletableDeferred<Unit>()
            Fixture(
                this,
                reader =
                    TerminalClipboardReader {
                        withContext(NonCancellable) { release.await() }
                        TerminalClipboardReadResult.Text("old-secret")
                    },
            ).use { old ->
                try {
                    old.query()
                    runCurrent()
                    old.session.close()
                    Fixture(this, reader = TerminalClipboardReader { TerminalClipboardReadResult.Text("a") }).use { replacement ->
                        replacement.query()
                        runCurrent()
                        release.complete(Unit)
                        runCurrent()
                        assertEquals("", old.output())
                        assertEquals(listOf(TerminalClipboardReadOutcome.CANCELLED), old.outcomes)
                        assertEquals("\u001b]52;c;YQ==\u001b\\", replacement.output())
                    }
                } finally {
                    release.complete(Unit)
                }
            }
        }

    private class Fixture(
        scope: TestScope,
        permission: TerminalClipboardPermission = TerminalClipboardPermission.ALLOW,
        response: HostControlPolicy = HostControlPolicy.ALLOW,
        limit: Int = TerminalClipboardPolicy.DEFAULT_MAX_DECODED_BYTES,
        reader: TerminalClipboardReader? = null,
        ioDispatcher: CoroutineDispatcher = StandardTestDispatcher(scope.testScheduler),
        timeSource: TimeSource = scope.testScheduler.timeSource,
    ) : AutoCloseable {
        val connector = MockConnector()
        val admissions = mutableListOf<TerminalClipboardAuditEvent>()
        val audits = mutableListOf<TerminalClipboardReadAuditEvent>()
        val outcomes get() = audits.map { it.outcome }
        val policy =
            HostPolicy(
                terminalResponsePolicy = response,
                clipboardPolicy = TerminalClipboardPolicy(readPermission = permission, maxDecodedBytes = limit),
            )
        val session =
            TerminalSession
                .create(
                    TerminalBuffers.create(10, 3),
                    connector,
                    hostEvents =
                        object : HostEventSink by HostEventSink.NONE {
                            override fun terminalClipboardRequest(event: TerminalClipboardAuditEvent) {
                                admissions += event
                            }

                            override fun terminalClipboardReadCompleted(event: TerminalClipboardReadAuditEvent) {
                                audits += event
                            }
                        },
                    hostPolicy = policy,
                    workerDispatcher = StandardTestDispatcher(scope.testScheduler),
                    ioDispatcher = ioDispatcher,
                    clipboardReader = reader,
                    clipboardReadTimeSource = timeSource,
                ).also { it.start(10, 3) }

        fun query() = connector.feedFromHost("\u001b]52;c;?\u0007".toByteArray())

        fun output(): String = connector.writtenBytes.toString(Charsets.US_ASCII)

        override fun close() = session.close()
    }
}
