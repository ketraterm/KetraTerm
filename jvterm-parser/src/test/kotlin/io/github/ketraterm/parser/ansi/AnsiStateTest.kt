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

@DisplayName("AnsiState")
class AnsiStateTest {
    // ----- Helpers ----------------------------------------------------------

    private val allStates: List<Int> =
        listOf(
            AnsiState.GROUND,
            AnsiState.ESCAPE,
            AnsiState.ESCAPE_INTERMEDIATE,
            AnsiState.CSI_ENTRY,
            AnsiState.CSI_PARAM,
            AnsiState.CSI_INTERMEDIATE,
            AnsiState.CSI_IGNORE,
            AnsiState.OSC_STRING,
            AnsiState.DCS_ENTRY,
            AnsiState.DCS_PASSTHROUGH,
            AnsiState.SOS_PM_APC_STRING,
            AnsiState.IGNORE_UNTIL_ST,
            AnsiState.OSC_ESCAPE,
            AnsiState.DCS_ESCAPE,
            AnsiState.SOS_PM_APC_ESCAPE,
            AnsiState.IGNORE_UNTIL_ST_ESCAPE,
        )

    private val stringStates: Set<Int> =
        setOf(
            AnsiState.OSC_STRING,
            AnsiState.OSC_ESCAPE,
            AnsiState.DCS_PASSTHROUGH,
            AnsiState.DCS_ESCAPE,
            AnsiState.SOS_PM_APC_STRING,
            AnsiState.SOS_PM_APC_ESCAPE,
            AnsiState.IGNORE_UNTIL_ST,
            AnsiState.IGNORE_UNTIL_ST_ESCAPE,
        )

    private val csiStates: Set<Int> =
        setOf(
            AnsiState.CSI_ENTRY,
            AnsiState.CSI_PARAM,
            AnsiState.CSI_INTERMEDIATE,
            AnsiState.CSI_IGNORE,
        )

    private fun assertRejectsState(state: Int) {
        val stringError =
            assertThrows(IllegalArgumentException::class.java) {
                AnsiState.isStringState(state)
            }
        val csiError =
            assertThrows(IllegalArgumentException::class.java) {
                AnsiState.isCsiState(state)
            }

        assertAll(
            { assertEquals("state out of range: $state", stringError.message) },
            { assertEquals("state out of range: $state", csiError.message) },
        )
    }

    // ----- Constants / layout ----------------------------------------------

    @Nested
    @DisplayName("constants and layout")
    inner class ConstantsAndLayout {
        @Test
        fun `state ids are contiguous and covered by COUNT`() {
            assertAll(
                { assertEquals(AnsiState.COUNT, allStates.size) },
                { assertEquals((0 until AnsiState.COUNT).toList(), allStates) },
                { assertEquals(allStates.size, allStates.toSet().size, "state ids must be unique") },
            )
        }

        @Test
        fun `ground is the default zero state`() {
            assertEquals(0, AnsiState.GROUND)
        }

        @Test
        fun `escape states keep their expected order`() {
            assertAll(
                { assertEquals(1, AnsiState.ESCAPE) },
                { assertEquals(2, AnsiState.ESCAPE_INTERMEDIATE) },
                { assertTrue(AnsiState.GROUND < AnsiState.ESCAPE) },
                { assertTrue(AnsiState.ESCAPE < AnsiState.ESCAPE_INTERMEDIATE) },
            )
        }

        @Test
        fun `CSI states occupy one contiguous block`() {
            assertEquals(
                (AnsiState.CSI_ENTRY..AnsiState.CSI_IGNORE).toSet(),
                csiStates,
            )
        }

        @Test
        fun `string-capable states are explicit and not implied by position`() {
            assertEquals(
                setOf(
                    AnsiState.OSC_STRING,
                    AnsiState.OSC_ESCAPE,
                    AnsiState.DCS_PASSTHROUGH,
                    AnsiState.DCS_ESCAPE,
                    AnsiState.SOS_PM_APC_STRING,
                    AnsiState.SOS_PM_APC_ESCAPE,
                    AnsiState.IGNORE_UNTIL_ST,
                    AnsiState.IGNORE_UNTIL_ST_ESCAPE,
                ),
                stringStates,
            )
        }

        @Test
        fun `count is one past the highest valid state id`() {
            assertEquals(allStates.max() + 1, AnsiState.COUNT)
        }
    }

    // ----- isStringState ----------------------------------------------------

    @Nested
    @DisplayName("isStringState")
    inner class IsStringState {
        @Test
        fun `returns true for OSC string state`() {
            assertTrue(AnsiState.isStringState(AnsiState.OSC_STRING))
        }

        @Test
        fun `returns true for DCS passthrough payload state`() {
            assertTrue(AnsiState.isStringState(AnsiState.DCS_PASSTHROUGH))
        }

        @Test
        fun `returns true for SOS PM APC string state`() {
            assertTrue(AnsiState.isStringState(AnsiState.SOS_PM_APC_STRING))
        }

        @Test
        fun `returns true for generic ignore until ST state`() {
            assertTrue(AnsiState.isStringState(AnsiState.IGNORE_UNTIL_ST))
        }

        @Test
        fun `returns false for every non-string state`() {
            for (state in allStates) {
                if (state !in stringStates) {
                    assertFalse(AnsiState.isStringState(state), "state $state")
                }
            }
        }

        @Test
        fun `does not classify DCS entry as a string payload state`() {
            assertFalse(AnsiState.isStringState(AnsiState.DCS_ENTRY))
        }

        @Test
        fun `does not classify ground escape or CSI states as string states`() {
            assertAll(
                { assertFalse(AnsiState.isStringState(AnsiState.GROUND)) },
                { assertFalse(AnsiState.isStringState(AnsiState.ESCAPE)) },
                { assertFalse(AnsiState.isStringState(AnsiState.ESCAPE_INTERMEDIATE)) },
                { assertFalse(AnsiState.isStringState(AnsiState.CSI_ENTRY)) },
                { assertFalse(AnsiState.isStringState(AnsiState.CSI_PARAM)) },
                { assertFalse(AnsiState.isStringState(AnsiState.CSI_INTERMEDIATE)) },
                { assertFalse(AnsiState.isStringState(AnsiState.CSI_IGNORE)) },
            )
        }
    }

