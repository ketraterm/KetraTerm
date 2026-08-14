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
    internal constructor(
        val learningStore: TerminalCompletionLearningStore,
        initialPersistencePath: Path?,
        private var persistenceEnabled: Boolean,
        private val ioDispatcher: CoroutineDispatcher,
        private val onPersistenceFailure: (Throwable) -> Unit,
        private val fileStoreFactory: (Path, (Throwable) -> Unit) -> CompletionLearningFileStore,
    ) {
        /**
         * Creates a repository backed by the standard bounded local-file store.
         *
         * @param learningStore bounded in-memory learning index used by ranking.
         * @param initialPersistencePath initial snapshot path, or `null` for memory-only learning.
         * @param persistenceEnabled whether the initial path may be read and written.
         * @param ioDispatcher dispatcher used for local-file access.
         * @param onPersistenceFailure optional diagnostic callback for failed file access.
         */
        @JvmOverloads
        constructor(
            learningStore: TerminalCompletionLearningStore,
            initialPersistencePath: Path? = null,
            persistenceEnabled: Boolean = true,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
            onPersistenceFailure: (Throwable) -> Unit = {},
        ) : this(
            learningStore = learningStore,
            initialPersistencePath = initialPersistencePath,
            persistenceEnabled = persistenceEnabled,
            ioDispatcher = ioDispatcher,
            onPersistenceFailure = onPersistenceFailure,
            fileStoreFactory = { path, onFailure -> CompletionLearningFileStore(path, onFailure) },
        )

        private val mutex = Mutex()
        private var configuredPersistencePath: Path? = initialPersistencePath
        private var initializedPathIdentity: Path? = null
        private var initialized = false
        private val importedPathIdentities = mutableSetOf<Path>()
        private var writeBlockedPathIdentity: Path? = null

        /** Loads the configured snapshot once and merges it with live learning. */
        suspend fun initialize() {
            mutex.withLock { ensureInitializedLocked() }
        }

        /**
         * Changes the persistence destination, loading and merging its snapshot.
         * This does not change whether persistence is enabled. Passing `null`
         * removes the destination, while a non-null path remains inactive until
         * [setPersistenceEnabled] is called with `true` when currently disabled.
         *
         * @param path replacement persistence path, or `null` for no destination.
         */
        suspend fun setPersistencePath(path: Path?) {
            mutex.withLock {
                configuredPersistencePath = path
                ensureInitializedLocked()
            }
        }

        /** Enables or disables access to the configured persistence path. */
        suspend fun setPersistenceEnabled(enabled: Boolean) {
            mutex.withLock {
                persistenceEnabled = enabled
                ensureInitializedLocked()
            }
        }

        /**
         * Runs one bounded learning mutation and persists the resulting snapshot.
         *
         * @param mutation synchronous mutation of the in-memory learning index.
         */
        suspend fun mutate(mutation: TerminalCompletionLearningStore.() -> Unit) {
            mutex.withLock {
                ensureInitializedLocked()
                learningStore.mutation()
                persistCurrentSnapshot()
            }
        }

        private suspend fun ensureInitializedLocked() {
            val pathIdentity = activePersistencePath()?.identity()
            if (initialized && initializedPathIdentity == pathIdentity) return
            loadConfiguredPath()
            initializedPathIdentity = pathIdentity
            initialized = true
        }

        private suspend fun loadConfiguredPath() {
            val path = activePersistencePath()
            if (path == null) return
            val pathIdentity = path.identity()
            writeBlockedPathIdentity = null
            if (pathIdentity in importedPathIdentities) {
                persistCurrentSnapshot()
                return
            }

            val store = fileStoreFactory(path, onPersistenceFailure)
            when (val outcome = withContext(ioDispatcher) { store.loadSnapshot() }) {
                CompletionLearningFileLoadOutcome.Missing -> {
                    importedPathIdentities.add(pathIdentity)
                    withContext(ioDispatcher) { store.persist(learningStore.snapshot()) }
                }

                is CompletionLearningFileLoadOutcome.Loaded -> {
                    learningStore.mergeSnapshot(outcome.snapshot)
                    importedPathIdentities.add(pathIdentity)
                    withContext(ioDispatcher) { store.persist(learningStore.snapshot()) }
                }

                CompletionLearningFileLoadOutcome.Rejected,
                CompletionLearningFileLoadOutcome.Failed,
                -> writeBlockedPathIdentity = pathIdentity
            }
        }

        private suspend fun persistCurrentSnapshot() {
            val path = activePersistencePath() ?: return
            val pathIdentity = path.identity()
            if (writeBlockedPathIdentity == pathIdentity || pathIdentity !in importedPathIdentities) return
            val snapshot = learningStore.snapshot()
            withContext(ioDispatcher) {
                fileStoreFactory(path, onPersistenceFailure).persist(snapshot)
            }
        }

        private fun Path.identity(): Path = toAbsolutePath().normalize()

        private fun activePersistencePath(): Path? = configuredPersistencePath.takeIf { persistenceEnabled }

        companion object {
            /**
             * Returns the versioned filename used for persisted learning.
             *
             * Hosts choose the parent directory while the persistence module
             * owns the file-format identity.
             *
             * @return current completion-learning filename.
             */
            @JvmStatic
            fun currentFileName(): String = CompletionLearningSnapshotCodec.currentFileName()
        }
    }
