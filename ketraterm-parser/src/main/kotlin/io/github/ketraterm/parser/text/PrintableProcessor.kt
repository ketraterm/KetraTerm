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
package io.github.ketraterm.parser.text

import io.github.ketraterm.parser.charset.CharsetMapper
import io.github.ketraterm.parser.runtime.ParserState
import io.github.ketraterm.parser.spi.TerminalCommandSink
import io.github.ketraterm.parser.unicode.GraphemeAssembler

/**
 * Printable ingress bridge.
 *
 * Responsibilities:
 * - Applies parser printable policy before grapheme assembly.
 * - Keeps ActionEngine free from UTF-8 and Unicode segmentation details.
 *
 * Current policy:
 * - ASCII bytes are accepted through [acceptAsciiByte].
 * - U+FFFD replacement output is treated as normal printable input.
 * - GL charset mapping is applied through [CharsetMapper] before grapheme assembly.
 * - Grapheme segmentation is delegated to [io.github.ketraterm.parser.unicode.GraphemeAssembler].
 */
internal class PrintableProcessor(
    private val sink: TerminalCommandSink,
    private val graphemeAssembler: GraphemeAssembler = GraphemeAssembler(sink),
) {
    /**
     * Accepts one ASCII-domain printable byte from the ANSI FSM GROUND state.
     */
    fun acceptAsciiByte(
        state: ParserState,
        byteValue: Int,
    ) {
        require(byteValue in 0x20..0x7e) { "byteValue is not printable ASCII: $byteValue" }
        acceptCodepoint(state, byteValue)
    }

    /** Accepts a non-empty printable ASCII slice with identity charset mapping already checked. */
    fun acceptAsciiRun(
        state: ParserState,
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) {
        graphemeAssembler.acceptAsciiRun(state, bytes, offset, length)
    }

    /**
     * Accepts one Unicode codepoint from the ANSI FSM GROUND state.
     *
     * The top-level parser calls this after UTF-8 decoding. The processor applies
     * GL charset mapping and handles grapheme assembly and forwarding to the sink.
     */
    fun acceptDecodedCodepoint(
        state: ParserState,
        codepoint: Int,
    ) {
        require(codepoint in 0..0x10ffff) { "invalid codepoint: $codepoint" }
        acceptCodepoint(state, codepoint)
    }

    /**
     * Flushes active grapheme state before structural parser actions.
     */
    fun flush(state: ParserState) {
        graphemeAssembler.flush(state)
    }

    /**
     * Publishes the active grapheme prefix for live rendering while retaining
     * enough parser context to extend that cell if a later byte continues the
     * same grapheme across a host read boundary.
     */
    fun flushForRender(state: ParserState) {
        graphemeAssembler.flushForRender(state)
    }

    fun reset(state: ParserState) {
        graphemeAssembler.reset(state)
    }

    private fun acceptCodepoint(
        state: ParserState,
        codepoint: Int,
    ) {
        val mapped = CharsetMapper.map(state, codepoint)
        graphemeAssembler.accept(state, mapped)
    }
}
