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
package io.github.jvterm.core.engine

import io.github.jvterm.core.model.Line
import io.github.jvterm.core.model.TerminalConstants
import io.github.jvterm.core.state.TerminalState
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class TerminalInvariantPropertyTest {
    private fun visibleLine(
        state: TerminalState,
        row: Int,
    ): Line = state.ring[state.resolveRingIndex(row)]

    private fun assertNoOrphanSpacers(state: TerminalState) {
        for (row in 0 until state.dimensions.height) {
            val line = visibleLine(state, row)
            for (col in 0 until line.width) {
                if (line.rawCodepoint(col) == TerminalConstants.WIDE_CHAR_SPACER) {
                    assertTrue(col > 0, "Spacer cannot appear in column 0")
                    val leader = line.rawCodepoint(col - 1)
                    assertTrue(
                        leader != TerminalConstants.EMPTY && leader != TerminalConstants.WIDE_CHAR_SPACER,
                        "Spacer at row=$row col=$col must have a non-empty leader on its left",
                    )
                }
            }
        }
    }

    @Test
    fun `noMutationLeavesOrphanSpacer_afterRandomWriteEraseIchDchSequences`() {
        val state = TerminalState(initialWidth = 6, initialHeight = 3, maxHistory = 2)
        val engine = MutationEngine(state)
        val random = Random(1234)

        repeat(400) {
            state.cursor.row = random.nextInt(state.dimensions.height)
            state.cursor.col = random.nextInt(state.dimensions.width)

            when (random.nextInt(6)) {
                0 -> engine.printCodepoint('A'.code + random.nextInt(26), 1)
                1 -> engine.printCodepoint(0x1F600 + random.nextInt(5), 2)
                2 -> engine.eraseLineToEnd()
                3 -> engine.insertBlankCharacters(1 + random.nextInt(2))
                4 -> engine.deleteCharacters(1 + random.nextInt(2))
                else -> engine.eraseCurrentLine()
            }

            assertNoOrphanSpacers(state)
        }
    }

    @Test
    fun `noResizeLeavesSpacerWithoutLeader_afterRandomWidthChanges`() {
        val state = TerminalState(initialWidth = 8, initialHeight = 4, maxHistory = 3)
        val engine = MutationEngine(state)
        val random = Random(5678)

        repeat(60) {
            state.cursor.row = random.nextInt(state.dimensions.height)
            state.cursor.col = random.nextInt(state.dimensions.width)
            if (random.nextBoolean()) {
                engine.printCodepoint(0x1F600 + random.nextInt(5), 2)
            } else {
                engine.printCodepoint('a'.code + random.nextInt(26), 1)
            }
        }

        repeat(40) {
            val oldWidth = state.dimensions.width
            val oldHeight = state.dimensions.height
            val newWidth = 2 + random.nextInt(7)
            val newHeight = 2 + random.nextInt(4)

            TerminalResizer.resizeBuffer(
                buffer = state.primaryBuffer,
                oldWidth = oldWidth,
                oldHeight = oldHeight,
                newWidth = newWidth,
                newHeight = newHeight,
            )
            state.dimensions.width = newWidth
            state.dimensions.height = newHeight
            state.primaryBuffer.resetScrollRegion(newHeight)

            val liveTop = (state.primaryBuffer.ring.size - newHeight).coerceAtLeast(0)
            for (i in liveTop until state.primaryBuffer.ring.size) {
                val line = state.primaryBuffer.ring[i]
                for (col in 0 until line.width) {
                    if (line.rawCodepoint(col) == TerminalConstants.WIDE_CHAR_SPACER) {
                        assertTrue(col > 0, "Spacer cannot appear in column 0 after resize")
                        val leader = line.rawCodepoint(col - 1)
                        assertTrue(
                            leader != TerminalConstants.EMPTY && leader != TerminalConstants.WIDE_CHAR_SPACER,
                            "Spacer at logicalLine=$i col=$col must retain a leader after resize",
                        )
                    }
                }
            }
        }
    }
}
