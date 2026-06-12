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

import io.github.jvterm.parser.runtime.ParserState
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("ANSI host harness")
internal class AnsiIntegrationHarnessTest {
    // ----- Helpers ----------------------------------------------------------

    private fun assertGround(h: AnsiHarness) {
        assertEquals(AnsiState.GROUND, h.state.fsmState)
    }

    private fun assertNoCommandDispatch(h: AnsiHarness) {
        assertAll(
            { assertTrue(h.dispatcher.controls.isEmpty(), "controls") },
            { assertTrue(h.dispatcher.esc.isEmpty(), "ESC") },
            { assertTrue(h.dispatcher.csi.isEmpty(), "CSI") },
            { assertTrue(h.sink.events.isEmpty(), "sink") },
        )
    }

    private fun assertPayloadBytes(
        h: AnsiHarness,
        expected: List<Int>,
    ) {
        assertEquals(expected.size, h.state.payloadLength)
        for (i in expected.indices) {
            assertEquals(expected[i].toByte(), h.state.payloadBuffer[i], "payload[$i]")
        }
    }

    // ----- Ground / printable ingress --------------------------------------

    @Nested
    @DisplayName("ground and printable ingress")
    inner class GroundAndPrintableIngress {
        @Test
        fun `plain ASCII prints through printable sink`() {
            val h = AnsiHarness()

            h.acceptAscii("abc")

            assertAll(
                { assertEquals(listOf('a'.code, 'b'.code, 'c'.code), h.printable.asciiBytes) },
                { assertGround(h) },
            )
        }

        @Test
        fun `ASCII structural bytes print as text while in ground`() {
            val h = AnsiHarness()

            h.acceptAscii("[]\\P_X^;:?")

            assertAll(
                { assertEquals("[]\\P_X^;:?".map { it.code }, h.printable.asciiBytes) },
                { assertNoCommandDispatch(h) },
                { assertGround(h) },
            )
        }

        @Test
        fun `non-ASCII bytes print only while in ground`() {
            val h = AnsiHarness()

            h.accept(byteArrayOf(0xE2.toByte(), 0x82.toByte(), 0xAC.toByte()))

            assertAll(
                { assertTrue(h.printable.asciiBytes.isEmpty()) },
                { assertEquals(listOf(0xE2, 0x82, 0xAC), h.printable.utf8Bytes) },
                { assertNoCommandDispatch(h) },
                { assertGround(h) },
            )
        }

        @Test
        fun `C0 controls execute in ground without printing`() {
            val h = AnsiHarness()

            h.accept(byteArrayOf('A'.code.toByte(), 0x07, 0x09, 'B'.code.toByte()))

            assertAll(
                { assertEquals(listOf('A'.code, 'B'.code), h.printable.asciiBytes) },
                { assertEquals(listOf(0x07, 0x09).map { RecordingCommandDispatcher.C0(it) }, h.dispatcher.controls) },
                { assertTrue(h.dispatcher.csi.isEmpty()) },
                { assertGround(h) },
            )
        }

        @Test
        fun `DEL is ignored in ground`() {
            val h = AnsiHarness()

            h.accept(byteArrayOf('A'.code.toByte(), 0x7F, 'B'.code.toByte()))

            assertAll(
                { assertEquals(listOf('A'.code, 'B'.code), h.printable.asciiBytes) },
                { assertNoCommandDispatch(h) },
                { assertGround(h) },
            )
        }
    }

    // ----- ESC --------------------------------------------------------------

