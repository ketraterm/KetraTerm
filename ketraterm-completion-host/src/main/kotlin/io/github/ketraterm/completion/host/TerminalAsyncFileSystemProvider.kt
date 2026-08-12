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
import kotlinx.coroutines.CoroutineScope
import java.nio.file.Path
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Non-blocking filesystem adapter backed by the shared latest-snapshot loader.
 *
 * A request resolves a local directory and reads the matching ready snapshot.
 * Cache misses merely emit a new load key; changing the active path cancels the
 * obsolete scan instead of filling a second work queue.
 */
class TerminalAsyncFileSystemProvider
    internal constructor(
        scope: CoroutineScope,
        loader: suspend (directory: Path, entryNamePrefix: String) -> List<TerminalFileEntry>,
        onSnapshotChanged: () -> Unit,
        private val resolver: TerminalCompletionPathResolver,
        onBackgroundFailure: (Throwable) -> Unit,
        nanoTime: () -> Long = System::nanoTime,
        snapshotTtlNanos: Long = TimeUnit.SECONDS.toNanos(DEFAULT_SNAPSHOT_TTL_SECONDS),
    ) : TerminalFileSystemProvider,
        AutoCloseable {
        private val snapshots =
            TerminalValueSnapshotProvider<QueryKey, TerminalFileEntry>(
                scope = scope,
                loader = { key -> loader(key.directory, key.entryNamePrefix) },
                onSnapshotChanged = onSnapshotChanged,
                onBackgroundFailure = onBackgroundFailure,
                nanoTime = nanoTime,
                snapshotTtlNanos = snapshotTtlNanos,
            )

        /**
         * Returns the ready listing or an empty list while the latest scan runs.
         *
         * @param request lexical path request from the pure completion engine.
         * @return immutable ready entries, or an empty list when unavailable.
         */
        override fun listDirectory(request: TerminalDirectoryListingRequest): List<TerminalFileEntry> {
            val directory = resolver.resolve(request) ?: return emptyList()
            return snapshots.values(
                QueryKey(
                    directory = directory,
                    entryNamePrefix = request.entryNamePrefix.lowercase(Locale.ROOT),
                ),
            )
        }

        /** Cancels the active scan and releases the ready snapshot. */
        override fun close() {
            snapshots.close()
        }

        private data class QueryKey(
            val directory: Path,
            val entryNamePrefix: String,
        )

        private companion object {
            private const val DEFAULT_SNAPSHOT_TTL_SECONDS = 2L
        }
    }
