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
import io.github.ketraterm.completion.commandline.TerminalCommandLineTokenizer
import io.github.ketraterm.completion.commandline.TerminalCompletionContextResolver
import io.github.ketraterm.completion.model.TerminalCompletionFeedbackKind
import kotlin.test.*

class GlobalCompletionRankerIncrementalTest {
    @Test
    fun `conflicting equivalent preferences use strongest magnitude with negative ties in either arrival order`() {
        val request = TerminalCompletionRequest("g", 1, shellCapabilities = TerminalShellCapabilities.POSIX)
        val context = TerminalCompletionContext.resolve(request)
        val live = TerminalCompletionCandidate("git", 0, 1, "shell", TerminalCompletionCandidateKind.COMMAND)
        val quoted = live.copy(replacementText = "'git'")

        fun score(
            acceptances: Int,
            dismissals: Int,
            reversed: Boolean,
        ): Int {
            val store = TerminalCompletionLearningStore()
            repeat(acceptances) { store.recordSuggestionFeedback("git", TerminalCompletionFeedbackKind.ACCEPTED, null, null, 0) }
            repeat(dismissals) { store.recordSuggestionFeedback("'git'", TerminalCompletionFeedbackKind.DISMISSED, null, null, 0) }
            val state =
                GlobalCompletionRanker().createRequestState(
                    request,
                    context,
                    10,
                    0,
                    store.indexesFor(TerminalShellSyntax.POSIX).evidence,
                )
            val sources =
                listOf(
                    CompletionSourceCandidates(0, 0, candidates = listOf(live)),
                    CompletionSourceCandidates(1, 0, candidates = listOf(quoted)),
                )
            for (source in if (reversed) sources.reversed() else sources) state.ingest(source)
            return state.rankedCandidates().single().score
        }
        val baseline = score(0, 0, false)
        for (reversed in listOf(false, true)) {
            assertEquals(baseline + 30, score(3, 2, reversed))
            assertEquals(baseline - 30, score(2, 3, reversed))
            assertEquals(baseline - 20, score(2, 2, reversed))
        }
    }

    @Test
    fun `equivalent edits retain positive and negative learning independently of presentation and provider count`() {
        val request = TerminalCompletionRequest("cd ", 3, shellCapabilities = TerminalShellCapabilities.POSIX)
        val context = TerminalCompletionContext.resolve(request)
        val live = TerminalCompletionCandidate("build/", 3, 3, "path", TerminalCompletionCandidateKind.PATH)
        val learned = live.copy(replacementText = "'build'", source = "learned")
        val now = 2_000_000_000_000L

        fun rank(
            feedback: TerminalCompletionFeedbackKind?,
            together: Boolean,
            reversed: Boolean,
            duplicate: Boolean,
        ): Int {
            val store = TerminalCompletionLearningStore()
            if (feedback != null) store.recordSuggestionFeedback("cd 'build'", feedback, null, null, now)
            val state =
                GlobalCompletionRanker().createRequestState(
                    request,
                    context,
                    10,
                    now,
                    store.indexesFor(TerminalShellSyntax.POSIX).evidence,
                )
            val sources =
                if (together) {
                    listOf(CompletionSourceCandidates(0, 0, candidates = listOf(live, learned)))
                } else {
                    listOf(
                        CompletionSourceCandidates(0, 0, candidates = listOf(live)),
                        CompletionSourceCandidates(1, 0, isFallback = true, candidates = listOf(learned)),
                    )
                }
            for (source in if (reversed) sources.reversed() else sources) state.ingest(source)
            if (duplicate) state.ingest(CompletionSourceCandidates(2, 0, isFallback = true, candidates = listOf(learned, learned)))
            val result = state.rankedCandidates().single()
            assertEquals("build/", result.replacementText)
            assertEquals("path", result.source)
            return result.score
        }

        for (together in listOf(false, true)) {
            val baseline = rank(null, together, false, false)
            for (reversed in listOf(false, true)) {
                for (duplicate in listOf(false, true)) {
                    assertEquals(baseline + 70, rank(TerminalCompletionFeedbackKind.ACCEPTED, together, reversed, duplicate))
                    assertEquals(baseline - 10, rank(TerminalCompletionFeedbackKind.DISMISSED, together, reversed, duplicate))
                }
            }
        }
    }

    @Test
    fun `progressive merges preserve final ordering metadata and scores across all source arrival orders`() {
        val token = Any()
        val sources =
            listOf(
                CompletionSourceCandidates(0, 20, candidates = listOf(candidate("gradle", 100), candidate("'git'", 0))),
                CompletionSourceCandidates(
                    1,
                    -20,
                    candidates = listOf(candidate("git", 0, source = "commands", detail = "Git CLI", feedbackToken = token)),
                ),
                CompletionSourceCandidates(
                    2,
                    100,
                    candidates = listOf(candidate("git", Int.MAX_VALUE).copy(kind = TerminalCompletionCandidateKind.PATH)),
                ),
            )
        var expected: List<TerminalCompletionCandidate>? = null
        for (first in sources.indices) {
            for (second in sources.indices.filter { it != first }) {
                val third = sources.indices.single { it != first && it != second }
                val state = requestState()
                for (index in listOf(first, second, third)) {
                    state.ingest(sources[index])
                    state.rankedCandidates()
                }
                val results = state.rankedCandidates()
                assertEquals(listOf("git", "gradle"), results.map { it.replacementText })
                assertEquals("commands", results.first().source)
                assertEquals("Git CLI", results.first().detail)
                assertSame(token, results.first().feedbackToken)
                if (expected == null) expected = results else assertEquals(expected, results)
            }
        }
    }

