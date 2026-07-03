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
package io.github.ketraterm.ui.swing.api

import java.awt.event.AdjustmentEvent
import javax.swing.JScrollBar
import kotlin.math.roundToInt

/**
 * Bridges a top-origin Swing scrollbar to the shared terminal viewport.
 *
 * The scrollbar retains pixel-scale coordinates, allowing its thumb to move
 * continuously during direct manipulation. Each thumb position maps to an
 * integer top row, which is applied immediately with no easing lag. Terminal
 * publications do not overwrite the host-owned thumb coordinate during a
 * drag, and release is already row-aligned.
 *
 * The scrollbar remains visible even when no history is retained so host
 * layouts reserve its width before the first scrollback-producing command.
 * Hiding it after session start would shrink the terminal component and can
 * make PTY/core width synchronization lag by one cell.
 *
 * @param scrollbar host-owned vertical scrollbar.
 */
class SwingScrollbarAdapter(
    private val scrollbar: JScrollBar,
) : TerminalViewportListener {
    private var viewportScroller: SwingScrollbarScroller? = null
    private var updatingFromTerminal = false
    private var historySize = 0
    private var visualRangePixels = 0
    private var cellHeightPixels = 1
    private var viewportHeightPixels = 1

    init {
        scrollbar.isVisible = true
        scrollbar.isEnabled = false
        scrollbar.addAdjustmentListener(::handleAdjustment)
    }

    /**
     * Attaches the terminal controlled by this adapter.
     *
     * @param viewportScroller shared row-scrolling destination.
     */
    fun attach(viewportScroller: SwingScrollbarScroller) {
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
            visualScrollOffsetPixels = scrollbackOffset * cellHeightPixels,
            viewportHeightPixels = maxOf(viewportHeightPixels, saturatedMultiply(visibleRows, cellHeightPixels)),
            cellHeightPixels = cellHeightPixels,
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
        this.viewportHeightPixels = viewportHeightPixels
        visualRangePixels = saturatedMultiply(historySize, cellHeightPixels)
        val publishedValue =
            (visualRangePixels - visualScrollOffsetPixels.roundToInt())
                .coerceIn(0, visualRangePixels)
        val maximum = saturatedAdd(visualRangePixels, viewportHeightPixels)
        val displayedValue = if (scrollbar.valueIsAdjusting) scrollbar.value.coerceIn(0, visualRangePixels) else publishedValue

        updatingFromTerminal = true
        try {
            scrollbar.isVisible = true
            scrollbar.isEnabled = historySize > 0
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

        val value = event.value.coerceIn(0, visualRangePixels)
        val topRow = (value / cellHeightPixels).coerceIn(0, historySize)
        val targetOffset = historySize - topRow
        viewportScroller?.scrollFromScrollbar(targetOffset, event.valueIsAdjusting)
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

/** Destination for scrollbar positions mapped to terminal rows. */
fun interface SwingScrollbarScroller {
    /**
     * Moves the viewport to [scrollbackOffset] terminal rows above live output.
     *
     * While [valueIsAdjusting] is true, implementations must apply the row
     * immediately so content never lags behind the thumb. A release publishes
     * the same already-aligned row as a completed scroll.
     *
     * @param scrollbackOffset non-negative integer scrollback offset.
     * @param valueIsAdjusting whether the scrollbar thumb is actively held.
     */
    fun scrollFromScrollbar(
        scrollbackOffset: Int,
        valueIsAdjusting: Boolean,
    )
}
