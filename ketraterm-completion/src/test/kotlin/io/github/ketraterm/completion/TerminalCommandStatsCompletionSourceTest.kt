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
package io.github.ketraterm.completion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TerminalCommandStatsCompletionSourceTest {
    @Test
    fun `successful command result creates full-line history candidate`() {
        val source = TerminalCommandStatsCompletionSource()
        source.recordCommandResult(
            commandLine = "git status",
            successful = true,
            profileId = "pwsh",
            workingDirectoryUri = "file:///repo",
            usedAtEpochMillis = 1_000,
        )

        val candidates = source.complete(request("git s", profileId = "pwsh", workingDirectoryUri = "file:///repo"))

        assertEquals(listOf("git status"), candidates.map { it.replacementText })
        assertEquals("stats", candidates.single().source)
        assertEquals(TerminalCompletionCandidateKind.HISTORY, candidates.single().kind)
        assertEquals(0, candidates.single().replacementStartOffset)
        assertEquals(5, candidates.single().replacementEndOffset)
    }

    @Test
    fun `records compact success and failure counts for one normalized command`() {
        val source = TerminalCommandStatsCompletionSource()

        source.recordCommandResult("Git Status", successful = true, profileId = "bash", workingDirectoryUri = null, usedAtEpochMillis = 10)
        source.recordCommandResult("git status", successful = false, profileId = "bash", workingDirectoryUri = null, usedAtEpochMillis = 20)

        assertEquals(
            listOf(
                TerminalCommandCompletionStats(
                    commandLine = "git status",
                    normalizedCommandLine = "git status",
                    profileId = "bash",
                    workingDirectoryUri = null,
                    useCount = 2,
                    successCount = 1,
                    failureCount = 1,
                    acceptedCount = 0,
                    dismissedCount = 0,
                    lastUsedEpochMillis = 20,
                ),
            ),
            source.snapshot(),
        )
    }

    @Test
    fun `accepted feedback boosts candidate above dismissed candidate`() {
        val source = TerminalCommandStatsCompletionSource()
        source.recordCommandResult("git status", successful = true, profileId = null, workingDirectoryUri = null, usedAtEpochMillis = 100)
        source.recordCommandResult(
            "git switch main",
            successful = true,
            profileId = null,
            workingDirectoryUri = null,
            usedAtEpochMillis = 100,
        )

        repeat(4) {
            source.recordSuggestionFeedback(
                commandLine = "git switch main",
                feedback = TerminalCompletionFeedbackKind.ACCEPTED,
                profileId = null,
                workingDirectoryUri = null,
                feedbackAtEpochMillis = 200 + it.toLong(),
            )
        }
        repeat(4) {
            source.recordSuggestionFeedback(
                commandLine = "git status",
                feedback = TerminalCompletionFeedbackKind.DISMISSED,
                profileId = null,
                workingDirectoryUri = null,
                feedbackAtEpochMillis = 200 + it.toLong(),
            )
        }

        val candidates = source.complete(request("git s"))

        assertEquals(listOf("git switch main", "git status"), candidates.map { it.replacementText })
    }

    @Test
    fun `profile and working directory matches affect ranking`() {
        val source = TerminalCommandStatsCompletionSource()
        source.recordCommandResult(
            "npm test",
            successful = true,
            profileId = "bash",
            workingDirectoryUri = "file:///repo-a",
            usedAtEpochMillis = 100,
        )
        source.recordCommandResult(
            "npm update",
            successful = true,
            profileId = "pwsh",
            workingDirectoryUri = "file:///repo-b",
            usedAtEpochMillis = 100,
        )

        val candidates = source.complete(request("npm ", profileId = "pwsh", workingDirectoryUri = "file:///repo-b"))

        assertEquals(listOf("npm update", "npm test"), candidates.map { it.replacementText })
    }

    @Test
    fun `exact command prefix is not suggested`() {
        val source = TerminalCommandStatsCompletionSource()
        source.recordCommandResult("git status", successful = true, profileId = null, workingDirectoryUri = null, usedAtEpochMillis = 100)

        assertTrue(source.complete(request("git status")).isEmpty())
    }

    @Test
    fun `failure only and dismissed only rows are tracked but not suggested`() {
        val source = TerminalCommandStatsCompletionSource()
        source.recordCommandResult("git status", successful = false, profileId = null, workingDirectoryUri = null, usedAtEpochMillis = 100)
        source.recordSuggestionFeedback(
            commandLine = "git switch main",
            feedback = TerminalCompletionFeedbackKind.DISMISSED,
            profileId = null,
            workingDirectoryUri = null,
            feedbackAtEpochMillis = 200,
        )

        assertEquals(listOf("git switch main", "git status"), source.snapshot().map { it.commandLine })
        assertTrue(source.complete(request("git s")).isEmpty())
    }

    @Test
    fun `blank multiline and negative timestamp events are ignored`() {
        val source = TerminalCommandStatsCompletionSource()

        source.recordCommandResult("   ", successful = true, profileId = null, workingDirectoryUri = null, usedAtEpochMillis = 100)
        source.recordCommandResult(
            "git status\ngit log",
            successful = true,
            profileId = null,
            workingDirectoryUri = null,
            usedAtEpochMillis = 100,
        )
        source.recordSuggestionFeedback(
            commandLine = "git status",
            feedback = TerminalCompletionFeedbackKind.ACCEPTED,
            profileId = null,
            workingDirectoryUri = null,
            feedbackAtEpochMillis = -1,
        )

        assertTrue(source.snapshot().isEmpty())
    }

    @Test
    fun `capacity keeps most relevant recent records`() {
        val source = TerminalCommandStatsCompletionSource(capacity = 2)

        source.recordCommandResult("one", successful = true, profileId = null, workingDirectoryUri = null, usedAtEpochMillis = 1)
        source.recordCommandResult("two", successful = true, profileId = null, workingDirectoryUri = null, usedAtEpochMillis = 2)
        source.recordCommandResult("three", successful = true, profileId = null, workingDirectoryUri = null, usedAtEpochMillis = 3)

        assertEquals(listOf("three", "two"), source.snapshot().map { it.commandLine })
    }

    @Test
    fun `replace all deduplicates by normalized command profile and directory`() {
        val source = TerminalCommandStatsCompletionSource()

        source.replaceAll(
            listOf(
                stats("Git Status", profileId = "bash", workingDirectoryUri = "file:///repo", lastUsedEpochMillis = 10),
                stats("git status", profileId = "bash", workingDirectoryUri = "file:///repo", lastUsedEpochMillis = 20),
                stats("git status", profileId = "pwsh", workingDirectoryUri = "file:///repo", lastUsedEpochMillis = 5),
            ),
        )

        assertEquals(
            listOf(
                stats("git status", profileId = "bash", workingDirectoryUri = "file:///repo", lastUsedEpochMillis = 20),
                stats("git status", profileId = "pwsh", workingDirectoryUri = "file:///repo", lastUsedEpochMillis = 5),
            ),
            source.snapshot(),
        )
    }

    @Test
    fun `factory creates command stats source`() {
        val source = TerminalCompletionSources.commandStats(capacity = 1)

        source.recordCommandResult("git status", successful = true, profileId = null, workingDirectoryUri = null, usedAtEpochMillis = 1)

        assertEquals(listOf("git status"), source.snapshot().map { it.commandLine })
    }

    private fun request(
        commandLine: String,
        profileId: String? = null,
        workingDirectoryUri: String? = null,
    ): TerminalCompletionRequest =
        TerminalCompletionRequest(
            commandLine = commandLine,
            cursorOffset = commandLine.length,
            profileId = profileId,
            workingDirectoryUri = workingDirectoryUri,
        )

    private fun stats(
        commandLine: String,
        profileId: String?,
        workingDirectoryUri: String?,
        lastUsedEpochMillis: Long,
    ): TerminalCommandCompletionStats =
        TerminalCommandCompletionStats(
            commandLine = commandLine,
            normalizedCommandLine = TerminalCommandCompletionStats.normalizeCommandLine(commandLine),
            profileId = profileId,
            workingDirectoryUri = workingDirectoryUri,
            useCount = 0,
            successCount = 0,
            failureCount = 0,
            acceptedCount = 0,
            dismissedCount = 0,
            lastUsedEpochMillis = lastUsedEpochMillis,
        )
}
