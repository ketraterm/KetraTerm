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
    fun accept(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size,
    )

    fun acceptByte(byteValue: Int)

    fun endOfInput()

    fun reset()
}
