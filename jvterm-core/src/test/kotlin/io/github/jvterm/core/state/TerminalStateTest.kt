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
package io.github.jvterm.core.state

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TerminalStateTest {
    @Test
    fun `constructor initializes global hardware and both screens`() {
        val state = TerminalState(initialWidth = 10, initialHeight = 3, maxHistory = 7)

        assertAll(
            { assertEquals(10, state.dimensions.width) },
            { assertEquals(3, state.dimensions.height) },
            { assertFalse(state.isAltScreenActive) },
            { assertSame(state.primaryBuffer, state.activeBuffer) },
            { assertEquals(3, state.primaryBuffer.ring.size) },
            { assertEquals(10, state.primaryBuffer.ring.capacity) },
            { assertEquals(3, state.altBuffer.ring.size) },
            { assertEquals(3, state.altBuffer.ring.capacity) },
            { assertNotSame(state.primaryBuffer.store, state.altBuffer.store) },
            { assertSame(state.primaryBuffer.ring, state.ring) },
            { assertSame(state.primaryBuffer.cursor, state.cursor) },
            { assertSame(state.primaryBuffer.savedCursor, state.savedCursor) },
            { assertEquals(0, state.scrollTop) },
            { assertEquals(2, state.scrollBottom) },
            { assertTrue(state.isFullViewportScroll) },
            { assertEquals(8, state.tabStops.getNextStop(1)) },
        )
    }

    @Test
    fun `resolveRingIndex maps against active ring with and without history`() {
        val state = TerminalState(initialWidth = 10, initialHeight = 3, maxHistory = 7)

        repeat(2) {
            state.primaryBuffer.ring
                .push()
                .clear(state.pen.currentAttr)
        }

        assertAll(
            { assertEquals(2, state.resolveRingIndex(0)) },
            { assertEquals(3, state.resolveRingIndex(1)) },
            { assertEquals(4, state.resolveRingIndex(2)) },
        )

        state.enterAltScreen(clearBeforeEnter = true)

        assertAll(
            { assertEquals(0, state.resolveRingIndex(0)) },
            { assertEquals(1, state.resolveRingIndex(1)) },
            { assertEquals(2, state.resolveRingIndex(2)) },
        )
    }

    @Test
    fun `enterAltScreen switches active accessors and wipes alt state`() {
        val state = TerminalState(initialWidth = 10, initialHeight = 5, maxHistory = 2)

        state.primaryBuffer.cursor.col = 7
        state.primaryBuffer.cursor.row = 3
        state.primaryBuffer.cursor.pendingWrap = true
        state.primaryBuffer.ring[0].setCell(0, 'P'.code, state.pen.currentAttr)

        state.altBuffer.cursor.col = 4
        state.altBuffer.cursor.row = 2
        state.altBuffer.cursor.pendingWrap = true
        state.altBuffer.setScrollRegion(top = 2, bottom = 4, isOriginMode = false, viewportHeight = 5)
        state.altBuffer.ring[0].setCell(0, 'A'.code, state.pen.currentAttr)

        state.enterAltScreen(clearBeforeEnter = true)

        assertAll(
            { assertTrue(state.isAltScreenActive) },
            { assertSame(state.altBuffer, state.activeBuffer) },
            { assertSame(state.altBuffer.ring, state.ring) },
            { assertSame(state.altBuffer.cursor, state.cursor) },
            { assertSame(state.altBuffer.savedCursor, state.savedCursor) },
            { assertEquals(0, state.cursor.col) },
            { assertEquals(0, state.cursor.row) },
            { assertFalse(state.cursor.pendingWrap) },
            { assertEquals(0, state.scrollTop) },
            { assertEquals(4, state.scrollBottom) },
            { assertTrue(state.isFullViewportScroll) },
            { assertEquals("", state.altBuffer.ring[0].toTextTrimmed()) },
            { assertEquals("P", state.primaryBuffer.ring[0].toTextTrimmed()) },
        )
    }

    @Test
    fun `enterAltScreen is a no-op when already active`() {
        val state = TerminalState(initialWidth = 6, initialHeight = 3, maxHistory = 1)
        state.enterAltScreen(clearBeforeEnter = true)
        state.altBuffer.ring[0].setCell(0, 'A'.code, state.pen.currentAttr)

        state.enterAltScreen(clearBeforeEnter = true)

        assertAll(
            { assertTrue(state.isAltScreenActive) },
            { assertEquals("A", state.altBuffer.ring[0].toTextTrimmed()) },
        )
    }

    @Test
    fun `exitAltScreen restores primary routing and preserves both buffers`() {
        val state = TerminalState(initialWidth = 10, initialHeight = 5, maxHistory = 2)

        state.primaryBuffer.cursor.col = 2
        state.primaryBuffer.cursor.row = 1
        state.primaryBuffer.cursor.pendingWrap = true
        state.primaryBuffer.ring[0].setCell(0, 'P'.code, state.pen.currentAttr)

        state.enterAltScreen(clearBeforeEnter = true)
        state.altBuffer.cursor.col = 4
        state.altBuffer.cursor.row = 3
        state.altBuffer.cursor.pendingWrap = true
        state.altBuffer.ring[0].setCell(0, 'A'.code, state.pen.currentAttr)

        state.exitAltScreen()

        assertAll(
            { assertFalse(state.isAltScreenActive) },
            { assertSame(state.primaryBuffer, state.activeBuffer) },
            { assertSame(state.primaryBuffer.ring, state.ring) },
            { assertSame(state.primaryBuffer.cursor, state.cursor) },
            { assertSame(state.primaryBuffer.savedCursor, state.savedCursor) },
            { assertEquals(2, state.cursor.col) },
            { assertEquals(1, state.cursor.row) },
            { assertTrue(state.cursor.pendingWrap) },
            { assertEquals("P", state.primaryBuffer.ring[0].toTextTrimmed()) },
            { assertEquals("A", state.altBuffer.ring[0].toTextTrimmed()) },
        )
    }

    @Test
    fun `exitAltScreen is a no-op on primary screen`() {
        val state = TerminalState(initialWidth = 8, initialHeight = 4, maxHistory = 1)

        state.exitAltScreen()

        assertAll(
            { assertFalse(state.isAltScreenActive) },
            { assertSame(state.primaryBuffer, state.activeBuffer) },
        )
    }

    @Test
    fun `isFullViewportScroll delegates to active screen margins`() {
        val state = TerminalState(initialWidth = 10, initialHeight = 4, maxHistory = 1)

        state.primaryBuffer.setScrollRegion(top = 2, bottom = 3, isOriginMode = false, viewportHeight = 4)
        assertFalse(state.isFullViewportScroll)

        state.enterAltScreen(clearBeforeEnter = true)
        assertTrue(state.isFullViewportScroll)
    }

    @Test
    fun `cancelPendingWrap affects only active cursor`() {
        val state = TerminalState(initialWidth = 10, initialHeight = 4, maxHistory = 1)

        state.primaryBuffer.cursor.pendingWrap = true
        state.altBuffer.cursor.pendingWrap = true

        state.cancelPendingWrap()
        assertAll(
            { assertFalse(state.primaryBuffer.cursor.pendingWrap) },
            { assertTrue(state.altBuffer.cursor.pendingWrap) },
        )

        state.enterAltScreen(clearBeforeEnter = true)
        state.altBuffer.cursor.pendingWrap = true
        state.cancelPendingWrap()

        assertAll(
            { assertFalse(state.altBuffer.cursor.pendingWrap) },
            { assertFalse(state.primaryBuffer.cursor.pendingWrap) },
        )
    }

    @Test
    fun `enterAltScreen actively clears stale savedCursor from previous alt sessions`() {
        val state = TerminalState(initialWidth = 10, initialHeight = 10, maxHistory = 10)

        // 1. Enter Alt Screen and simulate an app saving the cursor
        state.enterAltScreen(clearBeforeEnter = true)
        state.altBuffer.savedCursor.isSaved = true
        state.altBuffer.savedCursor.col = 5
        state.altBuffer.savedCursor.row = 5

        // 2. Exit Alt Screen
        state.exitAltScreen()

        // 3. Re-enter Alt Screen
        state.enterAltScreen(clearBeforeEnter = true)

        // Ensure the saved cursor was wiped clean
        assertFalse(
            state.altBuffer.savedCursor.isSaved,
            "Entering the Alt Screen MUST wipe any stale saved cursor state",
        )
    }
}
