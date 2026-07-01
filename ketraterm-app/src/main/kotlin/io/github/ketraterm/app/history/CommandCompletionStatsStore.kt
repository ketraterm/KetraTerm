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
import io.github.ketraterm.completion.TerminalCommandCompletionStatsSnapshot
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

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
    private val writeQueue = CommandCompletionStatsWriteQueue(::writeSnapshot)

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
     * Loads exact command, structural shape, and source-specific feedback stats
     * from disk.
     *
     * @return decoded completion stats snapshot.
     */
    fun loadSnapshot(): TerminalCommandCompletionStatsSnapshot {
        if (!Files.isRegularFile(path)) return TerminalCommandCompletionStatsSnapshot()
        return runCatching {
            val lines = Files.readAllLines(path, StandardCharsets.UTF_8)
            CommandCompletionStatsSanitizer.sanitize(CommandCompletionStatsCodec.decode(lines))
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
        val stableSnapshot = CommandCompletionStatsSanitizer.sanitize(snapshot)
        writeQueue.enqueue(stableSnapshot)
    }

    /**
     * Waits until all writes queued before this call have completed.
     *
     * Tests and shutdown use this to make persistence deterministic.
     */
    fun flush() {
        writeQueue.flush()
    }

    override fun close() = writeQueue.close()

    private fun writeSnapshot(snapshot: TerminalCommandCompletionStatsSnapshot) {
        runCatching {
            path.parent?.let(Files::createDirectories)
            val temporary = path.resolveSibling("${path.fileName}.tmp")
            Files.newBufferedWriter(temporary, StandardCharsets.UTF_8).use { writer ->
                for (line in CommandCompletionStatsCodec.encode(snapshot)) {
                    writer.appendLine(line)
                }
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
}
