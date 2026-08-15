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
package io.github.ketraterm.completion.persistence

import io.github.ketraterm.completion.api.TerminalCompletionCandidateKind
import io.github.ketraterm.completion.model.*
import java.nio.charset.StandardCharsets
import java.util.*

/** Internal line codec for the bounded local completion-learning file. */
internal object CompletionLearningSnapshotCodec {
    fun currentFileName(): String = FILE_NAME

    fun encode(snapshot: TerminalCommandCompletionStatsSnapshot): List<String> =
        buildList(1 + snapshot.commandStats.size + snapshot.shapeStats.size + snapshot.feedbackStats.size) {
            add(HEADER)
            for (record in snapshot.commandStats) add(encodeCommandRow(record))
            for (record in snapshot.shapeStats) add(encodeShapeRow(record))
            for (record in snapshot.feedbackStats) add(encodeFeedbackRow(record))
        }

    fun decode(lines: List<String>): TerminalCommandCompletionStatsSnapshot {
        if (lines.firstOrNull() != HEADER) return TerminalCommandCompletionStatsSnapshot()

        val commandRecords = ArrayList<TerminalCommandCompletionStats>(lines.size - 1)
        val shapeRecords = ArrayList<TerminalCommandShapeStats>(lines.size - 1)
        val feedbackRecords = ArrayList<TerminalCompletionFeedbackStats>(lines.size - 1)
        var index = 1
        while (index < lines.size) {
            val fields = lines[index].split('\t')
            when (fields.firstOrNull()) {
                ROW_COMMAND -> decodeCommandRow(fields)?.let(commandRecords::add)
                ROW_SHAPE -> decodeShapeRow(fields)?.let(shapeRecords::add)
                ROW_FEEDBACK -> decodeFeedbackRow(fields)?.let(feedbackRecords::add)
            }
            index++
        }
        return TerminalCommandCompletionStatsSnapshot(
            commandStats = commandRecords,
            shapeStats = shapeRecords,
            feedbackStats = feedbackRecords,
        )
    }

    fun encodeCommandRow(record: TerminalCommandCompletionStats): String =
        listOf(
            ROW_COMMAND,
            encodeText(record.commandLine),
            encodeText(record.profileId.orEmpty()),
            encodeText(record.workingDirectoryUri.orEmpty()),
            record.useCount.toString(),
            record.successCount.toString(),
            record.failureCount.toString(),
            record.acceptedCount.toString(),
            record.dismissedCount.toString(),
            record.lastUsedEpochMillis.toString(),
        ).joinToString("\t")

    fun encodeShapeRow(record: TerminalCommandShapeStats): String =
        listOf(
            ROW_SHAPE,
            encodeText(record.shape.executable),
            encodeTextList(record.shape.subcommands),
            encodeTextList(record.shape.optionNames),
            record.shape.positionalArgumentCount.toString(),
            record.shape.optionValueCount.toString(),
            encodeText(record.profileId.orEmpty()),
            encodeText(record.workingDirectoryUri.orEmpty()),
            record.useCount.toString(),
            record.successCount.toString(),
            record.failureCount.toString(),
            record.acceptedCount.toString(),
            record.dismissedCount.toString(),
            record.lastUsedEpochMillis.toString(),
        ).joinToString("\t")

    fun encodeFeedbackRow(record: TerminalCompletionFeedbackStats): String =
        listOf(
            ROW_FEEDBACK,
            encodeText(record.source),
            record.candidateKind.name,
            encodeText(record.profileId.orEmpty()),
            encodeText(record.workingDirectoryUri.orEmpty()),
            record.acceptedCount.toString(),
            record.dismissedCount.toString(),
            record.lastUsedEpochMillis.toString(),
        ).joinToString("\t")

