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

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("AnsiStateMachine")
class AnsiStateMachineTest {
    // ----- Helpers ----------------------------------------------------------

    private data class DecodedTransition(
        val nextState: Int,
        val action: Int,
    )

    private data class Step(
        val byteValue: Int,
        val byteClass: Int,
        val fromState: Int,
        val nextState: Int,
        val action: Int,
    )

    private val states: List<Int> = (0 until AnsiState.COUNT).toList()
    private val byteClasses: List<Int> = (0 until ByteClass.ROUTING_COUNT).toList()

    private val groundPrintableClasses: List<Int> =
        listOf(
            ByteClass.INTERMEDIATE,
            ByteClass.PARAM_DIGIT,
            ByteClass.COLON,
            ByteClass.PARAM_SEP,
            ByteClass.PRIVATE_MARKER,
            ByteClass.DCS_INTRO,
            ByteClass.CSI_INTRO,
            ByteClass.ST_INTRO,
            ByteClass.OSC_INTRO,
            ByteClass.SOS_PM_APC_INTRO,
            ByteClass.FINAL_BYTE,
        )

    private val escDispatchClasses: List<Int> =
        listOf(
            ByteClass.PARAM_DIGIT,
            ByteClass.COLON,
            ByteClass.PARAM_SEP,
            ByteClass.PRIVATE_MARKER,
            ByteClass.FINAL_BYTE,
        )

    private val escIntermediateDispatchClasses: List<Int> =
        listOf(
            ByteClass.PARAM_DIGIT,
            ByteClass.COLON,
            ByteClass.PARAM_SEP,
            ByteClass.PRIVATE_MARKER,
            ByteClass.DCS_INTRO,
            ByteClass.CSI_INTRO,
            ByteClass.ST_INTRO,
            ByteClass.OSC_INTRO,
            ByteClass.SOS_PM_APC_INTRO,
            ByteClass.FINAL_BYTE,
        )

    private val csiDispatchClasses: List<Int> =
        listOf(
            ByteClass.DCS_INTRO,
            ByteClass.CSI_INTRO,
            ByteClass.ST_INTRO,
            ByteClass.OSC_INTRO,
            ByteClass.SOS_PM_APC_INTRO,
            ByteClass.FINAL_BYTE,
        )

    private val stringAsciiPayloadClasses: List<Int> =
        listOf(
            ByteClass.INTERMEDIATE,
            ByteClass.PARAM_DIGIT,
            ByteClass.COLON,
            ByteClass.PARAM_SEP,
            ByteClass.PRIVATE_MARKER,
            ByteClass.DCS_INTRO,
            ByteClass.CSI_INTRO,
            ByteClass.ST_INTRO,
            ByteClass.OSC_INTRO,
            ByteClass.SOS_PM_APC_INTRO,
            ByteClass.FINAL_BYTE,
        )

    private val stringAsciiPayloadClassesExceptSt: List<Int> =
        stringAsciiPayloadClasses.filterNot { it == ByteClass.ST_INTRO }

    private val nonStringStates: List<Int> = states.filterNot(AnsiState::isStringState)

    private fun decoded(
        state: Int,
        byteClass: Int,
    ): DecodedTransition {
        val transition = AnsiStateMachine.transition(state, byteClass)
        return DecodedTransition(
            nextState = AnsiStateMachine.nextState(transition),
            action = AnsiStateMachine.action(transition),
        )
    }

    private fun assertTransition(
        state: Int,
        byteClass: Int,
        expectedNextState: Int,
        expectedAction: Int,
    ) {
        val actual = decoded(state, byteClass)

        assertAll(
            "state=$state byteClass=$byteClass",
            { assertEquals(expectedNextState, actual.nextState, "next state") },
            { assertEquals(expectedAction, actual.action, "action") },
        )
    }

    private fun assertClasses(
        state: Int,
        classes: Iterable<Int>,
        expectedNextState: Int,
        expectedAction: Int,
    ) {
        for (byteClass in classes) {
            assertTransition(state, byteClass, expectedNextState, expectedAction)
        }
    }

    private fun feedBytes(vararg byteValues: Int): List<Step> {
        val steps = mutableListOf<Step>()
        var state = AnsiState.GROUND

        for (byteValue in byteValues) {
            val byteClass = ByteClass.classify(byteValue)
            val transition = decoded(state, byteClass)
            steps +=
                Step(
                    byteValue = byteValue,
                    byteClass = byteClass,
                    fromState = state,
                    nextState = transition.nextState,
                    action = transition.action,
                )
            state = transition.nextState
        }

        return steps
    }

