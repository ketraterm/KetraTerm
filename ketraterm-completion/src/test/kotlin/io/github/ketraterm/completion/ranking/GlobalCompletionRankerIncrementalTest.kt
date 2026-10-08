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

import io.github.ketraterm.completion.api.TerminalCompletionCandidate
import io.github.ketraterm.completion.api.TerminalCompletionCandidateKind
import io.github.ketraterm.completion.api.TerminalCompletionRequest
import io.github.ketraterm.completion.commandline.TerminalCommandLineTokenizer
import io.github.ketraterm.completion.commandline.TerminalCompletionContextResolver
import kotlin.test.*

class GlobalCompletionRankerIncrementalTest {
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
