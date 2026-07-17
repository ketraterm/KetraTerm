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

import io.github.ketraterm.completion.model.TerminalCompletionDomainValue
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/**
 * Application-scoped owner of bounded IntelliJ completion snapshot work.
 *
 * Two IO workers consume a bounded, non-blocking queue shared by all terminal
 * completion sessions. Submission rejects work when the queue is full instead
 * of blocking the Swing event-dispatch thread. Provider failures are isolated
 * so one failed load cannot terminate a worker. Closing this owner cancels
 * queued and active work and is safe to repeat.
 */
internal class IntellijCompletionSnapshotService : AutoCloseable {
    private val scope =
        CoroutineScope(
            SupervisorJob() +
                    Dispatchers.IO.limitedParallelism(WORKER_COUNT) +
                    CoroutineName("intellij-completion-snapshots"),
        )
    private val workQueue = Channel<suspend () -> Unit>(WORK_QUEUE_CAPACITY)
    private val scheduler = IntellijCompletionLoadScheduler { work -> workQueue.trySend(work).isSuccess }

    init {
        repeat(WORKER_COUNT) { workerIndex ->
            scope.launch(CoroutineName("intellij-completion-snapshot-worker-$workerIndex")) {
                for (work in workQueue) {
                    try {
                        work()
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: RuntimeException) {
                        // A provider failure must not terminate the shared worker.
                    }
                }
            }
        }
    }

    /**
     * Creates a session-owned asynchronous directory snapshot provider.
     *
     * @param onSnapshotChanged callback invoked on a snapshot worker after a
     * new active snapshot is published; the callback must arrange any required
     * Swing-thread handoff.
     * @param scanner blocking directory scanner executed only by snapshot workers.
     * @return provider that must be closed with its terminal session.
     */
    fun createDirectoryProvider(
        onSnapshotChanged: () -> Unit,
        scanner: IntellijDirectoryScanner = BoundedIntellijDirectoryScanner(),
    ): IntellijAsyncFileSystemProvider =
        IntellijAsyncFileSystemProvider(
            scheduler = scheduler,
            onSnapshotChanged = onSnapshotChanged,
            scanner = scanner,
        )

    /**
     * Creates a session-owned asynchronous Git-branch snapshot provider.
     *
     * @param workingDirectoryUriProvider thread-safe supplier for the session's
     * latest working-directory URI.
     * @param loader blocking branch loader executed only by snapshot workers.
     * @param onSnapshotChanged callback invoked on a snapshot worker after a
     * new active snapshot is published; the callback must arrange any required
     * Swing-thread handoff.
     * @return provider that must be closed with its terminal session.
     */
    fun createGitBranchProvider(
        workingDirectoryUriProvider: () -> String?,
        loader: (String?) -> List<TerminalCompletionDomainValue>,
        onSnapshotChanged: () -> Unit,
    ): IntellijGitBranchCompletionProvider =
        IntellijGitBranchCompletionProvider(
            workingDirectoryUriProvider = workingDirectoryUriProvider,
            scheduler = scheduler,
            loader = loader,
            onSnapshotChanged = onSnapshotChanged,
        )

    /** Cancels shared snapshot work and releases worker resources idempotently. */
    override fun close() {
        workQueue.close()
        scope.cancel()
    }

    private companion object {
        private const val WORKER_COUNT = 2
        private const val WORK_QUEUE_CAPACITY = 32
    }
}
