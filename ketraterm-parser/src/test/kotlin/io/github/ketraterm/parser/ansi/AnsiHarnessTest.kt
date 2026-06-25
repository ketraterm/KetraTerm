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

@DisplayName("AnsiHarness")
class AnsiHarnessTest {
    @Nested
    @DisplayName("byte ingestion")
    inner class ByteIngestion {
        @Test
        fun `acceptByte rejects values outside unsigned byte range`() {
            val harness = AnsiHarness()

            assertAll(
                {
                    assertThrows(IllegalArgumentException::class.java) {
                        harness.acceptByte(-1)
                    }
                },
                {
                    assertThrows(IllegalArgumentException::class.java) {
                        harness.acceptByte(256)
                    }
                },
            )
        }

        @Test
        fun `accept treats signed ByteArray elements as unsigned bytes`() {
            val harness = AnsiHarness()

            harness.accept(byteArrayOf(0xE2.toByte(), 0x82.toByte(), 0xAC.toByte()))

            assertAll(
                { assertEquals(listOf(0xE2, 0x82, 0xAC), harness.printable.utf8Bytes) },
                { assertTrue(harness.printable.asciiBytes.isEmpty()) },
                { assertEquals(AnsiState.GROUND, harness.state.fsmState) },
            )
        }

        @Test
        fun `accept with an empty byte array leaves harness state untouched`() {
            val harness = AnsiHarness()

            harness.accept(byteArrayOf())

            assertAll(
                { assertEquals(AnsiState.GROUND, harness.state.fsmState) },
                { assertTrue(harness.printable.asciiBytes.isEmpty()) },
                { assertTrue(harness.printable.utf8Bytes.isEmpty()) },
                { assertTrue(harness.dispatcher.controls.isEmpty()) },
                { assertTrue(harness.sink.events.isEmpty()) },
            )
        }
    }

    @Nested
    @DisplayName("ASCII traces")
    inner class AsciiTraces {
        @Test
        fun `acceptAscii feeds printable ASCII through the real matrix and action engine`() {
            val harness = AnsiHarness()

            harness.acceptAscii("Hi")

            assertAll(
                { assertEquals(listOf('H'.code, 'i'.code), harness.printable.asciiBytes) },
                { assertTrue(harness.printable.utf8Bytes.isEmpty()) },
                { assertEquals(0, harness.printable.flushCount) },
                { assertEquals(AnsiState.GROUND, harness.state.fsmState) },
            )
        }

        @Test
        fun `CSI trace records final and parameter snapshot`() {
            val harness = AnsiHarness()

            harness.acceptAscii("\u001B[?25h")

            val csi = harness.dispatcher.csi.single()
            assertAll(
                { assertEquals('h'.code, csi.finalByte) },
                { assertEquals(listOf(25), csi.params) },
                { assertEquals('?'.code, csi.privateMarker) },
                { assertEquals(AnsiState.GROUND, harness.state.fsmState) },
                { assertEquals(0, harness.state.paramCount) },
            )
        }
    }

    @Nested
    @DisplayName("string traces")
    inner class StringTraces {
        @Test
        fun `OSC BEL dispatches semantic OSC title`() {
            val harness = AnsiHarness()

            harness.acceptAscii("\u001B]0;t\u0007")

            assertAll(
                { assertEquals(listOf("setIconAndWindowTitle:t"), harness.sink.events) },
                { assertTrue(harness.dispatcher.controls.isEmpty()) },
                { assertEquals(AnsiState.GROUND, harness.state.fsmState) },
            )
        }

        @Test
        fun `OSC ST terminates without ESC dispatch`() {
            val harness = AnsiHarness()

            harness.acceptAscii("\u001B]1;x\u001B\\")

            assertAll(
                { assertEquals(listOf("setIconTitle:x"), harness.sink.events) },
                { assertTrue(harness.dispatcher.esc.isEmpty()) },
                { assertEquals(AnsiState.GROUND, harness.state.fsmState) },
            )
        }

        @Test
        fun `DCS entry ignores ordinary C0 before passthrough payload starts`() {
            val harness = AnsiHarness()

            harness.accept(byteArrayOf(0x1B, 'P'.code.toByte(), 0x00))

            assertAll(
                { assertEquals(AnsiState.DCS_ENTRY, harness.state.fsmState) },
                { assertTrue(harness.dispatcher.controls.isEmpty()) },
                { assertEquals(0, harness.state.payloadLength) },
            )
        }

        @Test
        fun `DCS ST clears passthrough payload without ESC dispatch`() {
            val harness = AnsiHarness()

            harness.acceptAscii("\u001BPq\u001B\\")

            assertAll(
                { assertEquals(AnsiState.GROUND, harness.state.fsmState) },
                { assertTrue(harness.dispatcher.esc.isEmpty()) },
                { assertEquals(0, harness.state.payloadLength) },
                { assertTrue(harness.sink.events.isEmpty()) },
            )
        }
    }
}