    private fun decodeCommandRow(fields: List<String>): TerminalCommandCompletionStats? {
        if (fields.size != COMMAND_STATS_FIELD_COUNT) return null
        return runCatching {
            TerminalCommandCompletionStats(
                commandLine = decodeText(fields[1]),
                profileId = decodeText(fields[2]).takeIf(String::isNotEmpty),
                workingDirectoryUri = decodeText(fields[3]).takeIf(String::isNotEmpty),
                useCount = fields[4].toInt(),
                successCount = fields[5].toInt(),
                failureCount = fields[6].toInt(),
                acceptedCount = fields[7].toInt(),
                dismissedCount = fields[8].toInt(),
                lastUsedEpochMillis = fields[9].toLong(),
            )
        }.getOrNull()
    }

    private fun decodeShapeRow(fields: List<String>): TerminalCommandShapeStats? {
        if (fields.size != SHAPE_STATS_FIELD_COUNT) return null
        return runCatching {
            TerminalCommandShapeStats(
                shape =
                    TerminalCommandLineShape(
                        executable = decodeText(fields[1]),
                        subcommands = decodeTextList(fields[2]),
                        optionNames = decodeTextList(fields[3]),
                        positionalArgumentCount = fields[4].toInt(),
                        optionValueCount = fields[5].toInt(),
                    ),
                profileId = decodeText(fields[6]).takeIf(String::isNotEmpty),
                workingDirectoryUri = decodeText(fields[7]).takeIf(String::isNotEmpty),
                useCount = fields[8].toInt(),
                successCount = fields[9].toInt(),
                failureCount = fields[10].toInt(),
                acceptedCount = fields[11].toInt(),
                dismissedCount = fields[12].toInt(),
                lastUsedEpochMillis = fields[13].toLong(),
            )
        }.getOrNull()
    }

    private fun decodeFeedbackRow(fields: List<String>): TerminalCompletionFeedbackStats? =
        when (fields.size) {
            FEEDBACK_STATS_FIELD_COUNT -> decodeFeedbackRow(fields, profileIndex = 3)
            LEGACY_FEEDBACK_STATS_FIELD_COUNT -> decodeFeedbackRow(fields, profileIndex = 4)
            else -> null
        }

    private fun decodeFeedbackRow(
        fields: List<String>,
        profileIndex: Int,
    ): TerminalCompletionFeedbackStats? =
        runCatching {
            TerminalCompletionFeedbackStats(
                source = decodeText(fields[1]),
                candidateKind = TerminalCompletionCandidateKind.valueOf(fields[2]),
                profileId = decodeText(fields[profileIndex]).takeIf(String::isNotEmpty),
                workingDirectoryUri = decodeText(fields[profileIndex + 1]).takeIf(String::isNotEmpty),
                acceptedCount = fields[profileIndex + 2].toInt(),
                dismissedCount = fields[profileIndex + 3].toInt(),
                lastUsedEpochMillis = fields[profileIndex + 4].toLong(),
            )
        }.getOrNull()

    private fun encodeText(value: String): String = encoder.encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decodeText(value: String): String = String(decoder.decode(value), StandardCharsets.UTF_8)

    private fun encodeTextList(values: List<String>): String = values.joinToString(",") { encodeText(it) }

    private fun decodeTextList(value: String): List<String> = if (value.isEmpty()) emptyList() else value.split(',').map(::decodeText)

    private const val FORMAT_VERSION = 1
    private const val FILE_NAME = "command-completion-stats-v$FORMAT_VERSION.tsv"
    private const val HEADER = "KetraTerm_COMMAND_COMPLETION_STATS\t$FORMAT_VERSION"
    private const val COMMAND_STATS_FIELD_COUNT = 10
    private const val SHAPE_STATS_FIELD_COUNT = 14
    private const val FEEDBACK_STATS_FIELD_COUNT = 8
    private const val LEGACY_FEEDBACK_STATS_FIELD_COUNT = 9
    private const val ROW_COMMAND = "C"
    private const val ROW_SHAPE = "S"
    private const val ROW_FEEDBACK = "F"
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()
}
