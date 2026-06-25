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
package io.github.ketraterm.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TabLayoutTest {
    @Test
    fun firstTabLeftPaddingMatchesTopPadding() {
        assertEquals(TabMetrics.TAB_TOP_PADDING, TabMetrics.TAB_START_X)
    }

    @Test
    fun preferredTabWidthIsFixed() {
        assertEquals(220, TabLayoutCalculator.preferredTabWidth())
        assertEquals(220, TabLayoutCalculator.preferredTabWidth())
    }

    @Test
    fun computeReturnsStableEmptyLayout() {
        val layout =
            TabLayoutCalculator.compute(
                componentWidth = 120,
                preferredWidths = emptyList(),
                previousScrollOffset = 70,
            )

        assertEquals(emptyList(), layout.tabWidths)
        assertEquals(0, layout.scrollOffset)
        assertEquals(0, layout.maxScrollOffset)
        assertEquals(52, layout.availableTabWidth)
        assertEquals(TabMetrics.TAB_START_X, layout.actionButtonStartX)
    }

    @Test
    fun computeKeepsPreferredWidthsWhenTabsFit() {
        val layout =
            TabLayoutCalculator.compute(
                componentWidth = 600,
                preferredWidths = listOf(120, 140),
                previousScrollOffset = 50,
            )

        assertEquals(listOf(120, 140), layout.tabWidths)
        assertEquals(0, layout.scrollOffset)
        assertEquals(0, layout.maxScrollOffset)
        assertFalse(layout.scrollButtonsVisible)
        assertEquals(
            TabMetrics.TAB_START_X + 120 + TabMetrics.TAB_GAP + 140,
            layout.actionButtonStartX,
        )
    }

    @Test
    fun computeDoesNotForceScrollWhenSmallPreferredTabsFit() {
        val layout =
            TabLayoutCalculator.compute(
                componentWidth = 260,
                preferredWidths = listOf(40, 40, 40),
                previousScrollOffset = 0,
            )

        assertEquals(listOf(40, 40, 40), layout.tabWidths)
        assertEquals(0, layout.maxScrollOffset)
        assertFalse(layout.scrollButtonsVisible)
    }

    @Test
    fun computeShrinksTabsWithoutScrollWhenMinimumWidthsFit() {
        val layout =
            TabLayoutCalculator.compute(
                componentWidth = 420,
                preferredWidths = listOf(180, 160, 140),
                previousScrollOffset = 0,
            )

        assertEquals(listOf(115, 115, 114), layout.tabWidths)
        assertEquals(0, layout.maxScrollOffset)
        assertFalse(layout.scrollButtonsVisible)
    }

    @Test
    fun computeUsesMinimumWidthsAndScrollButtonsWhenTabsCannotFit() {
        val layout =
            TabLayoutCalculator.compute(
                componentWidth = 260,
                preferredWidths = listOf(180, 160, 140),
                previousScrollOffset = 999,
            )

        assertEquals(listOf(100, 100, 100), layout.tabWidths)
        assertTrue(layout.scrollButtonsVisible)
        assertEquals(144, layout.availableTabWidth)
        assertEquals(164, layout.maxScrollOffset)
        assertEquals(164, layout.scrollOffset)
        assertEquals(TabMetrics.TAB_START_X + layout.availableTabWidth, layout.actionButtonStartX)
    }

    @Test
    fun computeClampsNegativeScrollOffset() {
        val layout =
            TabLayoutCalculator.compute(
                componentWidth = 260,
                preferredWidths = listOf(180, 160, 140),
                previousScrollOffset = -40,
            )

        assertEquals(0, layout.scrollOffset)
        assertEquals(164, layout.maxScrollOffset)
    }

    @Test
    fun computeHandlesNarrowComponentWithoutNegativeViewport() {
        val layout =
            TabLayoutCalculator.compute(
                componentWidth = 20,
                preferredWidths = listOf(120, 120),
                previousScrollOffset = 0,
            )

        assertEquals(0, layout.availableTabWidth)
        assertEquals(204, layout.maxScrollOffset)
        assertEquals(TabMetrics.TAB_START_X, layout.actionButtonStartX)
    }

    @Test
    fun visibleTabRangeClipsAgainstViewportAndScrollOffset() {
        val layout =
            TabLayout(
                tabWidths = listOf(100, 100, 100),
                scrollOffset = 80,
                maxScrollOffset = 120,
                availableTabWidth = 144,
                actionButtonStartX = 152,
            )

        assertEquals(Pair(8, 28), layout.visibleTabRange(0))
        assertEquals(Pair(32, 132), layout.visibleTabRange(1))
        assertEquals(Pair(136, 152), layout.visibleTabRange(2))
    }

    @Test
    fun visibleTabRangeReturnsNullForHiddenOrUnknownTabs() {
        val layout =
            TabLayout(
                tabWidths = listOf(100, 100),
                scrollOffset = 240,
                maxScrollOffset = 240,
                availableTabWidth = 80,
                actionButtonStartX = 88,
            )

        assertEquals(null, layout.visibleTabRange(0))
        assertEquals(null, layout.visibleTabRange(9))
    }
}
