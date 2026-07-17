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
import kotlinx.coroutines.channels.Channel

/**
 * Bounded scheduler seam used by asynchronous completion snapshot providers.
 *
 * Implementations must reject work rather than block when their capacity has
 * been exhausted.
 */
fun interface TerminalCompletionLoadScheduler {
    /**
     * Attempts to enqueue [work] without blocking the caller.
     *
     * @param work suspending snapshot load operation.
     * @return `true` when accepted, or `false` when closed or capacity-limited.
     */
    fun schedule(work: suspend () -> Unit): Boolean
}

/**
 * Shared owner of bounded background completion snapshot work.
 *
 * Providers created by this service retain their own immutable snapshots and
 * generations. The service only owns worker and queue capacity. Closing it
 * cancels queued and active work and is safe to repeat.
 *
 * @param workerCount positive number of concurrent snapshot workers.
 * @param queueCapacity positive number of queued operations accepted without blocking.
 * @param coroutineName diagnostic worker-name prefix.
 */
class TerminalCompletionSnapshotService
@JvmOverloads
constructor(
    workerCount: Int = DEFAULT_WORKER_COUNT,
    queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    coroutineName: String = DEFAULT_COROUTINE_NAME,
) : AutoCloseable {
    private val validatedWorkerCount = workerCount.also { require(it > 0) { "workerCount must be > 0, was $it" } }
    private val validatedQueueCapacity =
        queueCapacity.also { require(it > 0) { "queueCapacity must be > 0, was $it" } }
    private val validatedCoroutineName =
        coroutineName.also { require(it.isNotBlank()) { "coroutineName must not be blank" } }
    private val job = SupervisorJob()
    private val scope =
        CoroutineScope(
            job +
                    Dispatchers.IO.limitedParallelism(validatedWorkerCount) +
                    CoroutineName(validatedCoroutineName),
        )
    private val workQueue = Channel<suspend () -> Unit>(validatedQueueCapacity)
    private val scheduler = TerminalCompletionLoadScheduler { work -> workQueue.trySend(work).isSuccess }

    init {
        repeat(validatedWorkerCount) { workerIndex ->
            scope.launch(CoroutineName("$validatedCoroutineName-worker-$workerIndex")) {
                for (work in workQueue) {
                    try {
                        work()
                    } catch (cancellation: CancellationException) {
                        // A provider may cancel only its own load. Stop the
                        // worker only when the service job was cancelled.
                        currentCoroutineContext().ensureActive()
                    } catch (_: Exception) {
                        // One provider failure must not terminate a shared worker.
                    }
                }
            }
        }
    }

    /**
     * Creates a session-owned asynchronous directory provider.
     *
     * @param onSnapshotChanged callback invoked on a worker after publication.
     * @param resolver pure local path resolver.
     * @param scanner blocking bounded directory scanner run only by workers.
     * @return provider that the owning session must close.
     */
    @JvmOverloads
    fun createDirectoryProvider(
        onSnapshotChanged: () -> Unit,
        resolver: TerminalCompletionPathResolver = TerminalCompletionPathResolver(),
        scanner: TerminalDirectoryScanner = TerminalBoundedDirectoryScanner(),
    ): TerminalAsyncFileSystemProvider =
        TerminalAsyncFileSystemProvider(
            scheduler = scheduler,
            onSnapshotChanged = onSnapshotChanged,
            resolver = resolver,
            scanner = scanner,
        )

    /**
     * Creates a session-owned asynchronous value snapshot provider.
     *
     * @param keyProvider thread-safe supplier for the current snapshot key.
     * @param loader blocking bounded loader invoked only by workers.
     * @param onSnapshotChanged callback invoked on a worker after publication.
     * @return provider that the owning session must close.
     */
    fun <K, V> createValueProvider(
        keyProvider: () -> K,
        loader: (K) -> List<V>,
        onSnapshotChanged: () -> Unit,
    ): TerminalValueSnapshotProvider<K, V> =
        TerminalValueSnapshotProvider(
            keyProvider = keyProvider,
            scheduler = scheduler,
            loader = loader,
            onSnapshotChanged = onSnapshotChanged,
        )

    /** Cancels queued and active work and releases worker resources. */
    override fun close() {
        // Pass the nullable cause explicitly: an embedding host may provide
        // a compatible coroutines runtime without newer
        // compiler-generated default-argument bridge methods.
        workQueue.close(null)
        job.cancel(CancellationException("Completion snapshot service closed"))
    }

    private companion object {
        private const val DEFAULT_WORKER_COUNT = 2
        private const val DEFAULT_QUEUE_CAPACITY = 32
        private const val DEFAULT_COROUTINE_NAME = "completion-snapshots"
    }
}