    private fun assertStTerminationNeedsStringContext(startState: Int) {
        val esc = decoded(startState, ByteClass.ESC)
        val st = decoded(esc.nextState, ByteClass.ST_INTRO)
        val expectedEscapeState =
            when (startState) {
                AnsiState.OSC_STRING -> AnsiState.OSC_ESCAPE
                AnsiState.DCS_PASSTHROUGH -> AnsiState.DCS_ESCAPE
                AnsiState.SOS_PM_APC_STRING -> AnsiState.SOS_PM_APC_ESCAPE
                AnsiState.IGNORE_UNTIL_ST -> AnsiState.IGNORE_UNTIL_ST_ESCAPE
                else -> error("unsupported start state: $startState")
            }
        val expectedEndAction =
            when (startState) {
                AnsiState.OSC_STRING -> FsmAction.OSC_END
                AnsiState.DCS_PASSTHROUGH -> FsmAction.DCS_END
                AnsiState.SOS_PM_APC_STRING,
                AnsiState.IGNORE_UNTIL_ST,
                -> FsmAction.STRING_END
                else -> error("unsupported start state: $startState")
            }

        assertAll(
            { assertEquals(expectedEscapeState, esc.nextState, "ESC must enter a string-local escape state") },
            { assertEquals(FsmAction.IGNORE, esc.action, "ESC inside strings must preserve payload context") },
            { assertEquals(AnsiState.GROUND, st.nextState, "ESC followed by ST_INTRO must terminate the string") },
            { assertEquals(expectedEndAction, st.action, "ESC \\ from a string must terminate via string-end action") },
        )
    }

    private fun assertStringEscapeState(
        escapeState: Int,
        bodyState: Int,
        asciiAction: Int,
        executeAction: Int,
        utf8Action: Int,
        endAction: Int,
    ) {
        assertClasses(escapeState, stringAsciiPayloadClassesExceptSt, bodyState, asciiAction)

        assertAll(
            { assertTransition(escapeState, ByteClass.EXECUTE, bodyState, executeAction) },
            { assertTransition(escapeState, ByteClass.UTF8_PAYLOAD, bodyState, utf8Action) },
            { assertTransition(escapeState, ByteClass.ST_INTRO, AnsiState.GROUND, endAction) },
            { assertTransition(escapeState, ByteClass.CAN_SUB, AnsiState.GROUND, endAction) },
            { assertTransition(escapeState, ByteClass.ESC, escapeState, FsmAction.IGNORE) },
            { assertTransition(escapeState, ByteClass.DEL, escapeState, FsmAction.IGNORE) },
        )
    }

    // ----- Matrix shape / encoding -----------------------------------------

    @Nested
    @DisplayName("matrix shape and encoding")
    inner class MatrixShapeAndEncoding {
        @Test
        fun `routing count includes all ASCII classes plus UTF-8 payload`() {
            assertAll(
                { assertEquals(ByteClass.COUNT + 1, ByteClass.ROUTING_COUNT) },
                { assertEquals(ByteClass.UTF8_PAYLOAD + 1, ByteClass.ROUTING_COUNT) },
                { assertEquals((0 until ByteClass.ROUTING_COUNT).toList(), byteClasses) },
            )
        }

        @Test
        fun `every valid state and byte class has a decodable transition`() {
            for (state in states) {
                for (byteClass in byteClasses) {
                    val transition = decoded(state, byteClass)

                    assertAll(
                        "state=$state byteClass=$byteClass",
                        { assertTrue(transition.nextState in 0 until AnsiState.COUNT, "valid next state") },
                        { assertTrue(transition.action in 0 until FsmAction.COUNT, "valid action") },
                    )
                }
            }
        }

        @Test
        fun `nextState and action decode the high and low bits independently`() {
            val encoded = (AnsiState.DCS_PASSTHROUGH shl 8) or FsmAction.DCS_PUT_UTF8

            assertAll(
                { assertEquals(AnsiState.DCS_PASSTHROUGH, AnsiStateMachine.nextState(encoded)) },
                { assertEquals(FsmAction.DCS_PUT_UTF8, AnsiStateMachine.action(encoded)) },
            )
        }

        @Test
        fun `transition rejects states outside the state table`() {
            val below =
                assertThrows(IllegalArgumentException::class.java) {
                    AnsiStateMachine.transition(-1, ByteClass.EXECUTE)
                }
            val atCount =
                assertThrows(IllegalArgumentException::class.java) {
                    AnsiStateMachine.transition(AnsiState.COUNT, ByteClass.EXECUTE)
                }
            val above =
                assertThrows(IllegalArgumentException::class.java) {
                    AnsiStateMachine.transition(Int.MAX_VALUE, ByteClass.EXECUTE)
                }

            assertAll(
                { assertEquals("state out of range: -1", below.message) },
                { assertEquals("state out of range: ${AnsiState.COUNT}", atCount.message) },
                { assertEquals("state out of range: ${Int.MAX_VALUE}", above.message) },
            )
        }

        @Test
        fun `transition rejects byte classes outside the routing table`() {
            val below =
                assertThrows(IllegalArgumentException::class.java) {
                    AnsiStateMachine.transition(AnsiState.GROUND, -1)
                }
            val atCount =
                assertThrows(IllegalArgumentException::class.java) {
                    AnsiStateMachine.transition(AnsiState.GROUND, ByteClass.ROUTING_COUNT)
                }
            val above =
                assertThrows(IllegalArgumentException::class.java) {
                    AnsiStateMachine.transition(AnsiState.GROUND, Int.MAX_VALUE)
                }

            assertAll(
                { assertEquals("byteClass out of range: -1", below.message) },
                { assertEquals("byteClass out of range: ${ByteClass.ROUTING_COUNT}", atCount.message) },
                { assertEquals("byteClass out of range: ${Int.MAX_VALUE}", above.message) },
            )
        }
    }

