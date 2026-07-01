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
class ShapeAwareCompletionSource(
    private val delegate: TerminalCompletionSource,
    private val shapeStatsProvider: () -> List<TerminalCommandShapeStats>,
    commandSpecs: List<TerminalCommandSpec> = TerminalCommandSpecs.defaults(),
) : TerminalCompletionSource {
    private val commandSpecs = commandSpecs.toList()

    /**
     * Returns delegated candidates with learned shape score adjustments.
     *
     * @param request completion context.
     * @return delegated candidates sorted by adjusted score.
     */
    override fun complete(request: TerminalCompletionRequest): List<TerminalCompletionCandidate> {
        val candidates = delegate.complete(request)
        if (candidates.isEmpty()) return candidates
        val shapeStats = shapeStatsProvider()
        if (shapeStats.isEmpty()) return candidates

        val adjusted = ArrayList<TerminalCompletionCandidate>(candidates.size)
        for (candidate in candidates) {
            val shape = shapeAfterCandidate(request, candidate)
            val adjustment =
                if (shape == null) {
                    0
                } else {
                    shapeStats.adjustmentFor(shape, request)
                }
            adjusted += candidate.copy(score = candidate.score + adjustment)
        }
        return adjusted
            .sortedWith(TERMINAL_COMPLETION_CANDIDATE_ORDER)
            .take(request.maxCandidates)
    }

    private fun shapeAfterCandidate(
        request: TerminalCompletionRequest,
        candidate: TerminalCompletionCandidate,
    ): TerminalCommandLineShape? {
        if (candidate.replacementStartOffset > request.commandLine.length) return null
        if (candidate.replacementEndOffset > request.commandLine.length) return null
        if (candidate.replacementStartOffset > candidate.replacementEndOffset) return null
        val completed =
            request.commandLine.replaceRange(
                candidate.replacementStartOffset,
                candidate.replacementEndOffset,
                candidate.replacementText,
            )
        return TerminalCommandLineClassifier
            .classify(completed, commandSpecs)
            ?.shape
    }

    private fun List<TerminalCommandShapeStats>.adjustmentFor(
        shape: TerminalCommandLineShape,
        request: TerminalCompletionRequest,
    ): Int {
        var best: Int? = null
        var bestSpecificity = -1
        for (stats in this) {
            if (!stats.shape.matchesCandidateShape(shape)) continue
            val specificity = stats.contextSpecificity(request)
            if (specificity < 0) continue
            val adjustment = stats.scoreAdjustment(request)
            if (specificity > bestSpecificity || (specificity == bestSpecificity && (best == null || adjustment > best))) {
                best = adjustment
                bestSpecificity = specificity
            }
        }
        return best ?: 0
    }

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

    private fun TerminalCommandShapeStats.scoreAdjustment(request: TerminalCompletionRequest): Int {
        var score = 0
        score += minOf(useCount, MAX_COUNTER_SCORE_UNITS) * USE_COUNT_BOOST
        score += minOf(successCount, MAX_COUNTER_SCORE_UNITS) * SUCCESS_COUNT_BOOST
        score += minOf(acceptedCount, MAX_COUNTER_SCORE_UNITS) * ACCEPTED_COUNT_BOOST
        score -= minOf(failureCount, MAX_COUNTER_SCORE_UNITS) * FAILURE_COUNT_PENALTY
        score -= minOf(dismissedCount, MAX_COUNTER_SCORE_UNITS) * DISMISSED_COUNT_PENALTY
        if (profileId != null && profileId == request.profileId) score += PROFILE_MATCH_BOOST
        if (workingDirectoryUri != null && workingDirectoryUri == request.workingDirectoryUri) {
            score += WORKING_DIRECTORY_MATCH_BOOST
        }
        return score.coerceIn(MIN_SCORE_ADJUSTMENT, MAX_SCORE_ADJUSTMENT)
    }

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
    }
}
