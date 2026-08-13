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
import io.github.ketraterm.completion.model.TerminalCommandCompletionStats
import io.github.ketraterm.completion.model.TerminalCommandCompletionStatsSnapshot
import io.github.ketraterm.completion.model.TerminalCommandShapeStats
import io.github.ketraterm.completion.model.TerminalCompletionFeedbackStats
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes

/** Result of reading a completion-learning file without conflating absence, rejection, and I/O failure. */
internal sealed interface CompletionLearningFileLoadOutcome {
    /** No file exists at the configured path. */
    data object Missing : CompletionLearningFileLoadOutcome

    /** The file was valid and decoded to [snapshot]. */
    data class Loaded(
        val snapshot: TerminalCommandCompletionStatsSnapshot,
    ) : CompletionLearningFileLoadOutcome

    /** The file exists but has an unsupported format or exceeds a hard input bound. */
    data object Rejected : CompletionLearningFileLoadOutcome

    /** The file could not be inspected or read. */
    data object Failed : CompletionLearningFileLoadOutcome
}

/** Bounded local-file implementation used only by the suspending repository. */
internal class CompletionLearningFileStore(
    private val path: Path,
    private val onFailure: (Throwable) -> Unit = {},
    private val openInput: (Path) -> InputStream = { Files.newInputStream(it) },
) {
    fun loadSnapshot(): CompletionLearningFileLoadOutcome =
        try {
            val attributes = Files.readAttributes(path, BasicFileAttributes::class.java)
            if (!attributes.isRegularFile) {
                CompletionLearningFileLoadOutcome.Rejected
            } else {
                val lines = readBoundedLines()
                if (lines == null || lines.firstOrNull() != CURRENT_HEADER) {
                    CompletionLearningFileLoadOutcome.Rejected
                } else {
                    CompletionLearningFileLoadOutcome.Loaded(
                        boundedSnapshot(
                            TerminalCompletionPersistencePolicy.sanitizeSnapshot(
                                CompletionLearningSnapshotCodec.decode(lines),
                            ),
                        ),
                    )
                }
            }
        } catch (_: NoSuchFileException) {
            CompletionLearningFileLoadOutcome.Missing
        } catch (failure: Exception) {
            failed(failure)
        }

    fun persist(snapshot: TerminalCommandCompletionStatsSnapshot) {
        writeSnapshot(boundedSnapshot(TerminalCompletionPersistencePolicy.sanitizeSnapshot(snapshot)))
    }

    private fun writeSnapshot(snapshot: TerminalCommandCompletionStatsSnapshot) {
        runCatching {
            path.parent?.let(Files::createDirectories)
            val temporary = path.resolveSibling("${path.fileName}.tmp")
            Files.newBufferedWriter(temporary, StandardCharsets.UTF_8).use { writer ->
                var writtenBytes = 0
                for (line in CompletionLearningSnapshotCodec.encode(snapshot)) {
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

    private fun readBoundedLines(): List<String>? {
        val bytes =
            openInput(path).use { input ->
                input.readNBytes(MAX_FILE_BYTES + 1)
            }
        if (bytes.size > MAX_FILE_BYTES) return null

        val lines = ArrayList<String>(minOf(MAX_FILE_LINES, DEFAULT_LINE_CAPACITY))
        var lineStart = 0
        for (index in bytes.indices) {
            if (bytes[index] != NEWLINE_BYTE) continue
            val contentEnd = if (index > lineStart && bytes[index - 1] == CARRIAGE_RETURN_BYTE) index - 1 else index
            if (!lines.addBoundedLine(bytes, lineStart, contentEnd)) return null
            lineStart = index + 1
        }
        if (lineStart < bytes.size && !lines.addBoundedLine(bytes, lineStart, bytes.size)) return null
        return lines
    }

    private fun MutableList<String>.addBoundedLine(
        bytes: ByteArray,
        start: Int,
        end: Int,
    ): Boolean {
        val byteCount = end - start
        if (size == MAX_FILE_LINES || byteCount > MAX_LINE_BYTES) return false
        add(String(bytes, start, byteCount, StandardCharsets.UTF_8))
        return true
    }

    private fun failed(failure: Throwable): CompletionLearningFileLoadOutcome {
        onFailure(failure)
        return CompletionLearningFileLoadOutcome.Failed
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

    private companion object {
        private const val MAX_ROWS_PER_FAMILY = 2_048
        private const val MAX_FILE_BYTES = 4 * 1024 * 1024
        private const val MAX_FILE_LINES = 1 + 3 * MAX_ROWS_PER_FAMILY
        private const val MAX_LINE_BYTES = 16 * 1024
        private const val MAX_NEWLINE_BYTES = 2
        private const val MAX_RAW_CHARS_PER_FAMILY = 750_000
        private const val MAX_ROW_RAW_CHARS = 8 * 1024
        private const val MAX_TEXT_CHARS = 4 * 1024
        private const val MAX_SHAPE_TOKENS = 128
        private const val DEFAULT_LINE_CAPACITY = 256
        private const val NEWLINE_BYTE: Byte = 0x0A
        private const val CARRIAGE_RETURN_BYTE: Byte = 0x0D
        private val CURRENT_HEADER =
            CompletionLearningSnapshotCodec.encode(TerminalCommandCompletionStatsSnapshot()).single()
    }
}
