package com.gagik.parser.text

import com.gagik.parser.charset.CharsetMapper
import com.gagik.parser.runtime.ParserState
import com.gagik.parser.spi.TerminalCommandSink
import com.gagik.parser.unicode.GraphemeAssembler

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
 * - Grapheme segmentation is delegated to [com.gagik.parser.unicode.GraphemeAssembler].
 */
internal class PrintableProcessor(
    private val sink: TerminalCommandSink,
    private val graphemeAssembler: GraphemeAssembler = GraphemeAssembler(sink),
) {
    /**
     * Accepts one ASCII-domain printable byte from the ANSI FSM GROUND state.
     */
    fun acceptAsciiByte(state: ParserState, byteValue: Int) {
        require(byteValue in 0x20..0x7e) { "byteValue is not printable ASCII: $byteValue" }
        acceptCodepoint(state, byteValue)
    }

    /**
     * Accepts one Unicode codepoint from the ANSI FSM GROUND state.
     *
     * This is the only way to emit a codepoint from the parser.
     * The top-level parser must call this from the ActionEngine callback after UTF-8 decoding,
     * GL charset mapping, and any other policy decisions are applied.
     * The processor will handle grapheme assembly and forwarding to the sink.
     */
    fun acceptDecodedCodepoint(state: ParserState, codepoint: Int) {
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

    private fun acceptCodepoint(state: ParserState, codepoint: Int) {
        val mapped = CharsetMapper.map(state, codepoint)
        graphemeAssembler.accept(state, mapped)
    }
}
