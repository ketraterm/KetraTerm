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

/**
 * Table-driven ANSI FSM transition matrix.
 *
 * Design rules:
 * - The matrix owns control-flow routing.
 * - The action engine owns parser-state mutation.
 * - The command dispatcher owns semantic handoff to the sink.
 * - This matrix handles the ASCII/control domain plus the top-level UTF-8 ingress lane.
 * - Non-ASCII bytes are only printable in [AnsiState.GROUND] by default.
 * - DCS is structurally present, but Milestone A keeps it in bounded ignore/passthrough mode.
 *
 * Entry encoding:
 * - high bits: next state
 * - low bits: action id
 */
internal object AnsiStateMachine {
    private const val CLASS_SHIFT: Int = 5 // 32 columns reserved
    private const val ACTION_BITS: Int = 8
    private const val ACTION_MASK: Int = (1 shl ACTION_BITS) - 1

    private const val COLUMN_COUNT: Int = 1 shl CLASS_SHIFT
    private val transitions: IntArray = IntArray(AnsiState.COUNT * COLUMN_COUNT)

    init {
        initializeDefaults()
        buildAnywhereRules()
        buildGround()
        buildEscape()
        buildEscapeIntermediate()
        buildCsi()
        buildOsc()
        buildDcs()
        buildStringEscapeStates()
        buildIgnoredStrings()
    }

    @JvmStatic
    fun transition(
        state: Int,
        byteClass: Int,
    ): Int {
        require(state in 0 until AnsiState.COUNT) { "state out of range: $state" }
        require(byteClass in 0 until ByteClass.ROUTING_COUNT) { "byteClass out of range: $byteClass" }
        return transitions[index(state, byteClass)]
    }

    @JvmStatic
    fun nextState(transition: Int): Int = transition ushr ACTION_BITS

    @JvmStatic
    fun action(transition: Int): Int = transition and ACTION_MASK

    private fun initializeDefaults() {
        for (state in 0 until AnsiState.COUNT) {
            for (byteClass in 0 until ByteClass.ROUTING_COUNT) {
                set(state, byteClass, state, FsmAction.IGNORE)
            }
        }
    }

    /**
     * Rules that apply in almost all states.
     *
     * Important:
     * - UTF8_PAYLOAD does NOT default to printable ingress everywhere.
     * - Only [GROUND] treats UTF8_PAYLOAD as printable text by default.
     * - Active control-sequence states drop/abort on UTF8_PAYLOAD instead of leaking it into text.
     */
    private fun buildAnywhereRules() {
        for (state in 0 until AnsiState.COUNT) {
            set(state, ByteClass.EXECUTE, state, FsmAction.EXECUTE)
            set(state, ByteClass.CAN_SUB, AnsiState.GROUND, FsmAction.EXECUTE_AND_CLEAR)
            set(state, ByteClass.ESC, AnsiState.ESCAPE, FsmAction.CLEAR_SEQUENCE)
            set(state, ByteClass.DEL, state, FsmAction.IGNORE)
            set(state, ByteClass.UTF8_PAYLOAD, state, FsmAction.IGNORE)
        }

        // Printable non-ASCII ingress is only valid in GROUND.
        set(AnsiState.GROUND, ByteClass.UTF8_PAYLOAD, AnsiState.GROUND, FsmAction.PRINT_UTF8)

        // String payload states absorb non-ASCII bytes as payload bytes.
        set(AnsiState.OSC_STRING, ByteClass.UTF8_PAYLOAD, AnsiState.OSC_STRING, FsmAction.OSC_PUT_UTF8)
        set(AnsiState.DCS_PASSTHROUGH, ByteClass.UTF8_PAYLOAD, AnsiState.DCS_PASSTHROUGH, FsmAction.DCS_PUT_UTF8)

        // Ignored string-like states stay ignored.
        set(AnsiState.SOS_PM_APC_STRING, ByteClass.UTF8_PAYLOAD, AnsiState.SOS_PM_APC_STRING, FsmAction.IGNORE)
        set(AnsiState.IGNORE_UNTIL_ST, ByteClass.UTF8_PAYLOAD, AnsiState.IGNORE_UNTIL_ST, FsmAction.IGNORE)

        // Non-ASCII bytes are invalid inside active 7-bit control grammar states.
        set(AnsiState.ESCAPE, ByteClass.UTF8_PAYLOAD, AnsiState.GROUND, FsmAction.CLEAR_SEQUENCE)
        set(AnsiState.ESCAPE_INTERMEDIATE, ByteClass.UTF8_PAYLOAD, AnsiState.GROUND, FsmAction.CLEAR_SEQUENCE)
        set(AnsiState.CSI_ENTRY, ByteClass.UTF8_PAYLOAD, AnsiState.GROUND, FsmAction.CLEAR_SEQUENCE)
        set(AnsiState.CSI_PARAM, ByteClass.UTF8_PAYLOAD, AnsiState.GROUND, FsmAction.CLEAR_SEQUENCE)
        set(AnsiState.CSI_INTERMEDIATE, ByteClass.UTF8_PAYLOAD, AnsiState.GROUND, FsmAction.CLEAR_SEQUENCE)
        set(AnsiState.CSI_IGNORE, ByteClass.UTF8_PAYLOAD, AnsiState.GROUND, FsmAction.CLEAR_SEQUENCE)

        // Milestone A: DCS is present structurally, but we drop straight into passthrough/ignore.
        set(AnsiState.DCS_ENTRY, ByteClass.UTF8_PAYLOAD, AnsiState.DCS_PASSTHROUGH, FsmAction.DCS_IGNORE_START)
    }

