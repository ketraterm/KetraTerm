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

import io.github.ketraterm.completion.api.*
import io.github.ketraterm.completion.model.TerminalCommandSpec
import io.github.ketraterm.completion.model.TerminalCompletionValueDomain
import io.github.ketraterm.completion.model.TerminalOptionSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CompletionMergeRankingTest {
    @Test
    fun `quoted attached option values rank consistently for token and value edits`() {
        val line = "tool --branch='ma"
        val request = TerminalCompletionRequest(line, line.length, shellCapabilities = TerminalShellCapabilities.POWERSHELL)
        val context =
            TerminalCompletionContext.resolve(
                request,
                listOf(
                    TerminalCommandSpec(
                        "tool",
                        options = listOf(TerminalOptionSpec(listOf("--branch"), requiresValue = true)),
                    ),
                ),
            )
        for (wholeToken in listOf(false, true)) {
            val state = GlobalCompletionRanker().createRequestState(request, context, 20, 0)
            val start = if (wholeToken) line.indexOf("--") else line.indexOf('=') + 1
            val option = if (wholeToken) "--branch=" else ""
            state.ingest(
                CompletionSourceCandidates(
                    0,
                    0,
                    candidates =
                        listOf(
                            TerminalCompletionCandidate(
                                "$option'feature-main'",
                                start,
                                line.length,
                                "native",
                                TerminalCompletionCandidateKind.ARGUMENT,
                                score = Int.MAX_VALUE,
                            ),
                            TerminalCompletionCandidate(
                                "$option'main'",
                                start,
                                line.length,
                                "native",
                                TerminalCompletionCandidateKind.ARGUMENT,
                            ),
                        ),
                ),
            )
            assertEquals("$option'main'", state.rankedCandidates().first().replacementText)
        }
    }

    @Test
    fun `match quality uses the projected active word rather than provider labels or whole line edits`() {
        val state = state("custom ma")
        state.ingest(
            CompletionSourceCandidates(
                0,
                0,
                candidates =
                    listOf(
                        TerminalCompletionCandidate(
                            "custom feature-main",
                            0,
                            9,
                            "history",
                            TerminalCompletionCandidateKind.ARGUMENT,
                            displayText = "Favourite branch",
                            score = Int.MAX_VALUE,
                        ),
                        TerminalCompletionCandidate(
                            "custom 'main'",
                            0,
                            9,
                            "history",
                            TerminalCompletionCandidateKind.ARGUMENT,
                            displayText = "Default branch",
                        ),
                    ),
            ),
        )
        val ranked = state.rankedCandidates()
        assertEquals("custom 'main'", ranked.first().replacementText)
        assertTrue(ranked.first().score > ranked.last().score)
    }

    @Test
    fun `prefix matches stay ahead of boundary matches corroborated by more providers`() {
        val state = state("custom ma")
        state.ingest(source(0, candidate("main", "direct")))
        repeat(4) { state.ingest(source(it + 1, candidate("feature-main", "provider-$it"))) }
        assertEquals("main", state.rankedCandidates().first().replacementText)
    }

    @Test
    fun `redundant providers do not inflate relevance scores`() {
        val state = state("custom ma")
        val candidates = listOf(candidate("main", "provider"), candidate("master", "provider").copy(score = -1))
        state.ingest(CompletionSourceCandidates(0, 0, candidates = candidates))
        val initial = state.rankedCandidates().associate { it.replacementText to it.score }
        repeat(4) { state.ingest(CompletionSourceCandidates(it + 1, 0, candidates = candidates)) }
        assertEquals(initial, state.rankedCandidates().associate { it.replacementText to it.score })
    }

    @Test
    fun `branch arguments stay ahead of filesystem noise even with multiple path providers`() {
        val state = state("git switch ma")
        val branch =
            TerminalCompletionCandidate(
                "main",
                11,
                13,
                "git",
                TerminalCompletionCandidateKind.ARGUMENT,
                valueDomain = TerminalCompletionValueDomain.GIT_BRANCH,
            )
        state.ingest(source(0, branch))
        repeat(5) {
            state.ingest(source(it + 1, TerminalCompletionCandidate("main.txt", 11, 13, "files-$it", TerminalCompletionCandidateKind.PATH)))
        }
        assertEquals("main", state.rankedCandidates().first().replacementText)
    }

    @Test
    fun `equivalent outcomes use the same narrow edit and feedback regardless of arrival order`() {
        val request = TerminalCompletionRequest("cd b", 4, shellCapabilities = TerminalShellCapabilities.POWERSHELL)
        val token = Any()
        val first =
            TerminalCompletionCandidate(
                "cd build/",
                0,
                4,
                "learned",
                TerminalCompletionCandidateKind.PATH,
                displayText = "build/",
            )
        val learned = CompletionSourceCandidates(0, 8, isFallback = true, candidates = listOf(first))
        val native =
            source(
                1,
                TerminalCompletionCandidate(
                    "build/",
                    3,
                    4,
                    "powershell",
                    TerminalCompletionCandidateKind.PATH,
                    feedbackToken = token,
                ),
            )
        for (order in listOf(listOf(learned, native), listOf(native, learned))) {
            val state = GlobalCompletionRanker().createRequestState(request, TerminalCompletionContext.resolve(request), 20, 0)
            for (result in order) {
                state.ingest(result)
                state.rankedCandidates()
            }
            val updated = state.rankedCandidates().single()
            assertEquals("build/", updated.replacementText)
            assertEquals(3, updated.replacementStartOffset)
            assertEquals(4, updated.replacementEndOffset)
            assertSame(token, updated.feedbackToken)
        }
    }

    @Test
    fun `nested path matches rank by entry prefix across PowerShell separators`() {
        for (separator in listOf('/', '\\')) {
            val line = "cd src${separator}ma"
            val state = state(line)
            state.ingest(
                CompletionSourceCandidates(
                    0,
                    0,
                    candidates =
                        listOf(
                            TerminalCompletionCandidate(
                                "src${separator}feature-main$separator",
                                3,
                                line.length,
                                "native",
                                TerminalCompletionCandidateKind.PATH,
                                displayText = "feature-main$separator",
                                score = 100,
                            ),
                            TerminalCompletionCandidate(
                                "src${separator}main$separator",
                                3,
                                line.length,
                                "files",
                                TerminalCompletionCandidateKind.PATH,
                                displayText = "main$separator",
                            ),
                        ),
                ),
            )
            val ranked = state.rankedCandidates()
            assertEquals("main$separator", ranked.first().displayText)
            assertTrue(ranked.first().score > ranked.last().score)
        }
    }

    private fun state(line: String): GlobalCompletionRanker.RequestCompletionRankingState {
        val request = TerminalCompletionRequest(line, line.length, shellCapabilities = TerminalShellCapabilities.POWERSHELL)
        return GlobalCompletionRanker().createRequestState(request, TerminalCompletionContext.resolve(request), 20, 0)
    }

    private fun candidate(
        text: String,
        source: String,
    ) = TerminalCompletionCandidate(text, 7, 9, source, TerminalCompletionCandidateKind.ARGUMENT)

    private fun source(
        index: Int,
        candidate: TerminalCompletionCandidate,
    ) = CompletionSourceCandidates(index, 0, candidates = listOf(candidate))
}
