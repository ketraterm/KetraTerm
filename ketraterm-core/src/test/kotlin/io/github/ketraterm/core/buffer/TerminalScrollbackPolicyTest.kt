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
import io.github.ketraterm.render.api.TerminalRenderBufferKind
import io.github.ketraterm.render.api.TerminalRenderFrameReader
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class TerminalScrollbackPolicyTest {
    @ParameterizedTest(name = "history capacity {0}")
    @CsvSource(
        "0, 0, CCC|DDD|DDD",
        "1, 1, CCC|CCC|DDD|DDD",
        "4, 4, AAA|BBB|BBB|CCC|CCC|DDD|DDD",
        "5, 5, AAA|AAA|BBB|BBB|CCC|CCC|DDD|DDD",
        "6, 5, AAA|AAA|BBB|BBB|CCC|CCC|DDD|DDD",
    )
    fun `narrowing retains exactly the newest rows allowed by history capacity`(
        maxHistory: Int,
        expectedHistory: Int,
        expectedRows: String,
    ) {
        for (alternate in booleanArrayOf(false, true)) {
            val buffer = TerminalBuffers.create(width = 8, height = 3, maxHistory = maxHistory)
            writeLines(buffer, "AAAAAA", "BBBBBB", "CCCCCC", "DDDDDD")
            assertEquals(minOf(1, maxHistory), buffer.historySize)
            buffer.positionCursor(col = 1, row = 2)
            if (alternate) {
                buffer.enterAltBuffer()
                writeLines(buffer, "T0", "T1", "T2", "T3")
            }

            val result = buffer.resize(3, 3)

            if (alternate) {
                assertEquals(0 to 0, result)
                assertBlankAlternate(buffer, width = 3, height = 3)
                buffer.exitAltBuffer()
            } else {
                assertEquals(0 to expectedHistory, result)
            }
            assertAll(
                "alternate=$alternate, capacity=$maxHistory",
                { assertEquals(expectedHistory, buffer.historySize) },
                { assertEquals(expectedRows.replace('|', '\n'), buffer.getAllAsString()) },
                { assertEquals("CCC\nDDD\nDDD", buffer.getScreenAsString()) },
            )
        }
    }

    @ParameterizedTest(name = "alternate mode {0}")
    @ValueSource(ints = [47, 1047, 1049])
    fun `repeated alternate resize cycles never resurrect evicted primary rows`(mode: Int) {
        val buffer = TerminalBuffers.create(width = 8, height = 3, maxHistory = 1)
        writeLines(buffer, "AAAAAA", "BBBBBB", "CCCCCC", "DDDDDD")
        buffer.positionCursor(col = 1, row = 2)
        assertEquals(1, buffer.historySize)

        repeat(3) {
            enterAlternate(buffer, mode)
            writeLines(buffer, "T0", "T1", "T2", "T3")
            assertEquals(0 to 0, buffer.resize(3, 3, oldScrollbackOffset = Int.MAX_VALUE))
            assertBlankAlternate(buffer, width = 3, height = 3)
            exitAlternate(buffer, mode)
            assertAll(
                { assertEquals(1, buffer.historySize) },
                { assertEquals("CCC\nCCC\nDDD\nDDD", buffer.getAllAsString()) },
                { assertEquals("CCC\nDDD\nDDD", buffer.getScreenAsString()) },
            )

            enterAlternate(buffer, mode)
            writeLines(buffer, "X0", "X1", "X2", "X3")
            assertEquals(0 to 0, buffer.resize(8, 3))
            assertBlankAlternate(buffer, width = 8, height = 3)
            exitAlternate(buffer, mode)
            assertAll(
                { assertEquals(0, buffer.historySize) },
                { assertEquals("CCCCCC\nDDDDDD\n", buffer.getAllAsString()) },
                { assertEquals("CCCCCC\nDDDDDD\n", buffer.getScreenAsString()) },
            )
        }
    }

    @ParameterizedTest(name = "history capacity {0}")
    @ValueSource(ints = [0, 1, 2])
    fun `height shrink evicts oldest rows and growth cannot restore them`(maxHistory: Int) {
        val buffer = TerminalBuffers.create(width = 4, height = 4, maxHistory = maxHistory)
        writeLines(buffer, "H0", "H1", "P0", "P1", "P2", "P3")
        buffer.positionCursor(col = 0, row = 3)
        assertEquals(maxHistory, buffer.historySize)
        buffer.enterAltBuffer()
        writeLines(buffer, "T0", "T1", "T2", "T3", "T4")

        assertEquals(0 to 0, buffer.resize(4, 2))
        assertBlankAlternate(buffer, width = 4, height = 2)
        buffer.exitAltBuffer()
        val retainedRows =
            when (maxHistory) {
                0 -> "P2\nP3"
                1 -> "P1\nP2\nP3"
                else -> "P0\nP1\nP2\nP3"
            }
        assertAll(
            { assertEquals(maxHistory, buffer.historySize) },
            { assertEquals(retainedRows, buffer.getAllAsString()) },
            { assertEquals("P2\nP3", buffer.getScreenAsString()) },
        )

        buffer.enterAltBuffer()
        buffer.writeText("TUI")
        assertEquals(0 to 0, buffer.resize(4, 5))
        assertBlankAlternate(buffer, width = 4, height = 5)
        buffer.exitAltBuffer()
        assertAll(
            { assertEquals(maxHistory, buffer.historySize) },
            { assertEquals("$retainedRows\n\n\n", buffer.getAllAsString()) },
            { assertEquals("P2\nP3\n\n\n", buffer.getScreenAsString()) },
        )
    }

    private fun writeLines(
        buffer: TerminalBuffer,
        vararg lines: String,
    ) {
        for ((index, text) in lines.withIndex()) {
            if (index > 0) {
                buffer.carriageReturn()
                buffer.newLine()
            }
            buffer.writeText(text)
        }
    }

    private fun enterAlternate(
        buffer: TerminalBuffer,
        mode: Int,
    ) {
        when (mode) {
            47 -> buffer.enterAltBufferWithoutCursorSave(clearBeforeEnter = false)
            1047 -> buffer.enterAltBufferWithoutCursorSave(clearBeforeEnter = true)
            1049 -> buffer.enterAltBuffer()
            else -> error("Unexpected alternate-screen mode: $mode")
        }
    }

    private fun exitAlternate(
        buffer: TerminalBuffer,
        mode: Int,
    ) {
        if (mode == 1049) buffer.exitAltBuffer() else buffer.exitAltBufferWithoutCursorRestore()
    }

    private fun assertBlankAlternate(
        buffer: TerminalBuffer,
        width: Int,
        height: Int,
    ) {
        assertAll(
            { assertEquals(width, buffer.width) },
            { assertEquals(height, buffer.height) },
            { assertEquals(0, buffer.historySize) },
            { assertEquals("\n".repeat(height - 1), buffer.getAllAsString()) },
        )
        (buffer as TerminalRenderFrameReader).readRenderFrame { frame ->
            assertEquals(TerminalRenderBufferKind.ALTERNATE, frame.activeBuffer)
        }
    }

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
    fun `resize while alternate active preserves exact primary history and clears alternate text`() {
        val buffer = TerminalBuffers.create(width = 10, height = 5, maxHistory = 10)
        val state = stateOf(buffer)

        // Generate primary scrollback
        repeat(8) {
            buffer.writeText("P $it")
            buffer.carriageReturn()
            buffer.newLine()
        }
        val origHistorySize = (state.primaryBuffer.ring.size - state.dimensions.height).coerceAtLeast(0)
        assertEquals(4, origHistorySize)

        // Enter alt screen
        buffer.enterAltBuffer()
        buffer.writeText("Alt text")

        // Resize buffer while alt screen is active
        assertEquals(0 to 0, buffer.resize(12, 6, oldScrollbackOffset = 4))
        assertEquals("\n\n\n\n\n", buffer.getAllAsString())

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
        assertAll(
            { assertEquals(4, buffer.historySize) },
            { assertEquals("P 0\nP 1\nP 2\nP 3\nP 4\nP 5\nP 6\nP 7\n\n", buffer.getAllAsString()) },
            { assertEquals("P 4\nP 5\nP 6\nP 7\n\n", buffer.getScreenAsString()) },
        )
    }
}
