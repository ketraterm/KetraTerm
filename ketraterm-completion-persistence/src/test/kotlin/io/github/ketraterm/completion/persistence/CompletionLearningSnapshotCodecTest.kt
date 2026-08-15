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
import io.github.ketraterm.completion.api.TerminalCompletionLearningStore
import io.github.ketraterm.completion.model.*
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class CompletionLearningSnapshotCodecTest {
    @Test
    fun `current file name header and docs share the same format version`() {
        val encodedHeader = CompletionLearningSnapshotCodec.encode(TerminalCommandCompletionStatsSnapshot()).first()
        val storageDoc = Files.readString(repositoryRoot.resolve("docs/persistent-terminal-storage.md"))

        assertEquals("command-completion-stats-v1.tsv", TerminalCompletionLearningRepository.currentFileName())
        assertEquals("KetraTerm_COMMAND_COMPLETION_STATS\t1", encodedHeader)
        assertTrue(storageDoc.contains("`${TerminalCompletionLearningRepository.currentFileName()}`"))
        assertTrue(storageDoc.contains(encodedHeader))
    }

    @Test
    fun `round trips command shape and feedback stats with unicode text`() {
        val commandRecord =
            commandStats(
                commandLine = "echo cafe \uD83D\uDE80",
                profileId = "pwsh",
                workingDirectoryUri = "file:///C:/work space",
            )
        val shapeRecord =
            TerminalCommandShapeStats(
                shape =
                    TerminalCommandLineShape(
                        executable = "git",
                        subcommands = listOf("log"),
                        optionNames = listOf("--stat"),
                        positionalArgumentCount = 1,
                    ),
                profileId = "bash",
                workingDirectoryUri = "file:///repo",
                useCount = 3,
                successCount = 2,
                failureCount = 1,
                acceptedCount = 1,
                dismissedCount = 1,
                lastUsedEpochMillis = 200,
            )
        val feedbackRecord =
            TerminalCompletionFeedbackStats(
                source = "spec",
                candidateKind = TerminalCompletionCandidateKind.SUBCOMMAND,
                profileId = "bash",
                workingDirectoryUri = "file:///repo",
                acceptedCount = 2,
                dismissedCount = 1,
                lastUsedEpochMillis = 900,
            )
        val snapshot =
            TerminalCommandCompletionStatsSnapshot(
                commandStats = listOf(commandRecord),
                shapeStats = listOf(shapeRecord),
                feedbackStats = listOf(feedbackRecord),
            )

        val lines = CompletionLearningSnapshotCodec.encode(snapshot)

        assertEquals("KetraTerm_COMMAND_COMPLETION_STATS\t1", lines.first())
        assertFalse(lines.joinToString("\n").contains(commandRecord.commandLine))
        assertEquals(8, lines.last().split('\t').size)
        assertEquals(snapshot, CompletionLearningSnapshotCodec.decode(lines))
    }

    @Test
    fun `legacy feedback position is ignored while the remaining provider context is retained`() {
        val legacyRow = legacyFeedbackRow(position = "REMOVED_POSITION", acceptedCount = 2, lastUsedEpochMillis = 900)

        assertEquals(
            listOf(
                TerminalCompletionFeedbackStats(
                    source = "spec",
                    candidateKind = TerminalCompletionCandidateKind.SUBCOMMAND,
                    profileId = "bash",
                    workingDirectoryUri = "file:///repo",
                    acceptedCount = 2,
                    dismissedCount = 1,
                    lastUsedEpochMillis = 900,
                ),
            ),
            CompletionLearningSnapshotCodec.decode(listOf(HEADER, legacyRow)).feedbackStats,
        )
    }

    @Test
    fun `legacy rows differing only by position collapse to the newest provider feedback`() {
        val path = createTempDirectory("completion-legacy-feedback").resolve(TerminalCompletionLearningRepository.currentFileName())
        Files.write(
            path,
            listOf(
                HEADER,
                legacyFeedbackRow(position = "SUBCOMMAND", acceptedCount = 1, lastUsedEpochMillis = 100),
                legacyFeedbackRow(position = "ARGUMENT", acceptedCount = 3, lastUsedEpochMillis = 300),
            ),
            StandardCharsets.UTF_8,
        )
        val learning = TerminalCompletionLearningStore()

        val loaded = assertIs<CompletionLearningFileLoadOutcome.Loaded>(CompletionLearningFileStore(path).loadSnapshot())
        learning.replaceSnapshot(loaded.snapshot)

        assertEquals(1, learning.snapshot().feedbackStats.size)
        assertEquals(
            3,
            learning
                .snapshot()
                .feedbackStats
                .single()
                .acceptedCount,
        )
        assertEquals(
            300,
            learning
                .snapshot()
                .feedbackStats
                .single()
                .lastUsedEpochMillis,
        )
    }

    @Test
    fun `unknown header returns empty snapshot`() {
        val lines = listOf("KetraTerm_COMMAND_COMPLETION_STATS\t999", commandRow(commandStats("git status")))

        assertEquals(TerminalCommandCompletionStatsSnapshot(), CompletionLearningSnapshotCodec.decode(lines))
    }

    @Test
    fun `unknown malformed and invalid rows are ignored independently`() {
        val valid = commandStats("git status")
        val invalidBase64 =
            listOf("C", "$$$", "", "", "1", "1", "0", "0", "0", "100").joinToString("\t")
        val invalidCounter =
            listOf("C", encodeText("bad"), "", "", "-1", "0", "0", "0", "0", "100").joinToString("\t")
        val invalidFeedback =
            listOf("F", encodeText("spec"), "NOT_A_KIND", "", "", "1", "0", "100").joinToString("\t")
        val lines =
            listOf(
                HEADER,
                "X\tignored",
                "malformed",
                invalidBase64,
                invalidCounter,
                invalidFeedback,
                commandRow(valid),
            )

        assertEquals(
            TerminalCommandCompletionStatsSnapshot(commandStats = listOf(valid)),
            CompletionLearningSnapshotCodec.decode(lines),
        )
    }

    @Test
    fun `encoded rows omit derived command and shape keys`() {
        val commandRecord = commandStats("Git Status")
        val shapeRecord =
            TerminalCommandShapeStats(
                shape =
                    TerminalCommandLineShape(
                        executable = "git",
                        subcommands = listOf("log"),
                        optionNames = listOf("--stat"),
                        positionalArgumentCount = 1,
                    ),
                lastUsedEpochMillis = 100,
            )

        val lines =
            CompletionLearningSnapshotCodec.encode(
                TerminalCommandCompletionStatsSnapshot(
                    commandStats = listOf(commandRecord),
                    shapeStats = listOf(shapeRecord),
                ),
            )

        assertEquals(10, lines[1].split('\t').size)
        assertEquals(14, lines[2].split('\t').size)
        assertFalse(lines[1].contains(encodeText(commandRecord.normalizedCommandLine)))
        assertFalse(lines[2].contains(encodeText(shapeRecord.shape.normalizedShapeKey)))
    }

    @Test
    fun `sensitive argument text is not written by shape rows`() {
        val privateArgument = "secret-branch"
        val shapeRecord =
            TerminalCommandShapeStats(
                shape =
                    TerminalCommandLineShape(
                        executable = "git",
                        subcommands = listOf("log"),
                        optionNames = listOf("--stat"),
                        positionalArgumentCount = 1,
                    ),
                lastUsedEpochMillis = 200,
            )

        val lines =
            CompletionLearningSnapshotCodec.encode(
                TerminalCommandCompletionStatsSnapshot(shapeStats = listOf(shapeRecord)),
            )

        assertTrue(lines.none { it.contains(privateArgument) })
        assertEquals(
            TerminalCommandCompletionStatsSnapshot(shapeStats = listOf(shapeRecord)),
            CompletionLearningSnapshotCodec.decode(lines),
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

    private fun legacyFeedbackRow(
        position: String,
        acceptedCount: Int,
        lastUsedEpochMillis: Long,
    ): String =
        listOf(
            "F",
            encodeText("spec"),
            TerminalCompletionCandidateKind.SUBCOMMAND.name,
            position,
            encodeText("bash"),
            encodeText("file:///repo"),
            acceptedCount.toString(),
            "1",
            lastUsedEpochMillis.toString(),
        ).joinToString("\t")

    private fun encodeText(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private companion object {
        private const val HEADER = "KetraTerm_COMMAND_COMPLETION_STATS\t1"
        private val workingDirectory: Path = Paths.get("").toAbsolutePath()
        private val repositoryRoot: Path =
            if (Files.isRegularFile(workingDirectory.resolve("docs/persistent-terminal-storage.md"))) {
                workingDirectory
            } else {
                workingDirectory.parent
            }
    }
}
