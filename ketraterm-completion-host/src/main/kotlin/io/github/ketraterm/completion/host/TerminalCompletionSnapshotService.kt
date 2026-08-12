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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Lifecycle owner for asynchronous completion snapshots.
 *
 * Provider loads inherit this service's structured lifecycle and share a
 * suspending concurrency limit. Each provider owns at most one load job, with
 * no collector, worker queue, or detached coroutine.
 *
 * @param parentScope optional host lifecycle scope. When present, cancellation
 * of that scope cancels all snapshot work. Otherwise this closeable service
 * owns its root job.
 * @param maxConcurrentLoads positive maximum number of loaders running at once.
 * @param onBackgroundFailure diagnostic callback for failed loaders or
 * publication callbacks.
 * @throws IllegalArgumentException if [maxConcurrentLoads] is not positive.
 */
class TerminalCompletionSnapshotService
    @JvmOverloads
    constructor(
        parentScope: CoroutineScope? = null,
        maxConcurrentLoads: Int = DEFAULT_MAX_CONCURRENT_LOADS,
        private val onBackgroundFailure: (Throwable) -> Unit = {},
    ) : AutoCloseable {
        init {
            require(maxConcurrentLoads > 0) { "maxConcurrentLoads must be > 0, was $maxConcurrentLoads" }
        }

        private val job = Job(parentScope?.coroutineContext?.get(Job))
        private val scope =
            CoroutineScope(
                (parentScope?.coroutineContext ?: Dispatchers.Default) +
                    job +
                    CoroutineName(COROUTINE_NAME),
            )
        private val loadPermits = Semaphore(maxConcurrentLoads)

        /**
         * Creates a provider that scans only its latest directory request.
         *
         * @param onSnapshotChanged callback invoked after publication.
         * @param resolver pure local path resolver.
         * @param scanner suspending bounded directory scanner.
         * @return provider that the owning session must close.
         */
        @JvmOverloads
        fun createDirectoryProvider(
            onSnapshotChanged: () -> Unit,
            resolver: TerminalCompletionPathResolver = TerminalCompletionPathResolver(),
            scanner: TerminalDirectoryScanner = TerminalBoundedDirectoryScanner(onFailure = ::reportBackgroundFailure),
        ): TerminalAsyncFileSystemProvider =
            TerminalAsyncFileSystemProvider(
                scope = scope,
                loader = { directory, prefix -> withLoadPermit { scanner.scan(directory, prefix) } },
                onSnapshotChanged = onSnapshotChanged,
                resolver = resolver,
                onBackgroundFailure = ::reportBackgroundFailure,
            )

        /**
         * Creates a provider for one latest keyed immutable value snapshot.
         *
         * The loader is already suspending and inherits the service dispatcher.
         * Blocking implementations must move only their blocking section to an
         * injected [kotlinx.coroutines.CoroutineDispatcher].
         *
         * @param loader suspending bounded loader.
         * @param onSnapshotChanged callback invoked after publication.
         * @return provider that the owning session must close.
         */
        fun <K, V> createValueProvider(
            loader: suspend (K) -> List<V>,
            onSnapshotChanged: () -> Unit,
        ): TerminalValueSnapshotProvider<K, V> =
            TerminalValueSnapshotProvider(
                scope = scope,
                loader = { key -> withLoadPermit { loader(key) } },
                onSnapshotChanged = onSnapshotChanged,
                onBackgroundFailure = ::reportBackgroundFailure,
            )

        /** Cancels every active or permit-waiting provider load. */
        override fun close() {
            // The explicit overload avoids a cancellation default-argument
            // bridge that is absent from some IntelliJ-bundled runtimes.
            job.cancel(CancellationException("Completion snapshot service closed"))
        }

        private suspend fun <T> withLoadPermit(block: suspend () -> T): T = loadPermits.withPermit { block() }

        private fun reportBackgroundFailure(failure: Throwable) {
            try {
                onBackgroundFailure(failure)
            } catch (_: RuntimeException) {
                // Diagnostics must not affect provider lifecycles.
            }
        }

        private companion object {
            private const val DEFAULT_MAX_CONCURRENT_LOADS = 2
            private const val COROUTINE_NAME = "completion-snapshots"
        }
    }
