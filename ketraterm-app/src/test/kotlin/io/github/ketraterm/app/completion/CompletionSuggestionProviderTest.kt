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

import io.github.ketraterm.completion.TerminalCommandSpec
import io.github.ketraterm.completion.TerminalCompletionEngines
import io.github.ketraterm.completion.TerminalOptionSpec
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionRequest
import kotlin.test.Test
import kotlin.test.assertEquals

class CompletionSuggestionProviderTest {
    @Test
    fun `adapts completion candidates to swing suggestions with replacement ranges`() {
        val provider = provider()

        val suggestions = provider.suggestions(request("git c"))

        assertEquals(listOf("commit", "checkout"), suggestions.map { it.replacementText })
        assertEquals(listOf(4, 4), suggestions.map { it.replacementStartOffset })
        assertEquals(listOf(5, 5), suggestions.map { it.replacementEndOffset })
        assertEquals(listOf(-1, -1), suggestions.map { it.deleteCount })
        assertEquals(listOf("spec", "spec"), suggestions.map { it.source })
    }

    @Test
    fun `preserves candidate ranges that replace text after the cursor`() {
        val provider = provider()

        val suggestions = provider.suggestions(request("git che", cursorOffset = 6))

        val suggestion = suggestions.single()
        assertEquals("checkout", suggestion.replacementText)
        assertEquals(4, suggestion.replacementStartOffset)
        assertEquals(7, suggestion.replacementEndOffset)
        assertEquals(-1, suggestion.deleteCount)
    }

    @Test
    fun `preserves option detail`() {
        val provider = provider()

        val suggestion = provider.suggestions(request("git --")).single()

        assertEquals("--help", suggestion.replacementText)
        assertEquals("show help", suggestion.detail)
        assertEquals(4, suggestion.replacementStartOffset)
        assertEquals(6, suggestion.replacementEndOffset)
    }

    private fun provider(): CompletionSuggestionProvider =
        CompletionSuggestionProvider(
            TerminalCompletionEngines.fromSpecs(
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