    @Nested
    @DisplayName("ESC sequences")
    inner class EscSequences {
        @Test
        fun `plain ESC final dispatches through full matrix and action engine`() {
            val h = AnsiHarness()

            h.acceptAscii("\u001Bc")

            val esc = h.dispatcher.esc.single()
            assertAll(
                { assertEquals('c'.code, esc.finalByte) },
                { assertEquals(0, esc.intermediateCount) },
                { assertGround(h) },
            )
        }

        @Test
        fun `ESC intermediate bytes are preserved until final dispatch`() {
            val h = AnsiHarness()

            h.acceptAscii("\u001B#8")

            val esc = h.dispatcher.esc.single()
            assertAll(
                { assertEquals('8'.code, esc.finalByte) },
                { assertEquals('#'.code, esc.intermediates) },
                { assertEquals(1, esc.intermediateCount) },
                { assertGround(h) },
            )
        }

        @Test
        fun `ESC backslash outside a string is ordinary ESC dispatch`() {
            val h = AnsiHarness()

            h.acceptAscii("\u001B\\")

            assertAll(
                {
                    assertEquals(
                        '\\'.code,
                        h.dispatcher.esc
                            .single()
                            .finalByte,
                    )
                },
                { assertTrue(h.sink.events.isEmpty()) },
                { assertGround(h) },
            )
        }

        @Test
        fun `non-ASCII inside ESC clears sequence and does not print`() {
            val h = AnsiHarness()

            h.accept(byteArrayOf(0x1B, 0xC3.toByte(), 'X'.code.toByte()))

            assertAll(
                { assertTrue(h.dispatcher.esc.isEmpty()) },
                { assertEquals(listOf('X'.code), h.printable.asciiBytes) },
                { assertTrue(h.printable.utf8Bytes.isEmpty()) },
                { assertGround(h) },
            )
        }
    }

    // ----- CSI --------------------------------------------------------------

    @Nested
    @DisplayName("CSI sequences")
    inner class CsiSequences {
        @Test
        fun `CSI params dispatch through full matrix and action engine`() {
            val h = AnsiHarness()

            h.acceptAscii("\u001B[12;34H")

            val csi = h.dispatcher.csi.single()
            assertAll(
                { assertEquals('H'.code, csi.finalByte) },
                { assertEquals(listOf(12, 34), csi.params) },
                { assertGround(h) },
            )
        }

        @Test
        fun `DEC private CSI dispatch preserves private marker`() {
            val h = AnsiHarness()

            h.acceptAscii("\u001B[?25h")

            val csi = h.dispatcher.csi.single()
            assertAll(
                { assertEquals('h'.code, csi.finalByte) },
                { assertEquals('?'.code, csi.privateMarker) },
                { assertEquals(listOf(25), csi.params) },
                { assertGround(h) },
            )
        }

        @Test
        fun `CSI omitted fields are materialized`() {
            val h = AnsiHarness()

            h.acceptAscii("\u001B[;2;H")

            val csi = h.dispatcher.csi.single()
            assertAll(
                { assertEquals('H'.code, csi.finalByte) },
                { assertEquals(listOf(-1, 2, -1), csi.params) },
                { assertGround(h) },
            )
        }

        @Test
        fun `CSI subparameters preserve colon-opened field mask`() {
            val h = AnsiHarness()

            h.acceptAscii("\u001B[38:2:1:2:3m")

            val csi = h.dispatcher.csi.single()
            assertAll(
                { assertEquals('m'.code, csi.finalByte) },
                { assertEquals(listOf(38, 2, 1, 2, 3), csi.params) },
                { assertEquals(0b11110, csi.subParameterMask) },
                { assertGround(h) },
            )
        }

        @Test
        fun `CSI intermediate dispatch preserves intermediate bytes`() {
            val h = AnsiHarness()

            h.acceptAscii("\u001B[1\$q")

            val csi = h.dispatcher.csi.single()
            assertAll(
                { assertEquals('q'.code, csi.finalByte) },
                { assertEquals(listOf(1), csi.params) },
                { assertEquals('$'.code, csi.intermediates) },
                { assertEquals(1, csi.intermediateCount) },
                { assertGround(h) },
            )
        }

        @Test
        fun `ordinary C0 inside CSI executes without aborting the CSI`() {
            val h = AnsiHarness()

            h.accept(byteArrayOf(0x1B, '['.code.toByte(), '1'.code.toByte(), 0x07, '2'.code.toByte(), 'H'.code.toByte()))

            val csi = h.dispatcher.csi.single()
            assertAll(
                { assertEquals(listOf(0x07).map { RecordingCommandDispatcher.C0(it) }, h.dispatcher.controls) },
                { assertEquals('H'.code, csi.finalByte) },
                { assertEquals(listOf(12), csi.params) },
                { assertGround(h) },
            )
        }

        @Test
        fun `CAN inside CSI aborts sequence`() {
            val h = AnsiHarness()

            h.accept(byteArrayOf(0x1B, '['.code.toByte(), '1'.code.toByte(), 0x18, 'X'.code.toByte()))

            assertAll(
                { assertTrue(h.dispatcher.csi.isEmpty()) },
                { assertEquals(listOf(0x18).map { RecordingCommandDispatcher.C0(it) }, h.dispatcher.controls) },
                { assertEquals(listOf('X'.code), h.printable.asciiBytes) },
                { assertGround(h) },
            )
        }

        @Test
        fun `SUB inside CSI aborts sequence`() {
            val h = AnsiHarness()

            h.accept(byteArrayOf(0x1B, '['.code.toByte(), '9'.code.toByte(), 0x1A, 'X'.code.toByte()))

            assertAll(
                { assertTrue(h.dispatcher.csi.isEmpty()) },
                { assertEquals(listOf(0x1A).map { RecordingCommandDispatcher.C0(it) }, h.dispatcher.controls) },
                { assertEquals(listOf('X'.code), h.printable.asciiBytes) },
                { assertGround(h) },
            )
        }

        @Test
        fun `non-ASCII byte inside CSI clears sequence and does not print`() {
            val h = AnsiHarness()

            h.accept(byteArrayOf(0x1B, '['.code.toByte(), 0xC3.toByte(), 'X'.code.toByte()))

            assertAll(
                { assertTrue(h.dispatcher.csi.isEmpty()) },
                { assertEquals(listOf('X'.code), h.printable.asciiBytes) },
                { assertTrue(h.printable.utf8Bytes.isEmpty()) },
                { assertGround(h) },
            )
        }

        @Test
        fun `DEL inside CSI is ignored without changing parameter parsing`() {
            val h = AnsiHarness()

            h.accept(byteArrayOf(0x1B, '['.code.toByte(), '1'.code.toByte(), 0x7F, '2'.code.toByte(), 'H'.code.toByte()))

            val csi = h.dispatcher.csi.single()
            assertAll(
                { assertEquals('H'.code, csi.finalByte) },
                { assertEquals(listOf(12), csi.params) },
                { assertGround(h) },
            )
        }

        @Test
        fun `ESC inside CSI restarts escape parsing and clears the pending CSI`() {
            val h = AnsiHarness()

            h.acceptAscii("\u001B[12\u001BcX")

            assertAll(
                { assertTrue(h.dispatcher.csi.isEmpty()) },
                {
                    assertEquals(
                        'c'.code,
                        h.dispatcher.esc
                            .single()
                            .finalByte,
                    )
                },
                { assertEquals(listOf('X'.code), h.printable.asciiBytes) },
                { assertGround(h) },
            )
        }

        @Test
        fun `invalid private marker after CSI params enters ignore until final`() {
            val h = AnsiHarness()

            h.acceptAscii("\u001B[1?25hX")

            assertAll(
                { assertTrue(h.dispatcher.csi.isEmpty()) },
                { assertEquals(listOf('X'.code), h.printable.asciiBytes) },
                { assertGround(h) },
            )
        }

        @Test
        fun `parameter byte after CSI intermediate enters ignore until final`() {
            val h = AnsiHarness()

            h.acceptAscii("\u001B[\$1qX")

            assertAll(
                { assertTrue(h.dispatcher.csi.isEmpty()) },
                { assertEquals(listOf('X'.code), h.printable.asciiBytes) },
                { assertGround(h) },
            )
        }
    }

