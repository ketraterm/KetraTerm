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
package io.github.ketraterm.app.ui

import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class TerminalBellDispatcherTest {
    @Test
    fun `bursts schedule one UI task and merge flags for each originating pane`() {
        val queued = ArrayDeque<() -> Unit>()
        val deliveries = mutableListOf<List<TerminalBellDispatcher.Request>>()
        val dispatcher = TerminalBellDispatcher({ queued.addLast(it) }, { 0 }) { deliveries += it }
        repeat(100_000) { dispatcher.request("first") }
        dispatcher.request("first", urgent = true)
        dispatcher.request("first", pop = true)
        dispatcher.request("second")
        assertEquals(1, queued.size)
        queued.removeFirst()()
        assertEquals(
            listOf(
                TerminalBellDispatcher.Request("first", urgent = true, pop = true),
                TerminalBellDispatcher.Request("second", urgent = false, pop = false),
            ),
            deliveries.single(),
        )
    }

    @Test
    fun `cooldown starts at actual delivery and permits the exact half second boundary`() {
        var now = Long.MAX_VALUE - 1_000_000_000L
        val queued = ArrayDeque<() -> Unit>()
        var deliveries = 0
        val dispatcher = TerminalBellDispatcher({ queued.addLast(it) }, { now }) { deliveries++ }
        dispatcher.request("pane")
        now += 1_000_000_000L
        queued.removeFirst()()
        now += 499_999_999L
        dispatcher.request("pane")
        assertTrue(queued.isEmpty())
        now++
        dispatcher.request("pane")
        assertEquals(1, queued.size)
        queued.removeFirst()()
        assertEquals(2, deliveries)
    }

    @Test
    fun `pending tab identifiers are bounded even while the UI is stalled`() {
        val queued = ArrayDeque<() -> Unit>()
        var delivered = emptyList<TerminalBellDispatcher.Request>()
        val dispatcher = TerminalBellDispatcher({ queued.addLast(it) }, { 0 }) { delivered = it }
        repeat(100_000) { dispatcher.request("pane-$it") }
        dispatcher.request("pane-0", urgent = true)
        assertEquals(1, queued.size)
        queued.removeFirst()()
        assertEquals(64, delivered.size)
        assertTrue(delivered.first().urgent)
    }

    @Test
    fun `close cancels queued feedback and rejects future bells`() {
        val queued = ArrayDeque<() -> Unit>()
        val dispatcher = TerminalBellDispatcher({ queued.addLast(it) }, { 0 }) { error("Closed window must not deliver") }
        dispatcher.request("pane")
        dispatcher.close()
        queued.removeFirst()()
        dispatcher.request("pane")
        assertTrue(queued.isEmpty())
    }

    @Test
    fun `concurrent producers share one queued task without losing pane identity`() {
        val queued = ConcurrentLinkedQueue<() -> Unit>()
        var delivered = emptyList<TerminalBellDispatcher.Request>()
        val dispatcher = TerminalBellDispatcher({ queued.add(it) }, { 0 }) { delivered = it }
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val jobs =
                (0..3).map { pane ->
                    pool.submit {
                        start.await()
                        repeat(10_000) { dispatcher.request("pane-$pane") }
                    }
                }
            start.countDown()
            jobs.forEach { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, queued.size)
            queued.remove()()
            assertEquals((0..3).map { "pane-$it" }.toSet(), delivered.map { it.tabId }.toSet())
        } finally {
            start.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `dispatch failure does not strand later feedback`() {
        var reject = true
        var deliveries = 0
        val dispatcher = TerminalBellDispatcher({ if (reject) error("dispatch rejected") else it() }, { 0 }) { deliveries++ }
        assertFailsWith<IllegalStateException> { dispatcher.request("pane") }
        reject = false
        dispatcher.request("pane")
        assertEquals(1, deliveries)
    }
}
