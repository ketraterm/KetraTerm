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
package io.github.jvterm.parser.runtime

import io.github.jvterm.parser.ansi.AnsiState
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("ParserState")
class ParserStateTest {
    // ----- Helpers ----------------------------------------------------------

    private fun dirtySequenceState(state: ParserState) {
        state.params[0] = 12
        state.params[1] = -1
        state.params[2] = 345
        state.paramCount = 3
        state.currentParamStarted = true
        state.subParameterMask = 0b1010
        state.intermediates = 0x2F_24_23_20
        state.intermediateCount = 4
        state.privateMarker = '?'.code
    }

    private fun dirtyUtf8State(state: ParserState) {
        state.utf8State = 7
        state.utf8Codepoint = 0x1F600
    }

    private fun dirtyClusterState(state: ParserState) {
        state.clusterBuffer[0] = 'a'.code
        state.clusterBuffer[1] = 0x0301
        state.clusterLength = 2
        state.prevGraphemeBreakClass = 13
        state.prevWasZwj = true
        state.zwjBeforeExtendedPictographic = true
        state.lastNonExtendWasExtendedPictographic = true
        state.regionalIndicatorParity = 1
    }

    private fun dirtyCharsetState(state: ParserState) {
        state.charsets[0] = ParserState.CHARSET_DEC_SPECIAL_GRAPHICS
        state.charsets[1] = 99
        state.charsets[2] = ParserState.CHARSET_DEC_SPECIAL_GRAPHICS
        state.charsets[3] = 42
        state.glSlot = 1
        state.grSlot = 3
        state.singleShiftSlot = 2
    }

    private fun dirtyPayloadState(state: ParserState) {
        state.payloadBuffer[0] = 0x1B
        state.payloadBuffer[1] = 'P'.code.toByte()
        state.payloadBuffer[2] = 'q'.code.toByte()
        state.payloadLength = 3
        state.payloadCode = 52
        state.payloadOverflowed = true
    }

    private fun dirtyEverything(state: ParserState) {
        state.fsmState = AnsiState.DCS_PASSTHROUGH
        dirtySequenceState(state)
        dirtyUtf8State(state)
        dirtyClusterState(state)
        dirtyCharsetState(state)
        dirtyPayloadState(state)
    }

    private fun assertDefaultSequenceState(state: ParserState) {
        assertAll(
            { assertEquals(0, state.paramCount) },
            { assertFalse(state.currentParamStarted) },
            { assertEquals(0, state.subParameterMask) },
            { assertEquals(0, state.intermediates) },
            { assertEquals(0, state.intermediateCount) },
            { assertEquals(0, state.privateMarker) },
        )
    }

    private fun assertDefaultUtf8State(state: ParserState) {
        assertAll(
            { assertEquals(0, state.utf8State) },
            { assertEquals(0, state.utf8Codepoint) },
        )
    }

    private fun assertDefaultClusterState(state: ParserState) {
        assertAll(
            { assertEquals(0, state.clusterLength) },
            { assertEquals(0, state.prevGraphemeBreakClass) },
            { assertFalse(state.prevWasZwj) },
            { assertFalse(state.zwjBeforeExtendedPictographic) },
            { assertFalse(state.lastNonExtendWasExtendedPictographic) },
            { assertEquals(0, state.regionalIndicatorParity) },
        )
    }

    private fun assertDefaultCharsetState(state: ParserState) {
        assertAll(
            { assertArrayEquals(IntArray(4) { ParserState.CHARSET_ASCII }, state.charsets) },
            { assertEquals(0, state.glSlot) },
            { assertEquals(2, state.grSlot) },
            { assertEquals(-1, state.singleShiftSlot) },
        )
    }

    private fun assertDefaultPayloadState(state: ParserState) {
        assertAll(
            { assertEquals(0, state.payloadLength) },
            { assertEquals(-1, state.payloadCode) },
            { assertFalse(state.payloadOverflowed) },
        )
    }