    private fun buildGround() {
        val s = AnsiState.GROUND

        set(s, ByteClass.INTERMEDIATE, s, FsmAction.PRINT_ASCII)
        set(s, ByteClass.PARAM_DIGIT, s, FsmAction.PRINT_ASCII)
        set(s, ByteClass.COLON, s, FsmAction.PRINT_ASCII)
        set(s, ByteClass.PARAM_SEP, s, FsmAction.PRINT_ASCII)
        set(s, ByteClass.PRIVATE_MARKER, s, FsmAction.PRINT_ASCII)
        set(s, ByteClass.DCS_INTRO, s, FsmAction.PRINT_ASCII)
        set(s, ByteClass.CSI_INTRO, s, FsmAction.PRINT_ASCII)
        set(s, ByteClass.ST_INTRO, s, FsmAction.PRINT_ASCII)
        set(s, ByteClass.OSC_INTRO, s, FsmAction.PRINT_ASCII)
        set(s, ByteClass.SOS_PM_APC_INTRO, s, FsmAction.PRINT_ASCII)
        set(s, ByteClass.FINAL_BYTE, s, FsmAction.PRINT_ASCII)
    }

    private fun buildEscape() {
        val s = AnsiState.ESCAPE

        set(s, ByteClass.INTERMEDIATE, AnsiState.ESCAPE_INTERMEDIATE, FsmAction.COLLECT_INTERMEDIATE)

        set(s, ByteClass.CSI_INTRO, AnsiState.CSI_ENTRY, FsmAction.CLEAR_SEQUENCE)
        set(s, ByteClass.OSC_INTRO, AnsiState.OSC_STRING, FsmAction.OSC_START)
        set(s, ByteClass.DCS_INTRO, AnsiState.DCS_ENTRY, FsmAction.CLEAR_SEQUENCE)
        set(s, ByteClass.SOS_PM_APC_INTRO, AnsiState.SOS_PM_APC_STRING, FsmAction.IGNORE)

        // ESC \ acts as ST in string termination contexts, but plain ESC dispatch in normal ESC flow.
        set(s, ByteClass.ST_INTRO, AnsiState.GROUND, FsmAction.ESC_DISPATCH)

        set(s, ByteClass.PARAM_DIGIT, AnsiState.GROUND, FsmAction.ESC_DISPATCH)
        set(s, ByteClass.COLON, AnsiState.GROUND, FsmAction.ESC_DISPATCH)
        set(s, ByteClass.PARAM_SEP, AnsiState.GROUND, FsmAction.ESC_DISPATCH)
        set(s, ByteClass.PRIVATE_MARKER, AnsiState.GROUND, FsmAction.ESC_DISPATCH)
        set(s, ByteClass.FINAL_BYTE, AnsiState.GROUND, FsmAction.ESC_DISPATCH)
    }

    private fun buildEscapeIntermediate() {
        val s = AnsiState.ESCAPE_INTERMEDIATE

        set(s, ByteClass.INTERMEDIATE, s, FsmAction.COLLECT_INTERMEDIATE)

        set(s, ByteClass.PARAM_DIGIT, AnsiState.GROUND, FsmAction.ESC_DISPATCH)
        set(s, ByteClass.COLON, AnsiState.GROUND, FsmAction.ESC_DISPATCH)
        set(s, ByteClass.PARAM_SEP, AnsiState.GROUND, FsmAction.ESC_DISPATCH)
        set(s, ByteClass.PRIVATE_MARKER, AnsiState.GROUND, FsmAction.ESC_DISPATCH)
        set(s, ByteClass.DCS_INTRO, AnsiState.GROUND, FsmAction.ESC_DISPATCH)
        set(s, ByteClass.CSI_INTRO, AnsiState.GROUND, FsmAction.ESC_DISPATCH)
        set(s, ByteClass.ST_INTRO, AnsiState.GROUND, FsmAction.ESC_DISPATCH)
        set(s, ByteClass.OSC_INTRO, AnsiState.GROUND, FsmAction.ESC_DISPATCH)
        set(s, ByteClass.SOS_PM_APC_INTRO, AnsiState.GROUND, FsmAction.ESC_DISPATCH)
        set(s, ByteClass.FINAL_BYTE, AnsiState.GROUND, FsmAction.ESC_DISPATCH)
    }

