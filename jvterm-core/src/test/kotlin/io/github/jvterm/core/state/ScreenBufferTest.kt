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

class ScreenBufferTest {
    private fun newBuffer(
        width: Int = 10,
        height: Int = 5,
        maxHistory: Int = 4,
    ): ScreenBuffer = ScreenBuffer(width, height, maxHistory)

    @Test
    fun `constructor initializes memory arena cursor and scroll margins`() {
        val buffer = newBuffer(width = 8, height = 3, maxHistory = 7)

        assertAll(
            { assertEquals(0, buffer.cursor.col) },
            { assertEquals(0, buffer.cursor.row) },
            { assertFalse(buffer.cursor.pendingWrap) },
            { assertFalse(buffer.savedCursor.isSaved) },
            { assertEquals(0, buffer.scrollTop) },
            { assertEquals(2, buffer.scrollBottom) },
            { assertEquals(0, buffer.ring.size) },
            { assertEquals(10, buffer.ring.capacity) },
            { assertTrue(buffer.isFullViewportScroll(3)) },
        )
    }

    @Test
    fun `isFullViewportScroll depends on current margins and provided viewport height`() {
        val buffer = newBuffer(width = 8, height = 5, maxHistory = 0)

        assertTrue(buffer.isFullViewportScroll(5))

        buffer.setScrollRegion(top = 2, bottom = 4, isOriginMode = false, viewportHeight = 5)

        assertAll(
            { assertFalse(buffer.isFullViewportScroll(5)) },
            { assertFalse(buffer.isFullViewportScroll(4)) },
        )
    }

    @Test
    fun `setScrollRegion converts from 1-based rows and homes cursor with origin mode off`() {
        val buffer = newBuffer(width = 8, height = 5, maxHistory = 0)
        buffer.cursor.col = 6
        buffer.cursor.row = 4
        buffer.cursor.pendingWrap = true

        buffer.setScrollRegion(top = 2, bottom = 4, isOriginMode = false, viewportHeight = 5)

        assertAll(
            { assertEquals(1, buffer.scrollTop) },
            { assertEquals(3, buffer.scrollBottom) },
            { assertEquals(0, buffer.cursor.col) },
            { assertEquals(0, buffer.cursor.row) },
            { assertFalse(buffer.cursor.pendingWrap) },
        )
    }

    @Test
    fun `setScrollRegion homes cursor to new top margin with origin mode on`() {
        val buffer = newBuffer(width = 8, height = 5, maxHistory = 0)
        buffer.cursor.col = 6
        buffer.cursor.row = 4
        buffer.cursor.pendingWrap = true

        buffer.setScrollRegion(top = 2, bottom = 4, isOriginMode = true, viewportHeight = 5)

        assertAll(
            { assertEquals(1, buffer.scrollTop) },
            { assertEquals(3, buffer.scrollBottom) },
            { assertEquals(0, buffer.cursor.col) },
            { assertEquals(1, buffer.cursor.row) },
            { assertFalse(buffer.cursor.pendingWrap) },
        )
    }

    @Test
    fun `setScrollRegion clamps out-of-range values to viewport`() {
        val buffer = newBuffer(width = 8, height = 5, maxHistory = 0)

        buffer.setScrollRegion(top = -50, bottom = 99, isOriginMode = false, viewportHeight = 5)

        assertAll(
            { assertEquals(0, buffer.scrollTop) },
            { assertEquals(4, buffer.scrollBottom) },
            { assertTrue(buffer.isFullViewportScroll(5)) },
        )
    }

    @Test
    fun `setScrollRegion ignores degenerate ranges`() {
        val buffer = newBuffer(width = 8, height = 5, maxHistory = 0)
        buffer.setScrollRegion(top = 2, bottom = 4, isOriginMode = false, viewportHeight = 5)
        buffer.cursor.col = 5
        buffer.cursor.row = 2
        buffer.cursor.pendingWrap = true

        buffer.setScrollRegion(top = 5, bottom = 5, isOriginMode = true, viewportHeight = 5)

        assertAll(
            { assertEquals(1, buffer.scrollTop) },
            { assertEquals(3, buffer.scrollBottom) },
            { assertEquals(5, buffer.cursor.col) },
            { assertEquals(2, buffer.cursor.row) },
            { assertTrue(buffer.cursor.pendingWrap) },
        )
    }

    @Test
    fun `resetScrollRegion restores full viewport margins without moving cursor`() {
        val buffer = newBuffer(width = 8, height = 5, maxHistory = 0)
        buffer.setScrollRegion(top = 2, bottom = 4, isOriginMode = false, viewportHeight = 5)
        buffer.cursor.col = 7
        buffer.cursor.row = 3
        buffer.cursor.pendingWrap = true

        buffer.resetScrollRegion(viewportHeight = 5)

        assertAll(
            { assertEquals(0, buffer.scrollTop) },
            { assertEquals(4, buffer.scrollBottom) },
            { assertTrue(buffer.isFullViewportScroll(5)) },
            { assertEquals(7, buffer.cursor.col) },
            { assertEquals(3, buffer.cursor.row) },
            { assertTrue(buffer.cursor.pendingWrap) },
        )
    }