    // ----- OSC --------------------------------------------------------------

    @Nested
    @DisplayName("OSC strings")
    inner class OscStrings {
        @Test
        fun `OSC terminates with BEL`() {
            val h = AnsiHarness()

            h.acceptAscii("\u001B]0;title\u0007")

            assertAll(
                { assertEquals(listOf("setIconAndWindowTitle:title"), h.sink.events) },
                { assertGround(h) },
            )
        }

        @Test
        fun `OSC terminates with ST`() {
            val h = AnsiHarness()

            h.acceptAscii("\u001B]0;title\u001B\\")

            assertAll(
                { assertEquals(listOf("setIconAndWindowTitle:title"), h.sink.events) },
                { assertTrue(h.dispatcher.esc.isEmpty()) },
                { assertGround(h) },
            )
        }

        @Test
        fun `OSC ordinary C0 is ignored and BEL still terminates`() {
            val h = AnsiHarness()

            h.accept(byteArrayOf(0x1B, ']'.code.toByte(), 'a'.code.toByte(), 0x00, 'b'.code.toByte(), 0x07))

            assertAll(
                { assertTrue(h.sink.events.isEmpty()) },
                { assertTrue(h.dispatcher.controls.isEmpty()) },
                { assertGround(h) },
            )
        }

        @Test
        fun `OSC ST_INTRO byte is payload when it is not preceded by ESC`() {
            val h = AnsiHarness()

            h.acceptAscii("\u001B]a\\b\u0007")

            assertAll(
                { assertTrue(h.sink.events.isEmpty()) },
                { assertGround(h) },
            )
        }

        @Test
        fun `OSC ESC followed by non-ST resumes payload without dispatching ESC`() {
            val h = AnsiHarness()

            h.acceptAscii("\u001B]a\u001Bb\u0007")

            assertAll(
                { assertTrue(h.sink.events.isEmpty()) },
                { assertTrue(h.dispatcher.esc.isEmpty()) },
                { assertGround(h) },
            )
        }

        @Test
        fun `OSC repeated ESC keeps waiting for a real ST`() {
            val h = AnsiHarness()

            h.acceptAscii("\u001B]a\u001B\u001Bb\u0007")

            assertAll(
                { assertTrue(h.sink.events.isEmpty()) },
                { assertTrue(h.dispatcher.esc.isEmpty()) },
                { assertGround(h) },
            )
        }

        @Test
        fun `OSC nested introducers after ESC are payload, not new sequences`() {
            val h = AnsiHarness()

            h.acceptAscii("\u001B]a\u001B]b\u0007")

            assertAll(
                { assertTrue(h.sink.events.isEmpty()) },
                { assertTrue(h.dispatcher.esc.isEmpty()) },
                { assertGround(h) },
            )
        }

        @Test
        fun `OSC BEL after string-local ESC terminates OSC`() {
            val h = AnsiHarness()

            h.accept(byteArrayOf(0x1B, ']'.code.toByte(), 'a'.code.toByte(), 0x1B, 0x07))

            assertAll(
                { assertTrue(h.sink.events.isEmpty()) },
                { assertTrue(h.dispatcher.controls.isEmpty()) },
                { assertGround(h) },
            )
        }

        @Test
        fun `OSC DEL is ignored without entering payload`() {
            val h = AnsiHarness()

            h.accept(byteArrayOf(0x1B, ']'.code.toByte(), 'a'.code.toByte(), 0x7F, 'b'.code.toByte(), 0x07))

            assertAll(
                { assertTrue(h.sink.events.isEmpty()) },
                { assertGround(h) },
            )
        }

        @Test
        fun `OSC stores non-ASCII payload bytes until terminator`() {
            val h = AnsiHarness()

            h.accept(byteArrayOf(0x1B, ']'.code.toByte(), 0xE2.toByte(), 0x82.toByte(), 0xAC.toByte(), 0x07))

            assertAll(
                { assertTrue(h.sink.events.isEmpty()) },
                { assertTrue(h.printable.utf8Bytes.isEmpty()) },
                { assertGround(h) },
            )
        }

        @Test
        fun `OSC payload overflow is bounded and reported`() {
            val h = AnsiHarness(state = ParserState(maxPayload = 3))

            h.acceptAscii("\u001B]abcdef\u0007")

            assertAll(
                { assertTrue(h.sink.events.isEmpty()) },
                { assertGround(h) },
            )
        }

        @Test
        fun `unterminated OSC remains in string state without dispatching`() {
            val h = AnsiHarness()

            h.acceptAscii("\u001B]abc")

            assertAll(
                { assertTrue(h.sink.events.isEmpty()) },
                { assertEquals(AnsiState.OSC_STRING, h.state.fsmState) },
                { assertEquals(3, h.state.payloadLength) },
            )
        }

        @Test
        fun `CAN inside OSC terminates bounded OSC payload`() {
            val h = AnsiHarness()

            h.accept(byteArrayOf(0x1B, ']'.code.toByte(), 'a'.code.toByte(), 0x18))

            assertAll(
                { assertTrue(h.sink.events.isEmpty()) },
                { assertTrue(h.dispatcher.controls.isEmpty()) },
                { assertGround(h) },
            )
        }
    }

