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
import java.util.concurrent.TimeUnit

/**
 * Non-blocking view of one latest keyed immutable value snapshot.
 *
 * [values] only reads ready state and lazily starts one load when needed. A
 * new key cancels that load directly; there is no collector, queue, generation
 * counter, or detached worker.
 */
class TerminalValueSnapshotProvider<K, V>
    internal constructor(
        private val scope: CoroutineScope,
        private val loader: suspend (K) -> List<V>,
        private val onSnapshotChanged: () -> Unit,
        private val onBackgroundFailure: (Throwable) -> Unit,
        private val nanoTime: () -> Long = System::nanoTime,
        private val snapshotTtlNanos: Long = TimeUnit.SECONDS.toNanos(DEFAULT_SNAPSHOT_TTL_SECONDS),
    ) : AutoCloseable {
        private val lock = Any()
        private var closed = false
        private var state: SnapshotState<K, V>? = null

        init {
            require(snapshotTtlNanos > 0L) { "snapshotTtlNanos must be > 0, was $snapshotTtlNanos" }
        }

        /**
         * Returns the ready snapshot and requests a refresh when absent or stale.
         *
         * @param requestedKey key whose snapshot is requested.
         * @return immutable ready values, or an empty list while loading or closed.
         */
        fun values(requestedKey: K): List<V> {
            var loadToCancel: Job? = null
            var loadToStart: Job? = null
            val readySnapshot =
                synchronized(lock) {
                    if (closed || !scope.isActive) return emptyList()
                    var current = state
                    if (current == null || current.key != requestedKey) {
                        loadToCancel = current?.activeLoad
                        current = SnapshotState(requestedKey)
                    }
                    val snapshot = current.snapshot
                    val isFresh = snapshot != null && nanoTime() - current.createdAtNanos < snapshotTtlNanos
                    if (!isFresh && current.activeLoad == null) {
                        val load =
                            scope.launch(start = CoroutineStart.LAZY) {
                                load(requestedKey)
                            }
                        current = current.copy(activeLoad = load)
                        loadToStart = load
                    }
                    state = current
                    snapshot ?: emptyList()
                }

            loadToCancel?.cancel(CancellationException("Completion snapshot key changed"))
            loadToStart?.let { load ->
                if (!load.start()) {
                    synchronized(lock) {
                        val current = state
                        if (current?.activeLoad === load) state = current.copy(activeLoad = null)
                    }
                }
            }
            return readySnapshot
        }

        private suspend fun load(requestedKey: K) {
            val context = currentCoroutineContext()
            val loadJob = checkNotNull(context[Job])
            try {
                val loaded = loader(requestedKey).toList()
                context.ensureActive()
                val publish =
                    synchronized(lock) {
                        val current = state
                        if (current?.activeLoad !== loadJob) {
                            false
                        } else {
                            state =
                                current.copy(
                                    snapshot = loaded,
                                    createdAtNanos = nanoTime(),
                                    activeLoad = null,
                                )
                            true
                        }
                    }
                if (publish) {
                    try {
                        onSnapshotChanged()
                    } catch (failure: RuntimeException) {
                        onBackgroundFailure(failure)
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                onBackgroundFailure(failure)
            } finally {
                synchronized(lock) {
                    val current = state
                    if (current?.activeLoad === loadJob) state = current.copy(activeLoad = null)
                }
            }
        }

        /** Cancels the active load, if any, and releases the ready snapshot. */
        override fun close() {
            val load =
                synchronized(lock) {
                    if (closed) return
                    closed = true
                    state?.activeLoad.also { state = null }
                }
            load?.cancel(CancellationException("Completion snapshot provider closed"))
        }

        private data class SnapshotState<K, V>(
            val key: K,
            val snapshot: List<V>? = null,
            val createdAtNanos: Long = 0L,
            val activeLoad: Job? = null,
        )

        private companion object {
            private const val DEFAULT_SNAPSHOT_TTL_SECONDS = 2L
        }
    }
