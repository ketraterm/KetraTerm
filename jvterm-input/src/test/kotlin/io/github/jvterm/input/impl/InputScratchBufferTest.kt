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
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class InputScratchBufferTest {
    @Test
    fun `appends bytes and ascii text`() {
        val buffer = InputScratchBuffer(ByteArray(8))

        buffer.appendByte(0x1b)
        buffer.appendAscii("[A")

        assertAll(
            { assertEquals(3, buffer.length) },
            { assertOutput(byteArrayOf(0x1b, '['.code.toByte(), 'A'.code.toByte()), buffer) },
        )
    }

    @Test
    fun `rejects byte outside unsigned byte range`() {
        val buffer = InputScratchBuffer(ByteArray(4))

        assertAll(
            {
                assertThrows(IllegalArgumentException::class.java) {
                    buffer.appendByte(-1)
                }
            },
            {
                assertThrows(IllegalArgumentException::class.java) {
                    buffer.appendByte(256)
                }
            },
        )
    }

    @Test
    fun `rejects non ascii text`() {
        val buffer = InputScratchBuffer(ByteArray(8))

        assertThrows(IllegalArgumentException::class.java) {
            buffer.appendAscii("é")
        }
    }

    @Test
    fun `rejects writes beyond capacity`() {
        val buffer = InputScratchBuffer(ByteArray(2))

        buffer.appendByte('a'.code)
        buffer.appendByte('b'.code)

        assertThrows(IllegalArgumentException::class.java) {
            buffer.appendByte('c'.code)
        }
    }

    @Test
    fun `appends decimal values without disturbing prefix`() {
        val buffer = InputScratchBuffer(ByteArray(16))

        buffer.appendAscii("\u001b[")
        buffer.appendDecimal(12345)
        buffer.appendByte('~'.code)

        assertAll(
            { assertEquals(8, buffer.length) },
            { assertOutput(ascii("\u001b[12345~"), buffer) },
        )
    }

    @Test
    fun `appends zero decimal`() {
        val buffer = InputScratchBuffer(ByteArray(4))

        buffer.appendDecimal(0)

        assertOutput(ascii("0"), buffer)
    }

    @Test
    fun `rejects negative decimal`() {
        val buffer = InputScratchBuffer(ByteArray(4))

        assertThrows(IllegalArgumentException::class.java) {
            buffer.appendDecimal(-1)
        }
    }

    @Test
    fun `clear resets length and allows reuse`() {
        val buffer = InputScratchBuffer(ByteArray(8))

        buffer.appendAscii("first")
        buffer.clear()
        buffer.appendAscii("xy")

        assertAll(
            { assertEquals(2, buffer.length) },
            { assertOutput(ascii("xy"), buffer) },
        )
    }

    @Test
    fun `writeTo passes active range with offset zero`() {
        val buffer = InputScratchBuffer(ByteArray(8))
        val output = RecordingHostOutput()

        buffer.appendAscii("abc")
        buffer.writeTo(output)

        assertAll(
            { assertEquals(0, output.lastOffset) },
            { assertEquals(3, output.lastLength) },
            { assertArrayEquals(ascii("abc"), output.bytes) },
        )
    }

    @Test
    fun `writeTo allows immediate scratch reuse after output copies range`() {
        val buffer = InputScratchBuffer(ByteArray(8))
        val output = RecordingHostOutput()

        buffer.appendAscii("old")
        buffer.writeTo(output)
        buffer.clear()
        buffer.appendAscii("new")

        assertArrayEquals(ascii("old"), output.bytes)
    }

    private fun assertOutput(
        expected: ByteArray,
        buffer: InputScratchBuffer,
    ) {
        val output = RecordingHostOutput()
        buffer.writeTo(output)
        assertArrayEquals(expected, output.bytes)
    }

    private fun ascii(text: String): ByteArray {
        val bytes = ByteArray(text.length)
        var i = 0
        while (i < text.length) {
            bytes[i] = text[i].code.toByte()
            i++
        }
        return bytes
    }

    private class RecordingHostOutput : TerminalHostOutput {
        var bytes: ByteArray = ByteArray(0)
            private set
        var lastOffset: Int = -1
            private set
        var lastLength: Int = -1
            private set

        override fun writeByte(byte: Int) {
            bytes = byteArrayOf(byte.toByte())
            lastOffset = 0
            lastLength = 1
        }

        override fun writeBytes(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) {
            this.bytes = bytes.copyOfRange(offset, offset + length)
            lastOffset = offset
            lastLength = length
        }

        override fun writeAscii(text: String) {
            bytes = text.encodeToByteArray()
            lastOffset = 0
            lastLength = bytes.size
        }

        override fun writeUtf8(text: String) {
            bytes = text.encodeToByteArray()
            lastOffset = 0
            lastLength = bytes.size
        }
    }
}
