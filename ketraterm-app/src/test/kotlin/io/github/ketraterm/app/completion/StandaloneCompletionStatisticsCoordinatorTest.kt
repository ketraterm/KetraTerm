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
package io.github.ketraterm.app.completion

import io.github.ketraterm.completion.api.TerminalCompletionSources
import io.github.ketraterm.completion.model.TerminalCommandSpec
import io.github.ketraterm.completion.model.TerminalCommandSpecs
import io.github.ketraterm.completion.persistence.TerminalCompletionStatsStore
import io.github.ketraterm.ui.swing.suggestion.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class StandaloneCompletionStatisticsCoordinatorTest {
    @Test
    fun `feedback is sanitized persisted reloaded and changes global ranking`(
        @TempDir tempDirectory: Path,
    ) {
        val path = tempDirectory.resolve(TerminalCompletionStatsStore.currentFileName())
        val liveSource = TerminalCompletionSources.learningStore(commandSpecs = TerminalCommandSpecs.defaults())

        StandaloneCompletionStatisticsCoordinator(liveSource, path).use { coordinator ->
            val handler = coordinator.createFeedbackHandler("bash") { "file:///repo" }
            handler.onSuggestionFeedback(feedback(SwingShellSuggestionFeedbackKind.ACCEPTED, "switch"))
            handler.onSuggestionFeedback(feedback(SwingShellSuggestionFeedbackKind.DISMISSED, "status"))
            handler.onSuggestionFeedback(
                feedback(
                    kind = SwingShellSuggestionFeedbackKind.ACCEPTED,
                    replacementText = "--password hunter2",
                    commandText = "docker login ",
                    replacementStartOffset = 13,
                ),
            )
        }

        val reloaded = TerminalCompletionStatsStore(path).use(TerminalCompletionStatsStore::loadSnapshot)
        assertEquals(setOf("git switch", "git status"), reloaded.commandStats.map { it.commandLine }.toSet())
        assertFalse(reloaded.commandStats.any { "hunter2" in it.commandLine })

        val restartedSource = TerminalCompletionSources.learningStore(commandSpecs = TerminalCommandSpecs.defaults())
        restartedSource.replaceSnapshot(reloaded)
        val registry =
            StandaloneCompletionRegistry(
                specs =
                    listOf(
                        TerminalCommandSpec(
                            name = "git",
                            subcommands = listOf(TerminalCommandSpec("status"), TerminalCommandSpec("switch")),
                        ),
                    ),
                persistentStatsSource = restartedSource,
            )
        try {
            val provider =
                registry.createProvider(
                    sessionId = "restarted",
                    profileId = "bash",
                    workingDirectoryUriProvider = { "file:///repo" },
                )
            val request = SwingShellSuggestionRequest("git ", 4, anchorColumn = 0, anchorRow = 0)

            assertEquals(
                listOf("git switch", "git status"),
                provider.suggestions(request).mapNotNull { it.commandTextAfterReplacement(request) }.take(2),
            )
        } finally {
            registry.close()
        }
    }

    @Test
    fun `disabling persistence stops subsequent learning from reaching disk`(
        @TempDir tempDirectory: Path,
    ) {
        val path = tempDirectory.resolve(TerminalCompletionStatsStore.currentFileName())
        val source = TerminalCompletionSources.learningStore()

        StandaloneCompletionStatisticsCoordinator(source, path).use { coordinator ->
            coordinator.recordFinishedCommand(
                commandLine = "git status",
                successful = true,
                profileId = "bash",
                workingDirectoryUri = "file:///repo",
                usedAtEpochMillis = 1_000L,
            )
            coordinator.setPersistencePath(null)
            coordinator.recordFinishedCommand(
                commandLine = "npm test",
                successful = true,
                profileId = "bash",
                workingDirectoryUri = "file:///repo",
                usedAtEpochMillis = 2_000L,
            )
        }

        assertEquals(setOf("git status", "npm test"), source.snapshot().map { it.commandLine }.toSet())
        TerminalCompletionStatsStore(path).use { reloaded ->
            assertEquals(listOf("git status"), reloaded.loadSnapshot().commandStats.map { it.commandLine })
        }
    }

    private fun feedback(
        kind: SwingShellSuggestionFeedbackKind,
        replacementText: String,
        commandText: String = "git s",
        replacementStartOffset: Int = 4,
    ): SwingShellSuggestionFeedback {
        val request =
            SwingShellSuggestionRequest(
                commandText = commandText,
                cursorOffset = commandText.length,
                anchorColumn = 0,
                anchorRow = 0,
            )
        return SwingShellSuggestionFeedback(
            kind = kind,
            suggestion =
                SwingShellSuggestion(
                    replacementText = replacementText,
                    replacementStartOffset = replacementStartOffset,
                    replacementEndOffset = commandText.length,
                    source = "spec",
                    kind = "SUBCOMMAND",
                ),
            index = 0,
            request = request,
        )
    }
}
