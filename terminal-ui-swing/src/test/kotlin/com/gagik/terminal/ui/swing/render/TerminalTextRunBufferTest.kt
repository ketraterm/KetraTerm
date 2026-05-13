package com.gagik.terminal.ui.swing.render

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertSame

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
}
