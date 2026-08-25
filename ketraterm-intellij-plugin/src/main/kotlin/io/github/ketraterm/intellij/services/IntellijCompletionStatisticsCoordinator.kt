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
import io.github.ketraterm.completion.persistence.TerminalCompletionLearningCoordinator
import io.github.ketraterm.session.TerminalShellIntegrationCommandLifecycle
import io.github.ketraterm.session.TerminalShellIntegrationCommandMetadata
import io.github.ketraterm.ui.swing.host.SwingCompletionContext
import io.github.ketraterm.ui.swing.host.SwingCompletionFeedbackRecorder
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionFeedbackHandler
import kotlinx.coroutines.CoroutineScope
import java.nio.file.Path

/**
 * IntelliJ adapter for learned completion statistics.
 *
 * The shared learning coordinator updates bounded memory synchronously and
 * checkpoints dirty state; this class only maps IntelliJ lifecycle and Swing
 * feedback events.
 *
 * @param statsSource shared bounded learning store.
 * @param persistencePath fixed product-owned persistence destination.
 * @param persistenceEnabled whether the fixed destination may initially be read and written.
 * @param coroutineScope IntelliJ lifecycle scope used to launch infrequent events.
 */
internal class IntellijCompletionStatisticsCoordinator(
    statsSource: TerminalCompletionLearningStore,
    persistencePath: Path,
    persistenceEnabled: Boolean,
    coroutineScope: CoroutineScope,
) {
    private val learning =
        TerminalCompletionLearningCoordinator(
            learningStore = statsSource,
            coroutineScope = coroutineScope,
            persistencePath = persistencePath,
            persistenceEnabled = persistenceEnabled,
        )
    private val feedbackRecorder =
        SwingCompletionFeedbackRecorder(
            recordSuggestionFeedback = learning::recordSuggestionFeedback,
        )

    /**
     * Enables or disables disk-backed learned completion statistics.
     *
     * The first enable loads the fixed snapshot once and merges its aggregate
     * counters with learning collected during the current session.
     * Earlier mutations remain in memory; disabling cancels a pending
     * checkpoint.
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

    /** Gracefully checkpoints dirty learning and stops the persistence worker. */
    suspend fun closeAndFlush() {
        learning.closeAndFlush()
    }
}
