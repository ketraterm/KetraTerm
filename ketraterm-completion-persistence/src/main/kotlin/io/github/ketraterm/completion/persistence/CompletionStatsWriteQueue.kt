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

import io.github.ketraterm.completion.model.TerminalCommandCompletionStatsSnapshot
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Single-worker queue that coalesces pending completion-statistics snapshots.
 *
 * At most one drain task is scheduled at a time. If producers enqueue multiple
 * snapshots while a write is pending or active, only the newest pending
 * snapshot is retained. A snapshot already being written is allowed to finish.
 *
 * @param writeSnapshot blocking writer invoked on the queue worker.
 * @param threadName daemon worker thread name.
 * @param closeTimeoutSeconds maximum time to wait for worker shutdown.
 */
internal class CompletionStatsWriteQueue(
    private val writeSnapshot: (TerminalCommandCompletionStatsSnapshot) -> Unit,
    threadName: String = DEFAULT_THREAD_NAME,
    private val closeTimeoutSeconds: Long = DEFAULT_CLOSE_TIMEOUT_SECONDS,
) : AutoCloseable {
    private val lock = Any()
    private val worker =
        Executors.newSingleThreadExecutor { task ->
            Thread(task, threadName).apply { isDaemon = true }
        }
    private var pendingSnapshot: TerminalCommandCompletionStatsSnapshot? = null
    private var drainScheduled = false
    private var closed = false

    /**
     * Queues [snapshot] for eventual writing.
     *
     * Calls after [close] are ignored so shutdown racing with late host
     * feedback cannot throw on a UI thread.
     *
     * @param snapshot complete sanitized snapshot to write.
     */
    fun enqueue(snapshot: TerminalCommandCompletionStatsSnapshot) {
        val shouldSchedule =
            synchronized(lock) {
                if (closed) return
                pendingSnapshot = snapshot
                if (drainScheduled) {
                    false
                } else {
                    drainScheduled = true
                    true
                }
            }
        if (shouldSchedule) worker.execute(::drainPendingSnapshots)
    }

    /** Writes all snapshots that are pending before this call returns. */
    fun flush() {
        val future =
            synchronized(lock) {
                if (closed) {
                    null
                } else {
                    worker.submit(::drainPendingSnapshots)
                }
            }
        future?.get()
    }

    /** Flushes the newest pending snapshot and stops accepting writes. */
    override fun close() {
        val future =
            synchronized(lock) {
                if (closed) {
                    null
                } else {
                    closed = true
                    worker.submit(::drainPendingSnapshots)
                }
            }
        future?.get()
        worker.shutdown()
        if (!worker.awaitTermination(closeTimeoutSeconds, TimeUnit.SECONDS)) {
            worker.shutdownNow()
        }
    }

    private fun drainPendingSnapshots() {
        while (true) {
            val snapshot =
                synchronized(lock) {
                    val snapshot = pendingSnapshot
                    if (snapshot == null) {
                        drainScheduled = false
                        return
                    }
                    pendingSnapshot = null
                    snapshot
                }
            writeSnapshot(snapshot)
        }
    }

    private companion object {
        private const val DEFAULT_THREAD_NAME = "ketraterm-command-completion-stats"
        private const val DEFAULT_CLOSE_TIMEOUT_SECONDS = 5L
    }
}
