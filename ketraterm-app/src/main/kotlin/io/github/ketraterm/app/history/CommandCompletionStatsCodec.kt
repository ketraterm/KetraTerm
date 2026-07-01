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
package io.github.ketraterm.app.history

import io.github.ketraterm.completion.api.TerminalCompletionCandidateKind
import io.github.ketraterm.completion.model.*
import java.nio.charset.StandardCharsets
import java.util.*

/**
 * Pure encoder/decoder for the compact command-completion stats file format.
 *
 * The codec performs no filesystem I/O, scheduling, or privacy filtering. It
 * accepts a complete line sequence and independently skips malformed rows so a
 * damaged persisted entry cannot discard otherwise usable ranking metadata.
 */
internal object CommandCompletionStatsCodec {
    /**
     * Encodes [snapshot] as versioned tab-separated lines.
     *
     * @param snapshot aggregate completion statistics to persist.
     * @return header plus row lines ready to write as UTF-8 text.
     */
    fun encode(snapshot: TerminalCommandCompletionStatsSnapshot): List<String> =
        buildList(1 + snapshot.commandStats.size + snapshot.shapeStats.size + snapshot.feedbackStats.size) {
            add(COMMAND_COMPLETION_STATS_HEADER)
            for (record in snapshot.commandStats) add(encodeCommandRow(record))
            for (record in snapshot.shapeStats) add(encodeShapeRow(record))
            for (record in snapshot.feedbackStats) add(encodeFeedbackRow(record))
        }

    /**
     * Decodes versioned tab-separated lines into a stats snapshot.
     *
     * Unknown headers and malformed rows are ignored. Invalid model values are
     * rejected by the shared completion data classes and therefore skipped.
     *
     * @param lines complete file lines.
     * @return decoded stats snapshot, or an empty snapshot when the header is unknown.
     */
    fun decode(lines: List<String>): TerminalCommandCompletionStatsSnapshot {
        if (lines.firstOrNull() != COMMAND_COMPLETION_STATS_HEADER) {
            return TerminalCommandCompletionStatsSnapshot()
        }
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

    private fun encodeCommandRow(record: TerminalCommandCompletionStats): String =
        listOf(
            ROW_COMMAND,
            encodeText(record.commandLine),
            encodeText(record.normalizedCommandLine),
            encodeText(record.profileId.orEmpty()),
            encodeText(record.workingDirectoryUri.orEmpty()),
            record.useCount.toString(),
            record.successCount.toString(),
            record.failureCount.toString(),
            record.acceptedCount.toString(),
            record.dismissedCount.toString(),
            record.lastUsedEpochMillis.toString(),
        ).joinToString("\t")

    private fun encodeShapeRow(record: TerminalCommandShapeStats): String =
        listOf(
            ROW_SHAPE,
            encodeText(record.shape.executable),
            encodeTextList(record.shape.subcommands),
            encodeTextList(record.shape.optionNames),
            record.shape.positionalArgumentCount.toString(),
            record.shape.optionValueCount.toString(),
            encodeText(record.shape.normalizedShapeKey),
            encodeText(record.profileId.orEmpty()),
            encodeText(record.workingDirectoryUri.orEmpty()),
            record.useCount.toString(),
            record.successCount.toString(),
            record.failureCount.toString(),
            record.acceptedCount.toString(),
            record.dismissedCount.toString(),
            record.lastUsedEpochMillis.toString(),
        ).joinToString("\t")

    private fun encodeFeedbackRow(record: TerminalCompletionFeedbackStats): String =
        listOf(
            ROW_FEEDBACK,
            encodeText(record.source),
            record.candidateKind.name,
            record.tokenPosition.name,
            record.replacementStartOffset.toString(),
            record.replacementEndOffset.toString(),
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
                normalizedCommandLine = decodeText(fields[2]),
                profileId = decodeText(fields[3]).takeIf(String::isNotEmpty),
                workingDirectoryUri = decodeText(fields[4]).takeIf(String::isNotEmpty),
                useCount = fields[5].toInt(),
                successCount = fields[6].toInt(),
                failureCount = fields[7].toInt(),
                acceptedCount = fields[8].toInt(),
                dismissedCount = fields[9].toInt(),
                lastUsedEpochMillis = fields[10].toLong(),
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
                        normalizedShapeKey = decodeText(fields[6]),
                    ),
                profileId = decodeText(fields[7]).takeIf(String::isNotEmpty),
                workingDirectoryUri = decodeText(fields[8]).takeIf(String::isNotEmpty),
                useCount = fields[9].toInt(),
                successCount = fields[10].toInt(),
                failureCount = fields[11].toInt(),
                acceptedCount = fields[12].toInt(),
                dismissedCount = fields[13].toInt(),
                lastUsedEpochMillis = fields[14].toLong(),
            )
        }.getOrNull()
    }

    private fun decodeFeedbackRow(fields: List<String>): TerminalCompletionFeedbackStats? {
        if (fields.size != FEEDBACK_STATS_FIELD_COUNT) return null
        return runCatching {
            TerminalCompletionFeedbackStats(
                source = decodeText(fields[1]),
                candidateKind = TerminalCompletionCandidateKind.valueOf(fields[2]),
                tokenPosition = TerminalCompletionTokenPosition.valueOf(fields[3]),
                replacementStartOffset = fields[4].toInt(),
                replacementEndOffset = fields[5].toInt(),
                profileId = decodeText(fields[6]).takeIf(String::isNotEmpty),
                workingDirectoryUri = decodeText(fields[7]).takeIf(String::isNotEmpty),
                acceptedCount = fields[8].toInt(),
                dismissedCount = fields[9].toInt(),
                lastUsedEpochMillis = fields[10].toLong(),
            )
        }.getOrNull()
    }

    private fun encodeText(value: String): String = encoder.encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decodeText(value: String): String = String(decoder.decode(value), StandardCharsets.UTF_8)

    private fun encodeTextList(values: List<String>): String = values.joinToString(",") { encodeText(it) }

    private fun decodeTextList(value: String): List<String> =
        if (value.isEmpty()) {
            emptyList()
        } else {
            value.split(',').map(::decodeText)
        }

    private const val COMMAND_COMPLETION_STATS_HEADER = "KetraTerm_COMMAND_COMPLETION_STATS\t1"
    private const val COMMAND_STATS_FIELD_COUNT = 11
    private const val SHAPE_STATS_FIELD_COUNT = 15
    private const val FEEDBACK_STATS_FIELD_COUNT = 11
    private const val ROW_COMMAND = "C"
    private const val ROW_SHAPE = "S"
    private const val ROW_FEEDBACK = "F"
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()
}
