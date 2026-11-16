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
import io.github.ketraterm.input.TerminalInputEncoders
import io.github.ketraterm.input.api.TerminalInputEncoder
import io.github.ketraterm.input.api.TerminalInputEncoderFactory
import io.github.ketraterm.input.event.TerminalPasteEvent
import io.github.ketraterm.testkit.MockConnector
import io.github.ketraterm.transport.TerminalConnector
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

class TerminalSessionWorkerFailureTest {
    @ParameterizedTest
    @CsvSource(
        "connector,exception",
        "connector,cancellation",
        "connector,error",
        "encoder,exception",
        "encoder,cancellation",
        "encoder,error",
    )
    fun `worker failure closes admission preserves cleanup failures and never retries`(
        source: String,
        kind: String,
    ) {
        val failure =
            when (kind) {
                "exception" -> IOException("worker failed")
                "cancellation" -> CancellationException("worker cancelled")
                else -> AssertionError("worker assertion failed")
            }
        val cleanupFailure = IOException("connector cleanup failed")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor =
            Executors.newSingleThreadExecutor { action ->
                Thread(action, "terminal-failed-worker-test").apply {
                    isDaemon = true
                }
            }
        val delegate = MockConnector()
        var writes = 0
        var encodings = 0

        fun failOutput(): Nothing {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "The test did not release the worker" }
            throw failure
        }

        val connector =
            object : TerminalConnector by delegate {
                override fun write(
                    bytes: ByteArray,
                    offset: Int,
                    length: Int,
                ) {
                    writes++
                    delegate.write(bytes, offset, 1)
                    failOutput()
                }

                override fun close() {
                    delegate.close()
                    throw cleanupFailure
                }
            }
        val factory =
            if (source == "encoder") {
                TerminalInputEncoderFactory { modes, output, policy ->
                    val encoder = TerminalInputEncoders.create(modes, output, policy)
                    object : TerminalInputEncoder by encoder {
                        override fun encodePaste(event: TerminalPasteEvent) {
                            encodings++
                            failOutput()
                        }
                    }
                }
            } else {
                null
            }

        fun probe() =
            runTest {
                withContext(Dispatchers.IO) {
                    try {
                        executor.asCoroutineDispatcher().use { dispatcher ->
                            TerminalSession
                                .create(
                                    TerminalBuffers.create(10, 3),
                                    connector,
                                    inputEncoderFactory = factory,
                                    workerDispatcher = dispatcher,
                                    ioDispatcher = dispatcher,
                                    cleanupDispatcher = dispatcher,
                                ).use { session ->
                                    try {
                                        session.start(10, 3)
                                        val admission =
                                            if (source == "connector") {
                                                session.submitBytes("first".toByteArray())
                                            } else {
                                                session.submitInput(TerminalPasteEvent("first"))
                                            }
                                        assertEquals(TerminalInputAdmission.ACCEPTED, admission)
                                        assertTrue(entered.await(5, TimeUnit.SECONDS), "The writer did not start")
                                        assertEquals(TerminalInputAdmission.ACCEPTED, session.submitBytes("queued".toByteArray()))
                                        release.countDown()
                                        val closed = withTimeout(5.seconds) { session.state.first { it is TerminalSessionState.Closed } }
                                        assertEquals(TerminalSessionState.Closed(TerminalSessionCloseEvent(null, failure, false)), closed)
                                        assertSame(failure, session.failure)
                                        assertEquals(1, failure.suppressed.count { it === cleanupFailure })
                                        assertTrue(session.isClosed)
                                        assertFalse(session.isCoroutineScopeActive)
                                        assertEquals(TerminalInputAdmission.CLOSED, session.submitBytes("later".toByteArray()))
                                        assertEquals(1, delegate.closeCount)
                                        assertEquals(if (source == "connector") 1 else 0, writes)
                                        assertEquals(if (source == "encoder") 1 else 0, encodings)
                                        assertArrayEquals(
                                            if (source ==
                                                "connector"
                                            ) {
                                                byteArrayOf('f'.code.toByte())
                                            } else {
                                                byteArrayOf()
                                            },
                                            delegate.writtenBytes,
                                        )
                                    } finally {
                                        release.countDown()
                                    }
                                }
                        }
                    } finally {
                        executor.shutdownNow()
                        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "The writer executor did not stop")
                    }
                }
            }

        if (failure is Error) {
            assertSame(failure, assertThrows(AssertionError::class.java) { probe() })
        } else {
            probe()
        }
    }
}
