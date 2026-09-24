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

import io.github.ketraterm.core.api.TerminalModeBits
import io.github.ketraterm.input.event.TerminalPasteEvent
import io.github.ketraterm.input.policy.PasteControlPolicy
import io.github.ketraterm.input.policy.PasteLineEndingPolicy
import io.github.ketraterm.input.policy.TerminalInputPolicy
import io.github.ketraterm.protocol.host.TerminalHostOutput
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

class PasteEncoderTest {
    @Test
    fun `bracketed paste neutralizes embedded delimiters and interrupt controls`() {
        assertBytes(
            expected = esc("[200~") + "a\u241b[201~b\u2403c\\u009b201~d\u241b[200~".encodeToByteArray() + esc("[201~"),
            event = TerminalPasteEvent("a\u001b[201~b\u0003c\u009b201~d\u001b[200~"),
            modeBits = TerminalModeBits.BRACKETED_PASTE,
        )
    }

    @Test
    fun `bracketed paste cannot reconstruct a delimiter around another delimiter`() {
        assertBytes(
            expected = esc("[200~") + "\u241b\u241b[201~[201~\r\nnext".encodeToByteArray() + esc("[201~"),
            event = TerminalPasteEvent("\u001b\u001b[201~[201~\r\nnext"),
            modeBits = TerminalModeBits.BRACKETED_PASTE,
        )
    }

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
    fun `unbracketed paste canonicalizes all line endings to CRLF when configured`() {
        assertBytes(
            expected = "a\r\nb\r\nc\r\nd".encodeToByteArray(),
            event = TerminalPasteEvent("a\r\nb\nc\rd"),
            policy =
                TerminalInputPolicy(
                    pasteLineEndingPolicy = PasteLineEndingPolicy.CARRIAGE_RETURN_AND_LINE_FEED,
                ),
        )
    }

    @Test
    fun `bracketed paste preserves original line endings despite canonicalization policy`() {
        assertBytes(
            expected = esc("[200~") + "a\r\nb\nc\rd".encodeToByteArray() + esc("[201~"),
            event = TerminalPasteEvent("a\r\nb\nc\rd"),
            modeBits = TerminalModeBits.BRACKETED_PASTE,
            policy =
                TerminalInputPolicy(
                    pasteLineEndingPolicy = PasteLineEndingPolicy.CARRIAGE_RETURN_AND_LINE_FEED,
                ),
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
    fun `unbracketed preserve policy retains escape controls`() {
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
                    pasteControlPolicy = PasteControlPolicy.STRIP_C0_EXCEPT_TAB_CR_LF,
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
                    pasteControlPolicy = PasteControlPolicy.STRIP_C0_EXCEPT_TAB_CR_LF,
                ),
        )
    }

    @Test
    fun `explicit LF policy maps crlf and cr to lf`() {
        assertBytes(
            expected = "a\nb\nc\nd".encodeToByteArray(),
            event = TerminalPasteEvent("a\r\nb\rc\nd"),
            policy =
                TerminalInputPolicy(
                    pasteLineEndingPolicy = PasteLineEndingPolicy.LINE_FEED,
                ),
        )
    }

    @Test
    fun `host line ending policy governs normalized unbracketed paste`() {
        assertBytes(
            expected = "a\rb\rc\rd".encodeToByteArray(),
            event = TerminalPasteEvent("a\r\nb\rc\nd"),
            policy =
                TerminalInputPolicy(
                    pasteLineEndingPolicy = PasteLineEndingPolicy.CARRIAGE_RETURN,
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
                    pasteControlPolicy = PasteControlPolicy.STRIP_C0_EXCEPT_TAB_CR_LF,
                ),
        )
    }

    @Test
    fun `every control policy protects framing and preserves bracketed line endings`() {
        for (controls in PasteControlPolicy.entries) {
            for (lineEndings in PasteLineEndingPolicy.entries) {
                val payload =
                    when (controls) {
                        PasteControlPolicy.PRESERVE -> "\u0000\u241b[201~\u2403\\u009b201~\t\r\n\r\n"
                        PasteControlPolicy.STRIP_C0_EXCEPT_TAB_CR_LF -> "[201~\\u009b201~\t\r\n\r\n"
                    }
                assertBytes(
                    expected = esc("[200~") + payload.encodeToByteArray() + esc("[201~"),
                    event = TerminalPasteEvent("\u0000\u001b[201~\u0003\u009b201~\t\r\n\r\n"),
                    modeBits = TerminalModeBits.BRACKETED_PASTE,
                    policy = TerminalInputPolicy(pasteControlPolicy = controls, pasteLineEndingPolicy = lineEndings),
                )
            }
        }
    }

