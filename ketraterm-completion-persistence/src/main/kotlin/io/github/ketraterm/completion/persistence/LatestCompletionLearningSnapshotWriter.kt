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
package io.github.ketraterm.completion.persistence

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/**
 * Writes only the latest requested learning generation.
 *
 * Requests replace one shared latest value and use a conflated wakeup, so a
 * burst retains neither per-event snapshots nor per-event channel nodes. The
 * immutable snapshot is materialized only when a generation reaches the I/O
 * boundary. A flush forces the latest generation past the debounce and
 * observes the actual write result for that generation.
 */
internal class LatestCompletionLearningSnapshotWriter(
    coroutineScope: CoroutineScope,
    workerDispatcher: CoroutineDispatcher,
    private val debounceMillis: Long,
    private val materialize: (CompletionLearningWriteRequest) -> CompletionLearningSnapshotWrite,
    private val persist: suspend (CompletionLearningSnapshotWrite) -> Unit,
) {
    init {
        require(debounceMillis >= 0L) { "debounceMillis must be >= 0, was $debounceMillis" }
    }

    private val stateLock = Any()
    private val wakeups = Channel<Unit>(Channel.CONFLATED)
    private val waiters = ArrayList<FlushWaiter>()
    private var latestRequest: GenerationRequest? = null
    private var latestGeneration = 0L
    private var settledGeneration = 0L
    private var settledFailure: Throwable? = null
    private var forceThroughGeneration = 0L
    private var closing = false
    private var stoppedFailure: Throwable? = null
    private val worker =
        coroutineScope.launch(context = workerDispatcher, start = CoroutineStart.UNDISPATCHED) {
            runWriter()
        }

    /** Replaces the pending request with a new monotonically increasing generation. */
    fun request(write: CompletionLearningWriteRequest?) {
        synchronized(stateLock) {
            check(!closing) { "completion-learning snapshot writer is closed" }
            check(latestGeneration != Long.MAX_VALUE) { "completion-learning snapshot generation overflow" }
            ++latestGeneration
            latestRequest = GenerationRequest(latestGeneration, write)
        }
        wakeups.trySend(Unit)
    }

    /** Forces and awaits the newest generation requested before this call. */
    suspend fun flushLatest() {
        val generation = synchronized(stateLock) { latestGeneration }
        flush(generation)
    }

    /** Flushes the current generation, closes the writer, and joins its child job. */
    suspend fun closeAndFlush() {
        val flushFailure = runCatching { flushLatest() }.exceptionOrNull()
        synchronized(stateLock) { closing = true }
        wakeups.trySend(Unit)
        worker.join()
        if (flushFailure != null) throw flushFailure
    }

    private suspend fun flush(generation: Long) {
        val waiter = CompletableDeferred<Unit>()
        var waiting = false
        val immediateFailure =
            synchronized(stateLock) {
                stoppedFailure?.let { return@synchronized it }
                if (settledGeneration >= generation) {
                    return@synchronized settledFailure.takeIf { settledGeneration == generation }
                }
                waiters += FlushWaiter(generation, waiter)
                forceThroughGeneration = maxOf(forceThroughGeneration, generation)
                waiting = true
                null
            }

        if (immediateFailure != null) throw immediateFailure
        if (!waiting) return
        wakeups.trySend(Unit)
        waiter.await()
    }

    private suspend fun runWriter() {
        var terminalFailure: Throwable? = null
        try {
            while (true) {
                wakeups.receive()
                while (true) {
                    val view = nextRequestView()
                    if (view.shouldClose) return
                    val request = view.request ?: break

                    if (request.write == null) {
                        settle(request.generation, null)
                        continue
                    }

                    if (!view.forced && debounceMillis > 0L) {
                        delay(debounceMillis)
                        if (requestChangedOrForced(request.generation)) continue
                    }

                    val write = materialize(request.write)
                    if (requestChanged(request.generation)) continue

                    val failure =
                        try {
                            persist(write)
                            null
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (writeFailure: Throwable) {
                            writeFailure
                        }
                    settle(request.generation, failure)
                }
            }
        } catch (failure: Throwable) {
            terminalFailure = failure
            throw failure
        } finally {
            val failure = terminalFailure ?: CancellationException("completion-learning snapshot writer stopped")
            val pending =
                synchronized(stateLock) {
                    closing = true
                    stoppedFailure = failure
                    val copy = waiters.toList()
                    waiters.clear()
                    copy
                }
            pending.forEach { it.completed.completeExceptionally(failure) }
            wakeups.close(failure)
        }
    }

    private fun nextRequestView(): RequestView =
        synchronized(stateLock) {
            val request = latestRequest?.takeIf { it.generation > settledGeneration }
            RequestView(
                request = request,
                forced = request != null && (closing || forceThroughGeneration >= request.generation),
                shouldClose = closing && request == null,
            )
        }

    private fun requestChangedOrForced(generation: Long): Boolean =
        synchronized(stateLock) {
            latestRequest?.generation != generation || closing || forceThroughGeneration >= generation
        }

    private fun requestChanged(generation: Long): Boolean = synchronized(stateLock) { latestRequest?.generation != generation }

    private fun settle(
        generation: Long,
        failure: Throwable?,
    ) {
        val completed = ArrayList<FlushWaiter>()
        synchronized(stateLock) {
            if (generation <= settledGeneration) return
            settledGeneration = generation
            settledFailure = failure
            if (forceThroughGeneration <= generation) forceThroughGeneration = 0L

            val iterator = waiters.iterator()
            while (iterator.hasNext()) {
                val waiter = iterator.next()
                if (waiter.generation > generation) continue
                iterator.remove()
                completed += waiter
            }
        }

        for (waiter in completed) {
            if (failure == null) {
                waiter.completed.complete(Unit)
            } else {
                waiter.completed.completeExceptionally(failure)
            }
        }
    }

    private data class GenerationRequest(
        val generation: Long,
        val write: CompletionLearningWriteRequest?,
    )

    private data class RequestView(
        val request: GenerationRequest?,
        val forced: Boolean,
        val shouldClose: Boolean,
    )

    private data class FlushWaiter(
        val generation: Long,
        val completed: CompletableDeferred<Unit>,
    )
}
