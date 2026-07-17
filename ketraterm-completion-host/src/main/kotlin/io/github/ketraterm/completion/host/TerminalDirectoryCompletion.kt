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
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
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
 * @param nanoTime monotonic clock used for expiry.
 * @param snapshotTtlNanos positive lifetime of ready snapshots.
 * @param snapshotCapacity positive maximum retained ready snapshots.
 */
class TerminalAsyncFileSystemProvider
@JvmOverloads
constructor(
    private val scheduler: TerminalCompletionLoadScheduler,
    private val onSnapshotChanged: () -> Unit,
    private val resolver: TerminalCompletionPathResolver = TerminalCompletionPathResolver(),
    private val scanner: TerminalDirectoryScanner = TerminalBoundedDirectoryScanner(),
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
        var generation = 0L
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
        } catch (_: RuntimeException) {
            // The owning UI may close while publication is in flight.
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

/**
 * Resolves lexical completion paths without filesystem access or authority loss.
 *
 * @param homeDirectory explicit local home used for tilde expansion.
 * @param windows whether Windows drive and UNC syntax is accepted.
 */
class TerminalCompletionPathResolver
@JvmOverloads
constructor(
    private val homeDirectory: Path? = System.getProperty("user.home")?.takeIf(String::isNotBlank)?.let(Path::of),
    private val windows: Boolean = FileSystems.getDefault().separator == "\\",
) {
    /**
     * Resolves [request] into a normalized absolute local directory.
     *
     * @return local path, or `null` for malformed, remote, or unsupported input.
     */
    fun resolve(request: TerminalDirectoryListingRequest): Path? {
        val workingDirectory = TerminalLocalFileUriResolver.resolve(request.workingDirectoryUri) ?: return null
        val prefix = request.directoryPrefix
        val resolved =
            when {
                prefix == "~/" -> homeDirectory
                prefix.startsWith("~/") -> homeDirectory?.resolve(prefix.substring(2))
                prefix.hasWindowsDriveRoot() -> if (windows) Path.of(prefix.replace('/', '\\')) else null
                prefix.startsWith("//") -> if (windows) Path.of(prefix.replace('/', '\\')) else null
                prefix.startsWith('/') -> Path.of(prefix)
                prefix.isEmpty() -> workingDirectory
                else -> workingDirectory.resolve(prefix)
            } ?: return null
        return resolved.toAbsolutePath().normalize()
    }

    private fun String.hasWindowsDriveRoot(): Boolean =
        length >= 3 && this[0].isLetter() && this[1] == ':' && this[2] == '/'
}

/** Blocking directory scan contract invoked only by host snapshot workers. */
fun interface TerminalDirectoryScanner {
    /**
     * Scans direct children beginning with [entryNamePrefix].
     *
     * @param directory normalized absolute local directory.
     * @param entryNamePrefix case-insensitive child-name prefix.
     * @return bounded deterministically ordered entries.
     */
    fun scan(
        directory: Path,
        entryNamePrefix: String,
    ): List<TerminalFileEntry>
}

/**
 * Best-effort time-, visit-, and result-bounded local directory scanner.
 *
 * @param maxVisitedEntries positive cap on inspected direct children.
 * @param maxMatchingEntries positive cap on retained matches.
 * @param scanBudgetNanos positive best-effort monotonic scan budget.
 * @param nanoTime monotonic clock used to enforce the budget.
 */
class TerminalBoundedDirectoryScanner
@JvmOverloads
constructor(
    private val maxVisitedEntries: Int = DEFAULT_MAX_VISITED_ENTRIES,
    private val maxMatchingEntries: Int = DEFAULT_MAX_MATCHING_ENTRIES,
    private val scanBudgetNanos: Long = TimeUnit.MILLISECONDS.toNanos(DEFAULT_SCAN_BUDGET_MILLIS),
    private val nanoTime: () -> Long = System::nanoTime,
) : TerminalDirectoryScanner {
    init {
        require(maxVisitedEntries > 0) { "maxVisitedEntries must be > 0, was $maxVisitedEntries" }
        require(maxMatchingEntries > 0) { "maxMatchingEntries must be > 0, was $maxMatchingEntries" }
        require(scanBudgetNanos > 0L) { "scanBudgetNanos must be > 0, was $scanBudgetNanos" }
    }

    /** Returns a bounded deterministic snapshot or an empty list on failure. */
    override fun scan(
        directory: Path,
        entryNamePrefix: String,
    ): List<TerminalFileEntry> {
        val startedAt = nanoTime()
        val entries = PriorityQueue(maxMatchingEntries, ENTRY_ORDER.reversed())
        try {
            if (!Files.isDirectory(directory)) return emptyList()
            Files.newDirectoryStream(directory).use { stream ->
                var visited = 0
                val iterator = stream.iterator()
                while (visited < maxVisitedEntries && nanoTime() - startedAt < scanBudgetNanos && iterator.hasNext()) {
                    val child = iterator.next()
                    visited++
                    val name = child.fileName?.toString() ?: continue
                    if (!name.startsWith(entryNamePrefix, ignoreCase = true)) continue
                    val attributes =
                        try {
                            Files.readAttributes(child, BasicFileAttributes::class.java)
                        } catch (_: Exception) {
                            continue
                        }
                    val entry = TerminalFileEntry(name, attributes.isDirectory)
                    if (entries.size < maxMatchingEntries) {
                        entries += entry
                    } else if (ENTRY_ORDER.compare(entry, entries.peek()) < 0) {
                        entries.remove()
                        entries += entry
                    }
                }
            }
        } catch (_: Exception) {
            return emptyList()
        }
        return ArrayList(entries).apply { sortWith(ENTRY_ORDER) }
    }

    private companion object {
        private const val DEFAULT_MAX_VISITED_ENTRIES = 8_192
        private const val DEFAULT_MAX_MATCHING_ENTRIES = 256
        private const val DEFAULT_SCAN_BUDGET_MILLIS = 50L
        private val ENTRY_ORDER =
            compareBy<TerminalFileEntry, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
                .thenBy { it.name }
    }
}