    @Test
    fun `clearGrid reuses ring and store and fills viewport with blank lines`() {
        val buffer = newBuffer(width = 8, height = 3, maxHistory = 2)
        buffer.clearGrid(penAttr = 11, viewportHeight = 3)
        buffer.ring[0].setCell(0, 'A'.code, 11)
        repeat(2) { buffer.ring.push().clear(11) }

        val ringBefore = buffer.ring
        val storeBefore = buffer.store

        buffer.clearGrid(penAttr = 37, viewportHeight = 3)

        assertAll(
            { assertSame(ringBefore, buffer.ring) },
            { assertSame(storeBefore, buffer.store) },
            { assertEquals(3, buffer.ring.size) },
            { assertEquals("", buffer.ring[0].toTextTrimmed()) },
            { assertEquals("", buffer.ring[1].toTextTrimmed()) },
            { assertEquals("", buffer.ring[2].toTextTrimmed()) },
            { assertEquals(37L, buffer.ring[0].getPackedAttr(0)) },
            { assertEquals(37L, buffer.ring[1].getPackedAttr(0)) },
            { assertEquals(37L, buffer.ring[2].getPackedAttr(0)) },
            { assertSame(storeBefore, buffer.ring[0].store) },
            { assertSame(storeBefore, buffer.ring[1].store) },
            { assertSame(storeBefore, buffer.ring[2].store) },
        )
    }

    @Test
    fun `clearGrid accepts zero viewport height and empties ring`() {
        val buffer = newBuffer(width = 8, height = 3, maxHistory = 2)
        buffer.clearGrid(penAttr = 19, viewportHeight = 3)

        val ringBefore = buffer.ring
        val storeBefore = buffer.store

        buffer.clearGrid(penAttr = 23, viewportHeight = 0)

        assertAll(
            { assertSame(ringBefore, buffer.ring) },
            { assertSame(storeBefore, buffer.store) },
            { assertEquals(0, buffer.ring.size) },
        )
    }

    @Test
    fun `replaceStorage swaps ring and store, clamps cursor bounds, and rebuilds blank arena`() {
        val buffer = newBuffer(width = 8, height = 3, maxHistory = 2)
        buffer.clearGrid(penAttr = 5, viewportHeight = 3)
        buffer.ring[0].setCell(0, 'X'.code, 5)

        buffer.cursor.col = 2
        buffer.cursor.row = 1
        buffer.cursor.pendingWrap = true
        buffer.savedCursor.col = 7
        buffer.savedCursor.row = 2
        buffer.savedCursor.attr = 123
        buffer.savedCursor.pendingWrap = true
        buffer.savedCursor.isOriginMode = true
        buffer.savedCursor.isSaved = true

        val ringBefore = buffer.ring
        val storeBefore = buffer.store

        buffer.replaceStorage(newWidth = 12, newHeight = 5, penAttr = 29)

        assertAll(
            { assertNotSame(ringBefore, buffer.ring) },
            { assertNotSame(storeBefore, buffer.store) },
            { assertEquals(7, buffer.ring.capacity) },
            { assertEquals(5, buffer.ring.size) },
            { assertEquals(0, buffer.scrollTop) },
            { assertEquals(4, buffer.scrollBottom) },
            { assertTrue(buffer.isFullViewportScroll(5)) },
            { assertEquals(12, buffer.ring[0].width) },
            { assertEquals("", buffer.ring[0].toTextTrimmed()) },
            { assertEquals("", buffer.ring[4].toTextTrimmed()) },
            { assertEquals(29L, buffer.ring[0].getPackedAttr(0)) },
            { assertEquals(29L, buffer.ring[4].getPackedAttr(0)) },
            { assertSame(buffer.store, buffer.ring[0].store) },
            { assertSame(buffer.store, buffer.ring[4].store) },
            // Cursor is preserved because it is within new bounds
            { assertEquals(2, buffer.cursor.col) },
            { assertEquals(1, buffer.cursor.row) },
            // THE FIX: pendingWrap MUST be false because col 2 is not at the new right margin (11)
            { assertFalse(buffer.cursor.pendingWrap, "pendingWrap must clear when not at right margin") },
            // Saved cursor is untouched by active buffer resizes
            { assertEquals(7, buffer.savedCursor.col) },
            { assertEquals(2, buffer.savedCursor.row) },
            { assertEquals(123, buffer.savedCursor.attr) },
            { assertFalse(buffer.savedCursor.pendingWrap, "Saved pendingWrap must clear when grid is wiped") },
            { assertTrue(buffer.savedCursor.isOriginMode) },
            { assertTrue(buffer.savedCursor.isSaved) },
        )
    }

