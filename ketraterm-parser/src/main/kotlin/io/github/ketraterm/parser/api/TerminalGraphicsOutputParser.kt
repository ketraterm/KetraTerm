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
package io.github.ketraterm.parser.api

import io.github.ketraterm.protocol.TerminalGraphicsCommand

/**
 * Optional synchronous parser boundary for externally decoded image payloads.
 *
 * A session retains its ingress range and mutation admission while releasing its
 * core lock around [consumePendingGraphics]. No parser operation may run concurrently
 * or reenter the consumer. Ordinary [accept] retains its full-range sink-dispatch
 * contract; hosts requiring PNG/zlib decoding use [acceptUntilGraphics] instead.
 * Parsers returned by [TerminalParsers] implement this boundary.
 */
public interface TerminalGraphicsOutputParser : TerminalOutputParser {
    /** Whether a completed image payload awaits [consumePendingGraphics]. */
    public val hasPendingGraphics: Boolean

    /**
     * Consumes through the first graphics command requiring external decoding.
     * Other commands dispatch synchronously. A yielded command has not reached the
     * sink; consume it before supplying further bytes, including an empty range.
     *
     * @param bytes borrowed ingress storage, unchanged by parsing.
     * @param offset first byte of the selected range.
     * @param length number of bytes available from [offset].
     * @return consumed byte count, including the graphics string terminator;
     * equals [length] when no earlier yield occurred. Check [hasPendingGraphics]
     * even when all supplied bytes were consumed.
     * @throws IllegalArgumentException if the range is outside [bytes].
     * @throws IllegalStateException if a previous payload is pending or the parser is reentered.
     */
    public fun acceptUntilGraphics(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): Int

    /**
     * Consumes the pending controls and the used prefix of exclusively owned staging.
     * The consumer may compact staging in place. Neither controls nor bytes may
     * escape this callback. Staging is released on return, including failure.
     *
     * @param consumer synchronous image decoder/commit operation; arguments are
     * original controls, staging bytes, and their logical length.
     * @throws IllegalStateException if no payload is pending or the parser is reentered.
     */
    public fun consumePendingGraphics(consumer: (TerminalGraphicsCommand, ByteArray, Int) -> Unit)
}
