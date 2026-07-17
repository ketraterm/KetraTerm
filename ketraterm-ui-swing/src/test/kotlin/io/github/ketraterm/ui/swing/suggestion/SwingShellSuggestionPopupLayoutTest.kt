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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Font
import javax.swing.JPanel

class SwingShellSuggestionPopupLayoutTest {
    @Test
    fun `prepares normalized width-bounded presentation rows`() {
        val component = JPanel().apply { font = Font(Font.MONOSPACED, Font.PLAIN, 13) }
        val layout = SwingShellSuggestionPopupLayout()
        val displayText = "checkout-a-provider-controlled-branch-name-that-is-far-too-long"
        val detail = "  provider detail that must be normalized and truncated  "

        layout.prepare(
            component = component,
            suggestions = listOf(suggestion(displayText = displayText, detail = detail, source = " history ")),
            availableWidth = 180,
        )

        val row = layout.row(0)
        assertEquals(1, layout.rowCount)
        assertEquals("HISTORY", row.sourceLabel)
        assertTrue(row.sourceWidth > 0)
        assertTrue(row.displayText.endsWith("..."))
        assertTrue(row.displayText.length < displayText.length)
        assertTrue(row.detail.endsWith("..."))
        assertFalse(row.detail.startsWith(' '))
        assertFalse(row.detail.endsWith(' '))
    }

    @Test
    fun `caps prepared rows and clears stale rows`() {
        val component = JPanel()
        val layout = SwingShellSuggestionPopupLayout()
        val suggestions = List(12) { index -> suggestion(displayText = "candidate-$index") }

        layout.prepare(component, suggestions, availableWidth = 440)

        assertEquals(8, layout.rowCount)
        assertEquals("candidate-7", layout.row(7).displayText)

        layout.prepare(component, emptyList(), availableWidth = 440)

        assertEquals(0, layout.rowCount)
        assertThrows(IllegalArgumentException::class.java) { layout.row(0) }
    }

    @Test
    fun `bounds hostile provider source labels`() {
        val component = JPanel().apply { font = Font(Font.MONOSPACED, Font.PLAIN, 13) }
        val layout = SwingShellSuggestionPopupLayout()

        layout.prepare(
            component = component,
            suggestions =
                listOf(
                    suggestion(
                        displayText = "candidate",
                        source = "provider-source-label-that-is-intentionally-far-too-long-for-the-popup",
                    ),
                ),
            availableWidth = 240,
        )

        val row = layout.row(0)
        assertTrue(row.sourceLabel.endsWith("..."))
        assertTrue(row.sourceWidth <= 112 + 14)
        assertFalse(row.displayText.isEmpty())
    }

    @Test
    fun `ellipsizing never splits a UTF-16 surrogate pair`() {
        val component = JPanel().apply { font = Font(Font.MONOSPACED, Font.PLAIN, 13) }
        val layout = SwingShellSuggestionPopupLayout()
        val displayText = "\uD83D\uDE00".repeat(32)

        layout.prepare(
            component = component,
            suggestions = listOf(suggestion(displayText = displayText, source = "path")),
            availableWidth = 150,
        )

        val prepared = layout.row(0).displayText
        assertTrue(prepared.endsWith("..."))
        assertTrue(prepared.removeSuffix("...").hasValidSurrogatePairs())
    }

    private fun suggestion(
        displayText: String,
        detail: String = "",
        source: String = "spec",
    ): SwingShellSuggestion =
        SwingShellSuggestion(
            replacementText = displayText,
            replacementStartOffset = 0,
            replacementEndOffset = 0,
            source = source,
            kind = "COMMAND",
            displayText = displayText,
            detail = detail,
        )

    private fun String.hasValidSurrogatePairs(): Boolean {
        var index = 0
        while (index < length) {
            val current = this[index]
            when {
                Character.isHighSurrogate(current) -> {
                    if (index + 1 >= length || !Character.isLowSurrogate(this[index + 1])) return false
                    index += 2
                }

                Character.isLowSurrogate(current) -> return false
                else -> index++
            }
        }
        return true
    }
}
