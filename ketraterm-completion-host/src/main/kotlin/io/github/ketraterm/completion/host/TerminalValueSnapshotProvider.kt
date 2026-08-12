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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import java.util.concurrent.TimeUnit

/**
 * Non-blocking view of one latest keyed immutable value snapshot.
 *
 * [values] only reads ready state and emits a refresh request. A single
 * `collectLatest` child performs loads, so a new key or provider closure
 * cancels obsolete work through normal structured concurrency.
 */
class TerminalValueSnapshotProvider<K, V>
    internal constructor(
        scope: CoroutineScope,
        private val loader: suspend (K) -> List<V>,
        private val onSnapshotChanged: () -> Unit,
        private val onBackgroundFailure: (Throwable) -> Unit,
        private val nanoTime: () -> Long = System::nanoTime,
        private val snapshotTtlNanos: Long = TimeUnit.SECONDS.toNanos(DEFAULT_SNAPSHOT_TTL_SECONDS),
    ) : AutoCloseable {
        private val lock = Any()
        private val requests = MutableStateFlow<LoadRequest<K>?>(null)
        private var closed = false
        private var key: K? = null
        private var hasKey = false
        private var snapshot = emptyList<V>()
        private var createdAtNanos = 0L
        private var hasSnapshot = false
        private var generation = 0L
        private var loadingGeneration: Long? = null
        private val collectorJob: Job

        init {
            require(snapshotTtlNanos > 0L) { "snapshotTtlNanos must be > 0, was $snapshotTtlNanos" }
            collectorJob =
                scope.launch {
                    requests.filterNotNull().collectLatest(::load)
                }
        }

        /**
         * Returns the ready snapshot and requests a refresh when absent or stale.
         *
         * @param requestedKey key whose snapshot is requested.
         * @return immutable ready values, or an empty list while loading or closed.
         */
        fun values(requestedKey: K): List<V> =
            synchronized(lock) {
                if (closed || !collectorJob.isActive) return emptyList()
                val now = nanoTime()
                if (!hasKey || requestedKey != key) {
                    key = requestedKey
                    hasKey = true
                    snapshot = emptyList()
                    createdAtNanos = 0L
                    hasSnapshot = false
                    loadingGeneration = null
                }
                if (hasSnapshot && now - createdAtNanos < snapshotTtlNanos) return snapshot
                if (loadingGeneration == null) {
                    val request = LoadRequest(requestedKey, ++generation)
                    loadingGeneration = request.generation
                    requests.value = request
                }
                snapshot
            }

        private suspend fun load(request: LoadRequest<K>) {
            try {
                val loaded = loader(request.key).toList()
                currentCoroutineContext().ensureActive()
                val publish =
                    synchronized(lock) {
                        if (closed || loadingGeneration != request.generation || key != request.key) {
                            false
                        } else {
                            snapshot = loaded
                            createdAtNanos = nanoTime()
                            hasSnapshot = true
                            loadingGeneration = null
                            true
                        }
                    }
                if (publish) notifySnapshotChanged()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                reportBackgroundFailure(failure)
            } finally {
                synchronized(lock) {
                    if (loadingGeneration == request.generation) loadingGeneration = null
                }
            }
        }

        private fun notifySnapshotChanged() {
            try {
                onSnapshotChanged()
            } catch (failure: RuntimeException) {
                reportBackgroundFailure(failure)
            }
        }

        private fun reportBackgroundFailure(failure: Throwable) {
            try {
                onBackgroundFailure(failure)
            } catch (_: RuntimeException) {
                // Diagnostics must not affect snapshot publication.
            }
        }

        /** Cancels the collector, active load, and any load waiting for a permit. */
        override fun close() {
            synchronized(lock) {
                if (closed) return
                closed = true
                generation++
                loadingGeneration = null
                snapshot = emptyList()
                hasSnapshot = false
                hasKey = false
                key = null
            }
            collectorJob.cancel(CancellationException(CANCELLATION_MESSAGE))
        }

        private data class LoadRequest<K>(
            val key: K,
            val generation: Long,
        )

        private companion object {
            private const val CANCELLATION_MESSAGE = "Completion value snapshot provider closed"
            private const val DEFAULT_SNAPSHOT_TTL_SECONDS = 2L
        }
    }
