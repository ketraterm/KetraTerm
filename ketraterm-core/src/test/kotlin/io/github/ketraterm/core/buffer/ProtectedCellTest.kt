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
import io.github.ketraterm.core.codec.AttributeCodec
import io.github.ketraterm.core.state.TerminalState
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ProtectedCellTest {
    private fun blankScreen(height: Int): String = List(height) { "" }.joinToString("\n")

    private fun stateOf(api: TerminalBuffer): TerminalState {
        val componentsField = api.javaClass.getDeclaredField("components")
        componentsField.isAccessible = true
        val components = componentsField.get(api)

        val stateField = components.javaClass.getDeclaredField("state")
        stateField.isAccessible = true
        return stateField.get(components) as TerminalState
    }

    @Test
    fun `altBufferEntry_noProtectedCells`() {
        val buffer = TerminalBuffers.create(width = 4, height = 2, maxHistory = 2)
        val state = stateOf(buffer)
        buffer.setSelectiveEraseProtection(true)
        buffer.writeCodepoint('A'.code)

        buffer.enterAltBuffer()

        assertAll(
            { assertEquals(blankScreen(2), buffer.getScreenAsString()) },
            { assertFalse(AttributeCodec.isProtected(state.altBuffer.ring[state.altBuffer.ring.size - 1].getPackedAttr(0))) },
        )
    }

    @Test
    fun `clearAllHistory_ignoresProtection`() {
        val buffer = TerminalBuffers.create(width = 4, height = 2, maxHistory = 2)
        val state = stateOf(buffer)
        buffer.setSelectiveEraseProtection(true)
        buffer.writeText("AB")

        buffer.clearAll()

        val top = (state.primaryBuffer.ring.size - state.dimensions.height).coerceAtLeast(0)
        assertAll(
            { assertEquals(blankScreen(2), buffer.getScreenAsString()) },
            { assertFalse(AttributeCodec.isProtected(state.primaryBuffer.ring[top].getPackedAttr(0))) },
        )
    }
}
