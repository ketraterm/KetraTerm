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
package com.gagik.terminal.protocol.keyboard

import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TerminalKeyboardProtocolTest {
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

    @Test
    fun `kitty keyboard progressive flags match protocol bit values`() {
        assertAll(
            { assertEquals(1, KittyKeyboardProgressiveFlag.DISAMBIGUATE_ESCAPE_CODES) },
            { assertEquals(2, KittyKeyboardProgressiveFlag.REPORT_EVENT_TYPES) },
            { assertEquals(4, KittyKeyboardProgressiveFlag.REPORT_ALTERNATE_KEYS) },
            { assertEquals(8, KittyKeyboardProgressiveFlag.REPORT_ALL_KEYS_AS_ESCAPE_CODES) },
            { assertEquals(16, KittyKeyboardProgressiveFlag.REPORT_ASSOCIATED_TEXT) },
            { assertEquals(31, KittyKeyboardProgressiveFlag.SUPPORTED_MASK) },
        )
    }

    @Test
    fun `kitty keyboard flag application modes match protocol parameter values`() {
        assertAll(
            { assertEquals(1, KittyKeyboardFlagApplicationMode.REPLACE) },
            { assertEquals(2, KittyKeyboardFlagApplicationMode.SET) },
            { assertEquals(3, KittyKeyboardFlagApplicationMode.CLEAR) },
        )
    }

    @Test
    fun `kitty keyboard event types match protocol subparameter values`() {
        assertAll(
            { assertEquals(1, KittyKeyboardEventType.PRESS) },
            { assertEquals(2, KittyKeyboardEventType.REPEAT) },
            { assertEquals(3, KittyKeyboardEventType.RELEASE) },
        )
    }

    @Test
    fun `kitty keyboard first-slice functional key codes match protocol values`() {
        assertAll(
            { assertEquals(0x09, KittyKeyboardFunctionalKeyCode.TAB) },
            { assertEquals(0x0d, KittyKeyboardFunctionalKeyCode.ENTER) },
            { assertEquals(0x1b, KittyKeyboardFunctionalKeyCode.ESCAPE) },
            { assertEquals(0x7f, KittyKeyboardFunctionalKeyCode.BACKSPACE) },
        )
    }
}
