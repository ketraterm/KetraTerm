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
package io.github.jvterm.ui.swing.render.font

import kotlin.test.*

class TerminalTextRunBufferTest {
    @Test
    fun clearKeepsBackingArrayForReuse() {
        val buffer = TerminalTextRunBuffer(initialCapacity = 4)
        buffer.appendAscii('a'.code)
        val chars = buffer.chars

        buffer.clear()
        buffer.appendAscii('b'.code)

        assertSame(chars, buffer.chars)
        assertEquals(1, buffer.length)
        assertEquals('b', buffer.chars[0])
    }

    @Test
    fun appendsSupplementaryCodepointsAsSurrogatePairs() {
        val buffer = TerminalTextRunBuffer(initialCapacity = 1)

        buffer.appendCodePoint(0x1f600)

        assertEquals(2, buffer.length)
        assertContentEquals(charArrayOf('\ud83d', '\ude00'), buffer.chars.copyOf(buffer.length))
    }

    @Test
    fun `appendAscii drops characters exceeding maximum capacity boundary`() {
        // Arrange
        val buffer = TerminalTextRunBuffer(initialCapacity = 2, maxCapacity = 4)

        // Act
        buffer.appendAscii('a'.code)
        buffer.appendAscii('b'.code)
        buffer.appendAscii('c'.code)
        buffer.appendAscii('d'.code)
        buffer.appendAscii('e'.code) // Exceeds max capacity

        // Assert
        assertEquals(4, buffer.length, "Buffer length must be rigidly capped at maxCapacity")
        assertTrue(buffer.chars.size <= 4, "Backing array must not allocate beyond maxCapacity")
        assertEquals('d', buffer.chars[3], "The last valid slot must contain the fourth append")
    }

    @Test
    fun `appendCodePoint drops multi unit supplementary glyph if it overflows max capacity`() {
        // Arrange
        val buffer = TerminalTextRunBuffer(initialCapacity = 2, maxCapacity = 3)

        // Act
        buffer.appendAscii('a'.code) // length = 1
        buffer.appendCodePoint(0x1f600) // Requires 2 units, length becomes 3
        buffer.appendCodePoint(0x1f600) // Exceeds max capacity completely, must be dropped

        // Assert
        assertEquals(3, buffer.length)
    }

    @Test
    fun `appendCodePoint cleanly drops multi unit scalar if single trailing slot remains`() {
        // Arrange
        val buffer = TerminalTextRunBuffer(initialCapacity = 2, maxCapacity = 3)

        // Act
        buffer.appendAscii('a'.code) // length = 1
        buffer.appendAscii('b'.code) // length = 2
        buffer.appendCodePoint(0x1f600) // Requires 2 units, but only 1 slots remains. Must drop entirely.

        // Assert
        assertEquals(2, buffer.length, "Partial surrogate pairs must never be appended to prevent text corruption")
        assertEquals('b', buffer.chars[1])
    }
}
