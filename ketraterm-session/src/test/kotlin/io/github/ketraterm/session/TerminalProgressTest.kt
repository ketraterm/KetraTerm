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
import io.github.ketraterm.host.TerminalTitlePermission
import io.github.ketraterm.host.TerminalTitlePolicy
import io.github.ketraterm.protocol.TerminalProgressState
import io.github.ketraterm.testkit.MockConnector
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalProgressTest {
    @Test
    fun `snapshot validates percentage and canonical hidden and indeterminate values`() {
        assertEquals(TerminalProgressState.HIDDEN, TerminalProgress.HIDDEN.state)
        assertEquals(0, TerminalProgress.HIDDEN.percentage)
        assertEquals(100, TerminalProgress(TerminalProgressState.WARNING, 100).percentage)
        for (value in intArrayOf(Int.MIN_VALUE, -1, 101, Int.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { TerminalProgress(TerminalProgressState.NORMAL, value) }
        }
        assertFailsWith<IllegalArgumentException> { TerminalProgress(TerminalProgressState.HIDDEN, 1) }
        assertFailsWith<IllegalArgumentException> { TerminalProgress(TerminalProgressState.INDETERMINATE, 1) }
    }

    @Test
    fun `retained progress expires at five minutes without requiring collectors`() =
        runTest {
            val tracker = TerminalProgressTracker(backgroundScope) { testScheduler.currentTime * 1_000_000 }
            tracker.update(TerminalProgressState.NORMAL, 50)
            assertEquals(TerminalProgress(TerminalProgressState.NORMAL, 50), tracker.progress.value)
            runCurrent()
            advanceTimeBy(299_999.milliseconds)
            runCurrent()
            assertEquals(50, tracker.progress.value.percentage)
            advanceTimeBy(1.milliseconds)
            runCurrent()
            assertEquals(TerminalProgress.HIDDEN, tracker.progress.value)
            tracker.close()
        }

    @Test
    fun `identical reports renew expiry without duplicate observable states`() =
        runTest {
            val tracker = TerminalProgressTracker(backgroundScope) { testScheduler.currentTime * 1_000_000 }
            val states = mutableListOf<TerminalProgress>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { tracker.progress.collect(states::add) }
            tracker.update(TerminalProgressState.ERROR, 0)
            runCurrent()
            advanceTimeBy(299_999.milliseconds)
            tracker.update(TerminalProgressState.ERROR, 0)
            runCurrent()
            advanceTimeBy(1.milliseconds)
            runCurrent()
            assertEquals(TerminalProgress(TerminalProgressState.ERROR, 0), tracker.progress.value)
            advanceTimeBy(299_999.milliseconds)
            runCurrent()
            assertEquals(TerminalProgress.HIDDEN, tracker.progress.value)
            assertEquals(listOf(TerminalProgress.HIDDEN, TerminalProgress(TerminalProgressState.ERROR, 0), TerminalProgress.HIDDEN), states)
            tracker.close()
        }

    @Test
    fun `clear and a fresh report retire old expiry even when worker has not run`() =
        runTest {
            val tracker = TerminalProgressTracker(backgroundScope) { testScheduler.currentTime * 1_000_000 }
            tracker.update(TerminalProgressState.NORMAL, 100)
            runCurrent()
            advanceTimeBy(299_999.milliseconds)
            tracker.update(TerminalProgressState.HIDDEN, 0)
            tracker.update(TerminalProgressState.INDETERMINATE, 0)
            advanceTimeBy(1.milliseconds)
            runCurrent()
            assertEquals(TerminalProgress(TerminalProgressState.INDETERMINATE, 0), tracker.progress.value)
            tracker.close()
            assertEquals(TerminalProgress.HIDDEN, tracker.progress.value)
            tracker.update(TerminalProgressState.NORMAL, 10)
            runCurrent()
            assertEquals(TerminalProgress.HIDDEN, tracker.progress.value)
        }

    @Test
    fun `report floods retain only latest state and close before worker starts`() =
        runTest {
            val tracker = TerminalProgressTracker(backgroundScope) { testScheduler.currentTime * 1_000_000 }
            repeat(10_000) { tracker.update(TerminalProgressState.NORMAL, it % 101) }
            assertEquals(TerminalProgress(TerminalProgressState.NORMAL, 0), tracker.progress.value)
            tracker.close()
            runCurrent()
            assertEquals(TerminalProgress.HIDDEN, tracker.progress.value)
        }

    @Test
    fun `session retains reports independently of shell metadata and clears on RIS and policy revocation`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val connector = MockConnector()
            TerminalSession
                .create(
                    TerminalBuffers.create(10, 3),
                    connector,
                    workerDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                    cleanupDispatcher = dispatcher,
                ).use { session ->
                    assertEquals(TerminalProgress.HIDDEN, session.progress.value)
                    session.start()
                    connector.feedFromHost("\u001b]9;4;1;100\u0007".encodeToByteArray())
                    assertEquals(TerminalProgress(TerminalProgressState.NORMAL, 100), session.progress.value)
                    connector.feedFromHost("\u001bc".encodeToByteArray())
                    assertEquals(TerminalProgress.HIDDEN, session.progress.value)
                    connector.feedFromHost("\u001b]9;4;4;0\u0007".encodeToByteArray())
                    assertEquals(TerminalProgress(TerminalProgressState.WARNING, 0), session.progress.value)
                    session.setHostPolicy(HostPolicy(titlePolicy = TerminalTitlePolicy(permission = TerminalTitlePermission.DENY)))
                    assertEquals(TerminalProgress(TerminalProgressState.WARNING, 0), session.progress.value)
                    connector.feedFromHost("\u001b]9;4;1;25\u0007".encodeToByteArray())
                    assertEquals(TerminalProgress(TerminalProgressState.NORMAL, 25), session.progress.value)
                    session.setHostPolicy(HostPolicy(progressPolicy = HostControlPolicy.DENY))
                    assertEquals(TerminalProgress.HIDDEN, session.progress.value)
                    connector.feedFromHost("\u001b]9;4;2;50\u0007".encodeToByteArray())
                    assertEquals(TerminalProgress.HIDDEN, session.progress.value)
                    session.setHostPolicy(HostPolicy())
                    assertEquals(TerminalProgress.HIDDEN, session.progress.value)
                    connector.feedFromHost("\u001b]9;4;2;50\u0007".encodeToByteArray())
                    session.requestClose()
                    assertEquals(TerminalProgress.HIDDEN, session.progress.value)
                    runCurrent()
                    assertTrue(session.state.value is TerminalSessionState.Closed)
                }
        }

    @Test
    fun `expiry uses report receipt time even if worker starts late and nanoTime wraps`() =
        runTest {
            var clockNanos = Long.MAX_VALUE - 1_000_000L
            val tracker = TerminalProgressTracker(backgroundScope) { clockNanos }
            tracker.update(TerminalProgressState.NORMAL, 1)
            // Let the expiry worker start only after most of the report lifetime has elapsed.
            clockNanos += 299_999_000_000L
            runCurrent()
            assertEquals(TerminalProgressState.NORMAL, tracker.progress.value.state)
            clockNanos += 1_000_000L
            advanceTimeBy(1.milliseconds)
            runCurrent()
            assertEquals(TerminalProgress.HIDDEN, tracker.progress.value)
            tracker.close()
        }

    @Test
    fun `unconfined subscriber reentry can close without a stale report resurrection`() =
        runTest {
            val tracker = TerminalProgressTracker(backgroundScope) { testScheduler.currentTime * 1_000_000 }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                tracker.progress.collect { if (it.state == TerminalProgressState.NORMAL) tracker.close() }
            }
            tracker.update(TerminalProgressState.NORMAL, 25)
            runCurrent()
            assertEquals(TerminalProgress.HIDDEN, tracker.progress.value)
            tracker.update(TerminalProgressState.WARNING, 90)
            assertEquals(TerminalProgress.HIDDEN, tracker.progress.value)
        }

    @Test
    fun `owner cancellation clears retained progress and retires later reports`() =
        runTest {
            val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val tracker = TerminalProgressTracker(owner) { testScheduler.currentTime * 1_000_000 }
            try {
                tracker.update(TerminalProgressState.NORMAL, 50)
                runCurrent()
                owner.cancel()
                runCurrent()
                assertEquals(TerminalProgress.HIDDEN, tracker.progress.value)
                tracker.update(TerminalProgressState.WARNING, 80)
                assertEquals(TerminalProgress.HIDDEN, tracker.progress.value)
            } finally {
                tracker.close()
                owner.cancel()
            }
        }

    @Test
    fun `owner cancellation before first report retires lazy expiry worker`() =
        runTest {
            val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val tracker = TerminalProgressTracker(owner) { testScheduler.currentTime * 1_000_000 }
            owner.cancel()
            runCurrent()
            tracker.update(TerminalProgressState.NORMAL, 50)
            assertEquals(TerminalProgress.HIDDEN, tracker.progress.value)
            tracker.close()
        }

    @Test
    fun `initial title denial and remote closure cannot retain stale progress`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val connector = MockConnector()
            TerminalSession
                .create(
                    TerminalBuffers.create(10, 3),
                    connector,
                    hostPolicy = HostPolicy(progressPolicy = HostControlPolicy.DENY),
                    workerDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                    cleanupDispatcher = dispatcher,
                ).use { session ->
                    session.start()
                    connector.feedFromHost("\u001b]9;4;3\u0007".encodeToByteArray())
                    assertEquals(TerminalProgress.HIDDEN, session.progress.value)
                    session.setHostPolicy(HostPolicy())
                    connector.feedFromHost("\u001b]9;4;3\u0007".encodeToByteArray())
                    assertEquals(TerminalProgressState.INDETERMINATE, session.progress.value.state)
                    connector.simulateClosed(0)
                    assertEquals(TerminalProgress.HIDDEN, session.progress.value)
                }
        }
}
