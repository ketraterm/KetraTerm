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

import io.github.ketraterm.session.TerminalShellIntegrationCommandMetadata
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Opt-in, bounded command metadata persistence for the standalone host.
 *
 * Writes run on one daemon worker so terminal parser/transport threads never
 * perform disk I/O. The versioned tab-separated format Base64-encodes all text
 * fields, and each replacement is committed through an atomic move when the
 * filesystem supports it. Raw terminal output is deliberately never stored.
 */
internal class CommandHistoryStore(
    private val path: Path,
    private val capacity: Int = DEFAULT_CAPACITY,
) : AutoCloseable {
    init {
        require(capacity > 0) { "capacity must be > 0, was $capacity" }
    }

    private val entries = ArrayDeque<CommandHistoryEntry>(capacity)
    private val worker =
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "jvterm-command-history").apply { isDaemon = true }
        }

    init {
        load()
    }

    /** Queues one completed command metadata record for bounded persistence. */
    fun record(
        profileId: String,
        metadata: TerminalShellIntegrationCommandMetadata,
    ) {
        val command = metadata.commandText ?: return
        val finishedAt = metadata.finishedAtEpochMillis ?: return
        val entry =
            CommandHistoryEntry(
                profileId = profileId,
                command = command,
                workingDirectoryUri = metadata.workingDirectoryUri,
                exitCode = metadata.exitCode,
                startedAtEpochMillis = metadata.startedAtEpochMillis,
                finishedAtEpochMillis = finishedAt,
            )
        worker.execute {
            while (entries.size >= capacity) entries.removeFirst()
            entries.addLast(entry)
            persist()
        }
    }

    /** Waits until all already-queued writes have completed. Intended for shutdown and tests. */
    fun flush() {
        worker.submit {}.get()
    }

    internal fun snapshot(): List<CommandHistoryEntry> {
        flush()
        return entries.toList()
    }

    override fun close() {
        worker.shutdown()
        if (!worker.awaitTermination(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            worker.shutdownNow()
        }
    }

    private fun load() {
        if (!Files.isRegularFile(path)) return
        runCatching {
            val lines = Files.readAllLines(path, StandardCharsets.UTF_8)
            if (lines.firstOrNull() != HEADER) return
            var index = 1
            while (index < lines.size) {
                decode(lines[index])?.let { entry ->
                    while (entries.size >= capacity) entries.removeFirst()
                    entries.addLast(entry)
                }
                index++
            }
        }.onFailure { exception ->
            System.err.println("Failed to load command history from $path: ${exception.message}")
        }
    }

    private fun persist() {
        runCatching {
            path.parent?.let(Files::createDirectories)
            val temporary = path.resolveSibling("${path.fileName}.tmp")
            Files.newBufferedWriter(temporary, StandardCharsets.UTF_8).use { writer ->
                writer.appendLine(HEADER)
                for (entry in entries) writer.appendLine(encode(entry))
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
            System.err.println("Failed to persist command history to $path: ${exception.message}")
        }
    }

    private fun encode(entry: CommandHistoryEntry): String =
        listOf(
            entry.startedAtEpochMillis.toString(),
            entry.finishedAtEpochMillis.toString(),
            entry.exitCode?.toString().orEmpty(),
            encodeText(entry.profileId),
            encodeText(entry.workingDirectoryUri.orEmpty()),
            encodeText(entry.command),
        ).joinToString("\t")

    private fun decode(line: String): CommandHistoryEntry? {
        val fields = line.split('\t')
        if (fields.size != FIELD_COUNT) return null
        return runCatching {
            CommandHistoryEntry(
                profileId = decodeText(fields[3]),
                command = decodeText(fields[5]),
                workingDirectoryUri = decodeText(fields[4]).takeIf(String::isNotEmpty),
                exitCode = fields[2].takeIf(String::isNotEmpty)?.toInt(),
                startedAtEpochMillis = fields[0].toLong(),
                finishedAtEpochMillis = fields[1].toLong(),
            )
        }.getOrNull()
    }

    private fun encodeText(value: String): String = encoder.encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decodeText(value: String): String = String(decoder.decode(value), StandardCharsets.UTF_8)

    private companion object {
        private const val HEADER = "JVTERM_COMMAND_HISTORY\t1"
        private const val FIELD_COUNT = 6
        private const val DEFAULT_CAPACITY = 10_000
        private const val CLOSE_TIMEOUT_SECONDS = 5L
        private val encoder = Base64.getUrlEncoder().withoutPadding()
        private val decoder = Base64.getUrlDecoder()
    }
}

/** One persisted command metadata record. Raw command output is intentionally absent. */
internal data class CommandHistoryEntry(
    val profileId: String,
    val command: String,
    val workingDirectoryUri: String?,
    val exitCode: Int?,
    val startedAtEpochMillis: Long,
    val finishedAtEpochMillis: Long,
)
