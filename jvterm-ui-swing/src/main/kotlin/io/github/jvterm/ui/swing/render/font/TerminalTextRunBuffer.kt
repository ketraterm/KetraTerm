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

/**
 * Reusable mutable text buffer for Java2D draw calls.
 *
 * **Security:** This buffer employs a hard capacity limit to prevent Out-Of-Memory (OOM)
 * exhaustion from hostile terminal streams (e.g., unbounded single-line text dumps).
 *
 * @param initialCapacity Starting size of the char array.
 * @param maxCapacity Absolute upper bound for array growth.
 */
internal class TerminalTextRunBuffer(
    initialCapacity: Int,
    // Safe upper bound for a physical terminal row
    private val maxCapacity: Int = 8192,
) {
    init {
        require(initialCapacity > 0) { "initialCapacity must be > 0, was $initialCapacity" }
        require(maxCapacity >= initialCapacity) { "maxCapacity must be >= initialCapacity" }
    }

    /** The backing array of UTF-16 code units. */
    var chars: CharArray = CharArray(initialCapacity)
        private set

    /** The number of UTF-16 code units in the buffer. */
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
     * Drops the char if maximum capacity is reached.
     *
     * @param codepoint ASCII code point
     */
    fun appendAscii(codepoint: Int) {
        if (!ensureCapacity(length + 1)) return
        chars[length] = codepoint.toChar()
        length++
    }

    /**
     * Appends one Unicode scalar value as UTF-16 code units.
     * Drops the char if maximum capacity is reached.
     *
     * @param codepoint Unicode scalar value
     */
    fun appendCodePoint(codepoint: Int) {
        if (codepoint <= 0xffff) {
            if (!ensureCapacity(length + 1)) return
            chars[length] = codepoint.toChar()
            length++
        } else {
            if (!ensureCapacity(length + 2)) return
            val value = codepoint - 0x10000
            chars[length] = (0xd800 or (value ushr 10)).toChar()
            chars[length + 1] = (0xdc00 or (value and 0x3ff)).toChar()
            length += 2
        }
    }

    private fun ensureCapacity(required: Int): Boolean {
        if (required > maxCapacity) return false
        if (required <= chars.size) return true

        var newCapacity = chars.size
        while (newCapacity < required) {
            val doubled = newCapacity * 2
            newCapacity =
                if (doubled !in 1..maxCapacity) {
                    maxCapacity
                } else {
                    doubled
                }
        }
        chars = chars.copyOf(newCapacity)
        return true
    }
}