    @Test
    fun `top K publication is reused until an updated outcome enters the retained set`() {
        val request = TerminalCompletionRequest("g", 1)
        val context =
            TerminalCompletionContextResolver.resolve(
                commandLine = request.commandLine,
                lineContext = TerminalCommandLineTokenizer.parse(request.commandLine, request.cursorOffset),
                commandSpecs = emptyList(),
            )
        val state =
            GlobalCompletionRanker()
                .createRequestState(request, context, resultLimit = 2, nowEpochMillis = 0L)
        state.ingest(
            CompletionSourceCandidates(
                sourceIndex = 0,
                priority = 0,
                candidates = listOf(candidate("alpha", 20), candidate("bravo", 10)),
            ),
        )
        val initial = state.rankedCandidates()

        assertSame(initial, state.rankedCandidates())

        state.ingest(
            CompletionSourceCandidates(
                sourceIndex = 1,
                priority = -20,
                candidates = listOf(candidate("charlie", 1)),
            ),
        )
        assertSame(initial, state.rankedCandidates())

        state.ingest(
            CompletionSourceCandidates(
                sourceIndex = 2,
                priority = 20,
                candidates = listOf(candidate("charlie", 1)),
            ),
        )
        val promoted = state.rankedCandidates()

        assertNotSame(initial, promoted)
        assertTrue(promoted.any { it.replacementText == "charlie" })
    }

    @Test
    fun `primary presentation wins identical edit regardless of source arrival order`() {
        val fallbackToken = Any()
        val primaryToken = Any()
        val fallback =
            CompletionSourceCandidates(
                sourceIndex = 0,
                priority = 100,
                isFallback = true,
                candidates =
                    listOf(
                        candidate(
                            replacement = "gradle",
                            score = 20,
                            source = "learned",
                            detail = "learned command",
                            feedbackToken = fallbackToken,
                        ),
                    ),
            )
        val primary =
            CompletionSourceCandidates(
                sourceIndex = 1,
                priority = 0,
                candidates =
                    listOf(
                        candidate(
                            replacement = "gradle",
                            score = 10,
                            source = "spec",
                            detail = "build automation tool",
                            feedbackToken = primaryToken,
                        ),
                    ),
            )

        val fallbackFirst = requestState()
        fallbackFirst.ingest(fallback)
        val fallbackOnly = fallbackFirst.rankedCandidates()
        assertEquals("learned", fallbackOnly.single().source)
        assertSame(fallbackToken, fallbackOnly.single().feedbackToken)

        fallbackFirst.ingest(primary)
        val fallbackThenPrimary = fallbackFirst.rankedCandidates()
        assertNotSame(fallbackOnly, fallbackThenPrimary)

        val primaryFirst = requestState()
        primaryFirst.ingest(primary)
        primaryFirst.ingest(fallback)
        val primaryThenFallback = primaryFirst.rankedCandidates()

        assertEquals(fallbackThenPrimary, primaryThenFallback)
        assertEquals("spec", fallbackThenPrimary.single().source)
        assertEquals("build automation tool", fallbackThenPrimary.single().detail)
        assertSame(primaryToken, fallbackThenPrimary.single().feedbackToken)
        assertSame(primaryToken, primaryThenFallback.single().feedbackToken)
    }

    @Test
    fun `equivalent outcomes retain the chosen edit token regardless of source arrival order`() {
        val quotedToken = Any()
        val bareToken = Any()
        val quoted =
            CompletionSourceCandidates(
                sourceIndex = 0,
                priority = 20,
                candidates = listOf(candidate("'gradle'", 20, feedbackToken = quotedToken)),
            )
        val bare =
            CompletionSourceCandidates(
                sourceIndex = 1,
                priority = 0,
                candidates = listOf(candidate("gradle", 10, feedbackToken = bareToken)),
            )

        for (sourceOrder in listOf(listOf(quoted, bare), listOf(bare, quoted))) {
            val state = requestState()
            for (source in sourceOrder) state.ingest(source)

            val candidate = state.rankedCandidates().single()
            assertEquals("gradle", candidate.replacementText)
            assertEquals("gradle", candidate.source)
            assertSame(bareToken, candidate.feedbackToken)
        }
    }

    private fun requestState(): GlobalCompletionRanker.RequestCompletionRankingState {
        val request = TerminalCompletionRequest("g", 1)
        val context =
            TerminalCompletionContextResolver.resolve(
                commandLine = request.commandLine,
                lineContext = TerminalCommandLineTokenizer.parse(request.commandLine, request.cursorOffset),
                commandSpecs = emptyList(),
            )
        return GlobalCompletionRanker()
            .createRequestState(request, context, resultLimit = 2, nowEpochMillis = 0L)
    }

    private fun candidate(
        replacement: String,
        score: Int,
        source: String = replacement,
        detail: String = "",
        feedbackToken: Any? = null,
    ): TerminalCompletionCandidate =
        TerminalCompletionCandidate(
            replacementText = replacement,
            replacementStartOffset = 0,
            replacementEndOffset = 1,
            displayText = replacement,
            detail = detail,
            source = source,
            kind = TerminalCompletionCandidateKind.COMMAND,
            score = score,
            feedbackToken = feedbackToken,
        )
}
