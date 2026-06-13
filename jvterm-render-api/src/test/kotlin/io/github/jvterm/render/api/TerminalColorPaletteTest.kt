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
package io.github.jvterm.render.api
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TerminalColorPaletteTest {
    @Test
    fun defaultPaletteContains256IndexedColors() {
        assertEquals(256, TerminalColorPalette.defaultIndexedColors().size)
    }

    @Test
    fun defaultPaletteUsesRendererNeutralAnsiFallbacks() {
        val palette = TerminalColorPalette()

        assertEquals(0xFFFFFFFF.toInt(), palette.defaultForeground)
        assertEquals(0xFF000000.toInt(), palette.defaultBackground)
        assertEquals(0xFF000000.toInt(), palette.indexedColor(0))
        assertEquals(0xFF800000.toInt(), palette.indexedColor(1))
        assertEquals(0xFFFFFFFF.toInt(), palette.indexedColor(15))
    }

    @Test
    fun resolvesDefaultForegroundAndBackground() {
        val palette =
            TerminalColorPalette(
                defaultForeground = 0xFF010203.toInt(),
                defaultBackground = 0xFF040506.toInt(),
            )

        assertEquals(0xFF010203.toInt(), palette.foreground(TerminalRenderAttrs.DEFAULT))
        assertEquals(0xFF040506.toInt(), palette.background(TerminalRenderAttrs.DEFAULT))
    }

    @Test
    fun boldForegroundUsesBrightAnsiColorWhenEnabled() {
        val colors = IntArray(256) { 0xFF000000.toInt() or it }
        val palette = TerminalColorPalette(indexedColors = colors, boldAsBright = true)
        val attrs =
            TerminalRenderAttrs.pack(
                foregroundKind = TerminalRenderColorKind.INDEXED,
                foregroundValue = 2,
                bold = true,
            )

        assertEquals(colors[10], palette.foreground(attrs))
    }

    @Test
    fun copiesIndexedColorsOnConstructionAndExplicitArrayExport() {
        val colors = IntArray(256) { 0xFF000000.toInt() or it }
        val palette = TerminalColorPalette(indexedColors = colors)

        colors[4] = 0xFFFF0000.toInt()
        val exposed = palette.toIndexedColorsArray()
        exposed[4] = 0xFF00FF00.toInt()

        assertEquals(0xFF000004.toInt(), palette.indexedColor(4))
        assertEquals(0xFF000004.toInt(), palette.toIndexedColorsArray()[4])
    }

    @Test
    fun copiesIndexedColorsIntoCallerOwnedBuffer() {
        val colors = IntArray(256) { 0xFF000000.toInt() or it }
        val palette = TerminalColorPalette(indexedColors = colors)
        val destination = IntArray(300) { -1 }

        palette.copyIndexedColorsInto(destination, offset = 10)

        assertEquals(-1, destination[9])
        assertEquals(colors[0], destination[10])
        assertEquals(colors[255], destination[265])
        assertEquals(-1, destination[266])
    }

    @Test
    fun copyIndexedColorsIntoRejectsTooSmallDestination() {
        val palette = TerminalColorPalette()

        val error =
            assertFailsWith<IllegalArgumentException> {
                palette.copyIndexedColorsInto(IntArray(255))
            }

        assertTrue(error.message!!.contains("insufficient capacity"))
    }

    @Test
    fun inverseSwapsForegroundAndBackground() {
        val palette =
            TerminalColorPalette(
                defaultForeground = 0xFF111111.toInt(),
                defaultBackground = 0xFF222222.toInt(),
            )
        val attrs = TerminalRenderAttrs.pack(inverse = true)

        assertEquals(0xFF222222.toInt(), palette.foreground(attrs))
        assertEquals(0xFF111111.toInt(), palette.background(attrs))
    }

    @Test
    fun faintDoesNotChangePaletteResolvedColor() {
        val palette = TerminalColorPalette(defaultForeground = 0xFF224466.toInt())
        val attrs = TerminalRenderAttrs.pack(faint = true)

        assertEquals(0xFF224466.toInt(), palette.foreground(attrs))
    }

    @Test
    fun rejectsWrongIndexedPaletteSize() {
        assertFailsWith<IllegalArgumentException> {
            TerminalColorPalette(indexedColors = IntArray(16))
        }
    }
}
