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

import io.github.ketraterm.completion.api.TerminalCompletionPersistencePolicy
import io.github.ketraterm.completion.model.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Versioned local-file persistence for compact completion statistics.
 *
 * The store serializes aggregate ranking metadata rather than terminal output
 * or raw repeated shell history. Loading is synchronous so a host can warm its
 * in-memory index deterministically; writes run on one daemon worker so command
 * lifecycle and UI input paths never perform disk I/O. Both loaded and outgoing
 * snapshots are filtered through [TerminalCompletionPersistencePolicy].
 *
 * @property path destination file for the versioned statistics index.
 * @property onFailure optional host diagnostics callback for failed I/O.
 */
class TerminalCompletionStatsStore
@JvmOverloads
constructor(
    private val path: Path,
    private val onFailure: (Throwable) -> Unit = {},
) : AutoCloseable {
    private val writeQueue = CompletionStatsWriteQueue(::writeSnapshot)

    /**
     * Loads exact command, structural shape, and source-specific feedback
     * statistics from disk.
     *
     * Malformed files produce an empty snapshot and do not prevent later
     * writes. Individual malformed rows are handled by the shared snapshot
     * codec.
     *
     * @return decoded and sanitized completion-statistics snapshot.
     */
    fun loadSnapshot(): TerminalCommandCompletionStatsSnapshot {
        if (!Files.isRegularFile(path)) return TerminalCommandCompletionStatsSnapshot()
        return runCatching {
            val lines = readBoundedLines() ?: return@runCatching TerminalCommandCompletionStatsSnapshot()
            boundedSnapshot(
                TerminalCompletionPersistencePolicy.sanitizeSnapshot(
                    TerminalCommandCompletionStatsSnapshotCodec.decode(lines),
                ),
            )
        }.onFailure(onFailure).getOrElse {
            TerminalCommandCompletionStatsSnapshot()
        }
    }

    /**
     * Queues a complete snapshot replacement.
     *
     * The snapshot is sanitized before it crosses the asynchronous write
     * boundary. When several replacements are pending, the queue retains only
     * the newest complete snapshot.
     *
     * @param snapshot compact statistics produced by the completion engine.
     */
    fun persist(snapshot: TerminalCommandCompletionStatsSnapshot) {
        val stableSnapshot = boundedSnapshot(TerminalCompletionPersistencePolicy.sanitizeSnapshot(snapshot))
        writeQueue.enqueue(stableSnapshot)
    }

    /** Waits until all writes queued before this call have completed. */
    fun flush() {
        writeQueue.flush()
    }

    /** Flushes the newest pending snapshot and stops the store worker. */
    override fun close() = writeQueue.close()

    private fun writeSnapshot(snapshot: TerminalCommandCompletionStatsSnapshot) {
        runCatching {
            path.parent?.let(Files::createDirectories)
            val temporary = path.resolveSibling("${path.fileName}.tmp")
            Files.newBufferedWriter(temporary, StandardCharsets.UTF_8).use { writer ->
                var writtenBytes = 0
                for (line in TerminalCommandCompletionStatsSnapshotCodec.encode(snapshot)) {
                    val lineBytes = line.toByteArray(StandardCharsets.UTF_8).size
                    if (lineBytes > MAX_LINE_BYTES || writtenBytes + lineBytes + MAX_NEWLINE_BYTES > MAX_FILE_BYTES) break
                    writer.appendLine(line)
                    writtenBytes += lineBytes + MAX_NEWLINE_BYTES
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
        }.onFailure(onFailure)
    }

    private fun readBoundedLines(): List<String>? =
        Files.newInputStream(path).use { input ->
            val bytes = input.readNBytes(MAX_FILE_BYTES + 1)
            if (bytes.size > MAX_FILE_BYTES) return null
            val lines = ArrayList<String>(minOf(MAX_FILE_LINES, DEFAULT_LINE_CAPACITY))
            BufferedReader(InputStreamReader(bytes.inputStream(), StandardCharsets.UTF_8)).use { reader ->
                while (lines.size < MAX_FILE_LINES) {
                    val line = reader.readLine() ?: return lines
                    if (line.length <= MAX_LINE_CHARS) lines += line
                }
            }
            lines
        }

    private fun boundedSnapshot(snapshot: TerminalCommandCompletionStatsSnapshot): TerminalCommandCompletionStatsSnapshot =
        TerminalCommandCompletionStatsSnapshot(
            commandStats = boundedRows(snapshot.commandStats, ::commandRowSize),
            shapeStats = boundedRows(snapshot.shapeStats, ::shapeRowSize),
            feedbackStats = boundedRows(snapshot.feedbackStats, ::feedbackRowSize),
        )

    private fun <T> boundedRows(
        rows: List<T>,
        rowSize: (T) -> Int?,
    ): List<T> {
        val retained = ArrayList<T>(minOf(rows.size, MAX_ROWS_PER_FAMILY))
        var retainedChars = 0
        for (row in rows) {
            val size = rowSize(row) ?: continue
            if (retainedChars + size > MAX_RAW_CHARS_PER_FAMILY) continue
            retained += row
            retainedChars += size
            if (retained.size == MAX_ROWS_PER_FAMILY) break
        }
        return retained
    }

    private fun commandRowSize(row: TerminalCommandCompletionStats): Int? =
        boundedTextSize(row.commandLine, row.profileId, row.workingDirectoryUri)

    private fun shapeRowSize(row: TerminalCommandShapeStats): Int? {
        if (row.shape.subcommands.size > MAX_SHAPE_TOKENS || row.shape.optionNames.size > MAX_SHAPE_TOKENS) return null
        return boundedTextSize(
            row.shape.executable,
            row.profileId,
            row.workingDirectoryUri,
            *row.shape.subcommands.toTypedArray(),
            *row.shape.optionNames.toTypedArray(),
        )
    }

    private fun feedbackRowSize(row: TerminalCompletionFeedbackStats): Int? =
        boundedTextSize(row.source, row.profileId, row.workingDirectoryUri)

    private fun boundedTextSize(vararg values: String?): Int? {
        var total = 0
        for (value in values) {
            val length = value?.length ?: continue
            if (length > MAX_TEXT_CHARS || total + length > MAX_ROW_RAW_CHARS) return null
            total += length
        }
        return total
    }

    companion object {
        /**
         * Returns the canonical file name for the current persisted format.
         *
         * Hosts should resolve this name beneath their own configuration or
         * application-data directory.
         *
         * @return versioned completion-statistics file name.
         */
        @JvmStatic
        fun currentFileName(): String = TerminalCommandCompletionStatsSnapshotCodec.currentFileName()

        private const val MAX_ROWS_PER_FAMILY = 2_048
        private const val MAX_FILE_BYTES = 4 * 1024 * 1024
        private const val MAX_FILE_LINES = 1 + 3 * MAX_ROWS_PER_FAMILY
        private const val MAX_LINE_BYTES = 16 * 1024
        private const val MAX_LINE_CHARS = 16 * 1024
        private const val MAX_NEWLINE_BYTES = 2
        private const val MAX_RAW_CHARS_PER_FAMILY = 750_000
        private const val MAX_ROW_RAW_CHARS = 8 * 1024
        private const val MAX_TEXT_CHARS = 4 * 1024
        private const val MAX_SHAPE_TOKENS = 128
        private const val DEFAULT_LINE_CAPACITY = 256
    }
}
