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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("CsiCommand")
class CsiCommandTest {
    private val allCommands =
        listOf(
            CsiCommand.UNKNOWN,
            CsiCommand.CUU,
            CsiCommand.CUD,
            CsiCommand.CUF,
            CsiCommand.CUB,
            CsiCommand.CNL,
            CsiCommand.CPL,
            CsiCommand.CHA,
            CsiCommand.CUP,
            CsiCommand.VPA,
            CsiCommand.ED,
            CsiCommand.EL,
            CsiCommand.IL,
            CsiCommand.DL,
            CsiCommand.ICH,
            CsiCommand.DCH,
            CsiCommand.ECH,
            CsiCommand.SU,
            CsiCommand.SD,
            CsiCommand.SM_ANSI,
            CsiCommand.RM_ANSI,
            CsiCommand.SM_DEC,
            CsiCommand.RM_DEC,
            CsiCommand.DECSTR,
            CsiCommand.SGR,
            CsiCommand.CHT,
            CsiCommand.CBT,
            CsiCommand.TBC,
            CsiCommand.DECSTBM,
            CsiCommand.DECSLRM,
            CsiCommand.DECSED,
            CsiCommand.DECSEL,
            CsiCommand.DECSCA,
            CsiCommand.DA_PRIMARY,
            CsiCommand.DA_SECONDARY,
            CsiCommand.DA_TERTIARY,
            CsiCommand.DSR,
            CsiCommand.DSR_DEC,
            CsiCommand.WINDOW_OP,
            CsiCommand.DECSCUSR,
            CsiCommand.XTFMTKEYS,
            CsiCommand.XTMODKEYS,
        )

    @Nested
    @DisplayName("constants")
    inner class Constants {
        @Test
        fun `command ids are contiguous and stable`() {
            assertEquals((0..41).toList(), allCommands)
        }

        @Test
        fun `UNKNOWN remains the zero command`() {
            assertEquals(0, CsiCommand.UNKNOWN)
        }

        @Test
        fun `command ids are unique`() {
            assertTrue(allCommands.size == allCommands.toSet().size)
        }
    }
}
