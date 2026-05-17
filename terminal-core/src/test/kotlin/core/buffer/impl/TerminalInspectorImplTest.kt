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
package com.gagik.core.buffer.impl

import com.gagik.core.codec.AttributeCodec
import com.gagik.core.engine.MutationEngine
import com.gagik.core.model.AttributeColor
import com.gagik.core.model.Attributes
import com.gagik.core.model.UnderlineStyle
import com.gagik.core.state.TerminalState
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TerminalInspectorImplTest {
    @Test
    fun `renders visible rows screen and all content`() {
        val state = TerminalState(3, 2, 2)
        val mutation = MutationEngine(state)
        val inspector = TerminalInspectorImpl(state)

        mutation.printCodepoint('A'.code, 1)
        mutation.printCodepoint('B'.code, 1)
        state.cursor.col = 0
        mutation.newLine()
        mutation.printCodepoint('C'.code, 1)

        assertAll(
            { assertEquals("AB", inspector.getLineAsString(0)) },
            { assertEquals("C", inspector.getLineAsString(1)) },
            { assertEquals("AB\nC", inspector.getScreenAsString()) },
            { assertEquals("AB\nC", inspector.getAllAsString()) },
        )
    }

    @Test
    fun `unpacks cell attributes at a visible coordinate`() {
        val state = TerminalState(3, 1, 1)
        val mutation = MutationEngine(state)
        val inspector = TerminalInspectorImpl(state)

        state.pen.setAttributes(3, 7, bold = true, italic = true)
        mutation.printCodepoint('X'.code, 1)

        val expected =
            Attributes(
                foreground = AttributeColor.indexed(2),
                background = AttributeColor.indexed(6),
                bold = true,
                italic = true,
                underlineStyle = UnderlineStyle.NONE,
            )
        assertAll(
            { assertEquals(expected, inspector.getAttrAt(0, 0)) },
            {
                assertEquals(
                    expected,
                    AttributeCodec.unpack(
                        state.ring[state.resolveRingIndex(0)].getPackedAttr(0),
                        state.ring[state.resolveRingIndex(0)].getPackedExtendedAttr(0),
                    ),
                )
            },
        )
    }
}
