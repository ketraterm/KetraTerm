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

import javax.swing.SwingUtilities

/** Window-owned, bounded bell admission. One UI task and at most two deliveries per second. */
internal class TerminalBellDispatcher(
    private val dispatch: (() -> Unit) -> Unit = { SwingUtilities.invokeLater(it) },
    private val nanoTime: () -> Long = System::nanoTime,
    private val deliver: (List<Request>) -> Unit,
) : AutoCloseable {
    private val lock = Any()
    private val pending = LinkedHashMap<String, Request>()
    private var queued = false
    private var closed = false
    private var hasDelivered = false
    private var deliveredAt = 0L

    fun request(
        tabId: String,
        urgent: Boolean = false,
        pop: Boolean = false,
    ) {
        synchronized(lock) {
            if (closed) return
            if (!queued && hasDelivered && nanoTime() - deliveredAt < INTERVAL_NANOS) return
            val previous = pending[tabId]
            if (previous == null) {
                if (pending.size < MAX_PENDING_TABS) pending[tabId] = Request(tabId, urgent, pop)
            } else if (urgent && !previous.urgent || pop && !previous.pop) {
                pending[tabId] = previous.copy(urgent = urgent || previous.urgent, pop = pop || previous.pop)
            }
            if (queued) return
            queued = true
        }
        try {
            dispatch(::flush)
        } catch (failure: Throwable) {
            synchronized(lock) {
                queued = false
                pending.clear()
            }
            throw failure
        }
    }

    private fun flush() {
        val tabs =
            synchronized(lock) {
                queued = false
                if (closed) return
                val tabs = pending.values.toList()
                pending.clear()
                hasDelivered = true
                deliveredAt = nanoTime()
                tabs
            }
        deliver(tabs)
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            pending.clear()
        }
    }

    internal data class Request(
        val tabId: String,
        val urgent: Boolean,
        val pop: Boolean,
    )

    private companion object {
        const val MAX_PENDING_TABS = 64
        const val INTERVAL_NANOS = 500_000_000L
    }
}
