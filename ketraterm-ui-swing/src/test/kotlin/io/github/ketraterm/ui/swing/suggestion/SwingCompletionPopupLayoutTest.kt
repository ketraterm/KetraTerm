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

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.Font
import javax.swing.JPanel

class SwingCompletionPopupLayoutTest {
    @Test
    fun `prepares normalized width-bounded presentation rows`() {
        val component = component()
        val layout = prepareLayout(component)
        val displayText = "checkout-a-provider-controlled-branch-name-that-is-far-too-long"
        val detail = "  provider\n detail that must be normalized and truncated  "

        layout.prepare(
            component = component,
            suggestions = listOf(suggestion(displayText = displayText, detail = detail, sourceDisplayText = " Recent commands ")),
            appearance = appearance(component),
            availableWidth = 180,
        )

        val row = layout.row(0)
        assertEquals(1, layout.rowCount)
        assertTrue(row.sourceText.startsWith("RECENT"))
        assertFalse(row.sourceText.contains('\n'))
        assertTrue(row.displayText.endsWith('…'))
        assertTrue(row.displayText.length < displayText.length)
        assertFalse(row.detailText.startsWith(' '))
        assertFalse(row.detailText.endsWith(' '))
        assertFalse(row.detailText.contains('\n'))
    }

    @Test
    fun `caps prepared rows and clears stale rows`() {
        val component = component()
        val layout = prepareLayout(component)
        val suggestions = List(12) { index -> suggestion(displayText = "candidate-$index") }

        layout.prepare(component, suggestions, appearance(component), availableWidth = 440)

        assertEquals(SwingShellSuggestionViewSnapshot.MAX_VISIBLE_SUGGESTIONS, layout.rowCount)
        assertEquals("candidate-7", layout.row(7).displayText)

        layout.clear()

        assertEquals(0, layout.rowCount)
        assertThrows(IllegalArgumentException::class.java) { layout.row(0) }
    }

    @Test
    fun `uses presentation source instead of reparsing provider identifier`() {
        val component = component()
        val layout = prepareLayout(component)

        layout.prepare(
            component,
            listOf(suggestion("candidate", source = "intellij-git-branch", sourceDisplayText = "Git")),
            appearance(component),
            availableWidth = 440,
        )

        assertEquals("GIT", layout.row(0).sourceText)
        assertFalse(layout.row(0).sourceText.contains("INTELLIJ"))
    }

    @Test
    fun `ellipsizing retains complete extended grapheme clusters`() {
        val component = component()
        val layout = prepareLayout(component)
        val cluster = "\uD83D\uDC69\u200D\uD83D\uDCBB"
        val displayText = cluster.repeat(32)

        layout.prepare(
            component,
            listOf(suggestion(displayText, sourceDisplayText = "Path")),
            appearance(component),
            availableWidth = 150,
        )

        val preparedPrefix = layout.row(0).displayText.removeSuffix("…")
        assertTrue(layout.row(0).displayText.endsWith('…'))
        assertEquals(0, preparedPrefix.length % cluster.length)
        assertTrue(preparedPrefix.hasValidSurrogatePairs())
    }

    @Test
    fun `preferred width adapts within bounded popup dimensions`() {
        val component = component()
        val layout = prepareLayout(component)

        layout.prepare(
            component,
            listOf(suggestion("a-very-long-command-name-with-useful-context")),
            appearance(component),
            availableWidth = SWING_COMPLETION_POPUP_MAX_WIDTH,
        )

        assertTrue(layout.preferredWidth in SWING_COMPLETION_POPUP_MIN_WIDTH..SWING_COMPLETION_POPUP_MAX_WIDTH)
        assertTrue(layout.preferredWidth > SWING_COMPLETION_POPUP_MIN_WIDTH)
    }

    @Test
    fun `font metrics determine row height and baselines`() {
        val component = component(fontSize = 56)
        val layout = prepareLayout(component)

        layout.prepare(component, listOf(suggestion("candidate")), appearance(component), availableWidth = 440)

        val metrics = component.getFontMetrics(component.font)
        assertTrue(layout.rowHeight >= metrics.height + 10)
        assertTrue(layout.primaryBaseline >= metrics.ascent)
        assertTrue(layout.primaryBaseline + metrics.descent <= layout.rowHeight)
    }

    @Test
    fun `short component window follows the selected row`() {
        val component = component()
        val layout = prepareLayout(component)
        layout.prepare(
            component,
            List(8) { index -> suggestion("candidate-$index") },
            appearance(component),
            availableWidth = 440,
        )
        val twoRowsHigh = SwingCompletionPopupLayout.SURFACE_PADDING * 2 + layout.rowHeight * 2

        assertEquals(2, layout.visibleRowCapacity(twoRowsHigh))
        assertEquals(5, layout.firstVisibleRow(twoRowsHigh, selectedIndex = 6))
        assertEquals(0, layout.firstVisibleRow(twoRowsHigh, selectedIndex = -1))
    }

