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
package io.github.ketraterm.parser.ansi

import io.github.ketraterm.parser.ansi.dcs.DcsDispatcher
import io.github.ketraterm.parser.ansi.osc.OscDispatcher
import io.github.ketraterm.parser.runtime.ParserState
import io.github.ketraterm.parser.spi.TerminalCommandSink
import io.github.ketraterm.protocol.ControlCode

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
    private val clipboardWriteLimitBytes: () -> Int = { 0 },
) {
    private val oscDispatcher = OscDispatcher()

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

            FsmAction.PARAM_SEPARATOR, FsmAction.PARAM_COLON -> {
                flushPrintable(state)
                appendParamSeparator(state, openedByColon = action == FsmAction.PARAM_COLON)
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
                if (!state.paramsOverflowed) {
                    dispatcher.dispatchCsi(
                        sink = sink,
                        state = state,
                        finalByte = byteValue,
                    )
                }
                state.clearSequenceState()
                state.fsmState = nextState
            }

            FsmAction.OSC_START -> {
                flushPrintable(state)
                state.clearSequenceState()
                state.clearPayloadState()

                state.fsmState = nextState
            }

            FsmAction.OSC_PUT_ASCII -> {
                putOscPayloadByte(state, byteValue)
                state.fsmState = nextState
            }

            FsmAction.OSC_PUT_UTF8 -> {
                putOscPayloadByte(state, byteValue)
                state.fsmState = nextState
            }

            FsmAction.DCS_IGNORE_START -> {
                flushPrintable(state)
                state.clearPayloadState()
                putDcsPayloadByte(state, byteValue)
                state.fsmState = nextState
            }

            FsmAction.DCS_PUT_ASCII -> {
                putDcsPayloadByte(state, byteValue)
                state.fsmState = nextState
            }

            FsmAction.DCS_PUT_UTF8 -> {
                putDcsPayloadByte(state, byteValue)
                state.fsmState = nextState
            }

            FsmAction.OSC_EXECUTE_CONTROL -> {
                if (byteValue == ControlCode.BEL) {
                    finishOsc(state, AnsiState.GROUND)
                } else {
                    // Ordinary C0 inside OSC is ignored.
                    state.fsmState = nextState
                }
            }

            FsmAction.OSC_END -> {
                finishOsc(state, nextState)
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
        if (state.paramsOverflowed) return

        if (state.paramCount == 0) {
            if (!openParamField(state, openedByColon = false)) return
        }

        val index = state.paramCount - 1
        val current = state.params[index]

        state.params[index] =
            if (!state.currentParamStarted || current < 0) {
                digit
            } else {
                if (current > (Int.MAX_VALUE - digit) / 10) {
                    state.parameterValueSaturated = true
                    Int.MAX_VALUE
                } else {
                    current * 10 + digit
                }
            }

        state.currentParamStarted = true
    }

    private fun appendParamSeparator(
        state: ParserState,
        openedByColon: Boolean,
    ) {
        if (state.paramsOverflowed) return
        if (state.paramCount == 0) {
            if (!openParamField(state, openedByColon = false)) return
        }

        openParamField(state, openedByColon)
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
            state.paramsOverflowed = true
            return false
        }

        state.params[index] = -1
        state.paramCount++
        state.currentParamStarted = false

        if (openedByColon) {
            state.subParameterMask = state.subParameterMask or (1 shl index)
        }

        return true
    }

    private fun putOscPayloadByte(
        state: ParserState,
        byteValue: Int,
    ) {
        if (!state.payloadOverflowed &&
            state.clipboardDataStart >= 0 &&
            state.payloadLength == state.clipboardDataStart &&
            (
                byteValue in 'A'.code..'Z'.code ||
                    byteValue in 'a'.code..'z'.code ||
                    byteValue in '0'.code..'9'.code ||
                    byteValue == '+'.code ||
                    byteValue == '/'.code
            )
        ) {
            // Queries and empty writes never need larger storage. Permissions remain host-owned.
            val limit = ControlStringPolicy.clipboardLimit(state.clipboardDataStart, clipboardWriteLimitBytes())
            state.payloadLimit = maxOf(state.payloadLimit, limit)
        }
        if (!putPayloadByte(state, byteValue)) return
        if (!state.payloadHeaderComplete && byteValue == ';'.code) {
            state.payloadCode = ControlStringPolicy.oscCommand(state.payloadBuffer, state.payloadLength - 1)
            selectPayloadLimit(state, ControlStringPolicy.oscLimit(state.payloadCode))
        } else if (state.payloadCode == 52) {
            if (state.clipboardDataStart < 0 && byteValue == ';'.code) {
                state.clipboardDataStart = state.payloadLength
            }
        }
    }

    private fun finishOsc(
        state: ParserState,
        nextState: Int,
    ) {
        try {
            oscDispatcher.dispatch(
                sink = sink,
                payload = state.payloadBuffer,
                length = state.payloadLength,
                overflowed = state.payloadOverflowed,
                payloadLimit = state.payloadLimit,
            )
        } finally {
            state.clearPayloadState()
            state.clearSequenceState()
            state.fsmState = nextState
        }
    }

    private fun putDcsPayloadByte(
        state: ParserState,
        byteValue: Int,
    ) {
        if (!putPayloadByte(state, byteValue)) return
        if (!state.payloadHeaderComplete && state.payloadLength == 2) {
            selectPayloadLimit(state, ControlStringPolicy.dcsLimit(state.payloadBuffer[0].toInt() and 0xff, byteValue))
        }
    }

    private fun selectPayloadLimit(
        state: ParserState,
        limit: Int,
    ) {
        state.payloadHeaderComplete = true
        state.payloadLimit = minOf(state.payloadLimit, limit)
        if (state.payloadLimit > 0 && state.payloadLength > state.payloadLimit) state.payloadOverflowed = true
    }

    private fun putPayloadByte(
        state: ParserState,
        byteValue: Int,
    ): Boolean {
        if (state.payloadOverflowed || state.payloadLimit == 0) {
            return false
        }

        if (state.payloadLength >= state.payloadLimit) {
            state.discardOverflowedPayload()
            return false
        }

        if (state.payloadLength == state.payloadBuffer.size) {
            val capacity = minOf(state.payloadBuffer.size.toLong() * 2, state.payloadLimit.toLong()).toInt()
            state.payloadBuffer = state.payloadBuffer.copyOf(capacity)
        }

        state.payloadBuffer[state.payloadLength] = byteValue.toByte()
        state.payloadLength++
        return true
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
