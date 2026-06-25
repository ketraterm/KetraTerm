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

class TerminalKeyEventTest {
    @Test
    fun `creates non-printable key event`() {
        val event =
            TerminalKeyEvent.key(
                key = TerminalKey.UP,
                modifiers = TerminalModifiers.SHIFT,
            )

        assertAll(
            { assertEquals(TerminalKey.UP, event.key) },
            { assertEquals(TerminalKeyEvent.NO_CODEPOINT, event.codepoint) },
            { assertEquals(TerminalModifiers.SHIFT, event.modifiers) },
        )
    }

    @Test
    fun `creates printable codepoint event`() {
        val event =
            TerminalKeyEvent.codepoint(
                codepoint = 'a'.code,
                modifiers = TerminalModifiers.ALT,
            )

        assertAll(
            { assertNull(event.key) },
            { assertEquals('a'.code, event.codepoint) },
            { assertEquals(TerminalModifiers.ALT, event.modifiers) },
        )
    }

    @Test
    fun `rejects invalid modifier bits`() {
        assertThrows(IllegalArgumentException::class.java) {
            TerminalKeyEvent.key(
                key = TerminalKey.ENTER,
                modifiers = 1 shl 12,
            )
        }
    }

    @Test
    fun `rejects neither key nor codepoint`() {
        assertThrows(IllegalArgumentException::class.java) {
            TerminalKeyEvent()
        }
    }

    @Test
    fun `rejects both key and codepoint`() {
        assertThrows(IllegalArgumentException::class.java) {
            TerminalKeyEvent(
                key = TerminalKey.ENTER,
                codepoint = 'x'.code,
            )
        }
    }

    @Test
    fun `rejects surrogate codepoint`() {
        assertThrows(IllegalArgumentException::class.java) {
            TerminalKeyEvent.codepoint(0xd800)
        }
    }

    @Test
    fun `rejects codepoint above Unicode scalar range`() {
        assertThrows(IllegalArgumentException::class.java) {
            TerminalKeyEvent.codepoint(0x110000)
        }
    }

    @Test
    fun `rejects C0 control codepoint`() {
        assertThrows(IllegalArgumentException::class.java) {
            TerminalKeyEvent.codepoint(0x001b)
        }
    }

    @Test
    fun `rejects DEL codepoint`() {
        assertThrows(IllegalArgumentException::class.java) {
            TerminalKeyEvent.codepoint(0x007f)
        }
    }

    @Test
    fun `accepts ASCII printable codepoint`() {
        val event = TerminalKeyEvent.codepoint('~'.code)

        assertEquals('~'.code, event.codepoint)
    }

    @Test
    fun `accepts emoji scalar codepoint`() {
        val event = TerminalKeyEvent.codepoint(0x1f600)

        assertEquals(0x1f600, event.codepoint)
    }
}
