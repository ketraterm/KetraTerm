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
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompletionLearningSnapshotCodecTest {
    @Test
    fun `current file name header and docs share the same format version`() {
        val encoded = CompletionLearningSnapshotCodec.encode(TerminalCommandCompletionStatsSnapshot.EMPTY)
        val storageDoc = Files.readString(repositoryRoot.resolve("docs/persistent-terminal-storage.md"))

        assertEquals("command-completion-stats-v2.tsv", TerminalCompletionLearningCoordinator.currentFileName())
        assertEquals(listOf(HEADER), encoded)
        assertTrue(storageDoc.contains("`${TerminalCompletionLearningCoordinator.currentFileName()}`"))
        assertTrue(storageDoc.contains(HEADER.replace("\t", "<TAB>")) || storageDoc.contains(HEADER))
    }

    @Test
    fun `round trips exact command statistics with unicode text`() {
        val snapshot =
            TerminalCommandCompletionStatsSnapshot(
                commandStats = listOf(commandStats("git commit -m 'Բարև աշխարհ'")),
            )

        assertEquals(snapshot, CompletionLearningSnapshotCodec.decode(CompletionLearningSnapshotCodec.encode(snapshot)))
    }

    @Test
    fun `unsupported and malformed rows reject the complete snapshot`() {
        val valid = commandStats("git status")
        val malformedCount = commandRow(valid).replace("\t4\t", "\tinvalid\t")
        val malformedUtf8 =
            commandRow(valid)
                .split('\t')
                .toMutableList()
                .also { it[1] = "_w" }
                .joinToString("\t")
        val invalidRows =
            listOf(
                malformedCount,
                malformedUtf8,
                "C\tnot-base64",
                "S\tremoved",
                "F\tremoved",
                "X\tunknown",
            )

        for (invalidRow in invalidRows) {
            assertNull(
                CompletionLearningSnapshotCodec.decode(
                    listOf(HEADER, commandRow(valid), invalidRow),
                ),
            )
        }
    }

    @Test
    fun `previous exact-incompatible schema is rejected`() {
        val decoded = CompletionLearningSnapshotCodec.decode(listOf("KetraTerm_COMMAND_COMPLETION_STATS\t1"))

        assertNull(decoded)
    }

    @Test
    fun `unknown header is rejected`() {
        val decoded = CompletionLearningSnapshotCodec.decode(listOf("KetraTerm_COMMAND_COMPLETION_STATS\t999"))

        assertNull(decoded)
    }

    @Test
    fun `header-only file decodes to an empty snapshot`() {
        assertEquals(
            TerminalCommandCompletionStatsSnapshot.EMPTY,
            CompletionLearningSnapshotCodec.decode(listOf(HEADER)),
        )
    }

    private fun commandStats(
        commandLine: String,
        profileId: String? = "bash",
        workingDirectoryUri: String? = "file:///repo",
    ): TerminalCommandCompletionStats =
        TerminalCommandCompletionStats(
            commandLine = commandLine,
            profileId = profileId,
            workingDirectoryUri = workingDirectoryUri,
            useCount = 4,
            successCount = 3,
            failureCount = 1,
            acceptedCount = 2,
            dismissedCount = 1,
            lastUsedEpochMillis = 1234,
        )

    private fun commandRow(record: TerminalCommandCompletionStats): String =
        listOf(
            "C",
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

    private fun encodeText(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private companion object {
        private const val HEADER = "KetraTerm_COMMAND_COMPLETION_STATS\t2"
        private val workingDirectory: Path = Paths.get("").toAbsolutePath()
        private val repositoryRoot: Path =
            if (Files.isRegularFile(workingDirectory.resolve("docs/persistent-terminal-storage.md"))) {
                workingDirectory
            } else {
                workingDirectory.parent
            }
    }
}
