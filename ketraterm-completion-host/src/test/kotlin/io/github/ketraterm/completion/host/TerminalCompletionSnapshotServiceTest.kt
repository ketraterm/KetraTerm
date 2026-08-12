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

import kotlinx.coroutines.*
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalCompletionSnapshotServiceTest {
    @Test
    fun `parent scope cancellation reaches active provider load`() =
        runTest {
            val parentJob = SupervisorJob()
            val parentScope = CoroutineScope(coroutineContext + parentJob)
            val service = TerminalCompletionSnapshotService(parentScope = parentScope)
            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
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
                    onSnapshotChanged = {},
                )

            provider.values("key")
            runCurrent()
            started.await()
            parentJob.cancel(CancellationException("host lifecycle closed"))
            advanceUntilIdle()

            assertTrue(cancelled.isCompleted)
            provider.close()
            service.close()
        }

    @Test
    fun `shared semaphore limits loaders without a second work queue`() =
        runTest {
            val service = TerminalCompletionSnapshotService(parentScope = this, maxConcurrentLoads = 1)
            val firstStarted = CompletableDeferred<Unit>()
            val firstRelease = CompletableDeferred<Unit>()
            val secondStarted = CompletableDeferred<Unit>()
            val first =
                service.createValueProvider<String, String>(
                    loader = {
                        firstStarted.complete(Unit)
                        firstRelease.await()
                        listOf("first")
                    },
                    onSnapshotChanged = {},
                )
            val second =
                service.createValueProvider<String, String>(
                    loader = {
                        secondStarted.complete(Unit)
                        listOf("second")
                    },
                    onSnapshotChanged = {},
                )

            first.values("key")
            runCurrent()
            firstStarted.await()
            second.values("key")
            runCurrent()
            assertFalse(secondStarted.isCompleted)

            firstRelease.complete(Unit)
            advanceUntilIdle()
            assertTrue(secondStarted.isCompleted)

            first.close()
            second.close()
            service.close()
        }
}
