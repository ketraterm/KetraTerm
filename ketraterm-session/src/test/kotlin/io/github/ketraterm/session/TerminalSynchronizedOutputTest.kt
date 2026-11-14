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
import io.github.ketraterm.core.api.TerminalModeSnapshot
import io.github.ketraterm.core.api.TerminalRenderBuffer
import io.github.ketraterm.testkit.MockConnector
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds

class TerminalSynchronizedOutputTest {
    @Test
    fun `synchronized output cannot enter between publication eligibility and frame copy`() {
        val scheduler = TestCoroutineScheduler()
        val snapshotRead = CountDownLatch(1)
        val releaseSnapshot = CountDownLatch(1)
        val armed = AtomicBoolean(false)
        val core = TerminalBuffers.create(20, 3)
        val terminal =
            object : TerminalRenderBuffer by core {
                override fun getModeSnapshot(): TerminalModeSnapshot {
                    val snapshot = core.getModeSnapshot()
                    if (armed.compareAndSet(true, false)) {
                        snapshotRead.countDown()
                        check(releaseSnapshot.await(SESSION_THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "snapshot was not released" }
                    }
                    return snapshot
                }
            }
        val connector = MockConnector()
        val dispatcher = StandardTestDispatcher(scheduler)
        TerminalSession
            .create(
                terminal,
                connector,
                workerDispatcher = dispatcher,
                ioDispatcher = dispatcher,
                cleanupDispatcher = dispatcher,
            ).use { session ->
                session.start(20, 3)
                connector.feedFromHost("old".encodeToByteArray())
                armed.set(true)
                SessionTestThread("render-publication") { scheduler.runCurrent() }.use { render ->
                    try {
                        assertTrue(snapshotRead.await(SESSION_THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                        SessionTestThread("synchronized-output-ingress") {
                            connector.feedFromHost("\u001b[?2026hPARTIAL".encodeToByteArray())
                        }.use { ingress ->
                            try {
                                ingress.awaitBlockedBy(render)
                            } finally {
                                releaseSnapshot.countDown()
                            }
                            ingress.awaitCompletion()
                        }
                        render.awaitCompletion()
                    } finally {
                        releaseSnapshot.countDown()
                    }
                }
                scheduler.advanceTimeBy(TerminalSession.RENDER_PUBLICATION_INTERVAL_MS.milliseconds)
                scheduler.runCurrent()
                assertTrue(session.modeSnapshot.isSynchronizedOutput)
                assertEquals("old", session.readPublishedFrame { String(it.codeWords, 0, it.columns).trimEnd('\u0000') })
                connector.feedFromHost("\u001b[?2026l".encodeToByteArray())
                scheduler.runCurrent()
                assertFalse(session.modeSnapshot.isSynchronizedOutput)
                assertEquals("oldPARTIAL", session.readPublishedFrame { String(it.codeWords, 0, it.columns).trimEnd('\u0000') })
            }
        scheduler.runCurrent()
    }
}
