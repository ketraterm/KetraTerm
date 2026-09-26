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
import java.util.*

/**
 * Owned, prevalidated OSC 52 response. Preparation does not write any bytes.
 * Serialize [writeTo] with all other terminal output, then [close] to clear the
 * owned Base64 buffer. Closing and writing must not run concurrently.
 */
class TerminalClipboardReply private constructor(
    private val selection: TerminalClipboardSelection,
    private val payload: ByteArray,
) : AutoCloseable {
    private var closed = false

    /** Complete wire size, including the OSC introducer, selectors and ST. */
    val byteCount: Int = selection.value.length + ENVELOPE_BYTES + payload.size

    /** Writes one complete response using bounded borrowed byte ranges. */
    fun writeTo(output: TerminalHostOutput) {
        check(!closed) { "Clipboard reply is closed" }
        output.writeAscii("\u001b]52;")
        output.writeAscii(selection.value)
        output.writeByte(';'.code)
        var offset = 0
        while (offset < payload.size) {
            val count = minOf(WRITE_CHUNK_BYTES, payload.size - offset)
            output.writeBytes(payload, offset, count)
            offset += count
        }
        output.writeAscii("\u001b\\")
    }

    override fun close() {
        payload.fill(0)
        closed = true
    }

    companion object {
        /**
         * Validates scalar UTF-16 and both byte budgets before allocating UTF-8
         * or Base64 storage. Null means malformed text or a size/overflow limit.
         * Text is preserved exactly; paste policy and terminal modes do not apply.
         */
        fun prepare(
            selection: TerminalClipboardSelection,
            text: String,
            maxDecodedBytes: Int,
            maxWireBytes: Int,
        ): TerminalClipboardReply? {
            require(maxDecodedBytes >= 0 && maxWireBytes >= 0)
            val available = maxWireBytes - selection.value.length - ENVELOPE_BYTES
            if (available < 0) return null
            // Division first keeps padded Base64 arithmetic within Int bounds.
            val byteLimit = minOf(maxDecodedBytes, available / 4 * 3)
            if (text.length > byteLimit) return null
            var count = 0L
            var index = 0
            while (index < text.length) {
                val ch = text[index++]
                count +=
                    when {
                        ch.code < 0x80 -> 1
                        ch.code < 0x800 -> 2
                        ch.isHighSurrogate() -> {
                            if (index == text.length || !text[index].isLowSurrogate()) return null
                            index++
                            4
                        }
                        ch.isLowSurrogate() -> return null
                        else -> 3
                    }
                if (count > byteLimit) return null
            }
            val utf8 = text.toByteArray(Charsets.UTF_8)
            return try {
                TerminalClipboardReply(selection, Base64.getEncoder().encode(utf8))
            } finally {
                utf8.fill(0)
            }
        }

        private const val ENVELOPE_BYTES = 8
        private const val WRITE_CHUNK_BYTES = 16 * 1024
    }
}
