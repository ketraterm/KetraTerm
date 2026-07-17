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

import io.github.ketraterm.completion.api.TerminalCommandStatsCompletionSource
import io.github.ketraterm.completion.api.TerminalCompletionPersistencePolicy
import io.github.ketraterm.completion.model.TerminalCommandCompletionStatsSnapshot
import io.github.ketraterm.ui.swing.host.SwingCompletionContext
import io.github.ketraterm.ui.swing.host.SwingCompletionFeedbackRecorder
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionFeedback
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionFeedbackHandler

/**
 * Standalone persistence policy around the shared Swing completion feedback adapter.
 *
 * @param statsSource mutable shared command-statistics source.
 * @param persistSnapshot optional standalone snapshot persistence callback.
 * @param submitMutation dispatcher for serialized statistics mutations.
 * @param clockEpochMillis host wall-clock supplier.
 */
internal class StandaloneCompletionFeedbackRecorder(
    statsSource: TerminalCommandStatsCompletionSource,
    persistSnapshot: ((TerminalCommandCompletionStatsSnapshot) -> Unit)? = null,
    submitMutation: (() -> Unit) -> Unit = { mutation -> mutation() },
    clockEpochMillis: () -> Long = System::currentTimeMillis,
) {
    private val delegate =
        SwingCompletionFeedbackRecorder(
            statsSource = statsSource,
            submitMutation = submitMutation,
            afterMutation = persistSnapshot,
            allowsCommand = TerminalCompletionPersistencePolicy::allowsCommand,
            clockEpochMillis = clockEpochMillis,
        )

    /** Creates a feedback handler bound to one standalone terminal context. */
    fun createHandler(
        profileId: String?,
        workingDirectoryUriProvider: () -> String?,
    ): SwingShellSuggestionFeedbackHandler =
        delegate.createHandler {
            SwingCompletionContext(
                profileId = profileId,
                workingDirectoryUri = workingDirectoryUriProvider(),
            )
        }

    /** Records one popup feedback event using the supplied standalone context. */
    fun record(
        feedback: SwingShellSuggestionFeedback,
        profileId: String?,
        workingDirectoryUri: String?,
    ) {
        delegate.record(
            feedback,
            SwingCompletionContext(
                profileId = profileId,
                workingDirectoryUri = workingDirectoryUri,
            ),
        )
    }
}