    // ----- GROUND -----------------------------------------------------------

    @Nested
    @DisplayName("GROUND")
    inner class Ground {
        @Test
        fun `ASCII structural and final classes print without leaving ground`() {
            assertClasses(
                AnsiState.GROUND,
                groundPrintableClasses,
                AnsiState.GROUND,
                FsmAction.PRINT_ASCII,
            )
        }

        @Test
        fun `C0 execute bytes execute in place`() {
            assertTransition(
                AnsiState.GROUND,
                ByteClass.EXECUTE,
                AnsiState.GROUND,
                FsmAction.EXECUTE,
            )
        }

        @Test
        fun `CAN and SUB execute then clear to ground`() {
            assertTransition(
                AnsiState.GROUND,
                ByteClass.CAN_SUB,
                AnsiState.GROUND,
                FsmAction.EXECUTE_AND_CLEAR,
            )
        }

        @Test
        fun `ESC enters escape state and clears sequence bookkeeping`() {
            assertTransition(
                AnsiState.GROUND,
                ByteClass.ESC,
                AnsiState.ESCAPE,
                FsmAction.CLEAR_SEQUENCE,
            )
        }

        @Test
        fun `DEL is ignored in ground`() {
            assertTransition(
                AnsiState.GROUND,
                ByteClass.DEL,
                AnsiState.GROUND,
                FsmAction.IGNORE,
            )
        }

        @Test
        fun `UTF-8 payload is printable only in ground`() {
            assertTransition(
                AnsiState.GROUND,
                ByteClass.UTF8_PAYLOAD,
                AnsiState.GROUND,
                FsmAction.PRINT_UTF8,
            )
        }
    }

    // ----- Anywhere / UTF-8 rules ------------------------------------------

