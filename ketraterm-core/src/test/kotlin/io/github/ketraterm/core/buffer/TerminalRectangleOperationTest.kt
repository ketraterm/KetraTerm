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
import io.github.ketraterm.protocol.DecRectangleAttribute
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

    @Test
    fun `DECCRA snapshots overlapping source before mutating destination`() {
        val buffer = TerminalBuffers.create(width = 6, height = 2)
        "ABCDEF".forEach { buffer.writeCodepoint(it.code) }

        buffer.copyRectangle(
            sourceTop = 1,
            sourceLeft = 1,
            sourceBottom = 1,
            sourceRight = 4,
            sourcePage = 1,
            destinationTop = 1,
            destinationLeft = 2,
            destinationPage = 1,
        )

        assertEquals("AABCDF", buffer.getLineAsString(0))
    }

    @Test
    fun `DECCRA preserves a copied wide span after source is erased`() {
        val buffer = TerminalBuffers.create(width = 6, height = 2)
        buffer.writeCodepoint(0x1F642)

        buffer.copyRectangle(
            sourceTop = 1,
            sourceLeft = 1,
            sourceBottom = 1,
            sourceRight = 2,
            sourcePage = 0,
            destinationTop = 1,
            destinationLeft = 4,
            destinationPage = 0,
        )
        buffer.eraseRectangle(top = 1, left = 1, bottom = 1, right = 2, selective = false)

        assertAll(
            { assertEquals(TerminalConstants.EMPTY, buffer.getCodepointAt(0, 0)) },
            { assertEquals(TerminalConstants.EMPTY, buffer.getCodepointAt(1, 0)) },
            { assertEquals(0x1F642, buffer.getCodepointAt(3, 0)) },
            { assertEquals(TerminalConstants.WIDE_CHAR_SPACER, buffer.getCodepointAt(4, 0)) },
        )
    }

    @Test
    fun `DECCRA rejects unavailable source and destination pages`() {
        val buffer = TerminalBuffers.create(width = 4, height = 2)
        buffer.writeCodepoint('A'.code)

        buffer.copyRectangle(1, 1, 1, 1, 2, 1, 2, 1)
        buffer.copyRectangle(1, 1, 1, 1, 1, 1, 2, 2)

        assertEquals("A", buffer.getLineAsString(0))
    }

    @Test
    fun `DECCARA rectangle extent materializes blanks and preserves protection`() {
        val buffer = TerminalBuffers.create(width = 4, height = 2)
        buffer.setSelectiveEraseProtection(true)
        buffer.writeCodepoint('A'.code)

        buffer.setAttributeChangeExtent(2)
        buffer.changeRectangleAttributes(
            top = 1,
            left = 1,
            bottom = 1,
            right = 3,
            setMask = DecRectangleAttribute.BOLD or DecRectangleAttribute.UNDERLINE,
            clearMask = 0,
        )

        assertAll(
            { assertEquals('A'.code, buffer.getCodepointAt(0, 0)) },
            { assertEquals(' '.code, buffer.getCodepointAt(1, 0)) },
            { assertEquals(' '.code, buffer.getCodepointAt(2, 0)) },
            { assertTrue(buffer.getAttrAt(0, 0)?.bold == true) },
            { assertTrue(buffer.getAttrAt(1, 0)?.underlineStyle?.sgrCode == 1) },
            { assertTrue(buffer.getAttrAt(0, 0)?.selectiveEraseProtected == true) },
        )
    }

    @Test
    fun `DECSACE stream extent wraps and skips undrawn cells`() {
        val buffer = TerminalBuffers.create(width = 4, height = 2)
        buffer.writeCodepoint('A'.code)
        buffer.writeCodepoint('B'.code)
        buffer.positionCursor(col = 1, row = 1)
        buffer.writeCodepoint('X'.code)

        buffer.changeRectangleAttributes(
            top = 1,
            left = 2,
            bottom = 2,
            right = 3,
            setMask = DecRectangleAttribute.BLINK,
            clearMask = 0,
        )

        assertAll(
            { assertTrue(buffer.getAttrAt(1, 0)?.blink == true) },
            { assertTrue(buffer.getAttrAt(1, 1)?.blink == true) },
            { assertEquals(TerminalConstants.EMPTY, buffer.getCodepointAt(2, 0)) },
            { assertEquals(TerminalConstants.EMPTY, buffer.getCodepointAt(0, 1)) },
        )
    }

    @Test
    fun `DECRARA toggles a complete wide span when its spacer is selected`() {
        val buffer = TerminalBuffers.create(width = 5, height = 2)
        buffer.positionCursor(col = 1, row = 0)
        buffer.writeCodepoint(0x1F642)
        buffer.setAttributeChangeExtent(2)

        buffer.reverseRectangleAttributes(
            top = 1,
            left = 3,
            bottom = 1,
            right = 3,
            reverseMask = DecRectangleAttribute.BOLD or DecRectangleAttribute.INVERSE,
        )

        assertAll(
            { assertTrue(buffer.getAttrAt(1, 0)?.bold == true) },
            { assertTrue(buffer.getAttrAt(1, 0)?.inverse == true) },
            { assertTrue(buffer.getAttrAt(2, 0)?.bold == true) },
            { assertTrue(buffer.getAttrAt(2, 0)?.inverse == true) },
        )
    }

    @Test
    fun `DECCARA preserves grapheme payload while updating its attributes`() {
        val buffer = TerminalBuffers.create(width = 5, height = 2)
        buffer.writeCluster(intArrayOf('A'.code, 0x0301), 2)
        buffer.setAttributeChangeExtent(2)

        buffer.changeRectangleAttributes(
            top = 1,
            left = 1,
            bottom = 1,
            right = 1,
            setMask = DecRectangleAttribute.INVERSE,
            clearMask = 0,
        )

        val cluster = IntArray(2)
        val line = buffer.getLine(0)
        assertAll(
            { assertTrue(line.isCluster(0)) },
            { assertEquals(2, line.readCluster(0, cluster)) },
            { assertArrayEquals(intArrayOf('A'.code, 0x0301), cluster) },
            { assertTrue(buffer.getAttrAt(0, 0)?.inverse == true) },
        )
    }

    @Test
    fun `unsupported DECSACE value leaves the previous extent unchanged`() {
        val buffer = TerminalBuffers.create(width = 3, height = 2)
        buffer.setAttributeChangeExtent(2)
        buffer.setAttributeChangeExtent(99)

        buffer.changeRectangleAttributes(
            top = 1,
            left = 1,
            bottom = 1,
            right = 1,
            setMask = DecRectangleAttribute.BOLD,
            clearMask = 0,
        )

        assertEquals(' '.code, buffer.getCodepointAt(0, 0))
    }
}
