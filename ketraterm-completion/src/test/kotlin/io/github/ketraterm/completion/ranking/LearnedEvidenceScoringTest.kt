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

import kotlin.test.Test
import kotlin.test.assertEquals

class LearnedEvidenceScoringTest {
    @Test
    fun `saturated positive evidence remains bounded`() {
        val counts =
            LearnedEvidenceCounts(
                useCount = Long.MAX_VALUE,
                successCount = Long.MAX_VALUE,
                acceptedCount = Long.MAX_VALUE,
            )

        assertEquals(260, LearnedEvidenceScoring.exact(counts, contextBoost = 0, nowEpochMillis = 0))
        assertEquals(110, LearnedEvidenceScoring.shape(counts, contextBoost = 0))
        assertEquals(100, LearnedEvidenceScoring.provider(counts, contextBoost = 0))
    }

    @Test
    fun `saturated negative evidence remains bounded`() {
        val counts =
            LearnedEvidenceCounts(
                failureCount = Long.MAX_VALUE,
                dismissedCount = Long.MAX_VALUE,
            )

        assertEquals(-180, LearnedEvidenceScoring.exact(counts, contextBoost = 0, nowEpochMillis = 0))
        assertEquals(-80, LearnedEvidenceScoring.shape(counts, contextBoost = 0))
        assertEquals(-80, LearnedEvidenceScoring.provider(counts, contextBoost = 0))
    }

    @Test
    fun `recency uses elapsed age and safely handles future timestamps`() {
        val now = 2_000_000_000_000L

        assertEquals(40, LearnedEvidenceScoring.recencyBoost(now, now - 60_000L))
        assertEquals(25, LearnedEvidenceScoring.recencyBoost(now, now - 2L * 60L * 60L * 1_000L))
        assertEquals(10, LearnedEvidenceScoring.recencyBoost(now, now - 2L * 24L * 60L * 60L * 1_000L))
        assertEquals(5, LearnedEvidenceScoring.recencyBoost(now, now - 8L * 24L * 60L * 60L * 1_000L))
        assertEquals(0, LearnedEvidenceScoring.recencyBoost(now, now - 31L * 24L * 60L * 60L * 1_000L))
        assertEquals(40, LearnedEvidenceScoring.recencyBoost(now, now + 1_000L))
        assertEquals(0, LearnedEvidenceScoring.recencyBoost(now, 0L))
    }
}