    private fun buildCsi() {
        // CSI_ENTRY
        set(AnsiState.CSI_ENTRY, ByteClass.PARAM_DIGIT, AnsiState.CSI_PARAM, FsmAction.PARAM_DIGIT)
        set(AnsiState.CSI_ENTRY, ByteClass.COLON, AnsiState.CSI_PARAM, FsmAction.PARAM_COLON)
        set(AnsiState.CSI_ENTRY, ByteClass.PARAM_SEP, AnsiState.CSI_PARAM, FsmAction.PARAM_SEPARATOR)
        set(AnsiState.CSI_ENTRY, ByteClass.PRIVATE_MARKER, AnsiState.CSI_PARAM, FsmAction.SET_PRIVATE_MARKER)
        set(AnsiState.CSI_ENTRY, ByteClass.INTERMEDIATE, AnsiState.CSI_INTERMEDIATE, FsmAction.COLLECT_INTERMEDIATE)
        set(AnsiState.CSI_ENTRY, ByteClass.DCS_INTRO, AnsiState.GROUND, FsmAction.CSI_DISPATCH)
        set(AnsiState.CSI_ENTRY, ByteClass.CSI_INTRO, AnsiState.GROUND, FsmAction.CSI_DISPATCH)
        set(AnsiState.CSI_ENTRY, ByteClass.ST_INTRO, AnsiState.GROUND, FsmAction.CSI_DISPATCH)
        set(AnsiState.CSI_ENTRY, ByteClass.OSC_INTRO, AnsiState.GROUND, FsmAction.CSI_DISPATCH)
        set(AnsiState.CSI_ENTRY, ByteClass.SOS_PM_APC_INTRO, AnsiState.GROUND, FsmAction.CSI_DISPATCH)
        set(AnsiState.CSI_ENTRY, ByteClass.FINAL_BYTE, AnsiState.GROUND, FsmAction.CSI_DISPATCH)

        // CSI_PARAM
        set(AnsiState.CSI_PARAM, ByteClass.PARAM_DIGIT, AnsiState.CSI_PARAM, FsmAction.PARAM_DIGIT)
        set(AnsiState.CSI_PARAM, ByteClass.COLON, AnsiState.CSI_PARAM, FsmAction.PARAM_COLON)
        set(AnsiState.CSI_PARAM, ByteClass.PARAM_SEP, AnsiState.CSI_PARAM, FsmAction.PARAM_SEPARATOR)
        set(AnsiState.CSI_PARAM, ByteClass.PRIVATE_MARKER, AnsiState.CSI_IGNORE, FsmAction.IGNORE)
        set(AnsiState.CSI_PARAM, ByteClass.INTERMEDIATE, AnsiState.CSI_INTERMEDIATE, FsmAction.COLLECT_INTERMEDIATE)
        set(AnsiState.CSI_PARAM, ByteClass.DCS_INTRO, AnsiState.GROUND, FsmAction.CSI_DISPATCH)
        set(AnsiState.CSI_PARAM, ByteClass.CSI_INTRO, AnsiState.GROUND, FsmAction.CSI_DISPATCH)
        set(AnsiState.CSI_PARAM, ByteClass.ST_INTRO, AnsiState.GROUND, FsmAction.CSI_DISPATCH)
        set(AnsiState.CSI_PARAM, ByteClass.OSC_INTRO, AnsiState.GROUND, FsmAction.CSI_DISPATCH)
        set(AnsiState.CSI_PARAM, ByteClass.SOS_PM_APC_INTRO, AnsiState.GROUND, FsmAction.CSI_DISPATCH)
        set(AnsiState.CSI_PARAM, ByteClass.FINAL_BYTE, AnsiState.GROUND, FsmAction.CSI_DISPATCH)

        // CSI_INTERMEDIATE
        set(AnsiState.CSI_INTERMEDIATE, ByteClass.INTERMEDIATE, AnsiState.CSI_INTERMEDIATE, FsmAction.COLLECT_INTERMEDIATE)
        set(AnsiState.CSI_INTERMEDIATE, ByteClass.PARAM_DIGIT, AnsiState.CSI_IGNORE, FsmAction.IGNORE)
        set(AnsiState.CSI_INTERMEDIATE, ByteClass.COLON, AnsiState.CSI_IGNORE, FsmAction.IGNORE)
        set(AnsiState.CSI_INTERMEDIATE, ByteClass.PARAM_SEP, AnsiState.CSI_IGNORE, FsmAction.IGNORE)
        set(AnsiState.CSI_INTERMEDIATE, ByteClass.PRIVATE_MARKER, AnsiState.CSI_IGNORE, FsmAction.IGNORE)
        set(AnsiState.CSI_INTERMEDIATE, ByteClass.DCS_INTRO, AnsiState.GROUND, FsmAction.CSI_DISPATCH)
        set(AnsiState.CSI_INTERMEDIATE, ByteClass.CSI_INTRO, AnsiState.GROUND, FsmAction.CSI_DISPATCH)
        set(AnsiState.CSI_INTERMEDIATE, ByteClass.ST_INTRO, AnsiState.GROUND, FsmAction.CSI_DISPATCH)
        set(AnsiState.CSI_INTERMEDIATE, ByteClass.OSC_INTRO, AnsiState.GROUND, FsmAction.CSI_DISPATCH)
        set(AnsiState.CSI_INTERMEDIATE, ByteClass.SOS_PM_APC_INTRO, AnsiState.GROUND, FsmAction.CSI_DISPATCH)
        set(AnsiState.CSI_INTERMEDIATE, ByteClass.FINAL_BYTE, AnsiState.GROUND, FsmAction.CSI_DISPATCH)

        // CSI_IGNORE
        set(AnsiState.CSI_IGNORE, ByteClass.INTERMEDIATE, AnsiState.CSI_IGNORE, FsmAction.IGNORE)
        set(AnsiState.CSI_IGNORE, ByteClass.PARAM_DIGIT, AnsiState.CSI_IGNORE, FsmAction.IGNORE)
        set(AnsiState.CSI_IGNORE, ByteClass.COLON, AnsiState.CSI_IGNORE, FsmAction.IGNORE)
        set(AnsiState.CSI_IGNORE, ByteClass.PARAM_SEP, AnsiState.CSI_IGNORE, FsmAction.IGNORE)
        set(AnsiState.CSI_IGNORE, ByteClass.PRIVATE_MARKER, AnsiState.CSI_IGNORE, FsmAction.IGNORE)
        set(AnsiState.CSI_IGNORE, ByteClass.DCS_INTRO, AnsiState.GROUND, FsmAction.IGNORE)
        set(AnsiState.CSI_IGNORE, ByteClass.CSI_INTRO, AnsiState.GROUND, FsmAction.IGNORE)
        set(AnsiState.CSI_IGNORE, ByteClass.ST_INTRO, AnsiState.GROUND, FsmAction.IGNORE)
        set(AnsiState.CSI_IGNORE, ByteClass.OSC_INTRO, AnsiState.GROUND, FsmAction.IGNORE)
        set(AnsiState.CSI_IGNORE, ByteClass.SOS_PM_APC_INTRO, AnsiState.GROUND, FsmAction.IGNORE)
        set(AnsiState.CSI_IGNORE, ByteClass.FINAL_BYTE, AnsiState.GROUND, FsmAction.IGNORE)
    }

