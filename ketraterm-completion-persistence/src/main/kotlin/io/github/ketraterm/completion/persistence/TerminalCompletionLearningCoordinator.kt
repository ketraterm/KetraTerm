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

import io.github.ketraterm.completion.api.TerminalCompletionLearningStore
import io.github.ketraterm.completion.api.TerminalCompletionPersistencePolicy
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns one ordered completion-learning command stream in a host lifecycle scope.
 *
 * This class contains no product policy beyond the shared command-persistence
 * privacy check. The host still owns [coroutineScope], the persistence path,
 * enablement settings, and failure reporting. One worker initializes the
 * repository and executes every subsequent command in submission order.
 * Cancelling the supplied scope cancels the worker and its queued operations.
 *
 * @param repository suspending learning and persistence repository.
 * @param coroutineScope caller-owned lifecycle scope for infrequent learning operations.
 * @param workerDispatcher dispatcher used by the ordered command worker. The
 * worker remains a child of [coroutineScope].
 */
class TerminalCompletionLearningCoordinator
    @JvmOverloads
    constructor(
        private val repository: TerminalCompletionLearningRepository,
        coroutineScope: CoroutineScope,
        workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
    ) {
        private val commands = Channel<Command>(COMMAND_QUEUE_CAPACITY)
        private val acceptingCommands = AtomicBoolean(true)
        private val worker =
            coroutineScope.async(context = workerDispatcher, start = CoroutineStart.UNDISPATCHED) {
                var completionFailure: Throwable? = null
                try {
                    repository.initialize()
                    for (command in commands) {
                        command.execute(repository)
                    }
                } catch (failure: Throwable) {
                    completionFailure = failure
                    throw failure
                } finally {
                    commands.close(completionFailure)
                    val failure = completionFailure ?: CancellationException("completion-learning owner stopped")
                    while (true) {
                        val pending = commands.tryReceive().getOrNull() ?: break
                        if (pending is Command.Flush) pending.fail(failure)
                    }
                }
            }

        /** Mutable learning store shared with completion ranking and history sources. */
        val learningStore: TerminalCompletionLearningStore
            get() = repository.learningStore

        /**
         * Queues one learning mutation after all previously submitted commands.
         *
         * @param mutation synchronous bounded mutation run under the repository mutex.
         */
        fun submit(mutation: () -> Unit) {
            enqueue(Command.Mutate(mutation))
        }

        /**
         * Records one completed command when the shared persistence privacy policy permits it.
         *
         * @param commandLine command text reported by shell integration.
         * @param successful whether the command completed successfully.
         * @param profileId optional host profile identifier.
         * @param workingDirectoryUri optional working-directory URI captured for the command.
         * @param usedAtEpochMillis non-negative completion timestamp.
         */
        fun recordCommandResult(
            commandLine: String,
            successful: Boolean,
            profileId: String?,
            workingDirectoryUri: String?,
            usedAtEpochMillis: Long,
        ) {
            if (!TerminalCompletionPersistencePolicy.allowsCommand(commandLine)) return
            submit {
                learningStore.recordCommandResult(
                    commandLine = commandLine,
                    successful = successful,
                    profileId = profileId,
                    workingDirectoryUri = workingDirectoryUri,
                    usedAtEpochMillis = usedAtEpochMillis,
                )
            }
        }

        /**
         * Queues a change to the active persistence destination.
         *
         * @param path replacement snapshot path, or `null` for memory-only learning.
         */
        fun setPersistencePath(path: Path?) {
            enqueue(Command.SetPersistencePath(path))
        }

        /**
         * Queues a change to persistence enablement for the configured path.
         *
         * @param enabled whether the configured snapshot path may be read and written.
         */
        fun setPersistenceEnabled(enabled: Boolean) {
            enqueue(Command.SetPersistenceEnabled(enabled))
        }

        /**
         * Waits until every command submitted before this call has completed.
         *
         * The barrier includes repository initialization and any disk write caused
         * by an earlier mutation or settings change. Commands submitted after the
         * barrier are not awaited.
         */
        suspend fun flush() {
            check(acceptingCommands.get()) { "completion-learning owner is closed" }
            val completed = CompletableDeferred<Unit>()
            commands.send(Command.Flush(completed))
            completed.await()
        }

        /** Stops accepting commands and lets the worker drain the queue in order. */
        fun close() {
            if (acceptingCommands.compareAndSet(true, false)) {
                commands.close()
            }
        }

        /**
         * Stops accepting commands and waits for every queued operation and disk
         * write to finish. Calling this method more than once is safe.
         */
        suspend fun closeAndFlush() {
            close()
            worker.await()
        }

        private fun enqueue(command: Command) {
            check(acceptingCommands.get()) { "completion-learning owner is closed" }
            commands.trySend(command).getOrThrow()
        }

        private sealed interface Command {
            suspend fun execute(repository: TerminalCompletionLearningRepository)

            class Mutate(
                private val mutation: () -> Unit,
            ) : Command {
                override suspend fun execute(repository: TerminalCompletionLearningRepository) {
                    repository.mutate { mutation() }
                }
            }

            class SetPersistencePath(
                private val path: Path?,
            ) : Command {
                override suspend fun execute(repository: TerminalCompletionLearningRepository) {
                    repository.setPersistencePath(path)
                }
            }

            class SetPersistenceEnabled(
                private val enabled: Boolean,
            ) : Command {
                override suspend fun execute(repository: TerminalCompletionLearningRepository) {
                    repository.setPersistenceEnabled(enabled)
                }
            }

            class Flush(
                private val completed: CompletableDeferred<Unit>,
            ) : Command {
                override suspend fun execute(repository: TerminalCompletionLearningRepository) {
                    completed.complete(Unit)
                }

                fun fail(failure: Throwable) {
                    completed.completeExceptionally(failure)
                }
            }
        }

        private companion object {
            private const val COMMAND_QUEUE_CAPACITY = 1_024
        }
    }
