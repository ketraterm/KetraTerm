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
import io.github.ketraterm.completion.api.TerminalCompletionRequest
import io.github.ketraterm.completion.api.TerminalShellSyntax
import io.github.ketraterm.completion.commandline.TerminalCommandLineTokenizer
import io.github.ketraterm.completion.internal.canonicalizeWorkingDirectoryUri
import io.github.ketraterm.completion.model.*

/** Identity-cached learned evidence indexes for one merged engine. */
internal class LearnedCompletionEvidenceIndexCache(
    private val outcomeResolver: TerminalCompletionOutcomeKeyResolver,
) {
    private val lock = Any()

    @Volatile
    private var indexedSnapshot: TerminalCommandCompletionStatsSnapshot? = null

    @Volatile
    private var indexes: Map<TerminalShellSyntax, LearnedCompletionEvidenceIndex> = emptyMap()

    fun indexFor(
        snapshot: TerminalCommandCompletionStatsSnapshot,
        shellSyntax: TerminalShellSyntax,
    ): LearnedCompletionEvidenceIndex {
        val current = indexes[shellSyntax]
        if (snapshot === indexedSnapshot && current != null) return current
        return synchronized(lock) {
            if (snapshot !== indexedSnapshot) {
                indexedSnapshot = snapshot
                indexes = emptyMap()
            }
            indexes[shellSyntax] ?: LearnedCompletionEvidenceIndex.build(snapshot, shellSyntax, outcomeResolver).also {
                indexes = indexes + (shellSyntax to it)
            }
        }
    }
}