    private fun buildOsc() {
        val s = AnsiState.OSC_STRING

        set(s, ByteClass.INTERMEDIATE, s, FsmAction.OSC_PUT_ASCII)
        set(s, ByteClass.PARAM_DIGIT, s, FsmAction.OSC_PUT_ASCII)
        set(s, ByteClass.COLON, s, FsmAction.OSC_PUT_ASCII)
        set(s, ByteClass.PARAM_SEP, s, FsmAction.OSC_PUT_ASCII)
        set(s, ByteClass.PRIVATE_MARKER, s, FsmAction.OSC_PUT_ASCII)
        set(s, ByteClass.DCS_INTRO, s, FsmAction.OSC_PUT_ASCII)
        set(s, ByteClass.CSI_INTRO, s, FsmAction.OSC_PUT_ASCII)
        set(s, ByteClass.ST_INTRO, s, FsmAction.OSC_PUT_ASCII)
        set(s, ByteClass.OSC_INTRO, s, FsmAction.OSC_PUT_ASCII)
        set(s, ByteClass.SOS_PM_APC_INTRO, s, FsmAction.OSC_PUT_ASCII)
        set(s, ByteClass.FINAL_BYTE, s, FsmAction.OSC_PUT_ASCII)

        // BEL terminates OSC through EXECUTE handling.
        // ESC enters ESCAPE; ActionEngine must recognize ESC + ST_INTRO as ST when returning from OSC.
    }

