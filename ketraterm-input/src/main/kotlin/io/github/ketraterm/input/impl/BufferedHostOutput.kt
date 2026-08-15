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
package io.github.ketraterm.input.impl

import io.github.ketraterm.protocol.host.TerminalHostOutput

/** Bounded reusable coalescing sink for repeated input sequences. */
internal class BufferedHostOutput(
    private val delegate: TerminalHostOutput,
    capacity: Int = DEFAULT_CAPACITY,
) : TerminalHostOutput {
    private val buffer: ByteArray
    private var length = 0

    init {
        require(capacity > 0) { "capacity must be > 0, was $capacity" }
        buffer = ByteArray(capacity)
    }

    override fun writeByte(byte: Int) {
        require(byte in 0..0xff) { "byte must be in 0..255, was $byte" }
        if (length == buffer.size) flush()
        buffer[length++] = byte.toByte()
    }

    override fun writeBytes(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length) {
            "invalid byte range: offset=$offset, length=$length, size=${bytes.size}"
        }
        var sourceOffset = offset
        var remaining = length
        while (remaining > 0) {
            if (this.length == buffer.size) flush()
            val copyLength = minOf(remaining, buffer.size - this.length)
            bytes.copyInto(
                destination = buffer,
                destinationOffset = this.length,
                startIndex = sourceOffset,
                endIndex = sourceOffset + copyLength,
            )
            this.length += copyLength
            sourceOffset += copyLength
            remaining -= copyLength
        }
    }

    override fun writeAscii(text: String) {
        for (character in text) {
            require(character.code <= 0x7f) { "non-ASCII character: ${character.code}" }
            writeByte(character.code)
        }
    }

    override fun writeUtf8(text: String) {
        val bytes = text.encodeToByteArray()
        writeBytes(bytes, 0, bytes.size)
    }

    fun flush() {
        if (length == 0) return
        delegate.writeBytes(buffer, 0, length)
        length = 0
    }

    fun reset() {
        length = 0
    }

    private companion object {
        private const val DEFAULT_CAPACITY = 4 * 1024
    }
}
