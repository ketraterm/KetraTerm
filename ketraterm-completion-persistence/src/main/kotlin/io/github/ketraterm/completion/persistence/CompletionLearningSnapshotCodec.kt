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

import io.github.ketraterm.completion.model.TerminalCommandCompletionStats
import io.github.ketraterm.completion.model.TerminalCommandCompletionStatsSnapshot
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.*

/** Strict internal line codec for exact-command completion-learning statistics. */
internal object CompletionLearningSnapshotCodec {
    fun currentFileName(): String = FILE_NAME

    fun encode(snapshot: TerminalCommandCompletionStatsSnapshot): List<String> =
        buildList(1 + snapshot.commandStats.size) {
            add(HEADER)
            for (record in snapshot.commandStats) add(encodeCommandRow(record))
        }

    fun decode(lines: List<String>): TerminalCommandCompletionStatsSnapshot? {
        if (lines.firstOrNull() != HEADER) return null

        val commandRecords = ArrayList<TerminalCommandCompletionStats>(lines.size - 1)
        var index = 1
        while (index < lines.size) {
            val fields = lines[index].split('\t')
            if (fields.firstOrNull() != ROW_COMMAND) return null
            commandRecords += decodeCommandRow(fields) ?: return null
            index++
        }
        return TerminalCommandCompletionStatsSnapshot(commandStats = commandRecords)
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

    private fun decodeCommandRow(fields: List<String>): TerminalCommandCompletionStats? {
        if (fields.size != COMMAND_STATS_FIELD_COUNT) return null
        return try {
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
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: CharacterCodingException) {
            null
        }
    }

    private fun encodeText(value: String): String {
        val utf8 =
            StandardCharsets.UTF_8
                .newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(value))
        val bytes = ByteArray(utf8.remaining())
        utf8.get(bytes)
        return encoder.encodeToString(bytes)
    }

    private fun decodeText(value: String): String =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(decoder.decode(value)))
            .toString()

    private const val FORMAT_VERSION = 2
    private const val FILE_NAME = "command-completion-stats-v$FORMAT_VERSION.tsv"
    private const val HEADER = "KetraTerm_COMMAND_COMPLETION_STATS\t$FORMAT_VERSION"
    private const val COMMAND_STATS_FIELD_COUNT = 10
    private const val ROW_COMMAND = "C"
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()
}
