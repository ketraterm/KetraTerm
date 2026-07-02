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
package io.github.ketraterm.completion.internal

import io.github.ketraterm.completion.api.TerminalCompletionCandidate
import io.github.ketraterm.completion.api.TerminalCompletionRequest
import io.github.ketraterm.completion.model.TerminalCommandCompletionStats
import io.github.ketraterm.completion.model.TerminalCommandShapeStats
import io.github.ketraterm.completion.model.TerminalCompletionFeedbackStats

internal val TERMINAL_COMPLETION_CANDIDATE_ORDER: Comparator<TerminalCompletionCandidate> =
    compareByDescending<TerminalCompletionCandidate> { it.score }
        .thenBy { it.displayText }
        .thenBy { it.replacementText }

internal val TERMINAL_COMMAND_COMPLETION_STATS_ORDER: Comparator<TerminalCommandCompletionStats> =
    compareByDescending<TerminalCommandCompletionStats> { it.lastUsedEpochMillis }
        .thenByDescending { it.acceptedCount }
        .thenByDescending { it.successCount }
        .thenBy { it.dismissedCount }
        .thenBy { it.commandLine }

internal val TERMINAL_COMMAND_SHAPE_STATS_ORDER: Comparator<TerminalCommandShapeStats> =
    compareByDescending<TerminalCommandShapeStats> { it.lastUsedEpochMillis }
        .thenByDescending { it.acceptedCount }
        .thenByDescending { it.successCount }
        .thenBy { it.dismissedCount }
        .thenBy { it.shape.normalizedShapeKey }

internal val TERMINAL_COMPLETION_FEEDBACK_STATS_ORDER: Comparator<TerminalCompletionFeedbackStats> =
    compareByDescending<TerminalCompletionFeedbackStats> { it.lastUsedEpochMillis }
        .thenByDescending { it.acceptedCount }
        .thenBy { it.dismissedCount }
        .thenBy { it.source }
        .thenBy { it.candidateKind.name }
        .thenBy { it.tokenPosition.name }

internal fun isRecordableTerminalCompletionCommand(commandLine: String): Boolean =
    commandLine.isNotBlank() && !commandLine.hasTerminalCompletionLineBreak()

internal fun normalizeTerminalCommandLine(commandLine: String): String = commandLine.trim().lowercase()

internal fun String.hasTerminalCompletionLineBreak(): Boolean = indexOf('\n') >= 0 || indexOf('\r') >= 0

/**
 * Returns whether [offset] is a valid UTF-16 scalar boundary in this string.
 */
internal fun String.isTerminalCompletionUtf16Boundary(offset: Int): Boolean {
    if (offset !in 0..length) return false
    val afterHighSurrogate = offset > 0 && Character.isHighSurrogate(this[offset - 1])
    val beforeLowSurrogate = offset < length && Character.isLowSurrogate(this[offset])
    return !afterHighSurrogate && !beforeLowSurrogate
}

/**
 * Projects [candidate] onto this request command line when its replacement
 * range is contained in the command line and does not split a surrogate pair.
 */
internal fun TerminalCompletionRequest.commandLineAfterCandidate(candidate: TerminalCompletionCandidate): String? {
    val startOffset = candidate.replacementStartOffset
    val endOffset = candidate.replacementEndOffset
    if (startOffset > commandLine.length) return null
    if (endOffset > commandLine.length) return null
    if (!commandLine.isTerminalCompletionUtf16Boundary(startOffset)) return null
    if (!commandLine.isTerminalCompletionUtf16Boundary(endOffset)) return null
    return commandLine.replaceRange(startOffset, endOffset, candidate.replacementText)
}

internal fun saturatedCompletionCounterIncrement(value: Int): Int = if (value == Int.MAX_VALUE) value else value + 1

internal fun isRelativeCdCommand(commandLine: String): Boolean {
    val tokens =
        try {
            io.github.ketraterm.completion.commandline.TerminalCommandLineTokenizer
                .parse(commandLine, commandLine.length)
                .tokens
        } catch (_: Exception) {
            return false
        }
    if (tokens.isEmpty()) return false
    val first = tokens[0].text.lowercase()
    if (first != "cd" && first != "chdir" && first != "pushd" && first != "set-location" && first != "sl") {
        return false
    }
    if (tokens.size < 2) return false
    val arg = tokens[1].text
    if (arg.isEmpty()) return false
    if (arg.startsWith("/") || arg.startsWith("\\") || arg.startsWith("~")) {
        return false
    }
    if (arg.length >= 2 && arg[0].isLetter() && arg[1] == ':') {
        return false
    }
    if (isPureTraversalPath(arg)) {
        return false
    }
    return true
}

private fun isPureTraversalPath(path: String): Boolean {
    for (i in path.indices) {
        val ch = path[i]
        if (ch != '.' && ch != '/' && ch != '\\') {
            return false
        }
    }
    return true
}

internal fun canonicalizeWorkingDirectoryUri(uri: String): String {
    val trimmed = uri.trim()
    return if (trimmed.endsWith("/")) trimmed else "$trimmed/"
}
