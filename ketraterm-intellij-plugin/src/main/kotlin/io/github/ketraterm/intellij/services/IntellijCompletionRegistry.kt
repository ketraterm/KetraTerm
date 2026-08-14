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

import io.github.ketraterm.completion.api.TerminalCompletionLearningStore
import io.github.ketraterm.completion.api.TerminalCompletionSessionRegistry
import io.github.ketraterm.completion.host.TerminalLocalFileSystemProvider
import io.github.ketraterm.completion.model.TerminalCommandSpec
import io.github.ketraterm.completion.model.TerminalCommandSpecs
import io.github.ketraterm.completion.persistence.TerminalCompletionLearningRepository
import io.github.ketraterm.session.TerminalShellIntegrationCommandLifecycle
import io.github.ketraterm.session.TerminalShellIntegrationCommandMetadata
import io.github.ketraterm.ui.swing.host.SwingCompletionSuggestionProvider
import kotlinx.coroutines.CoroutineScope

/**
 * Plugin-owned bridge from IntelliJ session context to shared completion sessions and learned statistics.
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
    statsSource: TerminalCompletionLearningStore = TerminalCompletionLearningStore(commandSpecs = specs),
    learningRepository: TerminalCompletionLearningRepository = TerminalCompletionLearningRepository(statsSource),
    sessionMruCapacity: Int = DEFAULT_SESSION_MRU_CAPACITY,
    coroutineScope: CoroutineScope,
) : AutoCloseable {
    private val lock = Any()
    private var closed = false
    private val sessions =
        TerminalCompletionSessionRegistry(
            commandSpecs = specs,
            learningStore = statsSource,
            sessionMruCapacity = sessionMruCapacity,
        )
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
        synchronized(lock) {
            check(!closed) { "IntelliJ completion registry is closed" }
            return createSession(context)
        }
    }

    private fun createSession(context: IntellijCompletionSessionContext): IntellijCompletionSession {
        val fileSystemProvider = TerminalLocalFileSystemProvider(scanner = context.directoryScanner)
        val completionSession =
            sessions.openSession(
                sessionId = context.sessionId,
                fileSystemProvider = fileSystemProvider,
                additionalSources = context.additionalSources,
            )
        try {
            val provider =
                SwingCompletionSuggestionProvider(
                    engine = completionSession.engine,
                    contextProvider = { context.swingContext() },
                )
            return IntellijCompletionSession(
                provider = provider,
                feedbackHandler = statistics.createFeedbackHandler(context::swingContext),
                closeAction = completionSession::close,
            )
        } catch (failure: Throwable) {
            completionSession.close()
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
            if (closed) return
            if (successful) {
                sessions.recordSuccessfulCommand(
                    sessionId = sessionId,
                    commandLine = command,
                    profileId = profileId,
                    workingDirectoryUri = metadata.workingDirectoryUri,
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
        synchronized(lock) {
            if (closed) return
            statistics.setPersistenceEnabled(enabled)
        }
    }

    /** Clears sessions and releases learning resources. Closing is idempotent. */
    override fun close() {
        if (beginClose()) sessions.close()
        statistics.close()
    }

    /** Clears sessions, drains queued learning, and waits for the final persistence write. */
    suspend fun closeAndFlush() {
        if (beginClose()) sessions.close()
        statistics.closeAndFlush()
    }

    private fun beginClose(): Boolean =
        synchronized(lock) {
            if (closed) return@synchronized false
            closed = true
            true
        }

    private companion object {
        private const val DEFAULT_SESSION_MRU_CAPACITY = 128
    }
}
