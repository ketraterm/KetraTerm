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
                normalizedCommandLine = "echo hello world",
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
            store.persist(listOf(record))
            store.flush()
        }

        CommandCompletionStatsStore(path).use { reloaded ->
            assertEquals(listOf(record), reloaded.load())
        }
        assertEquals(false, Files.readString(path).contains(record.commandLine))
    }

    @Test
    fun `ignores malformed and invalid rows independently`(
        @TempDir directory: Path,
    ) {
        val path = directory.resolve("completion-stats.tsv")
        val valid = stats(commandLine = "git status", normalizedCommandLine = "git status")
        CommandCompletionStatsStore(path).use { store ->
            store.persist(listOf(valid))
            store.flush()
        }
        Files.writeString(
            path,
            Files.readString(path) +
                "malformed\n" +
                invalidNegativeCounterLine() +
                "\n",
        )

        CommandCompletionStatsStore(path).use { reloaded ->
            assertEquals(listOf(valid), reloaded.load())
        }
    }

    @Test
    fun `later persisted snapshot replaces earlier file contents`(
        @TempDir directory: Path,
    ) {
        val path = directory.resolve("completion-stats.tsv")
        val first = stats(commandLine = "git status", normalizedCommandLine = "git status")
        val second = stats(commandLine = "npm test", normalizedCommandLine = "npm test")

        CommandCompletionStatsStore(path).use { store ->
            store.persist(listOf(first))
            store.flush()
            store.persist(listOf(second))
            store.flush()
        }

        CommandCompletionStatsStore(path).use { reloaded ->
            assertEquals(listOf(second), reloaded.load())
        }
    }

    private fun stats(
        commandLine: String,
        normalizedCommandLine: String,
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
            normalizedCommandLine = normalizedCommandLine,
            profileId = profileId,
            workingDirectoryUri = workingDirectoryUri,
            useCount = useCount,
            successCount = successCount,
            failureCount = failureCount,
            acceptedCount = acceptedCount,
            dismissedCount = dismissedCount,
            lastUsedEpochMillis = lastUsedEpochMillis,
        )

    private fun invalidNegativeCounterLine(): String =
        listOf(
            encodeText("bad"),
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

    private fun encodeText(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(StandardCharsets.UTF_8))
}
