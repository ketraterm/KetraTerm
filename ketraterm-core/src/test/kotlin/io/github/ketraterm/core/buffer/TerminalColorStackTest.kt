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
import io.github.ketraterm.protocol.TerminalColorRequest
import io.github.ketraterm.render.api.TerminalColorBehavior
import io.github.ketraterm.render.api.TerminalColorPalette
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class TerminalColorStackTest {
    @Test
    fun `nested pushes restore every indexed and named color and dynamic behavior in LIFO order`() {
        val terminal = TerminalBuffers.create(4, 3)
        val saved =
            TerminalColorPalette(
                defaultForeground = 0xff112233.toInt(),
                defaultBackground = 0xff223344.toInt(),
                selectionForeground = 0xff334455.toInt(),
                selectionBackground = 0xff445566.toInt(),
                cursorForeground = 0xff556677.toInt(),
                cursorBackground = 0xff667788.toInt(),
                indexedColors = IntArray(256) { 0xff000000.toInt() or (it * 0x010101) },
            ).copyWithColorBehavior(
                TerminalColorBehavior.DYNAMIC_CURSOR_FOREGROUND or TerminalColorBehavior.DYNAMIC_CURSOR_BACKGROUND or
                    TerminalColorBehavior.OVERRIDE_SELECTION_FOREGROUND or TerminalColorBehavior.DYNAMIC_SELECTION_FOREGROUND or
                    TerminalColorBehavior.OVERRIDE_SELECTION_BACKGROUND or TerminalColorBehavior.DYNAMIC_SELECTION_BACKGROUND,
            )
        terminal.setThemePalette(saved)
        terminal.pushColors()

        val changes = TerminalColorRequest(262)
        changes.clear(byteArrayOf('c'.code.toByte()))
        for (target in 0..TerminalColorRequest.CURSOR_TEXT) {
            changes.add(target, TerminalColorRequest.SET, 0xffabcdef.toInt(), 0, 1)
        }
        terminal.applyColorRequest(changes, false)
        val middle = terminal.palette
        for (index in 0..255) assertEquals(0xffabcdef.toInt(), middle.indexedColor(index))
        assertEquals(0xffabcdef.toInt(), middle.defaultForeground)
        assertEquals(0xffabcdef.toInt(), middle.defaultBackground)
        assertEquals(0xffabcdef.toInt(), middle.selectionForeground)
        assertEquals(0xffabcdef.toInt(), middle.selectionBackground)
        assertEquals(0xffabcdef.toInt(), middle.cursorForeground)
        assertEquals(0xffabcdef.toInt(), middle.cursorBackground)
        assertEquals(
            TerminalColorBehavior.OVERRIDE_SELECTION_FOREGROUND or TerminalColorBehavior.OVERRIDE_SELECTION_BACKGROUND,
            middle.colorBehavior,
        )

        terminal.pushColors()
        terminal.setDynamicColor(10, 0xff987654.toInt())
        terminal.popColors()
        assertSame(middle, terminal.palette)
        terminal.popColors()
        assertSame(saved, terminal.palette)
        terminal.popColors()
        assertSame(saved, terminal.palette)
    }

    @Test
    fun `overflow retains the ten most recent snapshots and discards the oldest`() {
        val terminal = TerminalBuffers.create(4, 3)
        for (value in 1..12) {
            terminal.setDynamicColor(10, 0xff000000.toInt() or value)
            terminal.pushColors()
        }
        terminal.setDynamicColor(10, 0xffabcdef.toInt())

        for (value in 12 downTo 3) {
            terminal.popColors()
            assertEquals(0xff000000.toInt() or value, terminal.palette.defaultForeground)
        }
        val oldestRetained = terminal.palette
        repeat(3) { terminal.popColors() }
        assertSame(oldestRetained, terminal.palette)

        terminal.pushColors()
        terminal.setDynamicColor(10, 0xff123456.toInt())
        terminal.popColors()
        assertSame(oldestRetained, terminal.palette)
    }

    @Test
    fun `push empty pop and equal restoration preserve palette identity and render generations`() {
        val terminal = TerminalBuffers.create(4, 3)
        val saved = terminal.palette
        terminal.pushColors()
        terminal.setDynamicColor(10, 0xff123456.toInt())
        terminal.setDynamicColor(10, saved.defaultForeground)
        val current = terminal.palette
        assertEquals(saved, current)
        assertNotSame(saved, current)
        var frameGeneration = 0L
        val lineGenerations = LongArray(terminal.height)
        terminal.readRenderFrame {
            frameGeneration = it.frameGeneration
            for (row in lineGenerations.indices) lineGenerations[row] = it.lineGeneration(row)
        }

        terminal.popColors()
        terminal.popColors()
        terminal.pushColors()
        terminal.popColors()

        assertSame(current, terminal.palette)
        terminal.readRenderFrame {
            assertEquals(frameGeneration, it.frameGeneration)
            for (row in lineGenerations.indices) assertEquals(lineGenerations[row], it.lineGeneration(row))
        }
    }

    @Test
    fun `effective restoration invalidates every visible row without reporting a host scheme change`() {
        val terminal = TerminalBuffers.create(4, 3)
        terminal.setColorSchemeReportingEnabled(true)
        terminal.pushColors()
        terminal.setDynamicColor(10, 0xff123456.toInt())
        var frameGeneration = 0L
        var structureGeneration = 0L
        val lineGenerations = LongArray(terminal.height)
        terminal.readRenderFrame {
            frameGeneration = it.frameGeneration
            structureGeneration = it.structureGeneration
            for (row in lineGenerations.indices) lineGenerations[row] = it.lineGeneration(row)
        }

        terminal.popColors()

        terminal.readRenderFrame {
            assertNotEquals(frameGeneration, it.frameGeneration)
            assertEquals(structureGeneration, it.structureGeneration)
            for (row in lineGenerations.indices) assertNotEquals(lineGenerations[row], it.lineGeneration(row))
        }
        assertEquals(0, terminal.pendingResponseBytes)
    }

    @Test
    fun `theme replacement preserves saved colors while pop retains current host preferences and reset source`() {
        val terminal = TerminalBuffers.create(4, 3)
        val saved =
            TerminalColorPalette(defaultForeground = 0xff112233.toInt(), cursorBackground = 0xff445566.toInt())
                .copyWithColorBehavior(TerminalColorBehavior.DYNAMIC_CURSOR_BACKGROUND)
        terminal.setThemePalette(saved)
        terminal.pushColors()
        val latest =
            TerminalColorPalette(
                defaultForeground = 0xffabcdef.toInt(),
                cursorBackground = 0xff987654.toInt(),
                indexedColors = IntArray(256) { 0xff654321.toInt() },
                boldAsBright = false,
                isDark = false,
            )
        terminal.setThemePalette(latest)

        terminal.popColors()

        assertEquals(saved.copy(boldAsBright = false, isDark = false), terminal.palette)
        terminal.requestDeviceStatusReport(996, true)
        val reply = ByteArray(terminal.pendingResponseBytes)
        assertEquals(reply.size, terminal.readResponseBytes(reply))
        assertEquals("\u001b[?997;2n", reply.decodeToString())
        terminal.resetDynamicColor(10)
        terminal.resetDynamicColor(12)
        terminal.resetPaletteColors(intArrayOf(0))
        assertEquals(latest.defaultForeground, terminal.palette.defaultForeground)
        assertEquals(latest.cursorBackground, terminal.palette.cursorBackground)
        assertEquals(0, terminal.palette.colorBehavior)
        assertEquals(latest.indexedColor(0), terminal.palette.indexedColor(0))
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `both reset forms clear saved colors while retaining their existing active palette semantics`(hard: Boolean) {
        val terminal = TerminalBuffers.create(4, 3)
        val theme = terminal.palette
        terminal.pushColors()
        terminal.setDynamicColor(10, 0xff112233.toInt())
        terminal.pushColors()
        terminal.setDynamicColor(10, 0xff445566.toInt())
        val current = terminal.palette

        if (hard) terminal.reset() else terminal.softReset()

        assertSame(if (hard) theme else current, terminal.palette)
        terminal.setDynamicColor(10, 0xffabcdef.toInt())
        val afterReset = terminal.palette
        repeat(3) { terminal.popColors() }
        assertSame(afterReset, terminal.palette)
        terminal.pushColors()
        terminal.setDynamicColor(10, 0xff987654.toInt())
        terminal.popColors()
        assertSame(afterReset, terminal.palette)
    }

    @Test
    fun `one color stack survives screen switches and resize`() {
        val terminal = TerminalBuffers.create(4, 3)
        val primary = terminal.palette
        terminal.pushColors()
        terminal.enterAltBuffer()
        terminal.setDynamicColor(10, 0xff112233.toInt())
        val alternate = terminal.palette
        terminal.pushColors()
        terminal.setDynamicColor(10, 0xff445566.toInt())
        terminal.resize(6, 4)

        terminal.popColors()
        assertSame(alternate, terminal.palette)
        terminal.exitAltBuffer()
        terminal.popColors()
        assertSame(primary, terminal.palette)
    }
}
