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

import io.github.ketraterm.completion.api.*
import io.github.ketraterm.completion.model.TerminalCommandSpec
import io.github.ketraterm.completion.model.TerminalOptionSpec
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StandaloneCompletionSuggestionProviderTest {
    @Test
    fun `adapts completion candidates to swing suggestions with replacement ranges`() {
        val provider = provider()

        val suggestions = provider.suggestions(request("git c"))

        assertEquals(listOf("commit", "checkout"), suggestions.map { it.replacementText })
        assertEquals(listOf(4, 4), suggestions.map { it.replacementStartOffset })
        assertEquals(listOf(5, 5), suggestions.map { it.replacementEndOffset })
        assertEquals(listOf("spec", "spec"), suggestions.map { it.source })
        assertEquals(listOf("SUBCOMMAND", "SUBCOMMAND"), suggestions.map { it.kind })
    }

    @Test
    fun `preserves candidate ranges that replace text after the cursor`() {
        val provider = provider()

        val suggestions = provider.suggestions(request("git che", cursorOffset = 6))

        val suggestion = suggestions.single()
        assertEquals("checkout", suggestion.replacementText)
        assertEquals(4, suggestion.replacementStartOffset)
        assertEquals(7, suggestion.replacementEndOffset)
        assertEquals("spec", suggestion.source)
        assertEquals("SUBCOMMAND", suggestion.kind)
    }

    @Test
    fun `preserves option detail`() {
        val provider = provider()

        val suggestion = provider.suggestions(request("git --h")).single()

        assertEquals("--help", suggestion.replacementText)
        assertEquals("show help", suggestion.detail)
        assertEquals(4, suggestion.replacementStartOffset)
        assertEquals(7, suggestion.replacementEndOffset)
    }

    @Test
    fun `passes standalone context to completion engine`() {
        val requests = ArrayList<TerminalCompletionRequest>()
        val provider =
            StandaloneCompletionSuggestionProvider(
                engine = { request ->
                    requests += request
                    listOf(
                        TerminalCompletionCandidate(
                            replacementText = "git status",
                            replacementStartOffset = 0,
                            replacementEndOffset = request.commandLine.length,
                            source = "test",
                            kind = TerminalCompletionCandidateKind.COMMAND,
                        ),
                    )
                },
                contextProvider = {
                    StandaloneCompletionSuggestionContext(
                        profileId = "bash",
                        workingDirectoryUri = "file:///repo",
                    )
                },
            )

        provider.suggestions(request("git"))

        assertEquals("bash", requests.single().profileId)
        assertEquals("file:///repo", requests.single().workingDirectoryUri)
    }

    @Test
    fun `malformed cursor request returns no suggestions`() {
        val provider = provider()

        val suggestions = provider.suggestions(request("a\uD83D\uDE02", cursorOffset = 2))

        assertTrue(suggestions.isEmpty())
    }

    private fun provider(): StandaloneCompletionSuggestionProvider =
        StandaloneCompletionSuggestionProvider(
            TerminalCompletionEngines.fromSources(
                TerminalCompletionSources.fromSpecs(
                    listOf(
                        TerminalCommandSpec(
                            name = "git",
                            subcommands =
                                listOf(
                                    TerminalCommandSpec("commit", "record changes"),
                                    TerminalCommandSpec("checkout", "switch branches"),
                                ),
                            options =
                                listOf(
                                    TerminalOptionSpec(listOf("--help"), "show help"),
                                ),
                        ),
                    ),
                ),
            ),
        )

    private fun request(
        commandText: String,
        cursorOffset: Int = commandText.length,
    ): SwingShellSuggestionRequest =
        SwingShellSuggestionRequest(
            commandText = commandText,
            cursorOffset = cursorOffset,
            anchorColumn = cursorOffset,
            anchorRow = 0,
        )
}
