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
package io.github.ketraterm.completion.model

import io.github.ketraterm.completion.api.TerminalCompletionCandidateKind
import io.github.ketraterm.completion.commandline.classifyGenericCommandLineShape
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalCommandCompletionStatsSnapshotCodecTest {
    @Test
    fun `current file name header and docs share the same format version`() {
        val encodedHeader = TerminalCommandCompletionStatsSnapshotCodec.encode(TerminalCommandCompletionStatsSnapshot()).first()
        val storageDoc = Files.readString(repositoryRoot.resolve("docs/persistent-terminal-storage.md"))

        assertEquals("command-completion-stats-v1.tsv", TerminalCommandCompletionStatsSnapshotCodec.currentFileName())
        assertEquals("KetraTerm_COMMAND_COMPLETION_STATS\t1", encodedHeader)
        assertTrue(storageDoc.contains("`${TerminalCommandCompletionStatsSnapshotCodec.currentFileName()}`"))
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
                shape = classifyGenericCommandLineShape("git log --stat main")!!,
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
                tokenPosition = TerminalCompletionTokenPosition.SUBCOMMAND,
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

        val lines = TerminalCommandCompletionStatsSnapshotCodec.encode(snapshot)

        assertEquals("KetraTerm_COMMAND_COMPLETION_STATS\t1", lines.first())
        assertFalse(lines.joinToString("\n").contains(commandRecord.commandLine))
        assertEquals(snapshot, TerminalCommandCompletionStatsSnapshotCodec.decode(lines))
    }

    @Test
    fun `unknown header returns empty snapshot`() {
        val lines = listOf("KetraTerm_COMMAND_COMPLETION_STATS\t999", commandRow(commandStats("git status")))

        assertEquals(TerminalCommandCompletionStatsSnapshot(), TerminalCommandCompletionStatsSnapshotCodec.decode(lines))
    }

    @Test
    fun `unknown and malformed rows are ignored independently`() {
        val valid = commandStats("git status")
        val lines =
            listOf(
                "KetraTerm_COMMAND_COMPLETION_STATS\t1",
                "X\tignored",
                "malformed",
                commandRow(valid),
            )

        assertEquals(
            TerminalCommandCompletionStatsSnapshot(commandStats = listOf(valid)),
            TerminalCommandCompletionStatsSnapshotCodec.decode(lines),
        )
    }

    @Test
    fun `invalid base64 row is ignored independently`() {
        val valid = commandStats("git status")
        val invalidBase64 =
            listOf(
                "C",
                "$$$",
                "",
                "",
                "1",
                "1",
                "0",
                "0",
                "0",
                "100",
            ).joinToString("\t")
        val lines = listOf("KetraTerm_COMMAND_COMPLETION_STATS\t1", invalidBase64, commandRow(valid))

        assertEquals(
            TerminalCommandCompletionStatsSnapshot(commandStats = listOf(valid)),
            TerminalCommandCompletionStatsSnapshotCodec.decode(lines),
        )
    }

    @Test
    fun `invalid counters and enum names are ignored independently`() {
        val feedbackRecord =
            TerminalCompletionFeedbackStats(
                source = "spec",
                candidateKind = TerminalCompletionCandidateKind.SUBCOMMAND,
                tokenPosition = TerminalCompletionTokenPosition.SUBCOMMAND,
                acceptedCount = 1,
                lastUsedEpochMillis = 100,
            )
        val lines =
            listOf(
                "KetraTerm_COMMAND_COMPLETION_STATS\t1",
                invalidNegativeCounterCommandRow(),
                malformedFeedbackRow(),
            ) +
                TerminalCommandCompletionStatsSnapshotCodec
                    .encode(TerminalCommandCompletionStatsSnapshot(feedbackStats = listOf(feedbackRecord)))
                    .drop(1)

        assertEquals(
            TerminalCommandCompletionStatsSnapshot(feedbackStats = listOf(feedbackRecord)),
            TerminalCommandCompletionStatsSnapshotCodec.decode(lines),
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
            TerminalCommandCompletionStatsSnapshotCodec.encode(
                TerminalCommandCompletionStatsSnapshot(
                    commandStats = listOf(commandRecord),
                    shapeStats = listOf(shapeRecord),
                ),
            )

        assertEquals(10, lines[1].split('\t').size)
        assertEquals(14, lines[2].split('\t').size)
        assertFalse(lines[1].contains(encodeText(commandRecord.normalizedCommandLine)))
        assertFalse(lines[2].contains(encodeText(shapeRecord.shape.normalizedShapeKey)))
        assertEquals(
            TerminalCommandCompletionStatsSnapshot(
                commandStats = listOf(commandRecord),
                shapeStats = listOf(shapeRecord),
            ),
            TerminalCommandCompletionStatsSnapshotCodec.decode(lines),
        )
    }

    @Test
    fun `sensitive argument text is not written by shape rows`() {
        val shapeRecord =
            TerminalCommandShapeStats(
                shape = classifyGenericCommandLineShape("git log --stat secret-branch")!!,
                lastUsedEpochMillis = 200,
            )

        val lines =
            TerminalCommandCompletionStatsSnapshotCodec.encode(
                TerminalCommandCompletionStatsSnapshot(shapeStats = listOf(shapeRecord)),
            )

        assertTrue(lines.none { it.contains("secret-branch") })
        assertEquals(
            TerminalCommandCompletionStatsSnapshot(shapeStats = listOf(shapeRecord)),
            TerminalCommandCompletionStatsSnapshotCodec.decode(lines),
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

    private fun invalidNegativeCounterCommandRow(): String =
        listOf(
            "C",
            encodeText("bad"),
            "",
            "",
            "-1",
            "0",
            "0",
            "0",
            "0",
            "100",
        ).joinToString("\t")

    private fun malformedFeedbackRow(): String =
        listOf(
            "F",
            encodeText("spec"),
            "NOT_A_KIND",
            TerminalCompletionTokenPosition.SUBCOMMAND.name,
            "",
            "",
            "1",
            "0",
            "100",
        ).joinToString("\t")

    private fun encodeText(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private companion object {
        private val workingDirectory: Path = Paths.get("").toAbsolutePath()
        private val repositoryRoot: Path =
            if (Files.isRegularFile(workingDirectory.resolve("docs/persistent-terminal-storage.md"))) {
                workingDirectory
            } else {
                workingDirectory.parent
            }
    }
}
