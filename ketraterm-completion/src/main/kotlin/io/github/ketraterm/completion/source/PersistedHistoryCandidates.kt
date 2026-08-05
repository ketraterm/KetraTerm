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
import io.github.ketraterm.completion.internal.canonicalizeWorkingDirectoryUri
import io.github.ketraterm.completion.internal.isRelativeCdCommand
import io.github.ketraterm.completion.model.TerminalCommandCompletionStats
import io.github.ketraterm.completion.ranking.LearnedEvidenceScoring

/** Appends learned fallback candidates from persisted positive command evidence. */
internal fun appendPersistedHistoryCandidates(
    request: TerminalCompletionRequest,
    lineContext: TerminalCommandLineContext,
    completionContext: TerminalCompletionContext,
    index: LearnedHistoryCandidateIndex,
    nowEpochMillis: Long,
    destination: MutableList<TerminalCompletionCandidate>,
) {
    for (indexed in index.matching(lineContext)) {
        val entry = indexed.stats
        if (!entry.isValidFor(request)) continue
        projectLearnedCommandCandidate(
            request = request,
            requestLine = lineContext,
            completionContext = completionContext,
            learnedCommand = entry.commandLine,
            source = SOURCE_MRU,
            score = entry.localScore(request, nowEpochMillis),
            detailPrefix = "learned",
            learnedLine = indexed.lineContext,
        )?.let(destination::add)
    }
}

private fun TerminalCommandCompletionStats.isValidFor(request: TerminalCompletionRequest): Boolean {
    if (!isRelativeCdCommand(commandLine)) return true
    val entryDirectory = workingDirectoryUri ?: return true
    val requestDirectory = request.workingDirectoryUri ?: return true
    return canonicalizeWorkingDirectoryUri(entryDirectory) == canonicalizeWorkingDirectoryUri(requestDirectory)
}

private fun TerminalCommandCompletionStats.localScore(
    request: TerminalCompletionRequest,
    nowEpochMillis: Long,
): Int {
    var score =
        BASE_SCORE.toLong() +
            counterScore(useCount, USE_COUNT_SCORE) +
            counterScore(successCount, SUCCESS_COUNT_SCORE) +
            counterScore(failureCount, -FAILURE_COUNT_PENALTY) +
            counterScore(acceptedCount, ACCEPTED_COUNT_SCORE) +
            counterScore(dismissedCount, -DISMISSED_COUNT_PENALTY) +
            LearnedEvidenceScoring.recencyBoost(nowEpochMillis, lastUsedEpochMillis)
    if (profileId != null && profileId == request.profileId) score += PROFILE_MATCH_SCORE
    if (workingDirectoryUri != null && workingDirectoryUri == request.workingDirectoryUri) {
        score += WORKING_DIRECTORY_MATCH_SCORE
    }
    return score.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
}

private fun counterScore(
    count: Int,
    scorePerUnit: Int,
): Long = minOf(count, MAX_COUNTER_SCORE_UNITS).toLong() * scorePerUnit.toLong()

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
