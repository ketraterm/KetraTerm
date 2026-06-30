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

import io.github.ketraterm.completion.TerminalCommandCompletionStats
import io.github.ketraterm.completion.TerminalCommandCompletionStatsSnapshot
import io.github.ketraterm.completion.TerminalCommandStatsCompletionSource
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestion
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionFeedback
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionFeedbackKind
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StandaloneCompletionFeedbackRecorderTest {
    @Test
    fun `accepted range suggestion records resulting command and persists snapshot`() {
        val source = TerminalCommandStatsCompletionSource()
        val persisted = ArrayList<TerminalCommandCompletionStatsSnapshot>()
        val recorder =
            StandaloneCompletionFeedbackRecorder(
                statsSource = source,
                persistSnapshot = persisted::add,
                clockEpochMillis = { 1_000L },
            )

        recorder.record(
            feedback =
                feedback(
                    kind = SwingShellSuggestionFeedbackKind.ACCEPTED,
                    commandText = "git s",
                    replacementText = "git status",
                    replacementStartOffset = 0,
                    replacementEndOffset = 5,
                ),
            profileId = "bash",
            workingDirectoryUri = "file:///repo",
        )

        assertEquals(
            listOf(
                TerminalCommandCompletionStats(
                    commandLine = "git status",
                    normalizedCommandLine = "git status",
                    profileId = "bash",
                    workingDirectoryUri = "file:///repo",
                    acceptedCount = 1,
                    lastUsedEpochMillis = 1_000L,
                ),
            ),
            source.snapshot(),
        )
        assertEquals(source.snapshotAll(), persisted.single())
        assertEquals(
            "git",
            persisted
                .single()
                .shapeStats
                .single()
                .shape.executable,
        )
    }

    @Test
    fun `dismissed token suggestion records resulting command without making it suggestible`() {
        val source = TerminalCommandStatsCompletionSource()
        val recorder =
            StandaloneCompletionFeedbackRecorder(
                statsSource = source,
                persistSnapshot = {},
                clockEpochMillis = { 2_000L },
            )

        recorder.record(
            feedback =
                feedback(
                    kind = SwingShellSuggestionFeedbackKind.DISMISSED,
                    commandText = "git s",
                    replacementText = "status",
                    replacementStartOffset = 4,
                    replacementEndOffset = 5,
                ),
            profileId = "bash",
            workingDirectoryUri = "file:///repo",
        )

        assertEquals(
            listOf(
                TerminalCommandCompletionStats(
                    commandLine = "git status",
                    normalizedCommandLine = "git status",
                    profileId = "bash",
                    workingDirectoryUri = "file:///repo",
                    dismissedCount = 1,
                    lastUsedEpochMillis = 2_000L,
                ),
            ),
            source.snapshot(),
        )
        assertTrue(source.complete(completionRequest("git s")).isEmpty())
    }

    @Test
    fun `invalid replacement range is ignored and not persisted`() {
        val source = TerminalCommandStatsCompletionSource()
        var persistCount = 0
        val recorder =
            StandaloneCompletionFeedbackRecorder(
                statsSource = source,
                persistSnapshot = { persistCount++ },
            )

        recorder.record(
            feedback =
                feedback(
                    kind = SwingShellSuggestionFeedbackKind.ACCEPTED,
                    commandText = "git s",
                    replacementText = "git status",
                    replacementStartOffset = 0,
                    replacementEndOffset = 99,
                ),
            profileId = "bash",
            workingDirectoryUri = "file:///repo",
        )

        assertTrue(source.snapshot().isEmpty())
        assertEquals(0, persistCount)
    }

    @Test
    fun `created handler reads latest working directory`() {
        val source = TerminalCommandStatsCompletionSource()
        val recorder =
            StandaloneCompletionFeedbackRecorder(
                statsSource = source,
                persistSnapshot = {},
                clockEpochMillis = { 3_000L },
            )
        var workingDirectoryUri = "file:///first"
        val handler = recorder.createHandler(profileId = "bash") { workingDirectoryUri }

        workingDirectoryUri = "file:///second"
        handler.onSuggestionFeedback(
            feedback(
                kind = SwingShellSuggestionFeedbackKind.ACCEPTED,
                commandText = "npm t",
                replacementText = "npm test",
                replacementStartOffset = 0,
                replacementEndOffset = 5,
            ),
        )

        assertEquals("file:///second", source.snapshot().single().workingDirectoryUri)
        assertEquals("file:///second", source.shapeSnapshot().single().workingDirectoryUri)
    }

    private fun feedback(
        kind: SwingShellSuggestionFeedbackKind,
        commandText: String,
        replacementText: String,
        replacementStartOffset: Int,
        replacementEndOffset: Int,
    ): SwingShellSuggestionFeedback =
        SwingShellSuggestionFeedback(
            kind = kind,
            suggestion =
                SwingShellSuggestion(
                    replacementText = replacementText,
                    replacementStartOffset = replacementStartOffset,
                    replacementEndOffset = replacementEndOffset,
                ),
            index = 0,
            request =
                SwingShellSuggestionRequest(
                    commandText = commandText,
                    cursorOffset = commandText.length,
                    anchorColumn = commandText.length,
                    anchorRow = 0,
                ),
        )

    private fun completionRequest(commandLine: String): io.github.ketraterm.completion.TerminalCompletionRequest =
        io.github.ketraterm.completion.TerminalCompletionRequest(
            commandLine = commandLine,
            cursorOffset = commandLine.length,
            profileId = "bash",
            workingDirectoryUri = "file:///repo",
        )
}
