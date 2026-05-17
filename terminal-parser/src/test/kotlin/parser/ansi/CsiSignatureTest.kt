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
package com.gagik.parser.ansi

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("CsiSignature")
class CsiSignatureTest {
    @Nested
    @DisplayName("encoding")
    inner class Encoding {
        @Test
        fun `packs final private marker intermediates and intermediate count into stable bit fields`() {
            val signature =
                CsiSignature.encode(
                    finalByte = 0x70,
                    privateMarker = 0x3F,
                    intermediates = 0x2421,
                    intermediateCount = 2,
                )

            assertEquals(0x0002_0000_2421_3F70L, signature)
        }

        @Test
        fun `masks every encoded component to its assigned width`() {
            val signature =
                CsiSignature.encode(
                    finalByte = 0x170,
                    privateMarker = 0x13F,
                    intermediates = -1,
                    intermediateCount = 0x1F,
                )

            assertEquals(0x000F_FFFF_FFFF_3F70L, signature)
        }
    }

    @Nested
    @DisplayName("routing distinctions")
    inner class RoutingDistinctions {
        @Test
        fun `CSI p and CSI bang p are distinct signatures`() {
            val plain = CsiSignature.encode('p'.code, 0, 0, 0)
            val decstr = CsiSignature.encode('p'.code, 0, '!'.code, 1)

            assertNotEquals(plain, decstr)
            assertEquals(CsiCommand.UNKNOWN, GeneratedCsiDispatchTable.lookup(plain))
            assertEquals(CsiCommand.DECSTR, GeneratedCsiDispatchTable.lookup(decstr))
        }

        @Test
        fun `CSI h CSI question h and CSI bang h do not collide`() {
            val ansi = CsiSignature.encode('h'.code, 0, 0, 0)
            val dec = CsiSignature.encode('h'.code, '?'.code, 0, 0)
            val intermediate = CsiSignature.encode('h'.code, 0, '!'.code, 1)

            assertEquals(CsiCommand.SM_ANSI, GeneratedCsiDispatchTable.lookup(ansi))
            assertEquals(CsiCommand.SM_DEC, GeneratedCsiDispatchTable.lookup(dec))
            assertEquals(CsiCommand.UNKNOWN, GeneratedCsiDispatchTable.lookup(intermediate))
        }
    }
}
