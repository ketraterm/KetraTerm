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
import io.github.ketraterm.completion.api.TerminalCompletionCandidateKind
import io.github.ketraterm.completion.api.TerminalCompletionRequest
import io.github.ketraterm.completion.internal.TERMINAL_COMPLETION_CANDIDATE_ORDER
import io.github.ketraterm.completion.internal.canonicalizeWorkingDirectoryUri
import io.github.ketraterm.completion.internal.isRelativeCdCommand
import io.github.ketraterm.completion.internal.normalizeTerminalCommandLine
import io.github.ketraterm.completion.model.TerminalCommandCompletionStats

/**
 * Builds history candidates from exact command statistics.
 *
 * The builder is intentionally stateless: the caller owns synchronization and
 * supplies an immutable snapshot. This keeps command-stat storage separate from
 * candidate filtering, projection, and score calculation.
 */
internal object ExactCommandStatsCandidateBuilder {
    fun complete(
        request: TerminalCompletionRequest,
        snapshot: List<TerminalCommandCompletionStats>,
    ): List<TerminalCompletionCandidate> {
        if (snapshot.isEmpty()) return emptyList()

        val prefix = request.commandLine.substring(0, request.cursorOffset).trimStart()
        val normalizedPrefix = normalizeTerminalCommandLine(prefix)
        val candidates = ArrayList<TerminalCompletionCandidate>(minOf(snapshot.size, request.maxCandidates))
        for (entry in snapshot) {
            if (!entry.hasPositiveSuggestionSignal()) continue
            if (!entry.normalizedCommandLine.startsWith(normalizedPrefix)) continue
            if (entry.normalizedCommandLine == normalizedPrefix) continue
            if (isRelativeCdCommand(entry.commandLine)) {
                val entryUri = entry.workingDirectoryUri
                val requestUri = request.workingDirectoryUri
                if (entryUri != null &&
                    requestUri != null &&
                    canonicalizeWorkingDirectoryUri(entryUri) != canonicalizeWorkingDirectoryUri(requestUri)
                ) {
                    continue
                }
            }
            candidates += entry.toCandidate(request)
        }
        return candidates
            .sortedWith(TERMINAL_COMPLETION_CANDIDATE_ORDER)
            .take(request.maxCandidates)
    }

    private fun TerminalCommandCompletionStats.toCandidate(request: TerminalCompletionRequest): TerminalCompletionCandidate =
        TerminalCompletionCandidate(
            replacementText = commandLine,
            replacementStartOffset = 0,
            replacementEndOffset = request.commandLine.length,
            displayText = commandLine,
            source = SOURCE_STATS,
            kind = TerminalCompletionCandidateKind.HISTORY,
            score = score(request),
        )

    private fun TerminalCommandCompletionStats.hasPositiveSuggestionSignal(): Boolean = successCount > 0 || acceptedCount > 0

    private fun TerminalCommandCompletionStats.score(request: TerminalCompletionRequest): Int {
        val counterScore =
            STATS_BASE_SCORE.toLong() +
                TerminalCompletionScoreAdjustment.counterContribution(SCORE_POLICY, useCount, USE_COUNT_SCORE) +
                TerminalCompletionScoreAdjustment.counterContribution(SCORE_POLICY, successCount, SUCCESS_COUNT_SCORE) +
                TerminalCompletionScoreAdjustment.counterContribution(SCORE_POLICY, failureCount, -FAILURE_COUNT_PENALTY) +
                TerminalCompletionScoreAdjustment.counterContribution(SCORE_POLICY, acceptedCount, ACCEPTED_COUNT_SCORE) +
                TerminalCompletionScoreAdjustment.counterContribution(SCORE_POLICY, dismissedCount, -DISMISSED_COUNT_PENALTY) +
                minOf(lastUsedEpochMillis / RECENCY_SCORE_BUCKET_MILLIS, MAX_RECENCY_SCORE)
        return TerminalCompletionScoreAdjustment.score(
            policy = SCORE_POLICY,
            request = request,
            profileId = profileId,
            workingDirectoryUri = workingDirectoryUri,
            counterScore = counterScore,
        )
    }

    private const val SOURCE_STATS = "stats"
    private const val STATS_BASE_SCORE = 620
    private const val USE_COUNT_SCORE = 18
    private const val SUCCESS_COUNT_SCORE = 10
    private const val FAILURE_COUNT_PENALTY = 16
    private const val ACCEPTED_COUNT_SCORE = 24
    private const val DISMISSED_COUNT_PENALTY = 30
    private const val MAX_COUNTER_SCORE_UNITS = 50
    private const val PROFILE_MATCH_SCORE = 50
    private const val WORKING_DIRECTORY_MATCH_SCORE = 80
    private const val RECENCY_SCORE_BUCKET_MILLIS = 60_000L
    private const val MAX_RECENCY_SCORE = 200L
    private val SCORE_POLICY =
        TerminalCompletionScoreAdjustment.Policy(
            maxCounterScoreUnits = MAX_COUNTER_SCORE_UNITS,
            minScoreAdjustment = Int.MIN_VALUE,
            maxScoreAdjustment = Int.MAX_VALUE,
            profileMatchBoost = PROFILE_MATCH_SCORE,
            workingDirectoryMatchBoost = WORKING_DIRECTORY_MATCH_SCORE,
        )
}
