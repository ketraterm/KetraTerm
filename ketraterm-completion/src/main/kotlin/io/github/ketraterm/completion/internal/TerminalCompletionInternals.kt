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

internal fun <T> MutableList<T>.removeLeastRelevantBy(order: Comparator<T>) {
    if (isEmpty()) return
    var removeIndex = 0
    var index = 1
    while (index < size) {
        if (order.compare(this[index], this[removeIndex]) > 0) removeIndex = index
        index++
    }
    removeAt(removeIndex)
}
