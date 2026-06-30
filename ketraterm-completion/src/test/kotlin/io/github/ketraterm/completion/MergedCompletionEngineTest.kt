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

class MergedCompletionEngineTest {
    @Test
    fun `source priority ranks ahead of candidate score`() {
        val engine =
            TerminalCompletionEngines.fromSources(
                listOf(
                    entry(source(candidate("status", source = "spec", score = 900)), priority = 0),
                    entry(source(candidate("switch", source = "mru", score = 1)), priority = 100),
                ),
            )

        val candidates = engine.complete(request(maxCandidates = 8))

        assertEquals(listOf("switch", "status"), candidates.map { it.replacementText })
        assertEquals(listOf("mru", "spec"), candidates.map { it.source })
    }

    @Test
    fun `deduplicates by replacement range and replacement text using highest ranked candidate`() {
        val engine =
            TerminalCompletionEngines.fromSources(
                listOf(
                    entry(source(candidate("status", detail = "static", source = "spec", score = 900)), priority = 0),
                    entry(source(candidate("status", detail = "recent", source = "mru", score = 1)), priority = 100),
                ),
            )

        val candidates = engine.complete(request(maxCandidates = 8))

        assertEquals(listOf("status"), candidates.map { it.replacementText })
        assertEquals("recent", candidates.single().detail)
        assertEquals("mru", candidates.single().source)
    }

    @Test
    fun `keeps same replacement text when replacement range differs`() {
        val engine =
            TerminalCompletionEngines.fromSources(
                listOf(
                    entry(source(candidate("status", start = 0, end = 1, source = "left")), priority = 0),
                    entry(source(candidate("status", start = 4, end = 5, source = "right")), priority = 0),
                ),
            )

        val candidates = engine.complete(request(maxCandidates = 8))

        assertEquals(listOf("left", "right"), candidates.map { it.source })
        assertEquals(listOf(0, 4), candidates.map { it.replacementStartOffset })
    }

    @Test
    fun `applies max candidates after merging and sorting`() {
        val engine =
            TerminalCompletionEngines.fromSources(
                listOf(
                    entry(
                        source(
                            candidate("alpha", score = 10),
                            candidate("charlie", score = 30),
                        ),
                        priority = 0,
                    ),
                    entry(source(candidate("bravo", score = 20)), priority = 0),
                ),
            )

        val candidates = engine.complete(request(maxCandidates = 2))

        assertEquals(listOf("charlie", "bravo"), candidates.map { it.replacementText })
    }

    @Test
    fun `equal ranking falls back to display text for deterministic order`() {
        val engine =
            TerminalCompletionEngines.fromSources(
                source(
                    candidate("zeta", display = "zeta", score = 10),
                    candidate("alpha", display = "alpha", score = 10),
                ),
            )

        val candidates = engine.complete(request(maxCandidates = 8))

        assertEquals(listOf("alpha", "zeta"), candidates.map { it.replacementText })
    }

    @Test
    fun `empty source list returns no candidates`() {
        val engine = TerminalCompletionEngines.fromSources(emptyList<TerminalCompletionSourceEntry>())

        assertTrue(engine.complete(request(maxCandidates = 8)).isEmpty())
    }

    private fun entry(
        source: TerminalCompletionSource,
        priority: Int,
    ): TerminalCompletionSourceEntry =
        TerminalCompletionSourceEntry(
            source = source,
            priority = priority,
        )

    private fun source(vararg candidates: TerminalCompletionCandidate): TerminalCompletionSource =
        TerminalCompletionSource { candidates.toList() }

    private fun candidate(
        replacement: String,
        start: Int = 0,
        end: Int = 1,
        display: String = replacement,
        detail: String = "",
        source: String = "test",
        score: Int = 0,
    ): TerminalCompletionCandidate =
        TerminalCompletionCandidate(
            replacementText = replacement,
            replacementStartOffset = start,
            replacementEndOffset = end,
            displayText = display,
            detail = detail,
            source = source,
            score = score,
        )

    private fun request(maxCandidates: Int): TerminalCompletionRequest =
        TerminalCompletionRequest(
            commandLine = "s",
            cursorOffset = 1,
            maxCandidates = maxCandidates,
        )
}
