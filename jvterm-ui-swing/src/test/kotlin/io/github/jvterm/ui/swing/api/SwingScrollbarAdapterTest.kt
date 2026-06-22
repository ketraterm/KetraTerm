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

import java.awt.Adjustable
import javax.swing.JScrollBar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SwingScrollbarAdapterTest {
    @Test
    fun `drag keeps continuous thumb value while viewport uses whole top row`() {
        val scrollbar = JScrollBar(Adjustable.VERTICAL)
        var requestedOffset = -1
        val adapter = SwingScrollbarAdapter(scrollbar)
        adapter.attach(SwingSmoothScroller { offset -> requestedOffset = offset })
        val state = viewportState(renderOffset = 0)
        adapter.viewportStateChanged(state)

        scrollbar.model.valueIsAdjusting = true
        scrollbar.value = 47

        assertEquals(6, requestedOffset)
        adapter.viewportStateChanged(viewportState(renderOffset = requestedOffset))
        assertEquals(47, scrollbar.value)
        assertTrue(scrollbar.valueIsAdjusting)

        scrollbar.model.valueIsAdjusting = false

        assertEquals(40, scrollbar.value)
        assertEquals(6, requestedOffset)
    }

    @Test
    fun `terminal publication uses pixel scale with row-sized increments`() {
        val scrollbar = JScrollBar(Adjustable.VERTICAL)
        val adapter = SwingScrollbarAdapter(scrollbar)

        adapter.viewportStateChanged(viewportState(renderOffset = 3))

        assertTrue(scrollbar.isVisible)
        assertEquals(70, scrollbar.value)
        assertEquals(10, scrollbar.unitIncrement)
        assertEquals(30, scrollbar.blockIncrement)
        assertEquals(130, scrollbar.maximum)
    }

    @Test
    fun `fractional smooth viewport publishes precise thumb position`() {
        val scrollbar = JScrollBar(Adjustable.VERTICAL)
        val adapter = SwingScrollbarAdapter(scrollbar)

        adapter.viewportStateChanged(viewportState(renderOffset = 3, scrollbackOffset = 2.5))

        assertEquals(75, scrollbar.value)
    }

    private fun viewportState(
        renderOffset: Int,
        scrollbackOffset: Double = renderOffset.toDouble(),
    ): TerminalViewportState =
        TerminalViewportState(
            historySize = 10,
            scrollbackOffset = scrollbackOffset,
            renderOffset = renderOffset,
            visibleRows = 3,
            requestedRows = 3,
            visualScrollOffsetPixels = scrollbackOffset * 10.0,
            visualScrollRangePixels = 100,
            viewportHeightPixels = 30,
            contentHeightPixels = 30,
            cellHeightPixels = 10,
        )
}
