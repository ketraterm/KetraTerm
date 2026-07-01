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
package io.github.ketraterm.completion.ranking

import io.github.ketraterm.completion.api.TerminalCompletionCandidateKind
import io.github.ketraterm.completion.api.TerminalCompletionRequest
import io.github.ketraterm.completion.model.TerminalCommandCompletionStats
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExactCommandStatsCandidateBuilderTest {
    @Test
    fun `empty snapshot returns no candidates`() {
        val candidates = ExactCommandStatsCandidateBuilder.complete(request("git "), emptyList())

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun `builder emits full-line history candidate metadata`() {
        val candidates =
            ExactCommandStatsCandidateBuilder.complete(
                request("git s"),
                listOf(stats("git status", successCount = 1, lastUsedEpochMillis = 1)),
            )

        assertEquals(1, candidates.size)
        assertEquals("git status", candidates.single().replacementText)
        assertEquals("git status", candidates.single().displayText)
        assertEquals("stats", candidates.single().source)
        assertEquals(TerminalCompletionCandidateKind.HISTORY, candidates.single().kind)
        assertEquals(0, candidates.single().replacementStartOffset)
        assertEquals(5, candidates.single().replacementEndOffset)
    }

    @Test
    fun `builder filters failure-only dismissed-only non-prefix and exact-prefix rows`() {
        val candidates =
            ExactCommandStatsCandidateBuilder.complete(
                request("git s"),
                listOf(
                    stats("git status", successCount = 1),
                    stats("git stale", failureCount = 4),
                    stats("git stash", dismissedCount = 4),
                    stats("npm test", successCount = 1),
                    stats("git s", successCount = 1),
                ),
            )

        assertEquals(listOf("git status"), candidates.map { it.replacementText })
    }

    @Test
    fun `builder matches prefix case-insensitively after leading whitespace`() {
        val candidates =
            ExactCommandStatsCandidateBuilder.complete(
                request("   Git S"),
                listOf(
                    stats("git status", successCount = 1),
                    stats("git switch main", successCount = 1),
                    stats("git branch", successCount = 1),
                ),
            )

        assertEquals(listOf("git status", "git switch main"), candidates.map { it.replacementText })
    }

    @Test
    fun `builder ranks accepted command above dismissed command`() {
        val candidates =
            ExactCommandStatsCandidateBuilder.complete(
                request("git s"),
                listOf(
                    stats("git status", successCount = 1, dismissedCount = 8, lastUsedEpochMillis = 60_000),
                    stats("git switch main", successCount = 1, acceptedCount = 8, lastUsedEpochMillis = 60_000),
                ),
            )

        assertEquals(listOf("git switch main", "git status"), candidates.map { it.replacementText })
        assertTrue(candidates[0].score > candidates[1].score)
    }

    @Test
    fun `builder applies profile and working directory score boosts`() {
        val candidates =
            ExactCommandStatsCandidateBuilder.complete(
                request("npm ", profileId = "pwsh", workingDirectoryUri = "file:///repo-b"),
                listOf(
                    stats(
                        "npm test",
                        successCount = 1,
                        profileId = "bash",
                        workingDirectoryUri = "file:///repo-a",
                        lastUsedEpochMillis = 60_000,
                    ),
                    stats(
                        "npm update",
                        successCount = 1,
                        profileId = "pwsh",
                        workingDirectoryUri = "file:///repo-b",
                        lastUsedEpochMillis = 60_000,
                    ),
                ),
            )

        assertEquals(listOf("npm update", "npm test"), candidates.map { it.replacementText })
        assertTrue(candidates[0].score > candidates[1].score)
    }

    @Test
    fun `builder caps learned counter contribution and uses candidate order tiebreakers`() {
        val candidates =
            ExactCommandStatsCandidateBuilder.complete(
                request("git "),
                listOf(
                    stats("git beta", useCount = 500, successCount = 500, acceptedCount = 500, lastUsedEpochMillis = 60_000),
                    stats("git alpha", useCount = 50, successCount = 50, acceptedCount = 50, lastUsedEpochMillis = 60_000),
                ),
            )

        assertEquals(listOf("git alpha", "git beta"), candidates.map { it.replacementText })
        assertEquals(candidates[0].score, candidates[1].score)
    }

    @Test
    fun `builder respects request candidate limit after ranking`() {
        val candidates =
            ExactCommandStatsCandidateBuilder.complete(
                request("git ", maxCandidates = 2),
                listOf(
                    stats("git three", successCount = 1, lastUsedEpochMillis = 3 * 60_000),
                    stats("git one", successCount = 1, lastUsedEpochMillis = 1 * 60_000),
                    stats("git two", successCount = 1, lastUsedEpochMillis = 2 * 60_000),
                ),
            )

        assertEquals(listOf("git three", "git two"), candidates.map { it.replacementText })
    }

    private fun request(
        commandLine: String,
        profileId: String? = null,
        workingDirectoryUri: String? = null,
        maxCandidates: Int = TerminalCompletionRequest.DEFAULT_MAX_CANDIDATES,
    ): TerminalCompletionRequest =
        TerminalCompletionRequest(
            commandLine = commandLine,
            cursorOffset = commandLine.length,
            profileId = profileId,
            workingDirectoryUri = workingDirectoryUri,
            maxCandidates = maxCandidates,
        )

    private fun stats(
        commandLine: String,
        useCount: Int = 0,
        successCount: Int = 0,
        failureCount: Int = 0,
        acceptedCount: Int = 0,
        dismissedCount: Int = 0,
        profileId: String? = null,
        workingDirectoryUri: String? = null,
        lastUsedEpochMillis: Long = 0,
    ): TerminalCommandCompletionStats =
        TerminalCommandCompletionStats(
            commandLine = commandLine,
            normalizedCommandLine = TerminalCommandCompletionStats.normalizeCommandLine(commandLine),
            profileId = profileId,
            workingDirectoryUri = workingDirectoryUri,
            useCount = useCount,
            successCount = successCount,
            failureCount = failureCount,
            acceptedCount = acceptedCount,
            dismissedCount = dismissedCount,
            lastUsedEpochMillis = lastUsedEpochMillis,
        )
}
