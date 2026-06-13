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

import io.github.jvterm.core.codec.AttributeCodec
import io.github.jvterm.core.engine.CursorEngine
import io.github.jvterm.core.engine.MutationEngine
import io.github.jvterm.core.model.AttributeColor
import io.github.jvterm.core.model.Attributes
import io.github.jvterm.core.model.UnderlineStyle
import io.github.jvterm.core.state.TerminalState
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TerminalWriterImplTest {
    @Test
    fun `writes codepoints and advances the cursor`() {
        val state = TerminalState(5, 2, 2)
        val writer = TerminalWriterImpl(state, MutationEngine(state), CursorEngine(state))

        writer.setPenAttributes(3, 7, bold = true, italic = true)
        writer.writeCodepoint('X'.code)

        assertAll(
            { assertEquals('X'.code, state.ring[state.resolveRingIndex(0)].getCodepoint(0)) },
            { assertEquals(1, state.cursor.col) },
            { assertEquals(0, state.cursor.row) },
            {
                assertEquals(
                    Attributes(
                        foreground = AttributeColor.indexed(2),
                        background = AttributeColor.indexed(6),
                        bold = true,
                        italic = true,
                        underlineStyle = UnderlineStyle.NONE,
                    ),
                    AttributeCodec.unpack(
                        state.ring[state.resolveRingIndex(0)].getPackedAttr(0),
                        state.ring[state.resolveRingIndex(0)].getPackedExtendedAttr(0),
                    ),
                )
            },
        )
    }

    @Test
    fun `writes codepoints with explicit rgb indexed and inverse colors`() {
        val state = TerminalState(5, 2, 2)
        val writer = TerminalWriterImpl(state, MutationEngine(state), CursorEngine(state))

        writer.setPenColors(
            foreground = AttributeColor.rgb(0x10, 0x20, 0x30),
            background = AttributeColor.indexed(231),
            underlineStyle = UnderlineStyle.SINGLE,
            inverse = true,
        )
        writer.writeCodepoint('R'.code)

        val unpacked =
            AttributeCodec.unpack(
                state.ring[state.resolveRingIndex(0)].getPackedAttr(0),
                state.ring[state.resolveRingIndex(0)].getPackedExtendedAttr(0),
            )

        assertAll(
            { assertEquals(AttributeColor.rgb(0x10, 0x20, 0x30), unpacked.foreground) },
            { assertEquals(AttributeColor.indexed(231), unpacked.background) },
            { assertEquals(UnderlineStyle.SINGLE, unpacked.underlineStyle) },
            { assertTrue(unpacked.inverse) },
        )
    }

    @Test
    fun `writes text with supplementary code points literally`() {
        val state = TerminalState(6, 2, 2)
        val writer = TerminalWriterImpl(state, MutationEngine(state), CursorEngine(state))

        writer.writeText("A\uD83D\uDE00B")

        assertAll(
            { assertEquals("A\uD83D\uDE00B", state.ring[state.resolveRingIndex(0)].toTextTrimmed()) },
            { assertEquals(4, state.cursor.col) },
        )
    }

    @Test
    fun `writes parser segmented cluster through explicit cluster api`() {
        val state = TerminalState(6, 2, 2)
        val writer = TerminalWriterImpl(state, MutationEngine(state), CursorEngine(state))

        writer.writeCluster(intArrayOf('e'.code, 0x0301))

        val dest = IntArray(4)
        val written = state.ring[state.resolveRingIndex(0)].readCluster(0, dest)

        assertAll(
            { assertTrue(state.ring[state.resolveRingIndex(0)].isCluster(0)) },
            { assertEquals(2, written) },
            { assertEquals('e'.code, dest[0]) },
            { assertEquals(0x0301, dest[1]) },
        )
    }

    @Test
    fun `clearAll wipes screen history cursor and saved cursor`() {
        val state = TerminalState(4, 2, 2)
        val mutation = MutationEngine(state)
        val writer = TerminalWriterImpl(state, mutation, CursorEngine(state))

        writer.writeText("ABCD")
        state.savedCursor.isSaved = true
        writer.clearAll()

        assertAll(
            { assertEquals("", state.ring[state.resolveRingIndex(0)].toTextTrimmed()) },
            { assertEquals(0, state.cursor.col) },
            { assertEquals(0, state.cursor.row) },
            { assertEquals(false, state.savedCursor.isSaved) },
        )
    }
}
