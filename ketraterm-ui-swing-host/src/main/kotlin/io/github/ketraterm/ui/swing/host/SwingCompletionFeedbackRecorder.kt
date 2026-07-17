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
package io.github.ketraterm.ui.swing.host

import io.github.ketraterm.completion.api.TerminalCommandStatsCompletionSource
import io.github.ketraterm.completion.api.TerminalCompletionCandidateKind
import io.github.ketraterm.completion.model.TerminalCommandCompletionStatsSnapshot
import io.github.ketraterm.completion.model.TerminalCompletionFeedbackContext
import io.github.ketraterm.completion.model.TerminalCompletionFeedbackKind
import io.github.ketraterm.completion.model.TerminalCompletionTokenPosition
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionFeedback
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionFeedbackHandler
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionFeedbackKind
import io.github.ketraterm.ui.swing.suggestion.commandTextAfterReplacement

/**
 * Shared adapter from Swing suggestion feedback to completion statistics.
 *
 * Hosts inject privacy policy, mutation scheduling, and post-mutation snapshot
 * handling. This keeps Swing-to-completion vocabulary mapping identical while
 * leaving persistence and thread ownership in the product host.
 *
 * @param statsSource mutable completion statistics source.
 * @param submitMutation dispatcher for one bounded stats mutation.
 * @param afterMutation optional callback receiving the stable snapshot after mutation.
 * @param allowsCommand host privacy policy for the resulting command line.
 * @param clockEpochMillis host wall-clock supplier.
 */
class SwingCompletionFeedbackRecorder
@JvmOverloads
constructor(
    private val statsSource: TerminalCommandStatsCompletionSource,
    private val submitMutation: (() -> Unit) -> Unit = { mutation -> mutation() },
    private val afterMutation: ((TerminalCommandCompletionStatsSnapshot) -> Unit)? = null,
    private val allowsCommand: (String) -> Boolean = { true },
    private val clockEpochMillis: () -> Long = System::currentTimeMillis,
) {
    /**
     * Creates a feedback handler that reads current context per event.
     *
     * @param contextProvider supplier for current profile and directory state.
     * @return reusable Swing feedback handler.
     */
    fun createHandler(contextProvider: () -> SwingCompletionContext): SwingShellSuggestionFeedbackHandler =
        SwingShellSuggestionFeedbackHandler { feedback -> record(feedback, contextProvider()) }

    /**
     * Validates and records one accepted or dismissed suggestion.
     *
     * @param feedback Swing popup feedback event.
     * @param context host metadata active for the event.
     */
    fun record(
        feedback: SwingShellSuggestionFeedback,
        context: SwingCompletionContext,
    ) {
        val commandLine =
            feedback.suggestion
                .commandTextAfterReplacement(feedback.request)
                ?.takeUnless(String::isBlank)
                ?: return
        if (!allowsCommand(commandLine)) return
        val completionContext = feedback.completionContext()
        submitMutation {
            statsSource.recordSuggestionFeedback(
                commandLine = commandLine,
                feedback = feedback.kind.toCompletionKind(),
                profileId = context.profileId,
                workingDirectoryUri = context.workingDirectoryUri,
                feedbackAtEpochMillis = clockEpochMillis(),
                context = completionContext,
            )
            afterMutation?.invoke(statsSource.snapshotAll())
        }
    }

    private companion object {
        private fun SwingShellSuggestionFeedbackKind.toCompletionKind(): TerminalCompletionFeedbackKind =
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
