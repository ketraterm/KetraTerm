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
package io.github.ketraterm.completion.source

import io.github.ketraterm.completion.api.TerminalCompletionCandidate
import io.github.ketraterm.completion.api.TerminalCompletionRequest
import io.github.ketraterm.completion.commandline.TerminalCommandLineContext
import io.github.ketraterm.completion.commandline.TerminalCompletionContext
import io.github.ketraterm.completion.commandline.commandPrefix
import io.github.ketraterm.completion.internal.canonicalizeWorkingDirectoryUri
import io.github.ketraterm.completion.internal.isRelativeCdCommand
import io.github.ketraterm.completion.internal.normalizeTerminalCommandLine
import io.github.ketraterm.completion.model.TerminalCommandCompletionStats
import io.github.ketraterm.completion.ranking.TerminalCompletionScoreAdjustment

/** Builds learned fallback candidates from persisted positive command evidence. */
internal object PersistedHistoryCandidateBuilder {
    fun appendCandidates(
        request: TerminalCompletionRequest,
        lineContext: TerminalCommandLineContext,
        completionContext: TerminalCompletionContext,
        snapshot: List<TerminalCommandCompletionStats>,
        destination: MutableList<TerminalCompletionCandidate>,
    ) {
        if (snapshot.isEmpty()) return
        val normalizedPrefix = normalizeTerminalCommandLine(lineContext.commandPrefix(request.commandLine))
        for (entry in snapshot) {
            if (!entry.hasPositiveSuggestionSignal()) continue
            if (!entry.normalizedCommandLine.startsWith(normalizedPrefix) || entry.normalizedCommandLine == normalizedPrefix) continue
            if (!entry.isValidFor(request)) continue
            LearnedCommandCandidateProjector
                .project(
                    request = request,
                    requestLine = lineContext,
                    completionContext = completionContext,
                    learnedCommand = entry.commandLine,
                    source = SOURCE_MRU,
                    score = entry.localScore(request),
                    detailPrefix = "learned",
                )?.let(destination::add)
        }
    }

    private fun TerminalCommandCompletionStats.hasPositiveSuggestionSignal(): Boolean = successCount > 0 || acceptedCount > 0

    private fun TerminalCommandCompletionStats.isValidFor(request: TerminalCompletionRequest): Boolean {
        if (!isRelativeCdCommand(commandLine)) return true
        val entryDirectory = workingDirectoryUri ?: return true
        val requestDirectory = request.workingDirectoryUri ?: return true
        return canonicalizeWorkingDirectoryUri(entryDirectory) == canonicalizeWorkingDirectoryUri(requestDirectory)
    }

    private fun TerminalCommandCompletionStats.localScore(request: TerminalCompletionRequest): Int {
        val counterScore =
            BASE_SCORE.toLong() +
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

    private const val SOURCE_MRU = "mru"
    private const val BASE_SCORE = 620
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
