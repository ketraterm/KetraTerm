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
package com.gagik.terminal.protocol

import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TerminalProtocolModesTest {
    @Test
    fun `control codes expose ANSI wire byte values`() {
        assertAll(
            { assertEquals(0x00, ControlCode.NUL) },
            { assertEquals(0x07, ControlCode.BEL) },
            { assertEquals(0x08, ControlCode.BS) },
            { assertEquals(0x09, ControlCode.HT) },
            { assertEquals(0x0A, ControlCode.LF) },
            { assertEquals(0x0B, ControlCode.VT) },
            { assertEquals(0x0C, ControlCode.FF) },
            { assertEquals(0x0D, ControlCode.CR) },
            { assertEquals(0x18, ControlCode.CAN) },
            { assertEquals(0x1A, ControlCode.SUB) },
            { assertEquals(0x1B, ControlCode.ESC) },
            { assertEquals(0x7F, ControlCode.DEL) },
            { assertEquals(0x99, ControlCode.SGCI) },
            { assertEquals(0x9B, ControlCode.CSI) },
            { assertEquals(0x9D, ControlCode.OSC) },
        )
    }

    @Test
    fun `ANSI mode ids match CSI SM and RM protocol values`() {
        assertAll(
            { assertEquals(4, AnsiMode.INSERT) },
            { assertEquals(20, AnsiMode.NEW_LINE) },
        )
    }

    @Test
    fun `DEC private mode ids match common terminal protocol values`() {
        assertAll(
            { assertEquals(1, DecPrivateMode.APPLICATION_CURSOR_KEYS) },
            { assertEquals(3, DecPrivateMode.DECCOLM) },
            { assertEquals(5, DecPrivateMode.REVERSE_VIDEO) },
            { assertEquals(6, DecPrivateMode.ORIGIN) },
            { assertEquals(7, DecPrivateMode.AUTO_WRAP) },
            { assertEquals(12, DecPrivateMode.CURSOR_BLINK) },
            { assertEquals(25, DecPrivateMode.CURSOR_VISIBLE) },
            { assertEquals(66, DecPrivateMode.APPLICATION_KEYPAD) },
            { assertEquals(69, DecPrivateMode.LEFT_RIGHT_MARGIN) },
            { assertEquals(47, DecPrivateMode.ALT_SCREEN) },
            { assertEquals(1047, DecPrivateMode.ALT_SCREEN_BUFFER) },
            { assertEquals(1048, DecPrivateMode.SAVE_RESTORE_CURSOR) },
            { assertEquals(1049, DecPrivateMode.ALT_SCREEN_SAVE_CURSOR) },
            { assertEquals(9, DecPrivateMode.MOUSE_X10) },
            { assertEquals(1000, DecPrivateMode.MOUSE_NORMAL) },
            { assertEquals(1002, DecPrivateMode.MOUSE_BUTTON_EVENT) },
            { assertEquals(1003, DecPrivateMode.MOUSE_ANY_EVENT) },
            { assertEquals(1004, DecPrivateMode.FOCUS_REPORTING) },
            { assertEquals(1005, DecPrivateMode.MOUSE_UTF8) },
            { assertEquals(1006, DecPrivateMode.MOUSE_SGR) },
            { assertEquals(1015, DecPrivateMode.MOUSE_URXVT) },
            { assertEquals(2004, DecPrivateMode.BRACKETED_PASTE) },
            { assertEquals(2026, DecPrivateMode.SYNCHRONIZED_OUTPUT) },
        )
    }

    @Test
    fun `mouse modes expose input and core shared vocabulary`() {
        assertAll(
            { assertEquals("OFF", MouseTrackingMode.OFF.name) },
            { assertEquals("SGR", MouseEncodingMode.SGR.name) },
            { assertEquals("URXVT", MouseEncodingMode.URXVT.name) },
        )
    }

    @Test
    fun `modifyOtherKeys modes expose input and core shared vocabulary`() {
        assertAll(
            { assertEquals(0, ModifyOtherKeysMode.DISABLED) },
            { assertEquals(1, ModifyOtherKeysMode.MODE_1) },
            { assertEquals(2, ModifyOtherKeysMode.MODE_2) },
            { assertEquals(3, ModifyOtherKeysMode.MODE_3) },
        )
    }

    @Test
    fun `formatOtherKeys modes expose input and core shared vocabulary`() {
        assertAll(
            { assertEquals(0, FormatOtherKeysMode.DEFAULT) },
            { assertEquals(1, FormatOtherKeysMode.CSI_U) },
        )
    }

    @Test
    fun `xterm key option resource ids match control sequence values`() {
        assertAll(
            { assertEquals(4, XtermKeyModifierResource.MODIFY_OTHER_KEYS) },
            { assertEquals(4, XtermKeyFormatResource.FORMAT_OTHER_KEYS) },
        )
    }
}
