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

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class TerminalValueSnapshotProviderTest {
    @Test
    fun `initial request is non-blocking and publishes one ready snapshot`() {
        val scheduler = RecordingScheduler()
        var publications = 0
        val provider =
            TerminalValueSnapshotProvider(
                keyProvider = { "first" },
                scheduler = scheduler,
                loader = { key -> listOf("$key-value") },
                onSnapshotChanged = { publications++ },
                snapshotTtlNanos = Long.MAX_VALUE,
            )

        assertTrue(provider.values().isEmpty())
        assertEquals(1, scheduler.pendingCount)
        scheduler.runNext()

        assertEquals(1, publications)
        assertEquals(listOf("first-value"), provider.values())
    }

    @Test
    fun `key change rejects stale publication`() {
        val scheduler = RecordingScheduler()
        var key = "first"
        var publications = 0
        val provider =
            TerminalValueSnapshotProvider(
                keyProvider = { key },
                scheduler = scheduler,
                loader = { value -> listOf("$value-value") },
                onSnapshotChanged = { publications++ },
                snapshotTtlNanos = Long.MAX_VALUE,
            )

        provider.values()
        key = "second"
        provider.values()
        scheduler.runNext()
        assertEquals(0, publications)
        scheduler.runNext()

        assertEquals(1, publications)
        assertEquals("second-value", provider.values().single())
    }

    @Test
    fun `throwing loader permits retry`() {
        val scheduler = RecordingScheduler()
        var attempts = 0
        val provider =
            TerminalValueSnapshotProvider(
                keyProvider = { "key" },
                scheduler = scheduler,
                loader = {
                    attempts++
                    if (attempts == 1) error("load failed")
                    listOf("recovered")
                },
                onSnapshotChanged = {},
                snapshotTtlNanos = Long.MAX_VALUE,
            )

        provider.values()
        try {
            scheduler.runNext()
            fail("Expected the first load to fail")
        } catch (expectedFailure: IllegalStateException) {
            assertEquals("load failed", expectedFailure.message)
        }
        assertTrue(provider.values().isEmpty())
        scheduler.runNext()

        assertEquals("recovered", provider.values().single())
    }

    @Test
    fun `completed load cannot clear a refresh scheduled by its callback`() {
        val scheduler = RecordingScheduler()
        var now = 0L
        var loads = 0
        var publications = 0
        lateinit var provider: TerminalValueSnapshotProvider<String, String>
        provider =
            TerminalValueSnapshotProvider(
                keyProvider = { "key" },
                scheduler = scheduler,
                loader = { listOf("value-${++loads}") },
                onSnapshotChanged = {
                    publications++
                    if (publications == 1) {
                        now = 11L
                        provider.values()
                    }
                },
                nanoTime = { now },
                snapshotTtlNanos = 10L,
            )

        provider.values()
        scheduler.runNext()
        assertEquals(1, scheduler.pendingCount)

        assertEquals(listOf("value-1"), provider.values())
        assertEquals(1, scheduler.pendingCount)
        scheduler.runNext()

        assertEquals(listOf("value-2"), provider.values())
        assertEquals(2, loads)
    }

    @Test
    fun `closed provider discards an in-flight result`() {
        val scheduler = RecordingScheduler()
        var publications = 0
        val provider =
            TerminalValueSnapshotProvider(
                keyProvider = { "key" },
                scheduler = scheduler,
                loader = { listOf("value") },
                onSnapshotChanged = { publications++ },
                snapshotTtlNanos = Long.MAX_VALUE,
            )

        provider.values()
        provider.close()
        scheduler.runNext()

        assertEquals(0, publications)
        assertTrue(provider.values().isEmpty())
    }

    private class RecordingScheduler : TerminalCompletionLoadScheduler {
        private val tasks = ArrayDeque<suspend () -> Unit>()

        val pendingCount: Int
            get() = tasks.size

        override fun schedule(work: suspend () -> Unit): Boolean {
            tasks.addLast(work)
            return true
        }

        fun runNext() {
            runBlocking { tasks.removeFirst().invoke() }
        }
    }
}
