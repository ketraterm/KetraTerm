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

import io.github.ketraterm.completion.api.TerminalFileEntry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit

/**
 * Best-effort time-, visit-, and result-bounded local directory scanner.
 *
 * @param maxVisitedEntries positive cap on inspected direct children.
 * @param maxMatchingEntries positive cap on retained matches.
 * @param scanBudgetNanos positive best-effort monotonic scan budget.
 * @param nanoTime monotonic clock used to enforce the budget.
 * @param onFailure diagnostic callback for a directory-level scan failure.
 * @param ioDispatcher dispatcher used only for blocking filesystem access.
 * @throws IllegalArgumentException if a capacity or the scan budget is not positive.
 */
class TerminalBoundedDirectoryScanner
    @JvmOverloads
    constructor(
        private val maxVisitedEntries: Int = DEFAULT_MAX_VISITED_ENTRIES,
        private val maxMatchingEntries: Int = DEFAULT_MAX_MATCHING_ENTRIES,
        private val scanBudgetNanos: Long = TimeUnit.MILLISECONDS.toNanos(DEFAULT_SCAN_BUDGET_MILLIS),
        private val nanoTime: () -> Long = System::nanoTime,
        private val onFailure: (Throwable) -> Unit = {},
        private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : TerminalDirectoryScanner {
        @Volatile
        private var cachedSnapshot: DirectorySnapshot? = null

        init {
            require(maxVisitedEntries > 0) { "maxVisitedEntries must be > 0, was $maxVisitedEntries" }
            require(maxMatchingEntries > 0) { "maxMatchingEntries must be > 0, was $maxMatchingEntries" }
            require(scanBudgetNanos > 0L) { "scanBudgetNanos must be > 0, was $scanBudgetNanos" }
        }

        /**
         * Returns a bounded deterministic snapshot or an empty list on failure.
         *
         * @param directory normalized absolute local directory to inspect.
         * @param entryNamePrefix case-insensitive child-name prefix.
         * @return bounded deterministic entries, or an empty list on failure/interruption.
         */
        override suspend fun scan(
            directory: Path,
            entryNamePrefix: String,
        ): List<TerminalFileEntry> =
            runInterruptible(ioDispatcher) {
                val normalizedDirectory = directory.toAbsolutePath().normalize()
                val version = readVersion(normalizedDirectory)
                val cached = cachedSnapshot
                if (version != null && cached?.directory == normalizedDirectory && cached.version == version) {
                    return@runInterruptible cached.entries.matching(entryNamePrefix, maxMatchingEntries)
                }
                scanBlocking(normalizedDirectory, entryNamePrefix, version)
            }

        private fun scanBlocking(
            directory: Path,
            entryNamePrefix: String,
            initialVersion: DirectoryVersion?,
        ): List<TerminalFileEntry> {
            val startedAt = nanoTime()
            val entries = ArrayList<TerminalFileEntry>(minOf(maxVisitedEntries, DEFAULT_MAX_VISITED_ENTRIES))
            var complete = false
            try {
                if (!Files.isDirectory(directory)) return emptyList()
                Files.newDirectoryStream(directory).use { stream ->
                    var visited = 0
                    val iterator = stream.iterator()
                    while (
                        !Thread.currentThread().isInterrupted &&
                        visited < maxVisitedEntries &&
                        nanoTime() - startedAt < scanBudgetNanos &&
                        iterator.hasNext()
                    ) {
                        val child = iterator.next()
                        visited++
                        val name = child.fileName?.toString() ?: continue
                        val attributes =
                            try {
                                Files.readAttributes(child, BasicFileAttributes::class.java)
                            } catch (_: Exception) {
                                continue
                            }
                        val entry = TerminalFileEntry(name, attributes.isDirectory)
                        entries += entry
                    }
                    complete = !iterator.hasNext()
                }
            } catch (failure: Exception) {
                reportFailure(failure)
                return emptyList()
            }
            if (Thread.currentThread().isInterrupted) return emptyList()
            val snapshotEntries = TerminalDirectoryEntrySnapshot(entries)
            val finalVersion = readVersion(directory)
            if (complete && initialVersion != null && finalVersion == initialVersion) {
                cachedSnapshot = DirectorySnapshot(directory, initialVersion, snapshotEntries)
            }
            return snapshotEntries.matching(entryNamePrefix, maxMatchingEntries)
        }

        private fun readVersion(directory: Path): DirectoryVersion? =
            try {
                val attributes = Files.readAttributes(directory, BasicFileAttributes::class.java)
                if (!attributes.isDirectory) null else DirectoryVersion(attributes.fileKey(), attributes.lastModifiedTime().toMillis())
            } catch (_: Exception) {
                null
            }

        private fun reportFailure(failure: Throwable) {
            try {
                onFailure(failure)
            } catch (_: RuntimeException) {
                // A diagnostics sink must not turn a failed completion scan into a caller failure.
            }
        }

        private companion object {
            private const val DEFAULT_MAX_VISITED_ENTRIES = 8_192
            private const val DEFAULT_MAX_MATCHING_ENTRIES = 256
            private const val DEFAULT_SCAN_BUDGET_MILLIS = 50L
        }

        private data class DirectoryVersion(
            val fileKey: Any?,
            val modifiedEpochMillis: Long,
        )

        private data class DirectorySnapshot(
            val directory: Path,
            val version: DirectoryVersion,
            val entries: TerminalDirectoryEntrySnapshot,
        )
    }
