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

import io.github.ketraterm.completion.api.*
import io.github.ketraterm.completion.host.TerminalLocalFileSystemProvider
import io.github.ketraterm.completion.model.TerminalCommandSpec
import io.github.ketraterm.completion.model.TerminalCommandSpecs
import io.github.ketraterm.completion.persistence.TerminalCompletionLearningRepository
import io.github.ketraterm.session.TerminalShellIntegrationCommandLifecycle
import io.github.ketraterm.session.TerminalShellIntegrationCommandMetadata
import io.github.ketraterm.ui.swing.host.SwingCompletionSuggestionProvider
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Plugin-owned composition of shared completion sources and learned statistics.
 *
 * Session registration is synchronized and replacing an existing session id
 * clears its previous session-local learning.
 *
 * @param specs immutable command specifications shared by every session.
 * @param statsSource bounded learned-statistics source.
 * @param learningRepository serialized learning and persistence owner.
 * @param sessionMruCapacity positive per-session MRU capacity.
 * @param coroutineScope host lifecycle scope that parents completion work.
 * @throws IllegalArgumentException if [sessionMruCapacity] is not positive.
 */
internal class IntellijCompletionRegistry(
    specs: List<TerminalCommandSpec> = TerminalCommandSpecs.defaults(),
    private val statsSource: TerminalCompletionLearningStore = TerminalCompletionLearningStore(commandSpecs = specs),
    learningRepository: TerminalCompletionLearningRepository = TerminalCompletionLearningRepository(statsSource),
    private val sessionMruCapacity: Int = DEFAULT_SESSION_MRU_CAPACITY,
    coroutineScope: CoroutineScope,
) : AutoCloseable {
    init {
        require(sessionMruCapacity > 0) { "sessionMruCapacity must be > 0, was $sessionMruCapacity" }
    }

    /** Defensive immutable copy of the command specifications used by sessions. */
    private val commandSpecs: List<TerminalCommandSpec> = specs.toList()
    private val lock = Any()
    private val closed = AtomicBoolean()
    private val sessionStates = HashMap<String, SessionState>()
    private val statistics =
        IntellijCompletionStatisticsCoordinator(
            repository = learningRepository,
            coroutineScope = coroutineScope,
        )

    /**
     * Creates and registers all completion sources for one terminal session.
     *
     * @param context host capabilities and additional suspending sources for the session.
     * @return closeable session-facing provider and feedback resources.
     * @throws IllegalStateException if this registry is closed.
     */
    fun openSession(context: IntellijCompletionSessionContext): IntellijCompletionSession {
        val opened =
            synchronized(lock) {
                check(!closed.get()) { "IntelliJ completion registry is closed" }
                createSession(context)
            }
        try {
            opened.previous?.close()
        } catch (failure: Throwable) {
            runCatching(opened.session::close).exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
        return opened.session
    }

    private fun createSession(context: IntellijCompletionSessionContext): OpenedSession {
        val mruSource =
            TerminalCompletionSources.sessionMru(
                capacity = sessionMruCapacity,
                commandSpecs = commandSpecs,
                learningStore = statsSource,
            )
        try {
            val fileSystemProvider = TerminalLocalFileSystemProvider(scanner = context.directoryScanner)
            val sources =
                buildList {
                    add(
                        TerminalCompletionSourceEntry(
                            mruSource,
                            priority = TerminalCompletionSourcePrior.SESSION_MRU,
                        ),
                    )
                    add(
                        TerminalCompletionSourceEntry(
                            TerminalCompletionSources.path(
                                fileSystemProvider = fileSystemProvider,
                            ),
                            priority = TerminalCompletionSourcePrior.DIRECTORY_PATH,
                        ),
                    )
                    addAll(context.additionalSources)
                }
            val provider =
                SwingCompletionSuggestionProvider(
                    engine =
                        TerminalCompletionEngines.fromSources(
                            sources = sources,
                            commandSpecs = commandSpecs,
                            learningStore = statsSource,
                        ),
                    contextProvider = { context.swingContext() },
                )
            val state = SessionState(mruSource)
            val session =
                IntellijCompletionSession(
                    provider = provider,
                    feedbackHandler = statistics.createFeedbackHandler(context::swingContext),
                    closeAction = { removeSession(context.sessionId, state) },
                )
            return OpenedSession(session, sessionStates.put(context.sessionId, state))
        } catch (failure: Throwable) {
            mruSource.clear()
            throw failure
        }
    }

    /**
     * Updates session MRU state and queues privacy-filtered persistent learning.
     *
     * @param sessionId terminal session that produced the command.
     * @param profileId stable terminal profile identifier used for ranking context.
     * @param metadata trusted shell-integration lifecycle metadata.
     */
    fun recordFinishedCommand(
        sessionId: String,
        profileId: String,
        metadata: TerminalShellIntegrationCommandMetadata,
    ) {
        val command = metadata.commandText ?: return
        val successful = metadata.lifecycle == TerminalShellIntegrationCommandLifecycle.SUCCEEDED
        synchronized(lock) {
            if (closed.get()) return
            if (successful) {
                sessionStates[sessionId]?.mruSource?.recordSuccessfulCommand(
                    command,
                    profileId,
                    metadata.workingDirectoryUri,
                )
            }
            statistics.recordFinishedCommand(profileId, metadata)
        }
    }

    /**
     * Changes whether the registry may read and write learned statistics on disk.
     *
     * @param enabled `true` to persist sanitized learning across IDE restarts.
     */
    fun setPersistenceEnabled(enabled: Boolean) {
        statistics.setPersistenceEnabled(enabled)
    }

    private fun removeSession(
        sessionId: String,
        expected: SessionState,
    ) {
        val removed =
            synchronized(lock) {
                if (sessionStates[sessionId] !== expected) null else sessionStates.remove(sessionId)
            }
        removed?.close()
    }

    /** Clears sessions and releases learning resources. Closing is idempotent. */
    override fun close() {
        closeSessionStates().forEach(SessionState::close)
        statistics.close()
    }

    /** Clears sessions, drains queued learning, and waits for the final persistence write. */
    suspend fun closeAndFlush() {
        closeSessionStates().forEach(SessionState::close)
        statistics.closeAndFlush()
    }

    private fun closeSessionStates(): List<SessionState> =
        synchronized(lock) {
            if (!closed.compareAndSet(false, true)) return@synchronized emptyList()
            val copy = sessionStates.values.toList()
            sessionStates.clear()
            copy
        }

    /** Session resources retained by the registry until replacement or closure. */
    private data class OpenedSession(
        val session: IntellijCompletionSession,
        val previous: SessionState?,
    )

    private data class SessionState(
        val mruSource: TerminalSessionMruCompletionSource,
    ) : AutoCloseable {
        /** Clears session-local learning. */
        override fun close() {
            mruSource.clear()
        }
    }

    private companion object {
        private const val DEFAULT_SESSION_MRU_CAPACITY = 128
    }
}
