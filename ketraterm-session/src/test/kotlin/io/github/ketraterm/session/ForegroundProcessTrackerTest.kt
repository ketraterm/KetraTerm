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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundProcessTrackerTest {
    @Test
    fun `polling is shared subscription bound and clears stale names`() =
        runTest {
            var reads = 0
            var nextName: String? = "vim"
            val tracker =
                ForegroundProcessTracker(backgroundScope, {
                    reads++
                    nextName
                }, StandardTestDispatcher(testScheduler))
            runCurrent()
            advanceTimeBy(5_000.milliseconds)
            assertEquals(0, reads)

            val first = backgroundScope.launch { tracker.name.collect() }
            runCurrent()
            assertEquals("vim", tracker.name.value)
            assertEquals(1, reads)
            val second = backgroundScope.launch { tracker.name.collect() }
            runCurrent()
            assertEquals(1, reads)
            advanceTimeBy(1_000.milliseconds)
            runCurrent()
            assertEquals(2, reads)

            first.cancel()
            runCurrent()
            assertEquals("vim", tracker.name.value)
            assertEquals(2, reads)
            second.cancel()
            runCurrent()
            assertNull(tracker.name.value)
            advanceTimeBy(5_000.milliseconds)
            assertEquals(2, reads)

            nextName = "gradle"
            backgroundScope.launch { tracker.name.collect() }
            runCurrent()
            assertEquals("gradle", tracker.name.value)
            assertEquals(3, reads)
        }

    @Test
    fun `unavailable queries clear the name and subsequent polls recover`() =
        runTest {
            var reads = 0
            val tracker =
                ForegroundProcessTracker(backgroundScope, {
                    when (++reads) {
                        1 -> "vim"
                        2 -> throw IOException("process exited")
                        3 -> throw SecurityException("access denied")
                        4 -> null
                        else -> "git"
                    }
                }, StandardTestDispatcher(testScheduler))
            backgroundScope.launch { tracker.name.collect() }
            runCurrent()
            assertEquals("vim", tracker.name.value)
            repeat(3) {
                advanceTimeBy(1_000.milliseconds)
                runCurrent()
                assertNull(tracker.name.value)
            }
            advanceTimeBy(1_000.milliseconds)
            runCurrent()
            assertEquals("git", tracker.name.value)
        }

    @Test
    fun `session cancellation clears state and prevents new queries even with collectors`() =
        runTest {
            val sessionJob = Job()
            val scope = CoroutineScope(sessionJob + StandardTestDispatcher(testScheduler))
            var reads = 0
            val tracker =
                ForegroundProcessTracker(scope, {
                    reads++
                    "vim"
                }, StandardTestDispatcher(testScheduler))
            backgroundScope.launch { tracker.name.collect() }
            runCurrent()
            assertEquals("vim", tracker.name.value)
            sessionJob.cancel()
            runCurrent()
            assertNull(tracker.name.value)
            backgroundScope.launch { tracker.name.collect() }
            advanceTimeBy(5_000.milliseconds)
            runCurrent()
            assertEquals(1, reads)
            assertNull(tracker.name.value)
        }

    @Test
    fun `query returning after cancellation cannot publish its result`() =
        runTest {
            val sessionJob = Job()
            val scope = CoroutineScope(sessionJob + StandardTestDispatcher(testScheduler))
            val tracker =
                ForegroundProcessTracker(scope, {
                    sessionJob.cancel()
                    "stale"
                }, StandardTestDispatcher(testScheduler))
            val observed = mutableListOf<String?>()
            backgroundScope.launch { tracker.name.collect { observed += it } }
            runCurrent()
            assertEquals(listOf<String?>(null), observed)
            assertNull(tracker.name.value)
        }
}