    @Nested
    @DisplayName("anywhere and UTF-8 rules")
    inner class AnywhereAndUtf8Rules {
        @Test
        fun `CAN and SUB abort every state back to ground`() {
            for (state in states) {
                val transition = decoded(state, ByteClass.CAN_SUB)
                assertEquals(AnsiState.GROUND, transition.nextState, "state $state")
            }
        }

        @Test
        fun `DEL is ignored without changing state`() {
            for (state in states) {
                assertTransition(state, ByteClass.DEL, state, FsmAction.IGNORE)
            }
        }

        @Test
        fun `ESC restarts escape parsing from non-string states`() {
            for (state in nonStringStates) {
                assertTransition(
                    state,
                    ByteClass.ESC,
                    AnsiState.ESCAPE,
                    FsmAction.CLEAR_SEQUENCE,
                )
            }
        }

        @Test
        fun `active 7-bit control grammar states reject UTF-8 payload back to ground`() {
            val activeGrammarStates =
                listOf(
                    AnsiState.ESCAPE,
                    AnsiState.ESCAPE_INTERMEDIATE,
                    AnsiState.CSI_ENTRY,
                    AnsiState.CSI_PARAM,
                    AnsiState.CSI_INTERMEDIATE,
                    AnsiState.CSI_IGNORE,
                )

            for (state in activeGrammarStates) {
                assertTransition(
                    state,
                    ByteClass.UTF8_PAYLOAD,
                    AnsiState.GROUND,
                    FsmAction.CLEAR_SEQUENCE,
                )
            }
        }

        @Test
        fun `string payload states absorb UTF-8 according to their payload policy`() {
            assertAll(
                {
                    assertTransition(
                        AnsiState.OSC_STRING,
                        ByteClass.UTF8_PAYLOAD,
                        AnsiState.OSC_STRING,
                        FsmAction.OSC_PUT_UTF8,
                    )
                },
                {
                    assertTransition(
                        AnsiState.DCS_PASSTHROUGH,
                        ByteClass.UTF8_PAYLOAD,
                        AnsiState.DCS_PASSTHROUGH,
                        FsmAction.DCS_PUT_UTF8,
                    )
                },
                {
                    assertTransition(
                        AnsiState.SOS_PM_APC_STRING,
                        ByteClass.UTF8_PAYLOAD,
                        AnsiState.SOS_PM_APC_STRING,
                        FsmAction.IGNORE,
                    )
                },
                {
                    assertTransition(
                        AnsiState.IGNORE_UNTIL_ST,
                        ByteClass.UTF8_PAYLOAD,
                        AnsiState.IGNORE_UNTIL_ST,
                        FsmAction.IGNORE,
                    )
                },
            )
        }
    }

    // ----- ESCAPE -----------------------------------------------------------

    @Nested
    @DisplayName("ESCAPE")
    inner class Escape {
        @Test
        fun `intermediate bytes enter escape intermediate collection`() {
            assertTransition(
                AnsiState.ESCAPE,
                ByteClass.INTERMEDIATE,
                AnsiState.ESCAPE_INTERMEDIATE,
                FsmAction.COLLECT_INTERMEDIATE,
            )
        }

        @Test
        fun `control string and sequence introducers enter their dedicated states`() {
            assertAll(
                {
                    assertTransition(
                        AnsiState.ESCAPE,
                        ByteClass.CSI_INTRO,
                        AnsiState.CSI_ENTRY,
                        FsmAction.CLEAR_SEQUENCE,
                    )
                },
                {
                    assertTransition(
                        AnsiState.ESCAPE,
                        ByteClass.OSC_INTRO,
                        AnsiState.OSC_STRING,
                        FsmAction.OSC_START,
                    )
                },
                {
                    assertTransition(
                        AnsiState.ESCAPE,
                        ByteClass.DCS_INTRO,
                        AnsiState.DCS_ENTRY,
                        FsmAction.CLEAR_SEQUENCE,
                    )
                },
                {
                    assertTransition(
                        AnsiState.ESCAPE,
                        ByteClass.SOS_PM_APC_INTRO,
                        AnsiState.SOS_PM_APC_STRING,
                        FsmAction.IGNORE,
                    )
                },
            )
        }

        @Test
        fun `plain ESC final-like classes dispatch and return to ground`() {
            assertClasses(
                AnsiState.ESCAPE,
                escDispatchClasses,
                AnsiState.GROUND,
                FsmAction.ESC_DISPATCH,
            )
        }

        @Test
        fun `ESC backslash is plain ESC dispatch outside string context`() {
            assertTransition(
                AnsiState.ESCAPE,
                ByteClass.ST_INTRO,
                AnsiState.GROUND,
                FsmAction.ESC_DISPATCH,
            )
        }
    }

    // ----- ESCAPE_INTERMEDIATE ---------------------------------------------

    @Nested
    @DisplayName("ESCAPE_INTERMEDIATE")
    inner class EscapeIntermediate {
        @Test
        fun `additional intermediate bytes continue collection`() {
            assertTransition(
                AnsiState.ESCAPE_INTERMEDIATE,
                ByteClass.INTERMEDIATE,
                AnsiState.ESCAPE_INTERMEDIATE,
                FsmAction.COLLECT_INTERMEDIATE,
            )
        }

        @Test
        fun `final-like classes dispatch collected escape intermediates`() {
            assertClasses(
                AnsiState.ESCAPE_INTERMEDIATE,
                escIntermediateDispatchClasses,
                AnsiState.GROUND,
                FsmAction.ESC_DISPATCH,
            )
        }
    }

    // ----- CSI --------------------------------------------------------------

