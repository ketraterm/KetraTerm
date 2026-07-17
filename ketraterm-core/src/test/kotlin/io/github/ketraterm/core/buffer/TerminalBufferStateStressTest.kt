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
import io.github.ketraterm.core.model.TerminalConstants
import io.github.ketraterm.core.state.ScreenBuffer
import io.github.ketraterm.core.state.TerminalState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * Exercises public buffer mutations in deterministic mixed-operation sequences.
 *
 * The test deliberately crosses screen, margin, edit, scroll, and resize boundaries.
 * Each seed is independently reproducible from the assertion message.
 */
class TerminalBufferStateStressTest {
    @Test
    fun `mixed public mutations preserve storage and cursor invariants`() {
        val seeds = intArrayOf(0x1A2B3C, 0x5EED, 0xC0FFEE, 0x51A7E)

        for (seed in seeds) {
            val buffer = TerminalBuffers.create(width = 7, height = 4, maxHistory = 6)
            val random = Random(seed)

            repeat(600) { step ->
                applyRandomOperation(buffer, random)
                assertStateInvariants(stateOf(buffer), seed, step)
            }
        }
    }

    private fun applyRandomOperation(
        buffer: TerminalBuffer,
        random: Random,
    ) {
        when (random.nextInt(18)) {
            0 -> buffer.setLeftRightMarginMode(random.nextBoolean())
            1 -> setRandomHorizontalMargins(buffer, random)
            2 -> setRandomVerticalMargins(buffer, random)
            3 -> buffer.setOriginMode(random.nextBoolean())
            4 -> buffer.setAutoWrap(random.nextBoolean())
            5 -> buffer.positionCursor(random.nextInt(16) - 4, random.nextInt(12) - 4)
            6 -> buffer.writeCodepoint('A'.code + random.nextInt(26))
            7 -> buffer.writeCodepoint(0x1F600 + random.nextInt(5))
            8 -> buffer.writeCluster(intArrayOf('e'.code, 0x0301))
            9 -> buffer.insertBlankCharacters(1 + random.nextInt(4))
            10 -> buffer.deleteCharacters(1 + random.nextInt(4))
            11 -> buffer.insertLines(1 + random.nextInt(3))
            12 -> buffer.deleteLines(1 + random.nextInt(3))
            13 -> repeat(1 + random.nextInt(3)) { buffer.scrollUp() }
            14 -> repeat(1 + random.nextInt(3)) { buffer.scrollDown() }
            15 -> buffer.eraseCharacters(1 + random.nextInt(4))
            16 -> toggleAlternateBuffer(buffer, random)
            else -> buffer.resize(2 + random.nextInt(8), 2 + random.nextInt(5))
        }
    }

    private fun setRandomHorizontalMargins(
        buffer: TerminalBuffer,
        random: Random,
    ) {
        val width = buffer.width
        val left = 1 + random.nextInt(width - 1)
        val right = left + 1 + random.nextInt(width - left)
        buffer.setLeftRightMargins(left, right)
    }

    private fun setRandomVerticalMargins(
        buffer: TerminalBuffer,
        random: Random,
    ) {
        val height = buffer.height
        val top = 1 + random.nextInt(height - 1)
        val bottom = top + 1 + random.nextInt(height - top)
        buffer.setScrollRegion(top, bottom)
    }

    private fun toggleAlternateBuffer(
        buffer: TerminalBuffer,
        random: Random,
    ) {
        if (random.nextBoolean()) {
            buffer.enterAltBuffer()
        } else {
            buffer.exitAltBuffer()
        }
    }

    private fun assertStateInvariants(
        state: TerminalState,
        seed: Int,
        step: Int,
    ) {
        assertScreenInvariants(state.primaryBuffer, state, "primary", seed, step)
        assertScreenInvariants(state.altBuffer, state, "alternate", seed, step)
        assertEquals(
            state.dimensions.height,
            state.altBuffer.ring.size,
            "alternate buffer must never retain scrollback; seed=$seed step=$step",
        )
    }

    private fun assertScreenInvariants(
        screen: ScreenBuffer,
        state: TerminalState,
        name: String,
        seed: Int,
        step: Int,
    ) {
        val width = state.dimensions.width
        val height = state.dimensions.height
        val prefix = "$name screen; seed=$seed step=$step"

        assertTrue(screen.ring.size in height..(height + screen.maxHistory), "$prefix: invalid history size")
        assertTrue(screen.leftMargin in 0 until width, "$prefix: invalid left margin")
        assertTrue(screen.rightMargin in 0 until width, "$prefix: invalid right margin")
        assertTrue(screen.leftMargin < screen.rightMargin, "$prefix: degenerate horizontal margins")
        assertTrue(screen.scrollTop in 0 until height, "$prefix: invalid scroll top")
        assertTrue(screen.scrollBottom in 0 until height, "$prefix: invalid scroll bottom")
        assertTrue(screen.scrollTop < screen.scrollBottom, "$prefix: degenerate vertical margins")
        assertTrue(screen.cursor.col in 0 until width, "$prefix: cursor column out of bounds")
        assertTrue(screen.cursor.row in 0 until height, "$prefix: cursor row out of bounds")

        for (lineIndex in 0 until screen.ring.size) {
            val line = screen.ring[lineIndex]
            assertEquals(width, line.width, "$prefix: line $lineIndex has stale width")
            assertLineHasNoOrphanedSpans(lineIndex, line, prefix)
        }
    }

    private fun assertLineHasNoOrphanedSpans(
        lineIndex: Int,
        line: io.github.ketraterm.core.model.Line,
        prefix: String,
    ) {
        val clusterScratch = IntArray(32)

        for (column in 0 until line.width) {
            val value = line.rawCodepoint(column)
            if (value == TerminalConstants.WIDE_CHAR_SPACER) {
                assertTrue(column > 0, "$prefix: spacer at line=$lineIndex column=0")
                val leader = line.rawCodepoint(column - 1)
                assertTrue(
                    leader != TerminalConstants.EMPTY && leader != TerminalConstants.WIDE_CHAR_SPACER,
                    "$prefix: orphaned spacer at line=$lineIndex column=$column",
                )
            }
            if (value <= TerminalConstants.CLUSTER_HANDLE_MAX) {
                assertTrue(
                    line.readCluster(column, clusterScratch) > 0,
                    "$prefix: stale cluster handle at line=$lineIndex column=$column",
                )
            }
        }
    }

    private fun stateOf(buffer: TerminalBuffer): TerminalState {
        val componentsField = buffer.javaClass.getDeclaredField("components")
        componentsField.isAccessible = true
        val components = componentsField.get(buffer)

        val stateField = components.javaClass.getDeclaredField("state")
        stateField.isAccessible = true
        return stateField.get(components) as TerminalState
    }
}
