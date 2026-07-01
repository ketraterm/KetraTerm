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

import io.github.ketraterm.completion.api.TerminalCompletionRequest
import kotlin.test.Test
import kotlin.test.assertEquals

class TerminalCompletionScoreAdjustmentTest {
    @Test
    fun `counter contribution saturates at policy cap`() {
        val contribution =
            TerminalCompletionScoreAdjustment.counterContribution(
                policy = policy(maxCounterScoreUnits = 3),
                count = 99,
                scorePerUnit = 7,
            )

        assertEquals(21L, contribution)
    }

    @Test
    fun `score clamps positive and negative adjustments`() {
        val positive =
            TerminalCompletionScoreAdjustment.score(
                policy = policy(minScoreAdjustment = -20, maxScoreAdjustment = 20),
                request = request(),
                profileId = null,
                workingDirectoryUri = null,
                counterScore =
                    TerminalCompletionScoreAdjustment.counterContribution(
                        policy = policy(minScoreAdjustment = -20, maxScoreAdjustment = 20),
                        count = 5,
                        scorePerUnit = 10,
                    ),
            )
        val negative =
            TerminalCompletionScoreAdjustment.score(
                policy = policy(minScoreAdjustment = -20, maxScoreAdjustment = 20),
                request = request(),
                profileId = null,
                workingDirectoryUri = null,
                counterScore =
                    TerminalCompletionScoreAdjustment.counterContribution(
                        policy = policy(minScoreAdjustment = -20, maxScoreAdjustment = 20),
                        count = 5,
                        scorePerUnit = -10,
                    ),
            )

        assertEquals(20, positive)
        assertEquals(-20, negative)
    }

    @Test
    fun `score applies profile and working directory boosts`() {
        val score =
            TerminalCompletionScoreAdjustment.score(
                policy =
                    policy(
                        profileMatchBoost = 8,
                        workingDirectoryMatchBoost = 12,
                    ),
                request = request(profileId = "bash", workingDirectoryUri = "file:///repo"),
                profileId = "bash",
                workingDirectoryUri = "file:///repo",
                counterScore =
                    TerminalCompletionScoreAdjustment.counterContribution(
                        policy =
                            policy(
                                profileMatchBoost = 8,
                                workingDirectoryMatchBoost = 12,
                            ),
                        count = 2,
                        scorePerUnit = 5,
                    ),
            )

        assertEquals(30, score)
    }

    @Test
    fun `best adjustment chooses specific match over broad match`() {
        val adjustment =
            TerminalCompletionScoreAdjustment.bestMatchingAdjustment(
                records =
                    listOf(
                        Match(specificity = 1, adjustment = 100),
                        Match(specificity = 3, adjustment = -25),
                    ),
                specificity = Match::specificity,
                adjustment = Match::adjustment,
            )

        assertEquals(-25, adjustment)
    }

    @Test
    fun `best adjustment chooses highest score for equal specificity`() {
        val adjustment =
            TerminalCompletionScoreAdjustment.bestMatchingAdjustment(
                records =
                    listOf(
                        Match(specificity = 2, adjustment = -10),
                        Match(specificity = 2, adjustment = 40),
                        Match(specificity = 1, adjustment = 100),
                    ),
                specificity = Match::specificity,
                adjustment = Match::adjustment,
            )

        assertEquals(40, adjustment)
    }

    @Test
    fun `best adjustment returns zero when no records match`() {
        val adjustment =
            TerminalCompletionScoreAdjustment.bestMatchingAdjustment(
                records =
                    listOf(
                        Match(specificity = -1, adjustment = 100),
                        Match(specificity = -2, adjustment = -100),
                    ),
                specificity = Match::specificity,
                adjustment = Match::adjustment,
            )

        assertEquals(0, adjustment)
    }

    private data class Match(
        val specificity: Int,
        val adjustment: Int,
    )

    private fun policy(
        maxCounterScoreUnits: Int = 10,
        minScoreAdjustment: Int = -100,
        maxScoreAdjustment: Int = 100,
        profileMatchBoost: Int = 0,
        workingDirectoryMatchBoost: Int = 0,
    ): TerminalCompletionScoreAdjustment.Policy =
        TerminalCompletionScoreAdjustment.Policy(
            maxCounterScoreUnits = maxCounterScoreUnits,
            minScoreAdjustment = minScoreAdjustment,
            maxScoreAdjustment = maxScoreAdjustment,
            profileMatchBoost = profileMatchBoost,
            workingDirectoryMatchBoost = workingDirectoryMatchBoost,
        )

    private fun request(
        profileId: String? = null,
        workingDirectoryUri: String? = null,
    ): TerminalCompletionRequest =
        TerminalCompletionRequest(
            commandLine = "git s",
            cursorOffset = 5,
            profileId = profileId,
            workingDirectoryUri = workingDirectoryUri,
        )
}
