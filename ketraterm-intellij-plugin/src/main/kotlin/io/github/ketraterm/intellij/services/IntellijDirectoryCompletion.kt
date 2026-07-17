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
package io.github.ketraterm.intellij.services

import io.github.ketraterm.completion.api.TerminalDirectoryListingRequest
import io.github.ketraterm.completion.api.TerminalFileEntry
import io.github.ketraterm.completion.api.TerminalFileSystemProvider
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Bounded scheduling seam shared by asynchronous IntelliJ completion providers. */
internal fun interface IntellijCompletionLoadScheduler {
    /**
     * Attempts to enqueue [work] without blocking the caller.
     *
     * @param work suspending load operation owned by the receiving scheduler.
     * @return `true` when accepted, or `false` when closed or capacity-limited.
     */
    fun schedule(work: suspend () -> Unit): Boolean
}

/**
 * Session-local non-blocking filesystem provider backed by immutable snapshots.
 *
 * [listDirectory] performs path resolution and synchronized map lookups only;
 * blocking scans run through [scheduler]. Requests are generation-stamped, so
 * an older scan cannot publish after the active request changes. Failed scans
 * clear their in-flight marker and can be retried. Ready snapshots are bounded
 * by an access-ordered LRU and expire according to [snapshotTtlNanos].
 *
 * @property scheduler bounded asynchronous work scheduler.
 * @property onSnapshotChanged callback invoked on the scheduler worker after an
 * active snapshot is published.
 * @property resolver pure, authority-preserving path resolver.
 * @property scanner blocking bounded directory scanner.
 * @property nanoTime monotonic clock used only for snapshot expiry.
 * @property snapshotTtlNanos positive lifetime of a ready snapshot.
 * @property snapshotCapacity positive maximum number of retained ready snapshots.
 * @throws IllegalArgumentException if [snapshotTtlNanos] or [snapshotCapacity]
 * is not positive.
 */
internal class IntellijAsyncFileSystemProvider(
    private val scheduler: IntellijCompletionLoadScheduler,
    private val onSnapshotChanged: () -> Unit,
    private val resolver: IntellijCompletionPathResolver = IntellijCompletionPathResolver(),
    private val scanner: IntellijDirectoryScanner = BoundedIntellijDirectoryScanner(),
    private val nanoTime: () -> Long = System::nanoTime,
    private val snapshotTtlNanos: Long = TimeUnit.SECONDS.toNanos(DEFAULT_SNAPSHOT_TTL_SECONDS),
    private val snapshotCapacity: Int = DEFAULT_SNAPSHOT_CAPACITY,
) : TerminalFileSystemProvider,
    AutoCloseable {
    private val lock = Any()
    private val closed = AtomicBoolean()
    private val snapshots = LinkedHashMap<QueryKey, ReadySnapshot>(snapshotCapacity, LOAD_FACTOR, true)
    private val inFlightGenerations = HashMap<QueryKey, Long>()
    private var activeKey: QueryKey? = null
    private var activeGeneration = 0L

    init {
        require(snapshotTtlNanos > 0L) { "snapshotTtlNanos must be > 0, was $snapshotTtlNanos" }
        require(snapshotCapacity > 0) { "snapshotCapacity must be > 0, was $snapshotCapacity" }
    }

    /**
     * Returns a ready directory snapshot without performing filesystem I/O.
     *
     * A cache miss schedules one bounded scan and returns immediately. Invalid,
     * remote-authority, or unsupported paths return an empty list and schedule
     * no work.
     *
     * @param request resolved path fragment and entry-name prefix.
     * @return immutable ready snapshot, or an empty list while loading, when
     * unsupported, and after closure.
     */
    override fun listDirectory(request: TerminalDirectoryListingRequest): List<TerminalFileEntry> {
        if (closed.get()) return emptyList()
        val directory = resolver.resolve(request) ?: return activateUnsupportedRequest(request)
        val key = QueryKey(directory, request.entryNamePrefix.lowercase(Locale.ROOT))
        val now = nanoTime()
        var generation = 0L
        var shouldLoad = false
        synchronized(lock) {
            if (key != activeKey) {
                activeKey = key
                activeGeneration++
            }
            generation = activeGeneration
            val ready = snapshots[key]
            if (ready != null && now - ready.createdAtNanos < snapshotTtlNanos) return ready.entries
            if (ready != null) snapshots.remove(key)
            if (inFlightGenerations[key] != generation) {
                inFlightGenerations[key] = generation
                shouldLoad = true
            }
        }
        if (shouldLoad) submitLoad(key, request.entryNamePrefix, generation)
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
        requestedGeneration: Long,
    ) {
        val accepted =
            scheduler.schedule {
                try {
                    val shouldScan =
                        synchronized(lock) {
                            !closed.get() &&
                                    activeKey == key &&
                                    inFlightGenerations[key] == requestedGeneration
                        }
                    if (!shouldScan) return@schedule
                    val entries = scanner.scan(key.directory, entryNamePrefix).toList()
                    var publish = false
                    synchronized(lock) {
                        if (inFlightGenerations[key] == requestedGeneration) {
                            inFlightGenerations.remove(key)
                            if (!closed.get()) {
                                snapshots[key] = ReadySnapshot(entries, nanoTime())
                                trimSnapshots()
                                publish = activeKey == key && activeGeneration == requestedGeneration
                            }
                        }
                    }
                    if (publish) {
                        try {
                            onSnapshotChanged()
                        } catch (_: RuntimeException) {
                            // The owning pane may close while publication is in flight.
                        }
                    }
                } finally {
                    synchronized(lock) {
                        if (inFlightGenerations[key] == requestedGeneration) inFlightGenerations.remove(key)
                    }
                }
            }
        if (!accepted) {
            synchronized(lock) {
                if (inFlightGenerations[key] == requestedGeneration) inFlightGenerations.remove(key)
            }
        }
    }

    private fun trimSnapshots() {
        while (snapshots.size > snapshotCapacity) {
            val iterator = snapshots.entries.iterator()
            iterator.next()
            iterator.remove()
        }
    }

    /**
     * Invalidates pending generations and releases all retained snapshots.
     *
     * Closing is idempotent. Already-running scan work is not interrupted, but
     * its result is prevented from publishing.
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(lock) {
            activeKey = null
            inFlightGenerations.clear()
            snapshots.clear()
        }
    }

    /** Canonical cache key for one resolved directory and normalized prefix. */
    private data class QueryKey(
        val directory: Path,
        val entryNamePrefix: String,
    ) {
        companion object {
            val UNSUPPORTED = QueryKey(Path.of("."), "")
        }
    }

    /** Immutable directory entries and the monotonic time at which they were loaded. */
    private data class ReadySnapshot(
        val entries: List<TerminalFileEntry>,
        val createdAtNanos: Long,
    )

    private companion object {
        private const val DEFAULT_SNAPSHOT_CAPACITY = 32
        private const val DEFAULT_SNAPSHOT_TTL_SECONDS = 2L
        private const val LOAD_FACTOR = 0.75f
    }
}

