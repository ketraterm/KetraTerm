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
package io.github.ketraterm.parser.unicode

import io.github.ketraterm.parser.runtime.ParserState
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class GraphemeSegmenterTest {
    private lateinit var state: ParserState

    @BeforeEach
    fun setup() {
        state = ParserState()
    }

    private fun acceptAndCheckContinues(codepoint: Int): Boolean {
        val properties = UnicodeClass.properties(codepoint)
        val currentClass = UnicodeClass.graphemeBreakClass(properties)
        val continues = GraphemeSegmenter.continuesCurrentCluster(state, currentClass, properties)
        GraphemeSegmenter.updateContext(state, properties, currentClass)
        return continues
    }

    @Test
    fun `consecutive ordinary ASCII and Unicode bases form separate clusters`() {
        acceptAndCheckContinues('a'.code)

        for (codepoint in intArrayOf('b'.code, 0x4E2D, 0x6587, 0x03B1, 'c'.code)) {
            assertFalse(acceptAndCheckContinues(codepoint), "Boundary before U+${codepoint.toString(16)}")
        }
    }

    @Test
    fun `ordinary base ends an emoji ZWJ sequence and clears its context`() {
        acceptAndCheckContinues(0x1F468)
        assertTrue(acceptAndCheckContinues(0x200D))

        assertFalse(acceptAndCheckContinues('a'.code))
        assertFalse(acceptAndCheckContinues(0x1F469))
        assertFalse(acceptAndCheckContinues(0x4E2D))
    }

    @Test
    fun `prepend plus base stays one cluster`() {
        acceptAndCheckContinues(0x0600)

        assertTrue(acceptAndCheckContinues('a'.code))
    }

    @Test
    fun `CR LF and control are classified distinctly`() {
        assertEquals(UnicodeClass.GRAPHEME_CR, UnicodeClass.graphemeBreakClass(UnicodeClass.properties(0x000D)))
        assertEquals(UnicodeClass.GRAPHEME_LF, UnicodeClass.graphemeBreakClass(UnicodeClass.properties(0x000A)))
        assertEquals(UnicodeClass.GRAPHEME_CONTROL, UnicodeClass.graphemeBreakClass(UnicodeClass.properties(0x0000)))
    }

    @Test
    fun `CR LF stays one cluster and other controls force boundaries`() {
        acceptAndCheckContinues(0x000D)

        assertTrue(acceptAndCheckContinues(0x000A))
        assertFalse(acceptAndCheckContinues(0x0000))
        assertFalse(acceptAndCheckContinues('a'.code))
    }

    @Test
    fun `Hangul L V T stays one cluster`() {
        acceptAndCheckContinues(0x1100)

        assertTrue(acceptAndCheckContinues(0x1161))
        assertTrue(acceptAndCheckContinues(0x11A8))
    }

    @Test
    fun `RI RI RI becomes two clusters pair plus single`() {
        acceptAndCheckContinues(0x1F1E6)

        assertTrue(acceptAndCheckContinues(0x1F1E7))
        assertFalse(acceptAndCheckContinues(0x1F1E8))
    }

    @Test
    fun `emoji plus ZWJ plus emoji stays one cluster`() {
        acceptAndCheckContinues(0x1F468)

        assertTrue(acceptAndCheckContinues(0x200D))
        assertTrue(acceptAndCheckContinues(0x1F469))
    }

    @Test
    fun `emoji plus VS16 plus ZWJ plus emoji stays one cluster`() {
        acceptAndCheckContinues(0x1F468)

        assertTrue(acceptAndCheckContinues(0xFE0F))
        assertTrue(acceptAndCheckContinues(0x200D))
        assertTrue(acceptAndCheckContinues(0x1F469))
    }

    @Test
    fun `base plus combining plus base becomes two clusters`() {
        acceptAndCheckContinues('a'.code)

        assertTrue(acceptAndCheckContinues(0x0300))
        assertFalse(acceptAndCheckContinues('b'.code))
    }
}