    private fun buildDcs() {
        // Milestone A: enter passthrough/ignore immediately, regardless of introducer details.
        set(AnsiState.DCS_ENTRY, ByteClass.EXECUTE, AnsiState.DCS_ENTRY, FsmAction.IGNORE)
        set(AnsiState.DCS_ENTRY, ByteClass.INTERMEDIATE, AnsiState.DCS_PASSTHROUGH, FsmAction.DCS_IGNORE_START)
        set(AnsiState.DCS_ENTRY, ByteClass.PARAM_DIGIT, AnsiState.DCS_PASSTHROUGH, FsmAction.DCS_IGNORE_START)
        set(AnsiState.DCS_ENTRY, ByteClass.COLON, AnsiState.DCS_PASSTHROUGH, FsmAction.DCS_IGNORE_START)
        set(AnsiState.DCS_ENTRY, ByteClass.PARAM_SEP, AnsiState.DCS_PASSTHROUGH, FsmAction.DCS_IGNORE_START)
        set(AnsiState.DCS_ENTRY, ByteClass.PRIVATE_MARKER, AnsiState.DCS_PASSTHROUGH, FsmAction.DCS_IGNORE_START)
        set(AnsiState.DCS_ENTRY, ByteClass.DCS_INTRO, AnsiState.DCS_PASSTHROUGH, FsmAction.DCS_IGNORE_START)
        set(AnsiState.DCS_ENTRY, ByteClass.CSI_INTRO, AnsiState.DCS_PASSTHROUGH, FsmAction.DCS_IGNORE_START)
        set(AnsiState.DCS_ENTRY, ByteClass.ST_INTRO, AnsiState.DCS_PASSTHROUGH, FsmAction.DCS_IGNORE_START)
        set(AnsiState.DCS_ENTRY, ByteClass.OSC_INTRO, AnsiState.DCS_PASSTHROUGH, FsmAction.DCS_IGNORE_START)
        set(AnsiState.DCS_ENTRY, ByteClass.SOS_PM_APC_INTRO, AnsiState.DCS_PASSTHROUGH, FsmAction.DCS_IGNORE_START)
        set(AnsiState.DCS_ENTRY, ByteClass.FINAL_BYTE, AnsiState.DCS_PASSTHROUGH, FsmAction.DCS_IGNORE_START)

        val s = AnsiState.DCS_PASSTHROUGH
        set(s, ByteClass.INTERMEDIATE, s, FsmAction.DCS_PUT_ASCII)
        set(s, ByteClass.PARAM_DIGIT, s, FsmAction.DCS_PUT_ASCII)
        set(s, ByteClass.COLON, s, FsmAction.DCS_PUT_ASCII)
        set(s, ByteClass.PARAM_SEP, s, FsmAction.DCS_PUT_ASCII)
        set(s, ByteClass.PRIVATE_MARKER, s, FsmAction.DCS_PUT_ASCII)
        set(s, ByteClass.DCS_INTRO, s, FsmAction.DCS_PUT_ASCII)
        set(s, ByteClass.CSI_INTRO, s, FsmAction.DCS_PUT_ASCII)
        set(s, ByteClass.ST_INTRO, s, FsmAction.DCS_PUT_ASCII)
        set(s, ByteClass.OSC_INTRO, s, FsmAction.DCS_PUT_ASCII)
        set(s, ByteClass.SOS_PM_APC_INTRO, s, FsmAction.DCS_PUT_ASCII)
        set(s, ByteClass.FINAL_BYTE, s, FsmAction.DCS_PUT_ASCII)
    }

