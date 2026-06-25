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

@DisplayName("ByteClass")
class ByteClassTest {
    // ----- Helpers ----------------------------------------------------------

    private fun assertClass(
        byteValue: Int,
        expectedClass: Int,
    ) {
        assertAll(
            { assertEquals(expectedClass, ByteClass.classify(byteValue), "classify(0x${byteValue.hex()})") },
            { assertEquals(expectedClass, ByteClass.ASCII_MAP[byteValue].toInt(), "ASCII_MAP[0x${byteValue.hex()}]") },
        )
    }

    private fun assertClassRange(
        range: IntRange,
        expectedClass: Int,
    ) {
        for (byteValue in range) {
            assertClass(byteValue, expectedClass)
        }
    }

    private fun assertRejectsByteValue(byteValue: Int) {
        val classifyError =
            assertThrows(IllegalArgumentException::class.java) {
                ByteClass.classify(byteValue)
            }
        val asciiError =
            assertThrows(IllegalArgumentException::class.java) {
                ByteClass.isAsciiDomain(byteValue)
            }
        val utf8Error =
            assertThrows(IllegalArgumentException::class.java) {
                ByteClass.isUtf8DomainByDefault(byteValue)
            }

        assertAll(
            { assertEquals("byteValue out of range: $byteValue", classifyError.message) },
            { assertEquals("byteValue out of range: $byteValue", asciiError.message) },
            { assertEquals("byteValue out of range: $byteValue", utf8Error.message) },
        )
    }

    private fun expectedAsciiClass(byteValue: Int): Int =
        when (byteValue) {
            in 0x00..0x17 -> ByteClass.EXECUTE
            0x18 -> ByteClass.CAN_SUB
            0x19 -> ByteClass.EXECUTE
            0x1A -> ByteClass.CAN_SUB
            0x1B -> ByteClass.ESC
            in 0x1C..0x1F -> ByteClass.EXECUTE
            in 0x20..0x2F -> ByteClass.INTERMEDIATE
            in 0x30..0x39 -> ByteClass.PARAM_DIGIT
            0x3A -> ByteClass.COLON
            0x3B -> ByteClass.PARAM_SEP
            in 0x3C..0x3F -> ByteClass.PRIVATE_MARKER
            'P'.code -> ByteClass.DCS_INTRO
            'X'.code, '^'.code, '_'.code -> ByteClass.SOS_PM_APC_INTRO
            '['.code -> ByteClass.CSI_INTRO
            '\\'.code -> ByteClass.ST_INTRO
            ']'.code -> ByteClass.OSC_INTRO
            in 0x40..0x7E -> ByteClass.FINAL_BYTE
            0x7F -> ByteClass.DEL
            else -> error("outside ASCII domain: $byteValue")
        }

    private fun Int.hex(): String = toString(16).uppercase().padStart(2, '0')

    // ----- Constants / table shape -----------------------------------------

    @Nested
    @DisplayName("constants and lookup table")
    inner class ConstantsAndLookupTable {
        @Test
        fun `ASCII domain class ids are contiguous and covered by COUNT`() {
            val asciiClasses =
                listOf(
                    ByteClass.EXECUTE,
                    ByteClass.CAN_SUB,
                    ByteClass.ESC,
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
                    ByteClass.DEL,
                )

            assertAll(
                { assertEquals(ByteClass.COUNT, asciiClasses.size) },
                { assertEquals((0 until ByteClass.COUNT).toList(), asciiClasses) },
                { assertEquals(asciiClasses.size, asciiClasses.toSet().size, "class ids must be unique") },
            )
        }

        @Test
        fun `UTF8 payload sentinel is outside ASCII class count`() {
            assertAll(
                { assertEquals(ByteClass.COUNT, ByteClass.UTF8_PAYLOAD) },
                { assertTrue(ByteClass.UTF8_PAYLOAD !in 0 until ByteClass.COUNT) },
                { assertEquals(ByteClass.UTF8_PAYLOAD + 1, ByteClass.ROUTING_COUNT) },
            )
        }

        @Test
        fun `ASCII_MAP has exactly one slot for every 7-bit byte`() {
            assertEquals(128, ByteClass.ASCII_MAP.size)
        }

        @Test
        fun `ASCII_MAP and classify agree for every ASCII byte`() {
            for (byteValue in 0x00..0x7F) {
                assertEquals(
                    ByteClass.ASCII_MAP[byteValue].toInt(),
                    ByteClass.classify(byteValue),
                    "byte 0x${byteValue.hex()}",
                )
            }
        }

        @Test
        fun `ASCII_MAP contains only active ASCII class ids`() {
            for (byteValue in 0x00..0x7F) {
                assertTrue(
                    ByteClass.ASCII_MAP[byteValue].toInt() in 0 until ByteClass.COUNT,
                    "byte 0x${byteValue.hex()} has an invalid class",
                )
            }
        }
    }

    // ----- C0 controls ------------------------------------------------------

