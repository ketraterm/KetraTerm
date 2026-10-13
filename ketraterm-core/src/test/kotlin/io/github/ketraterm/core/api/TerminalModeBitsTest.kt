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
package io.github.ketraterm.core.api

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.protocol.MouseEncodingMode
import io.github.ketraterm.protocol.MouseTrackingMode
import io.github.ketraterm.protocol.keyboard.KittyKeyboardProgressiveFlag
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TerminalModeBitsTest {
    @Test
    fun `mouse routing defaults preserve zero-word consumers and do not corrupt existing fields`() {
        assertTrue(TerminalInputState.isAlternateScrollEnabled(0L))
        assertFalse(TerminalInputState.isShiftMouseCaptureEnabled(0L))
        val terminal = TerminalBuffers.create(4, 2)
        terminal.setMouseTrackingMode(MouseTrackingMode.ANY_EVENT)
        terminal.setMouseEncodingMode(MouseEncodingMode.SGR_PIXELS)
        terminal.setKittyKeyboardFlags(31)
        terminal.setModifyOtherKeysMode(3)
        terminal.setFormatOtherKeysMode(1)
        val before = terminal.getInputModeBits()
        terminal.setAlternateScrollEnabled(false)
        terminal.setShiftMouseCaptureEnabled(true)
        val bits = terminal.getInputModeBits()
        assertTrue(bits < 0, "Capture uses the sign bit without changing packed fields")
        assertEquals(
            before,
            bits and (TerminalModeBits.ALTERNATE_SCROLL_DISABLED or TerminalModeBits.SHIFT_MOUSE_CAPTURE).inv(),
        )
        assertFalse(TerminalInputState.isAlternateScrollEnabled(bits))
        assertTrue(TerminalInputState.isShiftMouseCaptureEnabled(bits))
        assertEquals(MouseTrackingMode.ANY_EVENT.ordinal, TerminalInputState.mouseTrackingMode(bits))
        assertEquals(MouseEncodingMode.SGR_PIXELS.ordinal, TerminalInputState.mouseEncodingMode(bits))
        assertEquals(31, TerminalInputState.kittyKeyboardFlags(bits))
        assertEquals(3, TerminalInputState.modifyOtherKeysMode(bits))
        assertEquals(1, TerminalInputState.formatOtherKeysMode(bits))
        terminal.setAlternateScrollEnabled(true)
        terminal.setShiftMouseCaptureEnabled(false)
        assertEquals(before, terminal.getInputModeBits())
    }

    @Test
    fun `mouse routing preferences survive soft reset and RIS restores defaults`() {
        val terminal = TerminalBuffers.create(4, 2)
        terminal.setAlternateScrollEnabled(false)
        terminal.setShiftMouseCaptureEnabled(true)
        terminal.softReset()
        assertFalse(TerminalInputState.isAlternateScrollEnabled(terminal.getInputModeBits()))
        assertTrue(TerminalInputState.isShiftMouseCaptureEnabled(terminal.getInputModeBits()))
        terminal.reset()
        assertTrue(TerminalInputState.isAlternateScrollEnabled(terminal.getInputModeBits()))
        assertFalse(TerminalInputState.isShiftMouseCaptureEnabled(terminal.getInputModeBits()))
    }

    @Test
    fun `published mouse numeric meanings remain compatible with existing packed readers`() {
        val terminal = TerminalBuffers.create(2, 2)
        val tracking = listOf("OFF", "X10", "NORMAL", "BUTTON_EVENT", "ANY_EVENT")
        val encoding = listOf("DEFAULT", "UTF8", "SGR", "URXVT", "SGR_PIXELS")
        for ((number, name) in tracking.withIndex()) {
            val mode = MouseTrackingMode.valueOf(name)
            assertEquals(number, mode.ordinal)
            terminal.setMouseTrackingMode(mode)
            assertEquals(number.toLong(), (terminal.getInputModeBits() ushr 20) and 15L)
            assertEquals(number, TerminalInputState.mouseTrackingMode(number.toLong() shl 20))
        }
        for ((number, name) in encoding.withIndex()) {
            val mode = MouseEncodingMode.valueOf(name)
            assertEquals(number, mode.ordinal)
            terminal.setMouseEncodingMode(mode)
            assertEquals(number.toLong(), (terminal.getInputModeBits() ushr 24) and 7L)
            assertEquals(number, TerminalInputState.mouseEncodingMode(number.toLong() shl 24))
        }
    }

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
                value = 8,
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
