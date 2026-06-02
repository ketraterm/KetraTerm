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
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("GeneratedCsiDispatchTable")
class GeneratedCsiDispatchTableTest {
    @Nested
    @DisplayName("lookup")
    inner class Lookup {
        @Test
        fun `known plain CSI signatures route to command ids`() {
            assertEquals(CsiCommand.ICH, lookup('@'))
            assertEquals(CsiCommand.CUU, lookup('A'))
            assertEquals(CsiCommand.CUD, lookup('B'))
            assertEquals(CsiCommand.CUF, lookup('C'))
            assertEquals(CsiCommand.CUB, lookup('D'))
            assertEquals(CsiCommand.CNL, lookup('E'))
            assertEquals(CsiCommand.CPL, lookup('F'))
            assertEquals(CsiCommand.CHA, lookup('G'))
            assertEquals(CsiCommand.CUP, lookup('H'))
            assertEquals(CsiCommand.CHT, lookup('I'))
            assertEquals(CsiCommand.ED, lookup('J'))
            assertEquals(CsiCommand.EL, lookup('K'))
            assertEquals(CsiCommand.IL, lookup('L'))
            assertEquals(CsiCommand.DL, lookup('M'))
            assertEquals(CsiCommand.DCH, lookup('P'))
            assertEquals(CsiCommand.SU, lookup('S'))
            assertEquals(CsiCommand.SD, lookup('T'))
            assertEquals(CsiCommand.ECH, lookup('X'))
            assertEquals(CsiCommand.CBT, lookup('Z'))
            assertEquals(CsiCommand.DA_PRIMARY, lookup('c'))
            assertEquals(CsiCommand.VPA, lookup('d'))
            assertEquals(CsiCommand.CUP, lookup('f'))
            assertEquals(CsiCommand.TBC, lookup('g'))
            assertEquals(CsiCommand.SM_ANSI, lookup('h'))
            assertEquals(CsiCommand.RM_ANSI, lookup('l'))
            assertEquals(CsiCommand.SGR, lookup('m'))
            assertEquals(CsiCommand.DSR, lookup('n'))
            assertEquals(CsiCommand.DECSTBM, lookup('r'))
            assertEquals(CsiCommand.DECSLRM, lookup('s'))
            assertEquals(CsiCommand.WINDOW_OP, lookup('t'))
        }

        @Test
        fun `DEC private mode signatures route separately from ANSI modes`() {
            assertEquals(CsiCommand.SM_DEC, lookup('h', privateMarker = '?'.code))
            assertEquals(CsiCommand.RM_DEC, lookup('l', privateMarker = '?'.code))
        }

        @Test
        fun `DEC private DSR routes separately from ANSI DSR`() {
            assertEquals(CsiCommand.DSR_DEC, lookup('n', privateMarker = '?'.code))
        }

        @Test
        fun `secondary and tertiary DA route by private marker`() {
            assertEquals(CsiCommand.DA_SECONDARY, lookup('c', privateMarker = '>'.code))
            assertEquals(CsiCommand.DA_TERTIARY, lookup('c', privateMarker = '='.code))
        }

        @Test
        fun `xterm key option controls route by greater-than private marker`() {
            assertEquals(CsiCommand.XTFMTKEYS, lookup('f', privateMarker = '>'.code))
            assertEquals(CsiCommand.XTMODKEYS, lookup('m', privateMarker = '>'.code))
        }

        @Test
        fun `DEC selective erase signatures route separately from normal erase`() {
            assertEquals(CsiCommand.DECSED, lookup('J', privateMarker = '?'.code))
            assertEquals(CsiCommand.DECSEL, lookup('K', privateMarker = '?'.code))
        }

        @Test
        fun `DECSTR routes only through CSI bang p`() {
            assertEquals(CsiCommand.DECSTR, lookup('p', intermediates = '!'.code, intermediateCount = 1))
            assertEquals(CsiCommand.UNKNOWN, lookup('p'))
            assertEquals(CsiCommand.UNKNOWN, lookup('p', intermediates = '"'.code, intermediateCount = 1))
        }

        @Test
        fun `DECSCA routes only through CSI double quote q`() {
            assertEquals(CsiCommand.DECSCA, lookup('q', intermediates = '"'.code, intermediateCount = 1))
            assertEquals(CsiCommand.UNKNOWN, lookup('q'))
            assertEquals(CsiCommand.UNKNOWN, lookup('q', intermediates = '!'.code, intermediateCount = 1))
        }

        @Test
        fun `DECSCUSR routes only through CSI space q`() {
            assertEquals(CsiCommand.DECSCUSR, lookup('q', intermediates = ' '.code, intermediateCount = 1))
            assertEquals(CsiCommand.UNKNOWN, lookup('q'))
            assertEquals(CsiCommand.UNKNOWN, lookup('q', intermediates = '!'.code, intermediateCount = 1))
        }

        @Test
        fun `unknown signatures return UNKNOWN`() {
            assertEquals(CsiCommand.UNKNOWN, lookup('m', privateMarker = '?'.code))
            assertEquals(CsiCommand.UNKNOWN, lookup('A', privateMarker = '?'.code))
            assertEquals(CsiCommand.UNKNOWN, lookup('A', intermediates = '!'.code, intermediateCount = 1))
        }
    }

    private fun lookup(
        finalByte: Char,
        privateMarker: Int = 0,
        intermediates: Int = 0,
        intermediateCount: Int = 0,
    ): Int =
        GeneratedCsiDispatchTable.lookup(
            CsiSignature.encode(
                finalByte = finalByte.code,
                privateMarker = privateMarker,
                intermediates = intermediates,
                intermediateCount = intermediateCount,
            ),
        )
}