    // ----- DCS --------------------------------------------------------------

    @Nested
    @DisplayName("DCS passthrough")
    inner class DcsPassthrough {
        @Test
        fun `DCS ST drops payload without ESC dispatch`() {
            val h = AnsiHarness()

            h.acceptAscii("\u001BPabc\u001B\\")

            assertAll(
                { assertTrue(h.dispatcher.esc.isEmpty()) },
                { assertEquals(0, h.state.payloadLength) },
                { assertGround(h) },
            )
        }

        @Test
        fun `DCS entry ignores ordinary C0 before passthrough starts`() {
            val h = AnsiHarness()

            h.accept(byteArrayOf(0x1B, 'P'.code.toByte(), 0x00))

            assertAll(
                { assertTrue(h.dispatcher.controls.isEmpty()) },
                { assertEquals(AnsiState.DCS_ENTRY, h.state.fsmState) },
                { assertEquals(0, h.state.payloadLength) },
            )
        }

        @Test
        fun `DCS first payload byte enters passthrough and is retained`() {
            val h = AnsiHarness()

            h.acceptAscii("\u001BPq")

            assertAll(
                { assertEquals(AnsiState.DCS_PASSTHROUGH, h.state.fsmState) },
                { assertPayloadBytes(h, listOf('q'.code)) },
                { assertTrue(h.dispatcher.esc.isEmpty()) },
            )
        }

        @Test
        fun `ordinary C0 inside DCS passthrough is retained as payload`() {
            val h = AnsiHarness()

            h.accept(byteArrayOf(0x1B, 'P'.code.toByte(), 'q'.code.toByte(), 0x00))

            assertAll(
                { assertEquals(AnsiState.DCS_PASSTHROUGH, h.state.fsmState) },
                { assertPayloadBytes(h, listOf('q'.code, 0x00)) },
                { assertTrue(h.dispatcher.controls.isEmpty()) },
            )
        }

        @Test
        fun `DCS ESC followed by non-ST resumes passthrough payload`() {
            val h = AnsiHarness()

            h.acceptAscii("\u001BPq\u001BA")

            assertAll(
                { assertEquals(AnsiState.DCS_PASSTHROUGH, h.state.fsmState) },
                { assertPayloadBytes(h, listOf('q'.code, 'A'.code)) },
                { assertTrue(h.dispatcher.esc.isEmpty()) },
            )
        }

        @Test
        fun `DCS repeated ESC keeps waiting for a real ST`() {
            val h = AnsiHarness()

            h.acceptAscii("\u001BPq\u001B\u001BA")

            assertAll(
                { assertEquals(AnsiState.DCS_PASSTHROUGH, h.state.fsmState) },
                { assertPayloadBytes(h, listOf('q'.code, 'A'.code)) },
                { assertTrue(h.dispatcher.esc.isEmpty()) },
            )
        }

        @Test
        fun `DCS ST_INTRO byte is payload when it is not preceded by ESC`() {
            val h = AnsiHarness()

            h.acceptAscii("\u001BPq\\")

            assertAll(
                { assertEquals(AnsiState.DCS_PASSTHROUGH, h.state.fsmState) },
                { assertPayloadBytes(h, listOf('q'.code, '\\'.code)) },
            )
        }

        @Test
        fun `DCS stores non-ASCII payload bytes without printing`() {
            val h = AnsiHarness()

            h.accept(byteArrayOf(0x1B, 'P'.code.toByte(), 'q'.code.toByte(), 0xE2.toByte()))

            assertAll(
                { assertEquals(AnsiState.DCS_PASSTHROUGH, h.state.fsmState) },
                { assertPayloadBytes(h, listOf('q'.code, 0xE2)) },
                { assertTrue(h.printable.utf8Bytes.isEmpty()) },
            )
        }

        @Test
        fun `CAN inside DCS ends passthrough and clears payload`() {
            val h = AnsiHarness()

            h.accept(byteArrayOf(0x1B, 'P'.code.toByte(), 'q'.code.toByte(), 0x18))

            assertAll(
                { assertTrue(h.dispatcher.controls.isEmpty()) },
                { assertEquals(0, h.state.payloadLength) },
                { assertGround(h) },
            )
        }

        @Test
        fun `DEL inside DCS is ignored and not retained`() {
            val h = AnsiHarness()

            h.accept(byteArrayOf(0x1B, 'P'.code.toByte(), 'q'.code.toByte(), 0x7F, 'r'.code.toByte()))

            assertAll(
                { assertEquals(AnsiState.DCS_PASSTHROUGH, h.state.fsmState) },
                { assertPayloadBytes(h, listOf('q'.code, 'r'.code)) },
            )
        }
    }

