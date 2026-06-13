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

import io.github.jvterm.core.api.TerminalModeBits
import io.github.jvterm.input.event.TerminalFocusEvent
import io.github.jvterm.protocol.host.TerminalHostOutput
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test

class FocusEncoderTest {
    @Test
    fun `focus disabled writes no bytes`() {
        assertBytes(ByteArray(0), TerminalFocusEvent(focused = true))
        assertBytes(ByteArray(0), TerminalFocusEvent(focused = false))
    }

    @Test
    fun `focus enabled true writes focus in`() {
        assertBytes(
            expected = esc("[I"),
            event = TerminalFocusEvent(focused = true),
            modeBits = TerminalModeBits.FOCUS_REPORTING,
        )
    }

    @Test
    fun `focus enabled false writes focus out`() {
        assertBytes(
            expected = esc("[O"),
            event = TerminalFocusEvent(focused = false),
            modeBits = TerminalModeBits.FOCUS_REPORTING,
        )
    }

    private fun assertBytes(
        expected: ByteArray,
        event: TerminalFocusEvent,
        modeBits: Long = 0L,
    ) {
        val output = RecordingHostOutput()
        val encoder = FocusEncoder(output)

        encoder.encode(event, modeBits)

        assertArrayEquals(expected, output.bytes)
    }

    private fun esc(textAfterEsc: String): ByteArray = byteArrayOf(0x1b) + textAfterEsc.encodeToByteArray()

    private class RecordingHostOutput : TerminalHostOutput {
        var bytes: ByteArray = ByteArray(0)
            private set

        override fun writeByte(byte: Int) {
            bytes += byte.toByte()
        }

        override fun writeBytes(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) {
            this.bytes += bytes.copyOfRange(offset, offset + length)
        }

        override fun writeAscii(text: String) {
            bytes += text.encodeToByteArray()
        }

        override fun writeUtf8(text: String) {
            bytes += text.encodeToByteArray()
        }
    }
}
