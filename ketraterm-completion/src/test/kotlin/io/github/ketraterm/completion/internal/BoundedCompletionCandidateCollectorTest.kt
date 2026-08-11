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
package io.github.ketraterm.completion.internal

import io.github.ketraterm.completion.api.TerminalCompletionCandidate
import io.github.ketraterm.completion.api.TerminalCompletionCandidateKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoundedCompletionCandidateCollectorTest {
    @Test
    fun `retains comparator-defined top candidates with deterministic ties`() {
        val collector = BoundedCompletionCandidateCollector(2)
        collector.offer(candidate("zeta", 10))
        collector.offer(candidate("beta", 20))

        assertTrue(collector.shouldMaterialize(10))
        collector.offer(candidate("alpha", 10))

        assertEquals(listOf("beta", "alpha"), collector.finish().map(TerminalCompletionCandidate::displayText))
    }

    @Test
    fun `rejects scores below full collector cutoff before materialization`() {
        val collector = BoundedCompletionCandidateCollector(1)
        collector.offer(candidate("best", 20))

        assertFalse(collector.shouldMaterialize(19))
        assertTrue(collector.shouldMaterialize(20))
    }

    private fun candidate(
        text: String,
        score: Int,
    ): TerminalCompletionCandidate =
        TerminalCompletionCandidate(
            replacementText = text,
            replacementStartOffset = 0,
            replacementEndOffset = 0,
            source = "test",
            kind = TerminalCompletionCandidateKind.ARGUMENT,
            score = score,
        )
}
