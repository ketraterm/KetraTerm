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
import io.github.ketraterm.completion.model.TerminalCommandCompletionStats
import io.github.ketraterm.completion.model.TerminalCommandCompletionStatsSnapshot
import io.github.ketraterm.completion.model.TerminalCompletionFeedbackStats
import java.io.IOException
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class CompletionLearningFileStoreTest {
    @Test
    fun `persist replaces the file synchronously`() {
        val path = createTempDirectory("completion-store").resolve(TerminalCompletionLearningRepository.currentFileName())
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
        val path = createTempDirectory("completion-store-private").resolve(TerminalCompletionLearningRepository.currentFileName())
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
    fun `encoded byte bound rejects a multibyte row without dropping later families`() {
        val path = createTempDirectory("completion-store-encoded-bound").resolve(TerminalCompletionLearningRepository.currentFileName())
        val feedback =
            TerminalCompletionFeedbackStats(
                source = "history",
                candidateKind = TerminalCompletionCandidateKind.HISTORY,
                acceptedCount = 1,
            )

        CompletionLearningFileStore(path).persist(
            TerminalCommandCompletionStatsSnapshot(
                commandStats = listOf(record("界".repeat(4_096))),
                feedbackStats = listOf(feedback),
            ),
        )

        val loaded = CompletionLearningFileStore(path).loadSnapshot().loadedSnapshot()
        assertEquals(emptyList(), loaded.commandStats)
        assertEquals(listOf(feedback), loaded.feedbackStats)
        assertTrue(Files.readAllLines(path).all { it.toByteArray().size <= MAX_LINE_BYTES })
    }

    @Test
    fun `each write uses a unique same-directory temporary file and cleans it`() {
        val directory = createTempDirectory("completion-store-temporary")
        val path = directory.resolve(TerminalCompletionLearningRepository.currentFileName())
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
        val path = createTempDirectory("completion-store-missing").resolve(TerminalCompletionLearningRepository.currentFileName())

        assertSame(CompletionLearningFileLoadOutcome.Missing, CompletionLearningFileStore(path).loadSnapshot())
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
        val header = "KetraTerm_COMMAND_COMPLETION_STATS\t1\n".encodeToByteArray()
        val oversizedLine = ByteArray(MAX_LINE_BYTES + 1) { 'x'.code.toByte() }

        assertRejectedWithoutMutation(header + oversizedLine)
    }

    @Test
    fun `excessive line count is rejected without changing bytes`() {
        val content =
            buildString {
                appendLine("KetraTerm_COMMAND_COMPLETION_STATS\t1")
                repeat(MAX_FILE_LINES) { appendLine("ignored") }
            }.encodeToByteArray()

        assertRejectedWithoutMutation(content)
    }

    @Test
    fun `non-file path is rejected`() {
        val path = createTempDirectory("completion-store-directory")

        assertSame(CompletionLearningFileLoadOutcome.Rejected, CompletionLearningFileStore(path).loadSnapshot())
        assertTrue(Files.isDirectory(path))
    }

    @Test
    fun `input failure is reported separately`() {
        val path = createTempDirectory("completion-store-failed").resolve(TerminalCompletionLearningRepository.currentFileName())
        Files.writeString(path, "KetraTerm_COMMAND_COMPLETION_STATS\t1")
        val failures = mutableListOf<Throwable>()
        val expectedFailure = IOException("test read failure")

        val outcome = CompletionLearningFileStore(path, failures::add, openInput = { throw expectedFailure }).loadSnapshot()

        assertSame(CompletionLearningFileLoadOutcome.Failed, outcome)
        assertSame(expectedFailure, failures.single())
        assertEquals("KetraTerm_COMMAND_COMPLETION_STATS\t1", Files.readString(path))
    }

    private fun assertRejectedWithoutMutation(originalBytes: ByteArray) {
        val path = createTempDirectory("completion-store-large").resolve(TerminalCompletionLearningRepository.currentFileName())
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
        private const val MAX_FILE_BYTES = 4 * 1024 * 1024
        private const val MAX_FILE_LINES = 1 + 3 * 2_048
        private const val MAX_LINE_BYTES = 16 * 1024
    }
}
