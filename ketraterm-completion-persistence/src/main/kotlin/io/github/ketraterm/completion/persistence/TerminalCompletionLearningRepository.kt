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
import io.github.ketraterm.completion.model.TerminalCommandCompletionStatsSnapshot
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path

/** Immutable destination captured for one pending write generation. */
internal data class CompletionLearningWriteRequest(
    val path: Path,
)

/** Destination and immutable payload resolved for one still-current generation. */
internal data class CompletionLearningSnapshotWrite(
    val path: Path,
    val snapshot: TerminalCommandCompletionStatsSnapshot,
)

/**
 * Passive configured I/O boundary for completion-learning snapshots.
 *
 * This repository owns fixed-path identity, bounded file loading, and one-shot
 * snapshot writes. It deliberately owns no mutex,
 * queue, coroutine scope, or mutation scheduling. A live product must hand the
 * repository to [TerminalCompletionLearningCoordinator], which is the sole
 * runtime serialization and write-lifecycle owner.
 *
 * [initialize] is directly available for focused loading tests. It must not be
 * called concurrently or after the repository has been handed to a coordinator.
 *
 * @param learningStore bounded in-memory learning index populated by loads.
 * @param persistencePath fixed snapshot path, or `null` for a memory-only namespace.
 * @param persistenceEnabled whether the initial path may be read and written.
 * @param ioDispatcher dispatcher used for local-file access.
 * @param onPersistenceFailure optional diagnostic callback for failed file access.
 */
internal class TerminalCompletionLearningRepository
    internal constructor(
        internal val learningStore: TerminalCompletionLearningStore,
        persistencePath: Path? = null,
        private var persistenceEnabled: Boolean = true,
        private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        private val onPersistenceFailure: (Throwable) -> Unit = {},
        private val fileStoreFactory: (Path, (Throwable) -> Unit) -> CompletionLearningSnapshotFileStore =
            { path, onFailure -> CompletionLearningFileStore(path, onFailure) },
    ) {
        internal val initialPersistenceEnabled: Boolean = persistenceEnabled
        private val persistencePath: Path? = persistencePath?.identity()
        private var pathInitialized = false
        private var writeBlocked = false
        private var initialized = false

        /** Loads and merges the initially configured snapshot once. */
        suspend fun initialize() {
            if (initialized) return
            initialized = true
            if (persistenceEnabled) loadConfiguredPath()
        }

        /** Enables or disables the fixed configured path without clearing live learning. */
        internal suspend fun setPersistenceEnabled(enabled: Boolean): Boolean {
            check(initialized) { "completion-learning repository is not initialized" }
            if (persistenceEnabled == enabled) return false

            persistenceEnabled = enabled
            if (!enabled) {
                if (writeBlocked) pathInitialized = false
                return true
            }

            if (!pathInitialized) loadConfiguredPath()
            return true
        }

        /** Captures the writable destination for a later latest-state write, if any. */
        internal fun writeRequestOrNull(): CompletionLearningWriteRequest? {
            val path = activePersistencePath() ?: return null
            if (!pathInitialized || writeBlocked) return null
            return CompletionLearningWriteRequest(path)
        }

        /** Materializes the current immutable state for one pending write generation. */
        internal fun materialize(write: CompletionLearningWriteRequest): CompletionLearningSnapshotWrite =
            CompletionLearningSnapshotWrite(write.path, learningStore.snapshot())

        /** Performs exactly one materialized write requested by the coordinator. */
        internal suspend fun persist(write: CompletionLearningSnapshotWrite) {
            withContext(ioDispatcher) {
                fileStoreFactory(write.path, onPersistenceFailure).persist(write.snapshot)
            }
        }

        private suspend fun loadConfiguredPath() {
            val path = activePersistencePath() ?: return
            writeBlocked = false
            val outcome =
                withContext(ioDispatcher) {
                    fileStoreFactory(path, onPersistenceFailure).loadSnapshot()
                }

            when (outcome) {
                CompletionLearningFileLoadOutcome.Missing -> Unit

                is CompletionLearningFileLoadOutcome.Loaded -> learningStore.mergeSnapshot(outcome.snapshot)

                CompletionLearningFileLoadOutcome.Rejected,
                CompletionLearningFileLoadOutcome.Failed,
                -> writeBlocked = true
            }

            pathInitialized = true
        }

        private fun Path.identity(): Path = toAbsolutePath().normalize()

        private fun activePersistencePath(): Path? = persistencePath.takeIf { persistenceEnabled }
    }
