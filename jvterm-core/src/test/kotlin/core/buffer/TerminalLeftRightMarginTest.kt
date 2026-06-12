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
package com.gagik.core.buffer

import com.gagik.core.TerminalBuffers
import com.gagik.core.api.TerminalBufferApi
import com.gagik.core.state.TerminalState
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TerminalLeftRightMarginTest {
    private fun stateOf(api: TerminalBufferApi): TerminalState {
        val componentsField = api.javaClass.getDeclaredField("components")
        componentsField.isAccessible = true
        val components = componentsField.get(api)

        val stateField = components.javaClass.getDeclaredField("state")
        stateField.isAccessible = true
        return stateField.get(components) as TerminalState
    }

    @Test
    fun `declrm_enable_homesTheCursor`() {
        val buffer = TerminalBuffers.create(width = 8, height = 4)
        buffer.positionCursor(5, 2)

        buffer.setLeftRightMarginMode(true)

        assertAll(
            { assertEquals(0, buffer.cursorCol) },
            { assertEquals(0, buffer.cursorRow) },
        )
    }

    @Test
    fun `declrm_disable_homesTheCursor`() {
        val buffer = TerminalBuffers.create(width = 8, height = 4)
        buffer.setLeftRightMarginMode(true)
        buffer.setLeftRightMargins(3, 6)
        buffer.positionCursor(4, 2)

        buffer.setLeftRightMarginMode(false)

        val state = stateOf(buffer)
        assertAll(
            { assertFalse(state.modes.isLeftRightMarginMode) },
            { assertEquals(0, buffer.cursorCol) },
            { assertEquals(0, buffer.cursorRow) },
            { assertEquals(0, state.activeBuffer.leftMargin) },
            { assertEquals(7, state.activeBuffer.rightMargin) },
        )
    }

    @Test
    fun `setMargins_withDecLrmOff_isIgnored`() {
        val buffer = TerminalBuffers.create(width = 8, height = 4)
        val state = stateOf(buffer)
        buffer.positionCursor(4, 1)

        buffer.setLeftRightMargins(3, 6)

        assertAll(
            { assertFalse(state.modes.isLeftRightMarginMode) },
            { assertEquals(0, state.activeBuffer.leftMargin) },
            { assertEquals(7, state.activeBuffer.rightMargin) },
            { assertEquals(4, buffer.cursorCol) },
            { assertEquals(1, buffer.cursorRow) },
        )
    }

    @Test
    fun `setMargins_degenerateRange_isIgnored`() {
        val buffer = TerminalBuffers.create(width = 8, height = 4)
        val state = stateOf(buffer)
        buffer.setLeftRightMarginMode(true)
        buffer.setLeftRightMargins(3, 6)
        buffer.positionCursor(4, 1)

        buffer.setLeftRightMargins(5, 5)

        assertAll(
            { assertEquals(2, state.activeBuffer.leftMargin) },
            { assertEquals(5, state.activeBuffer.rightMargin) },
            { assertEquals(4, buffer.cursorCol) },
            { assertEquals(1, buffer.cursorRow) },
        )
    }

    @Test
    fun `setMargins_success_homesTheCursor`() {
        val buffer = TerminalBuffers.create(width = 8, height = 4)
        buffer.setLeftRightMarginMode(true)
        buffer.positionCursor(4, 2)

        buffer.setLeftRightMargins(3, 6)

        assertAll(
            { assertEquals(2, buffer.cursorCol) },
            { assertEquals(0, buffer.cursorRow) },
        )
    }

    @Test
    fun `write_wrapsAtRightMargin_notAtScreenEdge`() {
        val buffer = TerminalBuffers.create(width = 8, height = 3)
        buffer.setLeftRightMarginMode(true)
        buffer.setLeftRightMargins(3, 5)

        buffer.writeText("ABCD")

        assertAll(
            { assertEquals('A'.code, buffer.getCodepointAt(2, 0)) },
            { assertEquals('B'.code, buffer.getCodepointAt(3, 0)) },
            { assertEquals('C'.code, buffer.getCodepointAt(4, 0)) },
            { assertEquals('D'.code, buffer.getCodepointAt(2, 1)) },
            { assertEquals(3, buffer.cursorCol) },
            { assertEquals(1, buffer.cursorRow) },
        )
    }

    @Test
    fun `write_decawmOff_clampedAtRightMargin`() {
        val buffer = TerminalBuffers.create(width = 8, height = 3)
        buffer.setLeftRightMarginMode(true)
        buffer.setLeftRightMargins(3, 5)
        buffer.setAutoWrap(false)

        buffer.writeText("ABCD")

        assertAll(
            { assertEquals('A'.code, buffer.getCodepointAt(2, 0)) },
            { assertEquals('B'.code, buffer.getCodepointAt(3, 0)) },
            { assertEquals('D'.code, buffer.getCodepointAt(4, 0)) },
            { assertEquals(4, buffer.cursorCol) },
            { assertEquals(0, buffer.cursorRow) },
        )
    }

    @Test
    fun `ich_doesNotPushContentBeyondRightMargin`() {
        val buffer = TerminalBuffers.create(width = 8, height = 2)
        val state = stateOf(buffer)
        buffer.setLeftRightMarginMode(true)
        buffer.setLeftRightMargins(3, 6)
        buffer.writeText("ABCD")
        state.activeBuffer.ring[state.resolveRingIndex(0)].setCell(6, 'X'.code, 0)
        state.activeBuffer.ring[state.resolveRingIndex(0)].setCell(7, 'Y'.code, 0)
        buffer.positionCursor(3, 0)

        buffer.insertBlankCharacters(2)

        assertAll(
            { assertEquals('A'.code, buffer.getCodepointAt(2, 0)) },
            { assertEquals(0, buffer.getCodepointAt(3, 0)) },
            { assertEquals(0, buffer.getCodepointAt(4, 0)) },
            { assertEquals('B'.code, buffer.getCodepointAt(5, 0)) },
            { assertEquals('X'.code, buffer.getCodepointAt(6, 0)) },
            { assertEquals('Y'.code, buffer.getCodepointAt(7, 0)) },
        )
    }

    @Test
    fun `dch_doesNotPullContentFromBeyondRightMargin`() {
        val buffer = TerminalBuffers.create(width = 8, height = 2)
        val state = stateOf(buffer)
        buffer.setLeftRightMarginMode(true)
        buffer.setLeftRightMargins(3, 6)
        buffer.writeText("ABCD")
        state.activeBuffer.ring[state.resolveRingIndex(0)].setCell(6, 'X'.code, 0)
        state.activeBuffer.ring[state.resolveRingIndex(0)].setCell(7, 'Y'.code, 0)
        buffer.positionCursor(3, 0)

        buffer.deleteCharacters(2)

        assertAll(
            { assertEquals('A'.code, buffer.getCodepointAt(2, 0)) },
            { assertEquals('D'.code, buffer.getCodepointAt(3, 0)) },
            { assertEquals(0, buffer.getCodepointAt(4, 0)) },
            { assertEquals(0, buffer.getCodepointAt(5, 0)) },
            { assertEquals('X'.code, buffer.getCodepointAt(6, 0)) },
            { assertEquals('Y'.code, buffer.getCodepointAt(7, 0)) },
        )
    }

    @Test
    fun `il_onlyMovesCellsInsideHorizontalMargins`() {
        val buffer = TerminalBuffers.create(width = 8, height = 4)
        val state = stateOf(buffer)
        buffer.setLeftRightMarginMode(true)
        buffer.setLeftRightMargins(3, 6)
        buffer.setScrollRegion(2, 4)

        state.activeBuffer.ring[state.resolveRingIndex(0)].setCell(0, 'A'.code, 0)
        state.activeBuffer.ring[state.resolveRingIndex(0)].setCell(2, 'a'.code, 0)
        state.activeBuffer.ring[state.resolveRingIndex(0)].setCell(3, 'b'.code, 0)
        state.activeBuffer.ring[state.resolveRingIndex(0)].setCell(4, 'c'.code, 0)
        state.activeBuffer.ring[state.resolveRingIndex(0)].setCell(7, '!'.code, 0)

        state.activeBuffer.ring[state.resolveRingIndex(1)].setCell(0, 'B'.code, 0)
        state.activeBuffer.ring[state.resolveRingIndex(1)].setCell(2, 'd'.code, 0)
        state.activeBuffer.ring[state.resolveRingIndex(1)].setCell(3, 'e'.code, 0)
        state.activeBuffer.ring[state.resolveRingIndex(1)].setCell(4, 'f'.code, 0)
        state.activeBuffer.ring[state.resolveRingIndex(1)].setCell(7, '?'.code, 0)

        state.activeBuffer.ring[state.resolveRingIndex(2)].setCell(0, 'C'.code, 0)
        state.activeBuffer.ring[state.resolveRingIndex(2)].setCell(2, 'g'.code, 0)
        state.activeBuffer.ring[state.resolveRingIndex(2)].setCell(3, 'h'.code, 0)
        state.activeBuffer.ring[state.resolveRingIndex(2)].setCell(4, 'i'.code, 0)
        state.activeBuffer.ring[state.resolveRingIndex(2)].setCell(7, '.'.code, 0)

        buffer.positionCursor(2, 1)
        buffer.insertLines(1)

        assertAll(
            { assertEquals('A'.code, buffer.getCodepointAt(0, 0)) },
            { assertEquals('a'.code, buffer.getCodepointAt(2, 0)) },
            { assertEquals('b'.code, buffer.getCodepointAt(3, 0)) },
            { assertEquals('c'.code, buffer.getCodepointAt(4, 0)) },
            { assertEquals('!'.code, buffer.getCodepointAt(7, 0)) },
            { assertEquals('B'.code, buffer.getCodepointAt(0, 1)) },
            { assertEquals(0, buffer.getCodepointAt(2, 1)) },
            { assertEquals(0, buffer.getCodepointAt(3, 1)) },
            { assertEquals(0, buffer.getCodepointAt(4, 1)) },
            { assertEquals('?'.code, buffer.getCodepointAt(7, 1)) },
            { assertEquals('C'.code, buffer.getCodepointAt(0, 2)) },
            { assertEquals('d'.code, buffer.getCodepointAt(2, 2)) },
            { assertEquals('e'.code, buffer.getCodepointAt(3, 2)) },
            { assertEquals('f'.code, buffer.getCodepointAt(4, 2)) },
            { assertEquals('.'.code, buffer.getCodepointAt(7, 2)) },
        )
    }

    @Test
    fun `dl_onlyMovesCellsInsideHorizontalMargins`() {
        val buffer = TerminalBuffers.create(width = 8, height = 4)
        val state = stateOf(buffer)
        buffer.setLeftRightMarginMode(true)
        buffer.setLeftRightMargins(3, 6)
        buffer.setScrollRegion(2, 4)

        state.activeBuffer.ring[state.resolveRingIndex(0)].setCell(0, 'A'.code, 0)
        state.activeBuffer.ring[state.resolveRingIndex(0)].setCell(2, 'a'.code, 0)
        state.activeBuffer.ring[state.resolveRingIndex(0)].setCell(3, 'b'.code, 0)
        state.activeBuffer.ring[state.resolveRingIndex(0)].setCell(4, 'c'.code, 0)
        state.activeBuffer.ring[state.resolveRingIndex(0)].setCell(7, '!'.code, 0)

        state.activeBuffer.ring[state.resolveRingIndex(1)].setCell(0, 'B'.code, 0)
        state.activeBuffer.ring[state.resolveRingIndex(1)].setCell(2, 'd'.code, 0)
        state.activeBuffer.ring[state.resolveRingIndex(1)].setCell(3, 'e'.code, 0)
        state.activeBuffer.ring[state.resolveRingIndex(1)].setCell(4, 'f'.code, 0)
        state.activeBuffer.ring[state.resolveRingIndex(1)].setCell(7, '?'.code, 0)

        state.activeBuffer.ring[state.resolveRingIndex(2)].setCell(0, 'C'.code, 0)
        state.activeBuffer.ring[state.resolveRingIndex(2)].setCell(2, 'g'.code, 0)
        state.activeBuffer.ring[state.resolveRingIndex(2)].setCell(3, 'h'.code, 0)
        state.activeBuffer.ring[state.resolveRingIndex(2)].setCell(4, 'i'.code, 0)
        state.activeBuffer.ring[state.resolveRingIndex(2)].setCell(7, '.'.code, 0)

        buffer.positionCursor(2, 1)
        buffer.deleteLines(1)

        assertAll(
            { assertEquals('A'.code, buffer.getCodepointAt(0, 0)) },
            { assertEquals('a'.code, buffer.getCodepointAt(2, 0)) },
            { assertEquals('b'.code, buffer.getCodepointAt(3, 0)) },
            { assertEquals('c'.code, buffer.getCodepointAt(4, 0)) },
            { assertEquals('!'.code, buffer.getCodepointAt(7, 0)) },
            { assertEquals('B'.code, buffer.getCodepointAt(0, 1)) },
            { assertEquals('g'.code, buffer.getCodepointAt(2, 1)) },
            { assertEquals('h'.code, buffer.getCodepointAt(3, 1)) },
            { assertEquals('i'.code, buffer.getCodepointAt(4, 1)) },
            { assertEquals('?'.code, buffer.getCodepointAt(7, 1)) },
            { assertEquals('C'.code, buffer.getCodepointAt(0, 2)) },
            { assertEquals(0, buffer.getCodepointAt(2, 2)) },
            { assertEquals(0, buffer.getCodepointAt(3, 2)) },
            { assertEquals(0, buffer.getCodepointAt(4, 2)) },
            { assertEquals('.'.code, buffer.getCodepointAt(7, 2)) },
        )
    }

    @Test
    fun `cr_movesToLeftMargin_whenLRActive`() {
        val buffer = TerminalBuffers.create(width = 8, height = 3)
        buffer.setLeftRightMarginMode(true)
        buffer.setLeftRightMargins(3, 6)
        buffer.positionCursor(5, 1)

        buffer.carriageReturn()

        assertAll(
            { assertEquals(2, buffer.cursorCol) },
            { assertEquals(1, buffer.cursorRow) },
        )
    }

    @Test
    fun `el_operations_onlyAffectHorizontalMarginRegion`() {
        val buffer = TerminalBuffers.create(width = 8, height = 3)
        val state = stateOf(buffer)
        buffer.setLeftRightMarginMode(true)
        buffer.setLeftRightMargins(3, 6)

        state.activeBuffer.ring[state.resolveRingIndex(0)].setCell(0, 'L'.code, 0)
        state.activeBuffer.ring[state.resolveRingIndex(0)].setCell(2, 'a'.code, 0)
        state.activeBuffer.ring[state.resolveRingIndex(0)].setCell(3, 'b'.code, 0)
        state.activeBuffer.ring[state.resolveRingIndex(0)].setCell(4, 'c'.code, 0)
        state.activeBuffer.ring[state.resolveRingIndex(0)].setCell(5, 'd'.code, 0)
        state.activeBuffer.ring[state.resolveRingIndex(0)].setCell(7, 'R'.code, 0)

        buffer.positionCursor(3, 0)
        buffer.eraseLineToEnd()

        assertAll(
            { assertEquals('L'.code, buffer.getCodepointAt(0, 0)) },
            { assertEquals('a'.code, buffer.getCodepointAt(2, 0)) },
            { assertEquals(0, buffer.getCodepointAt(3, 0)) },
            { assertEquals(0, buffer.getCodepointAt(4, 0)) },
            { assertEquals(0, buffer.getCodepointAt(5, 0)) },
            { assertEquals('R'.code, buffer.getCodepointAt(7, 0)) },
        )

        state.activeBuffer.ring[state.resolveRingIndex(0)].setCell(2, 'a'.code, 0)
        state.activeBuffer.ring[state.resolveRingIndex(0)].setCell(3, 'b'.code, 0)
        state.activeBuffer.ring[state.resolveRingIndex(0)].setCell(4, 'c'.code, 0)
        state.activeBuffer.ring[state.resolveRingIndex(0)].setCell(5, 'd'.code, 0)
        buffer.positionCursor(4, 0)
        buffer.eraseLineToCursor()

        assertAll(
            { assertEquals('L'.code, buffer.getCodepointAt(0, 0)) },
            { assertEquals(0, buffer.getCodepointAt(2, 0)) },
            { assertEquals(0, buffer.getCodepointAt(3, 0)) },
            { assertEquals(0, buffer.getCodepointAt(4, 0)) },
            { assertEquals('d'.code, buffer.getCodepointAt(5, 0)) },
            { assertEquals('R'.code, buffer.getCodepointAt(7, 0)) },
        )

        state.activeBuffer.ring[state.resolveRingIndex(0)].setCell(2, 'a'.code, 0)
        state.activeBuffer.ring[state.resolveRingIndex(0)].setCell(3, 'b'.code, 0)
        state.activeBuffer.ring[state.resolveRingIndex(0)].setCell(4, 'c'.code, 0)
        state.activeBuffer.ring[state.resolveRingIndex(0)].setCell(5, 'd'.code, 0)
        buffer.eraseCurrentLine()

        assertAll(
            { assertEquals('L'.code, buffer.getCodepointAt(0, 0)) },
            { assertEquals(0, buffer.getCodepointAt(2, 0)) },
            { assertEquals(0, buffer.getCodepointAt(3, 0)) },
            { assertEquals(0, buffer.getCodepointAt(4, 0)) },
            { assertEquals(0, buffer.getCodepointAt(5, 0)) },
            { assertEquals('R'.code, buffer.getCodepointAt(7, 0)) },
        )
    }

    @Test
    fun `ht_stopsAtRightMargin`() {
        val buffer = TerminalBuffers.create(width = 20, height = 2)
        buffer.setLeftRightMarginMode(true)
        buffer.setLeftRightMargins(3, 6)

        buffer.horizontalTab()

        assertEquals(5, buffer.cursorCol)
    }

    @Test
    fun `cursorAddressing_clampedToLRRegion`() {
        val buffer = TerminalBuffers.create(width = 8, height = 4)
        buffer.setLeftRightMarginMode(true)
        buffer.setLeftRightMargins(3, 6)

        buffer.positionCursor(0, 1)
        assertEquals(2, buffer.cursorCol)

        buffer.positionCursor(99, 2)
        assertAll(
            { assertEquals(5, buffer.cursorCol) },
            { assertEquals(2, buffer.cursorRow) },
        )
    }

    @Test
    fun `decom_plus_lr_doublyRelativeAddressing`() {
        val buffer = TerminalBuffers.create(width = 8, height = 6)
        buffer.setLeftRightMarginMode(true)
        buffer.setLeftRightMargins(3, 6)
        buffer.setScrollRegion(2, 5)
        buffer.setOriginMode(true)

        buffer.positionCursor(1, 1)

        assertAll(
            { assertEquals(3, buffer.cursorCol) },
            { assertEquals(2, buffer.cursorRow) },
        )
    }

    @Test
    fun `existingScrollRegion_unaffectedByLRMargins`() {
        val buffer = TerminalBuffers.create(width = 8, height = 4)
        val state = stateOf(buffer)
        buffer.setScrollRegion(2, 3)
        buffer.setLeftRightMarginMode(true)
        buffer.setLeftRightMargins(3, 5)

        state.activeBuffer.ring[state.resolveRingIndex(0)].setCell(0, 'T'.code, 0)
        state.activeBuffer.ring[state.resolveRingIndex(1)].setCell(0, 'A'.code, 0)
        state.activeBuffer.ring[state.resolveRingIndex(2)].setCell(0, 'B'.code, 0)
        state.activeBuffer.ring[state.resolveRingIndex(3)].setCell(0, 'Z'.code, 0)

        buffer.positionCursor(2, 2)
        buffer.newLine()

        assertAll(
            { assertEquals('T'.code, buffer.getCodepointAt(0, 0)) },
            { assertEquals('B'.code, buffer.getCodepointAt(0, 1)) },
            { assertEquals(0, buffer.getCodepointAt(0, 2)) },
            { assertEquals('Z'.code, buffer.getCodepointAt(0, 3)) },
        )
    }

    @Test
    fun `resize_resetsLRMarginsToFullWidth`() {
        val buffer = TerminalBuffers.create(width = 8, height = 4)
        val state = stateOf(buffer)
        buffer.setLeftRightMarginMode(true)
        buffer.setLeftRightMargins(3, 6)
        buffer.enterAltBuffer()
        buffer.setLeftRightMargins(2, 5)

        buffer.resize(10, 4)

        assertAll(
            { assertEquals(0, state.primaryBuffer.leftMargin) },
            { assertEquals(9, state.primaryBuffer.rightMargin) },
            { assertEquals(0, state.altBuffer.leftMargin) },
            { assertEquals(9, state.altBuffer.rightMargin) },
        )
    }

    @Test
    fun `altBufferSwitch_hasIndependentLRMargins`() {
        val buffer = TerminalBuffers.create(width = 8, height = 4)
        val state = stateOf(buffer)
        buffer.setLeftRightMarginMode(true)
        buffer.setLeftRightMargins(3, 6)

        buffer.enterAltBuffer()

        assertAll(
            { assertEquals(0, state.altBuffer.leftMargin) },
            { assertEquals(7, state.altBuffer.rightMargin) },
        )

        buffer.setLeftRightMargins(2, 5)
        buffer.exitAltBuffer()

        assertAll(
            { assertEquals(2, state.primaryBuffer.leftMargin) },
            { assertEquals(5, state.primaryBuffer.rightMargin) },
            { assertEquals(1, state.altBuffer.leftMargin) },
            { assertEquals(4, state.altBuffer.rightMargin) },
        )

        buffer.enterAltBuffer()

        assertAll(
            { assertEquals(0, state.altBuffer.leftMargin) },
            { assertEquals(7, state.altBuffer.rightMargin) },
            { assertEquals(2, state.primaryBuffer.leftMargin) },
            { assertEquals(5, state.primaryBuffer.rightMargin) },
        )
    }

    @Test
    fun `restoreCursor_preservesPendingWrapAtHorizontalRightMargin`() {
        val buffer = TerminalBuffers.create(width = 8, height = 3)
        buffer.setLeftRightMarginMode(true)
        buffer.setLeftRightMargins(3, 5)
        buffer.writeText("ABC")
        buffer.saveCursor()

        buffer.positionCursor(2, 1)
        buffer.restoreCursor()

        val state = stateOf(buffer)
        assertAll(
            { assertEquals(4, buffer.cursorCol) },
            { assertEquals(0, buffer.cursorRow) },
            { assertTrue(state.cursor.pendingWrap) },
        )
    }

    @Test
    fun `restoreCursor_clampsToHorizontalMargins_whenDeclrmmActive`() {
        val buffer = TerminalBuffers.create(width = 8, height = 3)
        val state = stateOf(buffer)
        buffer.positionCursor(7, 1)
        buffer.saveCursor()
        buffer.setLeftRightMarginMode(true)
        buffer.setLeftRightMargins(3, 6)

        buffer.restoreCursor()
        buffer.writeCodepoint('X'.code)

        assertAll(
            { assertEquals(5, buffer.cursorCol) },
            { assertEquals(1, buffer.cursorRow) },
            { assertEquals('X'.code, buffer.getCodepointAt(5, 1)) },
            { assertTrue(state.cursor.pendingWrap) },
        )
    }

    @Test
    fun `eraseLineToEnd_doesNotEraseOutsideHorizontalMargins_whenCursorIsOutside`() {
        val buffer = TerminalBuffers.create(width = 8, height = 2)
        val state = stateOf(buffer)
        buffer.setLeftRightMarginMode(true)
        buffer.setLeftRightMargins(3, 6)

        state.activeBuffer.ring[state.resolveRingIndex(0)].setCell(0, 'L'.code, 0)
        state.activeBuffer.ring[state.resolveRingIndex(0)].setCell(2, 'a'.code, 0)
        state.activeBuffer.ring[state.resolveRingIndex(0)].setCell(3, 'b'.code, 0)
        state.activeBuffer.ring[state.resolveRingIndex(0)].setCell(4, 'c'.code, 0)
        state.activeBuffer.ring[state.resolveRingIndex(0)].setCell(7, 'R'.code, 0)
        state.cursor.col = 0
        state.cursor.row = 0

        buffer.eraseLineToEnd()

        assertAll(
            { assertEquals('L'.code, buffer.getCodepointAt(0, 0)) },
            { assertEquals('a'.code, buffer.getCodepointAt(2, 0)) },
            { assertEquals('b'.code, buffer.getCodepointAt(3, 0)) },
            { assertEquals('c'.code, buffer.getCodepointAt(4, 0)) },
            { assertEquals('R'.code, buffer.getCodepointAt(7, 0)) },
        )
    }

    @Test
    fun `eraseLineToCursor_doesNotEraseOutsideHorizontalMargins_whenCursorIsOutside`() {
        val buffer = TerminalBuffers.create(width = 8, height = 2)
        val state = stateOf(buffer)
        buffer.setLeftRightMarginMode(true)
        buffer.setLeftRightMargins(3, 6)

        state.activeBuffer.ring[state.resolveRingIndex(0)].setCell(0, 'L'.code, 0)
        state.activeBuffer.ring[state.resolveRingIndex(0)].setCell(2, 'a'.code, 0)
        state.activeBuffer.ring[state.resolveRingIndex(0)].setCell(3, 'b'.code, 0)
        state.activeBuffer.ring[state.resolveRingIndex(0)].setCell(4, 'c'.code, 0)
        state.activeBuffer.ring[state.resolveRingIndex(0)].setCell(7, 'R'.code, 0)
        state.cursor.col = 7
        state.cursor.row = 0

        buffer.eraseLineToCursor()

        assertAll(
            { assertEquals('L'.code, buffer.getCodepointAt(0, 0)) },
            { assertEquals('a'.code, buffer.getCodepointAt(2, 0)) },
            { assertEquals('b'.code, buffer.getCodepointAt(3, 0)) },
            { assertEquals('c'.code, buffer.getCodepointAt(4, 0)) },
            { assertEquals('R'.code, buffer.getCodepointAt(7, 0)) },
        )
    }
}
