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
package io.github.ketraterm.completion.api

import io.github.ketraterm.completion.model.TerminalCommandSpec
import io.github.ketraterm.completion.model.TerminalCommandSpecs

/**
 * Host-neutral owner of per-session MRU sources and merged completion engines.
 *
 * Registration, replacement, command recording, removal, and shutdown are
 * serialized. Replacing or removing a session clears its MRU source without
 * affecting a newer registration for the same id. Closing is idempotent and
 * permanently rejects new sessions; late command records are ignored.
 *
 * @param commandSpecs immutable command vocabulary shared by every session.
 * Callers must not mutate this list while the registry is alive.
 * @param learningStore optional cross-session learning used by MRU recovery and ranking.
 * @param sessionMruCapacity positive number of distinct session commands retained per session.
 * @param sourceFailureHandler diagnostic sink shared by session engines.
 * @throws IllegalArgumentException if [sessionMruCapacity] is not positive.
 */
class TerminalCompletionSessionRegistry
    @JvmOverloads
    constructor(
        commandSpecs: List<TerminalCommandSpec> = TerminalCommandSpecs.defaults(),
        private val learningStore: TerminalCompletionLearningStore? = null,
        private val sessionMruCapacity: Int = DEFAULT_SESSION_MRU_CAPACITY,
        private val sourceFailureHandler: TerminalCompletionSourceFailureHandler =
            TerminalCompletionSourceFailureHandler.SYSTEM_LOGGER,
    ) : AutoCloseable {
        init {
            require(sessionMruCapacity > 0) { "sessionMruCapacity must be > 0, was $sessionMruCapacity" }
        }

        private val commandSpecs = commandSpecs
        private val lock = Any()
        private val sessions = HashMap<String, TerminalCompletionSessionHandle>()
        private var closed = false

        /**
         * Creates and registers one MRU, path, and merged-engine composition.
         *
         * A previous registration with [sessionId] is retired after the new
         * registration becomes authoritative. The returned handle removes only
         * itself, so closing an older handle cannot remove its replacement.
         *
         * @param sessionId non-blank host session identity.
         * @param fileSystemProvider host path provider for this session.
         * @param additionalSources extra host-specific source registrations.
         * @return registered completion-session handle owned by the caller.
         * @throws IllegalArgumentException if [sessionId] is blank.
         * @throws IllegalStateException if this registry is closed.
         */
        @JvmOverloads
        fun openSession(
            sessionId: String,
            fileSystemProvider: TerminalFileSystemProvider,
            additionalSources: List<TerminalCompletionSourceEntry> = emptyList(),
        ): TerminalCompletionSessionHandle {
            require(sessionId.isNotBlank()) { "sessionId must not be blank" }
            val mruSource =
                TerminalCompletionSources.sessionMru(
                    capacity = sessionMruCapacity,
                    commandSpecs = commandSpecs,
                    learningStore = learningStore,
                )
            val sources =
                buildList(additionalSources.size + BUILT_IN_SOURCE_COUNT) {
                    add(TerminalCompletionSourceEntry(mruSource, TerminalCompletionSourcePrior.SESSION_MRU))
                    add(
                        TerminalCompletionSourceEntry(
                            TerminalCompletionSources.path(fileSystemProvider),
                            TerminalCompletionSourcePrior.DIRECTORY_PATH,
                        ),
                    )
                    addAll(additionalSources)
                }
            val handle =
                TerminalCompletionSessionHandle(
                    sessionId = sessionId,
                    engine =
                        TerminalCompletionEngines.fromSources(
                            sources = sources,
                            commandSpecs = commandSpecs,
                            learningStore = learningStore,
                            sourceFailureHandler = sourceFailureHandler,
                        ),
                    mruSource = mruSource,
                    closeAction = ::removeSession,
                )
            val previous =
                try {
                    synchronized(lock) {
                        check(!closed) { "completion session registry is closed" }
                        sessions.put(sessionId, handle)
                    }
                } catch (failure: Throwable) {
                    handle.clearMru()
                    throw failure
                }
            previous?.clearMru()
            return handle
        }

        /**
         * Records one successful command in the currently registered session.
         *
         * Missing sessions and records arriving after shutdown are ignored.
         *
         * @param sessionId host session identity.
         * @param commandLine command line confirmed successful by the host.
         * @param profileId optional host profile identity.
         * @param workingDirectoryUri optional working-directory URI captured for the command.
         */
        fun recordSuccessfulCommand(
            sessionId: String,
            commandLine: String,
            profileId: String?,
            workingDirectoryUri: String?,
        ) {
            synchronized(lock) {
                if (closed) return
                sessions[sessionId]?.recordSuccessfulCommand(commandLine, profileId, workingDirectoryUri)
            }
        }

        /**
         * Removes and retires the current registration for [sessionId].
         *
         * Missing sessions and removals after shutdown are harmless.
         *
         * @param sessionId host session identity to remove.
         */
        fun removeSession(sessionId: String) {
            val removed = synchronized(lock) { sessions.remove(sessionId) }
            removed?.clearMru()
        }

        /** Permanently closes the registry and clears every retained session MRU. */
        override fun close() {
            val removed =
                synchronized(lock) {
                    if (closed) return
                    closed = true
                    val copy = sessions.values.toList()
                    sessions.clear()
                    copy
                }
            removed.forEach(TerminalCompletionSessionHandle::clearMru)
        }

        private fun removeSession(handle: TerminalCompletionSessionHandle) {
            val removed =
                synchronized(lock) {
                    if (sessions[handle.sessionId] !== handle) null else sessions.remove(handle.sessionId)
                }
            removed?.clearMru()
        }

        private companion object {
            private const val DEFAULT_SESSION_MRU_CAPACITY = 128
            private const val BUILT_IN_SOURCE_COUNT = 2
        }
    }

/**
 * Caller-owned handle for one registered completion session.
 *
 * @property sessionId stable host session identity.
 * @property engine merged engine composed for this registration.
 */
class TerminalCompletionSessionHandle
    internal constructor(
        val sessionId: String,
        val engine: TerminalCompletionEngine,
        private val mruSource: TerminalSessionMruCompletionSource,
        private val closeAction: (TerminalCompletionSessionHandle) -> Unit,
    ) : AutoCloseable {
        /** Removes this handle only if it is still the current registration. */
        override fun close() {
            closeAction(this)
        }

        internal fun recordSuccessfulCommand(
            commandLine: String,
            profileId: String?,
            workingDirectoryUri: String?,
        ) {
            mruSource.recordSuccessfulCommand(commandLine, profileId, workingDirectoryUri)
        }

        internal fun clearMru() {
            mruSource.clear()
        }
    }