    @Nested
    @DisplayName("C0 control classification")
    inner class C0ControlClassification {
        @Test
        fun `ordinary C0 controls are execute bytes`() {
            assertClassRange(0x00..0x17, ByteClass.EXECUTE)
            assertClass(0x19, ByteClass.EXECUTE)
            assertClassRange(0x1C..0x1F, ByteClass.EXECUTE)
        }

        @Test
        fun `CAN and SUB abort current parser sequence`() {
            assertAll(
                { assertClass(0x18, ByteClass.CAN_SUB) },
                { assertClass(0x1A, ByteClass.CAN_SUB) },
            )
        }

        @Test
        fun `ESC has its own introducer class`() {
            assertClass(0x1B, ByteClass.ESC)
        }

        @Test
        fun `control range boundaries do not bleed into neighboring classes`() {
            assertAll(
                { assertClass(0x17, ByteClass.EXECUTE) },
                { assertClass(0x18, ByteClass.CAN_SUB) },
                { assertClass(0x19, ByteClass.EXECUTE) },
                { assertClass(0x1A, ByteClass.CAN_SUB) },
                { assertClass(0x1B, ByteClass.ESC) },
                { assertClass(0x1C, ByteClass.EXECUTE) },
                { assertClass(0x20, ByteClass.INTERMEDIATE) },
            )
        }
    }

    // ----- ASCII structural domain -----------------------------------------

    @Nested
    @DisplayName("ASCII structural classification")
    inner class AsciiStructuralClassification {
        @Test
        fun `intermediate bytes are classified from space through slash`() {
            assertClassRange(0x20..0x2F, ByteClass.INTERMEDIATE)
        }

        @Test
        fun `decimal parameter digits are classified together`() {
            assertClassRange(0x30..0x39, ByteClass.PARAM_DIGIT)
        }

        @Test
        fun `colon is distinct from parameter separator`() {
            assertAll(
                { assertClass(0x3A, ByteClass.COLON) },
                { assertClass(0x3B, ByteClass.PARAM_SEP) },
            )
        }

        @Test
        fun `private marker bytes are less-than through question-mark`() {
            assertClassRange(0x3C..0x3F, ByteClass.PRIVATE_MARKER)
        }

        @Test
        fun `boundary bytes around parameter grammar keep their own classes`() {
            assertAll(
                { assertClass(0x2F, ByteClass.INTERMEDIATE) },
                { assertClass(0x30, ByteClass.PARAM_DIGIT) },
                { assertClass(0x39, ByteClass.PARAM_DIGIT) },
                { assertClass(0x3A, ByteClass.COLON) },
                { assertClass(0x3B, ByteClass.PARAM_SEP) },
                { assertClass(0x3C, ByteClass.PRIVATE_MARKER) },
                { assertClass(0x3F, ByteClass.PRIVATE_MARKER) },
                { assertClass(0x40, ByteClass.FINAL_BYTE) },
            )
        }
    }

    // ----- Final bytes / introducers ---------------------------------------

    @Nested
    @DisplayName("final bytes and structural introducers")
    inner class FinalBytesAndStructuralIntroducers {
        @Test
        fun `ordinary final bytes are classified as FINAL_BYTE`() {
            val structuralIntroducers =
                setOf(
                    'P'.code,
                    'X'.code,
                    '['.code,
                    '\\'.code,
                    ']'.code,
                    '^'.code,
                    '_'.code,
                )

            for (byteValue in 0x40..0x7E) {
                if (byteValue !in structuralIntroducers) {
                    assertClass(byteValue, ByteClass.FINAL_BYTE)
                }
            }
        }

        @Test
        fun `DCS introducer overrides the ordinary final-byte class`() {
            assertClass('P'.code, ByteClass.DCS_INTRO)
        }

        @Test
        fun `CSI introducer overrides the ordinary final-byte class`() {
            assertClass('['.code, ByteClass.CSI_INTRO)
        }

        @Test
        fun `ST introducer overrides the ordinary final-byte class`() {
            assertClass('\\'.code, ByteClass.ST_INTRO)
        }

        @Test
        fun `OSC introducer overrides the ordinary final-byte class`() {
            assertClass(']'.code, ByteClass.OSC_INTRO)
        }

        @Test
        fun `SOS PM and APC introducers share one class`() {
            assertAll(
                { assertClass('X'.code, ByteClass.SOS_PM_APC_INTRO) },
                { assertClass('^'.code, ByteClass.SOS_PM_APC_INTRO) },
                { assertClass('_'.code, ByteClass.SOS_PM_APC_INTRO) },
            )
        }

        @Test
        fun `bytes adjacent to structural introducers remain final bytes`() {
            assertAll(
                { assertClass('O'.code, ByteClass.FINAL_BYTE) },
                { assertClass('Q'.code, ByteClass.FINAL_BYTE) },
                { assertClass('W'.code, ByteClass.FINAL_BYTE) },
                { assertClass('Y'.code, ByteClass.FINAL_BYTE) },
                { assertClass('Z'.code, ByteClass.FINAL_BYTE) },
                { assertClass('`'.code, ByteClass.FINAL_BYTE) },
            )
        }

        @Test
        fun `DEL is not treated as a final byte`() {
            assertClass(0x7F, ByteClass.DEL)
        }
    }

