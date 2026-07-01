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
package io.github.ketraterm.ui.swing.suggestion

import kotlin.test.*

class SwingShellSuggestionReplacementTest {
    @Test
    fun `explicit replacement range returns validated exclusive range`() {
        val suggestion =
            SwingShellSuggestion(
                replacementText = "status",
                replacementStartOffset = 4,
                replacementEndOffset = 5,
            )

        val range = suggestion.explicitReplacementRangeFor(request("git s"))!!

        assertTrue(suggestion.hasExplicitReplacementRange())
        assertEquals(4, range.startOffset)
        assertEquals(5, range.endOffset)
    }

    @Test
    fun `explicit replacement plan includes command text projection and edit counts`() {
        val suggestion =
            SwingShellSuggestion(
                replacementText = "checkout",
                replacementStartOffset = 4,
                replacementEndOffset = 7,
            )

        val replacement = suggestion.replacementFor(request("git che", cursorOffset = 6))!!

        assertEquals(4, replacement.startOffset)
        assertEquals(7, replacement.endOffset)
        assertEquals(2, replacement.deleteBeforeCursorCount)
        assertEquals(1, replacement.deleteAfterCursorCount)
        assertEquals("git checkout", suggestion.commandTextAfterReplacement(request("git che", cursorOffset = 6)))
    }

    @Test
    fun `missing explicit replacement range returns null`() {
        val suggestion = SwingShellSuggestion("git status")

        assertFalse(suggestion.hasExplicitReplacementRange())
        assertNull(suggestion.explicitReplacementRangeFor(request("git s")))
    }

    @Test
    fun `default replacement plan replaces whole prefix before cursor`() {
        val suggestion = SwingShellSuggestion("git status")

        val replacement = suggestion.replacementFor(request("git s"))!!

        assertEquals(0, replacement.startOffset)
        assertEquals(5, replacement.endOffset)
        assertEquals(5, replacement.deleteBeforeCursorCount)
        assertEquals(0, replacement.deleteAfterCursorCount)
        assertEquals("git status", suggestion.commandTextAfterReplacement(request("git s")))
    }

    @Test
    fun `default replacement plan applies delete count by grapheme clusters`() {
        val suggestion = SwingShellSuggestion("ok", deleteCount = 1)

        val replacement = suggestion.replacementFor(request("echo \uD83D\uDE02"))!!

        assertEquals(5, replacement.startOffset)
        assertEquals(7, replacement.endOffset)
        assertEquals(1, replacement.deleteBeforeCursorCount)
        assertEquals("echo ok", suggestion.commandTextAfterReplacement(request("echo \uD83D\uDE02")))
    }

    @Test
    fun `default replacement caps delete count to available prefix clusters`() {
        val suggestion = SwingShellSuggestion("status", deleteCount = 99)

        val replacement = suggestion.replacementFor(request("git s"))!!

        assertEquals(0, replacement.startOffset)
        assertEquals(5, replacement.deleteBeforeCursorCount)
        assertEquals("status", suggestion.commandTextAfterReplacement(request("git s")))
    }

    @Test
    fun `explicit replacement range must contain cursor`() {
        val suggestion =
            SwingShellSuggestion(
                replacementText = "checkout",
                replacementStartOffset = 4,
                replacementEndOffset = 7,
            )

        assertNull(suggestion.explicitReplacementRangeFor(request("git checkout", cursorOffset = 12)))
        assertNull(suggestion.explicitReplacementRangeFor(request("git checkout", cursorOffset = 3)))
    }

    @Test
    fun `explicit replacement range rejects offsets outside command text`() {
        val suggestion =
            SwingShellSuggestion(
                replacementText = "status",
                replacementStartOffset = 4,
                replacementEndOffset = 99,
            )

        assertNull(suggestion.explicitReplacementRangeFor(request("git s")))
    }

    @Test
    fun `explicit replacement range rejects surrogate pair splits`() {
        val commandText = "echo \uD83D\uDE02"
        val suggestion =
            SwingShellSuggestion(
                replacementText = "emoji",
                replacementStartOffset = 5,
                replacementEndOffset = 7,
            )

        assertNull(suggestion.explicitReplacementRangeFor(request(commandText, cursorOffset = 6)))
    }

    @Test
    fun `default replacement rejects cursor that splits surrogate pair`() {
        val suggestion = SwingShellSuggestion("emoji")

        assertNull(suggestion.replacementFor(request("echo \uD83D\uDE02", cursorOffset = 6)))
        assertNull(suggestion.commandTextAfterReplacement(request("echo \uD83D\uDE02", cursorOffset = 6)))
    }

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
