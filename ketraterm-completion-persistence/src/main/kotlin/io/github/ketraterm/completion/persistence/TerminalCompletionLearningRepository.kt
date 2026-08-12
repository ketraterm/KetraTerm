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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Path

/**
 * Serializes completion learning and optional local-file persistence.
 *
 * A single [Mutex] protects mutations, persistence reconfiguration, loading,
 * and writes. Disk access runs on [ioDispatcher]; callers provide lifecycle
 * ownership by launching these suspending operations in their existing scope.
 *
 * @property learningStore bounded in-memory learning index used by ranking.
 * @param initialPersistencePath initial snapshot path, or `null` for memory-only learning.
 * @param persistenceEnabled whether the initial path may be read and written.
 * @param ioDispatcher dispatcher used for local-file access.
 * @param onPersistenceFailure optional diagnostic callback for failed file access.
 */
class TerminalCompletionLearningRepository
    @JvmOverloads
    constructor(
        val learningStore: TerminalCompletionLearningStore,
        initialPersistencePath: Path? = null,
        persistenceEnabled: Boolean = true,
        private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        private val onPersistenceFailure: (Throwable) -> Unit = {},
    ) {
        private val mutex = Mutex()
        private var configuredPersistencePath: Path? = initialPersistencePath
        private var persistencePath: Path? = initialPersistencePath.takeIf { persistenceEnabled }
        private var initializedPath: Path? = null

        /** Loads the configured snapshot once and merges it with live learning. */
        suspend fun initialize() {
            mutex.withLock { loadConfiguredPath() }
        }

        /**
         * Changes the persistence destination, loading and merging its snapshot.
         * Passing `null` keeps subsequent learning in memory only.
         *
         * @param path replacement persistence path, or `null` to disable disk I/O.
         */
        suspend fun setPersistencePath(path: Path?) {
            mutex.withLock {
                if (path == persistencePath && path == initializedPath) return
                configuredPersistencePath = path
                persistencePath = path
                loadConfiguredPath()
            }
        }

        /** Enables or disables access to the configured persistence path. */
        suspend fun setPersistenceEnabled(enabled: Boolean) {
            mutex.withLock {
                val replacement = configuredPersistencePath.takeIf { enabled }
                if (replacement == persistencePath && replacement == initializedPath) return
                persistencePath = replacement
                loadConfiguredPath()
            }
        }

        /**
         * Runs one bounded learning mutation and persists the resulting snapshot.
         *
         * @param mutation synchronous mutation of the in-memory learning index.
         */
        suspend fun mutate(mutation: TerminalCompletionLearningStore.() -> Unit) {
            mutex.withLock {
                learningStore.mutation()
                persistCurrentSnapshot()
            }
        }

        private suspend fun loadConfiguredPath() {
            val path = persistencePath
            initializedPath = path
            if (path == null) return
            val store = TerminalCompletionStatsStore(path, onPersistenceFailure)
            val loaded = withContext(ioDispatcher) { store.loadSnapshot() }
            learningStore.replaceSnapshot(mergeSnapshots(loaded, learningStore.snapshotAll()))
            withContext(ioDispatcher) { store.persist(learningStore.snapshotAll()) }
        }

        private suspend fun persistCurrentSnapshot() {
            val path = persistencePath ?: return
            val snapshot = learningStore.snapshotAll()
            withContext(ioDispatcher) {
                TerminalCompletionStatsStore(path, onPersistenceFailure).persist(snapshot)
            }
        }

        private companion object {
            private fun mergeSnapshots(
                loaded: TerminalCommandCompletionStatsSnapshot,
                live: TerminalCommandCompletionStatsSnapshot,
            ): TerminalCommandCompletionStatsSnapshot =
                TerminalCommandCompletionStatsSnapshot(
                    commandStats = loaded.commandStats + live.commandStats,
                    shapeStats = loaded.shapeStats + live.shapeStats,
                    feedbackStats = loaded.feedbackStats + live.feedbackStats,
                )
        }
    }