    // ----- Whole-domain invariants -----------------------------------------

    @Nested
    @DisplayName("whole-domain classification")
    inner class WholeDomainClassification {
        @Test
        fun `every ASCII byte has the expected class`() {
            for (byteValue in 0x00..0x7F) {
                assertEquals(
                    expectedAsciiClass(byteValue),
                    ByteClass.classify(byteValue),
                    "byte 0x${byteValue.hex()}",
                )
            }
        }

        @Test
        fun `every non-ASCII byte routes to UTF-8 payload`() {
            for (byteValue in 0x80..0xFF) {
                assertEquals(
                    ByteClass.UTF8_PAYLOAD,
                    ByteClass.classify(byteValue),
                    "byte 0x${byteValue.hex()}",
                )
            }
        }

        @Test
        fun `C1 control range is UTF-8 payload by default`() {
            for (byteValue in 0x80..0x9F) {
                assertEquals(
                    ByteClass.UTF8_PAYLOAD,
                    ByteClass.classify(byteValue),
                    "C1 byte 0x${byteValue.hex()} must not enter ANSI control classes by default",
                )
            }
        }

        @Test
        fun `representative UTF-8 lead continuation and invalid bytes all stay in UTF-8 domain`() {
            assertAll(
                { assertEquals(ByteClass.UTF8_PAYLOAD, ByteClass.classify(0x80)) },
                { assertEquals(ByteClass.UTF8_PAYLOAD, ByteClass.classify(0xBF)) },
                { assertEquals(ByteClass.UTF8_PAYLOAD, ByteClass.classify(0xC2)) },
                { assertEquals(ByteClass.UTF8_PAYLOAD, ByteClass.classify(0xE2)) },
                { assertEquals(ByteClass.UTF8_PAYLOAD, ByteClass.classify(0xF0)) },
                { assertEquals(ByteClass.UTF8_PAYLOAD, ByteClass.classify(0xFF)) },
            )
        }
    }

    // ----- Domain predicates ------------------------------------------------

    @Nested
    @DisplayName("domain predicates")
    inner class DomainPredicates {
        @Test
        fun `ASCII predicate is true only for 7-bit byte values`() {
            for (byteValue in 0x00..0x7F) {
                assertTrue(ByteClass.isAsciiDomain(byteValue), "byte 0x${byteValue.hex()}")
            }

            for (byteValue in 0x80..0xFF) {
                assertFalse(ByteClass.isAsciiDomain(byteValue), "byte 0x${byteValue.hex()}")
            }
        }

        @Test
        fun `UTF-8 default predicate is the inverse of ASCII domain for valid byte values`() {
            for (byteValue in 0x00..0xFF) {
                assertEquals(
                    !ByteClass.isAsciiDomain(byteValue),
                    ByteClass.isUtf8DomainByDefault(byteValue),
                    "byte 0x${byteValue.hex()}",
                )
            }
        }

        @Test
        fun `domain predicates have correct boundary behavior`() {
            assertAll(
                { assertTrue(ByteClass.isAsciiDomain(0x00)) },
                { assertTrue(ByteClass.isAsciiDomain(0x7F)) },
                { assertFalse(ByteClass.isAsciiDomain(0x80)) },
                { assertFalse(ByteClass.isAsciiDomain(0xFF)) },
                { assertFalse(ByteClass.isUtf8DomainByDefault(0x00)) },
                { assertFalse(ByteClass.isUtf8DomainByDefault(0x7F)) },
                { assertTrue(ByteClass.isUtf8DomainByDefault(0x80)) },
                { assertTrue(ByteClass.isUtf8DomainByDefault(0xFF)) },
            )
        }
    }

    // ----- Input validation -------------------------------------------------

    @Nested
    @DisplayName("input validation")
    inner class InputValidation {
        @Test
        fun `minimum and maximum byte values are accepted`() {
            assertAll(
                { assertDoesNotThrow { ByteClass.classify(0) } },
                { assertDoesNotThrow { ByteClass.classify(255) } },
                { assertDoesNotThrow { ByteClass.isAsciiDomain(0) } },
                { assertDoesNotThrow { ByteClass.isAsciiDomain(255) } },
                { assertDoesNotThrow { ByteClass.isUtf8DomainByDefault(0) } },
                { assertDoesNotThrow { ByteClass.isUtf8DomainByDefault(255) } },
            )
        }

        @Test
        fun `negative values are rejected by all public helpers`() {
            assertRejectsByteValue(-1)
            assertRejectsByteValue(Int.MIN_VALUE)
        }

        @Test
        fun `values above unsigned byte range are rejected by all public helpers`() {
            assertRejectsByteValue(256)
            assertRejectsByteValue(Int.MAX_VALUE)
        }
    }
}
