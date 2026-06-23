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
    fun `continuous thumb drag applies integer top row without lag`() {
        val scrollbar = JScrollBar(Adjustable.VERTICAL)
        var requestedOffset = -1
        var requestedAdjusting = false
        val adapter = SwingScrollbarAdapter(scrollbar)
        adapter.attach(
            SwingScrollbarScroller { offset, valueIsAdjusting ->
                requestedOffset = offset
                requestedAdjusting = valueIsAdjusting
            },
        )
        val state = viewportState(renderOffset = 0)
        adapter.viewportStateChanged(state)

        scrollbar.model.valueIsAdjusting = true
        scrollbar.value = 47

        assertEquals(6, requestedOffset)
        assertTrue(requestedAdjusting)
        adapter.viewportChanged(10, requestedOffset.toDouble(), 6, 3, 4)
        assertEquals(47, scrollbar.value)
        assertTrue(scrollbar.valueIsAdjusting)

        scrollbar.model.valueIsAdjusting = false

        assertEquals(6, requestedOffset)
        assertEquals(false, requestedAdjusting)
        assertEquals(47, scrollbar.value)

        adapter.viewportChanged(10, 6.0, 6, 3, 3)
        assertEquals(40, scrollbar.value)
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
    fun `primitive animation update preserves pixel metrics and moves thumb`() {
        val scrollbar = JScrollBar(Adjustable.VERTICAL)
        val adapter = SwingScrollbarAdapter(scrollbar)

        adapter.viewportStateChanged(viewportState(renderOffset = 3))
        adapter.viewportChanged(10, 2.5, 3, 3, 4)

        assertEquals(75, scrollbar.value)
        assertEquals(10, scrollbar.unitIncrement)
        assertEquals(30, scrollbar.blockIncrement)
        assertEquals(130, scrollbar.maximum)
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
