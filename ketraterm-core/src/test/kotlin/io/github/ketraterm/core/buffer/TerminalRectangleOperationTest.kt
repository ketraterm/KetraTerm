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
package io.github.ketraterm.core.buffer

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.core.model.TerminalConstants
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TerminalRectangleOperationTest {
    @Test
    fun `DECFRA fills an inclusive rectangle without moving cursor`() {
        val buffer = TerminalBuffers.create(width = 6, height = 4)
        buffer.positionCursor(col = 5, row = 3)

        buffer.fillRectangle('#'.code, top = 2, left = 2, bottom = 3, right = 4)

        assertAll(
            { assertEquals('#'.code, buffer.getCodepointAt(1, 1)) },
            { assertEquals('#'.code, buffer.getCodepointAt(3, 2)) },
            { assertEquals(TerminalConstants.EMPTY, buffer.getCodepointAt(0, 1)) },
            { assertEquals(TerminalConstants.EMPTY, buffer.getCodepointAt(4, 2)) },
            { assertEquals(5, buffer.cursorCol) },
            { assertEquals(3, buffer.cursorRow) },
        )
    }

    @Test
    fun `DECERA erases a full wide span when rectangle intersects its spacer`() {
        val buffer = TerminalBuffers.create(width = 5, height = 2)
        buffer.positionCursor(col = 1, row = 0)
        buffer.writeCodepoint(0x1F642)

        buffer.eraseRectangle(top = 1, left = 3, bottom = 1, right = 3, selective = false)

        assertAll(
            { assertEquals(TerminalConstants.EMPTY, buffer.getCodepointAt(1, 0)) },
            { assertEquals(TerminalConstants.EMPTY, buffer.getCodepointAt(2, 0)) },
        )
    }

    @Test
    fun `DECSERA preserves a protected wide span while erasing adjacent cells`() {
        val buffer = TerminalBuffers.create(width = 6, height = 2)
        buffer.setSelectiveEraseProtection(true)
        buffer.writeCodepoint(0x1F642)
        buffer.setSelectiveEraseProtection(false)
        buffer.writeCodepoint('B'.code)

        buffer.eraseRectangle(top = 1, left = 2, bottom = 1, right = 4, selective = true)

        assertAll(
            { assertEquals(0x1F642, buffer.getCodepointAt(0, 0)) },
            { assertEquals(TerminalConstants.WIDE_CHAR_SPACER, buffer.getCodepointAt(1, 0)) },
            { assertTrue(buffer.getAttrAt(0, 0)?.selectiveEraseProtected == true) },
            { assertEquals(TerminalConstants.EMPTY, buffer.getCodepointAt(2, 0)) },
        )
    }

    @Test
    fun `rectangle coordinates are relative to active margins in origin mode`() {
        val buffer = TerminalBuffers.create(width = 8, height = 5)
        buffer.setLeftRightMarginMode(true)
        buffer.setLeftRightMargins(left = 3, right = 6)
        buffer.setScrollRegion(top = 2, bottom = 4)
        buffer.setOriginMode(true)

        buffer.fillRectangle('X'.code, top = 1, left = 1, bottom = 2, right = 2)

        assertAll(
            { assertEquals('X'.code, buffer.getCodepointAt(2, 1)) },
            { assertEquals('X'.code, buffer.getCodepointAt(3, 2)) },
            { assertEquals(TerminalConstants.EMPTY, buffer.getCodepointAt(1, 1)) },
            { assertEquals(TerminalConstants.EMPTY, buffer.getCodepointAt(4, 3)) },
        )
    }
}
