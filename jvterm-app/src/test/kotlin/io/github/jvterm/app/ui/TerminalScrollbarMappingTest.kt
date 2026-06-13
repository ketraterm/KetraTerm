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
package io.github.jvterm.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class TerminalScrollbarMappingTest {
    @Test
    fun `live viewport maps to bottom scrollbar value`() {
        assertEquals(12, TerminalScrollbarMapping.valueForViewport(historySize = 12, renderOffset = 0))
        assertEquals(0, TerminalScrollbarMapping.offsetForValue(historySize = 12, value = 12))
    }

    @Test
    fun `history viewport maps to top-origin scrollbar value`() {
        assertEquals(8, TerminalScrollbarMapping.valueForViewport(historySize = 12, renderOffset = 4))
        assertEquals(4, TerminalScrollbarMapping.offsetForValue(historySize = 12, value = 8))
    }

    @Test
    fun `fractional viewport uses render offset for the indicator`() {
        assertEquals(11, TerminalScrollbarMapping.valueForViewport(historySize = 12, renderOffset = 1))
    }

    @Test
    fun `out of range values clamp to valid terminal offsets`() {
        assertEquals(0, TerminalScrollbarMapping.valueForViewport(historySize = 12, renderOffset = 99))
        assertEquals(12, TerminalScrollbarMapping.offsetForValue(historySize = 12, value = -20))
        assertEquals(0, TerminalScrollbarMapping.offsetForValue(historySize = 12, value = 99))
    }

    @Test
    fun `maximum includes visible extent for Swing bottom value`() {
        assertEquals(42, TerminalScrollbarMapping.maximum(historySize = 12, visibleRows = 30))
        assertEquals(13, TerminalScrollbarMapping.maximum(historySize = 12, visibleRows = 0))
    }
}