    private fun assertRejectsConstruction(
        maxParams: Int = ParserState.DEFAULT_MAX_PARAMS,
        maxCluster: Int = ParserState.DEFAULT_MAX_CLUSTER_CODEPOINTS,
        maxPayload: Int = ParserState.DEFAULT_MAX_PAYLOAD_BYTES,
        expectedMessage: String,
    ) {
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                ParserState(maxParams = maxParams, maxCluster = maxCluster, maxPayload = maxPayload)
            }
        assertEquals(expectedMessage, error.message)
    }

    // ----- Construction -----------------------------------------------------

    @Nested
    @DisplayName("construction")
    inner class Construction {
        @Test
        fun `default construction initializes all partitions`() {
            val state = ParserState()

            assertAll(
                { assertEquals(AnsiState.GROUND, state.fsmState) },
                { assertEquals(ParserState.DEFAULT_MAX_PARAMS, state.params.size) },
                { assertEquals(ParserState.DEFAULT_MAX_CLUSTER_CODEPOINTS, state.clusterBuffer.size) },
                { assertEquals(ParserState.DEFAULT_MAX_PAYLOAD_BYTES, state.payloadBuffer.size) },
                { assertDefaultSequenceState(state) },
                { assertDefaultUtf8State(state) },
                { assertDefaultClusterState(state) },
                { assertDefaultCharsetState(state) },
                { assertDefaultPayloadState(state) },
            )
        }

        @Test
        fun `minimum supported capacities construct successfully`() {
            val state = ParserState(maxParams = 1, maxCluster = 1, maxPayload = 1)

            assertAll(
                { assertEquals(1, state.params.size) },
                { assertEquals(1, state.clusterBuffer.size) },
                { assertEquals(1, state.payloadBuffer.size) },
                { assertEquals(AnsiState.GROUND, state.fsmState) },
                { assertDefaultSequenceState(state) },
                { assertDefaultClusterState(state) },
                { assertDefaultPayloadState(state) },
            )
        }

        @Test
        fun `maximum supported parameter capacity constructs successfully`() {
            val state = ParserState(maxParams = 32)

            assertEquals(32, state.params.size)
        }

        @Test
        fun `invalid parameter capacities are rejected`() {
            assertAll(
                {
                    assertRejectsConstruction(
                        maxParams = 0,
                        expectedMessage = "maxParams must be in 1..32, got 0",
                    )
                },
                {
                    assertRejectsConstruction(
                        maxParams = -1,
                        expectedMessage = "maxParams must be in 1..32, got -1",
                    )
                },
                {
                    assertRejectsConstruction(
                        maxParams = 33,
                        expectedMessage = "maxParams must be in 1..32, got 33",
                    )
                },
                {
                    assertRejectsConstruction(
                        maxParams = Int.MIN_VALUE,
                        expectedMessage = "maxParams must be in 1..32, got ${Int.MIN_VALUE}",
                    )
                },
            )
        }

        @Test
        fun `invalid cluster capacities are rejected`() {
            assertAll(
                {
                    assertRejectsConstruction(
                        maxCluster = 0,
                        expectedMessage = "maxCluster must be positive, got 0",
                    )
                },
                {
                    assertRejectsConstruction(
                        maxCluster = -1,
                        expectedMessage = "maxCluster must be positive, got -1",
                    )
                },
                {
                    assertRejectsConstruction(
                        maxCluster = Int.MIN_VALUE,
                        expectedMessage = "maxCluster must be positive, got ${Int.MIN_VALUE}",
                    )
                },
            )
        }

        @Test
        fun `invalid payload capacities are rejected`() {
            assertAll(
                {
                    assertRejectsConstruction(
                        maxPayload = 0,
                        expectedMessage = "maxPayload must be positive, got 0",
                    )
                },
                {
                    assertRejectsConstruction(
                        maxPayload = -1,
                        expectedMessage = "maxPayload must be positive, got -1",
                    )
                },
                {
                    assertRejectsConstruction(
                        maxPayload = Int.MIN_VALUE,
                        expectedMessage = "maxPayload must be positive, got ${Int.MIN_VALUE}",
                    )
                },
            )
        }
    }

    // ----- Defaults ---------------------------------------------------------

    @Nested
    @DisplayName("default invariants")
    inner class DefaultInvariants {
        @Test
        fun `params start with no valid fields even though backing array is allocated`() {
            val state = ParserState(maxParams = 4)

            assertAll(
                { assertEquals(0, state.paramCount) },
                { assertArrayEquals(intArrayOf(0, 0, 0, 0), state.params) },
            )
        }

        @Test
        fun `payload starts empty and unparsed`() {
            val state = ParserState(maxPayload = 4)

            assertAll(
                { assertEquals(0, state.payloadLength) },
                { assertEquals(-1, state.payloadCode) },
                { assertFalse(state.payloadOverflowed) },
                { assertArrayEquals(byteArrayOf(0, 0, 0, 0), state.payloadBuffer) },
            )
        }

        @Test
        fun `charset state starts with G0 mapped to GL and G2 mapped to GR`() {
            val state = ParserState()

            assertAll(
                { assertArrayEquals(IntArray(4) { ParserState.CHARSET_ASCII }, state.charsets) },
                { assertEquals(0, state.glSlot) },
                { assertEquals(2, state.grSlot) },
                { assertEquals(-1, state.singleShiftSlot) },
            )
        }
    }

    // ----- clearSequenceState ----------------------------------------------

    @Nested
    @DisplayName("clearSequenceState")
    inner class ClearSequenceState {
        @Test
        fun `clears sequence metadata in O(1)`() {
            val state = ParserState()
            dirtySequenceState(state)

            state.clearSequenceState()

            assertDefaultSequenceState(state)
        }

        @Test
        fun `does not wipe parameter backing storage`() {
            val state = ParserState()
            dirtySequenceState(state)

            state.clearSequenceState()

            assertAll(
                { assertEquals(12, state.params[0]) },
                { assertEquals(-1, state.params[1]) },
                { assertEquals(345, state.params[2]) },
            )
        }

        @Test
        fun `does not touch unrelated runtime partitions`() {
            val state = ParserState()
            dirtyEverything(state)

            state.clearSequenceState()

            assertAll(
                { assertEquals(AnsiState.DCS_PASSTHROUGH, state.fsmState) },
                { assertEquals(7, state.utf8State) },
                { assertEquals(2, state.clusterLength) },
                { assertEquals(1, state.glSlot) },
                { assertEquals(3, state.payloadLength) },
            )
        }

        @Test
        fun `is idempotent`() {
            val state = ParserState()

            state.clearSequenceState()
            state.clearSequenceState()

            assertDefaultSequenceState(state)
        }
    }

    // ----- clearPayloadState -----------------------------------------------

    @Nested
    @DisplayName("clearPayloadState")
    inner class ClearPayloadState {
        @Test
        fun `clears payload validity metadata`() {
            val state = ParserState()
            dirtyPayloadState(state)

            state.clearPayloadState()

            assertDefaultPayloadState(state)
        }

        @Test
        fun `does not wipe payload backing storage`() {
            val state = ParserState()
            dirtyPayloadState(state)

            state.clearPayloadState()

            assertAll(
                { assertEquals(0x1B.toByte(), state.payloadBuffer[0]) },
                { assertEquals('P'.code.toByte(), state.payloadBuffer[1]) },
                { assertEquals('q'.code.toByte(), state.payloadBuffer[2]) },
            )
        }

        @Test
        fun `does not touch sequence UTF-8 cluster charset or FSM state`() {
            val state = ParserState()
            dirtyEverything(state)

            state.clearPayloadState()

            assertAll(
                { assertEquals(AnsiState.DCS_PASSTHROUGH, state.fsmState) },
                { assertEquals(3, state.paramCount) },
                { assertEquals(7, state.utf8State) },
                { assertEquals(2, state.clusterLength) },
                { assertEquals(1, state.glSlot) },
            )
        }

        @Test
        fun `is idempotent`() {
            val state = ParserState()

            state.clearPayloadState()
            state.clearPayloadState()

            assertDefaultPayloadState(state)
        }
    }

    // ----- clearActiveClusterAfterFlush -------------------------------------

    @Nested
    @DisplayName("clearActiveClusterAfterFlush")
    inner class ClearActiveClusterAfterFlush {
        @Test
        fun `clears active grapheme metadata`() {
            val state = ParserState()
            dirtyClusterState(state)

            state.clearActiveClusterAfterFlush()

            assertDefaultClusterState(state)
        }

        @Test
        fun `does not wipe cluster backing storage`() {
            val state = ParserState()
            dirtyClusterState(state)

            state.clearActiveClusterAfterFlush()

            assertAll(
                { assertEquals('a'.code, state.clusterBuffer[0]) },
                { assertEquals(0x0301, state.clusterBuffer[1]) },
            )
        }

        @Test
        fun `does not reset UTF-8 decoder or unrelated parser state`() {
            val state = ParserState()
            dirtyEverything(state)

            state.clearActiveClusterAfterFlush()

            assertAll(
                { assertEquals(AnsiState.DCS_PASSTHROUGH, state.fsmState) },
                { assertEquals(7, state.utf8State) },
                { assertEquals(0x1F600, state.utf8Codepoint) },
                { assertEquals(3, state.paramCount) },
                { assertEquals(1, state.glSlot) },
                { assertEquals(3, state.payloadLength) },
            )
        }

        @Test
        fun `is idempotent`() {
            val state = ParserState()

            state.clearActiveClusterAfterFlush()
            state.clearActiveClusterAfterFlush()

            assertDefaultClusterState(state)
        }
    }

    // ----- resetUtf8State ---------------------------------------------------

    @Nested
    @DisplayName("resetUtf8State")
    inner class ResetUtf8State {
        @Test
        fun `resets decoder progress and partial codepoint`() {
            val state = ParserState()
            dirtyUtf8State(state)

            state.resetUtf8State()

            assertDefaultUtf8State(state)
        }

        @Test
        fun `does not clear active cluster or parser accumulators`() {
            val state = ParserState()
            dirtyEverything(state)

            state.resetUtf8State()

            assertAll(
                { assertEquals(2, state.clusterLength) },
                { assertEquals(3, state.paramCount) },
                { assertEquals(1, state.glSlot) },
                { assertEquals(3, state.payloadLength) },
                { assertEquals(AnsiState.DCS_PASSTHROUGH, state.fsmState) },
            )
        }

        @Test
        fun `is idempotent`() {
            val state = ParserState()

            state.resetUtf8State()
            state.resetUtf8State()

            assertDefaultUtf8State(state)
        }
    }

    // ----- resetCharsetState ------------------------------------------------

    @Nested
    @DisplayName("resetCharsetState")
    inner class ResetCharsetState {
        @Test
        fun `restores all charset slots and shifts to defaults`() {
            val state = ParserState()
            dirtyCharsetState(state)

            state.resetCharsetState()

            assertDefaultCharsetState(state)
        }

        @Test
        fun `does not touch sequence UTF-8 cluster payload or FSM state`() {
            val state = ParserState()
            dirtyEverything(state)

            state.resetCharsetState()

            assertAll(
                { assertEquals(AnsiState.DCS_PASSTHROUGH, state.fsmState) },
                { assertEquals(3, state.paramCount) },
                { assertEquals(7, state.utf8State) },
                { assertEquals(2, state.clusterLength) },
                { assertEquals(3, state.payloadLength) },
            )
        }

        @Test
        fun `is idempotent`() {
            val state = ParserState()

            state.resetCharsetState()
            state.resetCharsetState()

            assertDefaultCharsetState(state)
        }
    }

    // ----- resetAll ---------------------------------------------------------

    @Nested
    @DisplayName("resetAll")
    inner class ResetAll {
        @Test
        fun `resets every logical partition to defaults`() {
            val state = ParserState()
            dirtyEverything(state)

            state.resetAll()

            assertAll(
                { assertEquals(AnsiState.GROUND, state.fsmState) },
                { assertDefaultSequenceState(state) },
                { assertDefaultUtf8State(state) },
                { assertDefaultClusterState(state) },
                { assertDefaultCharsetState(state) },
                { assertDefaultPayloadState(state) },
            )
        }

        @Test
        fun `does not wipe scratch buffers while resetting their valid lengths`() {
            val state = ParserState()
            dirtyEverything(state)

            state.resetAll()

            assertAll(
                { assertEquals(12, state.params[0]) },
                { assertEquals(-1, state.params[1]) },
                { assertEquals('a'.code, state.clusterBuffer[0]) },
                { assertEquals(0x0301, state.clusterBuffer[1]) },
                { assertEquals(0x1B.toByte(), state.payloadBuffer[0]) },
                { assertEquals('P'.code.toByte(), state.payloadBuffer[1]) },
                { assertEquals(0, state.paramCount) },
                { assertEquals(0, state.clusterLength) },
                { assertEquals(0, state.payloadLength) },
            )
        }

        @Test
        fun `preserves configured capacities`() {
            val state = ParserState(maxParams = 3, maxCluster = 2, maxPayload = 5)
            state.fsmState = AnsiState.CSI_PARAM
            state.resetAll()

            assertAll(
                { assertEquals(3, state.params.size) },
                { assertEquals(2, state.clusterBuffer.size) },
                { assertEquals(5, state.payloadBuffer.size) },
                { assertEquals(AnsiState.GROUND, state.fsmState) },
            )
        }

        @Test
        fun `is idempotent after dirty state`() {
            val state = ParserState()
            dirtyEverything(state)

            state.resetAll()
            state.resetAll()

            assertAll(
                { assertEquals(AnsiState.GROUND, state.fsmState) },
                { assertDefaultSequenceState(state) },
                { assertDefaultUtf8State(state) },
                { assertDefaultClusterState(state) },
                { assertDefaultCharsetState(state) },
                { assertDefaultPayloadState(state) },
            )
        }
    }
}
