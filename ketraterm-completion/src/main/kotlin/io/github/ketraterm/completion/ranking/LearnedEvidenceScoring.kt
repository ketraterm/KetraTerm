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

/** Documented numeric policy for exact, shape, provider, and recency learning. */
internal object LearnedEvidenceScoring {
    fun exact(
        counts: LearnedEvidenceCounts,
        contextBoost: Int,
        nowEpochMillis: Long,
    ): Int {
        var score = minOf(counts.useCount, EXACT_MAX_USE_COUNT) * EXACT_USE_SCORE
        score += outcomeRatio(counts, EXACT_OUTCOME_SCALE)
        score += feedbackRatio(counts, RATIO_PRIOR, EXACT_FEEDBACK_SCALE)
        score += contextBoost
        score += recencyBoost(nowEpochMillis, counts.lastUsedEpochMillis)
        return score.coerceIn(EXACT_MIN_ADJUSTMENT, EXACT_MAX_ADJUSTMENT).toInt()
    }

    fun shape(
        counts: LearnedEvidenceCounts,
        contextBoost: Int,
    ): Int {
        var score = minOf(counts.useCount, SHAPE_MAX_USE_COUNT) * SHAPE_USE_SCORE
        score += outcomeRatio(counts, SHAPE_OUTCOME_SCALE)
        score += feedbackRatio(counts, RATIO_PRIOR, SHAPE_FEEDBACK_SCALE)
        score += contextBoost
        return score.coerceIn(SHAPE_MIN_ADJUSTMENT, SHAPE_MAX_ADJUSTMENT).toInt()
    }

    fun provider(
        counts: LearnedEvidenceCounts,
        contextBoost: Int,
    ): Int {
        val score = feedbackRatio(counts, PROVIDER_RATIO_PRIOR, PROVIDER_FEEDBACK_SCALE) + contextBoost
        return score.coerceIn(PROVIDER_MIN_ADJUSTMENT, PROVIDER_MAX_ADJUSTMENT)
    }

    private fun outcomeRatio(
        counts: LearnedEvidenceCounts,
        scale: Int,
    ): Int =
        boundedRatio(
            numerator = counts.successCount - counts.failureCount,
            denominator = saturatedPositiveSum(counts.successCount, counts.failureCount, RATIO_PRIOR),
            scale = scale,
        )

    private fun feedbackRatio(
        counts: LearnedEvidenceCounts,
        prior: Long,
        scale: Int,
    ): Int =
        boundedRatio(
            numerator = counts.acceptedCount - counts.dismissedCount,
            denominator = saturatedPositiveSum(counts.acceptedCount, counts.dismissedCount, prior),
            scale = scale,
        )

    private fun boundedRatio(
        numerator: Long,
        denominator: Long,
        scale: Int,
    ): Int {
        if (denominator <= 0L) return 0
        val boundedNumerator = numerator.coerceIn(-denominator, denominator)
        val scaleLong = scale.toLong()
        return if (boundedNumerator in Long.MIN_VALUE / scaleLong..Long.MAX_VALUE / scaleLong) {
            ((boundedNumerator * scaleLong) / denominator).toInt()
        } else {
            ((boundedNumerator.toDouble() / denominator.toDouble()) * scaleLong).toInt()
        }
    }

    private fun saturatedPositiveSum(
        first: Long,
        second: Long,
        third: Long,
    ): Long = saturatedPositiveAdd(saturatedPositiveAdd(first, second), third)

    private fun saturatedPositiveAdd(
        left: Long,
        right: Long,
    ): Long = if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

    fun recencyBoost(
        nowEpochMillis: Long,
        lastUsedEpochMillis: Long,
    ): Int {
        if (lastUsedEpochMillis <= 0L) return 0
        val age = (nowEpochMillis - lastUsedEpochMillis).coerceAtLeast(0L)
        return when {
            age <= ONE_HOUR_MILLIS -> 40
            age <= ONE_DAY_MILLIS -> 25
            age <= SEVEN_DAYS_MILLIS -> 10
            age <= THIRTY_DAYS_MILLIS -> 5
            else -> 0
        }
    }

    private const val RATIO_PRIOR = 4L
    private const val EXACT_MAX_USE_COUNT = 20L
    private const val EXACT_USE_SCORE = 3L
    private const val EXACT_OUTCOME_SCALE = 60
    private const val EXACT_FEEDBACK_SCALE = 140
    private const val EXACT_MIN_ADJUSTMENT = -180L
    private const val EXACT_MAX_ADJUSTMENT = 300L
    private const val SHAPE_MAX_USE_COUNT = 20L
    private const val SHAPE_USE_SCORE = 1L
    private const val SHAPE_OUTCOME_SCALE = 30
    private const val SHAPE_FEEDBACK_SCALE = 60
    private const val SHAPE_MIN_ADJUSTMENT = -80L
    private const val SHAPE_MAX_ADJUSTMENT = 120L
    private const val PROVIDER_RATIO_PRIOR = 8L
    private const val PROVIDER_FEEDBACK_SCALE = 100
    private const val PROVIDER_MIN_ADJUSTMENT = -80
    private const val PROVIDER_MAX_ADJUSTMENT = 120
    private const val ONE_HOUR_MILLIS = 60L * 60L * 1_000L
    private const val ONE_DAY_MILLIS = 24L * ONE_HOUR_MILLIS
    private const val SEVEN_DAYS_MILLIS = 7L * ONE_DAY_MILLIS
    private const val THIRTY_DAYS_MILLIS = 30L * ONE_DAY_MILLIS
}