    @Test
    fun `control filtering and unbracketed newline policies compose independently`() {
        for (controls in PasteControlPolicy.entries) {
            for (lineEndings in PasteLineEndingPolicy.entries) {
                val control = if (controls == PasteControlPolicy.PRESERVE) "\u0000" else ""
                val expected =
                    when (lineEndings) {
                        PasteLineEndingPolicy.PRESERVE -> "${control}a\r\nb\rc\nd\t"
                        PasteLineEndingPolicy.LINE_FEED -> "${control}a\nb\nc\nd\t"
                        PasteLineEndingPolicy.CARRIAGE_RETURN -> "${control}a\rb\rc\rd\t"
                        PasteLineEndingPolicy.CARRIAGE_RETURN_AND_LINE_FEED -> "${control}a\r\nb\r\nc\r\nd\t"
                    }
                assertBytes(
                    expected.encodeToByteArray(),
                    TerminalPasteEvent("\u0000a\r\nb\rc\nd\t"),
                    policy = TerminalInputPolicy(pasteControlPolicy = controls, pasteLineEndingPolicy = lineEndings),
                )
            }
        }
    }

    @Test
    fun `protection operates on Unicode characters rather than UTF8 continuation bytes`() {
        val text = "\u041b201~ \u009a \u009c \ud83d\ude00 e\u0301 \u001b[201~"
        assertBytes(
            expected = esc("[200~") + "\u041b201~ \u009a \u009c \ud83d\ude00 e\u0301 \u241b[201~".encodeToByteArray() + esc("[201~"),
            event = TerminalPasteEvent(text),
            modeBits = TerminalModeBits.BRACKETED_PASTE,
        )
    }

    @Test
    fun `all paste paths replace unpaired surrogates with the Unicode replacement character`() {
        for (controls in PasteControlPolicy.entries) {
            for (lineEndings in PasteLineEndingPolicy.entries) {
                for (mode in listOf(0L, TerminalModeBits.BRACKETED_PASTE)) {
                    val payload = "\ufffdx\ufffd\ud83d\ude00\ufffd".encodeToByteArray()
                    assertBytes(
                        expected = if (mode == 0L) payload else esc("[200~") + payload + esc("[201~"),
                        event = TerminalPasteEvent("\ud800x\udc00\ud83d\ude00\ud800"),
                        modeBits = mode,
                        policy = TerminalInputPolicy(pasteControlPolicy = controls, pasteLineEndingPolicy = lineEndings),
                    )
                }
            }
        }
    }

    @Test
    fun `framing and UTF8 survive every small output buffer boundary`() {
        for (capacity in 1..16) {
            for (prefixLength in 0..16) {
                val output = RecordingHostOutput()
                val encoder = PasteEncoder(output, InputScratchBuffer(), bufferedOutput = BufferedHostOutput(output, capacity))
                val prefix = "x".repeat(prefixLength)
                encoder.encode(
                    TerminalPasteEvent("$prefix\u001b\u001b[201~[201~\u009b201~\u0003\ud83d\ude00\r\n"),
                    TerminalModeBits.BRACKETED_PASTE,
                )
                encoder.encode(TerminalPasteEvent("next"), 0L)
                assertArrayEquals(
                    esc("[200~") + "$prefix\u241b\u241b[201~[201~\\u009b201~\u2403\ud83d\ude00\r\n".encodeToByteArray() + esc("[201~") +
                        "next".encodeToByteArray(),
                    output.bytes,
                    "capacity=$capacity, prefixLength=$prefixLength",
                )
            }
        }
    }

    @Test
    fun `transformed paste is coalesced and failed writes cannot leak into later input`() {
        val output = RecordingHostOutput()
        val encoder = PasteEncoder(output, InputScratchBuffer())
        val event = TerminalPasteEvent("\u001b" + "x".repeat(8192))
        encoder.encode(event, TerminalModeBits.BRACKETED_PASTE)
        assertEquals(3, output.writeCalls)
        assertArrayEquals(esc("[200~") + ("\u241b" + "x".repeat(8192)).encodeToByteArray() + esc("[201~"), output.bytes)

        output.failNextWrite = true
        assertThrows(IllegalStateException::class.java) { encoder.encode(event, TerminalModeBits.BRACKETED_PASTE) }
        val accepted = output.bytes
        encoder.encode(TerminalPasteEvent("\u001b[201~"), TerminalModeBits.BRACKETED_PASTE)
        assertArrayEquals(accepted + esc("[200~") + "\u241b[201~".encodeToByteArray() + esc("[201~"), output.bytes)
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
        private val buffer = ByteArrayOutputStream()
        val bytes: ByteArray get() = buffer.toByteArray()
        var writeCalls: Int = 0
            private set
        var failNextWrite: Boolean = false

        override fun writeByte(byte: Int) {
            writeBytes(byteArrayOf(byte.toByte()), 0, 1)
        }

        override fun writeBytes(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) {
            if (failNextWrite) {
                failNextWrite = false
                error("transport write failed")
            }
            writeCalls++
            buffer.write(bytes, offset, length)
        }

        override fun writeAscii(text: String) {
            writeUtf8(text)
        }

        override fun writeUtf8(text: String) {
            val encoded = text.encodeToByteArray()
            writeBytes(encoded, 0, encoded.size)
        }
    }
}
