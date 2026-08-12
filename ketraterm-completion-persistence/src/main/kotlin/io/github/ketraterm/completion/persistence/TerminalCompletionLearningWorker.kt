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
package io.github.ketraterm.completion.persistence

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single host-neutral worker for ordered learning mutations and blocking persistence.
 *
 * Hosts submit complete learning transactions—mutation, snapshot publication,
 * persistence, and notification—as one action. This keeps disk I/O off UI
 * threads without layering a second persistence queue beneath the worker.
 *
 * @param threadName diagnostic daemon thread name.
 */
class TerminalCompletionLearningWorker(
    threadName: String,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val executor =
        Executors.newSingleThreadExecutor { action ->
            Thread(action, threadName).apply { isDaemon = true }
        }

    /** Queues [action] after previously submitted learning transactions. */
    fun submit(action: () -> Unit) {
        if (closed.get()) return
        runCatching { executor.execute(action) }
    }

    /** Drains pending work, runs [finalAction], and stops the worker. */
    fun close(finalAction: () -> Unit) {
        if (!closed.compareAndSet(false, true)) return
        runCatching { executor.submit(finalAction).get() }
        executor.shutdown()
        val terminated =
            try {
                executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
        if (!terminated) executor.shutdownNow()
    }

    /** Drains pending work and stops the worker. */
    override fun close() {
        close {}
    }

    private companion object {
        private const val SHUTDOWN_TIMEOUT_SECONDS = 5L
    }
}
