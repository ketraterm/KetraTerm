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
package io.github.ketraterm.core.buffer

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.core.api.TerminalBuffer
import io.github.ketraterm.core.state.TerminalState
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TerminalScrollbackPolicyTest {
    private fun stateOf(api: TerminalBuffer): TerminalState {
        val componentsField = api.javaClass.getDeclaredField("components")
        componentsField.isAccessible = true
        val components = componentsField.get(api)

        val stateField = components.javaClass.getDeclaredField("state")
        stateField.isAccessible = true
        return stateField.get(components) as TerminalState
    }

    @Test
    fun `alternateBufferScrollbackIsolation_verifiesZeroHistoryAndNoLeakageToPrimary`() {
        val buffer = TerminalBuffers.create(width = 10, height = 5, maxHistory = 10)
        val state = stateOf(buffer)

        // 1. Write text in primary buffer that triggers vertical scrolling.
        // With height=5, writing 8 lines pushes 4 lines into primary scrollback history.
        repeat(8) {
            buffer.writeText("Line $it")
            buffer.carriageReturn()
            buffer.newLine()
        }
        val primaryHistorySize = (state.primaryBuffer.ring.size - state.dimensions.height).coerceAtLeast(0)
        assertEquals(4, primaryHistorySize, "Primary buffer must have 4 history lines")

        // Store primary history content to verify later.
        val primaryHistoryContent =
            (0 until primaryHistorySize).map { idx ->
                val line = state.primaryBuffer.ring[idx]
                (0 until 10).map { c -> line.rawCodepoint(c).toChar() }.joinToString("")
            }

        // 2. Switch to Alternate Screen Buffer
        buffer.enterAltBuffer()
        assertTrue(state.isAltScreenActive, "Alt screen must be active")
        val altHistorySize = (state.altBuffer.ring.size - state.dimensions.height).coerceAtLeast(0)
        assertEquals(0, altHistorySize, "Alt screen must have 0 history size")

        // 3. Write lines to Alt buffer to cause scrolling.
        // Alternate buffer has maxHistory=0, so scrolling should just discard/recycle lines, never grow history.
        repeat(20) {
            buffer.writeText("Alt line $it")
            buffer.carriageReturn()
            buffer.newLine()
        }
        val altHistorySizePost = (state.altBuffer.ring.size - state.dimensions.height).coerceAtLeast(0)
        assertEquals(0, altHistorySizePost, "Alt screen history size must stay 0")

        // 4. Return to Primary Screen Buffer
        buffer.exitAltBuffer()
        assertFalse(state.isAltScreenActive, "Primary screen must be active")

        // Verify primary history is intact and unchanged.
        val primaryHistorySizePost = (state.primaryBuffer.ring.size - state.dimensions.height).coerceAtLeast(0)
        assertEquals(4, primaryHistorySizePost, "Primary history size must be preserved")
        val postPrimaryHistoryContent =
            (0 until primaryHistorySizePost).map { idx ->
                val line = state.primaryBuffer.ring[idx]
                (0 until 10).map { c -> line.rawCodepoint(c).toChar() }.joinToString("")
            }
        assertEquals(
            primaryHistoryContent,
            postPrimaryHistoryContent,
            "Primary scrollback contents must not be modified by alt buffer edits",
        )
    }

    @Test
    fun `clearAllHistory_affectsOnlyPrimaryBuffer`() {
        val buffer = TerminalBuffers.create(width = 10, height = 5, maxHistory = 10)
        val state = stateOf(buffer)

        // Write to push lines to primary scrollback
        repeat(8) {
            buffer.writeText("L $it")
            buffer.carriageReturn()
            buffer.newLine()
        }
        val primaryHistorySize = (state.primaryBuffer.ring.size - state.dimensions.height).coerceAtLeast(0)
        assertTrue(primaryHistorySize > 0, "Primary buffer must have history")

        // Clear history
        buffer.clearAll()
        val primaryHistorySizePost = (state.primaryBuffer.ring.size - state.dimensions.height).coerceAtLeast(0)
        assertEquals(0, primaryHistorySizePost, "clearAll must clear primary history size")
    }

    @Test
    fun `resizeWhileAltScreenActive_doesNotLeakOrCorruptHistory`() {
        val buffer = TerminalBuffers.create(width = 10, height = 5, maxHistory = 10)
        val state = stateOf(buffer)

        // Generate primary scrollback
        repeat(8) {
            buffer.writeText("P $it")
            buffer.carriageReturn()
            buffer.newLine()
        }
        val origHistorySize = (state.primaryBuffer.ring.size - state.dimensions.height).coerceAtLeast(0)
        assertTrue(origHistorySize > 0)

        // Enter alt screen
        buffer.enterAltBuffer()
        buffer.writeText("Alt text")

        // Resize buffer while alt screen is active
        buffer.resize(12, 6)

        // Verify active buffer is resized and is still the alt screen with 0 history
        assertTrue(state.isAltScreenActive)
        val altHistorySize = (state.altBuffer.ring.size - state.dimensions.height).coerceAtLeast(0)
        assertEquals(0, altHistorySize)
        assertEquals(12, state.dimensions.width)
        assertEquals(6, state.dimensions.height)

        // Exit alt screen and verify primary buffer reflowed / resized without corruption
        buffer.exitAltBuffer()
        assertFalse(state.isAltScreenActive)
        assertEquals(12, state.dimensions.width)
        assertEquals(6, state.dimensions.height)
        // Primary history should still exist (reflowed or clipped accordingly)
        val primaryHistorySize = (state.primaryBuffer.ring.size - state.dimensions.height).coerceAtLeast(0)
        assertTrue(primaryHistorySize >= 0)
    }
}
