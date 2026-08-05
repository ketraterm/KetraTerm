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

import io.github.ketraterm.completion.api.TerminalDirectoryListingRequest
import io.github.ketraterm.completion.api.TerminalFileEntry
import io.github.ketraterm.completion.api.TerminalFileSystemProvider
import java.nio.file.Path
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Session-local non-blocking filesystem provider backed by immutable snapshots.
 *
 * A cache miss schedules one bounded scan and returns immediately. Snapshot
 * publication is generation-safe, failed scans clear only their matching
 * in-flight marker, and ready entries are retained in a bounded expiring LRU.
 *
 * @param scheduler bounded non-blocking background scheduler.
 * @param onSnapshotChanged callback invoked after the active snapshot publishes.
 * @param resolver pure authority-preserving path resolver.
 * @param scanner blocking bounded scanner invoked only by scheduled work.
 * @param onBackgroundFailure diagnostic callback for a failed publication callback.
 * @param nanoTime monotonic clock used for expiry.
 * @param snapshotTtlNanos positive lifetime of ready snapshots.
 * @param snapshotCapacity positive maximum retained ready snapshots.
 * @throws IllegalArgumentException if snapshot lifetime or capacity is not positive.
 */
class TerminalAsyncFileSystemProvider
    @JvmOverloads
    constructor(
        private val scheduler: TerminalCompletionLoadScheduler,
        private val onSnapshotChanged: () -> Unit,
        private val resolver: TerminalCompletionPathResolver = TerminalCompletionPathResolver(),
        private val scanner: TerminalDirectoryScanner = TerminalBoundedDirectoryScanner(),
        private val onBackgroundFailure: (Throwable) -> Unit = {},
        private val nanoTime: () -> Long = System::nanoTime,
        private val snapshotTtlNanos: Long = TimeUnit.SECONDS.toNanos(DEFAULT_SNAPSHOT_TTL_SECONDS),
        private val snapshotCapacity: Int = DEFAULT_SNAPSHOT_CAPACITY,
    ) : TerminalFileSystemProvider,
        AutoCloseable {
        private val lock = Any()
        private val closed = AtomicBoolean()
        private val snapshots = LinkedHashMap<QueryKey, ReadySnapshot>(snapshotCapacity, LOAD_FACTOR, true)
        private val inFlightLoads = HashMap<QueryKey, InFlightLoad>()
        private var activeKey: QueryKey? = null
        private var activeGeneration = 0L

        init {
            require(snapshotTtlNanos > 0L) { "snapshotTtlNanos must be > 0, was $snapshotTtlNanos" }
            require(snapshotCapacity > 0) { "snapshotCapacity must be > 0, was $snapshotCapacity" }
        }

        /**
         * Returns a ready snapshot or schedules a background load on a cache miss.
         *
         * @param request lexical path request from the pure completion engine.
         * @return immutable ready entries, or an empty list while unavailable.
         */
        override fun listDirectory(request: TerminalDirectoryListingRequest): List<TerminalFileEntry> {
            if (closed.get()) return emptyList()
            val directory = resolver.resolve(request) ?: return activateUnsupportedRequest(request)
            val key = QueryKey(directory, request.entryNamePrefix.lowercase(Locale.ROOT))
            val now = nanoTime()
            var generation: Long
            var loadToSubmit: InFlightLoad? = null
            synchronized(lock) {
                if (key != activeKey) {
                    activeKey = key
                    activeGeneration++
                }
                generation = activeGeneration
                val ready = snapshots[key]
                if (ready != null && now - ready.createdAtNanos < snapshotTtlNanos) return ready.entries
                if (ready != null) snapshots.remove(key)
                val inFlight = inFlightLoads[key]
                if (inFlight == null) {
                    val load = InFlightLoad(generation)
                    inFlightLoads[key] = load
                    loadToSubmit = load
                } else {
                    inFlight.acceptedGeneration = generation
                }
            }
            loadToSubmit?.let { load -> submitLoad(key, request.entryNamePrefix, load) }
            return emptyList()
        }

        private fun activateUnsupportedRequest(request: TerminalDirectoryListingRequest): List<TerminalFileEntry> {
            val key = QueryKey.UNSUPPORTED.copy(entryNamePrefix = request.directoryPrefix + request.entryNamePrefix)
            synchronized(lock) {
                if (key != activeKey) {
                    activeKey = key
                    activeGeneration++
                }
            }
            return emptyList()
        }

        private fun submitLoad(
            key: QueryKey,
            entryNamePrefix: String,
            load: InFlightLoad,
        ) {
            val accepted =
                scheduler.schedule {
                    try {
                        val shouldScan =
                            synchronized(lock) {
                                val ownsSlot = inFlightLoads[key] === load
                                if (closed.get() || activeKey != key || !ownsSlot) {
                                    if (ownsSlot) inFlightLoads.remove(key)
                                    false
                                } else {
                                    true
                                }
                            }
                        if (!shouldScan) return@schedule
                        val entries = scanner.scan(key.directory, entryNamePrefix).toList()
                        var publish = false
                        synchronized(lock) {
                            if (inFlightLoads[key] === load) {
                                inFlightLoads.remove(key)
                                if (!closed.get()) {
                                    snapshots[key] = ReadySnapshot(entries, nanoTime())
                                    trimSnapshots()
                                    publish = activeKey == key && activeGeneration == load.acceptedGeneration
                                }
                            }
                        }
                        if (publish) notifySnapshotChanged()
                    } finally {
                        synchronized(lock) {
                            if (inFlightLoads[key] === load) inFlightLoads.remove(key)
                        }
                    }
                }
            if (!accepted) {
                synchronized(lock) {
                    if (inFlightLoads[key] === load) inFlightLoads.remove(key)
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
                // A diagnostics sink must not affect completion publication.
            }
        }

        private fun trimSnapshots() {
            while (snapshots.size > snapshotCapacity) {
                val iterator = snapshots.entries.iterator()
                iterator.next()
                iterator.remove()
            }
        }

        /** Invalidates generations and releases retained snapshots idempotently. */
        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            synchronized(lock) {
                activeKey = null
                inFlightLoads.clear()
                snapshots.clear()
            }
        }

        private data class QueryKey(
            val directory: Path,
            val entryNamePrefix: String,
        ) {
            companion object {
                val UNSUPPORTED = QueryKey(Path.of("."), "")
            }
        }

        private data class ReadySnapshot(
            val entries: List<TerminalFileEntry>,
            val createdAtNanos: Long,
        )

        private class InFlightLoad(
            var acceptedGeneration: Long,
        )

        private companion object {
            private const val DEFAULT_SNAPSHOT_CAPACITY = 32
            private const val DEFAULT_SNAPSHOT_TTL_SECONDS = 2L
            private const val LOAD_FACTOR = 0.75f
        }
    }
