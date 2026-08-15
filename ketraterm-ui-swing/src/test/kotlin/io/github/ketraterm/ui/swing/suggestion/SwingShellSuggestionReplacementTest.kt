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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SwingShellSuggestionReplacementTest {
    @Test
    fun `constructor rejects invalid suggestion contract fields`() {
        assertFailsWith<IllegalArgumentException> {
            suggestion(replacementText = "")
        }
        assertFailsWith<IllegalArgumentException> {
            suggestion(startOffset = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            suggestion(startOffset = 5, endOffset = 4)
        }
        assertFailsWith<IllegalArgumentException> {
            suggestion(source = " ")
        }
        assertFailsWith<IllegalArgumentException> {
            suggestion(kind = "")
        }
    }

    @Test
    fun `replacement plan includes command text projection and edit counts`() {
        val suggestion =
            suggestion(
                replacementText = "checkout",
                startOffset = 4,
                endOffset = 7,
            )

        val request = request("git che", cursorOffset = 6)
        val replacement = suggestion.replacementFor(request)!!

        assertEquals(4, replacement.startOffset)
        assertEquals(7, replacement.endOffset)
        assertEquals(2, replacement.deleteBeforeCursorCount)
        assertEquals(1, replacement.deleteAfterCursorCount)
        assertEquals("git checkout", suggestion.commandTextAfterReplacement(request))
    }

    @Test
    fun `replacement plan deletes surrogate pairs by grapheme cluster`() {
        val commandText = "echo \uD83D\uDE02"
        val suggestion =
            suggestion(
                replacementText = "ok",
                startOffset = 5,
                endOffset = commandText.length,
                kind = "ARGUMENT",
            )

        val replacement = suggestion.replacementFor(request(commandText))!!

        assertEquals(1, replacement.deleteBeforeCursorCount)
        assertEquals(0, replacement.deleteAfterCursorCount)
        assertEquals("echo ok", suggestion.commandTextAfterReplacement(request(commandText)))
    }

    @Test
    fun `replacement plan deletes combining accents by grapheme cluster`() {
        val commandText = "echo e\u0301"
        val suggestion =
            suggestion(
                replacementText = "ok",
                startOffset = 5,
                endOffset = commandText.length,
                kind = "ARGUMENT",
            )

        val replacement = suggestion.replacementFor(request(commandText))!!

        assertEquals(1, replacement.deleteBeforeCursorCount)
        assertEquals("echo ok", suggestion.commandTextAfterReplacement(request(commandText)))
    }

    @Test
    fun `replacement range must contain cursor`() {
        val suggestion =
            suggestion(
                replacementText = "checkout",
                startOffset = 4,
                endOffset = 7,
            )

        assertNull(suggestion.replacementFor(request("git checkout", cursorOffset = 12)))
        assertNull(suggestion.replacementFor(request("git checkout", cursorOffset = 3)))
        assertNull(suggestion.commandTextAfterReplacement(request("git checkout", cursorOffset = 12)))
    }

    @Test
    fun `replacement range rejects offsets outside command text`() {
        val suggestion =
            suggestion(
                replacementText = "status",
                startOffset = 4,
                endOffset = 99,
            )

        assertNull(suggestion.replacementFor(request("git s")))
        assertNull(suggestion.commandTextAfterReplacement(request("git s")))
    }

    @Test
    fun `replacement range rejects surrogate pair splits`() {
        val commandText = "echo \uD83D\uDE02"
        val startSplit =
            suggestion(
                replacementText = "emoji",
                startOffset = 6,
                endOffset = commandText.length,
            )
        val endSplit =
            suggestion(
                replacementText = "emoji",
                startOffset = 5,
                endOffset = 6,
            )

        assertNull(startSplit.replacementFor(request(commandText)))
        assertNull(endSplit.replacementFor(request(commandText, cursorOffset = 5)))
    }

    @Test
    fun `replacement rejects request cursor that splits surrogate pair`() {
        val commandText = "echo \uD83D\uDE02"
        val suggestion =
            suggestion(
                replacementText = "emoji",
                startOffset = 5,
                endOffset = commandText.length,
            )

        assertNull(suggestion.replacementFor(request(commandText, cursorOffset = 6)))
        assertNull(suggestion.commandTextAfterReplacement(request(commandText, cursorOffset = 6)))
    }

    private fun suggestion(
        replacementText: String = "status",
        startOffset: Int = 4,
        endOffset: Int = 5,
        source: String = "spec",
        kind: String = "SUBCOMMAND",
    ): SwingShellSuggestion =
        SwingShellSuggestion(
            replacementText = replacementText,
            replacementStartOffset = startOffset,
            replacementEndOffset = endOffset,
            source = source,
            kind = kind,
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