    @Test
    fun `match ranges remain within grapheme-safe truncated prefix`() {
        val component = component(fontSize = 15)
        val layout = prepareLayout(component)
        val displayText = "buildReleaseWithAnExtremelyLongSuffix"

        layout.prepare(
            component,
            listOf(suggestion(displayText, matchedOffsets = intArrayOf(0, 1, 5, 8, 16, 17))),
            appearance(component),
            availableWidth = 150,
        )

        val row = layout.row(0)
        val retainedLength = row.displayText.removeSuffix("…").length
        var rangeIndex = 0
        while (rangeIndex < row.matchedRanges.rangeCount) {
            assertTrue(row.matchedRanges.endOffset(rangeIndex) <= retainedLength)
            rangeIndex++
        }
    }

    @Test
    fun `hostile strings have bounded prepared presentation`() {
        val component = component()
        val layout = prepareLayout(component)

        layout.prepare(
            component,
            listOf(
                suggestion(
                    displayText = "a".repeat(20_000),
                    detail = "b".repeat(20_000),
                    sourceDisplayText = "c".repeat(20_000),
                ),
            ),
            appearance(component),
            availableWidth = 440,
        )

        val row = layout.row(0)
        assertTrue(row.displayText.length <= 4097)
        assertTrue(row.detailText.length <= 1025)
        assertTrue(row.sourceText.length <= 129)
        assertTrue(row.sourceBadgeWidth <= 120)
        assertTrue(row.tooltipText.length < 2300)
    }

    @Test
    fun `single hostile grapheme cannot bypass raw presentation bounds`() {
        val component = component()
        val layout = prepareLayout(component)
        val hostileCluster = "a" + "\u0301".repeat(20_000)

        layout.prepare(
            component,
            listOf(suggestion(hostileCluster, detail = hostileCluster, sourceDisplayText = hostileCluster)),
            appearance(component),
            availableWidth = 440,
        )

        val row = layout.row(0)
        assertEquals("…", row.displayText)
        assertEquals("…", row.detailText)
        assertEquals("…", row.sourceText)
    }

    @Test
    fun `unsafe line and bidi controls are neutralized before display`() {
        val component = component()
        val layout = prepareLayout(component)

        layout.prepare(
            component,
            listOf(
                suggestion(
                    displayText = "git\u0000status\u202E",
                    detail = "show\nstatus\u2066now",
                    sourceDisplayText = "Built\tin\u200F",
                ),
            ),
            appearance(component),
            availableWidth = 440,
        )

        val row = layout.row(0)
        assertEquals("git�status�", row.displayText)
        assertEquals("show status now", row.detailText)
        assertEquals("BUILT IN", row.sourceText)
    }

    @Test
    fun `semantic accent roles survive layout preparation`() {
        val component = component()
        val layout = prepareLayout(component)

        layout.prepare(
            component,
            listOf(
                suggestion("build/", kind = "PATH"),
                suggestion("--help", kind = "OPTION"),
                suggestion("git status", source = "history", kind = "HISTORY"),
            ),
            appearance(component),
            availableWidth = 440,
        )

        assertEquals(SwingShellSuggestionAccentRole.PATH, layout.row(0).accentRole)
        assertEquals(SwingShellSuggestionAccentRole.OPTION, layout.row(1).accentRole)
        assertEquals(SwingShellSuggestionAccentRole.HISTORY, layout.row(2).accentRole)
    }

    private fun component(fontSize: Int = 13): JPanel =
        JPanel().apply {
            font = Font(Font.MONOSPACED, Font.PLAIN, fontSize)
            background = Color(0x10, 0x14, 0x18)
            foreground = Color(0xE5, 0xE7, 0xEB)
        }

    private fun appearance(component: JPanel): SwingCompletionPopupAppearance =
        SwingCompletionPopupAppearanceResolver.resolve(component.font, component.background, component.foreground)

    private fun prepareLayout(component: JPanel): SwingCompletionPopupLayout =
        SwingCompletionPopupLayout().apply {
            prepare(component, emptyList(), appearance(component), SWING_COMPLETION_POPUP_MAX_WIDTH)
        }

    private fun suggestion(
        displayText: String,
        detail: String = "",
        source: String = "spec",
        sourceDisplayText: String = source,
        kind: String = "COMMAND",
        matchedOffsets: IntArray = IntArray(0),
    ): SwingShellSuggestion =
        SwingShellSuggestion(
            replacementText = displayText,
            replacementStartOffset = 0,
            replacementEndOffset = 0,
            source = source,
            kind = kind,
            displayText = displayText,
            detail = detail,
            matchedRanges = SwingShellSuggestionMatchRanges.fromPackedOffsets(displayText, matchedOffsets),
            sourceDisplayText = sourceDisplayText,
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
