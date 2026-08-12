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

import io.github.ketraterm.completion.api.*
import io.github.ketraterm.completion.model.TerminalCommandCompletionStats
import io.github.ketraterm.completion.model.TerminalCommandCompletionStatsSnapshot
import io.github.ketraterm.completion.model.TerminalCompletionFeedbackStats
import io.github.ketraterm.completion.model.TerminalCompletionTokenPosition
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestion
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionFeedback
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionFeedbackKind
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionRequest
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SwingCompletionFeedbackRecorderTest {
    @Test
    fun `accepted range suggestion records resulting command and publishes snapshot`() {
        val source = TerminalCompletionSources.learningStore()
        val published = ArrayList<TerminalCommandCompletionStatsSnapshot>()
        val recorder = recorder(source, afterMutation = published::add, clockEpochMillis = { 1_000L })

        recorder.record(
            feedback =
                feedback(
                    kind = SwingShellSuggestionFeedbackKind.ACCEPTED,
                    commandText = "git s",
                    replacementText = "git status",
                    replacementStartOffset = 0,
                    replacementEndOffset = 5,
                ),
            context = context(),
        )

        assertEquals(
            listOf(
                TerminalCommandCompletionStats(
                    commandLine = "git status",
                    profileId = "bash",
                    workingDirectoryUri = "file:///repo",
                    acceptedCount = 1,
                    lastUsedEpochMillis = 1_000L,
                ),
            ),
            source.snapshotAll().commandStats,
        )
        assertEquals(source.snapshotAll(), published.single())
        assertEquals(
            "git",
            published
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
                    profileId = "bash",
                    workingDirectoryUri = "file:///repo",
                    acceptedCount = 1,
                    lastUsedEpochMillis = 1_000L,
                ),
            ),
            published.single().feedbackStats,
        )
    }

    @Test
    fun `dismissed token suggestion records resulting command without making it suggestible`() =
        runBlocking {
            val source = TerminalCompletionSources.learningStore()
            val recorder = recorder(source, clockEpochMillis = { 2_000L })

            recorder.record(
                feedback =
                    feedback(
                        kind = SwingShellSuggestionFeedbackKind.DISMISSED,
                        commandText = "git s",
                        replacementText = "status",
                        replacementStartOffset = 4,
                        replacementEndOffset = 5,
                    ),
                context = context(),
            )

            assertEquals(
                listOf(
                    TerminalCommandCompletionStats(
                        commandLine = "git status",
                        profileId = "bash",
                        workingDirectoryUri = "file:///repo",
                        dismissedCount = 1,
                        lastUsedEpochMillis = 2_000L,
                    ),
                ),
                source.snapshotAll().commandStats,
            )
            assertTrue(
                TerminalCompletionEngines
                    .fromSources(TerminalCompletionSources.sessionMru(learnedStatsProvider = source::snapshotAll))
                    .complete(completionRequest("git s"))
                    .isEmpty(),
            )
        }

    @Test
    fun `unknown suggestion kind records command feedback without source-specific row`() {
        val source = TerminalCompletionSources.learningStore()
        val recorder = recorder(source, clockEpochMillis = { 1_500L })

        recorder.record(
            feedback =
                feedback(
                    kind = SwingShellSuggestionFeedbackKind.ACCEPTED,
                    commandText = "git s",
                    replacementText = "git status",
                    replacementStartOffset = 0,
                    replacementEndOffset = 5,
                    suggestionKindName = "custom",
                ),
            context = context(),
        )

        assertEquals(
            1,
            source
                .snapshotAll()
                .commandStats
                .single()
                .acceptedCount,
        )
        assertTrue(source.snapshotAll().feedbackStats.isEmpty())
    }

    @Test
    fun `invalid replacement range is ignored and does not publish`() {
        val source = TerminalCompletionSources.learningStore()
        var publishCount = 0
        val recorder = recorder(source, afterMutation = { publishCount++ })

        recorder.record(
            feedback =
                feedback(
                    kind = SwingShellSuggestionFeedbackKind.ACCEPTED,
                    commandText = "git s",
                    replacementText = "git status",
                    replacementStartOffset = 0,
                    replacementEndOffset = 99,
                ),
            context = context(),
        )

        assertTrue(source.snapshotAll().commandStats.isEmpty())
        assertEquals(0, publishCount)
    }

    @Test
    fun `explicit Unicode range records same command accepted by Swing handler`() {
        val source = TerminalCompletionSources.learningStore()
        val published = ArrayList<TerminalCommandCompletionStatsSnapshot>()
        val recorder = recorder(source, afterMutation = published::add, clockEpochMillis = { 2_500L })

        recorder.record(
            feedback =
                feedback(
                    kind = SwingShellSuggestionFeedbackKind.ACCEPTED,
                    commandText = "echo \uD83D\uDE02",
                    replacementText = "ok",
                    replacementStartOffset = 5,
                    replacementEndOffset = "echo \uD83D\uDE02".length,
                    suggestionKind = TerminalCompletionCandidateKind.ARGUMENT,
                ),
            context = context(),
        )

        assertEquals(
            "echo ok",
            source
                .snapshotAll()
                .commandStats
                .single()
                .commandLine,
        )
        assertEquals(
            "echo ok",
            published
                .single()
                .commandStats
                .single()
                .commandLine,
        )
    }

    @Test
    fun `explicit Unicode range with malformed cursor is ignored`() {
        val source = TerminalCompletionSources.learningStore()
        var publishCount = 0
        val recorder = recorder(source, afterMutation = { publishCount++ })

        recorder.record(
            feedback =
                feedback(
                    kind = SwingShellSuggestionFeedbackKind.ACCEPTED,
                    commandText = "echo \uD83D\uDE02",
                    replacementText = "ok",
                    replacementStartOffset = 5,
                    replacementEndOffset = "echo \uD83D\uDE02".length,
                    cursorOffset = 6,
                    suggestionKind = TerminalCompletionCandidateKind.ARGUMENT,
                ),
            context = context(),
        )

        assertTrue(source.snapshotAll().commandStats.isEmpty())
        assertEquals(0, publishCount)
    }

    @Test
    fun `sensitive resulting command is ignored and does not publish`() {
        val source = TerminalCompletionSources.learningStore()
        var publishCount = 0
        val recorder = recorder(source, afterMutation = { publishCount++ })

        recorder.record(
            feedback =
                feedback(
                    kind = SwingShellSuggestionFeedbackKind.ACCEPTED,
                    commandText = "docker login ",
                    replacementText = "--password hunter2",
                    replacementStartOffset = 13,
                    replacementEndOffset = 13,
                ),
            context = context(),
        )

        assertEquals(TerminalCommandCompletionStatsSnapshot.EMPTY, source.snapshotAll())
        assertEquals(0, publishCount)
    }

    @Test
    fun `created handler reads latest context`() {
        val source = TerminalCompletionSources.learningStore()
        val recorder = recorder(source, clockEpochMillis = { 3_000L })
        var workingDirectoryUri = "file:///first"
        val handler =
            recorder.createHandler {
                context(workingDirectoryUri = workingDirectoryUri)
            }

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

        assertEquals(
            "file:///second",
            source
                .snapshotAll()
                .commandStats
                .single()
                .workingDirectoryUri,
        )
        assertEquals(
            "file:///second",
            source
                .snapshotAll()
                .shapeStats
                .single()
                .workingDirectoryUri,
        )
    }

    @Test
    fun `privacy policy sees leading whitespace before feedback is learned`() {
        val source = TerminalCompletionSources.learningStore(commandSpecs = emptyList())
        val recorder = recorder(source)

        recorder.record(
            feedback =
                feedback(
                    kind = SwingShellSuggestionFeedbackKind.ACCEPTED,
                    commandText = " gi",
                    replacementText = "git status",
                    replacementStartOffset = 1,
                    replacementEndOffset = 3,
                ),
            context = SwingCompletionContext(profileId = "bash"),
        )

        assertEquals(TerminalCommandCompletionStatsSnapshot.EMPTY, source.snapshotAll())
    }

    private fun recorder(
        source: TerminalCompletionLearningStore,
        afterMutation: ((TerminalCommandCompletionStatsSnapshot) -> Unit)? = null,
        clockEpochMillis: () -> Long = System::currentTimeMillis,
    ): SwingCompletionFeedbackRecorder =
        SwingCompletionFeedbackRecorder(
            statsSource = source,
            afterMutation = afterMutation,
            allowsCommand = TerminalCompletionPersistencePolicy::allowsCommand,
            clockEpochMillis = clockEpochMillis,
        )

    private fun context(workingDirectoryUri: String = "file:///repo"): SwingCompletionContext =
        SwingCompletionContext(
            profileId = "bash",
            workingDirectoryUri = workingDirectoryUri,
        )

    private fun feedback(
        kind: SwingShellSuggestionFeedbackKind,
        commandText: String,
        replacementText: String,
        replacementStartOffset: Int,
        replacementEndOffset: Int,
        cursorOffset: Int = commandText.length,
        source: String = "spec",
        suggestionKind: TerminalCompletionCandidateKind = TerminalCompletionCandidateKind.SUBCOMMAND,
        suggestionKindName: String = suggestionKind.name,
    ): SwingShellSuggestionFeedback =
        SwingShellSuggestionFeedback(
            kind = kind,
            suggestion =
                SwingShellSuggestion(
                    replacementText = replacementText,
                    replacementStartOffset = replacementStartOffset,
                    replacementEndOffset = replacementEndOffset,
                    source = source,
                    kind = suggestionKindName,
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
