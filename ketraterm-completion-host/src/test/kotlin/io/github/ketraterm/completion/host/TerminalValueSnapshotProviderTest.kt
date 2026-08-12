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
package io.github.ketraterm.completion.host

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalValueSnapshotProviderTest {
    @Test
    fun `values returns immediately and reuses a ready snapshot`() =
        runTest {
            var loads = 0
            var publications = 0
            val service = snapshotService()
            val provider =
                service.createValueProvider<String, String>(
                    loader = { key ->
                        loads++
                        listOf("$key-value")
                    },
                    onSnapshotChanged = { publications++ },
                )

            assertTrue(provider.values("key").isEmpty())
            runCurrent()

            assertEquals(listOf("key-value"), provider.values("key"))
            assertEquals(1, loads)
            assertEquals(1, publications)
            provider.close()
            service.close()
        }

    @Test
    fun `repeated reads do not duplicate an active load`() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var loads = 0
            val service = snapshotService()
            val provider =
                service.createValueProvider<String, String>(
                    loader = {
                        loads++
                        started.complete(Unit)
                        release.await()
                        listOf("value")
                    },
                    onSnapshotChanged = {},
                )

            provider.values("key")
            runCurrent()
            started.await()
            repeat(4) { assertTrue(provider.values("key").isEmpty()) }
            release.complete(Unit)
            advanceUntilIdle()

            assertEquals(1, loads)
            assertEquals(listOf("value"), provider.values("key"))
            provider.close()
            service.close()
        }

    @Test
    fun `latest key cancels obsolete work and is the only publication`() =
        runTest {
            val firstStarted = CompletableDeferred<Unit>()
            val firstCancelled = CompletableDeferred<Unit>()
            var publications = 0
            val service = snapshotService()
            val provider =
                service.createValueProvider<String, String>(
                    loader = { key ->
                        if (key == "first") {
                            firstStarted.complete(Unit)
                            try {
                                awaitCancellation()
                            } finally {
                                firstCancelled.complete(Unit)
                            }
                        }
                        listOf("$key-value")
                    },
                    onSnapshotChanged = { publications++ },
                )

            provider.values("first")
            runCurrent()
            firstStarted.await()
            provider.values("second")
            advanceUntilIdle()

            assertTrue(firstCancelled.isCompleted)
            assertEquals(1, publications)
            assertEquals(listOf("second-value"), provider.values("second"))
            provider.close()
            service.close()
        }

    @Test
    fun `failed load is reported and can be retried`() =
        runTest {
            var attempts = 0
            val failures = ArrayList<Throwable>()
            val service = snapshotService(failures::add)
            val provider =
                service.createValueProvider<String, String>(
                    loader = {
                        if (++attempts == 1) error("load failed")
                        listOf("recovered")
                    },
                    onSnapshotChanged = {},
                )

            provider.values("key")
            advanceUntilIdle()
            assertEquals("load failed", failures.single().message)

            assertTrue(provider.values("key").isEmpty())
            advanceUntilIdle()
            assertEquals(listOf("recovered"), provider.values("key"))
            provider.close()
            service.close()
        }

    @Test
    fun `publication callback can request an expired refresh`() =
        runTest {
            var now = 0L
            var loads = 0
            var publications = 0
            lateinit var provider: TerminalValueSnapshotProvider<String, String>
            provider =
                TerminalValueSnapshotProvider(
                    scope = this,
                    loader = { listOf("value-${++loads}") },
                    onSnapshotChanged = {
                        if (++publications == 1) {
                            now = 11L
                            provider.values("key")
                        }
                    },
                    onBackgroundFailure = {},
                    nanoTime = { now },
                    snapshotTtlNanos = 10L,
                )

            provider.values("key")
            advanceUntilIdle()

            assertEquals(listOf("value-2"), provider.values("key"))
            assertEquals(2, publications)
            provider.close()
        }

    @Test
    fun `close cancels active load and discards ready state`() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            var publications = 0
            val service = snapshotService()
            val provider =
                service.createValueProvider<String, String>(
                    loader = {
                        started.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            cancelled.complete(Unit)
                        }
                    },
                    onSnapshotChanged = { publications++ },
                )

            provider.values("key")
            runCurrent()
            started.await()
            provider.close()
            advanceUntilIdle()

            assertTrue(cancelled.isCompleted)
            assertEquals(0, publications)
            assertTrue(provider.values("key").isEmpty())
            service.close()
        }

    private fun TestScope.snapshotService(onBackgroundFailure: (Throwable) -> Unit = {}): TerminalCompletionSnapshotService =
        TerminalCompletionSnapshotService(
            parentScope = this,
            maxConcurrentLoads = 1,
            onBackgroundFailure = onBackgroundFailure,
        )
}
