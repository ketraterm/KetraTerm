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
package io.github.jvterm.render.api

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TerminalRenderCellFlagsTest {
    @Test
    fun `valid flag combinations match public cell encoding contract`() {
        assertAll(
            { assertTrue(TerminalRenderCellFlags.isValidCombination(TerminalRenderCellFlags.EMPTY)) },
            { assertTrue(TerminalRenderCellFlags.isValidCombination(TerminalRenderCellFlags.CODEPOINT)) },
            {
                assertTrue(
                    TerminalRenderCellFlags.isValidCombination(
                        TerminalRenderCellFlags.CODEPOINT or TerminalRenderCellFlags.WIDE_LEADING,
                    ),
                )
            },
            { assertTrue(TerminalRenderCellFlags.isValidCombination(TerminalRenderCellFlags.CLUSTER)) },
            {
                assertTrue(
                    TerminalRenderCellFlags.isValidCombination(
                        TerminalRenderCellFlags.CLUSTER or TerminalRenderCellFlags.WIDE_LEADING,
                    ),
                )
            },
            { assertTrue(TerminalRenderCellFlags.isValidCombination(TerminalRenderCellFlags.WIDE_TRAILING)) },
        )
    }

    @Test
    fun `invalid flag combinations are rejected`() {
        assertAll(
            {
                assertFalse(
                    TerminalRenderCellFlags.isValidCombination(
                        TerminalRenderCellFlags.EMPTY or TerminalRenderCellFlags.CODEPOINT,
                    ),
                )
            },
            {
                assertFalse(
                    TerminalRenderCellFlags.isValidCombination(
                        TerminalRenderCellFlags.EMPTY or TerminalRenderCellFlags.CLUSTER,
                    ),
                )
            },
            {
                assertFalse(
                    TerminalRenderCellFlags.isValidCombination(
                        TerminalRenderCellFlags.CODEPOINT or TerminalRenderCellFlags.CLUSTER,
                    ),
                )
            },
            {
                assertFalse(
                    TerminalRenderCellFlags.isValidCombination(
                        TerminalRenderCellFlags.WIDE_TRAILING or TerminalRenderCellFlags.CODEPOINT,
                    ),
                )
            },
            {
                assertFalse(
                    TerminalRenderCellFlags.isValidCombination(
                        TerminalRenderCellFlags.WIDE_TRAILING or TerminalRenderCellFlags.CLUSTER,
                    ),
                )
            },
            {
                assertFalse(
                    TerminalRenderCellFlags.isValidCombination(
                        TerminalRenderCellFlags.WIDE_TRAILING or TerminalRenderCellFlags.WIDE_LEADING,
                    ),
                )
            },
            { assertFalse(TerminalRenderCellFlags.isValidCombination(0)) },
            { assertFalse(TerminalRenderCellFlags.isValidCombination(1 shl 12)) },
        )
    }
}
