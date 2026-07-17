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
import io.github.ketraterm.completion.history.CommandPersistencePrivacyPolicy
import io.github.ketraterm.completion.model.*
import io.github.ketraterm.intellij.ui.IntellijCompletionContext
import io.github.ketraterm.intellij.ui.IntellijCompletionSuggestionProvider
import io.github.ketraterm.session.TerminalShellIntegrationCommandLifecycle
import io.github.ketraterm.session.TerminalShellIntegrationCommandMetadata
import io.github.ketraterm.ui.swing.suggestion.*
import io.github.ketraterm.workspace.TerminalWorkspaceTab
import io.github.ketraterm.workspace.persistence.CommandCompletionStatsStore
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Application-level owner of IntelliJ completion learning and session sources.
 *
 * The service owns persistent statistics and one [IntellijCompletionRegistry].
 * IntelliJ disposal closes all session providers before closing persistence.
 */
@Service(Service.Level.APP)
internal class KetraTermCompletionService : Disposable {
    private val statsStore =
        CommandCompletionStatsStore(
            PathManager.getSystemDir().resolve("ketraterm").resolve("completion-stats-v1.tsv"),
        )
    private val registry =
        IntellijCompletionRegistry(
            loadStats = statsStore::loadSnapshot,
            persistStats = statsStore::persist,
        )

    /**
     * Creates completion resources bound to one terminal workspace tab.
     *
     * @param project IntelliJ project used for project-aware VFS and Git snapshots.
     * @param tab terminal tab providing identity, profile, and working-directory state.
     * @return session resources that the owning terminal pane must close.
     * @throws IllegalStateException if application-level completion has been disposed.
     */
    fun openSession(
        project: Project,
        tab: TerminalWorkspaceTab,
    ): IntellijCompletionSession =
        registry.openSession(
            IntellijCompletionSessionContext(
                sessionId = tab.id,
                profileId = tab.profile.id,
                workingDirectoryUriProvider = { tab.currentWorkingDirectoryUri },
                shellCapabilities = tab.profile.kind.intellijCompletionShellCapabilities(),
                gitBranchLoader = IntellijGitBranchLoader(project)::load,
                directoryScanner = IntellijProjectDirectoryScanner(project),
            ),
        )

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

    /** Closes session sources, background workers, and the statistics store. */
    override fun dispose() {
        try {
            registry.close()
        } finally {
            statsStore.close()
        }
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
 * Statistics mutations and persistence are serialized on a dedicated executor;
 * directory and domain snapshots are delegated to [snapshotService]. Session
 * registration is synchronized and replacing an existing session id closes its
 * previous resources.
 *
 * @param specs immutable command specifications shared by every session.
 * @property statsSource bounded learned-statistics source.
 * @property loadStats startup snapshot loader executed on the statistics worker.
 * @property persistStats snapshot writer executed after statistics mutations.
 * @property sessionMruCapacity positive per-session MRU capacity.
 * @property snapshotService application-owned asynchronous snapshot scheduler.
 * @throws IllegalArgumentException if [sessionMruCapacity] is not positive.
 */
internal class IntellijCompletionRegistry(
    specs: List<TerminalCommandSpec> = TerminalCommandSpecs.defaults(),
    private val statsSource: TerminalCommandStatsCompletionSource = TerminalCompletionSources.commandStats(commandSpecs = specs),
    private val loadStats: () -> TerminalCommandCompletionStatsSnapshot = { TerminalCommandCompletionStatsSnapshot() },
    private val persistStats: (TerminalCommandCompletionStatsSnapshot) -> Unit = {},
    private val sessionMruCapacity: Int = DEFAULT_SESSION_MRU_CAPACITY,
    private val snapshotService: IntellijCompletionSnapshotService = IntellijCompletionSnapshotService(),
) : AutoCloseable {
    /** Defensive immutable copy of the command specifications used by sessions. */
    val commandSpecs: List<TerminalCommandSpec> = specs.toList()
    private val lock = Any()
    private val closed = AtomicBoolean()
    private val statsExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "intellij-completion-stats").apply { isDaemon = true }
        }
    private val sessionStates = HashMap<String, SessionState>()
    private val specSource =
        TerminalCompletionSources
            .fromSpecs(
                specs = commandSpecs,
                shapeStatsProvider = statsSource::shapeSnapshot,
            ).let(::feedbackAware)

