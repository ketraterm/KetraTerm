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
package io.github.ketraterm.app.completion

import io.github.ketraterm.completion.api.TerminalCompletionLearningStore
import io.github.ketraterm.completion.persistence.TerminalCompletionLearningCoordinator
import io.github.ketraterm.ui.swing.host.SwingCompletionContext
import io.github.ketraterm.ui.swing.host.SwingCompletionFeedbackRecorder
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionFeedbackHandler
import kotlinx.coroutines.CoroutineScope
import java.nio.file.Path

/**
 * Standalone owner of serialized completion learning and optional persistence.
 *
 * The shared coordinator updates bounded memory synchronously and conflates
 * pending disk generations in the application's lifecycle scope. This adapter
 * only maps standalone context and Swing feedback vocabulary.
 */
internal class StandaloneCompletionStatisticsCoordinator(
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

    /** Creates a feedback handler whose exact learning is recorded immediately. */
    fun createFeedbackHandler(
        profileId: String?,
        workingDirectoryUriProvider: () -> String?,
    ): SwingShellSuggestionFeedbackHandler =
        feedbackRecorder.createHandler {
            SwingCompletionContext(
                profileId = profileId,
                workingDirectoryUri = workingDirectoryUriProvider(),
            )
        }

    /** Records one privacy-filtered shell command result in bounded memory. */
    fun recordFinishedCommand(
        commandLine: String,
        successful: Boolean,
        profileId: String?,
        workingDirectoryUri: String?,
        usedAtEpochMillis: Long,
    ) {
        learning.recordCommandResult(
            commandLine = commandLine,
            successful = successful,
            profileId = profileId,
            workingDirectoryUri = workingDirectoryUri,
            usedAtEpochMillis = usedAtEpochMillis,
        )
    }

    /** Enables or disables the coordinator's fixed persistence destination. */
    fun setPersistenceEnabled(enabled: Boolean) {
        learning.setPersistenceEnabled(enabled)
    }

    /** Waits for hydration, prior persistence controls, and the latest requested write. */
    suspend fun flush() {
        learning.flush()
    }

    /** Gracefully flushes and stops the statistics worker. */
    suspend fun closeAndFlush() {
        learning.closeAndFlush()
    }

    /** Stops accepting statistics and drains persistence control work. */
    fun close() {
        learning.close()
    }
}
