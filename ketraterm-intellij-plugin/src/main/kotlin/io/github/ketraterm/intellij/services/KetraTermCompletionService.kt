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

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import io.github.ketraterm.completion.api.*
import io.github.ketraterm.completion.host.TerminalBoundedDirectoryScanner
import io.github.ketraterm.completion.host.TerminalDirectoryScanner
import io.github.ketraterm.completion.host.TerminalLocalFileSystemProvider
import io.github.ketraterm.completion.model.TerminalCommandSpec
import io.github.ketraterm.completion.model.TerminalCommandSpecs
import io.github.ketraterm.completion.persistence.TerminalCompletionLearningRepository
import io.github.ketraterm.completion.persistence.TerminalCompletionStatsStore
import io.github.ketraterm.intellij.settings.KetraTermIntellijSettings
import io.github.ketraterm.intellij.ui.IntellijCompletionContext
import io.github.ketraterm.intellij.ui.IntellijCompletionSuggestionProvider
import io.github.ketraterm.session.TerminalShellIntegrationCommandLifecycle
import io.github.ketraterm.session.TerminalShellIntegrationCommandMetadata
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionFeedbackHandler
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionProvider
import io.github.ketraterm.workspace.TerminalWorkspaceTab
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Application-level owner of IntelliJ completion learning and session sources.
 *
 * The service owns persistent statistics and one [IntellijCompletionRegistry].
 * IntelliJ disposal closes all session providers before closing persistence.
 */
@Service(Service.Level.APP)
internal class KetraTermCompletionService(
    coroutineScope: CoroutineScope,
) : Disposable {
    private val settings = KetraTermIntellijSettings.getInstance()
    private val learningStore = TerminalCompletionSources.learningStore()
    private val learningRepository =
        TerminalCompletionLearningRepository(
            learningStore = learningStore,
            initialPersistencePath =
            PathManager
                .getSystemDir()
                .resolve("ketraterm")
                .resolve(TerminalCompletionStatsStore.currentFileName()),
            persistenceEnabled = settings.completionLearningPersistenceEnabled(),
        )
    private val registry =
        IntellijCompletionRegistry(
            statsSource = learningStore,
            learningRepository = learningRepository,
            coroutineScope = coroutineScope,
        )
    private val settingsListener: () -> Unit = {
        registry.setPersistenceEnabled(settings.completionLearningPersistenceEnabled())
    }

    init {
        settings.addChangeListener(settingsListener)
    }

    /**
     * Creates completion resources bound to one terminal workspace tab.
     *
     * @param project IntelliJ project used for project-aware VFS and Git queries.
     * @param tab terminal tab providing identity, profile, and working-directory state.
     * @return session resources that the owning terminal pane must close.
     * @throws IllegalStateException if application-level completion has been disposed.
     */
    fun openSession(
        project: Project,
        tab: TerminalWorkspaceTab,
    ): IntellijCompletionSession {
        val workingDirectoryUriProvider = { tab.currentWorkingDirectoryUri }
        return registry.openSession(
            IntellijCompletionSessionContext(
                sessionId = tab.id,
                profileId = tab.profile.id,
                workingDirectoryUriProvider = workingDirectoryUriProvider,
                shellCapabilities = tab.profile.kind.intellijCompletionShellCapabilities(),
                additionalSources =
                    listOf(
                        TerminalCompletionSourceEntry(
                            intellijGitCompletionSource(
                                loader = IntellijGitCompletionLoader(project)::load,
                                workingDirectoryUriProvider = workingDirectoryUriProvider,
                            ),
                            TerminalCompletionSourcePrior.GIT_REFERENCE,
                        ),
                        TerminalCompletionSourceEntry(
                            intellijGitStatusPathCompletionSource(
                                loader = IntellijGitStatusPathLoader(project)::load,
                                workingDirectoryUriProvider = workingDirectoryUriProvider,
                            ),
                            TerminalCompletionSourcePrior.GIT_STATUS_PATH,
                        ),
                        TerminalCompletionSourceEntry(
                            intellijGradleTaskCompletionSource(
                                loader = IntellijGradleTaskLoader(project)::load,
                                workingDirectoryUriProvider = workingDirectoryUriProvider,
                            ),
                            TerminalCompletionSourcePrior.GRADLE_TASK,
                        ),
                        TerminalCompletionSourceEntry(
                            intellijProjectFileCompletionSource(
                                loader = IntellijProjectFileLoader(project)::load,
                                workingDirectoryUriProvider = workingDirectoryUriProvider,
                            ),
                            TerminalCompletionSourcePrior.PROJECT_FUZZY_PATH,
                        ),
                    ),
                directoryScanner = IntellijProjectDirectoryScanner(project),
            ),
        )
    }

    /**
     * Records one shell-integration command completion for MRU and learned ranking.
     *
     * Privacy policy is applied before any command is persisted.
     *
     * @param tab terminal tab that executed the command.
     * @param metadata trusted shell-integration command lifecycle metadata.
     */
    fun recordFinishedCommand(
        tab: TerminalWorkspaceTab,
        metadata: TerminalShellIntegrationCommandMetadata,
    ) {
        registry.recordFinishedCommand(
            sessionId = tab.id,
            profileId = tab.profile.id,
            metadata = metadata,
        )
    }

    /** Closes session sources. */
    override fun dispose() {
        settings.removeChangeListener(settingsListener)
        registry.close()
    }

    companion object {
        /**
         * Returns the application service instance.
         *
         * @return IntelliJ-managed completion service.
         */
        fun getInstance(): KetraTermCompletionService = service()
    }
}

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
 * @param coroutineScope host lifecycle scope that parents completion work, or
 * `null` for a registry-owned test scope.
 * @throws IllegalArgumentException if [sessionMruCapacity] is not positive.
 */
