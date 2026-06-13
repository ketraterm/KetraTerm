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
package io.github.jvterm.ui.swing.render

import io.github.jvterm.render.api.TerminalRenderAttrs
import io.github.jvterm.render.api.TerminalRenderCellFlags
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.awt.Font
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalPainterSupportTest {
    @Nested
    inner class FontStyleResolution {
        @Test
        fun `terminalFontStyle extracts bold and italic combinations`() {
            assertEquals(Font.PLAIN, terminalFontStyle(TerminalRenderAttrs.DEFAULT))
            assertEquals(Font.BOLD, terminalFontStyle(TerminalRenderAttrs.pack(bold = true)))
            assertEquals(Font.ITALIC, terminalFontStyle(TerminalRenderAttrs.pack(italic = true)))
            assertEquals(Font.BOLD or Font.ITALIC, terminalFontStyle(TerminalRenderAttrs.pack(bold = true, italic = true)))
        }
    }

    @Nested
    inner class CellFlagEvaluation {
        @Test
        fun `hasDrawableText identifies codepoints and clusters but ignores structural blanks`() {
            assertFalse(hasDrawableText(TerminalRenderCellFlags.EMPTY))
            assertFalse(hasDrawableText(TerminalRenderCellFlags.WIDE_TRAILING))

            assertTrue(hasDrawableText(TerminalRenderCellFlags.CODEPOINT))
            assertTrue(hasDrawableText(TerminalRenderCellFlags.CLUSTER))
            assertTrue(hasDrawableText(TerminalRenderCellFlags.CODEPOINT or TerminalRenderCellFlags.WIDE_LEADING))
        }

        @Test
        fun `isFastAsciiCell strictly filters to printable ascii range`() {
            // Boundary values
            assertTrue(isFastAsciiCell(TerminalRenderCellFlags.CODEPOINT, 0x20)) // Space
            assertTrue(isFastAsciiCell(TerminalRenderCellFlags.CODEPOINT, 0x7E)) // Tilde

            // Out of bounds (Control chars and extended ASCII)
            assertFalse(isFastAsciiCell(TerminalRenderCellFlags.CODEPOINT, 0x1F))
            assertFalse(isFastAsciiCell(TerminalRenderCellFlags.CODEPOINT, 0x7F)) // DEL

            // Invalid flags (Must be purely CODEPOINT, no CLUSTER or WIDE flags)
            assertFalse(isFastAsciiCell(TerminalRenderCellFlags.CLUSTER, 0x41))
            assertFalse(isFastAsciiCell(TerminalRenderCellFlags.CODEPOINT or TerminalRenderCellFlags.WIDE_LEADING, 0x41))
        }

        @Test
        fun `cellSpan computes grid span for wide cells`() {
            assertEquals(1, cellSpan(TerminalRenderCellFlags.EMPTY))
            assertEquals(1, cellSpan(TerminalRenderCellFlags.CODEPOINT))
            assertEquals(2, cellSpan(TerminalRenderCellFlags.CODEPOINT or TerminalRenderCellFlags.WIDE_LEADING))
            assertEquals(2, cellSpan(TerminalRenderCellFlags.CLUSTER or TerminalRenderCellFlags.WIDE_LEADING))
        }

        @Test
        fun `visual cell range covers wide owners and trailing spacers`() {
            assertEquals(1, visualCellRangeStart(TerminalRenderCellFlags.CODEPOINT or TerminalRenderCellFlags.WIDE_LEADING, 1))
            assertEquals(2, visualCellRangeSpan(TerminalRenderCellFlags.CODEPOINT or TerminalRenderCellFlags.WIDE_LEADING, 1, 4))
            assertEquals(1, visualCellRangeStart(TerminalRenderCellFlags.WIDE_TRAILING, 2))
            assertEquals(2, visualCellRangeSpan(TerminalRenderCellFlags.WIDE_TRAILING, 2, 4))
            assertEquals(0, visualCellRangeStart(TerminalRenderCellFlags.WIDE_TRAILING, 0))
            assertEquals(1, visualCellRangeSpan(TerminalRenderCellFlags.WIDE_TRAILING, 0, 4))
        }
    }
}
