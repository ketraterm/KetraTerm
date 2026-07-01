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

class FeedbackAwareCompletionSourceTest {
    @Test
    fun `accepted matching feedback boosts candidate above otherwise higher score candidate`() {
        val source =
            FeedbackAwareCompletionSource(
                delegate =
                    fixedSource(
                        candidate("status", score = 320),
                        candidate(
                            replacementText = "src/main",
                            score = 300,
                            source = "path",
                            kind = TerminalCompletionCandidateKind.PATH,
                        ),
                    ),
                feedbackStatsProvider = {
                    listOf(
                        feedbackStats(
                            source = "path",
                            candidateKind = TerminalCompletionCandidateKind.PATH,
                            acceptedCount = 2,
                        ),
                    )
                },
            )

        val candidates = source.complete(request("git s"))

        assertEquals(listOf("src/main", "status"), candidates.map { it.replacementText })
        assertTrue(candidates[0].score > candidates[1].score)
    }

    @Test
    fun `dismissed matching feedback demotes candidate`() {
        val source =
            FeedbackAwareCompletionSource(
                delegate =
                    fixedSource(
                        candidate(
                            replacementText = "src/main",
                            score = 320,
                            source = "path",
                            kind = TerminalCompletionCandidateKind.PATH,
                        ),
                        candidate("status", score = 300),
                    ),
                feedbackStatsProvider = {
                    listOf(
                        feedbackStats(
                            source = "path",
                            candidateKind = TerminalCompletionCandidateKind.PATH,
                            dismissedCount = 2,
                        ),
                    )
                },
            )

        val candidates = source.complete(request("git s"))

        assertEquals(listOf("status", "src/main"), candidates.map { it.replacementText })
    }

    @Test
    fun `feedback from another provider does not affect candidate`() {
        val source =
            FeedbackAwareCompletionSource(
                delegate =
                    fixedSource(
                        candidate("status", score = 320),
                        candidate(
                            replacementText = "src/main",
                            score = 300,
                            source = "path",
                            kind = TerminalCompletionCandidateKind.PATH,
                        ),
                    ),
                feedbackStatsProvider = {
                    listOf(
                        feedbackStats(
                            source = "stats",
                            dismissedCount = 10,
                        ),
                    )
                },
            )

        val candidates = source.complete(request("git s"))

        assertEquals(listOf("status", "src/main"), candidates.map { it.replacementText })
    }

    @Test
    fun `specific rejected feedback overrides broad accepted feedback`() {
        val source =
            FeedbackAwareCompletionSource(
                delegate =
                    fixedSource(
                        candidate("status", score = 320),
                        candidate(
                            replacementText = "src/main",
                            score = 300,
                            source = "path",
                            kind = TerminalCompletionCandidateKind.PATH,
                        ),
                    ),
                feedbackStatsProvider = {
                    listOf(
                        feedbackStats(
                            source = "path",
                            candidateKind = TerminalCompletionCandidateKind.PATH,
                            acceptedCount = 10,
                            replacementStartOffset = -1,
                            replacementEndOffset = -1,
                        ),
                        feedbackStats(
                            source = "path",
                            candidateKind = TerminalCompletionCandidateKind.PATH,
                            profileId = "bash",
                            workingDirectoryUri = "file:///repo",
                            dismissedCount = 4,
                        ),
                    )
                },
            )

        val candidates = source.complete(request("git s", profileId = "bash", workingDirectoryUri = "file:///repo"))

        assertEquals(listOf("status", "src/main"), candidates.map { it.replacementText })
    }

    @Test
    fun `replacement range mismatch does not adjust candidate`() {
        val source =
            FeedbackAwareCompletionSource(
                delegate =
                    fixedSource(
                        candidate("status", score = 320),
                        candidate(
                            replacementText = "src/main",
                            score = 300,
                            source = "path",
                            kind = TerminalCompletionCandidateKind.PATH,
                            replacementStartOffset = 4,
                            replacementEndOffset = 6,
                        ),
                    ),
                feedbackStatsProvider = {
                    listOf(
                        feedbackStats(
                            source = "path",
                            candidateKind = TerminalCompletionCandidateKind.PATH,
                            acceptedCount = 10,
                            replacementStartOffset = 4,
                            replacementEndOffset = 5,
                        ),
                    )
                },
            )

        val candidates = source.complete(request("git sw"))

        assertEquals(listOf("status", "src/main"), candidates.map { it.replacementText })
    }

    @Test
    fun `feedback stats do not create candidates`() {
        val source =
            FeedbackAwareCompletionSource(
                delegate = TerminalCompletionSource.NONE,
                feedbackStatsProvider = {
                    listOf(feedbackStats(source = "spec", acceptedCount = 10))
                },
            )

        assertTrue(source.complete(request("git s")).isEmpty())
    }

    private fun fixedSource(vararg candidates: TerminalCompletionCandidate): TerminalCompletionSource =
        TerminalCompletionSource { candidates.toList() }

    private fun candidate(
        replacementText: String,
        score: Int,
        source: String = "spec",
        kind: TerminalCompletionCandidateKind = TerminalCompletionCandidateKind.SUBCOMMAND,
        replacementStartOffset: Int = 4,
        replacementEndOffset: Int = 5,
    ): TerminalCompletionCandidate =
        TerminalCompletionCandidate(
            replacementText = replacementText,
            replacementStartOffset = replacementStartOffset,
            replacementEndOffset = replacementEndOffset,
            displayText = replacementText,
            source = source,
            kind = kind,
            score = score,
        )

    private fun feedbackStats(
        source: String,
        candidateKind: TerminalCompletionCandidateKind = TerminalCompletionCandidateKind.SUBCOMMAND,
        profileId: String? = null,
        workingDirectoryUri: String? = null,
        acceptedCount: Int = 0,
        dismissedCount: Int = 0,
        replacementStartOffset: Int = 4,
        replacementEndOffset: Int = 5,
    ): TerminalCompletionFeedbackStats =
        TerminalCompletionFeedbackStats(
            source = source,
            candidateKind = candidateKind,
            tokenPosition = TerminalCompletionTokenPosition.fromCandidateKind(candidateKind),
            replacementStartOffset = replacementStartOffset,
            replacementEndOffset = replacementEndOffset,
            profileId = profileId,
            workingDirectoryUri = workingDirectoryUri,
            acceptedCount = acceptedCount,
            dismissedCount = dismissedCount,
            lastUsedEpochMillis = 100,
        )

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
}