    // ----- Ignored strings --------------------------------------------------

    @Nested
    @DisplayName("ignored SOS PM APC strings")
    inner class IgnoredStrings {
        @Test
        fun `SOS PM and APC ignore payload until ST`() {
            for (intro in listOf('X', '^', '_')) {
                val h = AnsiHarness()

                h.acceptAscii("\u001B${intro}ignored\u001B\\Z")

                assertAll(
                    "intro=$intro",
                    { assertNoCommandDispatch(h) },
                    { assertEquals(listOf('Z'.code), h.printable.asciiBytes) },
                    { assertGround(h) },
                )
            }
        }

        @Test
        fun `ignored strings ignore C0 DEL and non-ASCII payload`() {
            val h = AnsiHarness()

            h.accept(
                byteArrayOf(
                    0x1B,
                    'X'.code.toByte(),
                    'a'.code.toByte(),
                    0x07,
                    0x7F,
                    0xE2.toByte(),
                    0x1B,
                    '\\'.code.toByte(),
                    'Z'.code.toByte(),
                ),
            )

            assertAll(
                { assertTrue(h.dispatcher.controls.isEmpty()) },
                { assertTrue(h.printable.utf8Bytes.isEmpty()) },
                { assertEquals(listOf('Z'.code), h.printable.asciiBytes) },
                { assertGround(h) },
            )
        }

        @Test
        fun `ignored string ESC followed by non-ST resumes ignoring`() {
            val h = AnsiHarness()

            h.acceptAscii("\u001BXa\u001Bb\u001B\\Z")

            assertAll(
                { assertNoCommandDispatch(h) },
                { assertEquals(listOf('Z'.code), h.printable.asciiBytes) },
                { assertGround(h) },
            )
        }

        @Test
        fun `CAN inside ignored string returns to ground without dispatching`() {
            val h = AnsiHarness()

            h.accept(byteArrayOf(0x1B, 'X'.code.toByte(), 'a'.code.toByte(), 0x18, 'Z'.code.toByte()))

            assertAll(
                { assertNoCommandDispatch(h) },
                { assertEquals(listOf('Z'.code), h.printable.asciiBytes) },
                { assertGround(h) },
            )
        }
    }

