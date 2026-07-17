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

/** Application-level owner of IntelliJ completion learning and session sources. */
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

    fun openSession(tab: TerminalWorkspaceTab): IntellijCompletionSession =
        registry.openSession(
            IntellijCompletionSessionContext(
                sessionId = tab.id,
                profileId = tab.profile.id,
                workingDirectoryUriProvider = { tab.currentWorkingDirectoryUri },
                shellCapabilities = tab.profile.kind.intellijCompletionShellCapabilities(),
            ),
        )

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

    override fun dispose() {
        try {
            registry.close()
        } finally {
            statsStore.close()
        }
    }

    companion object {
        fun getInstance(): KetraTermCompletionService = service()
    }
}

/** Plugin-owned composition of shared completion sources and learned statistics. */
internal class IntellijCompletionRegistry(
    specs: List<TerminalCommandSpec> = TerminalCommandSpecs.defaults(),
    private val statsSource: TerminalCommandStatsCompletionSource = TerminalCompletionSources.commandStats(commandSpecs = specs),
    private val loadStats: () -> TerminalCommandCompletionStatsSnapshot = { TerminalCommandCompletionStatsSnapshot() },
    private val persistStats: (TerminalCommandCompletionStatsSnapshot) -> Unit = {},
    private val sessionMruCapacity: Int = DEFAULT_SESSION_MRU_CAPACITY,
    private val directoryCompletionService: IntellijDirectoryCompletionService = IntellijDirectoryCompletionService(),
) : AutoCloseable {
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

    fun openSession(context: IntellijCompletionSessionContext): IntellijCompletionSession {
        check(!closed.get()) { "IntelliJ completion registry is closed" }
        val notifier = SessionSourceChangeNotifier()
        val mruSource =
            TerminalCompletionSources.sessionMru(
                capacity = sessionMruCapacity,
                commandSpecs = commandSpecs,
            )
        val fileSystemProvider = directoryCompletionService.createProvider(notifier::notifyChanged)
        val sources =
            listOf(
                TerminalCompletionSourceEntry(feedbackAware(mruSource), priority = SESSION_MRU_PRIORITY),
                TerminalCompletionSourceEntry(feedbackAware(statsSource), priority = PERSISTENT_STATS_PRIORITY),
                TerminalCompletionSourceEntry(specSource, priority = SPEC_PRIORITY),
                TerminalCompletionSourceEntry(
                    TerminalCompletionSources.path(
                        fileSystemProvider = fileSystemProvider,
                        commandSpecs = commandSpecs,
                    ),
                    priority = PATH_PRIORITY,
                ),
            )
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
        val state = SessionState(mruSource, fileSystemProvider, notifier)
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

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val states =
            synchronized(lock) {
                val copy = sessionStates.values.toList()
                sessionStates.clear()
                copy
            }
        states.forEach(SessionState::close)
        directoryCompletionService.close()
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

    private data class SessionState(
        val mruSource: TerminalSessionMruCompletionSource,
        val fileSystemProvider: IntellijAsyncFileSystemProvider,
        val notifier: SessionSourceChangeNotifier,
    ) {
        fun close() {
            notifier.close()
            mruSource.clear()
            fileSystemProvider.close()
        }
    }

    private companion object {
        private const val DEFAULT_SESSION_MRU_CAPACITY = 128
        private const val STATS_SHUTDOWN_TIMEOUT_SECONDS = 5L
        private const val PATH_PRIORITY = 125
        private const val SESSION_MRU_PRIORITY = 100
        private const val PERSISTENT_STATS_PRIORITY = 50
        private const val SPEC_PRIORITY = 0

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

/** Host context used to create one testable IntelliJ completion session. */
internal data class IntellijCompletionSessionContext(
    val sessionId: String,
    val profileId: String,
    val workingDirectoryUriProvider: () -> String?,
    val shellCapabilities: TerminalShellCapabilities,
) {
    init {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        require(profileId.isNotBlank()) { "profileId must not be blank" }
    }
}

/** Session-owned completion resources consumed by one IntelliJ terminal pane. */
internal class IntellijCompletionSession(
    val provider: SwingShellSuggestionProvider,
    val feedbackHandler: SwingShellSuggestionFeedbackHandler,
    val commandSpecs: List<TerminalCommandSpec>,
    val shellCapabilities: TerminalShellCapabilities,
    private val notifier: SessionSourceChangeNotifier,
    private val closeAction: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean()

    fun onSourceChanged(listener: (() -> Unit)?) {
        if (closed.get()) {
            notifier.listener.set(null)
        } else {
            notifier.listener.set(listener)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        notifier.listener.set(null)
        closeAction()
    }
}

internal class SessionSourceChangeNotifier {
    val listener = AtomicReference<(() -> Unit)?>(null)
    private val closed = AtomicBoolean()

    fun notifyChanged() {
        if (!closed.get()) listener.get()?.invoke()
    }

    fun close() {
        closed.set(true)
        listener.set(null)
    }
}
