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

class XtermKeyResourceStateTest {
    private val resources = intArrayOf(0, 1, 2, 3, 4, 6, 7)

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
