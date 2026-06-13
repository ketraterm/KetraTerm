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
import io.github.jvterm.input.event.TerminalPasteEvent
import io.github.jvterm.input.policy.PasteSanitizationPolicy
import io.github.jvterm.input.policy.TerminalInputPolicy
import io.github.jvterm.protocol.host.TerminalHostOutput
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test

class PasteEncoderTest {
    @Test
    fun `plain paste writes UTF-8 text`() {
        assertBytes("plain é".encodeToByteArray(), TerminalPasteEvent("plain é"))
    }

    @Test
    fun `bracketed paste wraps UTF-8 text`() {
        assertBytes(
            expected = esc("[200~") + "text".encodeToByteArray() + esc("[201~"),
            event = TerminalPasteEvent("text"),
            modeBits = TerminalModeBits.BRACKETED_PASTE,
        )
    }

    @Test
    fun `bracketed paste preserves newlines by default`() {
        assertBytes(
            expected = esc("[200~") + "a\r\nb\nc\rd".encodeToByteArray() + esc("[201~"),
            event = TerminalPasteEvent("a\r\nb\nc\rd"),
            modeBits = TerminalModeBits.BRACKETED_PASTE,
        )
    }

    @Test
    fun `empty bracketed paste still emits wrappers`() {
        assertBytes(
            expected = esc("[200~") + esc("[201~"),
            event = TerminalPasteEvent(""),
            modeBits = TerminalModeBits.BRACKETED_PASTE,
        )
    }

    @Test
    fun `raw paste preserves escape controls`() {
        assertBytes(
            expected = byteArrayOf('a'.code.toByte(), 0x1b, 'b'.code.toByte()),
            event = TerminalPasteEvent("a\u001bb"),
        )
    }

    @Test
    fun `strip C0 paste policy removes controls except tab cr and lf`() {
        assertBytes(
            expected =
                byteArrayOf(
                    'a'.code.toByte(),
                    0x09,
                    'b'.code.toByte(),
                    0x0d,
                    'c'.code.toByte(),
                    0x0a,
                    'd'.code.toByte(),
                    'e'.code.toByte(),
                ),
            event = TerminalPasteEvent("a\tb\rc\nd\u001be\u0000"),
            policy =
                TerminalInputPolicy(
                    pasteSanitizationPolicy = PasteSanitizationPolicy.STRIP_C0_EXCEPT_TAB_CR_LF,
                ),
        )
    }

    @Test
    fun `strip C0 paste policy preserves non ASCII text`() {
        assertBytes(
            expected = "Ã©😀".encodeToByteArray(),
            event = TerminalPasteEvent("\u001bÃ©😀"),
            policy =
                TerminalInputPolicy(
                    pasteSanitizationPolicy = PasteSanitizationPolicy.STRIP_C0_EXCEPT_TAB_CR_LF,
                ),
        )
    }

    @Test
    fun `normalize line endings paste policy maps crlf and cr to lf`() {
        assertBytes(
            expected = "a\nb\nc\nd".encodeToByteArray(),
            event = TerminalPasteEvent("a\r\nb\rc\nd"),
            policy =
                TerminalInputPolicy(
                    pasteSanitizationPolicy = PasteSanitizationPolicy.NORMALIZE_LINE_ENDINGS,
                ),
        )
    }

    @Test
    fun `bracketed paste applies sanitization inside wrappers`() {
        assertBytes(
            expected = esc("[200~") + "ab".encodeToByteArray() + esc("[201~"),
            event = TerminalPasteEvent("a\u001bb"),
            modeBits = TerminalModeBits.BRACKETED_PASTE,
            policy =
                TerminalInputPolicy(
                    pasteSanitizationPolicy = PasteSanitizationPolicy.STRIP_C0_EXCEPT_TAB_CR_LF,
                ),
        )
    }

    private fun assertBytes(
        expected: ByteArray,
        event: TerminalPasteEvent,
        modeBits: Long = 0L,
        policy: TerminalInputPolicy = TerminalInputPolicy(),
    ) {
        val output = RecordingHostOutput()
        val encoder = PasteEncoder(output, InputScratchBuffer(), policy)

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
