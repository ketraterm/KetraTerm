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

import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TerminalCompletionSnapshotServiceTest {
    @Test
    fun `provider-local cancellation does not terminate shared worker`() {
        assertWorkerSurvives(CancellationException("provider load cancelled"))
    }

    @Test
    fun `checked provider failure does not terminate shared worker`() {
        assertWorkerSurvives(IOException("provider load failed"))
    }

    @Test
    fun `checked provider failure is reported without terminating shared worker`() {
        val failure = IOException("provider load failed")
        val reported = AtomicReference<Throwable?>()

        assertWorkerSurvives(failure) { reported.set(it) }

        assertEquals(failure, reported.get())
    }

    private fun assertWorkerSurvives(
        firstFailure: Exception,
        onBackgroundFailure: (Throwable) -> Unit = {},
    ) {
        val service =
            TerminalCompletionSnapshotService(
                workerCount = 1,
                queueCapacity = 2,
                onBackgroundFailure = onBackgroundFailure,
            )
        val attempts = AtomicInteger()
        val firstAttempt = CountDownLatch(1)
        val published = CountDownLatch(1)
        val provider =
            service.createValueProvider<String, String>(
                loader = {
                    if (attempts.incrementAndGet() == 1) {
                        firstAttempt.countDown()
                        throw firstFailure
                    }
                    listOf("recovered")
                },
                onSnapshotChanged = published::countDown,
            )
        try {
            provider.values("key")
            assertTrue(firstAttempt.await(5, TimeUnit.SECONDS), "first provider load did not run")

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (published.count != 0L && System.nanoTime() < deadline) {
                provider.values("key")
                Thread.sleep(5)
            }

            assertEquals(0L, published.count, "shared worker did not process the retry")
            assertEquals(listOf("recovered"), provider.values("key"))
        } finally {
            provider.close()
            service.close()
        }
    }
}
