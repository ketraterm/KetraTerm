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
package io.github.jvterm.core.buffer.impl

import io.github.jvterm.core.engine.MutationEngine
import io.github.jvterm.core.model.TerminalConstants
import io.github.jvterm.core.state.TerminalState
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TerminalReaderImplTest {
    @Test
    fun `exposes dimensions cursor and history from shared state`() {
        val state = TerminalState(4, 3, 5)
        val reader = TerminalReaderImpl(state)

        assertAll(
            { assertEquals(4, reader.width) },
            { assertEquals(3, reader.height) },
            { assertEquals(0, reader.cursorCol) },
            { assertEquals(0, reader.cursorRow) },
            { assertEquals(0, reader.historySize) },
        )
    }

    @Test
    fun `returns void line and safe fallbacks for out of bounds reads`() {
        val state = TerminalState(4, 2, 2)
        val reader = TerminalReaderImpl(state)

        assertAll(
            { assertEquals(0, reader.getLine(-1).width) },
            { assertEquals(TerminalConstants.EMPTY, reader.getCodepointAt(-1, 0)) },
            { assertEquals(TerminalConstants.EMPTY, reader.getCodepointAt(0, -1)) },
            { assertEquals(state.pen.currentAttr, reader.getPackedAttrAt(-1, 0)) },
            { assertEquals(state.pen.currentAttr, reader.getPackedAttrAt(0, 99)) },
        )
    }

    @Test
    fun `reads visible content from the backing ring`() {
        val state = TerminalState(3, 2, 2)
        val mutation = MutationEngine(state)
        val reader = TerminalReaderImpl(state)

        mutation.printCodepoint('A'.code, 1)
        mutation.printCodepoint('B'.code, 1)

        assertAll(
            { assertEquals('A'.code, reader.getCodepointAt(0, 0)) },
            { assertEquals('B'.code, reader.getCodepointAt(1, 0)) },
            { assertEquals(state.pen.currentAttr, reader.getPackedAttrAt(1, 0)) },
            { assertEquals('A'.code, reader.getLine(0).getCodepoint(0)) },
            { assertEquals('B'.code, reader.getLine(0).getCodepoint(1)) },
        )
    }
}
