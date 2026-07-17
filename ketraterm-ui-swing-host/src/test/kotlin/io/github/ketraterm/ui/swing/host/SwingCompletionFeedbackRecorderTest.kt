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

import io.github.ketraterm.completion.api.TerminalCompletionPersistencePolicy
import io.github.ketraterm.completion.api.TerminalCompletionSources
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestion
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionFeedback
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionFeedbackKind
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionRequest
import kotlin.test.Test
import kotlin.test.assertTrue

class SwingCompletionFeedbackRecorderTest {
    @Test
    fun `privacy policy sees leading whitespace before feedback is learned`() {
        val statsSource = TerminalCompletionSources.commandStats(commandSpecs = emptyList())
        val recorder =
            SwingCompletionFeedbackRecorder(
                statsSource = statsSource,
                allowsCommand = TerminalCompletionPersistencePolicy::allowsCommand,
            )

        recorder.record(
            feedback =
                SwingShellSuggestionFeedback(
                    kind = SwingShellSuggestionFeedbackKind.ACCEPTED,
                    suggestion =
                        SwingShellSuggestion(
                            replacementText = "git status",
                            replacementStartOffset = 1,
                            replacementEndOffset = 3,
                            source = "spec",
                            kind = "COMMAND",
                        ),
                    index = 0,
                    request =
                        SwingShellSuggestionRequest(
                            commandText = " gi",
                            cursorOffset = 3,
                            anchorColumn = 3,
                            anchorRow = 0,
                        ),
                ),
            context = SwingCompletionContext(profileId = "bash"),
        )

        assertTrue(statsSource.snapshotAll().commandStats.isEmpty())
        assertTrue(statsSource.snapshotAll().shapeStats.isEmpty())
        assertTrue(statsSource.snapshotAll().feedbackStats.isEmpty())
    }
}
