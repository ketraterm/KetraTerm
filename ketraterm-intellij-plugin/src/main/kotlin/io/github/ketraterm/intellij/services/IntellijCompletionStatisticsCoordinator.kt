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
import io.github.ketraterm.completion.persistence.TerminalCompletionLearningCoordinator
import io.github.ketraterm.completion.persistence.TerminalCompletionLearningRepository
import io.github.ketraterm.session.TerminalShellIntegrationCommandLifecycle
import io.github.ketraterm.session.TerminalShellIntegrationCommandMetadata
import io.github.ketraterm.ui.swing.host.SwingCompletionContext
import io.github.ketraterm.ui.swing.host.SwingCompletionFeedbackRecorder
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionFeedbackHandler
import kotlinx.coroutines.CoroutineScope

/**
 * IntelliJ adapter for learned completion statistics.
 *
 * The shared learning coordinator owns one lifecycle-scope worker for the
 * suspending repository; this class only maps IntelliJ lifecycle and Swing
 * feedback events.
 *
 * @param repository shared learning and persistence repository.
 * @param coroutineScope IntelliJ lifecycle scope used to launch infrequent events.
 */
internal class IntellijCompletionStatisticsCoordinator(
    private val repository: TerminalCompletionLearningRepository,
    coroutineScope: CoroutineScope,
) {
    private val learning = TerminalCompletionLearningCoordinator(repository, coroutineScope)
    val statsSource: TerminalCompletionLearningStore = learning.learningStore
    private val feedbackRecorder =
        SwingCompletionFeedbackRecorder(
            statsSource = statsSource,
            submitMutation = learning::submit,
            allowsCommand = TerminalCompletionPersistencePolicy::allowsCommand,
        )

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
        learning.setPersistenceEnabled(enabled)
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
        learning.recordCommandResult(
            commandLine = command,
            successful = metadata.lifecycle == TerminalShellIntegrationCommandLifecycle.SUCCEEDED,
            profileId = profileId,
            workingDirectoryUri = metadata.workingDirectoryUri,
            usedAtEpochMillis = metadata.finishedAtEpochMillis ?: System.currentTimeMillis(),
        )
    }

    /** Waits until all previously submitted learning and persistence work completes. */
    suspend fun flush() {
        learning.flush()
    }

    /** Gracefully flushes and stops the statistics worker. */
    suspend fun closeAndFlush() {
        learning.closeAndFlush()
    }

    /** Stops accepting statistics and drains queued work in the lifecycle scope. */
    fun close() {
        learning.close()
    }
}
