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

/**
 * Shared bounded scoring helper for learned completion ranking adjustments.
 *
 * This helper intentionally owns only the generic math used by decorators:
 * saturated counter contribution, profile/working-directory boosts, score
 * clamping, and "best by specificity, then score" selection. Domain matching
 * remains in each source because feedback rows and shape rows have different
 * match semantics.
 */
internal object TerminalCompletionScoreAdjustment {
    /**
     * Immutable score-adjustment policy for one learned ranking source.
     *
     * @property maxCounterScoreUnits maximum counter units contributing to a
     * score so a single heavily repeated signal cannot dominate forever.
     * @property minScoreAdjustment lowest returned score adjustment.
     * @property maxScoreAdjustment highest returned score adjustment.
     * @property profileMatchBoost score boost when a source-specific row
     * matches the request profile.
     * @property workingDirectoryMatchBoost score boost when a source-specific
     * row matches the request working directory.
     */
    data class Policy(
        val maxCounterScoreUnits: Int,
        val minScoreAdjustment: Int,
        val maxScoreAdjustment: Int,
        val profileMatchBoost: Int,
        val workingDirectoryMatchBoost: Int,
    ) {
        init {
            require(maxCounterScoreUnits >= 0) {
                "maxCounterScoreUnits must be >= 0, was $maxCounterScoreUnits"
            }
            require(minScoreAdjustment <= maxScoreAdjustment) {
                "minScoreAdjustment must be <= maxScoreAdjustment, was $minScoreAdjustment > $maxScoreAdjustment"
            }
        }
    }

    /**
     * Returns one saturated counter contribution for [policy].
     *
     * @param policy score policy carrying the counter cap.
     * @param count non-negative learned counter value.
     * @param scorePerUnit signed score contribution per saturated counter unit.
     * @return saturated signed score contribution.
     */
    fun counterContribution(
        policy: Policy,
        count: Int,
        scorePerUnit: Int,
    ): Long {
        require(count >= 0) { "count must be >= 0, was $count" }
        val units = minOf(count, policy.maxCounterScoreUnits)
        return units.toLong() * scorePerUnit.toLong()
    }

    /**
     * Computes a bounded score adjustment from counters and request context.
     *
     * @param policy score bounds and context boosts.
     * @param request completion request used for profile and working-directory matching.
     * @param profileId optional row profile id.
     * @param workingDirectoryUri optional row working-directory URI.
     * @param counterScore pre-accumulated signed learned counter contribution.
     * @return clamped score adjustment.
     */
    fun score(
        policy: Policy,
        request: TerminalCompletionRequest,
        profileId: String?,
        workingDirectoryUri: String?,
        counterScore: Long,
    ): Int {
        var score = counterScore
        score += contextBoost(policy, request, profileId, workingDirectoryUri).toLong()
        return score
            .coerceIn(policy.minScoreAdjustment.toLong(), policy.maxScoreAdjustment.toLong())
            .toInt()
    }

    /**
     * Selects the best matching adjustment from a set of candidate rows.
     *
     * Rows with negative specificity are treated as non-matches. Higher
     * specificity wins; if specificity ties, the higher adjustment wins. This
     * preserves existing feedback and shape ranking behavior.
     *
     * @param records candidate learned rows.
     * @param specificity returns row specificity, or a negative value for no match.
     * @param adjustment returns the already-clamped row adjustment.
     * @return best adjustment, or zero when no row matches.
     */
    inline fun <T> bestMatchingAdjustment(
        records: Iterable<T>,
        specificity: (T) -> Int,
        adjustment: (T) -> Int,
    ): Int {
        var best: Int? = null
        var bestSpecificity = -1
        for (record in records) {
            val currentSpecificity = specificity(record)
            if (currentSpecificity < 0) continue
            val currentAdjustment = adjustment(record)
            if (
                currentSpecificity > bestSpecificity ||
                (currentSpecificity == bestSpecificity && (best == null || currentAdjustment > best))
            ) {
                best = currentAdjustment
                bestSpecificity = currentSpecificity
            }
        }
        return best ?: 0
    }

    private fun contextBoost(
        policy: Policy,
        request: TerminalCompletionRequest,
        profileId: String?,
        workingDirectoryUri: String?,
    ): Int {
        var score = 0
        if (profileId != null && profileId == request.profileId) score += policy.profileMatchBoost
        if (workingDirectoryUri != null && workingDirectoryUri == request.workingDirectoryUri) {
            score += policy.workingDirectoryMatchBoost
        }
        return score
    }
}
