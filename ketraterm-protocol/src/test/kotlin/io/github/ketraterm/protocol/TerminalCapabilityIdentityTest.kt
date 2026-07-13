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
package io.github.ketraterm.protocol

import io.github.ketraterm.protocol.keyboard.KittyKeyboardProgressiveFlag
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("TerminalCapabilityIdentity")
class TerminalCapabilityIdentityTest {
    @Test
    fun `advertises conservative terminal identity values`() {
        assertAll(
            { assertEquals("xterm-256color", TerminalCapabilityIdentity.TERM_NAME) },
            { assertEquals("truecolor", TerminalCapabilityIdentity.COLOR_TERM_TRUECOLOR) },
            { assertEquals(1, TerminalCapabilityIdentity.PRIMARY_DA_TERMINAL_CLASS) },
            { assertEquals(2, TerminalCapabilityIdentity.PRIMARY_DA_ADVANCED_VIDEO) },
            { assertEquals(0, TerminalCapabilityIdentity.SECONDARY_DA_TERMINAL_ID) },
            { assertEquals(0, TerminalCapabilityIdentity.SECONDARY_DA_VERSION) },
            { assertEquals(0, TerminalCapabilityIdentity.SECONDARY_DA_OPTIONS) },
            { assertFalse(TerminalCapabilityIdentity.TERTIARY_DA_SUPPORTED) },
        )
    }

    @Test
    fun `advertises only implemented query capabilities`() {
        assertAll(
            { assertEquals("256", TerminalCapabilityIdentity.TERMINFO_COLOR_COUNT) },
            { assertTrue(TerminalCapabilityIdentity.TERMINFO_TRUECOLOR_SUPPORTED) },
            {
                assertEquals(
                    KittyKeyboardProgressiveFlag.DEFAULT_HOST_SUPPORTED_MASK,
                    TerminalCapabilityIdentity.KITTY_KEYBOARD_SUPPORTED_FLAGS,
                )
            },
            { assertTrue(TerminalCapabilityIdentity.KITTY_KEYBOARD_QUERY_RESPONSE_SUPPORTED) },
        )
    }
}