    @Nested
    @DisplayName("CSI states")
    inner class CsiStates {
        @Test
        fun `CSI entry routes parameter grammar and intermediates`() {
            assertAll(
                { assertTransition(AnsiState.CSI_ENTRY, ByteClass.PARAM_DIGIT, AnsiState.CSI_PARAM, FsmAction.PARAM_DIGIT) },
                { assertTransition(AnsiState.CSI_ENTRY, ByteClass.COLON, AnsiState.CSI_PARAM, FsmAction.PARAM_COLON) },
                { assertTransition(AnsiState.CSI_ENTRY, ByteClass.PARAM_SEP, AnsiState.CSI_PARAM, FsmAction.PARAM_SEPARATOR) },
                { assertTransition(AnsiState.CSI_ENTRY, ByteClass.PRIVATE_MARKER, AnsiState.CSI_PARAM, FsmAction.SET_PRIVATE_MARKER) },
                {
                    assertTransition(
                        AnsiState.CSI_ENTRY,
                        ByteClass.INTERMEDIATE,
                        AnsiState.CSI_INTERMEDIATE,
                        FsmAction.COLLECT_INTERMEDIATE,
                    )
                },
            )
        }

        @Test
        fun `CSI entry final-like classes dispatch immediately`() {
            assertClasses(
                AnsiState.CSI_ENTRY,
                csiDispatchClasses,
                AnsiState.GROUND,
                FsmAction.CSI_DISPATCH,
            )
        }

        @Test
        fun `CSI param continues parameter grammar until intermediate or final`() {
            assertAll(
                { assertTransition(AnsiState.CSI_PARAM, ByteClass.PARAM_DIGIT, AnsiState.CSI_PARAM, FsmAction.PARAM_DIGIT) },
                { assertTransition(AnsiState.CSI_PARAM, ByteClass.COLON, AnsiState.CSI_PARAM, FsmAction.PARAM_COLON) },
                { assertTransition(AnsiState.CSI_PARAM, ByteClass.PARAM_SEP, AnsiState.CSI_PARAM, FsmAction.PARAM_SEPARATOR) },
                { assertTransition(AnsiState.CSI_PARAM, ByteClass.PRIVATE_MARKER, AnsiState.CSI_IGNORE, FsmAction.IGNORE) },
                {
                    assertTransition(
                        AnsiState.CSI_PARAM,
                        ByteClass.INTERMEDIATE,
                        AnsiState.CSI_INTERMEDIATE,
                        FsmAction.COLLECT_INTERMEDIATE,
                    )
                },
            )
        }

        @Test
        fun `CSI param final-like classes dispatch`() {
            assertClasses(
                AnsiState.CSI_PARAM,
                csiDispatchClasses,
                AnsiState.GROUND,
                FsmAction.CSI_DISPATCH,
            )
        }

        @Test
        fun `CSI intermediate collects intermediates and rejects later parameter grammar`() {
            assertAll(
                {
                    assertTransition(
                        AnsiState.CSI_INTERMEDIATE,
                        ByteClass.INTERMEDIATE,
                        AnsiState.CSI_INTERMEDIATE,
                        FsmAction.COLLECT_INTERMEDIATE,
                    )
                },
                { assertTransition(AnsiState.CSI_INTERMEDIATE, ByteClass.PARAM_DIGIT, AnsiState.CSI_IGNORE, FsmAction.IGNORE) },
                { assertTransition(AnsiState.CSI_INTERMEDIATE, ByteClass.COLON, AnsiState.CSI_IGNORE, FsmAction.IGNORE) },
                { assertTransition(AnsiState.CSI_INTERMEDIATE, ByteClass.PARAM_SEP, AnsiState.CSI_IGNORE, FsmAction.IGNORE) },
                { assertTransition(AnsiState.CSI_INTERMEDIATE, ByteClass.PRIVATE_MARKER, AnsiState.CSI_IGNORE, FsmAction.IGNORE) },
            )
        }

        @Test
        fun `CSI intermediate final-like classes dispatch`() {
            assertClasses(
                AnsiState.CSI_INTERMEDIATE,
                csiDispatchClasses,
                AnsiState.GROUND,
                FsmAction.CSI_DISPATCH,
            )
        }

        @Test
        fun `CSI ignore drains parameter and intermediate tail until final-like terminator`() {
            val ignoredTailClasses =
                listOf(
                    ByteClass.INTERMEDIATE,
                    ByteClass.PARAM_DIGIT,
                    ByteClass.COLON,
                    ByteClass.PARAM_SEP,
                    ByteClass.PRIVATE_MARKER,
                )

            assertClasses(
                AnsiState.CSI_IGNORE,
                ignoredTailClasses,
                AnsiState.CSI_IGNORE,
                FsmAction.IGNORE,
            )
        }

        @Test
        fun `CSI ignore final-like classes return to ground without dispatch`() {
            assertClasses(
                AnsiState.CSI_IGNORE,
                csiDispatchClasses,
                AnsiState.GROUND,
                FsmAction.IGNORE,
            )
        }
    }

