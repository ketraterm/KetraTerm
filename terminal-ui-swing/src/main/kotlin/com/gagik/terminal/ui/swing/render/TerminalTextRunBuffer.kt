package com.gagik.terminal.ui.swing.render

/**
 * Reusable mutable text buffer for Java2D draw calls.
 */
internal class TerminalTextRunBuffer(
    initialCapacity: Int,
) {
    init {
        require(initialCapacity > 0) { "initialCapacity must be > 0, was $initialCapacity" }
    }

    var chars: CharArray = CharArray(initialCapacity)
        private set

    var length: Int = 0
        private set

    /**
     * Resets the buffer without clearing its backing array.
     */
    fun clear() {
        length = 0
    }

    /**
     * Appends one printable ASCII code point.
     */
    fun appendAscii(codepoint: Int) {
        ensureCapacity(length + 1)
        chars[length] = codepoint.toChar()
        length++
    }

    /**
     * Appends one Unicode scalar value as UTF-16 code units.
     */
    fun appendCodePoint(codepoint: Int) {
        if (codepoint <= 0xffff) {
            ensureCapacity(length + 1)
            chars[length] = codepoint.toChar()
            length++
        } else {
            ensureCapacity(length + 2)
            val value = codepoint - 0x10000
            chars[length] = (0xd800 or (value ushr 10)).toChar()
            chars[length + 1] = (0xdc00 or (value and 0x3ff)).toChar()
            length += 2
        }
    }

    private fun ensureCapacity(required: Int) {
        if (required <= chars.size) return

        var newCapacity = chars.size * 2
        while (newCapacity < required) {
            newCapacity *= 2
        }
        chars = chars.copyOf(newCapacity)
    }
}
