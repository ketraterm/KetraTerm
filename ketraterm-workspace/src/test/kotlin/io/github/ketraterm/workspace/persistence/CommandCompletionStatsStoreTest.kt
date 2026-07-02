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
package io.github.ketraterm.workspace.persistence

import io.github.ketraterm.completion.api.TerminalCompletionCandidateKind
import io.github.ketraterm.completion.model.*
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals

class CommandCompletionStatsStoreTest {
    @Test
    fun `persists versioned compact stats and reloads unicode text`(
        @TempDir directory: Path,
    ) {
        val path = directory.resolve("completion-stats.tsv")
        val record =
            stats(
                commandLine = "echo hello world",
                profileId = "pwsh",
                workingDirectoryUri = "file:///C:/work space",
                useCount = 4,
                successCount = 3,
                failureCount = 1,
                acceptedCount = 2,
                dismissedCount = 1,
                lastUsedEpochMillis = 1_234L,
            )

        CommandCompletionStatsStore(path).use { store ->
            store.persist(TerminalCommandCompletionStatsSnapshot(commandStats = listOf(record)))
            store.flush()
        }

        CommandCompletionStatsStore(path).use { reloaded ->
            assertEquals(TerminalCommandCompletionStatsSnapshot(commandStats = listOf(record)), reloaded.loadSnapshot())
        }
        assertEquals(false, Files.readString(path).contains(record.commandLine))
    }

    @Test
    fun `persists shape stats without raw positional arguments`(
        @TempDir directory: Path,
    ) {
        val path = directory.resolve("completion-stats.tsv")
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
                lastUsedEpochMillis = 500,
            )

        CommandCompletionStatsStore(path).use { store ->
            store.persist(TerminalCommandCompletionStatsSnapshot(shapeStats = listOf(shapeRecord)))
            store.flush()
        }

        CommandCompletionStatsStore(path).use { reloaded ->
            assertEquals(TerminalCommandCompletionStatsSnapshot(shapeStats = listOf(shapeRecord)), reloaded.loadSnapshot())
        }
        val persisted = Files.readString(path)
        assertEquals(false, persisted.contains("secret-branch"))
    }

    @Test
    fun `persists source-specific feedback stats`(
        @TempDir directory: Path,
    ) {
        val path = directory.resolve("completion-stats.tsv")
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

        CommandCompletionStatsStore(path).use { store ->
            store.persist(TerminalCommandCompletionStatsSnapshot(feedbackStats = listOf(feedbackRecord)))
            store.flush()
        }

        CommandCompletionStatsStore(path).use { reloaded ->
            assertEquals(TerminalCommandCompletionStatsSnapshot(feedbackStats = listOf(feedbackRecord)), reloaded.loadSnapshot())
        }
    }

    @Test
    fun `sanitizes exact command stats before persisting`(
        @TempDir directory: Path,
    ) {
        val path = directory.resolve("completion-stats.tsv")
        val safe = stats(commandLine = "git status")
        val privateByWhitespace = stats(commandLine = " export SECRET_TOKEN=123")
        val privateByKeyword = stats(commandLine = "docker login --password hunter2")

        CommandCompletionStatsStore(path).use { store ->
            store.persist(TerminalCommandCompletionStatsSnapshot(commandStats = listOf(safe, privateByWhitespace, privateByKeyword)))
            store.flush()
        }

        CommandCompletionStatsStore(path).use { reloaded ->
            assertEquals(TerminalCommandCompletionStatsSnapshot(commandStats = listOf(safe)), reloaded.loadSnapshot())
        }
        val persisted = Files.readString(path)
        assertEquals(false, persisted.contains(encodeText(privateByWhitespace.commandLine)))
        assertEquals(false, persisted.contains(encodeText(privateByKeyword.commandLine)))
    }

    @Test
    fun `later persisted snapshot replaces earlier file contents`(
        @TempDir directory: Path,
    ) {
        val path = directory.resolve("completion-stats.tsv")
        val first = stats(commandLine = "git status")
        val second = stats(commandLine = "npm test")

        CommandCompletionStatsStore(path).use { store ->
            store.persist(TerminalCommandCompletionStatsSnapshot(commandStats = listOf(first)))
            store.flush()
            store.persist(TerminalCommandCompletionStatsSnapshot(commandStats = listOf(second)))
            store.flush()
        }

        CommandCompletionStatsStore(path).use { reloaded ->
            assertEquals(TerminalCommandCompletionStatsSnapshot(commandStats = listOf(second)), reloaded.loadSnapshot())
        }
    }

    @Test
    fun `close flushes latest persisted snapshot`(
        @TempDir directory: Path,
    ) {
        val path = directory.resolve("completion-stats.tsv")
        val record = stats(commandLine = "git status")

        CommandCompletionStatsStore(path).use { store ->
            store.persist(TerminalCommandCompletionStatsSnapshot(commandStats = listOf(record)))
        }

        CommandCompletionStatsStore(path).use { reloaded ->
            assertEquals(TerminalCommandCompletionStatsSnapshot(commandStats = listOf(record)), reloaded.loadSnapshot())
        }
    }

    @Test
    fun `persist after close is ignored`(
        @TempDir directory: Path,
    ) {
        val path = directory.resolve("completion-stats.tsv")
        val first = stats(commandLine = "git status")
        val afterClose = stats(commandLine = "npm test")
        val store = CommandCompletionStatsStore(path)
        store.persist(TerminalCommandCompletionStatsSnapshot(commandStats = listOf(first)))
        store.close()

        store.persist(TerminalCommandCompletionStatsSnapshot(commandStats = listOf(afterClose)))
        store.flush()

        CommandCompletionStatsStore(path).use { reloaded ->
            assertEquals(TerminalCommandCompletionStatsSnapshot(commandStats = listOf(first)), reloaded.loadSnapshot())
        }
    }

    private fun stats(
        commandLine: String,
        profileId: String? = null,
        workingDirectoryUri: String? = null,
        useCount: Int = 1,
        successCount: Int = 1,
        failureCount: Int = 0,
        acceptedCount: Int = 0,
        dismissedCount: Int = 0,
        lastUsedEpochMillis: Long = 100L,
    ): TerminalCommandCompletionStats =
        TerminalCommandCompletionStats(
            commandLine = commandLine,
            profileId = profileId,
            workingDirectoryUri = workingDirectoryUri,
            useCount = useCount,
            successCount = successCount,
            failureCount = failureCount,
            acceptedCount = acceptedCount,
            dismissedCount = dismissedCount,
            lastUsedEpochMillis = lastUsedEpochMillis,
        )

    private fun encodeText(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))
}
