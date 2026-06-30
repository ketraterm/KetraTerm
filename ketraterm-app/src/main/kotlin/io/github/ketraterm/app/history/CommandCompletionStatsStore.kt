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

import io.github.ketraterm.completion.TerminalCommandCompletionStats
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
 * lifecycle events and Swing input never perform disk I/O.
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
     * Loads all valid stats rows from disk.
     *
     * Malformed rows are ignored independently so one damaged line does not
     * discard the complete index.
     *
     * @return decoded command-completion stats in persisted order.
     */
    fun load(): List<TerminalCommandCompletionStats> {
        if (!Files.isRegularFile(path)) return emptyList()
        return runCatching {
            val lines = Files.readAllLines(path, StandardCharsets.UTF_8)
            if (lines.firstOrNull() != COMMAND_COMPLETION_STATS_HEADER) return emptyList()
            val records = ArrayList<TerminalCommandCompletionStats>(lines.size - 1)
            var index = 1
            while (index < lines.size) {
                decode(lines[index])?.let(records::add)
                index++
            }
            records
        }.onFailure { exception ->
            System.err.println("Failed to load command completion stats from $path: ${exception.message}")
        }.getOrElse { emptyList() }
    }

    /**
     * Queues a full snapshot replacement.
     *
     * @param records compact stats rows produced by the shared completion index.
     */
    fun persist(records: List<TerminalCommandCompletionStats>) {
        val snapshot = records.toList()
        worker.execute {
            persistSnapshot(snapshot)
        }
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

    private fun persistSnapshot(records: List<TerminalCommandCompletionStats>) {
        runCatching {
            path.parent?.let(Files::createDirectories)
            val temporary = path.resolveSibling("${path.fileName}.tmp")
            Files.newBufferedWriter(temporary, StandardCharsets.UTF_8).use { writer ->
                writer.appendLine(COMMAND_COMPLETION_STATS_HEADER)
                for (record in records) writer.appendLine(encode(record))
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

    private fun encode(record: TerminalCommandCompletionStats): String =
        listOf(
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

    private fun decode(line: String): TerminalCommandCompletionStats? {
        val fields = line.split('\t')
        if (fields.size != COMMAND_COMPLETION_STATS_FIELD_COUNT) return null
        return runCatching {
            TerminalCommandCompletionStats(
                commandLine = decodeText(fields[0]),
                normalizedCommandLine = decodeText(fields[1]),
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

    private fun encodeText(value: String): String = commandCompletionStatsEncoder.encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decodeText(value: String): String = String(commandCompletionStatsDecoder.decode(value), StandardCharsets.UTF_8)

    private companion object {
        private const val COMMAND_COMPLETION_STATS_HEADER = "KetraTerm_COMMAND_COMPLETION_STATS\t1"
        private const val COMMAND_COMPLETION_STATS_FIELD_COUNT = 10
        private const val COMMAND_COMPLETION_STATS_CLOSE_TIMEOUT_SECONDS = 5L
        private val commandCompletionStatsEncoder = Base64.getUrlEncoder().withoutPadding()
        private val commandCompletionStatsDecoder = Base64.getUrlDecoder()
    }
}