    private fun buildStringEscapeStates() {
        // ---------------------------------------------------------------------
        // OSC string body
        // ---------------------------------------------------------------------
        set(AnsiState.OSC_STRING, ByteClass.EXECUTE, AnsiState.OSC_STRING, FsmAction.OSC_EXECUTE_CONTROL)
        set(AnsiState.OSC_STRING, ByteClass.CAN_SUB, AnsiState.GROUND, FsmAction.OSC_END)
        set(AnsiState.OSC_STRING, ByteClass.ESC, AnsiState.OSC_ESCAPE, FsmAction.IGNORE)

        // ESC \ terminates OSC.
        set(AnsiState.OSC_ESCAPE, ByteClass.ST_INTRO, AnsiState.GROUND, FsmAction.OSC_END)

        // Anything else after ESC inside OSC resumes OSC payload handling.
        set(AnsiState.OSC_ESCAPE, ByteClass.EXECUTE, AnsiState.OSC_STRING, FsmAction.OSC_EXECUTE_CONTROL)
        set(AnsiState.OSC_ESCAPE, ByteClass.CAN_SUB, AnsiState.GROUND, FsmAction.OSC_END)
        set(AnsiState.OSC_ESCAPE, ByteClass.ESC, AnsiState.OSC_ESCAPE, FsmAction.IGNORE)
        set(AnsiState.OSC_ESCAPE, ByteClass.UTF8_PAYLOAD, AnsiState.OSC_STRING, FsmAction.OSC_PUT_UTF8)

        setOscEscapeAsciiFallbacks()

        // ---------------------------------------------------------------------
        // DCS passthrough / bounded ignore
        // ---------------------------------------------------------------------
        // In DCS passthrough, ordinary C0 is retained as payload, not executed.
        set(AnsiState.DCS_PASSTHROUGH, ByteClass.EXECUTE, AnsiState.DCS_PASSTHROUGH, FsmAction.DCS_PUT_ASCII)
        set(AnsiState.DCS_PASSTHROUGH, ByteClass.CAN_SUB, AnsiState.GROUND, FsmAction.DCS_END)
        set(AnsiState.DCS_PASSTHROUGH, ByteClass.ESC, AnsiState.DCS_ESCAPE, FsmAction.IGNORE)

        // ESC \ terminates DCS.
        set(AnsiState.DCS_ESCAPE, ByteClass.ST_INTRO, AnsiState.GROUND, FsmAction.DCS_END)

        // Anything else after ESC inside DCS resumes DCS payload handling.
        set(AnsiState.DCS_ESCAPE, ByteClass.EXECUTE, AnsiState.DCS_PASSTHROUGH, FsmAction.DCS_PUT_ASCII)
        set(AnsiState.DCS_ESCAPE, ByteClass.CAN_SUB, AnsiState.GROUND, FsmAction.DCS_END)
        set(AnsiState.DCS_ESCAPE, ByteClass.ESC, AnsiState.DCS_ESCAPE, FsmAction.IGNORE)
        set(AnsiState.DCS_ESCAPE, ByteClass.UTF8_PAYLOAD, AnsiState.DCS_PASSTHROUGH, FsmAction.DCS_PUT_UTF8)

        setDcsEscapeAsciiFallbacks()

        // ---------------------------------------------------------------------
        // Ignored strings: SOS / PM / APC / generic ignore-until-ST
        // ---------------------------------------------------------------------
        set(AnsiState.SOS_PM_APC_STRING, ByteClass.EXECUTE, AnsiState.SOS_PM_APC_STRING, FsmAction.IGNORE)
        set(AnsiState.SOS_PM_APC_STRING, ByteClass.CAN_SUB, AnsiState.GROUND, FsmAction.STRING_END)
        set(AnsiState.SOS_PM_APC_STRING, ByteClass.ESC, AnsiState.SOS_PM_APC_ESCAPE, FsmAction.IGNORE)

        set(AnsiState.SOS_PM_APC_ESCAPE, ByteClass.EXECUTE, AnsiState.SOS_PM_APC_STRING, FsmAction.IGNORE)
        set(AnsiState.SOS_PM_APC_ESCAPE, ByteClass.CAN_SUB, AnsiState.GROUND, FsmAction.STRING_END)
        set(AnsiState.SOS_PM_APC_ESCAPE, ByteClass.ESC, AnsiState.SOS_PM_APC_ESCAPE, FsmAction.IGNORE)
        set(AnsiState.SOS_PM_APC_ESCAPE, ByteClass.UTF8_PAYLOAD, AnsiState.SOS_PM_APC_STRING, FsmAction.IGNORE)
        set(AnsiState.SOS_PM_APC_ESCAPE, ByteClass.ST_INTRO, AnsiState.GROUND, FsmAction.STRING_END)
        set(AnsiState.SOS_PM_APC_ESCAPE, ByteClass.INTERMEDIATE, AnsiState.SOS_PM_APC_STRING, FsmAction.IGNORE)
        set(AnsiState.SOS_PM_APC_ESCAPE, ByteClass.PARAM_DIGIT, AnsiState.SOS_PM_APC_STRING, FsmAction.IGNORE)
        set(AnsiState.SOS_PM_APC_ESCAPE, ByteClass.COLON, AnsiState.SOS_PM_APC_STRING, FsmAction.IGNORE)
        set(AnsiState.SOS_PM_APC_ESCAPE, ByteClass.PARAM_SEP, AnsiState.SOS_PM_APC_STRING, FsmAction.IGNORE)
        set(AnsiState.SOS_PM_APC_ESCAPE, ByteClass.PRIVATE_MARKER, AnsiState.SOS_PM_APC_STRING, FsmAction.IGNORE)
        set(AnsiState.SOS_PM_APC_ESCAPE, ByteClass.DCS_INTRO, AnsiState.SOS_PM_APC_STRING, FsmAction.IGNORE)
        set(AnsiState.SOS_PM_APC_ESCAPE, ByteClass.CSI_INTRO, AnsiState.SOS_PM_APC_STRING, FsmAction.IGNORE)
        set(AnsiState.SOS_PM_APC_ESCAPE, ByteClass.OSC_INTRO, AnsiState.SOS_PM_APC_STRING, FsmAction.IGNORE)
        set(AnsiState.SOS_PM_APC_ESCAPE, ByteClass.SOS_PM_APC_INTRO, AnsiState.SOS_PM_APC_STRING, FsmAction.IGNORE)
        set(AnsiState.SOS_PM_APC_ESCAPE, ByteClass.FINAL_BYTE, AnsiState.SOS_PM_APC_STRING, FsmAction.IGNORE)

        set(AnsiState.IGNORE_UNTIL_ST, ByteClass.EXECUTE, AnsiState.IGNORE_UNTIL_ST, FsmAction.IGNORE)
        set(AnsiState.IGNORE_UNTIL_ST, ByteClass.CAN_SUB, AnsiState.GROUND, FsmAction.STRING_END)
        set(AnsiState.IGNORE_UNTIL_ST, ByteClass.ESC, AnsiState.IGNORE_UNTIL_ST_ESCAPE, FsmAction.IGNORE)

        set(AnsiState.IGNORE_UNTIL_ST_ESCAPE, ByteClass.EXECUTE, AnsiState.IGNORE_UNTIL_ST, FsmAction.IGNORE)
        set(AnsiState.IGNORE_UNTIL_ST_ESCAPE, ByteClass.CAN_SUB, AnsiState.GROUND, FsmAction.STRING_END)
        set(AnsiState.IGNORE_UNTIL_ST_ESCAPE, ByteClass.ESC, AnsiState.IGNORE_UNTIL_ST_ESCAPE, FsmAction.IGNORE)
        set(AnsiState.IGNORE_UNTIL_ST_ESCAPE, ByteClass.UTF8_PAYLOAD, AnsiState.IGNORE_UNTIL_ST, FsmAction.IGNORE)
        set(AnsiState.IGNORE_UNTIL_ST_ESCAPE, ByteClass.ST_INTRO, AnsiState.GROUND, FsmAction.STRING_END)
        set(AnsiState.IGNORE_UNTIL_ST_ESCAPE, ByteClass.INTERMEDIATE, AnsiState.IGNORE_UNTIL_ST, FsmAction.IGNORE)
        set(AnsiState.IGNORE_UNTIL_ST_ESCAPE, ByteClass.PARAM_DIGIT, AnsiState.IGNORE_UNTIL_ST, FsmAction.IGNORE)
        set(AnsiState.IGNORE_UNTIL_ST_ESCAPE, ByteClass.COLON, AnsiState.IGNORE_UNTIL_ST, FsmAction.IGNORE)
        set(AnsiState.IGNORE_UNTIL_ST_ESCAPE, ByteClass.PARAM_SEP, AnsiState.IGNORE_UNTIL_ST, FsmAction.IGNORE)
        set(AnsiState.IGNORE_UNTIL_ST_ESCAPE, ByteClass.PRIVATE_MARKER, AnsiState.IGNORE_UNTIL_ST, FsmAction.IGNORE)
        set(AnsiState.IGNORE_UNTIL_ST_ESCAPE, ByteClass.DCS_INTRO, AnsiState.IGNORE_UNTIL_ST, FsmAction.IGNORE)
        set(AnsiState.IGNORE_UNTIL_ST_ESCAPE, ByteClass.CSI_INTRO, AnsiState.IGNORE_UNTIL_ST, FsmAction.IGNORE)
        set(AnsiState.IGNORE_UNTIL_ST_ESCAPE, ByteClass.OSC_INTRO, AnsiState.IGNORE_UNTIL_ST, FsmAction.IGNORE)
        set(AnsiState.IGNORE_UNTIL_ST_ESCAPE, ByteClass.SOS_PM_APC_INTRO, AnsiState.IGNORE_UNTIL_ST, FsmAction.IGNORE)
        set(AnsiState.IGNORE_UNTIL_ST_ESCAPE, ByteClass.FINAL_BYTE, AnsiState.IGNORE_UNTIL_ST, FsmAction.IGNORE)
    }

