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
import io.github.ketraterm.core.api.TerminalBuffer
import io.github.ketraterm.core.codec.AttributeCodec
import io.github.ketraterm.core.model.TerminalConstants
import io.github.ketraterm.core.state.TerminalState
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.random.Random

class TerminalLeftRightMarginTest {
    private fun stateOf(api: TerminalBuffer): TerminalState {
        val componentsField = api.javaClass.getDeclaredField("components")
        componentsField.isAccessible = true
        val components = componentsField.get(api)

        val stateField = components.javaClass.getDeclaredField("state")
        stateField.isAccessible = true
        return stateField.get(components) as TerminalState
    }

    @Test
    fun `mixed character edits never cross active horizontal margins`() {
        val buffer = TerminalBuffers.create(width = 8, height = 3)
        val state = stateOf(buffer)
        val random = Random(0x1E_F7)
        buffer.setLeftRightMarginMode(true)
        buffer.setLeftRightMargins(3, 6)
        buffer.setScrollRegion(1, 3)
        buffer.setAutoWrap(false)

        for (row in 0 until 3) {
            val line = state.activeBuffer.ring[state.resolveRingIndex(row)]
            line.setCell(0, 'L'.code + row, 0)
            line.setCell(1, 'l'.code + row, 0)
            line.setCell(6, 'R'.code + row, 0)
            line.setCell(7, 'r'.code + row, 0)
        }

        repeat(600) { step ->
            buffer.positionCursor(2 + random.nextInt(4), random.nextInt(3))
            when (random.nextInt(10)) {
                0 -> buffer.writeCodepoint('a'.code + random.nextInt(26))
                1 -> buffer.writeCodepoint(0x1F600 + random.nextInt(5))
                2 -> buffer.insertBlankCharacters(1 + random.nextInt(3))
                3 -> buffer.deleteCharacters(1 + random.nextInt(3))
                4 -> buffer.eraseCharacters(1 + random.nextInt(3))
                5 -> buffer.selectiveEraseLineToEnd()
                6 -> buffer.selectiveEraseLineToCursor()
                7 -> buffer.selectiveEraseCurrentLine()
                8 -> buffer.insertLines(1 + random.nextInt(2))
                else -> buffer.deleteLines(1 + random.nextInt(2))
            }

            for (row in 0 until 3) {
                assertAll(
                    { assertEquals('L'.code + row, buffer.getCodepointAt(0, row), "step=$step row=$row") },
                    { assertEquals('l'.code + row, buffer.getCodepointAt(1, row), "step=$step row=$row") },
                    { assertEquals('R'.code + row, buffer.getCodepointAt(6, row), "step=$step row=$row") },
                    { assertEquals('r'.code + row, buffer.getCodepointAt(7, row), "step=$step row=$row") },
                )
            }
        }
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
    fun `wide glyph at horizontal right margin wraps as one span`() {
        val buffer = TerminalBuffers.create(width = 8, height = 2)
        val state = stateOf(buffer)
        buffer.setLeftRightMarginMode(true)
        buffer.setLeftRightMargins(3, 5)
        buffer.positionCursor(4, 0)

        buffer.writeCodepoint(0x1F600)

        assertAll(
            { assertEquals(0, buffer.getCodepointAt(4, 0), "wide glyph must not be split at the right margin") },
            { assertEquals(0x1F600, buffer.getCodepointAt(2, 1)) },
            { assertEquals(-1, buffer.getCodepointAt(3, 1)) },
            { assertEquals(4, buffer.cursorCol) },
            { assertEquals(1, buffer.cursorRow) },
            {
                assertTrue(
                    state.activeBuffer.ring[state.resolveRingIndex(0)].wrapped,
                    "the vacated row must remain linked to the wrapped wide glyph",
                )
            },
            { assertEquals(0, buffer.getCodepointAt(5, 1), "right-margin guard must remain untouched") },
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

    @Test
    fun `leftRightMargin_randomOperationsPropertyTest`() {
        val buffer = TerminalBuffers.create(width = 10, height = 6)
        val state = stateOf(buffer)
        val random = Random(42)
        val logs = ArrayList<String>()

        try {
            repeat(1000) { step ->
                // Randomly enable or disable modes
                if (random.nextInt(20) == 0) {
                    val modeVal = random.nextBoolean()
                    buffer.setLeftRightMarginMode(modeVal)
                    logs.add("Step $step: setLeftRightMarginMode($modeVal)")
                }
                if (random.nextInt(20) == 0) {
                    val modeVal = random.nextBoolean()
                    buffer.setOriginMode(modeVal)
                    logs.add("Step $step: setOriginMode($modeVal)")
                }

                // Randomly update margins
                if (random.nextInt(15) == 0 && state.modes.isLeftRightMarginMode) {
                    val left = random.nextInt(4) // 0..3
                    val right = 5 + random.nextInt(4) // 5..8
                    buffer.setLeftRightMargins(left + 1, right + 1)
                    logs.add("Step $step: setLeftRightMargins(${left + 1}, ${right + 1})")
                }
                if (random.nextInt(15) == 0) {
                    val top = random.nextInt(2) // 0..1
                    val bottom = 3 + random.nextInt(2) // 3..4
                    buffer.setScrollRegion(top + 1, bottom + 1)
                    logs.add("Step $step: setScrollRegion(${top + 1}, ${bottom + 1})")
                }

                // Perform a random edit or cursor operation
                when (random.nextInt(26)) {
                    0 -> {
                        val cp = 'A'.code + random.nextInt(26)
                        buffer.writeCodepoint(cp)
                        logs.add("Step $step: writeCodepoint(${cp.toChar()})")
                    }
                    1 -> {
                        val cp = 0x1F600 + random.nextInt(5)
                        buffer.writeCluster(intArrayOf(cp), 1)
                        logs.add("Step $step: writeCluster(WideChar: $cp)")
                    }
                    2 -> {
                        buffer.newLine()
                        logs.add("Step $step: newLine()")
                    }
                    3 -> {
                        buffer.carriageReturn()
                        logs.add("Step $step: carriageReturn()")
                    }
                    4 -> {
                        buffer.reverseLineFeed()
                        logs.add("Step $step: reverseLineFeed()")
                    }
                    5 -> {
                        val cnt = 1 + random.nextInt(2)
                        buffer.insertLines(cnt)
                        logs.add("Step $step: insertLines($cnt)")
                    }
                    6 -> {
                        val cnt = 1 + random.nextInt(2)
                        buffer.deleteLines(cnt)
                        logs.add("Step $step: deleteLines($cnt)")
                    }
                    7 -> {
                        val cnt = 1 + random.nextInt(3)
                        buffer.insertBlankCharacters(cnt)
                        logs.add("Step $step: insertBlankCharacters($cnt)")
                    }
                    8 -> {
                        val cnt = 1 + random.nextInt(3)
                        buffer.deleteCharacters(cnt)
                        logs.add("Step $step: deleteCharacters($cnt)")
                    }
                    9 -> {
                        val cnt = 1 + random.nextInt(3)
                        buffer.eraseCharacters(cnt)
                        logs.add("Step $step: eraseCharacters($cnt)")
                    }
                    10 -> {
                        buffer.eraseLineToEnd()
                        logs.add("Step $step: eraseLineToEnd()")
                    }
                    11 -> {
                        buffer.eraseLineToCursor()
                        logs.add("Step $step: eraseLineToCursor()")
                    }
                    12 -> {
                        buffer.eraseCurrentLine()
                        logs.add("Step $step: eraseCurrentLine()")
                    }
                    13 -> {
                        buffer.scrollUp()
                        logs.add("Step $step: scrollUp()")
                    }
                    14 -> {
                        buffer.scrollDown()
                        logs.add("Step $step: scrollDown()")
                    }
                    15 -> {
                        val col = random.nextInt(12) - 1 // include out of bounds
                        val row = random.nextInt(8) - 1
                        buffer.positionCursor(col, row)
                        logs.add("Step $step: positionCursor($col, $row)")
                    }
                    16 -> {
                        val isSave = random.nextBoolean()
                        if (isSave) {
                            buffer.saveCursor()
                            logs.add("Step $step: saveCursor()")
                        } else {
                            buffer.restoreCursor()
                            logs.add("Step $step: restoreCursor()")
                        }
                    }
                    17 -> {
                        buffer.writeText("xyz")
                        logs.add("Step $step: writeText(xyz)")
                    }
                    18 -> {
                        buffer.selectiveEraseLineToEnd()
                        logs.add("Step $step: selectiveEraseLineToEnd()")
                    }
                    19 -> {
                        buffer.selectiveEraseLineToCursor()
                        logs.add("Step $step: selectiveEraseLineToCursor()")
                    }
                    20 -> {
                        buffer.selectiveEraseCurrentLine()
                        logs.add("Step $step: selectiveEraseCurrentLine()")
                    }
                    21 -> {
                        buffer.eraseScreenToEnd()
                        logs.add("Step $step: eraseScreenToEnd()")
                    }
                    22 -> {
                        buffer.eraseScreenToCursor()
                        logs.add("Step $step: eraseScreenToCursor()")
                    }
                    23 -> {
                        buffer.selectiveEraseScreenToEnd()
                        logs.add("Step $step: selectiveEraseScreenToEnd()")
                    }
                    24 -> {
                        buffer.selectiveEraseScreenToCursor()
                        logs.add("Step $step: selectiveEraseScreenToCursor()")
                    }
                    else -> {
                        buffer.selectiveEraseEntireScreen()
                        logs.add("Step $step: selectiveEraseEntireScreen()")
                    }
                }

                // INVARIANTS:
                val cursorCol = buffer.cursorCol
                val cursorRow = buffer.cursorRow

                // Cursor row must always be in [0, height - 1]
                assertTrue(cursorRow in 0 until buffer.height, "Step $step: Cursor row $cursorRow must be in 0..${buffer.height - 1}")
                // Cursor col must always be in [0, width - 1]
                assertTrue(cursorCol in 0 until buffer.width, "Step $step: Cursor col $cursorCol must be in 0..${buffer.width - 1}")

                // If left/right margins are active (which effectiveLeftMargin/effectiveRightMargin represent),
                // the cursor column must be within those margins.
                val left = state.effectiveLeftMargin
                val right = state.effectiveRightMargin
                assertTrue(
                    cursorCol in left..right,
                    "Step $step: Cursor col $cursorCol must be in effective horizontal margins $left..$right",
                )

                // 2. No orphan spacer characters in the grid
                for (r in 0 until buffer.height) {
                    for (c in 0 until buffer.width) {
                        val cp = buffer.getCodepointAt(c, r)
                        if (cp == TerminalConstants.WIDE_CHAR_SPACER) {
                            assertTrue(c > 0, "Step $step: WIDE_CHAR_SPACER cannot be in column 0 at row $r")
                            val leader = buffer.getCodepointAt(c - 1, r)
                            assertTrue(
                                leader != TerminalConstants.EMPTY && leader != TerminalConstants.WIDE_CHAR_SPACER,
                                "Step $step: Spacer at row=$r col=$c must have a non-empty leader on its left (was $leader)",
                            )
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            println("=== PROPERTY TEST FAILED ===")
            println("Error: ${e.message}")
            println("Steps leading to failure:")
            logs.forEach { println("  $it") }
            println("Grid content:")
            for (r in 0 until buffer.height) {
                val rowStr =
                    (0 until buffer.width)
                        .map { c ->
                            when (val cp = buffer.getCodepointAt(c, r)) {
                                TerminalConstants.EMPTY -> "."
                                TerminalConstants.WIDE_CHAR_SPACER -> "S"
                                else -> cp.toChar().toString()
                            }
                        }.joinToString("")
                println("Row $r: $rowStr")
            }
            throw e
        }
    }

    @Test
    fun `partial region scroll property preserves rows outside region and models guard-column movement`() {
        val buffer = TerminalBuffers.create(width = 8, height = 4)
        val state = stateOf(buffer)
        val random = Random(0x5C0A11)
        val expectedLeft = IntArray(4) { 'L'.code + it }
        val expectedRight = IntArray(4) { 'R'.code + it }

        buffer.setLeftRightMarginMode(true)
        buffer.setLeftRightMargins(3, 6)
        buffer.setScrollRegion(2, 3)
        for (row in 0 until 4) {
            val line = state.activeBuffer.ring[state.resolveRingIndex(row)]
            line.setCell(0, expectedLeft[row], 0)
            line.setCell(7, expectedRight[row], 0)
        }

        repeat(400) { step ->
            if (random.nextBoolean()) {
                buffer.scrollUp()
                expectedLeft[1] = expectedLeft[2]
                expectedRight[1] = expectedRight[2]
                expectedLeft[2] = TerminalConstants.EMPTY
                expectedRight[2] = TerminalConstants.EMPTY
            } else {
                buffer.scrollDown()
                expectedLeft[2] = expectedLeft[1]
                expectedRight[2] = expectedRight[1]
                expectedLeft[1] = TerminalConstants.EMPTY
                expectedRight[1] = TerminalConstants.EMPTY
            }

            for (row in 0 until 4) {
                assertAll(
                    { assertEquals(expectedLeft[row], buffer.getCodepointAt(0, row), "step=$step row=$row left") },
                    { assertEquals(expectedRight[row], buffer.getCodepointAt(7, row), "step=$step row=$row right") },
                )
            }
        }
    }

    @Test
    fun `random selective erases preserve protected wide clusters within horizontal margins`() {
        val buffer = TerminalBuffers.create(width = 8, height = 3)
        val state = stateOf(buffer)
        val random = Random(0x51EC7)

        buffer.setLeftRightMarginMode(true)
        buffer.setLeftRightMargins(3, 6)
        buffer.setSelectiveEraseProtection(true)
        for (row in 0 until 3) {
            buffer.positionCursor(2, row)
            buffer.writeCodepoint(0x1F600 + row)
        }
        buffer.setSelectiveEraseProtection(false)

        repeat(400) { step ->
            buffer.positionCursor(2 + random.nextInt(4), random.nextInt(3))
            when (random.nextInt(3)) {
                0 -> buffer.selectiveEraseLineToEnd()
                1 -> buffer.selectiveEraseLineToCursor()
                else -> buffer.selectiveEraseCurrentLine()
            }

            for (row in 0 until 3) {
                val line = state.activeBuffer.ring[state.resolveRingIndex(row)]
                assertAll(
                    { assertEquals(0x1F600 + row, buffer.getCodepointAt(2, row), "step=$step row=$row leader") },
                    { assertEquals(TerminalConstants.WIDE_CHAR_SPACER, buffer.getCodepointAt(3, row), "step=$step row=$row spacer") },
                    { assertTrue(AttributeCodec.isProtected(line.getPackedAttr(2)), "step=$step row=$row protection") },
                )
            }
        }
    }
}
