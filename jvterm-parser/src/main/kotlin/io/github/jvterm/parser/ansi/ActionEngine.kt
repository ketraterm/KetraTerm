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
package io.github.jvterm.parser.ansi

import io.github.jvterm.parser.ansi.dcs.DcsDispatcher
import io.github.jvterm.parser.ansi.osc.OscDispatcher
import io.github.jvterm.parser.runtime.ParserState
import io.github.jvterm.parser.spi.TerminalCommandSink
import io.github.jvterm.protocol.ControlCode

/**
 * Executes parser-internal FSM actions against [ParserState].
 *
 * Boundaries:
 * - Owns parser-state mutation.
 * - Flushes pending printable output before structural commands/dispatch.
 * - Delegates semantic control-sequence translation to [CommandDispatcher].
 * - Does not own UTF-8 decoding internals beyond forwarding raw non-ASCII bytes.
 */
internal class ActionEngine(
    private val sink: TerminalCommandSink,
    private val dispatcher: CommandDispatcher,
    private val printableSink: PrintableActionSink,
) {
    /**
     * Executes one FSM action.
     *
     * @param state parser runtime state
     * @param nextState state after applying the transition
     * @param action parser-internal action id from [FsmAction]
     * @param byteValue raw input byte in range 0..255
     */
    fun execute(
        state: ParserState,
        nextState: Int,
        action: Int,
        byteValue: Int,
    ) {
        require(byteValue in 0..255) { "byteValue out of range: $byteValue" }

        when (action) {
            FsmAction.IGNORE -> {
                state.fsmState = nextState
            }

            FsmAction.EXECUTE -> {
                executeControl(state, nextState, byteValue, clearAfter = false)
            }

            FsmAction.EXECUTE_AND_CLEAR -> {
                executeControl(state, nextState, byteValue, clearAfter = true)
            }

            FsmAction.CLEAR_SEQUENCE -> {
                flushPrintable(state)
                state.clearSequenceState()
                state.fsmState = nextState
            }

            FsmAction.PRINT_ASCII -> {
                state.fsmState = nextState
                printableSink.onAsciiByte(state, byteValue)
            }

            FsmAction.PRINT_UTF8 -> {
                state.fsmState = nextState
                printableSink.onUtf8Byte(state, byteValue)
            }

            FsmAction.COLLECT_INTERMEDIATE -> {
                flushPrintable(state)
                collectIntermediate(state, byteValue)
                state.fsmState = nextState
            }

            FsmAction.PARAM_DIGIT -> {
                flushPrintable(state)
                appendParamDigit(state, byteValue)
                state.fsmState = nextState
            }

            FsmAction.PARAM_SEPARATOR -> {
                flushPrintable(state)
                appendParamSeparator(state)
                state.fsmState = nextState
            }

            FsmAction.PARAM_COLON -> {
                flushPrintable(state)
                appendParamColon(state)
                state.fsmState = nextState
            }

            FsmAction.SET_PRIVATE_MARKER -> {
                flushPrintable(state)
                setPrivateMarker(state, byteValue)
                state.fsmState = nextState
            }

            FsmAction.ESC_DISPATCH -> {
                flushPrintable(state)
                dispatcher.dispatchEsc(
                    sink = sink,
                    state = state,
                    finalByte = byteValue,
                )
                state.clearSequenceState()
                state.fsmState = nextState
            }

            FsmAction.CSI_DISPATCH -> {
                flushPrintable(state)
                dispatcher.dispatchCsi(
                    sink = sink,
                    state = state,
                    finalByte = byteValue,
                )
                state.clearSequenceState()
                state.fsmState = nextState
            }

            FsmAction.OSC_START -> {
                flushPrintable(state)
                state.clearSequenceState()
                state.clearPayloadState()

                // OSC command code is not fully parsed yet.
                // Milestone A only accumulates bounded payload bytes.
                state.payloadCode = -1
                state.fsmState = nextState
            }

            FsmAction.OSC_PUT_ASCII -> {
                putPayloadByte(state, byteValue)
                state.fsmState = nextState
            }

            FsmAction.OSC_PUT_UTF8 -> {
                putPayloadByte(state, byteValue)
                state.fsmState = nextState
            }

            FsmAction.DCS_IGNORE_START -> {
                flushPrintable(state)
                state.clearPayloadState()
                putPayloadByte(state, byteValue)
                state.fsmState = nextState
            }

            FsmAction.DCS_PUT_ASCII -> {
                putPayloadByte(state, byteValue)
                state.fsmState = nextState
            }

            FsmAction.DCS_PUT_UTF8 -> {
                putPayloadByte(state, byteValue)
                state.fsmState = nextState
            }

            FsmAction.OSC_EXECUTE_CONTROL -> {
                if (byteValue == ControlCode.BEL) {
                    OscDispatcher.dispatch(
                        sink = sink,
                        payload = state.payloadBuffer,
                        length = state.payloadLength,
                        overflowed = state.payloadOverflowed,
                    )
                    state.clearPayloadState()
                    state.clearSequenceState()
                    state.fsmState = AnsiState.GROUND
                } else {
                    // Ordinary C0 inside OSC is ignored.
                    state.fsmState = nextState
                }
            }

            FsmAction.OSC_END -> {
                OscDispatcher.dispatch(
                    sink = sink,
                    payload = state.payloadBuffer,
                    length = state.payloadLength,
                    overflowed = state.payloadOverflowed,
                )
                state.clearPayloadState()
                state.clearSequenceState()
                state.fsmState = nextState
            }

            FsmAction.DCS_END -> {
                DcsDispatcher.dispatch(
                    sink = sink,
                    payload = state.payloadBuffer,
                    length = state.payloadLength,
                    overflowed = state.payloadOverflowed,
                )
                state.clearPayloadState()
                state.clearSequenceState()
                state.fsmState = nextState
            }

            FsmAction.STRING_END -> {
                state.clearPayloadState()
                state.clearSequenceState()
                state.fsmState = nextState
            }

            else -> error("Unknown FsmAction: $action")
        }
    }

    private fun executeControl(
        state: ParserState,
        nextState: Int,
        byteValue: Int,
        clearAfter: Boolean,
    ) {
        // Controls are structural in this path.
        flushPrintable(state)
        dispatcher.executeControl(
            sink = sink,
            state = state,
            controlByte = byteValue,
        )

        if (clearAfter) {
            state.clearSequenceState()
        }
        state.fsmState = nextState
    }

    private fun flushPrintable(state: ParserState) {
        printableSink.flush(state)
    }

    private fun collectIntermediate(
        state: ParserState,
        byteValue: Int,
    ) {
        if (state.intermediateCount >= 4) {
            return
        }
        state.intermediates = state.intermediates or (byteValue shl (state.intermediateCount * 8))
        state.intermediateCount++
    }

    private fun appendParamDigit(
        state: ParserState,
        byteValue: Int,
    ) {
        val digit = byteValue - '0'.code
        require(digit in 0..9) { "Expected decimal digit byte, got: $byteValue" }

        if (state.paramCount == 0) {
            if (!openParamField(state, openedByColon = false)) return
        }

        val index = state.paramCount - 1
        val current = state.params[index]

        state.params[index] =
            if (!state.currentParamStarted || current < 0) {
                digit
            } else {
                saturatingAppendDecimal(current, digit)
            }

        state.currentParamStarted = true
    }

    private fun appendParamSeparator(state: ParserState) {
        if (state.paramCount == 0) {
            if (!openParamField(state, openedByColon = false)) return
        }

        openParamField(state, openedByColon = false)
        state.currentParamStarted = false
    }

    private fun appendParamColon(state: ParserState) {
        if (state.paramCount == 0) {
            if (!openParamField(state, openedByColon = false)) return
        }

        openParamField(state, openedByColon = true)
        state.currentParamStarted = false
    }

    private fun setPrivateMarker(
        state: ParserState,
        byteValue: Int,
    ) {
        if (state.privateMarker == 0) {
            state.privateMarker = byteValue
        }
    }

    private fun openParamField(
        state: ParserState,
        openedByColon: Boolean,
    ): Boolean {
        val index = state.paramCount
        if (index >= state.params.size) {
            return false
        }

        state.params[index] = -1
        state.paramCount++

        if (openedByColon && index < 32) {
            state.subParameterMask = state.subParameterMask or (1 shl index)
        }

        return true
    }

    private fun putPayloadByte(
        state: ParserState,
        byteValue: Int,
    ) {
        if (state.payloadOverflowed) {
            return
        }

        if (state.payloadLength >= state.payloadBuffer.size) {
            state.payloadOverflowed = true
            return
        }

        state.payloadBuffer[state.payloadLength] = byteValue.toByte()
        state.payloadLength++
    }

    private fun saturatingAppendDecimal(
        value: Int,
        digit: Int,
    ): Int =
        if (value > 214_748_363) {
            Int.MAX_VALUE
        } else {
            value * 10 + digit
        }
}

/**
 * Narrow bridge for printable ingress and grapheme buffering.
 *
 * This keeps ActionEngine from knowing UTF-8 decoder details or grapheme storage details.
 */
internal interface PrintableActionSink {
    fun onAsciiByte(
        state: ParserState,
        byteValue: Int,
    )

    fun onUtf8Byte(
        state: ParserState,
        byteValue: Int,
    )

    fun flush(state: ParserState)
}
