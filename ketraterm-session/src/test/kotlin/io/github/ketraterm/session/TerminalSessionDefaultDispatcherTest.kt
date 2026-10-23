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
import io.github.ketraterm.testkit.MockConnector
import io.github.ketraterm.transport.TerminalConnector
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Runs in the defaultDispatcherTest JVM, with its ordinary I/O parallelism set before initialization. */
class TerminalSessionDefaultDispatcherTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `default cleanup releases a write occupying the only ordinary IO worker`(overflow: Boolean) {
        assertEquals("1", System.getProperty("kotlinx.coroutines.io.parallelism"))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writeCompleted = CountDownLatch(1)
        val delegate = MockConnector()
        val connector =
            object : TerminalConnector by delegate {
                override fun write(
                    bytes: ByteArray,
                    offset: Int,
                    length: Int,
                ) {
                    entered.countDown()
                    try {
                        release.await()
                    } finally {
                        writeCompleted.countDown()
                    }
                }

                override fun close() {
                    delegate.close()
                    release.countDown()
                }
            }
        val session = TerminalSession.create(TerminalBuffers.create(10, 3), connector)
        try {
            session.start(10, 3)
            assertEquals(TerminalInputAdmission.ACCEPTED, session.submitBytes(byteArrayOf(65)))
            assertTrue(entered.await(10, TimeUnit.SECONDS), "The ordinary I/O worker did not enter write")
            if (overflow) {
                assertEquals(
                    TerminalInputAdmission.CAPACITY_EXCEEDED,
                    session.submitBytes(ByteArray(OutboundWriter.MAX_QUEUED_BYTES + 1)),
                )
            } else {
                session.requestClose()
            }
            val closed = awaitClosed(session)
            assertEquals(!overflow, closed.event.locallyRequested)
            if (overflow) assertIs<OutboundCapacityException>(closed.event.failure) else assertNull(closed.event.failure)
            assertEquals(1, delegate.closeCount)
            assertTrue(writeCompleted.await(10, TimeUnit.SECONDS), "Connector cleanup did not release its active write")
            assertContentEquals(byteArrayOf(), delegate.writtenBytes)
        } finally {
            release.countDown()
            session.close()
            awaitClosed(session)
        }
    }

    private fun awaitClosed(session: TerminalSession): TerminalSessionState.Closed =
        runBlocking {
            withTimeout(10_000) { session.state.first { it is TerminalSessionState.Closed } as TerminalSessionState.Closed }
        }
}