    private fun setOscEscapeAsciiFallbacks() {
        set(AnsiState.OSC_ESCAPE, ByteClass.INTERMEDIATE, AnsiState.OSC_STRING, FsmAction.OSC_PUT_ASCII)
        set(AnsiState.OSC_ESCAPE, ByteClass.PARAM_DIGIT, AnsiState.OSC_STRING, FsmAction.OSC_PUT_ASCII)
        set(AnsiState.OSC_ESCAPE, ByteClass.COLON, AnsiState.OSC_STRING, FsmAction.OSC_PUT_ASCII)
        set(AnsiState.OSC_ESCAPE, ByteClass.PARAM_SEP, AnsiState.OSC_STRING, FsmAction.OSC_PUT_ASCII)
        set(AnsiState.OSC_ESCAPE, ByteClass.PRIVATE_MARKER, AnsiState.OSC_STRING, FsmAction.OSC_PUT_ASCII)
        set(AnsiState.OSC_ESCAPE, ByteClass.DCS_INTRO, AnsiState.OSC_STRING, FsmAction.OSC_PUT_ASCII)
        set(AnsiState.OSC_ESCAPE, ByteClass.CSI_INTRO, AnsiState.OSC_STRING, FsmAction.OSC_PUT_ASCII)
        set(AnsiState.OSC_ESCAPE, ByteClass.OSC_INTRO, AnsiState.OSC_STRING, FsmAction.OSC_PUT_ASCII)
        set(AnsiState.OSC_ESCAPE, ByteClass.SOS_PM_APC_INTRO, AnsiState.OSC_STRING, FsmAction.OSC_PUT_ASCII)
        set(AnsiState.OSC_ESCAPE, ByteClass.FINAL_BYTE, AnsiState.OSC_STRING, FsmAction.OSC_PUT_ASCII)
    }

