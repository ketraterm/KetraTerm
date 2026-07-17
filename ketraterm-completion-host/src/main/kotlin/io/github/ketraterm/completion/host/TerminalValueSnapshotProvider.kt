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

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Generation-safe asynchronous provider for one keyed immutable value snapshot.
 *
 * [values] never invokes [loader] directly. It returns the latest ready values
 * and schedules refresh work when the key changes or the snapshot expires.
 * Failed loads retain the previous ready snapshot and can be retried.
 *
 * @param keyProvider thread-safe supplier for the current snapshot key.
 * @param scheduler bounded non-blocking scheduler.
 * @param loader blocking bounded value loader invoked only by scheduled work.
 * @param onSnapshotChanged callback invoked after active publication.
 * @param nanoTime monotonic clock used for expiry.
 * @param snapshotTtlNanos positive lifetime of a ready snapshot.
 */
class TerminalValueSnapshotProvider<K, V>
@JvmOverloads
constructor(
    private val keyProvider: () -> K,
    private val scheduler: TerminalCompletionLoadScheduler,
    private val loader: (K) -> List<V>,
    private val onSnapshotChanged: () -> Unit,
    private val nanoTime: () -> Long = System::nanoTime,
    private val snapshotTtlNanos: Long = TimeUnit.SECONDS.toNanos(DEFAULT_SNAPSHOT_TTL_SECONDS),
) : AutoCloseable {
    private val lock = Any()
    private val closed = AtomicBoolean()
    private var key: K? = null
    private var hasKey = false
    private var snapshot = emptyList<V>()
    private var createdAtNanos = 0L
    private var hasSnapshot = false
    private var generation = 0L
    private var inFlightLoad: InFlightLoad? = null

    init {
        require(snapshotTtlNanos > 0L) { "snapshotTtlNanos must be > 0, was $snapshotTtlNanos" }
    }

    /** Returns ready values and schedules a refresh when necessary. */
    fun values(): List<V> {
        if (closed.get()) return emptyList()
        val requestedKey = keyProvider()
        val now = nanoTime()
        var loadToSubmit: InFlightLoad? = null
        synchronized(lock) {
            if (!hasKey || requestedKey != key) {
                key = requestedKey
                hasKey = true
                snapshot = emptyList()
                createdAtNanos = 0L
                hasSnapshot = false
                generation++
                inFlightLoad = null
            }
            if (hasSnapshot && now - createdAtNanos < snapshotTtlNanos) return snapshot
            if (inFlightLoad == null) {
                val load = InFlightLoad(generation)
                inFlightLoad = load
                loadToSubmit = load
            }
        }
        loadToSubmit?.let { load -> submitLoad(requestedKey, load) }
        return synchronized(lock) { snapshot }
    }

    private fun submitLoad(
        requestedKey: K,
        load: InFlightLoad,
    ) {
        val accepted =
            scheduler.schedule {
                try {
                    val shouldLoad =
                        synchronized(lock) {
                            val ownsSlot = inFlightLoad === load
                            if (closed.get() || generation != load.generation || key != requestedKey || !ownsSlot) {
                                if (ownsSlot) inFlightLoad = null
                                false
                            } else {
                                true
                            }
                        }
                    if (!shouldLoad) return@schedule
                    val loaded = loader(requestedKey).toList()
                    var publish = false
                    synchronized(lock) {
                        if (inFlightLoad === load) {
                            inFlightLoad = null
                            if (!closed.get() && generation == load.generation && key == requestedKey) {
                                snapshot = loaded
                                createdAtNanos = nanoTime()
                                hasSnapshot = true
                                publish = true
                            }
                        }
                    }
                    if (publish) notifySnapshotChanged()
                } finally {
                    synchronized(lock) {
                        if (inFlightLoad === load) inFlightLoad = null
                    }
                }
            }
        if (!accepted) {
            synchronized(lock) {
                if (inFlightLoad === load) inFlightLoad = null
            }
        }
    }

    private fun notifySnapshotChanged() {
        try {
            onSnapshotChanged()
        } catch (_: RuntimeException) {
            // The owning UI may close while publication is in flight.
        }
    }

    /** Invalidates active work and releases the retained snapshot. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(lock) {
            generation++
            inFlightLoad = null
            snapshot = emptyList()
            hasSnapshot = false
            hasKey = false
            key = null
        }
    }

    private class InFlightLoad(
        val generation: Long,
    )

    private companion object {
        private const val DEFAULT_SNAPSHOT_TTL_SECONDS = 2L
    }
}
