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

import java.awt.BasicStroke

/**
 * Shared geometry and stroke constants for the standalone tab strip.
 */
internal object LatticeTabMetrics {
    const val TAB_BAR_HEIGHT = 40
    const val TAB_TOP_PADDING = 8
    const val TAB_BOTTOM_PADDING = 0
    const val TAB_START_X = 8
    const val TAB_GAP = 4
    const val TAB_LABEL_PADDING_LEFT = 12
    const val TAB_PADDING_RIGHT = 6
    const val ICON_TEXT_GAP = 20
    const val CLOSE_BUTTON_SIZE = 16
    const val CLOSE_BUTTON_MARGIN_RIGHT = 6
    const val SCROLL_BUTTON_WIDTH = 24
    const val NEW_TAB_BUTTON_WIDTH = 28
    const val MENU_BUTTON_WIDTH = 24
    const val TRAILING_SPACE = 8
    const val CORNER_RADIUS = 10f
    const val MIN_LABEL_TEXT_WIDTH = 50
    const val MAX_LABEL_TEXT_WIDTH = 160
    const val MIN_TAB_WIDTH = 100
    val HAIRLINE_STROKE: BasicStroke = BasicStroke(1f)
    val DIVIDER_STROKE: BasicStroke = BasicStroke(1.2f)
    val ICON_DETAIL_STROKE: BasicStroke = BasicStroke(1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
    val ICON_STROKE: BasicStroke = BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
    val ACTION_ICON_STROKE: BasicStroke = BasicStroke(1.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
}

/**
 * Immutable tab-strip geometry derived from the component width and tabs.
 */
internal data class LatticeTabLayout(
    val tabWidths: List<Int>,
    val scrollOffset: Int,
    val maxScrollOffset: Int,
    val availableTabWidth: Int,
    val actionButtonStartX: Int,
) {
    val scrollButtonsVisible: Boolean
        get() = maxScrollOffset > 0

    /**
     * Returns the absolute x position of the tab at [index], before applying
     * horizontal scroll offset.
     */
    fun tabX(index: Int): Int {
        var tabX = LatticeTabMetrics.TAB_START_X
        for (i in 0 until index) {
            tabX += tabWidths[i] + LatticeTabMetrics.TAB_GAP
        }
        return tabX
    }

    /**
     * Returns the visible x range of [index] in component-local coordinates.
     */
    fun visibleTabRange(index: Int): Pair<Int, Int>? {
        val tabWidth = tabWidths.getOrNull(index) ?: return null
        val relativeLeft = tabX(index) - scrollOffset
        val visibleStart = maxOf(relativeLeft, LatticeTabMetrics.TAB_START_X)
        val visibleEnd = minOf(relativeLeft + tabWidth, LatticeTabMetrics.TAB_START_X + availableTabWidth)
        if (visibleStart >= visibleEnd) return null
        return Pair(visibleStart, visibleEnd)
    }

    internal companion object {
        fun totalTabContentWidth(tabWidths: List<Int>): Int = tabWidths.sum() + maxOf(0, tabWidths.size - 1) * LatticeTabMetrics.TAB_GAP
    }
}

/**
 * Calculates tab widths and action-button placement for [LatticeTabBar].
 */
internal object LatticeTabLayoutCalculator {
    fun preferredTabWidth(titleTextWidth: Int): Int {
        val textWidth =
            titleTextWidth.coerceIn(
                LatticeTabMetrics.MIN_LABEL_TEXT_WIDTH,
                LatticeTabMetrics.MAX_LABEL_TEXT_WIDTH,
            )
        return LatticeTabMetrics.TAB_LABEL_PADDING_LEFT +
            LatticeTabMetrics.ICON_TEXT_GAP +
            textWidth +
            LatticeTabMetrics.CLOSE_BUTTON_SIZE +
            LatticeTabMetrics.CLOSE_BUTTON_MARGIN_RIGHT +
            LatticeTabMetrics.TAB_PADDING_RIGHT
    }

    fun compute(
        componentWidth: Int,
        preferredWidths: List<Int>,
        previousScrollOffset: Int,
    ): LatticeTabLayout {
        if (preferredWidths.isEmpty()) {
            return LatticeTabLayout(
                tabWidths = emptyList(),
                scrollOffset = 0,
                maxScrollOffset = 0,
                availableTabWidth = spaceWithoutScrollButtons(componentWidth),
                actionButtonStartX = LatticeTabMetrics.TAB_START_X,
            )
        }

        val tabWidths = allocateTabWidths(componentWidth, preferredWidths)
        val totalWidth = LatticeTabLayout.totalTabContentWidth(tabWidths)
        val availableWithoutScroll = spaceWithoutScrollButtons(componentWidth)
        val preferredTotal = LatticeTabLayout.totalTabContentWidth(preferredWidths)
        val minimumTotal =
            preferredWidths.size * LatticeTabMetrics.MIN_TAB_WIDTH +
                (preferredWidths.size - 1) * LatticeTabMetrics.TAB_GAP
        val needsScroll = preferredTotal > availableWithoutScroll && minimumTotal > availableWithoutScroll
        val availableForTabs =
            if (needsScroll) {
                spaceWithScrollButtons(componentWidth)
            } else {
                availableWithoutScroll
            }
        val maxScrollOffset = if (needsScroll) maxOf(0, totalWidth - availableForTabs) else 0
        val scrollOffset = previousScrollOffset.coerceIn(0, maxScrollOffset)
        val actionButtonStartX =
            LatticeTabMetrics.TAB_START_X +
                if (needsScroll) {
                    availableForTabs
                } else {
                    totalWidth
                }

        return LatticeTabLayout(
            tabWidths = tabWidths,
            scrollOffset = scrollOffset,
            maxScrollOffset = maxScrollOffset,
            availableTabWidth = availableForTabs,
            actionButtonStartX = actionButtonStartX,
        )
    }

    private fun allocateTabWidths(
        componentWidth: Int,
        preferredWidths: List<Int>,
    ): List<Int> {
        val count = preferredWidths.size
        val availableWithoutScroll = spaceWithoutScrollButtons(componentWidth)
        val totalPreferred = LatticeTabLayout.totalTabContentWidth(preferredWidths)
        if (totalPreferred <= availableWithoutScroll) return preferredWidths

        val totalMinimum = count * LatticeTabMetrics.MIN_TAB_WIDTH + (count - 1) * LatticeTabMetrics.TAB_GAP
        if (totalMinimum > availableWithoutScroll) {
            return List(count) { LatticeTabMetrics.MIN_TAB_WIDTH }
        }

        val widths = IntArray(count)
        var remainingSpace = availableWithoutScroll - (count - 1) * LatticeTabMetrics.TAB_GAP
        var remainingCount = count
        val sortedIndices = preferredWidths.indices.sortedBy { preferredWidths[it] }

        for (index in sortedIndices) {
            val preferred = preferredWidths[index]
            val fairShare = remainingSpace / remainingCount
            val width = minOf(preferred, fairShare)
            widths[index] = width
            remainingSpace -= width
            remainingCount--
        }
        return widths.toList()
    }

    private fun spaceWithoutScrollButtons(componentWidth: Int): Int =
        (
            componentWidth -
                LatticeTabMetrics.TAB_START_X -
                LatticeTabMetrics.NEW_TAB_BUTTON_WIDTH -
                LatticeTabMetrics.MENU_BUTTON_WIDTH -
                LatticeTabMetrics.TRAILING_SPACE
        ).coerceAtLeast(0)

    private fun spaceWithScrollButtons(componentWidth: Int): Int =
        (spaceWithoutScrollButtons(componentWidth) - LatticeTabMetrics.SCROLL_BUTTON_WIDTH * 2).coerceAtLeast(0)
}