/**
 * Resolves completion paths without touching the filesystem or losing URI authorities.
 *
 * @property homeDirectory explicit local home used for tilde expansion, or
 * `null` when home expansion is unavailable.
 * @property windows whether drive-root and UNC syntax is accepted.
 */
internal class IntellijCompletionPathResolver(
    private val homeDirectory: Path? = System.getProperty("user.home")?.takeIf(String::isNotBlank)?.let(Path::of),
    private val windows: Boolean = FileSystems.getDefault().separator == "\\",
) {
    /**
     * Resolves the request directory into a normalized absolute local path.
     *
     * @param request directory request produced by the shared path source.
     * @return normalized local path, or `null` for malformed URIs, non-file
     * schemes, remote authorities, unavailable home expansion, or host-incompatible syntax.
     */
    fun resolve(request: TerminalDirectoryListingRequest): Path? {
        val workingDirectory = localWorkingDirectory(request.workingDirectoryUri) ?: return null
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

    private fun localWorkingDirectory(value: String): Path? =
        try {
            val uri = URI(value)
            if (!uri.scheme.equals("file", ignoreCase = true)) return null
            val authority = uri.authority
            if (!authority.isNullOrEmpty() && !authority.equals("localhost", ignoreCase = true)) return null
            val localUri = URI("file", null, uri.path ?: return null, null)
            Paths.get(localUri).toAbsolutePath().normalize()
        } catch (_: Exception) {
            null
        }

    private fun String.hasWindowsDriveRoot(): Boolean =
        length >= 3 && this[0].isLetter() && this[1] == ':' && this[2] == '/'
}

/** Background directory scan seam used by the IntelliJ path provider. */
internal fun interface IntellijDirectoryScanner {
    /**
     * Scans one local directory for names beginning with [entryNamePrefix].
     *
     * Implementations may block and are invoked only by snapshot workers.
     *
     * @param directory normalized absolute local directory.
     * @param entryNamePrefix case-insensitive name prefix.
     * @return bounded, deterministically ordered immutable entries.
     */
    fun scan(
        directory: Path,
        entryNamePrefix: String,
    ): List<TerminalFileEntry>
}

/**
 * Best-effort time-, visit-, and result-bounded local directory scanner.
 *
 * Filesystem failures are isolated per entry where possible and otherwise
 * produce an empty snapshot. The scanner does not follow directory trees.
 *
 * @property maxVisitedEntries positive cap on inspected direct children.
 * @property maxMatchingEntries positive cap on retained matching children.
 * @property scanBudgetNanos positive best-effort monotonic scan budget.
 * @property nanoTime monotonic clock used to enforce [scanBudgetNanos].
 * @throws IllegalArgumentException if any configured bound is not positive.
 */
internal class BoundedIntellijDirectoryScanner(
    private val maxVisitedEntries: Int = DEFAULT_MAX_VISITED_ENTRIES,
    private val maxMatchingEntries: Int = DEFAULT_MAX_MATCHING_ENTRIES,
    private val scanBudgetNanos: Long = TimeUnit.MILLISECONDS.toNanos(DEFAULT_SCAN_BUDGET_MILLIS),
    private val nanoTime: () -> Long = System::nanoTime,
) : IntellijDirectoryScanner {
    init {
        require(maxVisitedEntries > 0) { "maxVisitedEntries must be > 0, was $maxVisitedEntries" }
        require(maxMatchingEntries > 0) { "maxMatchingEntries must be > 0, was $maxMatchingEntries" }
        require(scanBudgetNanos > 0L) { "scanBudgetNanos must be > 0, was $scanBudgetNanos" }
    }

    /**
     * Scans a directory within the configured visit, result, and time bounds.
     *
     * @param directory normalized absolute local directory.
     * @param entryNamePrefix case-insensitive name prefix.
     * @return deterministically ordered entries, or an empty list when the
     * directory is unavailable or its stream cannot be read.
     */
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
