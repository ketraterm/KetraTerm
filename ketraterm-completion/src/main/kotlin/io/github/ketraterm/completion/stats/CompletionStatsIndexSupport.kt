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
package io.github.ketraterm.completion.stats

import io.github.ketraterm.completion.internal.isRecordableTerminalCompletionCommand
import io.github.ketraterm.completion.internal.saturatedCompletionCounterIncrement
import io.github.ketraterm.completion.model.TerminalCompletionFeedbackKind

internal fun isRecordableStatsEvent(
    commandLine: String,
    eventAtEpochMillis: Long,
): Boolean = eventAtEpochMillis >= 0L && isRecordableTerminalCompletionCommand(commandLine)

internal fun incrementAccepted(
    value: Int,
    feedback: TerminalCompletionFeedbackKind,
): Int =
    if (feedback == TerminalCompletionFeedbackKind.ACCEPTED) {
        saturatedCompletionCounterIncrement(value)
    } else {
        value
    }

internal fun incrementDismissed(
    value: Int,
    feedback: TerminalCompletionFeedbackKind,
): Int =
    if (feedback == TerminalCompletionFeedbackKind.DISMISSED) {
        saturatedCompletionCounterIncrement(value)
    } else {
        value
    }
