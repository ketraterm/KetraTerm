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

import io.github.jvterm.parser.ansi.AnsiState.ESCAPE
import io.github.jvterm.parser.ansi.AnsiState.GROUND

/**
 * ANSI parser finite-state machine states.
 *
 * Design rules:
 * - States model parser control-flow topology, not business meaning.
 * - Charset shift/designation state is NOT represented here.
 * - UTF-8 decoding state is NOT represented here.
 * - Grapheme buffering state is NOT represented here.
 * - Payload accumulation length/code is NOT represented here.
 *
 * This enum-free integer layout is intentional for hot-path friendliness.
 */
internal object AnsiState {
    /**
     * Default state.
     * Printable ASCII is emitted as printable input.
     * C0 controls execute immediately.
     * ESC enters [ESCAPE].
     */
    const val GROUND: Int = 0

    /**
     * Immediately after ESC.
     * Routes introducers like ESC [ / ] / P and handles plain ESC final dispatch.
     */
    const val ESCAPE: Int = 1

    /**
     * ESC followed by one or more intermediate bytes (0x20..0x2F).
     * Awaits the final byte for ESC dispatch.
     */
    const val ESCAPE_INTERMEDIATE: Int = 2

    /**
     * Immediately after entering CSI.
     * Accepts private markers, parameters, intermediates, or direct final dispatch.
     */
    const val CSI_ENTRY: Int = 3

    /**
     * Collecting CSI parameters (digits, separators, colon sub-parameters).
     */
    const val CSI_PARAM: Int = 4

    /**
     * Collecting CSI intermediate bytes after parameters.
     */
    const val CSI_INTERMEDIATE: Int = 5

    /**
     * Invalid CSI sequence tail.
     * Input is ignored until a terminating condition returns the machine to [GROUND].
     */
    const val CSI_IGNORE: Int = 6

    /**
     * Collecting OSC payload bytes until BEL or ST.
     * Parser may accumulate bounded payload or ignore, depending on milestone/features.
     */
    const val OSC_STRING: Int = 7

    /**
     * Immediately after entering DCS.
     * Present so DCS can later support params/intermediates cleanly without reshaping the FSM.
     * In Milestone A, this can collapse quickly into ignore/passthrough behavior.
     */
    const val DCS_ENTRY: Int = 8

    /**
     * DCS payload state.
     * In Milestone A this is expected to behave as bounded ignore until ST.
     */
    const val DCS_PASSTHROUGH: Int = 9

    /**
     * Ignored string payload (SOS / PM / APC family).
     * Input is ignored until ST.
     */
    const val SOS_PM_APC_STRING: Int = 10

    /**
     * Generic ignore-until-ST state.
     * Useful for malformed string/control sequences that must drain safely.
     */
    const val IGNORE_UNTIL_ST: Int = 11

    /** ESC seen while inside OSC string; awaiting ST terminator decision. */
    const val OSC_ESCAPE: Int = 12

    /** ESC seen while inside DCS passthrough; awaiting ST terminator decision. */
    const val DCS_ESCAPE: Int = 13

    /** ESC seen while inside SOS/PM/APC ignored string; awaiting ST terminator decision. */
    const val SOS_PM_APC_ESCAPE: Int = 14

    /** ESC seen while inside generic ignore-until-ST; awaiting ST terminator decision. */
    const val IGNORE_UNTIL_ST_ESCAPE: Int = 15

    /** Total number of FSM states. */
    const val COUNT: Int = 16

    @JvmStatic
    fun isStringState(state: Int): Boolean {
        require(state in 0 until COUNT) { "state out of range: $state" }
        return state == OSC_STRING ||
            state == OSC_ESCAPE ||
            state == DCS_PASSTHROUGH ||
            state == DCS_ESCAPE ||
            state == SOS_PM_APC_STRING ||
            state == SOS_PM_APC_ESCAPE ||
            state == IGNORE_UNTIL_ST ||
            state == IGNORE_UNTIL_ST_ESCAPE
    }

    @JvmStatic
    fun isCsiState(state: Int): Boolean {
        require(state in 0 until COUNT) { "state out of range: $state" }
        return state == CSI_ENTRY || state == CSI_PARAM || state == CSI_INTERMEDIATE || state == CSI_IGNORE
    }
}
