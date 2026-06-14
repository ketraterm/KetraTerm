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
package io.github.jvterm.parser.api

import io.github.jvterm.parser.spi.TerminalCommandSink

/**
 * Byte-stream parser contract for terminal host output.
 *
 * Implementations accept raw bytes from PTY/network/process output, preserve parser state across
 * arbitrary chunk boundaries, and emit semantic terminal operations to a [TerminalCommandSink].
 */
interface TerminalOutputParser {
    /**
     * Feeds a chunk of bytes from the terminal host output stream to the parser.
     *
     * The parser processes the byte sequence, transitions its internal state machine,
     * and dispatches recognized commands to the bound command sink.
     *
     * @param bytes The byte array containing raw terminal output.
     * @param offset The index of the first byte to process in [bytes].
     * @param length The number of bytes to process.
     */
    fun accept(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size,
    )

    /**
     * Feeds a single byte from the terminal host output stream to the parser.
     *
     * @param byteValue The byte value (in range 0..255) to process.
     */
    fun acceptByte(byteValue: Int)

    /**
     * Signals the end of the input stream.
     *
     * The parser will process any remaining buffered state or incomplete sequences
     * as appropriate before final termination.
     */
    fun endOfInput()

    /**
     * Resets the internal parser state, discarding any partially parsed sequences
     * or control signatures.
     */
    fun reset()
}
