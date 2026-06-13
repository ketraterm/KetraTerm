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
package io.github.jvterm.core.buffer.impl

import io.github.jvterm.core.TerminalBuffers
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TerminalWriterImplContractTest {
    @Test
    fun `clearAll_resetsTabStopsToDefault`() {
        val buffer = TerminalBuffers.create(width = 12, height = 2)
        buffer.clearAllTabStops()
        buffer.clearAll()
        buffer.horizontalTab()

        assertEquals(8, buffer.cursorCol, "clearAll must restore the default VT tab stops")
    }

    @Test
    fun `writeText_remains_scalarOnly_and_does_not_segment_graphemes`() {
        val buffer = TerminalBuffers.create(width = 6, height = 2)

        buffer.writeText("e\u0301")

        assertAll(
            { assertFalse(buffer.getLine(0).isCluster(0), "Core writeText must stay scalar-only until parser segmentation exists") },
            { assertEquals('e'.code, buffer.getCodepointAt(0, 0)) },
            { assertEquals(0x0301, buffer.getCodepointAt(1, 0)) },
            { assertEquals(2, buffer.cursorCol) },
        )
    }

    @Test
    fun `setTabStop_breaks_pendingWrap_so_next_print_does_not_wrap`() {
        val buffer = TerminalBuffers.create(width = 4, height = 2)

        buffer.positionCursor(3, 0)
        buffer.writeCodepoint('A'.code)
        buffer.setTabStop()
        buffer.writeCodepoint('B'.code)

        assertAll(
            { assertEquals('B'.code, buffer.getCodepointAt(3, 0)) },
            { assertEquals(0, buffer.getCodepointAt(0, 1)) },
            { assertEquals(3, buffer.cursorCol) },
            { assertEquals(0, buffer.cursorRow) },
        )
    }
}
