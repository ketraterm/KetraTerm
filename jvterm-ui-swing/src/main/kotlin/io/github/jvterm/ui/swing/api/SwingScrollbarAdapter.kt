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
package io.github.jvterm.ui.swing.api

import java.awt.event.AdjustmentEvent
import javax.swing.JScrollBar
import kotlin.math.roundToInt

/**
 * Bridges a top-origin Swing scrollbar to a row-aligned terminal viewport.
 *
 * The scrollbar retains pixel-scale coordinates, allowing its thumb to move
 * continuously during a drag. Every input value is converted to a whole
 * top-row target before the shared animator moves the terminal viewport. Terminal publications do
 * not overwrite the raw thumb coordinate while Swing reports an active drag.
 * Standalone and IDE hosts can therefore share one scrolling policy while
 * retaining full control over scrollbar appearance.
 *
 * @param scrollbar host-owned vertical scrollbar.
 */
class SwingScrollbarAdapter(
    private val scrollbar: JScrollBar,
) : TerminalViewportListener {
    private var viewportScroller: SwingSmoothScroller? = null
    private var updatingFromTerminal = false
    private var historySize = 0
    private var visualRangePixels = 0
    private var cellHeightPixels = 1
    private var alignedValue = 0

    init {
        scrollbar.addAdjustmentListener(::handleAdjustment)
    }

    /**
     * Attaches the terminal controlled by this adapter.
     *
     * @param viewportScroller row-aligned viewport destination.
     */
    fun attach(viewportScroller: SwingSmoothScroller) {
        this.viewportScroller = viewportScroller
    }

    override fun viewportChanged(
        historySize: Int,
        scrollbackOffset: Double,
        renderOffset: Int,
        visibleRows: Int,
        requestedRows: Int,
    ) {
        publish(
            historySize = historySize,
            visualScrollOffsetPixels = scrollbackOffset,
            viewportHeightPixels = maxOf(1, visibleRows),
            cellHeightPixels = 1,
        )
    }

    override fun viewportStateChanged(state: TerminalViewportState) {
        publish(
            historySize = state.historySize,
            visualScrollOffsetPixels = state.visualScrollOffsetPixels,
            viewportHeightPixels = maxOf(1, state.viewportHeightPixels),
            cellHeightPixels = state.cellHeightPixels,
        )
    }

    private fun publish(
        historySize: Int,
        visualScrollOffsetPixels: Double,
        viewportHeightPixels: Int,
        cellHeightPixels: Int,
    ) {
        this.historySize = historySize
        this.cellHeightPixels = cellHeightPixels
        visualRangePixels = saturatedMultiply(historySize, cellHeightPixels)
        alignedValue =
            (visualRangePixels - visualScrollOffsetPixels.roundToInt())
                .coerceIn(0, visualRangePixels)
        val maximum = saturatedAdd(visualRangePixels, viewportHeightPixels)
        val displayedValue = if (scrollbar.valueIsAdjusting) scrollbar.value.coerceIn(0, visualRangePixels) else alignedValue

        updatingFromTerminal = true
        try {
            scrollbar.isVisible = historySize > 0
            scrollbar.model.setRangeProperties(
                displayedValue,
                viewportHeightPixels,
                0,
                maximum,
                scrollbar.valueIsAdjusting,
            )
            scrollbar.blockIncrement = viewportHeightPixels
            scrollbar.unitIncrement = cellHeightPixels
        } finally {
            updatingFromTerminal = false
        }
    }

    private fun handleAdjustment(event: AdjustmentEvent) {
        if (updatingFromTerminal || historySize == 0) return

        val topRow = (event.value.coerceIn(0, visualRangePixels) / cellHeightPixels).coerceIn(0, historySize)
        val targetOffset = historySize - topRow
        alignedValue = saturatedMultiply(topRow, cellHeightPixels)
        viewportScroller?.smoothScrollToScrollbackOffset(targetOffset)

        if (!event.valueIsAdjusting && scrollbar.value != alignedValue) {
            updatingFromTerminal = true
            try {
                scrollbar.value = alignedValue
            } finally {
                updatingFromTerminal = false
            }
        }
    }

    private companion object {
        fun saturatedMultiply(
            left: Int,
            right: Int,
        ): Int = (left.toLong() * right.toLong()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

        fun saturatedAdd(
            left: Int,
            right: Int,
        ): Int = (left.toLong() + right.toLong()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}

/** Destination for whole-row scrollbar viewport requests. */
fun interface SwingSmoothScroller {
    /**
     * Smoothly moves the viewport to [scrollbackOffset] terminal rows above live output.
     *
     * @param scrollbackOffset non-negative whole-row scrollback offset.
     */
    fun smoothScrollToScrollbackOffset(scrollbackOffset: Int)
}
