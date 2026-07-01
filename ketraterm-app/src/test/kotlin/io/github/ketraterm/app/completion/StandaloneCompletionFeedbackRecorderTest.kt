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

import io.github.ketraterm.completion.api.TerminalCompletionCandidateKind
import io.github.ketraterm.completion.api.TerminalCompletionRequest
import io.github.ketraterm.completion.model.TerminalCommandCompletionStats
import io.github.ketraterm.completion.model.TerminalCommandCompletionStatsSnapshot
import io.github.ketraterm.completion.model.TerminalCompletionFeedbackStats
import io.github.ketraterm.completion.model.TerminalCompletionTokenPosition
import io.github.ketraterm.completion.source.TerminalCommandStatsCompletionSource
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
        assertEquals(
            listOf(
                TerminalCompletionFeedbackStats(
                    source = "spec",
                    candidateKind = TerminalCompletionCandidateKind.SUBCOMMAND,
                    tokenPosition = TerminalCompletionTokenPosition.SUBCOMMAND,
                    replacementStartOffset = 0,
                    replacementEndOffset = 5,
                    profileId = "bash",
                    workingDirectoryUri = "file:///repo",
                    acceptedCount = 1,
                    lastUsedEpochMillis = 1_000L,
                ),
            ),
            persisted.single().feedbackStats,
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
    fun `default delete feedback records same Unicode command accepted by Swing handler`() {
        val source = TerminalCommandStatsCompletionSource()
        val persisted = ArrayList<TerminalCommandCompletionStatsSnapshot>()
        val recorder =
            StandaloneCompletionFeedbackRecorder(
                statsSource = source,
                persistSnapshot = persisted::add,
                clockEpochMillis = { 2_500L },
            )

        recorder.record(
            feedback =
                feedback(
                    kind = SwingShellSuggestionFeedbackKind.ACCEPTED,
                    commandText = "echo \uD83D\uDE02",
                    replacementText = "ok",
                    replacementStartOffset = -1,
                    replacementEndOffset = -1,
                    deleteCount = 1,
                    suggestionKind = TerminalCompletionCandidateKind.ARGUMENT,
                ),
            profileId = "bash",
            workingDirectoryUri = "file:///repo",
        )

        assertEquals("echo ok", source.snapshot().single().commandLine)
        assertEquals(
            "echo ok",
            persisted
                .single()
                .commandStats
                .single()
                .commandLine,
        )
    }

    @Test
    fun `default delete feedback with malformed cursor is ignored`() {
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
                    commandText = "echo \uD83D\uDE02",
                    replacementText = "ok",
                    replacementStartOffset = -1,
                    replacementEndOffset = -1,
                    deleteCount = 1,
                    cursorOffset = 6,
                    suggestionKind = TerminalCompletionCandidateKind.ARGUMENT,
                ),
            profileId = "bash",
            workingDirectoryUri = "file:///repo",
        )

        assertTrue(source.snapshot().isEmpty())
        assertEquals(0, persistCount)
    }

    @Test
    fun `sensitive resulting command is ignored and not persisted`() {
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
                    commandText = "docker login ",
                    replacementText = "--password hunter2",
                    replacementStartOffset = 13,
                    replacementEndOffset = 13,
                ),
            profileId = "bash",
            workingDirectoryUri = "file:///repo",
        )

        assertTrue(source.snapshot().isEmpty())
        assertTrue(source.shapeSnapshot().isEmpty())
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
        deleteCount: Int = -1,
        cursorOffset: Int = commandText.length,
        source: String = "spec",
        suggestionKind: TerminalCompletionCandidateKind = TerminalCompletionCandidateKind.SUBCOMMAND,
    ): SwingShellSuggestionFeedback =
        SwingShellSuggestionFeedback(
            kind = kind,
            suggestion =
                SwingShellSuggestion(
                    replacementText = replacementText,
                    source = source,
                    kind = suggestionKind.name,
                    deleteCount = deleteCount,
                    replacementStartOffset = replacementStartOffset,
                    replacementEndOffset = replacementEndOffset,
                ),
            index = 0,
            request =
                SwingShellSuggestionRequest(
                    commandText = commandText,
                    cursorOffset = cursorOffset,
                    anchorColumn = cursorOffset,
                    anchorRow = 0,
                ),
        )

    private fun completionRequest(commandLine: String): TerminalCompletionRequest =
        TerminalCompletionRequest(
            commandLine = commandLine,
            cursorOffset = commandLine.length,
            profileId = "bash",
            workingDirectoryUri = "file:///repo",
        )
}
