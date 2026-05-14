package com.gagik.terminal.session

import com.gagik.terminal.protocol.host.TerminalHostOutput
import com.gagik.terminal.transport.TerminalConnector

/**
 * A zero-allocation, thread-safe bridge between terminal input encoding and
 * the transport connector.
 *
 * This implementation pre-allocates reusable buffers to encode ASCII and
 * UTF-8 text without allocating new [ByteArray] instances on every keypress
 * or paste event. It ensures all writes are synchronized through the session's
 * outbound write lock to maintain correct multiplexing order with core responses.
 */
internal class ConnectorTerminalHostOutput(
    private val connector: TerminalConnector,
    private val writeLock: Any,
) : TerminalHostOutput {
    private val one = ByteArray(1)
    private val asciiBuffer = ByteArray(ASCII_BUFFER_SIZE)
    private val utf8Buffer = ByteArray(UTF8_BUFFER_SIZE)

    override fun writeByte(byte: Int) {
        require(byte in 0..255) { "Host byte must be in 0..255, got $byte" }

        synchronized(writeLock) {
            one[0] = byte.toByte()
            connector.write(one, 0, 1)
        }
    }

    override fun writeBytes(bytes: ByteArray, offset: Int, length: Int) {
        require(offset >= 0) { "offset must be non-negative, got $offset" }
        require(length >= 0) { "length must be non-negative, got $length" }
        require(offset <= bytes.size) { "offset $offset exceeds size ${bytes.size}" }
        require(length <= bytes.size - offset) {
            "offset + length exceeds size: offset=$offset length=$length size=${bytes.size}"
        }

        synchronized(writeLock) {
            connector.write(bytes, offset, length)
        }
    }

    override fun writeAscii(text: String) {
        synchronized(writeLock) {
            var offset = 0
            while (offset < text.length) {
                val count = minOf(asciiBuffer.size, text.length - offset)
                var index = 0
                while (index < count) {
                    val code = text[offset + index].code
                    require(code in 0..0x7F) { "Non-ASCII character at index ${offset + index}: $code" }
                    asciiBuffer[index] = code.toByte()
                    index++
                }

                connector.write(asciiBuffer, 0, count)
                offset += count
            }
        }
    }

    /**
     * Manually encodes a string to UTF-8 using a reusable buffer.
     * This avoids allocating a new byte array for every paste or multi-byte input.
     */
    override fun writeUtf8(text: String) {
        synchronized(writeLock) {
            var bufferOffset = 0
            var charIndex = 0
            while (charIndex < text.length) {
                val codepoint: Int
                val ch = text[charIndex]

                if (Character.isHighSurrogate(ch)) {
                    if (charIndex + 1 < text.length && Character.isLowSurrogate(text[charIndex + 1])) {
                        codepoint = Character.toCodePoint(ch, text[charIndex + 1])
                        charIndex += 2
                    } else {
                        codepoint = REPLACEMENT_CODEPOINT
                        charIndex++
                    }
                } else if (Character.isLowSurrogate(ch)) {
                    codepoint = REPLACEMENT_CODEPOINT
                    charIndex++
                } else {
                    codepoint = ch.code
                    charIndex++
                }

                val bytesNeeded = when {
                    codepoint <= 0x7F -> 1
                    codepoint <= 0x7FF -> 2
                    codepoint <= 0xFFFF -> 3
                    else -> 4
                }

                if (bufferOffset + bytesNeeded > utf8Buffer.size) {
                    connector.write(utf8Buffer, 0, bufferOffset)
                    bufferOffset = 0
                }

                when (bytesNeeded) {
                    1 -> utf8Buffer[bufferOffset++] = codepoint.toByte()
                    2 -> {
                        utf8Buffer[bufferOffset++] = (0xC0 or (codepoint shr 6)).toByte()
                        utf8Buffer[bufferOffset++] = (0x80 or (codepoint and 0x3F)).toByte()
                    }
                    3 -> {
                        utf8Buffer[bufferOffset++] = (0xE0 or (codepoint shr 12)).toByte()
                        utf8Buffer[bufferOffset++] = (0x80 or ((codepoint shr 6) and 0x3F)).toByte()
                        utf8Buffer[bufferOffset++] = (0x80 or (codepoint and 0x3F)).toByte()
                    }
                    else -> {
                        utf8Buffer[bufferOffset++] = (0xF0 or (codepoint shr 18)).toByte()
                        utf8Buffer[bufferOffset++] = (0x80 or ((codepoint shr 12) and 0x3F)).toByte()
                        utf8Buffer[bufferOffset++] = (0x80 or ((codepoint shr 6) and 0x3F)).toByte()
                        utf8Buffer[bufferOffset++] = (0x80 or (codepoint and 0x3F)).toByte()
                    }
                }
            }

            if (bufferOffset > 0) {
                connector.write(utf8Buffer, 0, bufferOffset)
            }
        }
    }

    private companion object {
        const val ASCII_BUFFER_SIZE: Int = 1024
        const val UTF8_BUFFER_SIZE: Int = 8192
        const val REPLACEMENT_CODEPOINT: Int = 0xFFFD
    }
}
