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
import io.github.ketraterm.completion.api.TerminalCompletionPersistencePolicy
import io.github.ketraterm.completion.persistence.TerminalCompletionLearningRepository
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionFeedbackHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.nio.file.Path

/**
 * Standalone owner of serialized completion learning and optional persistence.
 *
 * The shared suspending repository serializes mutations and disk I/O. This
 * adapter only launches infrequent UI events in the application's lifecycle
 * scope.
 */
internal class StandaloneCompletionStatisticsCoordinator(
    private val statsSource: TerminalCompletionLearningStore,
    initialPersistencePath: Path?,
    private val coroutineScope: CoroutineScope,
) : AutoCloseable {
    private val repository =
        TerminalCompletionLearningRepository(
            learningStore = statsSource,
            initialPersistencePath = initialPersistencePath,
        )
    private val feedbackRecorder =
        StandaloneCompletionFeedbackRecorder(
            statsSource = statsSource,
            submitMutation = ::executeMutation,
        )

    init {
        coroutineScope.launch { repository.initialize() }
    }

    /** Creates a feedback handler whose mutations are serialized by this owner. */
    fun createFeedbackHandler(
        profileId: String?,
        workingDirectoryUriProvider: () -> String?,
    ): SwingShellSuggestionFeedbackHandler = feedbackRecorder.createHandler(profileId, workingDirectoryUriProvider)

    /** Records one privacy-filtered shell command result off the caller thread. */
    fun recordFinishedCommand(
        commandLine: String,
        successful: Boolean,
        profileId: String?,
        workingDirectoryUri: String?,
        usedAtEpochMillis: Long,
    ) {
        if (!TerminalCompletionPersistencePolicy.allowsCommand(commandLine)) return
        coroutineScope.launch {
            repository.mutate {
                recordCommandResult(
                    commandLine = commandLine,
                    successful = successful,
                    profileId = profileId,
                    workingDirectoryUri = workingDirectoryUri,
                    usedAtEpochMillis = usedAtEpochMillis,
                )
            }
        }
    }

    /** Enables, switches, or disables the persistence store asynchronously. */
    fun setPersistencePath(path: Path?) {
        coroutineScope.launch { repository.setPersistencePath(path) }
    }

    private fun executeMutation(mutation: () -> Unit) {
        coroutineScope.launch { repository.mutate { mutation() } }
    }

    /** Has no private worker or persistence resource to close. */
    override fun close() = Unit
}