    // ----- isCsiState -------------------------------------------------------

    @Nested
    @DisplayName("isCsiState")
    inner class IsCsiState {
        @Test
        fun `returns true for CSI entry state`() {
            assertTrue(AnsiState.isCsiState(AnsiState.CSI_ENTRY))
        }

        @Test
        fun `returns true for CSI parameter collection state`() {
            assertTrue(AnsiState.isCsiState(AnsiState.CSI_PARAM))
        }

        @Test
        fun `returns true for CSI intermediate collection state`() {
            assertTrue(AnsiState.isCsiState(AnsiState.CSI_INTERMEDIATE))
        }

        @Test
        fun `returns true for CSI ignore state`() {
            assertTrue(AnsiState.isCsiState(AnsiState.CSI_IGNORE))
        }

        @Test
        fun `returns false for every non-CSI state`() {
            for (state in allStates) {
                if (state !in csiStates) {
                    assertFalse(AnsiState.isCsiState(state), "state $state")
                }
            }
        }

        @Test
        fun `does not classify escape intermediate as CSI intermediate`() {
            assertFalse(AnsiState.isCsiState(AnsiState.ESCAPE_INTERMEDIATE))
        }

        @Test
        fun `does not classify string or DCS entry states as CSI states`() {
            assertAll(
                { assertFalse(AnsiState.isCsiState(AnsiState.OSC_STRING)) },
                { assertFalse(AnsiState.isCsiState(AnsiState.DCS_ENTRY)) },
                { assertFalse(AnsiState.isCsiState(AnsiState.DCS_PASSTHROUGH)) },
                { assertFalse(AnsiState.isCsiState(AnsiState.SOS_PM_APC_STRING)) },
                { assertFalse(AnsiState.isCsiState(AnsiState.IGNORE_UNTIL_ST)) },
            )
        }
    }

    // ----- Cross-predicate invariants --------------------------------------

    @Nested
    @DisplayName("cross-predicate invariants")
    inner class CrossPredicateInvariants {
        @Test
        fun `string and CSI state groups do not overlap`() {
            assertTrue(stringStates.intersect(csiStates).isEmpty())
        }

        @Test
        fun `no valid state is both a string state and a CSI state`() {
            for (state in allStates) {
                assertFalse(
                    AnsiState.isStringState(state) && AnsiState.isCsiState(state),
                    "state $state must not belong to both groups",
                )
            }
        }

        @Test
        fun `all valid states are accepted by both predicates`() {
            for (state in allStates) {
                assertDoesNotThrow { AnsiState.isStringState(state) }
                assertDoesNotThrow { AnsiState.isCsiState(state) }
            }
        }

        @Test
        fun `only string and CSI groups are recognized by helper predicates`() {
            val recognizedStates =
                allStates
                    .filter {
                        AnsiState.isStringState(it) || AnsiState.isCsiState(it)
                    }.toSet()

            assertEquals(stringStates + csiStates, recognizedStates)
        }

        @Test
        fun `ground escape and DCS entry are valid but ungrouped control-flow states`() {
            val ungroupedStates =
                setOf(
                    AnsiState.GROUND,
                    AnsiState.ESCAPE,
                    AnsiState.ESCAPE_INTERMEDIATE,
                    AnsiState.DCS_ENTRY,
                )

            for (state in ungroupedStates) {
                assertAll(
                    { assertFalse(AnsiState.isStringState(state), "state $state string") },
                    { assertFalse(AnsiState.isCsiState(state), "state $state CSI") },
                )
            }
        }
    }

    // ----- Input validation -------------------------------------------------

    @Nested
    @DisplayName("input validation")
    inner class InputValidation {
        @Test
        fun `lowest and highest valid state ids are accepted`() {
            assertAll(
                { assertDoesNotThrow { AnsiState.isStringState(0) } },
                { assertDoesNotThrow { AnsiState.isStringState(AnsiState.COUNT - 1) } },
                { assertDoesNotThrow { AnsiState.isCsiState(0) } },
                { assertDoesNotThrow { AnsiState.isCsiState(AnsiState.COUNT - 1) } },
            )
        }

        @Test
        fun `negative states are rejected by all public helpers`() {
            assertRejectsState(-1)
            assertRejectsState(Int.MIN_VALUE)
        }

        @Test
        fun `COUNT and larger states are rejected by all public helpers`() {
            assertRejectsState(AnsiState.COUNT)
            assertRejectsState(AnsiState.COUNT + 1)
            assertRejectsState(Int.MAX_VALUE)
        }
    }
}
