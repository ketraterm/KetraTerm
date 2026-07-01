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

import io.github.ketraterm.app.history.CommandPersistencePrivacyPolicy
import io.github.ketraterm.completion.*
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionFeedback
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionFeedbackHandler
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionFeedbackKind
import io.github.ketraterm.ui.swing.suggestion.commandTextAfterReplacement

/**
 * Standalone adapter from Swing popup feedback to shared completion statistics.
 *
 * The reusable Swing popup reports suggestion-level events. This adapter turns
 * those events into the command line that would result after applying the
 * suggestion, records accepted/dismissed counters in the shared stats source,
 * and asks the host to persist the compact snapshot.
 *
 * @param statsSource mutable shared command-stats completion source.
 * @param persistSnapshot callback used to persist a source snapshot.
 * @param clockEpochMillis supplies host wall-clock timestamps for feedback events.
 */
internal class StandaloneCompletionFeedbackRecorder(
    private val statsSource: TerminalCommandStatsCompletionSource,
    private val persistSnapshot: (TerminalCommandCompletionStatsSnapshot) -> Unit,
    private val clockEpochMillis: () -> Long = System::currentTimeMillis,
) {
    /**
     * Creates a Swing feedback handler bound to one terminal context.
     *
     * @param profileId stable profile id for the terminal session.
     * @param workingDirectoryUriProvider supplier for the current working-directory URI.
     * @return reusable Swing feedback handler for the session.
     */
    fun createHandler(
        profileId: String?,
        workingDirectoryUriProvider: () -> String?,
    ): SwingShellSuggestionFeedbackHandler =
        SwingShellSuggestionFeedbackHandler { feedback ->
            record(
                feedback = feedback,
                profileId = profileId,
                workingDirectoryUri = workingDirectoryUriProvider(),
            )
        }

    /**
     * Records one popup feedback event.
     *
     * Invalid replacement ranges are ignored because they cannot be mapped to a
     * trustworthy command line.
     *
     * @param feedback Swing popup feedback event.
     * @param profileId profile id active for the terminal session.
     * @param workingDirectoryUri current working-directory URI for ranking context.
     */
    fun record(
        feedback: SwingShellSuggestionFeedback,
        profileId: String?,
        workingDirectoryUri: String?,
    ) {
        val commandLine = commandLineAfterSuggestion(feedback) ?: return
        if (!CommandPersistencePrivacyPolicy.allowsCommand(commandLine)) return
        statsSource.recordSuggestionFeedback(
            commandLine = commandLine,
            feedback =
                when (feedback.kind) {
                    SwingShellSuggestionFeedbackKind.ACCEPTED -> TerminalCompletionFeedbackKind.ACCEPTED
                    SwingShellSuggestionFeedbackKind.DISMISSED -> TerminalCompletionFeedbackKind.DISMISSED
                },
            profileId = profileId,
            workingDirectoryUri = workingDirectoryUri,
            feedbackAtEpochMillis = clockEpochMillis(),
            context = feedback.context(),
        )
        persistSnapshot(statsSource.snapshotAll())
    }

    private companion object {
        private fun SwingShellSuggestionFeedback.context(): TerminalCompletionFeedbackContext? {
            val source = suggestion.source.takeIf(String::isNotBlank) ?: return null
            val candidateKind =
                runCatching {
                    TerminalCompletionCandidateKind.valueOf(suggestion.kind)
                }.getOrElse {
                    TerminalCompletionCandidateKind.ARGUMENT
                }
            return TerminalCompletionFeedbackContext(
                source = source,
                candidateKind = candidateKind,
                tokenPosition = TerminalCompletionTokenPosition.fromCandidateKind(candidateKind),
                replacementStartOffset = suggestion.replacementStartOffset,
                replacementEndOffset = suggestion.replacementEndOffset,
            )
        }

        private fun commandLineAfterSuggestion(feedback: SwingShellSuggestionFeedback): String? =
            feedback.suggestion
                .commandTextAfterReplacement(feedback.request)
                ?.trim()
                ?.takeIf(String::isNotEmpty)
    }
}