internal class IntellijCompletionRegistry(
    specs: List<TerminalCommandSpec> = TerminalCommandSpecs.defaults(),
    private val statsSource: TerminalCompletionLearningStore = TerminalCompletionSources.learningStore(commandSpecs = specs),
    learningRepository: TerminalCompletionLearningRepository = TerminalCompletionLearningRepository(statsSource),
    private val sessionMruCapacity: Int = DEFAULT_SESSION_MRU_CAPACITY,
    coroutineScope: CoroutineScope? = null,
) : AutoCloseable {
    init {
        require(sessionMruCapacity > 0) { "sessionMruCapacity must be > 0, was $sessionMruCapacity" }
    }

    /** Defensive immutable copy of the command specifications used by sessions. */
    private val commandSpecs: List<TerminalCommandSpec> = specs.toList()
    private val lock = Any()
    private val closed = AtomicBoolean()
    private val sessionStates = HashMap<String, SessionState>()
    private val ownedScope = if (coroutineScope == null) CoroutineScope(SupervisorJob() + Dispatchers.Default) else null
    private val completionScope = coroutineScope ?: requireNotNull(ownedScope)
    private val statistics =
        IntellijCompletionStatisticsCoordinator(
            repository = learningRepository,
            coroutineScope = completionScope,
        )
    private val specSource = TerminalCompletionSources.fromSpecs(commandSpecs)

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
                learnedStatsProvider = statsSource::snapshotAll,
            )
        try {
            val fileSystemProvider = TerminalLocalFileSystemProvider(scanner = context.directoryScanner)
            val sources =
                buildList {
                    add(
                        TerminalCompletionSourceEntry(
                            mruSource,
                            priority = TerminalCompletionSourcePrior.SESSION_MRU
                        )
                    )
                    add(
                        TerminalCompletionSourceEntry(
                            specSource,
                            priority = TerminalCompletionSourcePrior.STATIC_SPECIFICATION,
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
                IntellijCompletionSuggestionProvider(
                    engine =
                        TerminalCompletionEngines.fromSources(
                            sources = sources,
                            commandSpecs = commandSpecs,
                            learnedStatsProvider = statsSource::snapshotAll,
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
        if (successful) {
            synchronized(lock) { sessionStates[sessionId]?.mruSource }
                ?.recordSuccessfulCommand(command, profileId, metadata.workingDirectoryUri)
        }
        statistics.recordFinishedCommand(profileId, metadata)
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

    /**
     * Clears sessions and releases learning resources. Closing is idempotent.
     */
    override fun close() {
        val states =
            synchronized(lock) {
                if (!closed.compareAndSet(false, true)) return
                val copy = sessionStates.values.toList()
                sessionStates.clear()
                copy
            }
        states.forEach(SessionState::close)
        statistics.close()
        ownedScope?.cancel()
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

/**
 * Host context used to create one testable IntelliJ completion session.
 *
 * @property sessionId non-blank stable workspace-session identifier.
 * @property profileId non-blank stable terminal profile identifier.
 * @property workingDirectoryUriProvider thread-safe supplier for the latest URI.
 * @property shellCapabilities shell syntax and quoting capabilities.
 * @property additionalSources project-aware completion sources for this session.
 * @property directoryScanner suspending bounded directory scanner.
 * @throws IllegalArgumentException if [sessionId] or [profileId] is blank.
 */
internal data class IntellijCompletionSessionContext(
    val sessionId: String,
    val profileId: String,
    val workingDirectoryUriProvider: () -> String?,
    val shellCapabilities: TerminalShellCapabilities,
    val additionalSources: List<TerminalCompletionSourceEntry> = emptyList(),
    val directoryScanner: TerminalDirectoryScanner = TerminalBoundedDirectoryScanner(),
) {
    init {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        require(profileId.isNotBlank()) { "profileId must not be blank" }
    }

    fun swingContext(): IntellijCompletionContext =
        IntellijCompletionContext(
            profileId = profileId,
            workingDirectoryUri = workingDirectoryUriProvider(),
            shellCapabilities = shellCapabilities,
        )
}

/**
 * Session-owned completion resources consumed by one IntelliJ terminal pane.
 *
 * @property provider popup-facing suggestion provider.
 * @property feedbackHandler acceptance and dismissal learning handler.
 * @property closeAction registry callback that removes and closes this session.
 */
internal class IntellijCompletionSession(
    val provider: SwingShellSuggestionProvider,
    val feedbackHandler: SwingShellSuggestionFeedbackHandler,
    private val closeAction: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean()

    /** Releases registry-owned resources idempotently. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        closeAction()
    }
}