    init {
        require(sessionMruCapacity > 0) { "sessionMruCapacity must be > 0, was $sessionMruCapacity" }
        statsExecutor.execute {
            statsSource.replaceSnapshot(loadStats())
            notifyAllSourcesChanged()
        }
    }

    /**
     * Creates and registers all completion sources for one terminal session.
     *
     * @param context host capabilities and snapshot adapters for the session.
     * @return closeable session-facing provider and feedback resources.
     * @throws IllegalStateException if this registry is closed.
     */
    fun openSession(context: IntellijCompletionSessionContext): IntellijCompletionSession {
        check(!closed.get()) { "IntelliJ completion registry is closed" }
        val notifier = SessionSourceChangeNotifier()
        val mruSource =
            TerminalCompletionSources.sessionMru(
                capacity = sessionMruCapacity,
                commandSpecs = commandSpecs,
            )
        val fileSystemProvider =
            snapshotService.createDirectoryProvider(
                onSnapshotChanged = notifier::notifyChanged,
                scanner = context.directoryScanner,
            )
        val gitBranchProvider =
            context.gitBranchLoader?.let { loader ->
                snapshotService.createGitBranchProvider(
                    workingDirectoryUriProvider = context.workingDirectoryUriProvider,
                    loader = loader,
                    onSnapshotChanged = notifier::notifyChanged,
                )
            }
        val sources =
            buildList {
                add(TerminalCompletionSourceEntry(feedbackAware(mruSource), priority = SESSION_MRU_PRIORITY))
                add(TerminalCompletionSourceEntry(feedbackAware(statsSource), priority = PERSISTENT_STATS_PRIORITY))
                add(TerminalCompletionSourceEntry(specSource, priority = SPEC_PRIORITY))
                add(
                    TerminalCompletionSourceEntry(
                        TerminalCompletionSources.path(
                            fileSystemProvider = fileSystemProvider,
                            commandSpecs = commandSpecs,
                        ),
                        priority = PATH_PRIORITY,
                    ),
                )
                if (gitBranchProvider != null) {
                    add(
                        TerminalCompletionSourceEntry(
                            feedbackAware(
                                TerminalCompletionSources.valueDomain(
                                    domain = TerminalCompletionValueDomain.GIT_BRANCH,
                                    sourceId = SOURCE_INTELLIJ_GIT_BRANCH,
                                    valuesProvider = gitBranchProvider::values,
                                    commandSpecs = commandSpecs,
                                ),
                            ),
                            priority = DYNAMIC_DOMAIN_PRIORITY,
                        ),
                    )
                }
            }
        val provider =
            IntellijCompletionSuggestionProvider(
                engine = TerminalCompletionEngines.fromSources(sources, commandSpecs),
                contextProvider = {
                    IntellijCompletionContext(
                        profileId = context.profileId,
                        workingDirectoryUri = context.workingDirectoryUriProvider(),
                        shellCapabilities = context.shellCapabilities,
                    )
                },
            )
        val state = SessionState(mruSource, fileSystemProvider, gitBranchProvider, notifier)
        val previous = synchronized(lock) { sessionStates.put(context.sessionId, state) }
        previous?.close()
        return IntellijCompletionSession(
            provider = provider,
            feedbackHandler = createFeedbackHandler(context),
            commandSpecs = commandSpecs,
            shellCapabilities = context.shellCapabilities,
            notifier = notifier,
            closeAction = { removeSession(context.sessionId, state) },
        )
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
        if (!CommandPersistencePrivacyPolicy.allowsCommand(command)) return
        executeStatsMutation {
            statsSource.recordCommandResult(
                commandLine = command,
                successful = successful,
                profileId = profileId,
                workingDirectoryUri = metadata.workingDirectoryUri,
                usedAtEpochMillis = metadata.finishedAtEpochMillis ?: System.currentTimeMillis(),
            )
        }
    }

