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
package com.gagik.parser.impl

import com.gagik.parser.ansi.*
import com.gagik.parser.api.TerminalOutputParser
import com.gagik.parser.runtime.ParserState
import com.gagik.parser.spi.TerminalCommandSink
import com.gagik.parser.text.PrintableProcessor
import com.gagik.parser.text.PrintableProcessorActionSink
import com.gagik.parser.utf8.Utf8DecodeResult
import com.gagik.parser.utf8.Utf8Decoder

/**
 * Top-level terminal output parser.
 *
 * This class is the byte-stream orchestration layer.
 *
 * Responsibilities:
 * - Accept raw host-output bytes from PTY/network/process output.
 * - Preserve parser state across arbitrary chunk boundaries.
 * - Route bytes through UTF-8 decoding, ANSI FSM, ActionEngine, and CommandDispatcher.
 * - Flush partial UTF-8 / grapheme state at end-of-input or reset.
 *
 * Non-responsibilities:
 * - No keyboard/mouse/paste input encoding.
 * - No grid width/height knowledge.
 * - No cursor bounds checking.
 * - No renderer behavior.
 * - No terminal storage mutation except through [TerminalCommandSink].
 */
internal class TerminalParser(
    private val sink: TerminalCommandSink,
    private val state: ParserState = ParserState(),
) : TerminalOutputParser {
    private val utf8Decoder = Utf8Decoder()
    private val printableProcessor = PrintableProcessor(sink)

    private val actionEngine =
        ActionEngine(
            sink = sink,
            dispatcher = AnsiCommandDispatcher,
            printableSink = PrintableProcessorActionSink(printableProcessor),
        )

    /**
     * Accepts one chunk of host-output bytes.
     *
     * The chunk may end in the middle of:
     * - UTF-8 scalar
     * - CSI sequence
     * - OSC/DCS payload
     * - grapheme cluster
     */
    override fun accept(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) {
        require(offset >= 0) { "offset must be non-negative: $offset" }
        require(length >= 0) { "length must be non-negative: $length" }
        require(offset <= bytes.size) { "offset out of range: $offset" }
        require(offset + length <= bytes.size) {
            "offset + length out of range: offset=$offset length=$length size=${bytes.size}"
        }

        val end = offset + length
        var index = offset
        while (index < end) {
            acceptByteInternal(bytes[index].toInt() and 0xff)
            index++
        }
        flushPrintableForRender()
    }

    /**
     * Accepts one raw byte.
     *
     * Exposed for tests and for adapters that already chunk at byte granularity.
     */
    override fun acceptByte(byteValue: Int) {
        require(byteValue in 0..255) { "byteValue out of range: $byteValue" }
        acceptByteInternal(byteValue)
        flushPrintableForRender()
    }

    private fun acceptByteInternal(byteValue: Int) {
        if (utf8Decoder.hasPendingSequence()) {
            acceptUtf8Byte(byteValue, allowReplay = true)
            return
        }

        processByteNormally(byteValue, allowUtf8Replay = true)
    }

    /**
     * Flushes dangling UTF-8 and printable cluster state.
     *
     * This should be called when the host stream is closed or when the owner deliberately wants to
     * force pending printable state out.
     */
    override fun endOfInput() {
        val utf8Result = utf8Decoder.flushEndOfInput()
        emitUtf8Output(utf8Result)
        printableProcessor.flush(state)
    }

    /**
     * Resets all parser-owned state.
     *
     * This does not reset the terminal core. Core reset must be dispatched as an explicit terminal
     * command sequence such as RIS/DECSTR.
     */
    override fun reset() {
        utf8Decoder.reset()
        printableProcessor.reset(state)
        state.resetAll()
    }

    /**
     * Normal byte routing when no UTF-8 partial sequence is forcing priority.
     */
    private fun processByteNormally(
        byteValue: Int,
        allowUtf8Replay: Boolean,
    ) {
        val byteClass = ByteClass.classify(byteValue)

        if (byteClass == ByteClass.UTF8_PAYLOAD && state.fsmState == AnsiState.GROUND) {
            acceptUtf8Byte(byteValue, allowReplay = allowUtf8Replay)
            return
        }

        val transition = AnsiStateMachine.transition(state.fsmState, byteClass)
        val nextState = AnsiStateMachine.nextState(transition)
        val action = AnsiStateMachine.action(transition)

        actionEngine.execute(
            state = state,
            nextState = nextState,
            action = action,
            byteValue = byteValue,
        )
    }

    /**
     * Feeds one byte into the UTF-8 decoder.
     *
     * If the decoder reports REPROCESS_CURRENT_BYTE, the byte is routed back through the normal
     * ANSI byte path exactly once.
     *
     * This is essential for hostile streams such as:
     *
     * C3 ESC [ A
     *
     * Correct behavior:
     * - emit U+FFFD for broken UTF-8
     * - then process ESC [ A as cursor-up
     */
    private fun acceptUtf8Byte(
        byteValue: Int,
        allowReplay: Boolean,
    ) {
        val result = utf8Decoder.accept(byteValue)
        emitUtf8Output(result)

        if (!Utf8DecodeResult.shouldReprocessCurrentByte(result)) {
            return
        }

        check(allowReplay) {
            "Utf8Decoder requested repeated replay for byte $byteValue"
        }

        processByteNormally(
            byteValue = byteValue,
            allowUtf8Replay = false,
        )
    }

    private fun emitUtf8Output(result: Int) {
        if (Utf8DecodeResult.hasOutput(result)) {
            printableProcessor.acceptDecodedCodepoint(
                state = state,
                codepoint = Utf8DecodeResult.codepoint(result),
            )
        }
    }

    private fun flushPrintableForRender() {
        if (!utf8Decoder.hasPendingSequence() && state.fsmState == AnsiState.GROUND) {
            printableProcessor.flushForRender(state)
        }
    }
}
