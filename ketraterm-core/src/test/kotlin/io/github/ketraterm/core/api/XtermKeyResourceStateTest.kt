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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class XtermKeyResourceStateTest {
    private val resources = intArrayOf(0, 1, 2, 3, 4, 6, 7)

    @ParameterizedTest
    @CsvSource("0,0", "1,2", "2,2", "3,0", "4,0", "6,0", "7,0")
    fun `zero word and explicit defaults agree`(
        resource: Int,
        defaultLevel: Int,
    ) {
        assertEquals(defaultLevel, TerminalInputState.keyModifierOption(0L, resource))
        assertEquals(0, TerminalInputState.keyFormatOption(0L, resource))
        assertEquals(0L, TerminalInputState.withKeyModifierOption(0L, resource, defaultLevel))
        assertEquals(0L, TerminalInputState.withKeyFormatOption(0L, resource, 0))
    }

    @ParameterizedTest
    @CsvSource("0,15", "1,4", "2,4", "3,4", "4,3", "6,4", "7,4")
    fun `public modifier packing round trips every value and preserves other fields`(
        resource: Int,
        maximum: Int,
    ) {
        val before = independentlyConfiguredBits()
        val originalLevel = TerminalInputState.keyModifierOption(before, resource)
        for (value in -1..maximum) {
            val updated = TerminalInputState.withKeyModifierOption(before, resource, value)
            assertEquals(value, TerminalInputState.keyModifierOption(updated, resource))
            for (other in resources) {
                if (other != resource) {
                    assertEquals(TerminalInputState.keyModifierOption(before, other), TerminalInputState.keyModifierOption(updated, other))
                }
                assertEquals(TerminalInputState.keyFormatOption(before, other), TerminalInputState.keyFormatOption(updated, other))
            }
            assertEquals(before, TerminalInputState.withKeyModifierOption(updated, resource, originalLevel))
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 2, 3, 4, 6, 7])
    fun `public format packing replaces both values and preserves other fields`(resource: Int) {
        val before = independentlyConfiguredBits()
        for (value in 0..1) {
            val updated = TerminalInputState.withKeyFormatOption(before, resource, value)
            assertEquals(value, TerminalInputState.keyFormatOption(updated, resource))
            for (other in resources) {
                assertEquals(TerminalInputState.keyModifierOption(before, other), TerminalInputState.keyModifierOption(updated, other))
                if (other != resource) {
                    assertEquals(TerminalInputState.keyFormatOption(before, other), TerminalInputState.keyFormatOption(updated, other))
                }
            }
            assertEquals(before, TerminalInputState.withKeyFormatOption(updated, resource, 1))
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [Int.MIN_VALUE, -1, 5, 8, Int.MAX_VALUE])
    fun `public packing rejects unsupported resources`(resource: Int) {
        assertThrows(IllegalArgumentException::class.java) { TerminalInputState.withKeyModifierOption(0L, resource, 0) }
        assertThrows(IllegalArgumentException::class.java) { TerminalInputState.withKeyFormatOption(0L, resource, 0) }
    }

    @ParameterizedTest
    @CsvSource("0,15", "1,4", "2,4", "3,4", "4,3", "6,4", "7,4")
    fun `public packing rejects out of range values rather than truncating them`(
        resource: Int,
        maximum: Int,
    ) {
        for (value in intArrayOf(Int.MIN_VALUE, -2, maximum + 1, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { TerminalInputState.withKeyModifierOption(0L, resource, value) }
        }
        for (value in intArrayOf(Int.MIN_VALUE, -1, 2, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { TerminalInputState.withKeyFormatOption(0L, resource, value) }
        }
    }

    @Test
    fun `independently packed resources match stock core state`() {
        val terminal = TerminalBuffers.create(10, 3)
        var bits = terminal.getInputModeBits()
        for (resource in resources) {
            val maximum =
                if (resource == 0) {
                    15
                } else if (resource == 4) {
                    3
                } else {
                    4
                }
            for (value in -1..maximum) {
                bits = TerminalInputState.withKeyModifierOption(bits, resource, value)
                terminal.setKeyModifierOption(resource, value)
                assertEquals(terminal.getInputModeBits(), bits)
            }
            for (value in 0..1) {
                bits = TerminalInputState.withKeyFormatOption(bits, resource, value)
                terminal.setKeyFormatOption(resource, value)
                assertEquals(terminal.getInputModeBits(), bits)
            }
        }
    }

    private fun independentlyConfiguredBits(): Long {
        // Preserve unassigned bits as well as unrelated published input modes.
        var bits = Long.MIN_VALUE or (1L shl 19) or TerminalModeBits.APPLICATION_CURSOR_KEYS or TerminalModeBits.BRACKETED_PASTE
        bits = TerminalModeBits.withPackedValue(bits, TerminalModeBits.MOUSE_TRACKING_MASK, TerminalModeBits.MOUSE_TRACKING_SHIFT, 3)
        bits =
            TerminalModeBits.withPackedValue(
                bits,
                TerminalModeBits.KITTY_KEYBOARD_FLAGS_MASK,
                TerminalModeBits.KITTY_KEYBOARD_FLAGS_SHIFT,
                9,
            )
        for (resource in resources) {
            bits = TerminalInputState.withKeyModifierOption(bits, resource, -1)
            bits = TerminalInputState.withKeyFormatOption(bits, resource, 1)
        }
        return bits
    }

    @Test
    fun `all resources have independent state and family resets preserve other modes`() {
        val terminal = TerminalBuffers.create(10, 3)
        terminal.setApplicationCursorKeys(true)
        terminal.setKittyKeyboardFlags(9)
        val initial = terminal.getInputModeBits()
        for (resource in resources) {
            assertEquals(if (resource == 1 || resource == 2) 2 else 0, TerminalInputState.keyModifierOption(initial, resource))
            assertEquals(0, TerminalInputState.keyFormatOption(initial, resource))
            terminal.setKeyModifierOption(
                resource,
                if (resource == 0) {
                    15
                } else if (resource == 4) {
                    3
                } else {
                    4
                },
            )
            terminal.setKeyFormatOption(resource, 1)
        }
        val configured = terminal.getInputModeBits()
        for (resource in resources) {
            assertEquals(
                if (resource == 0) {
                    15
                } else if (resource == 4) {
                    3
                } else {
                    4
                },
                TerminalInputState.keyModifierOption(configured, resource),
            )
            assertEquals(1, TerminalInputState.keyFormatOption(configured, resource))
            terminal.setKeyModifierOption(resource, -1)
            assertEquals(-1, TerminalInputState.keyModifierOption(terminal.getInputModeBits(), resource))
        }
        terminal.resetKeyModifierOptions()
        for (resource in resources) {
            assertEquals(
                TerminalInputState.keyModifierOption(initial, resource),
                TerminalInputState.keyModifierOption(terminal.getInputModeBits(), resource),
            )
            assertEquals(1, TerminalInputState.keyFormatOption(terminal.getInputModeBits(), resource))
        }
        terminal.resetKeyFormatOptions()
        assertEquals(initial, terminal.getInputModeBits())
    }

    @Test
    fun `single resource reset preserves every other resource and terminal reset restores defaults`() {
        val terminal = TerminalBuffers.create(10, 3)
        val initial = terminal.getInputModeBits()
        for (resource in resources) {
            terminal.setKeyModifierOption(resource, -1)
            terminal.setKeyFormatOption(resource, 1)
        }
        terminal.resetKeyModifierOption(1)
        terminal.resetKeyFormatOption(6)
        val bits = terminal.getInputModeBits()
        for (resource in resources) {
            assertEquals(if (resource == 1) 2 else -1, TerminalInputState.keyModifierOption(bits, resource))
            assertEquals(if (resource == 6) 0 else 1, TerminalInputState.keyFormatOption(bits, resource))
        }
        terminal.softReset()
        assertEquals(initial, terminal.getInputModeBits())
        for (resource in resources) {
            terminal.setKeyModifierOption(resource, -1)
            terminal.setKeyFormatOption(resource, 1)
        }
        terminal.reset()
        assertEquals(initial, terminal.getInputModeBits())
    }

    @Test
    fun `invalid resource values fail atomically and unsupported queries stay silent`() {
        val terminal = TerminalBuffers.create(10, 3)
        val before = terminal.getInputModeBits()
        for (resource in intArrayOf(-1, 5, 8, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { terminal.setKeyModifierOption(resource, 0) }
            assertThrows(IllegalArgumentException::class.java) { terminal.setKeyFormatOption(resource, 0) }
            terminal.resetKeyModifierOption(resource)
            terminal.resetKeyFormatOption(resource)
            terminal.requestKeyModifierOption(resource)
            terminal.requestKeyFormatOption(resource)
            assertEquals(-2, TerminalInputState.keyModifierOption(before, resource))
            assertEquals(-2, TerminalInputState.keyFormatOption(before, resource))
        }
        for (resource in resources) {
            assertThrows(IllegalArgumentException::class.java) { terminal.setKeyModifierOption(resource, -2) }
            assertThrows(IllegalArgumentException::class.java) { terminal.setKeyModifierOption(resource, 16) }
            assertThrows(IllegalArgumentException::class.java) { terminal.setKeyFormatOption(resource, 2) }
        }
        assertEquals(before, terminal.getInputModeBits())
        assertEquals(0, terminal.pendingResponseBytes)
    }

    @Test
    fun `ordinary key convenience setters share generic validation and state`() {
        val terminal = TerminalBuffers.create(10, 3)
        for (level in -1..3) {
            terminal.setModifyOtherKeysMode(level)
            assertEquals(level, TerminalInputState.keyModifierOption(terminal.getInputModeBits(), 4))
            terminal.setKeyModifierOption(4, level)
            assertEquals(level, terminal.getModeSnapshot().modifyOtherKeysMode)
        }
        for (format in 0..1) {
            terminal.setFormatOtherKeysMode(format)
            assertEquals(format, TerminalInputState.keyFormatOption(terminal.getInputModeBits(), 4))
            terminal.setKeyFormatOption(4, format)
            assertEquals(format, terminal.getModeSnapshot().formatOtherKeysMode)
        }
        val before = terminal.getInputModeBits()
        for (invalid in intArrayOf(-2, 4, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { terminal.setModifyOtherKeysMode(invalid) }
        }
        for (invalid in intArrayOf(-1, 2, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { terminal.setFormatOtherKeysMode(invalid) }
        }
        assertEquals(before, terminal.getInputModeBits())
    }
}
