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

import io.github.jvterm.core.codec.AttributeCodec
import io.github.jvterm.core.model.Line
import io.github.jvterm.core.model.TerminalConstants
import io.github.jvterm.core.state.TerminalState
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MutationEngineProtectionTest {
    private fun createState(
        width: Int = 6,
        height: Int = 2,
        history: Int = 2,
    ): TerminalState = TerminalState(width, height, maxHistory = history)

    private fun lineAt(
        state: TerminalState,
        row: Int,
    ): Line {
        val top = (state.ring.size - state.dimensions.height).coerceAtLeast(0)
        return state.ring[top + row]
    }

    private fun writeAscii(
        writer: MutationEngine,
        text: String,
    ) {
        for (ch in text) {
            writer.printCodepoint(ch.code, 1)
        }
    }

    @Test
    fun `writeOverProtected_succeeds`() {
        val state = createState(width = 4, height = 1)
        val writer = MutationEngine(state)
        state.pen.setSelectiveEraseProtection(true)
        writer.printCodepoint('A'.code, 1)

        state.pen.setSelectiveEraseProtection(false)
        state.cursor.col = 0
        state.cursor.pendingWrap = false
        writer.printCodepoint('B'.code, 1)

        assertAll(
            { assertEquals('B'.code, lineAt(state, 0).getCodepoint(0)) },
            { assertFalse(AttributeCodec.isProtected(lineAt(state, 0).getPackedAttr(0))) },
        )
    }

    @Test
    fun `decsca_penFlag_stampsOnWrite`() {
        val state = createState(width = 4, height = 1)
        val writer = MutationEngine(state)
        state.pen.setSelectiveEraseProtection(true)

        writer.printCodepoint('P'.code, 1)

        assertTrue(AttributeCodec.isProtected(lineAt(state, 0).getPackedAttr(0)))
    }

    @Test
    fun `normalErase_clearsProtected`() {
        val state = createState(width = 4, height = 1)
        val writer = MutationEngine(state)
        state.pen.setSelectiveEraseProtection(true)
        writer.printCodepoint('A'.code, 1)
        state.pen.setSelectiveEraseProtection(false)
        writer.printCodepoint('B'.code, 1)
        state.cursor.col = 0

        writer.eraseCurrentLine()

        assertAll(
            { assertEquals(TerminalConstants.EMPTY, lineAt(state, 0).getCodepoint(0)) },
            { assertEquals(TerminalConstants.EMPTY, lineAt(state, 0).getCodepoint(1)) },
            { assertFalse(AttributeCodec.isProtected(lineAt(state, 0).getPackedAttr(0))) },
            { assertFalse(AttributeCodec.isProtected(lineAt(state, 0).getPackedAttr(1))) },
        )
    }

    @Test
    fun `eraseCharacters_ignoresProtectionLikeNormalErase`() {
        val state = createState(width = 4, height = 1)
        val writer = MutationEngine(state)
        state.pen.setSelectiveEraseProtection(true)
        writer.printCodepoint('A'.code, 1)
        state.pen.setSelectiveEraseProtection(false)
        writer.printCodepoint('B'.code, 1)
        state.cursor.col = 0

        writer.eraseCharacters(2)

        assertAll(
            { assertEquals(TerminalConstants.EMPTY, lineAt(state, 0).getCodepoint(0)) },
            { assertEquals(TerminalConstants.EMPTY, lineAt(state, 0).getCodepoint(1)) },
            { assertFalse(AttributeCodec.isProtected(lineAt(state, 0).getPackedAttr(0))) },
            { assertFalse(AttributeCodec.isProtected(lineAt(state, 0).getPackedAttr(1))) },
        )
    }

    @Test
    fun `selectiveErase_skipsProtected_and_clearsUnprotected`() {
        val state = createState(width = 4, height = 1)
        val writer = MutationEngine(state)
        state.pen.setSelectiveEraseProtection(true)
        writer.printCodepoint('A'.code, 1)
        state.pen.setSelectiveEraseProtection(false)
        writeAscii(writer, "BC")
        state.cursor.col = 0

        writer.selectiveEraseCurrentLine()

        assertAll(
            { assertEquals('A'.code, lineAt(state, 0).getCodepoint(0)) },
            { assertEquals(TerminalConstants.EMPTY, lineAt(state, 0).getCodepoint(1)) },
            { assertEquals(TerminalConstants.EMPTY, lineAt(state, 0).getCodepoint(2)) },
            { assertTrue(AttributeCodec.isProtected(lineAt(state, 0).getPackedAttr(0))) },
            { assertFalse(AttributeCodec.isProtected(lineAt(state, 0).getPackedAttr(1))) },
        )
    }

    @Test
    fun `selectiveEraseLineToEnd_clearsOnlyUnprotectedRange`() {
        val state = createState(width = 5, height = 1)
        val writer = MutationEngine(state)
        state.pen.setSelectiveEraseProtection(false)
        writer.printCodepoint('A'.code, 1)
        state.pen.setSelectiveEraseProtection(true)
        writer.printCodepoint('B'.code, 1)
        state.pen.setSelectiveEraseProtection(false)
        writeAscii(writer, "CD")
        state.cursor.col = 1

        writer.selectiveEraseLineToEnd()

        assertAll(
            { assertEquals('A'.code, lineAt(state, 0).getCodepoint(0)) },
            { assertEquals('B'.code, lineAt(state, 0).getCodepoint(1)) },
            { assertEquals(TerminalConstants.EMPTY, lineAt(state, 0).getCodepoint(2)) },
            { assertEquals(TerminalConstants.EMPTY, lineAt(state, 0).getCodepoint(3)) },
        )
    }

    @Test
    fun `protectedWideLeader_spacerInheritsProtection`() {
        val state = createState(width = 4, height = 1)
        val writer = MutationEngine(state)
        state.pen.setSelectiveEraseProtection(true)

        writer.printCodepoint(0x1F600, 2)

        assertAll(
            { assertTrue(AttributeCodec.isProtected(lineAt(state, 0).getPackedAttr(0))) },
            { assertTrue(AttributeCodec.isProtected(lineAt(state, 0).getPackedAttr(1))) },
        )
    }

    @Test
    fun `selectiveErase_protectedSpacerInheritsLeaderProtection`() {
        val state = createState(width = 4, height = 1)
        val writer = MutationEngine(state)
        state.pen.setSelectiveEraseProtection(true)
        writer.printCodepoint(0x1F600, 2)
        state.cursor.col = 1
        state.cursor.pendingWrap = false

        writer.selectiveEraseLineToEnd()

        assertAll(
            { assertEquals(0x1F600, lineAt(state, 0).getCodepoint(0)) },
            { assertEquals(TerminalConstants.WIDE_CHAR_SPACER, lineAt(state, 0).rawCodepoint(1)) },
        )
    }

    @Test
    fun `ich_shiftedCells_preserveProtectionBit`() {
        val state = createState(width = 5, height = 1)
        val writer = MutationEngine(state)
        state.pen.setSelectiveEraseProtection(true)
        writer.printCodepoint('A'.code, 1)
        state.pen.setSelectiveEraseProtection(false)
        writer.printCodepoint('B'.code, 1)
        state.cursor.col = 0

        writer.insertBlankCharacters(1)

        assertAll(
            { assertEquals(TerminalConstants.EMPTY, lineAt(state, 0).getCodepoint(0)) },
            { assertEquals('A'.code, lineAt(state, 0).getCodepoint(1)) },
            { assertTrue(AttributeCodec.isProtected(lineAt(state, 0).getPackedAttr(1))) },
            { assertFalse(AttributeCodec.isProtected(lineAt(state, 0).getPackedAttr(0))) },
        )
    }

    @Test
    fun `dch_shiftedCells_preserveProtectionBit`() {
        val state = createState(width = 5, height = 1)
        val writer = MutationEngine(state)
        state.pen.setSelectiveEraseProtection(false)
        writer.printCodepoint('X'.code, 1)
        state.pen.setSelectiveEraseProtection(true)
        writer.printCodepoint('A'.code, 1)
        state.pen.setSelectiveEraseProtection(false)
        writer.printCodepoint('B'.code, 1)
        state.cursor.col = 0

        writer.deleteCharacters(1)

        assertAll(
            { assertEquals('A'.code, lineAt(state, 0).getCodepoint(0)) },
            { assertTrue(AttributeCodec.isProtected(lineAt(state, 0).getPackedAttr(0))) },
            { assertEquals('B'.code, lineAt(state, 0).getCodepoint(1)) },
            { assertFalse(AttributeCodec.isProtected(lineAt(state, 0).getPackedAttr(1))) },
        )
    }
}
