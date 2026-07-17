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
package io.github.ketraterm.input.event

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TerminalModifiersTest {
    @Test
    fun `recognizes individual modifier bits`() {
        val modifiers = TerminalModifiers.SHIFT or TerminalModifiers.CTRL

        assertAll(
            { assertTrue(TerminalModifiers.hasShift(modifiers)) },
            { assertFalse(TerminalModifiers.hasAlt(modifiers)) },
            { assertTrue(TerminalModifiers.hasCtrl(modifiers)) },
            { assertFalse(TerminalModifiers.hasMeta(modifiers)) },
            { assertFalse(TerminalModifiers.hasSuper(modifiers)) },
        )
    }

    @Test
    fun `validates modifier bitmask`() {
        assertAll(
            { assertTrue(TerminalModifiers.isValid(TerminalModifiers.NONE)) },
            { assertTrue(TerminalModifiers.isValid(TerminalModifiers.VALID_MASK)) },
            { assertFalse(TerminalModifiers.isValid(TerminalModifiers.VALID_MASK + 1)) },
            { assertFalse(TerminalModifiers.isValid(-1)) },
        )
    }

    @Test
    fun `converts modifier bits to CSI modifier parameter`() {
        assertAll(
            { assertEquals(1, TerminalModifiers.toCsiModifierParam(TerminalModifiers.NONE)) },
            { assertEquals(2, TerminalModifiers.toCsiModifierParam(TerminalModifiers.SHIFT)) },
            { assertEquals(3, TerminalModifiers.toCsiModifierParam(TerminalModifiers.ALT)) },
            {
                assertEquals(
                    4,
                    TerminalModifiers.toCsiModifierParam(
                        TerminalModifiers.SHIFT or TerminalModifiers.ALT,
                    ),
                )
            },
            { assertEquals(5, TerminalModifiers.toCsiModifierParam(TerminalModifiers.CTRL)) },
            { assertEquals(9, TerminalModifiers.toCsiModifierParam(TerminalModifiers.META)) },
            { assertEquals(9, TerminalModifiers.toCsiModifierParam(TerminalModifiers.SUPER)) },
            { assertEquals(1, TerminalModifiers.toCsiModifierParam(TerminalModifiers.CAPS_LOCK)) },
        )
    }

    @Test
    fun `converts all Kitty modifier bits without legacy collapsing`() {
        assertAll(
            { assertEquals(9, TerminalModifiers.toKittyCsiModifierParam(TerminalModifiers.SUPER)) },
            { assertEquals(17, TerminalModifiers.toKittyCsiModifierParam(TerminalModifiers.HYPER)) },
            { assertEquals(33, TerminalModifiers.toKittyCsiModifierParam(TerminalModifiers.META)) },
            { assertEquals(65, TerminalModifiers.toKittyCsiModifierParam(TerminalModifiers.CAPS_LOCK)) },
            { assertEquals(129, TerminalModifiers.toKittyCsiModifierParam(TerminalModifiers.NUM_LOCK)) },
        )
    }

    @Test
    fun `rejects invalid modifier bits for CSI parameter`() {
        assertThrows(IllegalArgumentException::class.java) {
            TerminalModifiers.toCsiModifierParam(1 shl 8)
        }
    }
}
