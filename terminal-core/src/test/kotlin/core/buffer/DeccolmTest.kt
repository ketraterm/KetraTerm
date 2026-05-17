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

class DeccolmTest {
    private fun stateOf(api: TerminalBufferApi): TerminalState {
        val componentsField = api.javaClass.getDeclaredField("components")
        componentsField.isAccessible = true
        val components = componentsField.get(api)

        val stateField = components.javaClass.getDeclaredField("state")
        stateField.isAccessible = true
        return stateField.get(components) as TerminalState
    }

    private fun blankScreen(height: Int): String = List(height) { "" }.joinToString("\n")

    @Test
    fun `switch80to132_widthChanges`() {
        val buffer = TerminalBuffers.create(width = 80, height = 4)

        buffer.executeDeccolm(132)

        assertEquals(132, buffer.width)
    }

    @Test
    fun `switch132to80_widthChanges`() {
        val buffer = TerminalBuffers.create(width = 132, height = 4)

        buffer.executeDeccolm(80)

        assertEquals(80, buffer.width)
    }

    @Test
    fun `deccolm_clearsViewportAndHistory`() {
        val buffer = TerminalBuffers.create(width = 80, height = 2, maxHistory = 4)
        buffer.writeText("A".repeat(80))
        buffer.newLine()
        buffer.writeText("B".repeat(80))
        buffer.newLine()
        buffer.writeText("C".repeat(5))

        buffer.executeDeccolm(132)

        assertAll(
            { assertEquals(blankScreen(2), buffer.getScreenAsString()) },
            { assertEquals(0, buffer.historySize) },
        )
    }

    @Test
    fun `deccolm_homesCursorAbsolute`() {
        val buffer = TerminalBuffers.create(width = 80, height = 4)
        buffer.positionCursor(17, 3)

        buffer.executeDeccolm(132)

        assertAll(
            { assertEquals(0, buffer.cursorCol) },
            { assertEquals(0, buffer.cursorRow) },
        )
    }

    @Test
    fun `deccolm_homesCursorAbsolute_evenWithDecomActive`() {
        val buffer = TerminalBuffers.create(width = 80, height = 5)
        buffer.setScrollRegion(2, 4)
        buffer.setOriginMode(true)
        buffer.positionCursor(3, 2)

        buffer.executeDeccolm(132)

        assertAll(
            { assertEquals(0, buffer.cursorCol) },
            { assertEquals(0, buffer.cursorRow) },
        )
    }

    @Test
    fun `deccolm_resetsScrollMargins`() {
        val buffer = TerminalBuffers.create(width = 80, height = 5)
        val state = stateOf(buffer)
        buffer.setScrollRegion(2, 4)

        buffer.executeDeccolm(132)

        assertAll(
            { assertEquals(0, state.activeBuffer.scrollTop) },
            { assertEquals(4, state.activeBuffer.scrollBottom) },
        )
    }

    @Test
    fun `deccolm_resetsLRMargins_whenDeclrmmActive`() {
        val buffer = TerminalBuffers.create(width = 80, height = 5)
        val state = stateOf(buffer)
        buffer.setLeftRightMarginMode(true)
        buffer.setLeftRightMargins(3, 9)

        buffer.executeDeccolm(132)

        assertAll(
            { assertEquals(0, state.activeBuffer.leftMargin) },
            { assertEquals(131, state.activeBuffer.rightMargin) },
        )
    }

    @Test
    fun `deccolm_resetsLRMargins_whenDeclrmmInactive_noEffect`() {
        val buffer = TerminalBuffers.create(width = 80, height = 5)
        val state = stateOf(buffer)

        buffer.executeDeccolm(132)

        assertAll(
            { assertFalse(state.modes.isLeftRightMarginMode) },
            { assertEquals(0, state.activeBuffer.leftMargin) },
            { assertEquals(131, state.activeBuffer.rightMargin) },
        )
    }

    @Test
    fun `deccolm_resetsTabStopsToDefaultRhythm`() {
        val buffer = TerminalBuffers.create(width = 80, height = 5)
        val state = stateOf(buffer)
        state.tabStops.clearAll()
        state.tabStops.setStop(5)

        buffer.executeDeccolm(132)

        assertAll(
            { assertEquals(8, state.tabStops.getNextStop(0)) },
            { assertEquals(16, state.tabStops.getNextStop(8)) },
        )
    }

    @Test
    fun `deccolm_cancelsPendingWrap`() {
        val buffer = TerminalBuffers.create(width = 80, height = 3)
        val state = stateOf(buffer)
        buffer.writeText("A".repeat(80))
        assertTrue(state.cursor.pendingWrap)

        buffer.executeDeccolm(132)

        assertFalse(state.cursor.pendingWrap)
    }

    @Test
    fun `deccolm_preservesSavedCursor`() {
        val buffer = TerminalBuffers.create(width = 132, height = 4)
        val state = stateOf(buffer)
        state.modes.isOriginMode = true
        buffer.positionCursor(120, 3)
        buffer.saveCursor()
        state.savedCursor.pendingWrap = true

        buffer.executeDeccolm(80)

        assertAll(
            { assertTrue(state.savedCursor.isSaved) },
            { assertEquals(120, state.savedCursor.col) },
            { assertEquals(3, state.savedCursor.row) },
            { assertTrue(state.savedCursor.pendingWrap) },
            { assertTrue(state.savedCursor.isOriginMode) },
        )
    }

