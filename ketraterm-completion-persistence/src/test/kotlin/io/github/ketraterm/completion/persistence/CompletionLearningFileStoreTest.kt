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
import java.io.IOException
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class CompletionLearningFileStoreTest {
    @Test
    fun `persist replaces the file synchronously`() {
        val path = createTempDirectory("completion-store").resolve(TerminalCompletionLearningCoordinator.currentFileName())
        val store = CompletionLearningFileStore(path)
        val first = snapshot("git status")
        val second = snapshot("npm test")

        store.persist(first)
        assertEquals(first, store.loadSnapshot().loadedSnapshot())
        store.persist(second)
        assertEquals(second, store.loadSnapshot().loadedSnapshot())
    }

    @Test
    fun `private command rows are removed before writing`() {
        val path = createTempDirectory("completion-store-private").resolve(TerminalCompletionLearningCoordinator.currentFileName())
        val store = CompletionLearningFileStore(path)
        store.persist(
            TerminalCommandCompletionStatsSnapshot(
                commandStats =
                    listOf(
                        record("git status"),
                        record("docker login --password hunter2"),
                    ),
            ),
        )

        assertEquals(listOf(record("git status")), store.loadSnapshot().loadedSnapshot().commandStats)
    }

    @Test
    fun `encoded byte bound rejects a multibyte row without dropping later commands`() {
        val path = createTempDirectory("completion-store-encoded-bound").resolve(TerminalCompletionLearningCoordinator.currentFileName())
        val retained = record("git status")

        CompletionLearningFileStore(path).persist(
            TerminalCommandCompletionStatsSnapshot(
                commandStats = listOf(record("界".repeat(4_096)), retained),
            ),
        )

        val loaded = CompletionLearningFileStore(path).loadSnapshot().loadedSnapshot()
        assertEquals(listOf(retained), loaded.commandStats)
        assertTrue(Files.readAllLines(path).all { it.toByteArray().size <= MAX_LINE_BYTES })
    }

    @Test
    fun `each write uses a unique same-directory temporary file and cleans it`() {
        val directory = createTempDirectory("completion-store-temporary")
        val path = directory.resolve(TerminalCompletionLearningCoordinator.currentFileName())
        val temporaryFiles = mutableListOf<java.nio.file.Path>()
        val store =
            CompletionLearningFileStore(
                path = path,
                createTemporaryFile = { parent, prefix, suffix ->
                    Files.createTempFile(parent, prefix, suffix).also(temporaryFiles::add)
                },
            )

        store.persist(snapshot("command-1"))
        store.persist(snapshot("command-2"))

        assertEquals(2, temporaryFiles.distinct().size)
        assertTrue(temporaryFiles.all { it.parent == directory.toAbsolutePath().normalize() })
        assertTrue(temporaryFiles.none(Files::exists))
        assertEquals(
            "command-2",
            CompletionLearningFileStore(path)
                .loadSnapshot()
                .loadedSnapshot()
                .commandStats
                .single()
                .commandLine,
        )
    }

    @Test
    fun `missing file is reported separately`() {
        val path = createTempDirectory("completion-store-missing").resolve(TerminalCompletionLearningCoordinator.currentFileName())

        assertSame(CompletionLearningFileLoadOutcome.Missing, CompletionLearningFileStore(path).loadSnapshot())
    }

    @Test
    fun `previous format is rejected without changing bytes`() {
        assertRejectedWithoutMutation("KetraTerm_COMMAND_COMPLETION_STATS\t1\n".encodeToByteArray())
    }

    @Test
    fun `unknown header is rejected without changing bytes`() {
        assertRejectedWithoutMutation("KetraTerm_COMMAND_COMPLETION_STATS\t999\nC\tignored".encodeToByteArray())
    }

    @Test
    fun `oversized input file is rejected without changing bytes`() {
        assertRejectedWithoutMutation(ByteArray(MAX_FILE_BYTES + 1) { 'x'.code.toByte() })
    }

    @Test
    fun `oversized line is rejected without changing bytes`() {
        val header = "KetraTerm_COMMAND_COMPLETION_STATS\t2\n".encodeToByteArray()
        val oversizedLine = ByteArray(MAX_LINE_BYTES + 1) { 'x'.code.toByte() }

        assertRejectedWithoutMutation(header + oversizedLine)
    }

    @Test
    fun `excessive line count is rejected without changing bytes`() {
        val validRow = CompletionLearningSnapshotCodec.encode(snapshot("git status"))[1]
        val content =
            buildString {
                appendLine("KetraTerm_COMMAND_COMPLETION_STATS\t2")
                repeat(MAX_FILE_LINES) { appendLine(validRow) }
            }.encodeToByteArray()

        assertRejectedWithoutMutation(content)
    }

    @Test
    fun `unsupported and removed row families are rejected without changing bytes`() {
        val exactLines = CompletionLearningSnapshotCodec.encode(snapshot("git status"))

        for (rowTag in listOf("S", "F", "X")) {
            val originalBytes = (exactLines + "$rowTag\tunsupported").joinToString("\n", postfix = "\n").encodeToByteArray()
            assertRejectedWithoutMutation(originalBytes)
        }
    }

    @Test
    fun `malformed exact row is rejected without changing bytes`() {
        assertRejectedWithoutMutation(
            "KetraTerm_COMMAND_COMPLETION_STATS\t2\nC\tnot-base64\n".encodeToByteArray(),
        )
    }

    @Test
    fun `over-bound exact row rejects the complete file`() {
        val validRow = CompletionLearningSnapshotCodec.encodeCommandRow(record("git status"))
        val overBoundRow = CompletionLearningSnapshotCodec.encodeCommandRow(record("x".repeat(MAX_TEXT_CHARS + 1)))
        val originalBytes =
            listOf("KetraTerm_COMMAND_COMPLETION_STATS\t2", validRow, overBoundRow, validRow)
                .joinToString("\n", postfix = "\n")
                .encodeToByteArray()

        assertRejectedWithoutMutation(originalBytes)
    }

    @Test
    fun `aggregate exact-row byte overflow rejects the complete file`() {
        val row = CompletionLearningSnapshotCodec.encodeCommandRow(record("x".repeat(512)))
        val rowBytes = row.encodeToByteArray().size
        val rowCount = MAX_ENCODED_COMMAND_BYTES / (rowBytes + MAX_NEWLINE_BYTES) + 1
        assertTrue(rowCount < MAX_FILE_LINES)
        val originalBytes =
            buildString {
                appendLine("KetraTerm_COMMAND_COMPLETION_STATS\t2")
                repeat(rowCount) {
                    append(row)
                    append('\n')
                }
            }.encodeToByteArray()
        assertTrue(originalBytes.size <= MAX_FILE_BYTES)

        assertRejectedWithoutMutation(originalBytes)
    }

    @Test
    fun `non-file path is rejected`() {
        val path = createTempDirectory("completion-store-directory")

        assertSame(CompletionLearningFileLoadOutcome.Rejected, CompletionLearningFileStore(path).loadSnapshot())
        assertTrue(Files.isDirectory(path))
    }

    @Test
    fun `input failure is returned separately`() {
        val path = createTempDirectory("completion-store-failed").resolve(TerminalCompletionLearningCoordinator.currentFileName())
        Files.writeString(path, "KetraTerm_COMMAND_COMPLETION_STATS\t2")
        val expectedFailure = IOException("test read failure")

        val outcome = CompletionLearningFileStore(path, openInput = { throw expectedFailure }).loadSnapshot()

        assertSame(CompletionLearningFileLoadOutcome.Failed, outcome)
        assertEquals("KetraTerm_COMMAND_COMPLETION_STATS\t2", Files.readString(path))
    }

    @Test
    fun `output failure is rethrown`() {
        val path = createTempDirectory("completion-store-output-failed").resolve(TerminalCompletionLearningCoordinator.currentFileName())
        val expectedFailure = IOException("test write failure")
        val store =
            CompletionLearningFileStore(
                path = path,
                createTemporaryFile = { _, _, _ -> throw expectedFailure },
            )

        assertSame(expectedFailure, assertFailsWith<IOException> { store.persist(snapshot("git status")) })
    }

    @Test
    fun `malformed JVM text fails without replacing the existing file`() {
        val path = createTempDirectory("completion-store-malformed-text").resolve(TerminalCompletionLearningCoordinator.currentFileName())
        val store = CompletionLearningFileStore(path)
        store.persist(snapshot("git status"))
        val originalBytes = Files.readAllBytes(path)

        assertFailsWith<CharacterCodingException> { store.persist(snapshot("echo \uD800")) }

        assertContentEquals(originalBytes, Files.readAllBytes(path))
    }

    private fun assertRejectedWithoutMutation(originalBytes: ByteArray) {
        val path = createTempDirectory("completion-store-large").resolve(TerminalCompletionLearningCoordinator.currentFileName())
        Files.write(path, originalBytes)

        assertSame(CompletionLearningFileLoadOutcome.Rejected, CompletionLearningFileStore(path).loadSnapshot())
        assertContentEquals(originalBytes, Files.readAllBytes(path))
    }

    private fun CompletionLearningFileLoadOutcome.loadedSnapshot(): TerminalCommandCompletionStatsSnapshot =
        assertIs<CompletionLearningFileLoadOutcome.Loaded>(this).snapshot

    private fun snapshot(command: String) = TerminalCommandCompletionStatsSnapshot(commandStats = listOf(record(command)))

    private fun record(command: String) =
        TerminalCommandCompletionStats(
            commandLine = command,
            successCount = 1,
            failureCount = 0,
            lastUsedEpochMillis = 42L,
        )

    private companion object {
        private const val MAX_FILE_BYTES = 1_000_000 + 128
        private const val MAX_FILE_LINES = 1 + 2_048
        private const val MAX_LINE_BYTES = 16 * 1024
        private const val MAX_ENCODED_COMMAND_BYTES = 1_000_000
        private const val MAX_NEWLINE_BYTES = 2
        private const val MAX_TEXT_CHARS = 4 * 1024
    }
}
