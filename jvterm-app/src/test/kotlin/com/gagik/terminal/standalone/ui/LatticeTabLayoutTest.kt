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
package com.gagik.terminal.standalone.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LatticeTabLayoutTest {
    @Test
    fun firstTabLeftPaddingMatchesTopPadding() {
        assertEquals(LatticeTabMetrics.TAB_TOP_PADDING, LatticeTabMetrics.TAB_START_X)
    }

    @Test
    fun preferredTabWidthIsFixed() {
        assertEquals(220, LatticeTabLayoutCalculator.preferredTabWidth())
        assertEquals(220, LatticeTabLayoutCalculator.preferredTabWidth())
    }

    @Test
    fun computeReturnsStableEmptyLayout() {
        val layout =
            LatticeTabLayoutCalculator.compute(
                componentWidth = 120,
                preferredWidths = emptyList(),
                previousScrollOffset = 70,
            )

        assertEquals(emptyList(), layout.tabWidths)
        assertEquals(0, layout.scrollOffset)
        assertEquals(0, layout.maxScrollOffset)
        assertEquals(52, layout.availableTabWidth)
        assertEquals(LatticeTabMetrics.TAB_START_X, layout.actionButtonStartX)
    }

    @Test
    fun computeKeepsPreferredWidthsWhenTabsFit() {
        val layout =
            LatticeTabLayoutCalculator.compute(
                componentWidth = 600,
                preferredWidths = listOf(120, 140),
                previousScrollOffset = 50,
            )

        assertEquals(listOf(120, 140), layout.tabWidths)
        assertEquals(0, layout.scrollOffset)
        assertEquals(0, layout.maxScrollOffset)
        assertFalse(layout.scrollButtonsVisible)
        assertEquals(
            LatticeTabMetrics.TAB_START_X + 120 + LatticeTabMetrics.TAB_GAP + 140,
            layout.actionButtonStartX,
        )
    }

    @Test
    fun computeDoesNotForceScrollWhenSmallPreferredTabsFit() {
        val layout =
            LatticeTabLayoutCalculator.compute(
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
            LatticeTabLayoutCalculator.compute(
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
            LatticeTabLayoutCalculator.compute(
                componentWidth = 260,
                preferredWidths = listOf(180, 160, 140),
                previousScrollOffset = 999,
            )

        assertEquals(listOf(100, 100, 100), layout.tabWidths)
        assertTrue(layout.scrollButtonsVisible)
        assertEquals(144, layout.availableTabWidth)
        assertEquals(164, layout.maxScrollOffset)
        assertEquals(164, layout.scrollOffset)
        assertEquals(LatticeTabMetrics.TAB_START_X + layout.availableTabWidth, layout.actionButtonStartX)
    }

    @Test
    fun computeClampsNegativeScrollOffset() {
        val layout =
            LatticeTabLayoutCalculator.compute(
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
            LatticeTabLayoutCalculator.compute(
                componentWidth = 20,
                preferredWidths = listOf(120, 120),
                previousScrollOffset = 0,
            )

        assertEquals(0, layout.availableTabWidth)
        assertEquals(204, layout.maxScrollOffset)
        assertEquals(LatticeTabMetrics.TAB_START_X, layout.actionButtonStartX)
    }

    @Test
    fun visibleTabRangeClipsAgainstViewportAndScrollOffset() {
        val layout =
            LatticeTabLayout(
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
            LatticeTabLayout(
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
