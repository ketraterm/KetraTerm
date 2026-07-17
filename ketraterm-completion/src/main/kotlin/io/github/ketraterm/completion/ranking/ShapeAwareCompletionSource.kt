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
import io.github.ketraterm.completion.api.TerminalCompletionSource
import io.github.ketraterm.completion.commandline.*
import io.github.ketraterm.completion.internal.TERMINAL_COMPLETION_CANDIDATE_ORDER
import io.github.ketraterm.completion.internal.commandLineAfterCandidate
import io.github.ketraterm.completion.model.TerminalCommandLineShape
import io.github.ketraterm.completion.model.TerminalCommandShapeStats
import io.github.ketraterm.completion.model.TerminalCommandSpec
import io.github.ketraterm.completion.model.TerminalCommandSpecs

/**
 * Completion source decorator that adjusts candidate scores using learned
 * command-shape affinity.
 *
 * The decorator does not create candidates by itself. It asks [delegate] for a
 * bounded candidate list, projects each candidate onto the command line that
 * would result after replacement, derives a privacy-preserving
 * [TerminalCommandLineShape], and applies a bounded boost or penalty from
 * [shapeStatsProvider]. Exact command history remains a stronger source; this
 * wrapper only reorders candidates produced by the decorated source.
 *
 * @param delegate source whose candidates should be shape-ranked.
 * @param shapeStatsProvider supplier for the latest shape stats snapshot.
 * @param commandSpecs command specifications used to classify projected
 * candidates into the same command families used by stats recording.
 */
