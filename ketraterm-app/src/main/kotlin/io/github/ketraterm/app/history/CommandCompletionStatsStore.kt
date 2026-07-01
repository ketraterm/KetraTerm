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

import io.github.ketraterm.completion.*
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Versioned persistence for compact command-completion statistics.
 *
 * The store serializes aggregate ranking metadata, not raw repeated shell
 * history. Loading is synchronous during app startup so completion can warm its
 * in-memory index deterministically; writes run on one daemon worker so command
 * lifecycle events and Swing input never perform disk I/O. Loaded and persisted
 * rows are filtered through [CommandPersistencePrivacyPolicy].
 *
 * @property path destination file for the versioned tab-separated stats index.
 */
internal class CommandCompletionStatsStore(
    private val path: Path,
) : AutoCloseable {
    private val worker =
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "ketraterm-command-completion-stats").apply { isDaemon = true }
        }

    /**
     * Loads all valid exact command stats rows from disk.
     *
     * Malformed rows are ignored independently so one damaged line does not
     * discard the complete index.
     *
     * @return decoded exact command-completion stats in persisted order.
     */
    fun load(): List<TerminalCommandCompletionStats> = loadSnapshot().commandStats

    /**
     * Loads exact command and structural shape stats from disk.
     *
     * Version 1 files are exact-command only and are upgraded in memory by
     * returning an empty shape list. Version 2 files contain typed exact command
     * and shape rows. Version 3 also contains source-specific feedback rows.
     *
     * @return decoded completion stats snapshot.
     */
    fun loadSnapshot(): TerminalCommandCompletionStatsSnapshot {
        if (!Files.isRegularFile(path)) return TerminalCommandCompletionStatsSnapshot()
        return runCatching {
            val lines = Files.readAllLines(path, StandardCharsets.UTF_8)
            when (lines.firstOrNull()) {
                COMMAND_COMPLETION_STATS_HEADER_V1 -> loadVersionOne(lines)
                COMMAND_COMPLETION_STATS_HEADER_V2 -> loadVersionTwo(lines)
                COMMAND_COMPLETION_STATS_HEADER_V3 -> loadVersionThree(lines)
                else -> TerminalCommandCompletionStatsSnapshot()
            }
        }.onFailure { exception ->
            System.err.println("Failed to load command completion stats from $path: ${exception.message}")
        }.getOrElse { TerminalCommandCompletionStatsSnapshot() }
    }

    /**
     * Queues a full snapshot replacement for exact command stats only.
     *
     * @param records compact exact command rows produced by the shared completion index.
     */
    fun persist(records: List<TerminalCommandCompletionStats>) {
        persist(TerminalCommandCompletionStatsSnapshot(commandStats = records))
    }

    /**
     * Queues a full snapshot replacement.
     *
     * @param snapshot compact exact and shape stats produced by the shared completion index.
     */
    fun persist(snapshot: TerminalCommandCompletionStatsSnapshot) {
        val stableSnapshot = sanitizeSnapshot(snapshot)
        worker.execute {
            persistSnapshot(stableSnapshot)
        }
    }

    private fun loadVersionOne(lines: List<String>): TerminalCommandCompletionStatsSnapshot {
        val records = ArrayList<TerminalCommandCompletionStats>(lines.size - 1)
        var index = 1
        while (index < lines.size) {
            decodeCommandV1(lines[index])?.let(records::add)
            index++
        }
        return sanitizeSnapshot(TerminalCommandCompletionStatsSnapshot(commandStats = records))
    }

    private fun loadVersionTwo(lines: List<String>): TerminalCommandCompletionStatsSnapshot {
        val commandRecords = ArrayList<TerminalCommandCompletionStats>(lines.size - 1)
        val shapeRecords = ArrayList<TerminalCommandShapeStats>(lines.size - 1)
        var index = 1
        while (index < lines.size) {
            val fields = lines[index].split('\t')
            when (fields.firstOrNull()) {
                ROW_COMMAND -> decodeCommandV2(fields)?.let(commandRecords::add)
                ROW_SHAPE -> decodeShapeV2(fields)?.let(shapeRecords::add)
            }
            index++
        }
        return sanitizeSnapshot(TerminalCommandCompletionStatsSnapshot(commandStats = commandRecords, shapeStats = shapeRecords))
    }

    private fun loadVersionThree(lines: List<String>): TerminalCommandCompletionStatsSnapshot {
        val commandRecords = ArrayList<TerminalCommandCompletionStats>(lines.size - 1)
        val shapeRecords = ArrayList<TerminalCommandShapeStats>(lines.size - 1)
        val feedbackRecords = ArrayList<TerminalCompletionFeedbackStats>(lines.size - 1)
        var index = 1
        while (index < lines.size) {
            val fields = lines[index].split('\t')
            when (fields.firstOrNull()) {
                ROW_COMMAND -> decodeCommandV2(fields)?.let(commandRecords::add)
                ROW_SHAPE -> decodeShapeV2(fields)?.let(shapeRecords::add)
                ROW_FEEDBACK -> decodeFeedbackV3(fields)?.let(feedbackRecords::add)
            }
            index++
        }
        return sanitizeSnapshot(
            TerminalCommandCompletionStatsSnapshot(
                commandStats = commandRecords,
                shapeStats = shapeRecords,
                feedbackStats = feedbackRecords,
            ),
        )
    }

    /**
     * Waits until all writes queued before this call have completed.
     *
     * Tests and shutdown use this to make persistence deterministic.
     */
    fun flush() {
        worker.submit {}.get()
    }

    override fun close() {
        worker.shutdown()
        if (!worker.awaitTermination(COMMAND_COMPLETION_STATS_CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            worker.shutdownNow()
        }
    }

    private fun persistSnapshot(snapshot: TerminalCommandCompletionStatsSnapshot) {
        runCatching {
            path.parent?.let(Files::createDirectories)
            val temporary = path.resolveSibling("${path.fileName}.tmp")
            Files.newBufferedWriter(temporary, StandardCharsets.UTF_8).use { writer ->
                writer.appendLine(COMMAND_COMPLETION_STATS_HEADER_V3)
                for (record in snapshot.commandStats) writer.appendLine(encodeCommandV2(record))
                for (record in snapshot.shapeStats) writer.appendLine(encodeShapeV2(record))
                for (record in snapshot.feedbackStats) writer.appendLine(encodeFeedbackV3(record))
            }
            try {
                Files.move(
                    temporary,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
        }.onFailure { exception ->
            System.err.println("Failed to persist command completion stats to $path: ${exception.message}")
        }
    }

    private fun sanitizeSnapshot(snapshot: TerminalCommandCompletionStatsSnapshot): TerminalCommandCompletionStatsSnapshot =
        TerminalCommandCompletionStatsSnapshot(
            commandStats = snapshot.commandStats.filter(CommandPersistencePrivacyPolicy::allowsCommandStats),
            shapeStats = snapshot.shapeStats.filter(CommandPersistencePrivacyPolicy::allowsShapeStats),
            feedbackStats = snapshot.feedbackStats,
        )

    private fun encodeCommandV2(record: TerminalCommandCompletionStats): String =
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

    private fun encodeShapeV2(record: TerminalCommandShapeStats): String =
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

    private fun encodeFeedbackV3(record: TerminalCompletionFeedbackStats): String =
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

    private fun decodeCommandV1(line: String): TerminalCommandCompletionStats? {
        val fields = line.split('\t')
        if (fields.size != COMMAND_COMPLETION_STATS_FIELD_COUNT_V1) return null
        return decodeCommandFields(fields, offset = 0)
    }

    private fun decodeCommandV2(fields: List<String>): TerminalCommandCompletionStats? {
        if (fields.size != COMMAND_COMPLETION_STATS_FIELD_COUNT_V2) return null
        return decodeCommandFields(fields, offset = 1)
    }

    private fun decodeCommandFields(
        fields: List<String>,
        offset: Int,
    ): TerminalCommandCompletionStats? =
        runCatching {
            TerminalCommandCompletionStats(
                commandLine = decodeText(fields[offset]),
                normalizedCommandLine = decodeText(fields[offset + 1]),
                profileId = decodeText(fields[offset + 2]).takeIf(String::isNotEmpty),
                workingDirectoryUri = decodeText(fields[offset + 3]).takeIf(String::isNotEmpty),
                useCount = fields[offset + 4].toInt(),
                successCount = fields[offset + 5].toInt(),
                failureCount = fields[offset + 6].toInt(),
                acceptedCount = fields[offset + 7].toInt(),
                dismissedCount = fields[offset + 8].toInt(),
                lastUsedEpochMillis = fields[offset + 9].toLong(),
            )
        }.getOrNull()

    private fun decodeShapeV2(fields: List<String>): TerminalCommandShapeStats? {
        if (fields.size != SHAPE_STATS_FIELD_COUNT_V2) return null
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

    private fun decodeFeedbackV3(fields: List<String>): TerminalCompletionFeedbackStats? {
        if (fields.size != FEEDBACK_STATS_FIELD_COUNT_V3) return null
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

    private fun encodeText(value: String): String = commandCompletionStatsEncoder.encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decodeText(value: String): String = String(commandCompletionStatsDecoder.decode(value), StandardCharsets.UTF_8)

    private fun encodeTextList(values: List<String>): String = values.joinToString(",") { encodeText(it) }

    private fun decodeTextList(value: String): List<String> =
        if (value.isEmpty()) {
            emptyList()
        } else {
            value.split(',').map(::decodeText)
        }

    private companion object {
        private const val COMMAND_COMPLETION_STATS_HEADER_V1 = "KetraTerm_COMMAND_COMPLETION_STATS\t1"
        private const val COMMAND_COMPLETION_STATS_HEADER_V2 = "KetraTerm_COMMAND_COMPLETION_STATS\t2"
        private const val COMMAND_COMPLETION_STATS_HEADER_V3 = "KetraTerm_COMMAND_COMPLETION_STATS\t3"
        private const val COMMAND_COMPLETION_STATS_FIELD_COUNT_V1 = 10
        private const val COMMAND_COMPLETION_STATS_FIELD_COUNT_V2 = 11
        private const val SHAPE_STATS_FIELD_COUNT_V2 = 15
        private const val FEEDBACK_STATS_FIELD_COUNT_V3 = 11
        private const val ROW_COMMAND = "C"
        private const val ROW_SHAPE = "S"
        private const val ROW_FEEDBACK = "F"
        private const val COMMAND_COMPLETION_STATS_CLOSE_TIMEOUT_SECONDS = 5L
        private val commandCompletionStatsEncoder = Base64.getUrlEncoder().withoutPadding()
        private val commandCompletionStatsDecoder = Base64.getUrlDecoder()
    }
}
