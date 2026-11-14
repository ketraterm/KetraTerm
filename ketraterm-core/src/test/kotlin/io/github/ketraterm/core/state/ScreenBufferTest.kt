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
package io.github.ketraterm.core.state

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
    fun `clearGrid reuses ring and row storage and fills viewport with blank lines`() {
        val buffer = newBuffer(width = 8, height = 3, maxHistory = 2)
        buffer.clearGrid(penAttr = 11, viewportHeight = 3)
        buffer.ring[0].setCell(0, 'A'.code, 11)
        repeat(2) { buffer.ring.push().clear(11) }

        val ringBefore = buffer.ring

        buffer.clearGrid(penAttr = 37, viewportHeight = 3)

        assertAll(
            { assertSame(ringBefore, buffer.ring) },
            { assertEquals(3, buffer.ring.size) },
            { assertEquals("", buffer.ring[0].toTextTrimmed()) },
            { assertEquals("", buffer.ring[1].toTextTrimmed()) },
            { assertEquals("", buffer.ring[2].toTextTrimmed()) },
            { assertEquals(37L, buffer.ring[0].getPackedAttr(0)) },
            { assertEquals(37L, buffer.ring[1].getPackedAttr(0)) },
            { assertEquals(37L, buffer.ring[2].getPackedAttr(0)) },
        )
    }

    @Test
    fun `clearGrid accepts zero viewport height and empties ring`() {
        val buffer = newBuffer(width = 8, height = 3, maxHistory = 2)
        buffer.clearGrid(penAttr = 19, viewportHeight = 3)

        val ringBefore = buffer.ring

        buffer.clearGrid(penAttr = 23, viewportHeight = 0)

        assertAll(
            { assertSame(ringBefore, buffer.ring) },
            { assertEquals(0, buffer.ring.size) },
        )
    }

    @Test
    fun `replaceStorage replaces row storage, clamps cursor bounds, and rebuilds blank arena`() {
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

        buffer.replaceStorage(newWidth = 12, newHeight = 5, penAttr = 29)

        assertAll(
            { assertNotSame(ringBefore, buffer.ring) },
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

        buffer.replaceStorage(newWidth = 2, newHeight = 1, penAttr = 41)

        assertAll(
            { assertEquals(1, buffer.ring.capacity) },
            { assertEquals(1, buffer.ring.size) },
            { assertEquals(2, buffer.ring[0].width) },
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
    fun `setting flags establishes an active baseline restored after a push`() {
        val buffer = newBuffer()
        assertEquals(0, buffer.kittyKeyboardDepth)
        assertEquals(0, buffer.kittyKeyboardFlags)
        assertEquals(1, buffer.setKittyKeyboardFlags(1))
        assertEquals(1, buffer.kittyKeyboardDepth)
        assertEquals(8, buffer.pushKittyKeyboardFlags(8))
        assertEquals(2, buffer.kittyKeyboardDepth)
        assertEquals(1, buffer.popKittyKeyboardFlags(1))
        assertEquals(1, buffer.kittyKeyboardDepth)
        assertEquals(0, buffer.popKittyKeyboardFlags(1))
        assertEquals(0, buffer.kittyKeyboardDepth)
        assertEquals(0, buffer.popKittyKeyboardFlags(1))
    }

    @Test
    fun `a first push preserves Kitty's default zero baseline`() {
        val buffer = newBuffer()
        assertEquals(8, buffer.pushKittyKeyboardFlags(8))
        assertEquals(2, buffer.kittyKeyboardDepth)
        assertEquals(0, buffer.popKittyKeyboardFlags(1))
        assertEquals(1, buffer.kittyKeyboardDepth)
        assertEquals(0, buffer.popKittyKeyboardFlags(1))
        assertEquals(0, buffer.kittyKeyboardDepth)
    }

    @Test
    fun `setting flags replaces the current entry without changing depth or saved entries`() {
        val buffer = newBuffer()
        buffer.setKittyKeyboardFlags(1)
        buffer.pushKittyKeyboardFlags(8)
        assertEquals(12, buffer.setKittyKeyboardFlags(12))
        assertEquals(2, buffer.kittyKeyboardDepth)
        assertEquals(1, buffer.popKittyKeyboardFlags(1))
        assertEquals(0, buffer.setKittyKeyboardFlags(0))
        assertEquals(1, buffer.kittyKeyboardDepth)
        buffer.pushKittyKeyboardFlags(16)
        assertEquals(0, buffer.popKittyKeyboardFlags(1))
        assertEquals(1, buffer.kittyKeyboardDepth)
    }

    @Test
    fun `screen stack stores only flags supported by the input encoder`() {
        val buffer = newBuffer()
        assertEquals(31, buffer.setKittyKeyboardFlags(Int.MAX_VALUE))
        assertEquals(0, buffer.pushKittyKeyboardFlags(32))
        assertEquals(31, buffer.popKittyKeyboardFlags(1))
    }

    @Test
    fun `counted pops remove active entries including a set baseline`() {
        for (count in intArrayOf(Int.MIN_VALUE, -1, 0, 1, 2, 3, 4, Int.MAX_VALUE)) {
            val buffer = newBuffer()
            buffer.setKittyKeyboardFlags(9)
            buffer.pushKittyKeyboardFlags(1)
            buffer.pushKittyKeyboardFlags(2)
            buffer.pushKittyKeyboardFlags(4)
            val expected =
                when {
                    count <= 0 -> 4
                    count == 1 -> 2
                    count == 2 -> 1
                    count == 3 -> 9
                    else -> 0
                }
            assertEquals(expected, buffer.popKittyKeyboardFlags(count), "count=$count")
            assertEquals(expected, buffer.kittyKeyboardFlags, "count=$count")
            assertEquals(4 - count.coerceIn(0, 4), buffer.kittyKeyboardDepth, "count=$count")
        }
    }

    @Test
    fun `capacity includes the current entry and evicts only the oldest entry`() {
        val buffer = newBuffer()
        buffer.setKittyKeyboardFlags(16)
        for (flags in 1..31) buffer.pushKittyKeyboardFlags(flags)
        assertEquals(32, buffer.kittyKeyboardDepth)
        buffer.pushKittyKeyboardFlags(8)
        assertEquals(32, buffer.kittyKeyboardDepth)
        assertEquals(31, buffer.popKittyKeyboardFlags(1))
        for (expected in 30 downTo 1) assertEquals(expected, buffer.popKittyKeyboardFlags(1))
        assertEquals(1, buffer.kittyKeyboardDepth)
        assertEquals(0, buffer.popKittyKeyboardFlags(1))
        assertEquals(0, buffer.kittyKeyboardDepth)
        assertEquals(0, buffer.popKittyKeyboardFlags(Int.MAX_VALUE))
    }

    @Test
    fun `counted pops handle repeated overflow and saturated counts`() {
        for (count in intArrayOf(0, 1, 31, 32, 33, 64, Int.MAX_VALUE)) {
            val buffer = newBuffer()
            buffer.setKittyKeyboardFlags(1)
            repeat(100) { buffer.pushKittyKeyboardFlags(4) }
            assertEquals(32, buffer.kittyKeyboardDepth)
            val expected = if (count < 32) 4 else 0
            assertEquals(expected, buffer.popKittyKeyboardFlags(count), "count=$count")
            assertEquals(expected, buffer.kittyKeyboardFlags, "count=$count")
        }
    }

    @Test
    fun `empty and cleared stacks cannot restore stale entries in a new cycle`() {
        val buffer = newBuffer()
        assertEquals(0, buffer.popKittyKeyboardFlags(Int.MAX_VALUE))
        buffer.setKittyKeyboardFlags(9)
        buffer.pushKittyKeyboardFlags(8)
        buffer.clearKittyKeyboardStack()
        assertEquals(0, buffer.kittyKeyboardFlags)
        assertEquals(0, buffer.kittyKeyboardDepth)
        assertEquals(0, buffer.popKittyKeyboardFlags(0))
        assertEquals(0, buffer.popKittyKeyboardFlags(Int.MAX_VALUE))
        buffer.pushKittyKeyboardFlags(2)
        assertEquals(0, buffer.popKittyKeyboardFlags(1))
        buffer.setKittyKeyboardFlags(4)
        buffer.pushKittyKeyboardFlags(1)
        assertEquals(4, buffer.popKittyKeyboardFlags(1))
        assertEquals(0, buffer.popKittyKeyboardFlags(1))
    }
}