    // ----- Split and mixed workloads ---------------------------------------

    @Nested
    @DisplayName("split and mixed workloads")
    inner class SplitAndMixedWorkloads {
        @Test
        fun `CSI sequence can be split across accept calls`() {
            val h = AnsiHarness()

            h.acceptAscii("\u001B[12")
            assertEquals(AnsiState.CSI_PARAM, h.state.fsmState)

            h.acceptAscii(";34H")

            val csi = h.dispatcher.csi.single()
            assertAll(
                { assertEquals('H'.code, csi.finalByte) },
                { assertEquals(listOf(12, 34), csi.params) },
                { assertGround(h) },
            )
        }

        @Test
        fun `OSC string can be split across accept calls`() {
            val h = AnsiHarness()

            h.acceptAscii("\u001B]0;ti")
            assertEquals(AnsiState.OSC_STRING, h.state.fsmState)

            h.acceptAscii("tle\u0007")

            assertAll(
                { assertEquals(listOf("setIconAndWindowTitle:title"), h.sink.events) },
                { assertGround(h) },
            )
        }

        @Test
        fun `mixed printable CSI OSC DCS and printable tail keep independent side effects`() {
            val h = AnsiHarness()

            h.acceptAscii("a\u001B[31mb\u001B]0;t\u0007c\u001BPq\u001B\\d")

            assertAll(
                { assertEquals(listOf('a'.code, 'b'.code, 'c'.code, 'd'.code), h.printable.asciiBytes) },
                { assertEquals(1, h.dispatcher.csi.size) },
                {
                    assertEquals(
                        listOf(31),
                        h.dispatcher.csi
                            .single()
                            .params,
                    )
                },
                { assertEquals(listOf("setIconAndWindowTitle:t"), h.sink.events) },
                { assertTrue(h.dispatcher.esc.isEmpty()) },
                { assertGround(h) },
            )
        }

        @Test
        fun `unterminated control sequence keeps state across later input`() {
            val h = AnsiHarness()

            h.acceptAscii("\u001B[12")
            assertEquals(AnsiState.CSI_PARAM, h.state.fsmState)

            h.acceptAscii("H")

            assertAll(
                {
                    assertEquals(
                        listOf(12),
                        h.dispatcher.csi
                            .single()
                            .params,
                    )
                },
                { assertGround(h) },
            )
        }
    }
}
