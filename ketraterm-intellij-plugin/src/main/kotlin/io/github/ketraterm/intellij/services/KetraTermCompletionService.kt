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
import io.github.ketraterm.completion.model.TerminalCommandCompletionStatsSnapshot
import io.github.ketraterm.completion.model.TerminalCommandSpec
import io.github.ketraterm.completion.model.TerminalCommandSpecs
import io.github.ketraterm.completion.persistence.TerminalCompletionStatsStore
import io.github.ketraterm.intellij.ui.IntellijCompletionContext
import io.github.ketraterm.intellij.ui.IntellijCompletionSuggestionProvider
import io.github.ketraterm.session.TerminalShellIntegrationCommandLifecycle
import io.github.ketraterm.session.TerminalShellIntegrationCommandMetadata
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionFeedbackHandler
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionProvider
import io.github.ketraterm.workspace.TerminalWorkspaceTab
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
        TerminalCompletionStatsStore(
            PathManager
                .getSystemDir()
                .resolve("ketraterm")
                .resolve(TerminalCompletionStatsStore.currentFileName()),
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
                providerFactories =
                    listOf(
                        IntellijGitBranchProviderFactory(IntellijGitBranchLoader(project)::load),
                        IntellijGitStatusPathProviderFactory(IntellijGitStatusPathLoader(project)::load),
                        IntellijProjectFileProviderFactory(IntellijProjectFileLoader(project)::load),
                    ),
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
 * @param statsSource bounded learned-statistics source.
 * @param loadStats startup snapshot loader executed on the statistics worker.
 * @param persistStats snapshot writer executed after statistics mutations.
 * @param sessionMruCapacity positive per-session MRU capacity.
 * @param snapshotService optional application-owned asynchronous snapshot scheduler.
 * @throws IllegalArgumentException if [sessionMruCapacity] is not positive.
 */
internal class IntellijCompletionRegistry(
    specs: List<TerminalCommandSpec> = TerminalCommandSpecs.defaults(),
    private val statsSource: TerminalCommandStatsCompletionSource = TerminalCompletionSources.commandStats(commandSpecs = specs),
    loadStats: () -> TerminalCommandCompletionStatsSnapshot = { TerminalCommandCompletionStatsSnapshot() },
    persistStats: (TerminalCommandCompletionStatsSnapshot) -> Unit = {},
    private val sessionMruCapacity: Int = DEFAULT_SESSION_MRU_CAPACITY,
    snapshotService: IntellijCompletionSnapshotService? = null,
) : AutoCloseable {
    init {
        require(sessionMruCapacity > 0) { "sessionMruCapacity must be > 0, was $sessionMruCapacity" }
    }

    /** Defensive immutable copy of the command specifications used by sessions. */
    val commandSpecs: List<TerminalCommandSpec> = specs.toList()
    private val lock = Any()
    private val closed = AtomicBoolean()
    private val sessionStates = HashMap<String, SessionState>()
    private val snapshotService = snapshotService ?: IntellijCompletionSnapshotService()
    private val statistics =
        IntellijCompletionStatisticsCoordinator(
            statsSource = statsSource,
            loadStats = loadStats,
            persistStats = persistStats,
            onStatsChanged = ::notifyAllSourcesChanged,
        )
    private val specSource =
        TerminalCompletionSources
            .fromSpecs(
                specs = commandSpecs,
                shapeStatsProvider = statsSource::shapeSnapshot,
            ).let(statistics::feedbackAware)

    /**
     * Creates and registers all completion sources for one terminal session.
     *
     * @param context host capabilities and snapshot adapters for the session.
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
        val notifier = SessionSourceChangeNotifier()
        val mruSource =
            TerminalCompletionSources.sessionMru(
                capacity = sessionMruCapacity,
                commandSpecs = commandSpecs,
            )
        val resources = ArrayList<AutoCloseable>()
        try {
            val fileSystemProvider =
                snapshotService.createDirectoryProvider(
                    onSnapshotChanged = notifier::notifyChanged,
                    scanner = context.directoryScanner,
                )
            resources += fileSystemProvider
            val providerContext =
                IntellijCompletionProviderContext(
                    commandSpecs = commandSpecs,
                    workingDirectoryUriProvider = context.workingDirectoryUriProvider,
                    snapshotService = snapshotService,
                    onSnapshotChanged = notifier::notifyChanged,
                )
            val dynamicRegistrations =
                context.providerFactories.mapNotNull { factory ->
                    factory.create(providerContext)?.also { registration -> resources.addAll(registration.resources) }
                }
            val sources =
                buildList {
                    add(
                        TerminalCompletionSourceEntry(
                            statistics.feedbackAware(mruSource),
                            priority = SESSION_MRU_SOURCE_PRIORITY
                        )
                    )
                    add(
                        TerminalCompletionSourceEntry(
                            statistics.feedbackAware(statsSource),
                            priority = PERSISTENT_STATS_SOURCE_PRIORITY
                        )
                    )
                    add(TerminalCompletionSourceEntry(specSource, priority = SPEC_SOURCE_PRIORITY))
                    add(
                        TerminalCompletionSourceEntry(
                            TerminalCompletionSources.path(
                                fileSystemProvider = fileSystemProvider,
                                commandSpecs = commandSpecs,
                            ),
                            priority = PATH_SOURCE_PRIORITY,
                        ),
                    )
                    dynamicRegistrations.mapTo(this) { registration ->
                        registration.sourceEntry.copy(
                            source = statistics.feedbackAware(registration.sourceEntry.source),
                        )
                    }
                }
            val provider =
                IntellijCompletionSuggestionProvider(
                    engine = TerminalCompletionEngines.fromSources(sources, commandSpecs),
                    contextProvider = { context.swingContext() },
                )
            val state = SessionState(mruSource, resources.toList(), notifier)
            val session =
                IntellijCompletionSession(
                    provider = provider,
                    feedbackHandler = statistics.createFeedbackHandler(context::swingContext),
                    commandSpecs = commandSpecs,
                    shellCapabilities = context.shellCapabilities,
                    notifier = notifier,
                    closeAction = { removeSession(context.sessionId, state) },
                )
            return OpenedSession(session, sessionStates.put(context.sessionId, state))
        } catch (failure: Throwable) {
            notifier.close()
            mruSource.clear()
            closeCompletionResources(resources.asReversed(), failure)
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

    /**
     * Closes sessions and snapshot workers, then drains queued statistics work.
     *
     * Closing is idempotent. Interruption while awaiting the statistics worker
     * is restored on the calling thread before pending work is cancelled.
     */
    override fun close() {
        val states =
            synchronized(lock) {
                if (!closed.compareAndSet(false, true)) return
                val copy = sessionStates.values.toList()
                sessionStates.clear()
                copy
            }
        closeCompletionResources(states + snapshotService + statistics)?.let { failure -> throw failure }
    }

    /** Session resources retained by the registry until replacement or closure. */
    private data class OpenedSession(
        val session: IntellijCompletionSession,
        val previous: SessionState?,
    )

    private data class SessionState(
        val mruSource: TerminalSessionMruCompletionSource,
        val resources: List<AutoCloseable>,
        val notifier: SessionSourceChangeNotifier,
    ) : AutoCloseable {
        /** Closes notification and dynamic providers and clears session learning. */
        override fun close() {
            notifier.close()
            mruSource.clear()
            closeCompletionResources(resources)?.let { failure -> throw failure }
        }
    }

    private companion object {
        private const val DEFAULT_SESSION_MRU_CAPACITY = 128
        private const val PATH_SOURCE_PRIORITY = 125
        private const val SESSION_MRU_SOURCE_PRIORITY = 100
        private const val PERSISTENT_STATS_SOURCE_PRIORITY = 50
        private const val SPEC_SOURCE_PRIORITY = 0
    }
}

/**
 * Host context used to create one testable IntelliJ completion session.
 *
 * @property sessionId non-blank stable workspace-session identifier.
 * @property profileId non-blank stable terminal profile identifier.
 * @property workingDirectoryUriProvider thread-safe supplier for the latest URI.
 * @property shellCapabilities shell syntax and quoting capabilities.
 * @property providerFactories additive dynamic provider factories.
 * @property directoryScanner blocking bounded directory snapshot scanner.
 * @throws IllegalArgumentException if [sessionId] or [profileId] is blank.
 */
internal data class IntellijCompletionSessionContext(
    val sessionId: String,
    val profileId: String,
    val workingDirectoryUriProvider: () -> String?,
    val shellCapabilities: TerminalShellCapabilities,
    val providerFactories: List<IntellijCompletionProviderFactory> = emptyList(),
    val directoryScanner: IntellijDirectoryScanner = BoundedIntellijDirectoryScanner(),
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
        notifier.replaceListener(if (closed.get()) null else listener)
    }

    /** Detaches notifications and releases registry-owned resources idempotently. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        notifier.replaceListener(null)
        closeAction()
    }
}

/** Thread-safe, closeable single-listener notification bridge. */
internal class SessionSourceChangeNotifier {
    private val listener = AtomicReference<(() -> Unit)?>(null)
    private val closed = AtomicBoolean()

    /** Replaces the source-change callback unless this notifier is closed. */
    fun replaceListener(replacement: (() -> Unit)?) {
        synchronized(this) {
            listener.set(if (closed.get()) null else replacement)
        }
    }

    /** Invokes the current listener on the calling thread unless closed. */
    fun notifyChanged() {
        val current = if (closed.get()) null else listener.get()
        try {
            current?.invoke()
        } catch (_: RuntimeException) {
            // One disposed or faulty UI listener must not suppress other sessions.
        }
    }

    /** Permanently suppresses notifications and releases the current listener. */
    fun close() {
        synchronized(this) {
            closed.set(true)
            listener.set(null)
        }
    }
}

private fun closeCompletionResources(
    resources: Iterable<AutoCloseable>,
    initialFailure: Throwable? = null,
): Throwable? {
    var failure = initialFailure
    for (resource in resources) {
        try {
            resource.close()
        } catch (closeFailure: Throwable) {
            if (failure == null) {
                failure = closeFailure
            } else if (failure !== closeFailure) {
                failure.addSuppressed(closeFailure)
            }
        }
    }
    return failure
}
