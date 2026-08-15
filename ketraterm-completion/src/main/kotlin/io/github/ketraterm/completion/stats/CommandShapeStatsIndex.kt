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

import io.github.ketraterm.completion.commandline.TerminalCommandLineClassifier
import io.github.ketraterm.completion.internal.BoundedStatsRowIndex
import io.github.ketraterm.completion.internal.CompletionLearningContextKey
import io.github.ketraterm.completion.internal.TERMINAL_COMMAND_SHAPE_STATS_ORDER
import io.github.ketraterm.completion.internal.saturatedCompletionCounterIncrement
import io.github.ketraterm.completion.model.TerminalCommandLineShape
import io.github.ketraterm.completion.model.TerminalCommandShapeStats
import io.github.ketraterm.completion.model.TerminalCommandSpec
import io.github.ketraterm.completion.model.TerminalCompletionFeedbackKind

/**
 * Bounded privacy-preserving command-shape stats index.
 *
 * The index stores structural shapes only; raw positional arguments never
 * become part of shape keys or rows.
 */
internal class CommandShapeStatsIndex(
    private val capacity: Int,
    commandSpecs: List<TerminalCommandSpec>,
) {
    private var rows =
        BoundedStatsRowIndex(
            capacity = capacity,
            order = TERMINAL_COMMAND_SHAPE_STATS_ORDER,
            keySelector = { it.key() },
            shouldReplace = { current, candidate -> candidate.lastUsedEpochMillis >= current.lastUsedEpochMillis },
        )
    private val commandSpecs = commandSpecs.toList()

    fun replaceAll(records: List<TerminalCommandShapeStats>) = rows.replaceAll(records.map(::canonicalizeContext))

    fun mergeAll(records: List<TerminalCommandShapeStats>) = rows.mergeAll(records.map(::canonicalizeContext), ::mergeStats)

    fun snapshot(): List<TerminalCommandShapeStats> = rows.snapshot()

    fun copy(): CommandShapeStatsIndex = CommandShapeStatsIndex(capacity, commandSpecs).also { copy -> copy.rows = rows.copy() }

    fun recordCommandResult(
        commandLine: String,
        successful: Boolean,
        profileId: String?,
        workingDirectoryUri: String?,
        usedAtEpochMillis: Long,
    ) {
        if (!isRecordableStatsEvent(commandLine, usedAtEpochMillis)) return
        mutate(commandLine, profileId, workingDirectoryUri) { previous ->
            previous.copy(
                useCount = saturatedCompletionCounterIncrement(previous.useCount),
                successCount =
                    if (successful) {
                        saturatedCompletionCounterIncrement(previous.successCount)
                    } else {
                        previous.successCount
                    },
                failureCount =
                    if (successful) {
                        previous.failureCount
                    } else {
                        saturatedCompletionCounterIncrement(previous.failureCount)
                    },
                lastUsedEpochMillis = maxOf(previous.lastUsedEpochMillis, usedAtEpochMillis),
            )
        }
    }

    fun recordSuggestionFeedback(
        commandLine: String,
        feedback: TerminalCompletionFeedbackKind,
        profileId: String?,
        workingDirectoryUri: String?,
        feedbackAtEpochMillis: Long,
    ) {
        if (!isRecordableStatsEvent(commandLine, feedbackAtEpochMillis)) return
        mutate(commandLine, profileId, workingDirectoryUri) { previous ->
            previous.copy(
                acceptedCount = incrementAccepted(previous.acceptedCount, feedback),
                dismissedCount = incrementDismissed(previous.dismissedCount, feedback),
                lastUsedEpochMillis = maxOf(previous.lastUsedEpochMillis, feedbackAtEpochMillis),
            )
        }
    }

    private fun mutate(
        commandLine: String,
        profileId: String?,
        workingDirectoryUri: String?,
        update: (TerminalCommandShapeStats) -> TerminalCommandShapeStats,
    ) {
        val shape = shapeFor(commandLine) ?: return
        val context = CompletionLearningContextKey.of(profileId, workingDirectoryUri)
        rows.mutate(
            key = CommandShapeStatsKey(shape.normalizedShapeKey, context),
            initialRow = {
                TerminalCommandShapeStats(
                    shape = shape,
                    profileId = context.profileId,
                    workingDirectoryUri = context.workingDirectoryUri,
                )
            },
            update = update,
        )
    }

    private fun shapeFor(commandLine: String): TerminalCommandLineShape? =
        TerminalCommandLineClassifier
            .classify(commandLine, commandSpecs)
            ?.shape

    private data class CommandShapeStatsKey(
        val normalizedShapeKey: String,
        val context: CompletionLearningContextKey,
    )

    private fun TerminalCommandShapeStats.key(): CommandShapeStatsKey =
        CommandShapeStatsKey(
            normalizedShapeKey = shape.normalizedShapeKey,
            context = CompletionLearningContextKey.of(profileId, workingDirectoryUri),
        )

    private fun canonicalizeContext(record: TerminalCommandShapeStats): TerminalCommandShapeStats {
        val context = CompletionLearningContextKey.of(record.profileId, record.workingDirectoryUri)
        return record.copy(
            profileId = context.profileId,
            workingDirectoryUri = context.workingDirectoryUri,
        )
    }

    private fun mergeStats(
        current: TerminalCommandShapeStats,
        incoming: TerminalCommandShapeStats,
    ): TerminalCommandShapeStats {
        val newest = if (incoming.lastUsedEpochMillis >= current.lastUsedEpochMillis) incoming else current
        val context = CompletionLearningContextKey.of(current.profileId, current.workingDirectoryUri)
        return newest.copy(
            profileId = context.profileId,
            workingDirectoryUri = context.workingDirectoryUri,
            useCount = saturatedCounterSum(current.useCount, incoming.useCount),
            successCount = saturatedCounterSum(current.successCount, incoming.successCount),
            failureCount = saturatedCounterSum(current.failureCount, incoming.failureCount),
            acceptedCount = saturatedCounterSum(current.acceptedCount, incoming.acceptedCount),
            dismissedCount = saturatedCounterSum(current.dismissedCount, incoming.dismissedCount),
            lastUsedEpochMillis = maxOf(current.lastUsedEpochMillis, incoming.lastUsedEpochMillis),
        )
    }
}
