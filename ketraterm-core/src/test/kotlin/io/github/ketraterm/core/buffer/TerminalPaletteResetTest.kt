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
import io.github.ketraterm.render.api.TerminalColorPalette
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TerminalPaletteResetTest {
    @Test
    fun `selected reset restores latest theme entries and preserves other overrides`() {
        val terminal = TerminalBuffers.create(4, 3)
        val theme = TerminalColorPalette(indexedColors = IntArray(256) { 0xff000000.toInt() or it })
        terminal.setThemePalette(theme)
        terminal.setPaletteColor(0, 0xff123456.toInt())
        terminal.setPaletteColor(1, 0xffabcdef.toInt())
        terminal.setPaletteColor(255, 0xff654321.toInt())
        terminal.setDynamicColor(10, 0xff010203.toInt())
        val overridden = terminal.palette
        val indices = intArrayOf(0, 255, 0, 1)

        terminal.resetPaletteColors(indices, 3)
        indices.fill(1)

        val current = terminal.palette
        assertEquals(theme.indexedColor(0), current.indexedColor(0))
        assertEquals(theme.indexedColor(255), current.indexedColor(255))
        assertEquals(0xffabcdef.toInt(), current.indexedColor(1))
        assertEquals(0xff010203.toInt(), current.defaultForeground)
        assertEquals(theme.defaultBackground, current.defaultBackground)
        assertEquals(theme.cursorBackground, current.cursorBackground)
        assertEquals(0xff123456.toInt(), overridden.indexedColor(0))
        assertEquals(0xff654321.toInt(), overridden.indexedColor(255))
        terminal.readRenderFrame { assertSame(current, it.palette) }
    }

    @Test
    fun `full indexed reset preserves every dynamic override and palette setting`() {
        val terminal = TerminalBuffers.create(4, 3)
        val theme = TerminalColorPalette(isDark = false, boldAsBright = false)
        terminal.setThemePalette(theme)
        for (index in 0..255) terminal.setPaletteColor(index, 0xff102030.toInt())
        terminal.setDynamicColor(10, 0xff010203.toInt())
        terminal.setDynamicColor(11, 0xff040506.toInt())
        terminal.setDynamicColor(12, 0xff070809.toInt())

        terminal.resetPaletteColors(IntArray(0))

        val current = terminal.palette
        assertArrayEquals(theme.toIndexedColorsArray(), current.toIndexedColorsArray())
        assertEquals(0xff010203.toInt(), current.defaultForeground)
        assertEquals(0xff040506.toInt(), current.defaultBackground)
        assertEquals(0xff070809.toInt(), current.cursorBackground)
        assertEquals(theme.selectionForeground, current.selectionForeground)
        assertEquals(theme.selectionBackground, current.selectionBackground)
        assertEquals(theme.cursorForeground, current.cursorForeground)
        assertFalse(current.isDark)
        assertFalse(current.boldAsBright)
    }

    @Test
    fun `dynamic resets restore only requested roles from latest host theme`() {
        val terminal = TerminalBuffers.create(4, 3)
        val theme =
            TerminalColorPalette(
                defaultForeground = 0xff111111.toInt(),
                defaultBackground = 0xff222222.toInt(),
                cursorBackground = 0xff333333.toInt(),
            )
        terminal.setThemePalette(theme)
        terminal.setPaletteColor(3, 0xffabcdef.toInt())
        terminal.setDynamicColor(10, 0xff010203.toInt())
        terminal.setDynamicColor(11, 0xff040506.toInt())
        terminal.setDynamicColor(12, 0xff070809.toInt())

        terminal.resetDynamicColor(10)
        assertEquals(theme.defaultForeground, terminal.palette.defaultForeground)
        assertEquals(0xff040506.toInt(), terminal.palette.defaultBackground)
        assertEquals(0xff070809.toInt(), terminal.palette.cursorBackground)
        terminal.resetDynamicColor(11)
        assertEquals(theme.defaultBackground, terminal.palette.defaultBackground)
        assertEquals(0xff070809.toInt(), terminal.palette.cursorBackground)
        terminal.resetDynamicColor(12)
        assertEquals(theme.cursorBackground, terminal.palette.cursorBackground)
        assertEquals(0xffabcdef.toInt(), terminal.palette.indexedColor(3))

        val latest = terminal.palette.copy()
        terminal.setThemePalette(latest)
        terminal.setPaletteColor(3, 0xff654321.toInt())
        terminal.setDynamicColor(10, 0xff010203.toInt())
        terminal.resetPaletteColors(intArrayOf(3))
        terminal.resetDynamicColor(10)
        assertEquals(latest, terminal.palette)
    }

    @Test
    fun `redundant and invalid resets preserve identity and render generations`() {
        val terminal = TerminalBuffers.create(4, 3)
        val initial = terminal.palette
        var generation = 0L
        terminal.readRenderFrame { generation = it.lineGeneration(0) }

        terminal.resetPaletteColors(intArrayOf(-1, 256, Int.MIN_VALUE, Int.MAX_VALUE))
        terminal.resetPaletteColors(intArrayOf(0, 0, 255))
        terminal.resetPaletteColors(IntArray(0))
        for (target in intArrayOf(10, 11, 12, 9, 13, Int.MAX_VALUE)) terminal.resetDynamicColor(target)

        assertSame(initial, terminal.palette)
        terminal.readRenderFrame { assertEquals(generation, it.lineGeneration(0)) }
        assertThrows(IllegalArgumentException::class.java) { terminal.resetPaletteColors(intArrayOf(1), -1) }
        assertThrows(IllegalArgumentException::class.java) { terminal.resetPaletteColors(intArrayOf(1), 2) }
        assertSame(initial, terminal.palette)
        terminal.readRenderFrame { assertEquals(generation, it.lineGeneration(0)) }
    }
}
