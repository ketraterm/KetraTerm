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
import io.github.ketraterm.completion.api.TerminalCompletionContext
import io.github.ketraterm.completion.api.TerminalCompletionRequest
import io.github.ketraterm.completion.commandline.TerminalCommandLineContext
import io.github.ketraterm.completion.commandline.TerminalCommandLineToken
import io.github.ketraterm.completion.commandline.firstCommandTokenIndex
import io.github.ketraterm.completion.commandline.normalizeTerminalCommandToken
import io.github.ketraterm.completion.internal.canonicalizeWorkingDirectoryUri
import io.github.ketraterm.completion.model.TerminalCommandCompletionStats
import io.github.ketraterm.completion.ranking.LearnedEvidenceScoring

/** Appends fallback candidates from positive exact-command learning. */
internal fun appendLearnedHistoryCandidates(
    request: TerminalCompletionRequest,
    lineContext: TerminalCommandLineContext,
    completionContext: TerminalCompletionContext,
    index: LearnedHistoryCandidateIndex,
    nowEpochMillis: Long,
    destination: MutableList<TerminalCompletionCandidate>,
) {
    for (indexed in index.matching(lineContext)) {
        val entry = indexed.stats
        if (!isValidForDirectory(indexed.lineContext, entry.workingDirectoryUri, request.workingDirectoryUri)) continue
        projectLearnedCommandCandidate(
            request = request,
            requestLine = lineContext,
            completionContext = completionContext,
            learnedCommand = entry.commandLine,
            source = SOURCE_LEARNED,
            score = entry.localScore(request, nowEpochMillis),
            detailPrefix = "learned",
            learnedLine = indexed.lineContext,
        )?.let(destination::add)
    }
}

private fun isValidForDirectory(
    learnedLine: TerminalCommandLineContext,
    sourceWorkingDirectoryUri: String?,
    targetWorkingDirectoryUri: String?,
): Boolean {
    if (!learnedLine.isRelativeDirectoryChange()) return true
    val source = sourceWorkingDirectoryUri ?: return true
    val target = targetWorkingDirectoryUri ?: return true
    return canonicalizeWorkingDirectoryUri(source) == canonicalizeWorkingDirectoryUri(target)
}

private fun TerminalCommandLineContext.isRelativeDirectoryChange(): Boolean {
    val commandIndex = tokens.firstCommandTokenIndex()
    val command = tokens.getOrNull(commandIndex)?.text?.let(::normalizeTerminalCommandToken) ?: return false
    if (command !in DIRECTORY_CHANGE_COMMANDS) return false
    val argument = tokens.directoryArgument(commandIndex + 1) ?: return false
    return argument.isRelativeDirectoryPath()
}

private fun List<TerminalCommandLineToken>.directoryArgument(startIndex: Int): String? {
    var index = startIndex
    while (index < size) {
        val token = this[index].text
        val normalized = normalizeTerminalCommandToken(token)
        when {
            normalized == "--" -> return getOrNull(index + 1)?.text
            normalized == "-" -> return token
            normalized in DIRECTORY_PATH_OPTIONS -> return getOrNull(index + 1)?.text
            normalized in DIRECTORY_NON_PATH_VALUE_OPTIONS -> index += 2
            normalized.startsWith('-') -> index++
            else -> return token
        }
    }
    return null
}

private fun String.isRelativeDirectoryPath(): Boolean {
    if (isEmpty() || startsWith('/') || startsWith('\\') || startsWith('~')) return false
    if (length >= 2 && this[0].isLetter() && this[1] == ':') return false
    return any { it != '.' && it != '/' && it != '\\' }
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
    if (workingDirectoryUri != null &&
        request.workingDirectoryUri != null &&
        canonicalizeWorkingDirectoryUri(workingDirectoryUri) ==
        canonicalizeWorkingDirectoryUri(request.workingDirectoryUri)
    ) {
        score += WORKING_DIRECTORY_MATCH_SCORE
    }
    return score.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
}

private fun counterScore(
    count: Int,
    scorePerUnit: Int,
): Long = minOf(count, MAX_COUNTER_SCORE_UNITS).toLong() * scorePerUnit.toLong()

private const val SOURCE_LEARNED = "learned"
private const val BASE_SCORE = 620
private const val USE_COUNT_SCORE = 18
private const val SUCCESS_COUNT_SCORE = 10
private const val FAILURE_COUNT_PENALTY = 16
private const val ACCEPTED_COUNT_SCORE = 24
private const val DISMISSED_COUNT_PENALTY = 30
private const val MAX_COUNTER_SCORE_UNITS = 50
private const val PROFILE_MATCH_SCORE = 50
private const val WORKING_DIRECTORY_MATCH_SCORE = 80
private val DIRECTORY_CHANGE_COMMANDS = setOf("cd", "chdir", "pushd", "set-location", "sl")
private val DIRECTORY_PATH_OPTIONS = setOf("-path", "-literalpath")
private val DIRECTORY_NON_PATH_VALUE_OPTIONS = setOf("-stackname")
