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
package io.github.jvterm.core.api

import io.github.jvterm.protocol.keyboard.KittyKeyboardProgressiveFlag
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TerminalModeBitsTest {
    @Test
    fun `tests boolean flags`() {
        val bits = TerminalModeBits.APPLICATION_CURSOR_KEYS or TerminalModeBits.BRACKETED_PASTE

        assertTrue(TerminalModeBits.hasFlag(bits, TerminalModeBits.APPLICATION_CURSOR_KEYS))
        assertTrue(TerminalModeBits.hasFlag(bits, TerminalModeBits.BRACKETED_PASTE))
        assertFalse(TerminalModeBits.hasFlag(bits, TerminalModeBits.APPLICATION_KEYPAD))
    }

    @Test
    fun `stores and extracts packed values`() {
        val bits =
            TerminalModeBits.withPackedValue(
                bits = 0L,
                mask = TerminalModeBits.MOUSE_TRACKING_MASK,
                shift = TerminalModeBits.MOUSE_TRACKING_SHIFT,
                value = 4,
            )

        assertEquals(4, TerminalModeBits.packedValue(bits, TerminalModeBits.MOUSE_TRACKING_MASK, TerminalModeBits.MOUSE_TRACKING_SHIFT))
    }

    @Test
    fun `rejects packed values outside mask`() {
        assertThrows(IllegalArgumentException::class.java) {
            TerminalModeBits.withPackedValue(
                bits = 0L,
                mask = TerminalModeBits.MOUSE_ENCODING_MASK,
                shift = TerminalModeBits.MOUSE_ENCODING_SHIFT,
                value = 4,
            )
        }
    }

    @Test
    fun `TerminalInputState helpers decode input mode bits`() {
        val bits =
            TerminalModeBits.APPLICATION_CURSOR_KEYS or
                TerminalModeBits.APPLICATION_KEYPAD or
                TerminalModeBits.NEW_LINE_MODE or
                TerminalModeBits.BRACKETED_PASTE or
                TerminalModeBits.FOCUS_REPORTING
        val withFormat =
            TerminalModeBits.withPackedValue(
                bits = bits,
                mask = TerminalModeBits.FORMAT_OTHER_KEYS_MASK,
                shift = TerminalModeBits.FORMAT_OTHER_KEYS_SHIFT,
                value = 1,
            )
        val withKittyFlags =
            TerminalModeBits.withPackedValue(
                bits = withFormat,
                mask = TerminalModeBits.KITTY_KEYBOARD_FLAGS_MASK,
                shift = TerminalModeBits.KITTY_KEYBOARD_FLAGS_SHIFT,
                value =
                    KittyKeyboardProgressiveFlag.DISAMBIGUATE_ESCAPE_CODES or
                        KittyKeyboardProgressiveFlag.REPORT_ALL_KEYS_AS_ESCAPE_CODES,
            )

        assertTrue(TerminalInputState.isApplicationCursorKeys(withKittyFlags))
        assertTrue(TerminalInputState.isApplicationKeypad(withKittyFlags))
        assertTrue(TerminalInputState.isNewLineMode(withKittyFlags))
        assertTrue(TerminalInputState.isBracketedPasteEnabled(withKittyFlags))
        assertTrue(TerminalInputState.isFocusReportingEnabled(withKittyFlags))
        assertEquals(1, TerminalInputState.formatOtherKeysMode(withKittyFlags))
        assertEquals(
            KittyKeyboardProgressiveFlag.DISAMBIGUATE_ESCAPE_CODES or
                KittyKeyboardProgressiveFlag.REPORT_ALL_KEYS_AS_ESCAPE_CODES,
            TerminalInputState.kittyKeyboardFlags(withKittyFlags),
        )
    }
}