/** Immutable lookup structure used by global ranking. */
internal class LearnedCompletionEvidenceIndex private constructor(
    private val exactRows: Map<LearnedCompletionOutcomeKey, List<TerminalCommandCompletionStats>>,
    private val shapeIndex: ShapeRankingSnapshotIndex,
    private val feedbackIndex: FeedbackRankingSnapshotIndex,
) {
    fun exactAdjustment(
        key: LearnedCompletionOutcomeKey,
        request: TerminalCompletionRequest,
        nowEpochMillis: Long,
    ): Int {
        val rows = mostSpecific(exactRows[key].orEmpty(), request) { profileId to workingDirectoryUri }
        if (rows.isEmpty()) return 0
        val counts = rows.commandCounts()
        var score = minOf(counts.useCount, EXACT_MAX_USE_COUNT) * EXACT_USE_SCORE
        score +=
            boundedRatio(
                counts.successCount - counts.failureCount,
                counts.successCount + counts.failureCount + RATIO_PRIOR,
                EXACT_OUTCOME_SCALE,
            )
        score +=
            boundedRatio(
                counts.acceptedCount - counts.dismissedCount,
                counts.acceptedCount + counts.dismissedCount + RATIO_PRIOR,
                EXACT_FEEDBACK_SCALE,
            )
        score += contextBoost(rows.first(), request, EXACT_PROFILE_BOOST, EXACT_DIRECTORY_BOOST)
        score += recencyBoost(nowEpochMillis, counts.lastUsedEpochMillis)
        return score.coerceIn(EXACT_MIN_ADJUSTMENT.toLong(), EXACT_MAX_ADJUSTMENT.toLong()).toInt()
    }

    fun shapeAdjustment(
        candidateShape: TerminalCommandLineShape?,
        request: TerminalCompletionRequest,
    ): Int {
        if (candidateShape == null) return 0
        val matching = shapeIndex.familyRows(candidateShape).filter { it.shape.matches(candidateShape) }
        val rows = mostSpecific(matching, request) { profileId to workingDirectoryUri }
        if (rows.isEmpty()) return 0
        val counts = rows.shapeCounts()
        var score = minOf(counts.useCount, SHAPE_MAX_USE_COUNT) * SHAPE_USE_SCORE
        score +=
            boundedRatio(
                counts.successCount - counts.failureCount,
                counts.successCount + counts.failureCount + RATIO_PRIOR,
                SHAPE_OUTCOME_SCALE,
            )
        score +=
            boundedRatio(
                counts.acceptedCount - counts.dismissedCount,
                counts.acceptedCount + counts.dismissedCount + RATIO_PRIOR,
                SHAPE_FEEDBACK_SCALE,
            )
        score += contextBoost(rows.first(), request, SHAPE_PROFILE_BOOST, SHAPE_DIRECTORY_BOOST)
        return score.coerceIn(SHAPE_MIN_ADJUSTMENT.toLong(), SHAPE_MAX_ADJUSTMENT.toLong()).toInt()
    }

    fun providerAdjustment(
        candidate: TerminalCompletionCandidate,
        request: TerminalCompletionRequest,
    ): Int {
        val rows =
            feedbackIndex.matchingRows(
                source = candidate.source,
                candidateKind = candidate.kind,
                tokenPosition = TerminalCompletionTokenPosition.fromCandidateKind(candidate.kind),
                profileId = request.profileId,
                workingDirectoryUri = request.workingDirectoryUri,
            )
        if (rows.isEmpty()) return 0
        var accepted = 0L
        var dismissed = 0L
        for (row in rows) {
            accepted = saturatedAdd(accepted, row.acceptedCount.toLong())
            dismissed = saturatedAdd(dismissed, row.dismissedCount.toLong())
        }
        var score = boundedRatio(accepted - dismissed, accepted + dismissed + PROVIDER_RATIO_PRIOR, PROVIDER_FEEDBACK_SCALE)
        score += contextBoost(rows.first(), request, PROVIDER_PROFILE_BOOST, PROVIDER_DIRECTORY_BOOST)
        return score.coerceIn(PROVIDER_MIN_ADJUSTMENT, PROVIDER_MAX_ADJUSTMENT)
    }

    private fun TerminalCommandLineShape.matches(candidate: TerminalCommandLineShape): Boolean {
        if (executable != candidate.executable) return false
        if (!subcommands.startsWith(candidate.subcommands)) return false
        if (!optionNames.containsAll(candidate.optionNames)) return false
        if (positionalArgumentCount < candidate.positionalArgumentCount) return false
        return optionValueCount >= candidate.optionValueCount
    }

    private fun List<String>.startsWith(prefix: List<String>): Boolean {
        if (prefix.size > size) return false
        for (index in prefix.indices) if (this[index] != prefix[index]) return false
        return true
    }

    private data class Counts(
        var useCount: Long = 0,
        var successCount: Long = 0,
        var failureCount: Long = 0,
        var acceptedCount: Long = 0,
        var dismissedCount: Long = 0,
        var lastUsedEpochMillis: Long = 0,
    )

    private fun List<TerminalCommandCompletionStats>.commandCounts(): Counts =
        Counts().also { counts ->
            for (row in this) {
                counts.useCount = saturatedAdd(counts.useCount, row.useCount.toLong())
                counts.successCount = saturatedAdd(counts.successCount, row.successCount.toLong())
                counts.failureCount = saturatedAdd(counts.failureCount, row.failureCount.toLong())
                counts.acceptedCount = saturatedAdd(counts.acceptedCount, row.acceptedCount.toLong())
                counts.dismissedCount = saturatedAdd(counts.dismissedCount, row.dismissedCount.toLong())
                counts.lastUsedEpochMillis = maxOf(counts.lastUsedEpochMillis, row.lastUsedEpochMillis)
            }
        }

    private fun List<TerminalCommandShapeStats>.shapeCounts(): Counts =
        Counts().also { counts ->
            for (row in this) {
                counts.useCount = saturatedAdd(counts.useCount, row.useCount.toLong())
                counts.successCount = saturatedAdd(counts.successCount, row.successCount.toLong())
                counts.failureCount = saturatedAdd(counts.failureCount, row.failureCount.toLong())
                counts.acceptedCount = saturatedAdd(counts.acceptedCount, row.acceptedCount.toLong())
                counts.dismissedCount = saturatedAdd(counts.dismissedCount, row.dismissedCount.toLong())
                counts.lastUsedEpochMillis = maxOf(counts.lastUsedEpochMillis, row.lastUsedEpochMillis)
            }
        }

    companion object {
        private const val RATIO_PRIOR = 4L
        private const val EXACT_MAX_USE_COUNT = 20L
        private const val EXACT_USE_SCORE = 3L
        private const val EXACT_OUTCOME_SCALE = 60
        private const val EXACT_FEEDBACK_SCALE = 140
        private const val EXACT_PROFILE_BOOST = 20
        private const val EXACT_DIRECTORY_BOOST = 30
        private const val EXACT_MIN_ADJUSTMENT = -180
        private const val EXACT_MAX_ADJUSTMENT = 300
        private const val SHAPE_MAX_USE_COUNT = 20L
        private const val SHAPE_USE_SCORE = 1L
        private const val SHAPE_OUTCOME_SCALE = 30
        private const val SHAPE_FEEDBACK_SCALE = 60
        private const val SHAPE_PROFILE_BOOST = 10
        private const val SHAPE_DIRECTORY_BOOST = 15
        private const val SHAPE_MIN_ADJUSTMENT = -80
        private const val SHAPE_MAX_ADJUSTMENT = 120
        private const val PROVIDER_RATIO_PRIOR = 8L
        private const val PROVIDER_FEEDBACK_SCALE = 100
        private const val PROVIDER_PROFILE_BOOST = 10
        private const val PROVIDER_DIRECTORY_BOOST = 15
        private const val PROVIDER_MIN_ADJUSTMENT = -80
        private const val PROVIDER_MAX_ADJUSTMENT = 120
        private const val ONE_HOUR_MILLIS = 60L * 60L * 1_000L
        private const val ONE_DAY_MILLIS = 24L * ONE_HOUR_MILLIS
        private const val SEVEN_DAYS_MILLIS = 7L * ONE_DAY_MILLIS
        private const val THIRTY_DAYS_MILLIS = 30L * ONE_DAY_MILLIS

        fun build(
            snapshot: TerminalCommandCompletionStatsSnapshot,
            shellSyntax: TerminalShellSyntax,
            outcomeResolver: TerminalCompletionOutcomeKeyResolver,
        ): LearnedCompletionEvidenceIndex {
            val mutableRows = HashMap<LearnedCompletionOutcomeKey, MutableList<TerminalCommandCompletionStats>>()
            for (row in snapshot.commandStats) {
                val tokens = TerminalCommandLineTokenizer.parse(row.commandLine, row.commandLine.length, shellSyntax).tokens
                if (tokens.isEmpty()) continue
                outcomeResolver.learnedKey(row.commandLine, shellSyntax, -1, pathAware = false)?.let { key ->
                    mutableRows.getOrPut(key, ::ArrayList).add(row)
                }
                for (tokenIndex in tokens.indices) {
                    outcomeResolver.learnedKey(row.commandLine, shellSyntax, tokenIndex, pathAware = true)?.let { key ->
                        mutableRows.getOrPut(key, ::ArrayList).add(row)
                    }
                }
            }
            return LearnedCompletionEvidenceIndex(
                exactRows = mutableRows.mapValues { (_, rows) -> rows.toList() },
                shapeIndex = ShapeRankingSnapshotIndex.from(snapshot.shapeStats),
                feedbackIndex = FeedbackRankingSnapshotIndex.from(snapshot.feedbackStats),
            )
        }

        private fun boundedRatio(
            numerator: Long,
            denominator: Long,
            scale: Int,
        ): Int {
            if (denominator <= 0L) return 0
            val boundedNumerator = numerator.coerceIn(-denominator, denominator)
            return ((boundedNumerator * scale.toLong()) / denominator).toInt()
        }

        private fun recencyBoost(
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

        private fun saturatedAdd(
            left: Long,
            right: Long,
        ): Long = if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

        private inline fun <T> mostSpecific(
            rows: List<T>,
            request: TerminalCompletionRequest,
            context: T.() -> Pair<String?, String?>,
        ): List<T> {
            var bestSpecificity = -1
            val result = ArrayList<T>()
            for (row in rows) {
                val (profileId, workingDirectoryUri) = row.context()
                val specificity = contextSpecificity(profileId, workingDirectoryUri, request)
                if (specificity < 0) continue
                when {
                    specificity > bestSpecificity -> {
                        bestSpecificity = specificity
                        result.clear()
                        result += row
                    }
                    specificity == bestSpecificity -> result += row
                }
            }
            return result
        }

        private fun contextSpecificity(
            profileId: String?,
            workingDirectoryUri: String?,
            request: TerminalCompletionRequest,
        ): Int {
            var specificity = 0
            if (profileId != null) {
                if (profileId != request.profileId) return -1
                specificity++
            }
            if (workingDirectoryUri != null) {
                val requestUri = request.workingDirectoryUri ?: return -1
                if (canonicalizeWorkingDirectoryUri(workingDirectoryUri) != canonicalizeWorkingDirectoryUri(requestUri)) return -1
                specificity += 2
            }
            return specificity
        }

        private fun contextBoost(
            row: Any,
            request: TerminalCompletionRequest,
            profileBoost: Int,
            directoryBoost: Int,
        ): Int {
            val profileId: String?
            val workingDirectoryUri: String?
            when (row) {
                is TerminalCommandCompletionStats -> {
                    profileId = row.profileId
                    workingDirectoryUri = row.workingDirectoryUri
                }
                is TerminalCommandShapeStats -> {
                    profileId = row.profileId
                    workingDirectoryUri = row.workingDirectoryUri
                }
                is TerminalCompletionFeedbackStats -> {
                    profileId = row.profileId
                    workingDirectoryUri = row.workingDirectoryUri
                }
                else -> return 0
            }
            var score = 0
            if (profileId != null && profileId == request.profileId) score += profileBoost
            if (workingDirectoryUri != null &&
                request.workingDirectoryUri != null &&
                canonicalizeWorkingDirectoryUri(workingDirectoryUri) == canonicalizeWorkingDirectoryUri(request.workingDirectoryUri)
            ) {
                score += directoryBoost
            }
            return score
        }
    }
}
