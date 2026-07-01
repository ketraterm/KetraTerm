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
 * Bounded exact-command stats index.
 *
 * This class owns exact command row compaction, mutation, sorting, and capacity
 * eviction. Thread-safety belongs to the public source that contains the index
 * so multi-index snapshots can stay coherent.
 */
internal class CommandCompletionStatsIndex(
    private val capacity: Int,
) {
    init {
        require(capacity > 0) { "capacity must be > 0, was $capacity" }
    }

    private val entries = ArrayList<TerminalCommandCompletionStats>(capacity)

    fun replaceAll(records: List<TerminalCommandCompletionStats>) {
        val compacted = ArrayList<TerminalCommandCompletionStats>(minOf(records.size, capacity))
        for (record in records) {
            val index = compacted.indexOfKey(record)
            if (index >= 0) {
                if (record.lastUsedEpochMillis >= compacted[index].lastUsedEpochMillis) {
                    compacted[index] = record
                }
            } else {
                compacted += record
            }
        }
        compacted.sortWith(TERMINAL_COMMAND_COMPLETION_STATS_ORDER)
        entries.clear()
        entries.addAll(compacted.take(capacity))
    }

    fun snapshot(): List<TerminalCommandCompletionStats> = entries.toList()

    fun recordCommandResult(
        commandLine: String,
        successful: Boolean,
        profileId: String?,
        workingDirectoryUri: String?,
        usedAtEpochMillis: Long,
    ) {
        if (!isRecordableStatsEvent(commandLine, usedAtEpochMillis)) return
        mutate(commandLine, profileId, workingDirectoryUri) { previous, canonical ->
            previous.copy(
                commandLine = canonical,
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
        mutate(commandLine, profileId, workingDirectoryUri) { previous, canonical ->
            previous.copy(
                commandLine = canonical,
                acceptedCount = incrementAccepted(previous.acceptedCount, feedback),
                dismissedCount = incrementDismissed(previous.dismissedCount, feedback),
                lastUsedEpochMillis = maxOf(previous.lastUsedEpochMillis, feedbackAtEpochMillis),
            )
        }
    }

    private inline fun mutate(
        commandLine: String,
        profileId: String?,
        workingDirectoryUri: String?,
        update: (TerminalCommandCompletionStats, String) -> TerminalCommandCompletionStats,
    ) {
        val canonical = commandLine.trim()
        val normalized = TerminalCommandCompletionStats.normalizeCommandLine(canonical)
        val existingIndex = entries.indexOfKey(normalized, profileId, workingDirectoryUri)
        if (existingIndex >= 0) {
            entries[existingIndex] = update(entries[existingIndex], canonical)
        } else {
            if (entries.size == capacity) entries.removeLeastRelevantBy(TERMINAL_COMMAND_COMPLETION_STATS_ORDER)
            val initial =
                TerminalCommandCompletionStats(
                    commandLine = canonical,
                    normalizedCommandLine = normalized,
                    profileId = profileId,
                    workingDirectoryUri = workingDirectoryUri,
                )
            entries += update(initial, canonical)
        }
        entries.sortWith(TERMINAL_COMMAND_COMPLETION_STATS_ORDER)
    }

    private fun ArrayList<TerminalCommandCompletionStats>.indexOfKey(record: TerminalCommandCompletionStats): Int =
        indexOfKey(record.normalizedCommandLine, record.profileId, record.workingDirectoryUri)

    private fun List<TerminalCommandCompletionStats>.indexOfKey(
        normalizedCommandLine: String,
        profileId: String?,
        workingDirectoryUri: String?,
    ): Int {
        var index = 0
        while (index < size) {
            val entry = this[index]
            if (entry.normalizedCommandLine == normalizedCommandLine &&
                entry.profileId == profileId &&
                entry.workingDirectoryUri == workingDirectoryUri
            ) {
                return index
            }
            index++
        }
        return -1
    }
}

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
    init {
        require(capacity > 0) { "capacity must be > 0, was $capacity" }
    }

    private val entries = ArrayList<TerminalCommandShapeStats>(capacity)
    private val commandSpecs = commandSpecs.toList()

    fun replaceAll(records: List<TerminalCommandShapeStats>) {
        val compacted = ArrayList<TerminalCommandShapeStats>(minOf(records.size, capacity))
        for (record in records) {
            val index = compacted.indexOfShapeKey(record)
            if (index >= 0) {
                if (record.lastUsedEpochMillis >= compacted[index].lastUsedEpochMillis) {
                    compacted[index] = record
                }
            } else {
                compacted += record
            }
        }
        compacted.sortWith(TERMINAL_COMMAND_SHAPE_STATS_ORDER)
        entries.clear()
        entries.addAll(compacted.take(capacity))
    }

    fun snapshot(): List<TerminalCommandShapeStats> = entries.toList()

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

    private inline fun mutate(
        commandLine: String,
        profileId: String?,
        workingDirectoryUri: String?,
        update: (TerminalCommandShapeStats) -> TerminalCommandShapeStats,
    ) {
        val shape = shapeFor(commandLine) ?: return
        val existingIndex = entries.indexOfShapeKey(shape.normalizedShapeKey, profileId, workingDirectoryUri)
        if (existingIndex >= 0) {
            entries[existingIndex] = update(entries[existingIndex])
        } else {
            if (entries.size == capacity) entries.removeLeastRelevantBy(TERMINAL_COMMAND_SHAPE_STATS_ORDER)
            val initial =
                TerminalCommandShapeStats(
                    shape = shape,
                    profileId = profileId,
                    workingDirectoryUri = workingDirectoryUri,
                )
            entries += update(initial)
        }
        entries.sortWith(TERMINAL_COMMAND_SHAPE_STATS_ORDER)
    }

    private fun ArrayList<TerminalCommandShapeStats>.indexOfShapeKey(record: TerminalCommandShapeStats): Int =
        indexOfShapeKey(record.shape.normalizedShapeKey, record.profileId, record.workingDirectoryUri)

    private fun List<TerminalCommandShapeStats>.indexOfShapeKey(
        normalizedShapeKey: String,
        profileId: String?,
        workingDirectoryUri: String?,
    ): Int {
        var index = 0
        while (index < size) {
            val entry = this[index]
            if (entry.shape.normalizedShapeKey == normalizedShapeKey &&
                entry.profileId == profileId &&
                entry.workingDirectoryUri == workingDirectoryUri
            ) {
                return index
            }
            index++
        }
        return -1
    }

    private fun shapeFor(commandLine: String): TerminalCommandLineShape? =
        TerminalCommandLineClassifier
            .classify(commandLine, commandSpecs)
            ?.shape
}

/**
 * Bounded source-specific feedback stats index.
 *
 * Rows are keyed by displayed-candidate metadata rather than command text so
 * path, spec, IDE, and history providers can learn independently.
 */
internal class CompletionFeedbackStatsIndex(
    private val capacity: Int,
) {
    init {
        require(capacity > 0) { "capacity must be > 0, was $capacity" }
    }

    private val entries = ArrayList<TerminalCompletionFeedbackStats>(capacity)

    fun replaceAll(records: List<TerminalCompletionFeedbackStats>) {
        val compacted = ArrayList<TerminalCompletionFeedbackStats>(minOf(records.size, capacity))
        for (record in records) {
            val index = compacted.indexOfFeedbackKey(record)
            if (index >= 0) {
                if (record.lastUsedEpochMillis >= compacted[index].lastUsedEpochMillis) {
                    compacted[index] = record
                }
            } else {
                compacted += record
            }
        }
        compacted.sortWith(TERMINAL_COMPLETION_FEEDBACK_STATS_ORDER)
        entries.clear()
        entries.addAll(compacted.take(capacity))
    }

    fun snapshot(): List<TerminalCompletionFeedbackStats> = entries.toList()

    fun recordSuggestionFeedback(
        context: TerminalCompletionFeedbackContext,
        feedback: TerminalCompletionFeedbackKind,
        profileId: String?,
        workingDirectoryUri: String?,
        feedbackAtEpochMillis: Long,
    ) {
        if (feedbackAtEpochMillis < 0L) return
        mutate(context, profileId, workingDirectoryUri) { previous ->
            previous.copy(
                acceptedCount = incrementAccepted(previous.acceptedCount, feedback),
                dismissedCount = incrementDismissed(previous.dismissedCount, feedback),
                lastUsedEpochMillis = maxOf(previous.lastUsedEpochMillis, feedbackAtEpochMillis),
            )
        }
    }

    private inline fun mutate(
        context: TerminalCompletionFeedbackContext,
        profileId: String?,
        workingDirectoryUri: String?,
        update: (TerminalCompletionFeedbackStats) -> TerminalCompletionFeedbackStats,
    ) {
        val existingIndex = entries.indexOfFeedbackKey(context, profileId, workingDirectoryUri)
        if (existingIndex >= 0) {
            entries[existingIndex] = update(entries[existingIndex])
        } else {
            if (entries.size == capacity) {
                entries.removeLeastRelevantBy(TERMINAL_COMPLETION_FEEDBACK_STATS_ORDER)
            }
            val initial =
                TerminalCompletionFeedbackStats(
                    source = context.source,
                    candidateKind = context.candidateKind,
                    tokenPosition = context.tokenPosition,
                    replacementStartOffset = context.replacementStartOffset,
                    replacementEndOffset = context.replacementEndOffset,
                    profileId = profileId,
                    workingDirectoryUri = workingDirectoryUri,
                )
            entries += update(initial)
        }
        entries.sortWith(TERMINAL_COMPLETION_FEEDBACK_STATS_ORDER)
    }

    private fun ArrayList<TerminalCompletionFeedbackStats>.indexOfFeedbackKey(record: TerminalCompletionFeedbackStats): Int =
        indexOfFeedbackKey(
            source = record.source,
            candidateKind = record.candidateKind,
            tokenPosition = record.tokenPosition,
            replacementStartOffset = record.replacementStartOffset,
            replacementEndOffset = record.replacementEndOffset,
            profileId = record.profileId,
            workingDirectoryUri = record.workingDirectoryUri,
        )

    private fun List<TerminalCompletionFeedbackStats>.indexOfFeedbackKey(
        context: TerminalCompletionFeedbackContext,
        profileId: String?,
        workingDirectoryUri: String?,
    ): Int =
        indexOfFeedbackKey(
            source = context.source,
            candidateKind = context.candidateKind,
            tokenPosition = context.tokenPosition,
            replacementStartOffset = context.replacementStartOffset,
            replacementEndOffset = context.replacementEndOffset,
            profileId = profileId,
            workingDirectoryUri = workingDirectoryUri,
        )

    private fun List<TerminalCompletionFeedbackStats>.indexOfFeedbackKey(
        source: String,
        candidateKind: TerminalCompletionCandidateKind,
        tokenPosition: TerminalCompletionTokenPosition,
        replacementStartOffset: Int,
        replacementEndOffset: Int,
        profileId: String?,
        workingDirectoryUri: String?,
    ): Int {
        var index = 0
        while (index < size) {
            val entry = this[index]
            if (entry.source == source &&
                entry.candidateKind == candidateKind &&
                entry.tokenPosition == tokenPosition &&
                entry.replacementStartOffset == replacementStartOffset &&
                entry.replacementEndOffset == replacementEndOffset &&
                entry.profileId == profileId &&
                entry.workingDirectoryUri == workingDirectoryUri
            ) {
                return index
            }
            index++
        }
        return -1
    }
}

private fun isRecordableStatsEvent(
    commandLine: String,
    eventAtEpochMillis: Long,
): Boolean = eventAtEpochMillis >= 0L && isRecordableTerminalCompletionCommand(commandLine)

private fun incrementAccepted(
    value: Int,
    feedback: TerminalCompletionFeedbackKind,
): Int =
    if (feedback == TerminalCompletionFeedbackKind.ACCEPTED) {
        saturatedCompletionCounterIncrement(value)
    } else {
        value
    }

private fun incrementDismissed(
    value: Int,
    feedback: TerminalCompletionFeedbackKind,
): Int =
    if (feedback == TerminalCompletionFeedbackKind.DISMISSED) {
        saturatedCompletionCounterIncrement(value)
    } else {
        value
    }
