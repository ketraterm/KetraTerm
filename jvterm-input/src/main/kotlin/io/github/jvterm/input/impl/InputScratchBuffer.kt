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
package io.github.jvterm.input.impl

import io.github.jvterm.protocol.host.TerminalHostOutput

internal class InputScratchBuffer(
    private val bytes: ByteArray = ByteArray(DEFAULT_CAPACITY),
) {
    var length: Int = 0
        private set

    fun clear() {
        length = 0
    }

    fun appendByte(byte: Int) {
        require(byte in 0..255) { "byte out of range: $byte" }
        require(length < bytes.size) { "input scratch buffer overflow" }
        bytes[length] = byte.toByte()
        length++
    }

    fun appendAscii(text: String) {
        var i = 0
        while (i < text.length) {
            val ch = text[i].code
            require(ch in 0..0x7f) { "non-ASCII char in appendAscii: $ch" }
            appendByte(ch)
            i++
        }
    }

    fun appendDecimal(value: Int) {
        require(value >= 0) { "value must be non-negative: $value" }

        if (value == 0) {
            appendByte('0'.code)
            return
        }

        val start = length
        var current = value
        while (current > 0) {
            appendByte('0'.code + (current % 10))
            current /= 10
        }

        var left = start
        var right = length - 1
        while (left < right) {
            val tmp = bytes[left]
            bytes[left] = bytes[right]
            bytes[right] = tmp
            left++
            right--
        }
    }

    fun writeTo(output: TerminalHostOutput) {
        output.writeBytes(bytes, 0, length)
    }

    companion object {
        private const val DEFAULT_CAPACITY: Int = 64
    }
}