internal class ShapeAwareCompletionSource(
    private val delegate: TerminalCompletionSource,
    private val shapeStatsProvider: () -> List<TerminalCommandShapeStats>,
    commandSpecs: List<TerminalCommandSpec> = TerminalCommandSpecs.defaults(),
) : ContextAwareCompletionSource {
    private val commandSpecs = commandSpecs.toList()
    private val indexLock = Any()

    @Volatile
    private var indexedRecords: List<TerminalCommandShapeStats>? = null

    @Volatile
    private var snapshotIndex: ShapeRankingSnapshotIndex = ShapeRankingSnapshotIndex.EMPTY

    /**
     * Returns delegated candidates with learned shape score adjustments.
     *
     * @param request completion context.
     * @return delegated candidates sorted by adjusted score.
     */
    override fun complete(request: TerminalCompletionRequest): List<TerminalCompletionCandidate> =
        complete(
            request,
            TerminalCommandLineTokenizer.parse(request.commandLine, request.cursorOffset, request.shellCapabilities.syntax),
        )

    override fun complete(
        request: TerminalCompletionRequest,
        commandLineContext: TerminalCommandLineContext,
    ): List<TerminalCompletionCandidate> {
        val candidates = delegate.complete(request, commandLineContext)
        if (candidates.isEmpty()) return candidates
        val shapeIndex = indexFor(shapeStatsProvider())
        if (shapeIndex.isEmpty) return candidates

        val adjusted = ArrayList<TerminalCompletionCandidate>(candidates.size)
        for (candidate in candidates) {
            val shape = shapeAfterCandidate(request, candidate)
            val adjustment =
                if (shape == null) {
                    0
                } else {
                    shapeIndex.adjustmentFor(shape, request)
                }
            adjusted += candidate.copy(score = candidate.score + adjustment)
        }
        adjusted.sortWith(TERMINAL_COMPLETION_CANDIDATE_ORDER)
        return if (adjusted.size <= request.maxCandidates) {
            adjusted
        } else {
            adjusted.subList(0, request.maxCandidates).toList()
        }
    }

    private fun indexFor(records: List<TerminalCommandShapeStats>): ShapeRankingSnapshotIndex {
        if (records is IndexedShapeRankingSnapshot) return records.rankingIndex
        if (records === indexedRecords) return snapshotIndex
        return synchronized(indexLock) {
            if (records !== indexedRecords) {
                snapshotIndex = ShapeRankingSnapshotIndex.from(records)
                indexedRecords = records
            }
            snapshotIndex
        }
    }

    private fun shapeAfterCandidate(
        request: TerminalCompletionRequest,
        candidate: TerminalCompletionCandidate,
    ): TerminalCommandLineShape? {
        val completed = request.commandLineAfterCandidate(candidate) ?: return null
        return TerminalCommandLineClassifier
            .classify(completed, commandSpecs)
            ?.shape
    }

    private fun ShapeRankingSnapshotIndex.adjustmentFor(
        shape: TerminalCommandLineShape,
        request: TerminalCompletionRequest,
    ): Int =
        TerminalCompletionScoreAdjustment.bestMatchingAdjustment(
            records = familyRows(shape),
            specificity = {
                if (it.shape.matchesCandidateShape(shape)) {
                    it.contextSpecificity(request)
                } else {
                    -1
                }
            },
            adjustment = { it.scoreAdjustment(request) },
        )

    private fun TerminalCommandShapeStats.contextSpecificity(request: TerminalCompletionRequest): Int {
        var specificity = 0
        if (profileId != null) {
            if (profileId != request.profileId) return -1
            specificity += PROFILE_SPECIFICITY
        }
        if (workingDirectoryUri != null) {
            if (workingDirectoryUri != request.workingDirectoryUri) return -1
            specificity += WORKING_DIRECTORY_SPECIFICITY
        }
        return specificity
    }

    private fun TerminalCommandLineShape.matchesCandidateShape(candidateShape: TerminalCommandLineShape): Boolean {
        if (executable != candidateShape.executable) return false
        if (!subcommands.startsWith(candidateShape.subcommands)) return false
        if (!optionNames.containsAll(candidateShape.optionNames)) return false
        if (positionalArgumentCount < candidateShape.positionalArgumentCount) return false
        if (optionValueCount < candidateShape.optionValueCount) return false
        return true
    }

    private fun List<String>.startsWith(prefix: List<String>): Boolean {
        if (prefix.size > size) return false
        var index = 0
        while (index < prefix.size) {
            if (this[index] != prefix[index]) return false
            index++
        }
        return true
    }

    private fun TerminalCommandShapeStats.scoreAdjustment(request: TerminalCompletionRequest): Int =
        TerminalCompletionScoreAdjustment.score(
            policy = SCORE_POLICY,
            request = request,
            profileId = profileId,
            workingDirectoryUri = workingDirectoryUri,
            counterScore =
                TerminalCompletionScoreAdjustment.counterContribution(SCORE_POLICY, useCount, USE_COUNT_BOOST) +
                    TerminalCompletionScoreAdjustment.counterContribution(SCORE_POLICY, successCount, SUCCESS_COUNT_BOOST) +
                    TerminalCompletionScoreAdjustment.counterContribution(SCORE_POLICY, acceptedCount, ACCEPTED_COUNT_BOOST) +
                    TerminalCompletionScoreAdjustment.counterContribution(SCORE_POLICY, failureCount, -FAILURE_COUNT_PENALTY) +
                    TerminalCompletionScoreAdjustment.counterContribution(SCORE_POLICY, dismissedCount, -DISMISSED_COUNT_PENALTY),
        )

    private companion object {
        private const val USE_COUNT_BOOST = 3
        private const val SUCCESS_COUNT_BOOST = 5
        private const val ACCEPTED_COUNT_BOOST = 12
        private const val FAILURE_COUNT_PENALTY = 6
        private const val DISMISSED_COUNT_PENALTY = 14
        private const val PROFILE_MATCH_BOOST = 8
        private const val WORKING_DIRECTORY_MATCH_BOOST = 12
        private const val PROFILE_SPECIFICITY = 1
        private const val WORKING_DIRECTORY_SPECIFICITY = 2
        private const val MAX_COUNTER_SCORE_UNITS = 25
        private const val MIN_SCORE_ADJUSTMENT = -180
        private const val MAX_SCORE_ADJUSTMENT = 180
        private val SCORE_POLICY =
            TerminalCompletionScoreAdjustment.Policy(
                maxCounterScoreUnits = MAX_COUNTER_SCORE_UNITS,
                minScoreAdjustment = MIN_SCORE_ADJUSTMENT,
                maxScoreAdjustment = MAX_SCORE_ADJUSTMENT,
                profileMatchBoost = PROFILE_MATCH_BOOST,
                workingDirectoryMatchBoost = WORKING_DIRECTORY_MATCH_BOOST,
            )
    }
}