    @Test
    fun `replaceStorage handles minimal alternate style configuration`() {
        val buffer = newBuffer(width = 4, height = 2, maxHistory = 0)

        buffer.replaceStorage(newWidth = 1, newHeight = 1, penAttr = 41)

        assertAll(
            { assertEquals(1, buffer.ring.capacity) },
            { assertEquals(1, buffer.ring.size) },
            { assertEquals(1, buffer.ring[0].width) },
            { assertEquals(0, buffer.scrollTop) },
            { assertEquals(0, buffer.scrollBottom) },
            { assertTrue(buffer.isFullViewportScroll(1)) },
            { assertEquals("", buffer.ring[0].toTextTrimmed()) },
            { assertEquals(41L, buffer.ring[0].getPackedAttr(0)) },
        )
    }

    @Test
    fun `replaceStorage clamps savedCursor bounds to prevent out-of-bounds restore crashes`() {
        val buffer = ScreenBuffer(initialWidth = 10, initialHeight = 10, maxHistory = 0)

        // Simulate a saved cursor at the far edge
        buffer.savedCursor.isSaved = true
        buffer.savedCursor.col = 9
        buffer.savedCursor.row = 9
        buffer.savedCursor.pendingWrap = true

        // Shrink the terminal massively
        buffer.replaceStorage(newWidth = 5, newHeight = 5, penAttr = 0)

        assertAll(
            { assertTrue(buffer.savedCursor.isSaved, "Saved flag should be preserved") },
            { assertEquals(4, buffer.savedCursor.col, "Saved Col MUST be clamped to new width") },
            { assertEquals(4, buffer.savedCursor.row, "Saved Row MUST be clamped to new height") },
            { assertFalse(buffer.savedCursor.pendingWrap, "Saved pending wrap MUST be broken if pushed inward") },
        )
    }

    @Test
    fun `kitty keyboard push pop stack logic`() {
        val buffer = newBuffer()

        // Initial state
        assertFalse(buffer.hasSavedInitialFlags)
        assertEquals(0, buffer.kittyKeyboardFlags)

        // Push 1: initial flags were 0 (currentFlags = 0)
        // new flags = 5
        var current = buffer.pushKittyKeyboardFlags(5, 0)
        assertEquals(5, current)
        assertEquals(5, buffer.kittyKeyboardFlags)
        assertTrue(buffer.hasSavedInitialFlags)
        assertEquals(0, buffer.kittyKeyboardInitialFlags)

        // Push 2: current flags are 5, push 12
        current = buffer.pushKittyKeyboardFlags(12, 5)
        assertEquals(12, current)
        assertEquals(12, buffer.kittyKeyboardFlags)

        // Pop 1 count
        current = buffer.popKittyKeyboardFlags(1, 12)
        assertEquals(5, current)
        assertEquals(5, buffer.kittyKeyboardFlags)

        // Pop 1 count
        current = buffer.popKittyKeyboardFlags(1, 5)
        assertEquals(0, current) // Restored initial flags
        assertEquals(0, buffer.kittyKeyboardFlags)

        // Pop past empty
        current = buffer.popKittyKeyboardFlags(1, 0)
        assertEquals(0, current)
        assertEquals(0, buffer.kittyKeyboardFlags)
    }

    @Test
    fun `kitty keyboard stack overflow evicts oldest entries`() {
        val buffer = newBuffer()

        // Push 33 times. Stack capacity is 32.
        // First push saves initial flags (say, 999).
        // Then we push 32 other values.
        val flags = 100
        buffer.pushKittyKeyboardFlags(flags, 999) // depth 1, stack[0] = 999
        for (i in 1..32) {
            buffer.pushKittyKeyboardFlags(flags + i, flags + i - 1)
        }

        // At this point, we pushed 33 times.
        // The first push had currentFlags = 999.
        // The last push was flags + 32, with currentFlags = flags + 31.
        // Stack capacity is 32, so one eviction occurred.
        // The oldest entry (999) should be evicted from the stack array, but the initial flags field (999) is preserved separately.
        // Let's verify by popping 32 times.
        var current = flags + 32
        for (i in 1..32) {
            current = buffer.popKittyKeyboardFlags(1, current)
        }
        // Popping the 32nd time should give us the oldest remaining entry on the stack (which is the state from push 2, i.e., currentFlags = 100).
        assertEquals(100, current)

        // The 33rd pop (popping past empty) should restore the initial flags (999) because initial flags are stored outside the stack!
        current = buffer.popKittyKeyboardFlags(1, current)
        assertEquals(999, current)
    }

    @Test
    fun `clearKittyKeyboardStack resets stack state`() {
        val buffer = newBuffer()
        buffer.pushKittyKeyboardFlags(5, 1)
        buffer.pushKittyKeyboardFlags(10, 5)

        buffer.clearKittyKeyboardStack()
        assertEquals(0, buffer.kittyKeyboardFlags)
        assertFalse(buffer.hasSavedInitialFlags)

        // Pop should now default to 0 since stack was cleared and hasSavedInitialFlags is false
        val current = buffer.popKittyKeyboardFlags(1, 0)
        assertEquals(0, current)
    }
}
