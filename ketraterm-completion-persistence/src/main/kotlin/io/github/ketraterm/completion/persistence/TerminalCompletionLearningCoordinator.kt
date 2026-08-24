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
import io.github.ketraterm.completion.model.TerminalCompletionFeedbackKind
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.nio.file.Path

/**
 * Owns synchronous in-memory learning and one latest-snapshot writer.
 *
 * The caller supplies the lifecycle scope and a fixed persistence destination.
 * Each learning event updates the bounded store before returning, then emits a
 * conflated write generation. A small bounded control worker handles only
 * startup hydration, persistence enablement, and flush barriers. The
 * coordinator owns scheduling; its passive repository owns only configured
 * snapshot I/O.
 */
class TerminalCompletionLearningCoordinator
    internal constructor(
        private val repository: TerminalCompletionLearningRepository,
        coroutineScope: CoroutineScope,
        workerDispatcher: CoroutineDispatcher,
        writeDebounceMillis: Long,
    ) {
        /**
         * Creates a lifecycle-bound learning owner for one fixed persistence path.
         *
         * @param learningStore bounded in-memory learning used immediately by completion ranking. The coordinator
         * owns hydration from [persistencePath]; callers must not preload that same aggregate file into the store.
         * @param coroutineScope caller-owned lifecycle scope for learning and write workers.
         * @param persistencePath fixed snapshot path, or `null` for memory-only learning.
         * @param persistenceEnabled whether the fixed path may initially be read and written.
         * @param workerDispatcher dispatcher used by the persistence control and debounce workers.
         * @param ioDispatcher dispatcher used for bounded snapshot file access.
         * @param onPersistenceFailure optional diagnostic callback for failed file access.
         */
        @JvmOverloads
        constructor(
            learningStore: TerminalCompletionLearningStore,
            coroutineScope: CoroutineScope,
            persistencePath: Path? = null,
            persistenceEnabled: Boolean = true,
            workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
            onPersistenceFailure: (Throwable) -> Unit = {},
        ) : this(
            repository =
                TerminalCompletionLearningRepository(
                    learningStore = learningStore,
                    persistencePath = persistencePath,
                    persistenceEnabled = persistenceEnabled,
                    ioDispatcher = ioDispatcher,
                    onPersistenceFailure = onPersistenceFailure,
                ),
            coroutineScope = coroutineScope,
            workerDispatcher = workerDispatcher,
            writeDebounceMillis = DEFAULT_WRITE_DEBOUNCE_MILLIS,
        )

        private val stateLock = Any()
        private val controls = Channel<ControlCommand>(CONTROL_QUEUE_CAPACITY)
        private var acceptingEvents = true
        private var persistenceReady = false
        private var desiredPersistenceEnabled = repository.initialPersistenceEnabled
        private var persistenceControlGeneration = 0L
        private var currentWriteRequest: CompletionLearningWriteRequest? = null
        private var changedBeforeHydration = false
        private val snapshotWriter =
            LatestCompletionLearningSnapshotWriter(
                coroutineScope = coroutineScope,
                workerDispatcher = workerDispatcher,
                debounceMillis = writeDebounceMillis,
                materialize = repository::materialize,
                persist = repository::persist,
            )
        private val worker =
            coroutineScope.async(context = workerDispatcher, start = CoroutineStart.UNDISPATCHED) {
                runControlWorker()
            }

        /** Mutable learning store shared with completion ranking and history sources. */
        val learningStore: TerminalCompletionLearningStore
            get() = repository.learningStore

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
            if (!TerminalCompletionPersistencePolicy.allowsCommand(commandLine) || usedAtEpochMillis < 0L) return
            synchronized(stateLock) {
                check(acceptingEvents) { "completion-learning owner is closed" }
                val changed =
                    learningStore.recordCommandResult(
                        commandLine = commandLine,
                        successful = successful,
                        profileId = profileId,
                        workingDirectoryUri = workingDirectoryUri,
                        usedAtEpochMillis = usedAtEpochMillis,
                    )
                if (changed) requestWriteAfterMutation()
            }
        }

        /**
         * Records accepted or dismissed feedback for one exact suggested command.
         *
         * @param commandLine command text produced by accepting the suggestion.
         * @param feedback accepted or dismissed feedback kind.
         * @param profileId optional host profile identifier.
         * @param workingDirectoryUri optional working-directory URI captured for the event.
         * @param feedbackAtEpochMillis non-negative feedback timestamp.
         */
        fun recordSuggestionFeedback(
            commandLine: String,
            feedback: TerminalCompletionFeedbackKind,
            profileId: String?,
            workingDirectoryUri: String?,
            feedbackAtEpochMillis: Long,
        ) {
            if (!TerminalCompletionPersistencePolicy.allowsCommand(commandLine) || feedbackAtEpochMillis < 0L) return
            synchronized(stateLock) {
                check(acceptingEvents) { "completion-learning owner is closed" }
                val changed =
                    learningStore.recordSuggestionFeedback(
                        commandLine = commandLine,
                        feedback = feedback,
                        profileId = profileId,
                        workingDirectoryUri = workingDirectoryUri,
                        feedbackAtEpochMillis = feedbackAtEpochMillis,
                    )
                if (changed) requestWriteAfterMutation()
            }
        }

        /**
         * Changes persistence enablement for the fixed configured path.
         *
         * Disabling synchronously prevents new write requests and invalidates
         * any snapshot still waiting in the debounce window. Loading after an
         * enable remains asynchronous. In-memory learning stays active and is
         * written when the same path is enabled again.
         *
         * @param enabled whether the configured snapshot path may be read and written.
         */
        fun setPersistenceEnabled(enabled: Boolean) {
            synchronized(stateLock) {
                check(acceptingEvents) { "completion-learning owner is closed" }
                if (desiredPersistenceEnabled == enabled) return
                check(persistenceControlGeneration != Long.MAX_VALUE) {
                    "completion-learning persistence control generation overflow"
                }
                val generation = persistenceControlGeneration + 1L
                val control = ControlCommand.SetPersistenceEnabled(enabled, generation)
                check(controls.trySend(control).isSuccess) {
                    "completion-learning control queue is full or closed"
                }
                desiredPersistenceEnabled = enabled
                persistenceControlGeneration = generation
                if (!enabled) {
                    currentWriteRequest = null
                    snapshotWriter.request(null)
                }
            }
        }

        /**
         * Waits for hydration, earlier controls, and the latest requested disk generation.
         *
         * Write requests issued after the writer reaches this barrier are not
         * awaited. A failed latest write is reported to the configured diagnostic
         * callback and rethrown here.
         */
        suspend fun flush() {
            val completed = CompletableDeferred<Unit>()
            enqueueControl(ControlCommand.Flush(completed))
            completed.await()
        }

        /** Stops accepting events and lets the bounded control worker drain. */
        fun close() {
            synchronized(stateLock) {
                if (!acceptingEvents) return
                acceptingEvents = false
                controls.close()
            }
        }

        /**
         * Stops accepting events and waits for the final requested generation.
         *
         * Calling this method more than once is safe. Persistence failure from
         * the final generation is surfaced to the caller.
         */
        suspend fun closeAndFlush() {
            close()
            worker.await()
        }

        private suspend fun runControlWorker() {
            var terminalFailure: Throwable? = null
            try {
                repository.initialize()
                synchronized(stateLock) {
                    persistenceReady = true
                    currentWriteRequest = repository.writeRequestOrNull().takeIf { desiredPersistenceEnabled }
                    if (changedBeforeHydration) {
                        currentWriteRequest?.let(snapshotWriter::request)
                        changedBeforeHydration = false
                    }
                }
                for (control in controls) executeControl(control)
            } catch (failure: Throwable) {
                terminalFailure = failure
            } finally {
                synchronized(stateLock) {
                    acceptingEvents = false
                    controls.close(terminalFailure)
                }
                val pendingFailure = terminalFailure ?: CancellationException("completion-learning owner stopped")
                while (true) {
                    val pending = controls.tryReceive().getOrNull() ?: break
                    if (pending is ControlCommand.Flush) pending.completed.completeExceptionally(pendingFailure)
                }

                try {
                    snapshotWriter.closeAndFlush()
                } catch (closeFailure: Throwable) {
                    if (terminalFailure == null) {
                        terminalFailure = closeFailure
                    } else if (closeFailure !== terminalFailure) {
                        terminalFailure.addSuppressed(closeFailure)
                    }
                }
            }

            terminalFailure?.let { throw it }
        }

        private suspend fun executeControl(control: ControlCommand) {
            when (control) {
                is ControlCommand.SetPersistenceEnabled -> {
                    repository.setPersistenceEnabled(control.enabled)
                    val request = repository.writeRequestOrNull()
                    synchronized(stateLock) {
                        if (control.generation != persistenceControlGeneration ||
                            control.enabled != desiredPersistenceEnabled
                        ) {
                            return
                        }
                        currentWriteRequest = request
                        snapshotWriter.request(request)
                    }
                }

                is ControlCommand.Flush -> {
                    try {
                        snapshotWriter.flushLatest()
                        control.completed.complete(Unit)
                    } catch (failure: Throwable) {
                        control.completed.completeExceptionally(failure)
                        if (failure is CancellationException) throw failure
                    }
                }
            }
        }

        private fun requestWriteAfterMutation() {
            if (!persistenceReady) {
                changedBeforeHydration = true
                return
            }
            currentWriteRequest?.let(snapshotWriter::request)
        }

        private fun enqueueControl(control: ControlCommand) {
            synchronized(stateLock) {
                check(acceptingEvents) { "completion-learning owner is closed" }
                check(controls.trySend(control).isSuccess) { "completion-learning control queue is full or closed" }
            }
        }

        private sealed interface ControlCommand {
            class SetPersistenceEnabled(
                val enabled: Boolean,
                val generation: Long,
            ) : ControlCommand

            class Flush(
                val completed: CompletableDeferred<Unit>,
            ) : ControlCommand
        }

        companion object {
            private const val DEFAULT_WRITE_DEBOUNCE_MILLIS = 150L
            private const val CONTROL_QUEUE_CAPACITY = 64

            /**
             * Returns the versioned filename used for persisted learning.
             *
             * Hosts choose the parent directory while this module owns the
             * file-format identity.
             *
             * @return current completion-learning filename.
             */
            @JvmStatic
            fun currentFileName(): String = CompletionLearningSnapshotCodec.currentFileName()
        }
    }
