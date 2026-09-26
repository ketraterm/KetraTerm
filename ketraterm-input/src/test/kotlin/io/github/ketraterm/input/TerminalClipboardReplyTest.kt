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
package io.github.ketraterm.input

import io.github.ketraterm.protocol.TerminalClipboardSelection
import io.github.ketraterm.protocol.host.TerminalHostOutput
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

class TerminalClipboardReplyTest {
    private val selection = checkNotNull(TerminalClipboardSelection.parse("c"))

    @Test
    fun `reply preserves text and uses padded Base64 and seven bit ST`() {
        val cases =
            listOf(
                "" to "",
                "a" to "YQ==",
                "ab" to "YWI=",
                "abc" to "YWJj",
                "é🙂\r\n\u001b\u0000" to "w6nwn5mCDQobAA==",
            )
        for ((text, base64) in cases) {
            val output = Output()
            checkNotNull(TerminalClipboardReply.prepare(selection, text, 100, 200)).use { reply ->
                reply.writeTo(output)
                assertEquals("\u001b]52;c;" + base64 + "\u001b\\", output.bytes.toString(Charsets.US_ASCII))
                assertEquals(output.bytes.size, reply.byteCount)
            }
        }
    }

    @Test
    fun `raw and wire limits are checked before any partial response can be written`() {
        assertNotNull(TerminalClipboardReply.prepare(selection, "", 0, 9)?.also { it.close() })
        assertNull(TerminalClipboardReply.prepare(selection, "", 0, 8))
        assertNull(TerminalClipboardReply.prepare(selection, "é🙂", 5, 100))
        assertNull(TerminalClipboardReply.prepare(selection, "é🙂", 6, 16))
        checkNotNull(TerminalClipboardReply.prepare(selection, "é🙂", 6, 17)).use {
            assertEquals(17, it.byteCount)
        }
        for (text in listOf("\ud800", "\udfff", "\ud800x", "x\udfff")) {
            assertNull(TerminalClipboardReply.prepare(selection, text, Int.MAX_VALUE, Int.MAX_VALUE))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TerminalClipboardReply.prepare(selection, "", -1, 100)
        }
    }

    @Test
    fun `large reply uses bounded ranges and close clears retained payload`() {
        val output = Output()
        val reply = checkNotNull(TerminalClipboardReply.prepare(selection, "x".repeat(65536), 65536, 100000))
        reply.writeTo(output)
        assertTrue(output.largestWrite <= 16384)
        val borrowed = checkNotNull(output.borrowed)
        assertTrue(borrowed.any { it != 0.toByte() })
        reply.close()
        assertTrue(borrowed.all { it == 0.toByte() })
        assertThrows(IllegalStateException::class.java) { reply.writeTo(output) }
    }

    private class Output : TerminalHostOutput {
        private val captured = ByteArrayOutputStream()
        val bytes: ByteArray get() = captured.toByteArray()
        var borrowed: ByteArray? = null
        var largestWrite = 0

        override fun writeByte(byte: Int) = captured.write(byte)

        override fun writeBytes(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) {
            borrowed = bytes
            largestWrite = maxOf(largestWrite, length)
            captured.write(bytes, offset, length)
        }

        override fun writeAscii(text: String) = captured.write(text.toByteArray(Charsets.US_ASCII))

        override fun writeUtf8(text: String) = error("Reply must not use paste/text encoding")
    }
}
