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
package io.github.ketraterm.core.engine

import io.github.ketraterm.core.codec.AttributeCodec
import io.github.ketraterm.core.model.TerminalConstants
import io.github.ketraterm.core.state.TerminalState
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TerminalResizerProtectionTest {
    private fun resizeState(
        state: TerminalState,
        newWidth: Int,
        newHeight: Int,
    ) {
        val oldWidth = state.dimensions.width
        val oldHeight = state.dimensions.height
        TerminalResizer.resizeBuffer(state.primaryBuffer, oldWidth, oldHeight, newWidth, newHeight)
        state.dimensions.width = newWidth
        state.dimensions.height = newHeight
        state.tabStops.resize(newWidth)
    }

    @Test
    fun `resize_reflow_preservesProtectionBits`() {
        val state = TerminalState(initialWidth = 5, initialHeight = 2, maxHistory = 2)
        val writer = MutationEngine(state)
        state.pen.setSelectiveEraseProtection(true)
        writer.printCodepoint('A'.code, 1)
        state.pen.setSelectiveEraseProtection(false)
        writer.printCodepoint('B'.code, 1)
        writer.printCodepoint('C'.code, 1)

        resizeState(state, newWidth = 2, newHeight = 3)

        val top = (state.ring.size - state.dimensions.height).coerceAtLeast(0)
        val firstVisible = state.ring[top]
        val secondVisible = state.ring[top + 1]
        val thirdVisible = state.ring[top + 2]

        assertAll(
            { assertEquals('A'.code, firstVisible.getCodepoint(0)) },
            { assertTrue(AttributeCodec.isProtected(firstVisible.getPackedAttr(0))) },
            { assertEquals('B'.code, firstVisible.getCodepoint(1)) },
            { assertFalse(AttributeCodec.isProtected(firstVisible.getPackedAttr(1))) },
            { assertEquals('C'.code, secondVisible.getCodepoint(0)) },
            { assertFalse(AttributeCodec.isProtected(secondVisible.getPackedAttr(0))) },
            { assertEquals(TerminalConstants.EMPTY, thirdVisible.getCodepoint(0)) },
        )
    }

    @Test
    fun `resize_reflow_preservesProtectionAcrossWideGlyphSpan`() {
        val state = TerminalState(initialWidth = 5, initialHeight = 2, maxHistory = 2)
        val writer = MutationEngine(state)
        writer.printCodepoint('A'.code, 1)
        state.pen.setSelectiveEraseProtection(true)
        writer.printCodepoint(0x4F60, 2)
        state.pen.setSelectiveEraseProtection(false)
        writer.printCodepoint('B'.code, 1)

        resizeState(state, newWidth = 2, newHeight = 3)

        val wideLine =
            (0 until state.ring.size)
                .map(state.ring::get)
                .first { it.getCodepoint(0) == 0x4F60 }

        assertAll(
            { assertEquals(0x4F60, wideLine.getCodepoint(0)) },
            { assertEquals(TerminalConstants.WIDE_CHAR_SPACER, wideLine.getCodepoint(1)) },
            { assertTrue(AttributeCodec.isProtected(wideLine.getPackedAttr(0))) },
            { assertTrue(AttributeCodec.isProtected(wideLine.getPackedAttr(1))) },
        )
    }
}