    // ----- OSC / DCS / ignored strings -------------------------------------

    @Nested
    @DisplayName("string and passthrough states")
    inner class StringAndPassthroughStates {
        @Test
        fun `OSC string treats printable ASCII routing classes as OSC payload`() {
            assertClasses(
                AnsiState.OSC_STRING,
                stringAsciiPayloadClasses,
                AnsiState.OSC_STRING,
                FsmAction.OSC_PUT_ASCII,
            )
        }

        @Test
        fun `DCS entry enters passthrough ignore mode for any payload byte class`() {
            assertClasses(
                AnsiState.DCS_ENTRY,
                stringAsciiPayloadClasses + ByteClass.UTF8_PAYLOAD,
                AnsiState.DCS_PASSTHROUGH,
                FsmAction.DCS_IGNORE_START,
            )
        }

        @Test
        fun `DCS passthrough treats printable ASCII routing classes as DCS payload`() {
            assertClasses(
                AnsiState.DCS_PASSTHROUGH,
                stringAsciiPayloadClasses,
                AnsiState.DCS_PASSTHROUGH,
                FsmAction.DCS_PUT_ASCII,
            )
        }

        @Test
        fun `SOS PM APC string ignores printable ASCII routing classes`() {
            assertClasses(
                AnsiState.SOS_PM_APC_STRING,
                stringAsciiPayloadClasses,
                AnsiState.SOS_PM_APC_STRING,
                FsmAction.IGNORE,
            )
        }

        @Test
        fun `generic ignore-until-ST ignores printable ASCII routing classes`() {
            assertClasses(
                AnsiState.IGNORE_UNTIL_ST,
                stringAsciiPayloadClasses,
                AnsiState.IGNORE_UNTIL_ST,
                FsmAction.IGNORE,
            )
        }
    }

    // ----- End-to-end traces ------------------------------------------------

    @Nested
    @DisplayName("representative traces")
    inner class RepresentativeTraces {
        @Test
        fun `plain text stays in ground and prints`() {
            val steps = feedBytes('H'.code, 'i'.code)

            assertAll(
                { assertEquals(AnsiState.GROUND, steps[0].fromState) },
                { assertEquals(AnsiState.GROUND, steps[0].nextState) },
                { assertEquals(FsmAction.PRINT_ASCII, steps[0].action) },
                { assertEquals(AnsiState.GROUND, steps[1].fromState) },
                { assertEquals(AnsiState.GROUND, steps[1].nextState) },
                { assertEquals(FsmAction.PRINT_ASCII, steps[1].action) },
            )
        }

        @Test
        fun `CSI parameterized final dispatch returns to ground`() {
            val steps = feedBytes(0x1B, '['.code, '?'.code, '2'.code, '5'.code, 'h'.code)

            assertAll(
                { assertEquals(AnsiState.ESCAPE, steps[0].nextState) },
                { assertEquals(AnsiState.CSI_ENTRY, steps[1].nextState) },
                { assertEquals(AnsiState.CSI_PARAM, steps[2].nextState) },
                { assertEquals(FsmAction.SET_PRIVATE_MARKER, steps[2].action) },
                { assertEquals(AnsiState.CSI_PARAM, steps[3].nextState) },
                { assertEquals(FsmAction.PARAM_DIGIT, steps[3].action) },
                { assertEquals(AnsiState.CSI_PARAM, steps[4].nextState) },
                { assertEquals(FsmAction.PARAM_DIGIT, steps[4].action) },
                { assertEquals(AnsiState.GROUND, steps[5].nextState) },
                { assertEquals(FsmAction.CSI_DISPATCH, steps[5].action) },
            )
        }

        @Test
        fun `DCS entry moves into milestone-A passthrough on first payload byte`() {
            val steps = feedBytes(0x1B, 'P'.code, 'q'.code)

            assertAll(
                { assertEquals(AnsiState.ESCAPE, steps[0].nextState) },
                { assertEquals(AnsiState.DCS_ENTRY, steps[1].nextState) },
                { assertEquals(AnsiState.DCS_PASSTHROUGH, steps[2].nextState) },
                { assertEquals(FsmAction.DCS_IGNORE_START, steps[2].action) },
            )
        }
    }