    @Test
    fun `deccolm_preservesBothSavedCursorSlots`() {
        val buffer = TerminalBuffers.create(width = 132, height = 4)
        val state = stateOf(buffer)

        buffer.enterAltBuffer()

        state.primaryBuffer.savedCursor.col = 120
        state.primaryBuffer.savedCursor.row = 3
        state.primaryBuffer.savedCursor.attr = 0
        state.primaryBuffer.savedCursor.pendingWrap = true
        state.primaryBuffer.savedCursor.isOriginMode = true
        state.primaryBuffer.savedCursor.isSaved = true

        state.modes.isOriginMode = false
        buffer.positionCursor(17, 1)
        buffer.saveCursor()
        state.altBuffer.savedCursor.pendingWrap = false

        buffer.executeDeccolm(80)

        assertAll(
            { assertTrue(state.primaryBuffer.savedCursor.isSaved) },
            { assertEquals(120, state.primaryBuffer.savedCursor.col) },
            { assertEquals(3, state.primaryBuffer.savedCursor.row) },
            { assertTrue(state.primaryBuffer.savedCursor.pendingWrap) },
            { assertTrue(state.primaryBuffer.savedCursor.isOriginMode) },
            { assertTrue(state.altBuffer.savedCursor.isSaved) },
            { assertEquals(17, state.altBuffer.savedCursor.col) },
            { assertEquals(1, state.altBuffer.savedCursor.row) },
            { assertFalse(state.altBuffer.savedCursor.pendingWrap) },
            { assertFalse(state.altBuffer.savedCursor.isOriginMode) },
        )
    }

    @Test
    fun `deccolm_whileInAltBuffer_wipesAltAndReflowsPrimary`() {
        val buffer = TerminalBuffers.create(width = 80, height = 3, maxHistory = 4)
        val state = stateOf(buffer)
        buffer.writeText("PRIMARY-LINE")
        buffer.enterAltBuffer()
        buffer.writeText("ALT")

        buffer.executeDeccolm(132)

        val primaryTop = (state.primaryBuffer.ring.size - state.dimensions.height).coerceAtLeast(0)
        assertAll(
            { assertTrue(state.isAltScreenActive) },
            { assertEquals(blankScreen(3), buffer.getScreenAsString()) },
            { assertEquals('P'.code, state.primaryBuffer.ring[primaryTop].getCodepoint(0)) },
            { assertEquals(132, buffer.width) },
        )
    }

    @Test
    fun `deccolm_fromAlt_preservesPrimaryContentByReflow_notClear`() {
        val buffer = TerminalBuffers.create(width = 132, height = 2, maxHistory = 4)
        buffer.positionCursor(0, 1)
        buffer.writeText("A".repeat(90))
        buffer.enterAltBuffer()
        buffer.writeText("ALT")

        buffer.executeDeccolm(80)
        buffer.exitAltBuffer()

        assertAll(
            { assertEquals(80, buffer.width) },
            { assertEquals("A".repeat(80), buffer.getLineAsString(0)) },
            { assertEquals("A".repeat(10), buffer.getLineAsString(1)) },
        )
    }

    @Test
    fun `deccolm_invalidWidth_isIgnored`() {
        val buffer = TerminalBuffers.create(width = 80, height = 4)
        val state = stateOf(buffer)
        buffer.positionCursor(10, 2)

        buffer.executeDeccolm(100)

        assertAll(
            { assertEquals(80, buffer.width) },
            { assertEquals(10, buffer.cursorCol) },
            { assertEquals(2, buffer.cursorRow) },
            { assertEquals(79, state.activeBuffer.rightMargin) },
        )
    }

    @Test
    fun `deccolm_doesNotRegress_existingWrapBehavior`() {
        val buffer = TerminalBuffers.create(width = 80, height = 3)

        buffer.executeDeccolm(132)
        buffer.writeText("A".repeat(132))
        buffer.writeCodepoint('B'.code)

        assertAll(
            { assertEquals('B'.code, buffer.getCodepointAt(0, 1)) },
            { assertEquals(1, buffer.cursorCol) },
            { assertEquals(1, buffer.cursorRow) },
        )
    }

    @Test
    fun `deccolm_doesNotRegress_existingScrollRegionBehavior`() {
        val buffer = TerminalBuffers.create(width = 80, height = 4, maxHistory = 4)
        val state = stateOf(buffer)

        buffer.executeDeccolm(132)
        buffer.setScrollRegion(2, 3)
        state.activeBuffer.ring[state.resolveRingIndex(1)].setCell(0, 'A'.code, 0)
        state.activeBuffer.ring[state.resolveRingIndex(2)].setCell(0, 'B'.code, 0)
        buffer.positionCursor(0, 2)

        buffer.newLine()

        assertAll(
            { assertEquals('B'.code, buffer.getCodepointAt(0, 1)) },
            { assertEquals(0, buffer.getCodepointAt(0, 2)) },
            { assertEquals(2, buffer.cursorRow) },
        )
    }
}
