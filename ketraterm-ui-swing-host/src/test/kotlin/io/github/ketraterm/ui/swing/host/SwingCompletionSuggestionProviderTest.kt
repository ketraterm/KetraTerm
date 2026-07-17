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
package io.github.ketraterm.ui.swing.host

import io.github.ketraterm.completion.api.*
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SwingCompletionSuggestionProviderTest {
    @Test
    fun `forwards live host context and adapts candidates`() {
        lateinit var captured: TerminalCompletionRequest
        val provider =
            SwingCompletionSuggestionProvider(
                engine =
                    TerminalCompletionEngine { request ->
                        captured = request
                        listOf(
                            TerminalCompletionCandidate(
                                replacementText = "status",
                                replacementStartOffset = 4,
                                replacementEndOffset = 6,
                                source = "spec",
                                kind = TerminalCompletionCandidateKind.SUBCOMMAND,
                                displayText = "status",
                                detail = "show status",
                            ),
                        )
                    },
                contextProvider = {
                    SwingCompletionContext(
                        profileId = "bash",
                        workingDirectoryUri = "file:///repo",
                        shellCapabilities = TerminalShellCapabilities.POSIX,
                    )
                },
            )

        val suggestions = provider.suggestions(request("git st", cursorOffset = 6))

        assertEquals("bash", captured.profileId)
        assertEquals("file:///repo", captured.workingDirectoryUri)
        assertEquals(TerminalShellCapabilities.POSIX, captured.shellCapabilities)
        assertEquals("status", suggestions.single().replacementText)
        assertEquals("SUBCOMMAND", suggestions.single().kind)
    }

    @Test
    fun `invalid UTF-16 cursor is rejected before engine invocation`() {
        var invoked = false
        val provider =
            SwingCompletionSuggestionProvider(
                TerminalCompletionEngine {
                    invoked = true
                    emptyList()
                },
            )

        assertTrue(provider.suggestions(request("😀", cursorOffset = 1)).isEmpty())
        assertEquals(false, invoked)
    }

    private fun request(
        commandText: String,
        cursorOffset: Int,
    ): SwingShellSuggestionRequest =
        SwingShellSuggestionRequest(
            commandText = commandText,
            cursorOffset = cursorOffset,
            anchorColumn = 0,
            anchorRow = 0,
        )
}
