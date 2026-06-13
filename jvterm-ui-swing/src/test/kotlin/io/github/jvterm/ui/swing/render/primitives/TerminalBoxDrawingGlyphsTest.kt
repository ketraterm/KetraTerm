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
package io.github.jvterm.ui.swing.render.primitives

import io.github.jvterm.ui.swing.render.primitives.TerminalBoxDrawingGlyphs.DIAGONAL_FALLING
import io.github.jvterm.ui.swing.render.primitives.TerminalBoxDrawingGlyphs.DIAGONAL_RISING
import io.github.jvterm.ui.swing.render.primitives.TerminalBoxDrawingGlyphs.DOUBLE
import io.github.jvterm.ui.swing.render.primitives.TerminalBoxDrawingGlyphs.DOWN_SHIFT
import io.github.jvterm.ui.swing.render.primitives.TerminalBoxDrawingGlyphs.HEAVY
import io.github.jvterm.ui.swing.render.primitives.TerminalBoxDrawingGlyphs.LEFT_SHIFT
import io.github.jvterm.ui.swing.render.primitives.TerminalBoxDrawingGlyphs.LIGHT
import io.github.jvterm.ui.swing.render.primitives.TerminalBoxDrawingGlyphs.NONE
import io.github.jvterm.ui.swing.render.primitives.TerminalBoxDrawingGlyphs.RIGHT_SHIFT
import io.github.jvterm.ui.swing.render.primitives.TerminalBoxDrawingGlyphs.UP_SHIFT
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalBoxDrawingGlyphsTest {
    @Nested
    inner class BitwisePacking {
        @Test
        fun `pack and edge shifts preserve all styles flawlessly`() {
            val packed =
                TerminalBoxDrawingGlyphs.pack(
                    left = NONE,
                    right = LIGHT,
                    up = HEAVY,
                    down = DOUBLE,
                )

            assertEquals(NONE, TerminalBoxDrawingGlyphs.edge(packed, LEFT_SHIFT))
            assertEquals(LIGHT, TerminalBoxDrawingGlyphs.edge(packed, RIGHT_SHIFT))
            assertEquals(HEAVY, TerminalBoxDrawingGlyphs.edge(packed, UP_SHIFT))
            assertEquals(DOUBLE, TerminalBoxDrawingGlyphs.edge(packed, DOWN_SHIFT))
        }

        @Test
        fun `edges returns NONE for out of bounds codepoints`() {
            assertEquals(NONE, TerminalBoxDrawingGlyphs.edges(0x0020))
            assertEquals(NONE, TerminalBoxDrawingGlyphs.edges(0x2600))
        }
    }

    @Nested
    inner class DashResolutions {
        @Test
        fun `dash counts resolve strictly to unicode spec`() {
            // Triple Dash
            assertEquals(3, TerminalBoxDrawingGlyphs.dashCount(0x2504))
            // Quadruple Dash
            assertEquals(4, TerminalBoxDrawingGlyphs.dashCount(0x2508))
            // Double Dash
            assertEquals(2, TerminalBoxDrawingGlyphs.dashCount(0x254C))
            // Non-dashed
            assertEquals(0, TerminalBoxDrawingGlyphs.dashCount(0x2500))
        }
    }

    @Nested
    inner class GeometryMasks {
        @Test
        fun `diagonal masks resolve rising and falling states`() {
            assertEquals(DIAGONAL_RISING, TerminalBoxDrawingGlyphs.diagonalMask(0x2571))
            assertEquals(DIAGONAL_FALLING, TerminalBoxDrawingGlyphs.diagonalMask(0x2572))
            assertEquals(DIAGONAL_RISING or DIAGONAL_FALLING, TerminalBoxDrawingGlyphs.diagonalMask(0x2573))
            assertEquals(NONE, TerminalBoxDrawingGlyphs.diagonalMask(0x2500))
        }

        @Test
        fun `canPaint validates strict block range`() {
            assertTrue(TerminalBoxDrawingGlyphs.canPaint(0x2500))
            assertTrue(TerminalBoxDrawingGlyphs.canPaint(0x257F))
            assertFalse(TerminalBoxDrawingGlyphs.canPaint(0x24FF))
            assertFalse(TerminalBoxDrawingGlyphs.canPaint(0x2580))
        }
    }
}
