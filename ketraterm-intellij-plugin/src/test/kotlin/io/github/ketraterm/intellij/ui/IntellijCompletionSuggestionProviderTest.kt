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
package io.github.ketraterm.intellij.ui

import io.github.ketraterm.completion.api.*
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionRequest
import org.junit.Assert.assertEquals
import org.junit.Test

class IntellijCompletionSuggestionProviderTest {
    @Test
    fun `provider forwards live IntelliJ context and preserves candidate presentation`() {
        var captured: TerminalCompletionRequest? = null
        var directory = "file:///project-a"
        val provider =
            IntellijCompletionSuggestionProvider(
                engine =
                    TerminalCompletionEngine { request ->
                        captured = request
                        listOf(
                            TerminalCompletionCandidate(
                                replacementText = "--help",
                                replacementStartOffset = 4,
                                replacementEndOffset = 7,
                                kind = TerminalCompletionCandidateKind.OPTION,
                                source = "spec",
                                displayText = "--help",
                                detail = "Show help",
                            ),
                        )
                    },
                contextProvider = {
                    IntellijCompletionContext(
                        profileId = "pwsh",
                        workingDirectoryUri = directory,
                        shellCapabilities = TerminalShellCapabilities.POWERSHELL,
                    )
                },
            )

        val first = provider.suggestions(request("git --h")).single()
        assertEquals("pwsh", captured?.profileId)
        assertEquals("file:///project-a", captured?.workingDirectoryUri)
        assertEquals(TerminalShellCapabilities.POWERSHELL, captured?.shellCapabilities)
        assertEquals("Show help", first.detail)
        assertEquals(4, first.replacementStartOffset)

        directory = "file:///project-b"
        provider.suggestions(request("git --h"))
        assertEquals("file:///project-b", captured?.workingDirectoryUri)
    }

    private fun request(commandText: String): SwingShellSuggestionRequest =
        SwingShellSuggestionRequest(commandText, commandText.length, anchorColumn = 2, anchorRow = 3)
}