    // ----- String termination semantics ------------------------------------

    @Nested
    @DisplayName("string termination semantics")
    inner class StringTerminationSemantics {
        @Test
        fun `OSC BEL terminates the OSC string and returns to ground`() {
            val steps = feedBytes(0x1B, ']'.code, '0'.code, ';'.code, 't'.code, 0x07)
            val bel = steps.last()

            assertAll(
                { assertEquals(AnsiState.OSC_STRING, bel.fromState) },
                { assertEquals(ByteClass.EXECUTE, bel.byteClass) },
                { assertEquals(AnsiState.OSC_STRING, bel.nextState, "matrix stays in OSC; action interprets BEL") },
                { assertEquals(FsmAction.OSC_EXECUTE_CONTROL, bel.action) },
            )
        }

        @Test
        fun `OSC ordinary C0 controls are ignored rather than executed`() {
            val steps = feedBytes(0x1B, ']'.code, 0x00)
            val nul = steps.last()

            assertAll(
                { assertEquals(AnsiState.OSC_STRING, nul.fromState) },
                { assertEquals(ByteClass.EXECUTE, nul.byteClass) },
                { assertEquals(AnsiState.OSC_STRING, nul.nextState) },
                { assertEquals(FsmAction.OSC_EXECUTE_CONTROL, nul.action, "OSC has dedicated C0 handling action") },
            )
        }

        @Test
        fun `DCS entry ignores ordinary C0 while waiting for passthrough data`() {
            val steps = feedBytes(0x1B, 'P'.code, 0x00)
            val nul = steps.last()

            assertAll(
                { assertEquals(AnsiState.DCS_ENTRY, nul.fromState) },
                { assertEquals(ByteClass.EXECUTE, nul.byteClass) },
                { assertEquals(AnsiState.DCS_ENTRY, nul.nextState) },
                { assertEquals(FsmAction.IGNORE, nul.action) },
            )
        }

        @Test
        fun `DCS passthrough passes ordinary C0 controls as DCS payload`() {
            val steps = feedBytes(0x1B, 'P'.code, 'q'.code, 0x00)
            val nul = steps.last()

            assertAll(
                { assertEquals(AnsiState.DCS_PASSTHROUGH, nul.fromState) },
                { assertEquals(ByteClass.EXECUTE, nul.byteClass) },
                { assertEquals(AnsiState.DCS_PASSTHROUGH, nul.nextState) },
                { assertEquals(FsmAction.DCS_PUT_ASCII, nul.action, "ordinary C0 controls are part of DCS passthrough data") },
            )
        }

        @Test
        fun `ignored string states ignore ordinary C0 controls`() {
            val sosNul = feedBytes(0x1B, 'X'.code, 0x00).last()
            val genericIgnoreNul = decoded(AnsiState.IGNORE_UNTIL_ST, ByteClass.EXECUTE)

            assertAll(
                {
                    assertAll(
                        { assertEquals(AnsiState.SOS_PM_APC_STRING, sosNul.fromState) },
                        { assertEquals(AnsiState.SOS_PM_APC_STRING, sosNul.nextState) },
                        { assertEquals(FsmAction.IGNORE, sosNul.action) },
                    )
                },
                {
                    assertAll(
                        { assertEquals(AnsiState.IGNORE_UNTIL_ST, genericIgnoreNul.nextState) },
                        { assertEquals(FsmAction.IGNORE, genericIgnoreNul.action) },
                    )
                },
            )
        }

        @Test
        fun `OSC ST terminates the OSC string instead of dispatching plain ESC backslash`() {
            val steps = feedBytes(0x1B, ']'.code, '0'.code, ';'.code, 't'.code, 0x1B, '\\'.code)
            val esc = steps[5]
            val st = steps[6]

            assertAll(
                { assertEquals(AnsiState.OSC_STRING, esc.fromState) },
                {
                    assertNotEquals(
                        FsmAction.CLEAR_SEQUENCE,
                        esc.action,
                        "ESC inside OSC must preserve string context until the following byte is known",
                    )
                },
                { assertEquals(AnsiState.GROUND, st.nextState) },
                {
                    assertNotEquals(
                        FsmAction.ESC_DISPATCH,
                        st.action,
                        "ESC \\ must terminate OSC, not dispatch as a standalone ESC sequence",
                    )
                },
            )
        }

        @Test
        fun `DCS ST terminates passthrough instead of dispatching plain ESC backslash`() {
            val steps = feedBytes(0x1B, 'P'.code, 'q'.code, 0x1B, '\\'.code)
            val esc = steps[3]
            val st = steps[4]

            assertAll(
                { assertEquals(AnsiState.DCS_PASSTHROUGH, esc.fromState) },
                {
                    assertNotEquals(
                        FsmAction.CLEAR_SEQUENCE,
                        esc.action,
                        "ESC inside DCS passthrough must preserve passthrough context",
                    )
                },
                { assertEquals(AnsiState.GROUND, st.nextState) },
                {
                    assertNotEquals(
                        FsmAction.ESC_DISPATCH,
                        st.action,
                        "ESC \\ must terminate DCS passthrough, not dispatch as a standalone ESC sequence",
                    )
                },
            )
        }

        @Test
        fun `ignored string states terminate on ST with string context intact`() {
            assertAll(
                { assertStTerminationNeedsStringContext(AnsiState.SOS_PM_APC_STRING) },
                { assertStTerminationNeedsStringContext(AnsiState.IGNORE_UNTIL_ST) },
            )
        }

        @Test
        fun `OSC escape state terminates only on ST CAN or SUB and otherwise resumes OSC payload`() {
            assertStringEscapeState(
                escapeState = AnsiState.OSC_ESCAPE,
                bodyState = AnsiState.OSC_STRING,
                asciiAction = FsmAction.OSC_PUT_ASCII,
                executeAction = FsmAction.OSC_EXECUTE_CONTROL,
                utf8Action = FsmAction.OSC_PUT_UTF8,
                endAction = FsmAction.OSC_END,
            )
        }

        @Test
        fun `DCS escape state terminates only on ST CAN or SUB and otherwise resumes passthrough payload`() {
            assertStringEscapeState(
                escapeState = AnsiState.DCS_ESCAPE,
                bodyState = AnsiState.DCS_PASSTHROUGH,
                asciiAction = FsmAction.DCS_PUT_ASCII,
                executeAction = FsmAction.DCS_PUT_ASCII,
                utf8Action = FsmAction.DCS_PUT_UTF8,
                endAction = FsmAction.DCS_END,
            )
        }

        @Test
        fun `ignored string escape states terminate only on ST CAN or SUB and otherwise resume ignoring`() {
            assertAll(
                {
                    assertStringEscapeState(
                        escapeState = AnsiState.SOS_PM_APC_ESCAPE,
                        bodyState = AnsiState.SOS_PM_APC_STRING,
                        asciiAction = FsmAction.IGNORE,
                        executeAction = FsmAction.IGNORE,
                        utf8Action = FsmAction.IGNORE,
                        endAction = FsmAction.STRING_END,
                    )
                },
                {
                    assertStringEscapeState(
                        escapeState = AnsiState.IGNORE_UNTIL_ST_ESCAPE,
                        bodyState = AnsiState.IGNORE_UNTIL_ST,
                        asciiAction = FsmAction.IGNORE,
                        executeAction = FsmAction.IGNORE,
                        utf8Action = FsmAction.IGNORE,
                        endAction = FsmAction.STRING_END,
                    )
                },
            )
        }

        @Test
        fun `CAN and SUB use string-specific termination actions inside string states`() {
            assertAll(
                { assertTransition(AnsiState.OSC_STRING, ByteClass.CAN_SUB, AnsiState.GROUND, FsmAction.OSC_END) },
                { assertTransition(AnsiState.OSC_ESCAPE, ByteClass.CAN_SUB, AnsiState.GROUND, FsmAction.OSC_END) },
                { assertTransition(AnsiState.DCS_PASSTHROUGH, ByteClass.CAN_SUB, AnsiState.GROUND, FsmAction.DCS_END) },
                { assertTransition(AnsiState.DCS_ESCAPE, ByteClass.CAN_SUB, AnsiState.GROUND, FsmAction.DCS_END) },
                { assertTransition(AnsiState.SOS_PM_APC_STRING, ByteClass.CAN_SUB, AnsiState.GROUND, FsmAction.STRING_END) },
                { assertTransition(AnsiState.SOS_PM_APC_ESCAPE, ByteClass.CAN_SUB, AnsiState.GROUND, FsmAction.STRING_END) },
                { assertTransition(AnsiState.IGNORE_UNTIL_ST, ByteClass.CAN_SUB, AnsiState.GROUND, FsmAction.STRING_END) },
                { assertTransition(AnsiState.IGNORE_UNTIL_ST_ESCAPE, ByteClass.CAN_SUB, AnsiState.GROUND, FsmAction.STRING_END) },
            )
        }
    }
}
