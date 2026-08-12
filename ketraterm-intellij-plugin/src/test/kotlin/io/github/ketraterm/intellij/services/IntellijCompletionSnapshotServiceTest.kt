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
package io.github.ketraterm.intellij.services

import kotlinx.coroutines.*
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Lifecycle tests for IntelliJ's Platform-parented completion snapshot service. */
class IntellijCompletionSnapshotServiceTest {
    /** Verifies that plugin/application scope cancellation reaches active loaders. */
    @Test
    fun `platform parent scope cancellation cancels active snapshot load`() {
        val parentJob = SupervisorJob()
        val service = IntellijCompletionSnapshotService(CoroutineScope(parentJob + Dispatchers.Default))
        val started = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val provider =
            service.createValueProvider<String, String>(
                loader = {
                    started.countDown()
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled.countDown()
                    }
                },
                onSnapshotChanged = {},
            )
        try {
            provider.values("key")
            assertTrue("snapshot load did not start", started.await(5, TimeUnit.SECONDS))

            parentJob.cancel(CancellationException("platform service disposed"))

            assertTrue("snapshot load outlived platform scope", cancelled.await(5, TimeUnit.SECONDS))
        } finally {
            provider.close()
            service.close()
        }
    }
}
