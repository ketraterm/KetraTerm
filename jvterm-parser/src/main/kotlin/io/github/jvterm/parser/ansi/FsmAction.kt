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

/**
 * Internal FSM actions emitted by [AnsiStateMachine].
 *
 * Design rules:
 * - These are parser-internal transition outputs, not core-facing semantics.
 * - Actions must stay primitive and low-level.
 * - The action engine mutates ParserState in response to these actions.
 * - The command dispatcher translates completed control sequences to TerminalCommandSink calls.
 * - Do not smuggle business semantics or core API concepts into this layer.
 */
internal object FsmAction {
    /** No-op transition. */
    const val IGNORE: Int = 0

    /** Execute a C0 control byte in-place without changing parser-owned sequence state. */
    const val EXECUTE: Int = 1

    /** Execute control byte, then clear active escape/control sequence bookkeeping. */
    const val EXECUTE_AND_CLEAR: Int = 2

    /** Reset sequence accumulator state before entering a new escape/control sequence. */
    const val CLEAR_SEQUENCE: Int = 3

    /** Emit an ASCII-domain printable byte in GROUND. */
    const val PRINT_ASCII: Int = 4

    /** Route a non-ASCII byte into the UTF-8 decoder / printable path. */
    const val PRINT_UTF8: Int = 5

    /** Append one escape intermediate byte to parser state. */
    const val COLLECT_INTERMEDIATE: Int = 6

    /** Append one decimal digit to the active CSI parameter. */
    const val PARAM_DIGIT: Int = 7

    /** Terminate current CSI field with ';' and open the next field. */
    const val PARAM_SEPARATOR: Int = 8

    /** Terminate current CSI sub-parameter field with ':' and open the next field. */
    const val PARAM_COLON: Int = 9

    /** Record a CSI private marker byte (e.g. '?', '>', '=', '!'). */
    const val SET_PRIVATE_MARKER: Int = 10

    /** Dispatch an ESC final using the currently collected intermediate bytes and final byte. */
    const val ESC_DISPATCH: Int = 11

    /** Dispatch a CSI final using the currently accumulated params/intermediates/private marker. */
    const val CSI_DISPATCH: Int = 12

    /** Start bounded OSC payload collection. */
    const val OSC_START: Int = 13

    /** Append one ASCII-domain byte to the OSC payload buffer. */
    const val OSC_PUT_ASCII: Int = 14

    /** Append one non-ASCII raw byte to the OSC payload buffer. */
    const val OSC_PUT_UTF8: Int = 15

    /** Enter Milestone-A DCS bounded-ignore/passthrough mode. */
    const val DCS_IGNORE_START: Int = 16

    /** Append one ASCII-domain byte to bounded DCS scratch storage; Milestone A drops it on DCS_END. */
    const val DCS_PUT_ASCII: Int = 17

    /** Append one non-ASCII raw byte to bounded DCS scratch storage; Milestone A drops it on DCS_END. */
    const val DCS_PUT_UTF8: Int = 18

    /** Execute a C0 byte while inside OSC. BEL terminates; ordinary C0 is ignored. */
    const val OSC_EXECUTE_CONTROL: Int = 19

    /** Terminate OSC payload and dispatch bounded OSC payload. */
    const val OSC_END: Int = 20

    /** Terminate DCS passthrough. Milestone A drops bounded payload. */
    const val DCS_END: Int = 21

    /** Terminate ignored string payload. */
    const val STRING_END: Int = 22

    /** Total number of defined actions. */
    const val COUNT: Int = 23
}