    private fun setDcsEscapeAsciiFallbacks() {
        set(AnsiState.DCS_ESCAPE, ByteClass.INTERMEDIATE, AnsiState.DCS_PASSTHROUGH, FsmAction.DCS_PUT_ASCII)
        set(AnsiState.DCS_ESCAPE, ByteClass.PARAM_DIGIT, AnsiState.DCS_PASSTHROUGH, FsmAction.DCS_PUT_ASCII)
        set(AnsiState.DCS_ESCAPE, ByteClass.COLON, AnsiState.DCS_PASSTHROUGH, FsmAction.DCS_PUT_ASCII)
        set(AnsiState.DCS_ESCAPE, ByteClass.PARAM_SEP, AnsiState.DCS_PASSTHROUGH, FsmAction.DCS_PUT_ASCII)
        set(AnsiState.DCS_ESCAPE, ByteClass.PRIVATE_MARKER, AnsiState.DCS_PASSTHROUGH, FsmAction.DCS_PUT_ASCII)
        set(AnsiState.DCS_ESCAPE, ByteClass.DCS_INTRO, AnsiState.DCS_PASSTHROUGH, FsmAction.DCS_PUT_ASCII)
        set(AnsiState.DCS_ESCAPE, ByteClass.CSI_INTRO, AnsiState.DCS_PASSTHROUGH, FsmAction.DCS_PUT_ASCII)
        set(AnsiState.DCS_ESCAPE, ByteClass.OSC_INTRO, AnsiState.DCS_PASSTHROUGH, FsmAction.DCS_PUT_ASCII)
        set(AnsiState.DCS_ESCAPE, ByteClass.SOS_PM_APC_INTRO, AnsiState.DCS_PASSTHROUGH, FsmAction.DCS_PUT_ASCII)
        set(AnsiState.DCS_ESCAPE, ByteClass.FINAL_BYTE, AnsiState.DCS_PASSTHROUGH, FsmAction.DCS_PUT_ASCII)
    }

    private fun buildIgnoredStrings() {
        setIgnoredStringFallbacks(AnsiState.SOS_PM_APC_STRING)
        setIgnoredStringFallbacks(AnsiState.IGNORE_UNTIL_ST)
    }

    private fun setIgnoredStringFallbacks(state: Int) {
        set(state, ByteClass.INTERMEDIATE, state, FsmAction.IGNORE)
        set(state, ByteClass.PARAM_DIGIT, state, FsmAction.IGNORE)
        set(state, ByteClass.COLON, state, FsmAction.IGNORE)
        set(state, ByteClass.PARAM_SEP, state, FsmAction.IGNORE)
        set(state, ByteClass.PRIVATE_MARKER, state, FsmAction.IGNORE)
        set(state, ByteClass.DCS_INTRO, state, FsmAction.IGNORE)
        set(state, ByteClass.CSI_INTRO, state, FsmAction.IGNORE)
        set(state, ByteClass.ST_INTRO, state, FsmAction.IGNORE)
        set(state, ByteClass.OSC_INTRO, state, FsmAction.IGNORE)
        set(state, ByteClass.SOS_PM_APC_INTRO, state, FsmAction.IGNORE)
        set(state, ByteClass.FINAL_BYTE, state, FsmAction.IGNORE)
    }

    private fun set(
        state: Int,
        byteClass: Int,
        nextState: Int,
        action: Int,
    ) {
        transitions[index(state, byteClass)] = encode(nextState, action)
    }

    private fun encode(
        nextState: Int,
        action: Int,
    ): Int {
        require(nextState in 0 until AnsiState.COUNT) { "nextState out of range: $nextState" }
        require(action in 0 until FsmAction.COUNT) { "action out of range: $action" }
        return (nextState shl ACTION_BITS) or action
    }

    private fun index(
        state: Int,
        byteClass: Int,
    ): Int = (state shl CLASS_SHIFT) or byteClass
}
