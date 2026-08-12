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
import io.github.ketraterm.completion.api.TerminalCompletionPersistencePolicy
import io.github.ketraterm.completion.model.TerminalCommandCompletionStatsSnapshot
import io.github.ketraterm.completion.persistence.TerminalCompletionLearningWorker
import io.github.ketraterm.session.TerminalShellIntegrationCommandLifecycle
import io.github.ketraterm.session.TerminalShellIntegrationCommandMetadata
import io.github.ketraterm.ui.swing.host.SwingCompletionContext
import io.github.ketraterm.ui.swing.host.SwingCompletionFeedbackRecorder
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionFeedbackHandler
import java.util.concurrent.atomic.AtomicBoolean

/**
 * IntelliJ-owned serialization and persistence for learned completion statistics.
 *
 * This component owns the statistics executor independently from session/source
 * registration. Mutations are privacy-filtered, serialized, persisted, and
 * followed by one host notification.
 *
 * @param statsSource bounded shared statistics index.
 * @param loadStats startup snapshot loader.
 * @param persistStats snapshot persistence callback.
 * @param initialPersistenceEnabled whether disk-backed learning is initially enabled.
 * @param onStatsChanged callback invoked after loading or mutation.
 */
internal class IntellijCompletionStatisticsCoordinator(
    val statsSource: TerminalCompletionLearningStore,
    private val loadStats: () -> TerminalCommandCompletionStatsSnapshot,
    private val persistStats: (TerminalCommandCompletionStatsSnapshot) -> Unit,
    initialPersistenceEnabled: Boolean,
    private val onStatsChanged: () -> Unit,
) : AutoCloseable {
    private val persistenceEnabled = AtomicBoolean(initialPersistenceEnabled)
    private val worker = TerminalCompletionLearningWorker("intellij-completion-stats")
    private val feedbackRecorder =
        SwingCompletionFeedbackRecorder(
            statsSource = statsSource,
            submitMutation = ::executeMutation,
            allowsCommand = TerminalCompletionPersistencePolicy::allowsCommand,
        )

    init {
        if (persistenceEnabled.get()) {
            worker.submit {
                if (!persistenceEnabled.get()) return@submit
                statsSource.replaceSnapshot(loadStats())
                onStatsChanged()
            }
        }
    }

    /**
     * Enables or disables disk-backed learned completion statistics.
     *
     * Enabling loads the stored snapshot when no in-memory learning exists.
     * Otherwise, current session learning is persisted and takes precedence.
     * Disabling takes effect before already-queued mutations execute.
     *
     * @param enabled `true` to permit snapshot reads and writes.
     */
    fun setPersistenceEnabled(enabled: Boolean) {
        if (!persistenceEnabled.compareAndSet(!enabled, enabled)) return
        if (!enabled) return
        execute {
            if (!persistenceEnabled.get()) return@execute
            val current = statsSource.snapshotAll()
            if (current == TerminalCommandCompletionStatsSnapshot.EMPTY) {
                statsSource.replaceSnapshot(loadStats())
            } else {
                persistStats(current)
            }
            onStatsChanged()
        }
    }

    /** Creates a shared Swing feedback handler for one live session context. */
    fun createFeedbackHandler(contextProvider: () -> SwingCompletionContext): SwingShellSuggestionFeedbackHandler =
        feedbackRecorder.createHandler(contextProvider)

    /** Records one privacy-filtered completed command in persistent statistics. */
    fun recordFinishedCommand(
        profileId: String,
        metadata: TerminalShellIntegrationCommandMetadata,
    ) {
        val command = metadata.commandText ?: return
        if (!TerminalCompletionPersistencePolicy.allowsCommand(command)) return
        executeMutation {
            statsSource.recordCommandResult(
                commandLine = command,
                successful = metadata.lifecycle == TerminalShellIntegrationCommandLifecycle.SUCCEEDED,
                profileId = profileId,
                workingDirectoryUri = metadata.workingDirectoryUri,
                usedAtEpochMillis = metadata.finishedAtEpochMillis ?: System.currentTimeMillis(),
            )
        }
    }

    private fun executeMutation(mutation: () -> Unit) {
        execute {
            mutation()
            if (persistenceEnabled.get()) persistStats(statsSource.snapshotAll())
            onStatsChanged()
        }
    }

    private fun execute(action: () -> Unit) {
        worker.submit(action)
    }

    /** Drains queued mutations and closes the statistics worker idempotently. */
    override fun close() {
        worker.close()
    }
}