    private fun createFeedbackHandler(context: IntellijCompletionSessionContext): SwingShellSuggestionFeedbackHandler =
        SwingShellSuggestionFeedbackHandler { feedback ->
            val commandLine = feedback.commandLineAfterSuggestion() ?: return@SwingShellSuggestionFeedbackHandler
            if (!CommandPersistencePrivacyPolicy.allowsCommand(commandLine)) return@SwingShellSuggestionFeedbackHandler
            executeStatsMutation {
                statsSource.recordSuggestionFeedback(
                    commandLine = commandLine,
                    feedback = feedback.kind.toCompletionFeedbackKind(),
                    profileId = context.profileId,
                    workingDirectoryUri = context.workingDirectoryUriProvider(),
                    feedbackAtEpochMillis = System.currentTimeMillis(),
                    context = feedback.completionContext(),
                )
            }
        }

    private fun executeStatsMutation(mutation: () -> Unit) {
        if (closed.get()) return
        runCatching {
            statsExecutor.execute {
                mutation()
                persistStats(statsSource.snapshotAll())
                notifyAllSourcesChanged()
            }
        }
    }

    private fun notifyAllSourcesChanged() {
        val notifiers = synchronized(lock) { sessionStates.values.map(SessionState::notifier) }
        notifiers.forEach(SessionSourceChangeNotifier::notifyChanged)
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

    private fun feedbackAware(source: TerminalCompletionSource): TerminalCompletionSource =
        TerminalCompletionSources.feedbackAware(source, statsSource::feedbackSnapshot)

    /**
     * Closes sessions and snapshot workers, then drains queued statistics work.
     *
     * Closing is idempotent. Interruption while awaiting the statistics worker
     * is restored on the calling thread before pending work is cancelled.
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val states =
            synchronized(lock) {
                val copy = sessionStates.values.toList()
                sessionStates.clear()
                copy
            }
        states.forEach(SessionState::close)
        snapshotService.close()
        statsExecutor.shutdown()
        val terminated =
            try {
                statsExecutor.awaitTermination(STATS_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
        if (!terminated) {
            statsExecutor.shutdownNow()
        }
    }

    /** Session resources retained by the registry until replacement or closure. */
    private data class SessionState(
        val mruSource: TerminalSessionMruCompletionSource,
        val fileSystemProvider: IntellijAsyncFileSystemProvider,
        val gitBranchProvider: IntellijGitBranchCompletionProvider?,
        val notifier: SessionSourceChangeNotifier,
    ) {
        /** Closes notification and dynamic providers and clears session learning. */
        fun close() {
            notifier.close()
            mruSource.clear()
            fileSystemProvider.close()
            gitBranchProvider?.close()
        }
    }

    private companion object {
        private const val DEFAULT_SESSION_MRU_CAPACITY = 128
        private const val STATS_SHUTDOWN_TIMEOUT_SECONDS = 5L
        private const val PATH_PRIORITY = 125
        private const val DYNAMIC_DOMAIN_PRIORITY = 150
        private const val SESSION_MRU_PRIORITY = 100
        private const val PERSISTENT_STATS_PRIORITY = 50
        private const val SPEC_PRIORITY = 0
        private const val SOURCE_INTELLIJ_GIT_BRANCH = "intellij-git-branch"

        private fun SwingShellSuggestionFeedback.commandLineAfterSuggestion(): String? =
            suggestion.commandTextAfterReplacement(request)?.trim()?.takeIf(String::isNotEmpty)

        private fun SwingShellSuggestionFeedbackKind.toCompletionFeedbackKind(): TerminalCompletionFeedbackKind =
            when (this) {
                SwingShellSuggestionFeedbackKind.ACCEPTED -> TerminalCompletionFeedbackKind.ACCEPTED
                SwingShellSuggestionFeedbackKind.DISMISSED -> TerminalCompletionFeedbackKind.DISMISSED
            }

        private fun SwingShellSuggestionFeedback.completionContext(): TerminalCompletionFeedbackContext? {
            val source = suggestion.source.takeIf(String::isNotBlank) ?: return null
            val candidateKind =
                runCatching { TerminalCompletionCandidateKind.valueOf(suggestion.kind) }.getOrNull() ?: return null
            return TerminalCompletionFeedbackContext(
                source = source,
                candidateKind = candidateKind,
                tokenPosition = TerminalCompletionTokenPosition.fromCandidateKind(candidateKind),
            )
        }
    }
}

/**
 * Host context used to create one testable IntelliJ completion session.
 *
 * @property sessionId non-blank stable workspace-session identifier.
 * @property profileId non-blank stable terminal profile identifier.
 * @property workingDirectoryUriProvider thread-safe supplier for the latest URI.
 * @property shellCapabilities shell syntax and quoting capabilities.
 * @property gitBranchLoader optional blocking Git snapshot loader.
 * @property directoryScanner blocking bounded directory snapshot scanner.
 * @throws IllegalArgumentException if [sessionId] or [profileId] is blank.
 */
internal data class IntellijCompletionSessionContext(
    val sessionId: String,
    val profileId: String,
    val workingDirectoryUriProvider: () -> String?,
    val shellCapabilities: TerminalShellCapabilities,
    val gitBranchLoader: ((String?) -> List<TerminalCompletionDomainValue>)? = null,
    val directoryScanner: IntellijDirectoryScanner = BoundedIntellijDirectoryScanner(),
) {
    init {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        require(profileId.isNotBlank()) { "profileId must not be blank" }
    }
}

/**
 * Session-owned completion resources consumed by one IntelliJ terminal pane.
 *
 * @property provider popup-facing suggestion provider.
 * @property feedbackHandler acceptance and dismissal learning handler.
 * @property commandSpecs immutable command specifications used for triggering.
 * @property shellCapabilities shell syntax and quoting capabilities.
 * @property notifier thread-safe bridge for asynchronously published snapshots.
 * @property closeAction registry callback that removes and closes this session.
 */
internal class IntellijCompletionSession(
    val provider: SwingShellSuggestionProvider,
    val feedbackHandler: SwingShellSuggestionFeedbackHandler,
    val commandSpecs: List<TerminalCommandSpec>,
    val shellCapabilities: TerminalShellCapabilities,
    private val notifier: SessionSourceChangeNotifier,
    private val closeAction: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean()

    /**
     * Replaces the callback notified when an asynchronous source publishes.
     *
     * The callback may run on a completion worker and must perform any required
     * Swing-thread handoff. Passing `null` detaches the current callback.
     *
     * @param listener replacement callback, or `null` to detach.
     */
    fun onSourceChanged(listener: (() -> Unit)?) {
        if (closed.get()) {
            notifier.listener.set(null)
        } else {
            notifier.listener.set(listener)
        }
    }

    /** Detaches notifications and releases registry-owned resources idempotently. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        notifier.listener.set(null)
        closeAction()
    }
}

/** Thread-safe, closeable single-listener notification bridge. */
internal class SessionSourceChangeNotifier {
    /** Current source-change callback, atomically replaceable by the owning pane. */
    val listener = AtomicReference<(() -> Unit)?>(null)
    private val closed = AtomicBoolean()

    /** Invokes the current listener on the calling thread unless closed. */
    fun notifyChanged() {
        if (!closed.get()) listener.get()?.invoke()
    }

    /** Permanently suppresses notifications and releases the current listener. */
    fun close() {
        closed.set(true)
        listener.set(null)
    }
}
