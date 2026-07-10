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
import io.github.ketraterm.completion.commandline.ContextAwareCompletionSource
import io.github.ketraterm.completion.commandline.TerminalCommandLineContext
import io.github.ketraterm.completion.commandline.TerminalCommandLineTokenizer
import io.github.ketraterm.completion.commandline.complete
import io.github.ketraterm.completion.internal.TERMINAL_COMPLETION_CANDIDATE_ORDER
import io.github.ketraterm.completion.model.TerminalCompletionFeedbackStats
import io.github.ketraterm.completion.model.TerminalCompletionTokenPosition

/**
 * Completion source decorator that adjusts scores from source-specific feedback.
 *
 * The decorator does not create candidates. It only applies bounded boosts or
 * penalties to candidates returned by [delegate] when persisted
 * [TerminalCompletionFeedbackStats] match the candidate source, kind, token
 * position, profile, and working directory.
 *
 * @param delegate source whose candidates should be feedback-ranked.
 * @param feedbackStatsProvider supplier for the latest source-specific
 * feedback snapshot.
 */
internal class FeedbackAwareCompletionSource(
    private val delegate: TerminalCompletionSource,
    private val feedbackStatsProvider: () -> List<TerminalCompletionFeedbackStats>,
) : ContextAwareCompletionSource {
    /**
     * Returns delegated candidates with source-specific feedback adjustments.
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
        val feedbackStats = feedbackStatsProvider()
        if (feedbackStats.isEmpty()) return candidates

        val adjusted = ArrayList<TerminalCompletionCandidate>(candidates.size)
        for (candidate in candidates) {
            adjusted += candidate.copy(score = candidate.score + feedbackStats.adjustmentFor(candidate, request))
        }
        return adjusted
            .sortedWith(TERMINAL_COMPLETION_CANDIDATE_ORDER)
            .take(request.maxCandidates)
    }

    private fun List<TerminalCompletionFeedbackStats>.adjustmentFor(
        candidate: TerminalCompletionCandidate,
        request: TerminalCompletionRequest,
    ): Int {
        val tokenPosition = TerminalCompletionTokenPosition.fromCandidateKind(candidate.kind)
        return TerminalCompletionScoreAdjustment.bestMatchingAdjustment(
            records = this,
            specificity = { it.specificityFor(candidate, tokenPosition, request) },
            adjustment = { it.scoreAdjustment(request) },
        )
    }

    private fun TerminalCompletionFeedbackStats.specificityFor(
        candidate: TerminalCompletionCandidate,
        tokenPosition: TerminalCompletionTokenPosition,
        request: TerminalCompletionRequest,
    ): Int {
        if (source != candidate.source) return -1
        if (candidateKind != candidate.kind) return -1
        if (this.tokenPosition != tokenPosition) return -1
        var specificity = SOURCE_KIND_POSITION_SPECIFICITY
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

    private fun TerminalCompletionFeedbackStats.scoreAdjustment(request: TerminalCompletionRequest): Int =
        TerminalCompletionScoreAdjustment.score(
            policy = SCORE_POLICY,
            request = request,
            profileId = profileId,
            workingDirectoryUri = workingDirectoryUri,
            counterScore =
                TerminalCompletionScoreAdjustment.counterContribution(SCORE_POLICY, acceptedCount, ACCEPTED_COUNT_BOOST) +
                    TerminalCompletionScoreAdjustment.counterContribution(SCORE_POLICY, dismissedCount, -DISMISSED_COUNT_PENALTY),
        )

    private companion object {
        private const val ACCEPTED_COUNT_BOOST = 16
        private const val DISMISSED_COUNT_PENALTY = 22
        private const val PROFILE_MATCH_BOOST = 8
        private const val WORKING_DIRECTORY_MATCH_BOOST = 12
        private const val SOURCE_KIND_POSITION_SPECIFICITY = 1
        private const val PROFILE_SPECIFICITY = 2
        private const val WORKING_DIRECTORY_SPECIFICITY = 3
        private const val MAX_COUNTER_SCORE_UNITS = 12
        private const val MIN_SCORE_ADJUSTMENT = -160
        private const val MAX_SCORE_ADJUSTMENT = 160
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
