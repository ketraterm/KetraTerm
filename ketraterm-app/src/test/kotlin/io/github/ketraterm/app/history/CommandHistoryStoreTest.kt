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

import io.github.ketraterm.session.TerminalShellIntegrationCommandLifecycle
import io.github.ketraterm.session.TerminalShellIntegrationCommandMetadata
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class CommandHistoryStoreTest {
    @Test
    fun `persists versioned metadata without terminal output and reloads unicode`(
        @TempDir directory: Path,
    ) {
        val path = directory.resolve("history.tsv")
        CommandHistoryStore(path).use { store ->
            store.record(
                profileId = "pwsh",
                metadata = metadata(command = "echo héllo\nworld", exitCode = 7),
            )
            store.flush()
        }

        CommandHistoryStore(path).use { reloaded ->
            assertEquals(
                listOf(
                    CommandHistoryEntry(
                        profileId = "pwsh",
                        command = "echo héllo\nworld",
                        workingDirectoryUri = "file:///C:/work space",
                        exitCode = 7,
                        startedAtEpochMillis = 100L,
                        finishedAtEpochMillis = 250L,
                    ),
                ),
                reloaded.snapshot(),
            )
        }
        val persisted = Files.readString(path)
        check(!persisted.contains("terminal output"))
    }

    @Test
    fun `evicts oldest records at capacity and ignores malformed records`(
        @TempDir directory: Path,
    ) {
        val path = directory.resolve("history.tsv")
        CommandHistoryStore(path, capacity = 2).use { store ->
            store.record("bash", metadata("one", 0))
            store.record("bash", metadata("two", 0))
            store.record("bash", metadata("three", 0))
            store.flush()
        }
        Files.writeString(path, Files.readString(path) + "malformed\n")

        CommandHistoryStore(path, capacity = 2).use { reloaded ->
            assertEquals(listOf("two", "three"), reloaded.snapshot().map(CommandHistoryEntry::command))
        }
    }

    @Test
    fun `filters out sensitive commands and leading whitespace`(
        @TempDir directory: Path,
    ) {
        val path = directory.resolve("history.tsv")
        CommandHistoryStore(path).use { store ->
            // Safe commands
            store.record("bash", metadata("git status", 0))
            store.record("bash", metadata("npm run build", 0))

            // Ignored space/tab commands
            store.record("bash", metadata(" export VAR=val", 0))
            store.record("bash", metadata("\tsecret_command", 0))

            // Sensitive keyword commands
            store.record("bash", metadata("export SECRET_KEY=123", 0))
            store.record("bash", metadata("docker login -u user -p password", 0))
            store.record("bash", metadata("gh auth status", 0))
            store.record("bash", metadata("curl -H 'Authorization: Bearer xyz' url", 0))
            store.record("bash", metadata("private_key_generator --bits 2048", 0))

            store.flush()
        }

        CommandHistoryStore(path).use { reloaded ->
            val commands = reloaded.snapshot().map(CommandHistoryEntry::command)
            assertEquals(listOf("git status", "npm run build"), commands)
        }
    }

    private fun metadata(
        command: String,
        exitCode: Int,
    ): TerminalShellIntegrationCommandMetadata =
        TerminalShellIntegrationCommandMetadata(
            recordId = 1,
            lifecycle = TerminalShellIntegrationCommandLifecycle.FAILED,
            commandText = command,
            workingDirectoryUri = "file:///C:/work space",
            exitCode = exitCode,
            startedAtEpochMillis = 100L,
            finishedAtEpochMillis = 250L,
        )
}
