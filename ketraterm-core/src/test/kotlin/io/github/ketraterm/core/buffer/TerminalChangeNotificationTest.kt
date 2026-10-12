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
import io.github.ketraterm.core.api.TerminalBuffer
import io.github.ketraterm.core.api.TerminalResponseChannel
import io.github.ketraterm.protocol.DecPrivateMode
import io.github.ketraterm.render.api.TerminalColorPalette
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TerminalChangeNotificationTest {
    @Test
    fun hostRequestedResizeReportRequiresCurrentSubscriptionAndPermission() {
        val buffer = TerminalBuffers.create(10, 3)
        buffer.setWindowSizePixels(100, 60)
        buffer.requestWindowReport(TerminalResponseChannel.WINDOW_REPORT_IN_BAND_SIZE)
        assertEquals("", drain(buffer))
        buffer.setInBandResizeReportingEnabled(true)
        drain(buffer)
        buffer.requestWindowReport(TerminalResponseChannel.WINDOW_REPORT_IN_BAND_SIZE)
        assertEquals("\u001b[48;3;10;60;100t", drain(buffer))
        buffer.setChangeNotificationsAllowed(false)
        buffer.requestWindowReport(TerminalResponseChannel.WINDOW_REPORT_IN_BAND_SIZE)
        assertEquals("", drain(buffer))
        buffer.setChangeNotificationsAllowed(true)
        buffer.setInBandResizeReportingEnabled(false)
        buffer.requestWindowReport(TerminalResponseChannel.WINDOW_REPORT_IN_BAND_SIZE)
        assertEquals("", drain(buffer))
    }

    @Test
    fun `subscriptions default off and both reset operations disable them`() {
        for (hard in listOf(false, true)) {
            val buffer = TerminalBuffers.create(10, 3)
            assertSubscriptions(buffer, 2)
            buffer.setColorSchemeReportingEnabled(true)
            buffer.setInBandResizeReportingEnabled(true)
            assertEquals("\u001B[48;3;10;0;0t", drain(buffer))
            assertSubscriptions(buffer, 1)
            if (hard) buffer.reset() else buffer.softReset()
            assertSubscriptions(buffer, 2)
            buffer.setThemePalette(buffer.palette.copy(isDark = !buffer.palette.isDark))
            buffer.resize(buffer.width + 1, buffer.height)
            assertEquals("", drain(buffer))
        }
    }

    @Test
    fun `only changed host palettes notify and enabling never reports`() {
        val buffer = TerminalBuffers.create(10, 3)
        val dark = buffer.palette
        buffer.setColorSchemeReportingEnabled(true)
        buffer.setColorSchemeReportingEnabled(true)
        assertEquals("", drain(buffer))

        buffer.setThemePalette(dark.copy(defaultForeground = 0xffaabbcc.toInt()))
        assertEquals("\u001B[?997;1n", drain(buffer))
        val light = TerminalColorPalette(isDark = false)
        buffer.setThemePalette(light)
        assertEquals("\u001B[?997;2n", drain(buffer))
        buffer.setThemePalette(light.copy())
        buffer.setDynamicColor(11, 0xff000000.toInt())
        buffer.setPaletteColor(1, 0xff123456.toInt())
        buffer.resetPaletteColors(intArrayOf(1))
        buffer.resetDynamicColor(11)
        buffer.setThemePalette(light)
        assertEquals("", drain(buffer))

        buffer.setPaletteColor(1, 0xff123456.toInt())
        val applicationPalette = buffer.palette
        buffer.setThemePalette(applicationPalette.copy())
        assertSame(applicationPalette, buffer.palette)
        assertEquals("\u001B[?997;2n", drain(buffer))
        buffer.setColorSchemeReportingEnabled(false)
        buffer.setThemePalette(dark)
        assertEquals("", drain(buffer))
    }

    @Test
    fun `every resize enable reports current size and grid plus pixels change once`() {
        val buffer = TerminalBuffers.create(10, 3)
        buffer.setWindowSizePixels(100, 60)
        buffer.setInBandResizeReportingEnabled(true)
        buffer.setInBandResizeReportingEnabled(true)
        assertEquals("\u001B[48;3;10;60;100t".repeat(2), drain(buffer))

        buffer.resize(12, 4, 0, 144, 80)
        assertEquals(12, buffer.width)
        assertEquals(4, buffer.height)
        assertEquals("\u001B[48;4;12;80;144t", drain(buffer))
        buffer.resize(12, 4, 0, 144, 80)
        assertEquals("", drain(buffer))
        buffer.setWindowSizePixels(156, 84)
        assertEquals("\u001B[48;4;12;84;156t", drain(buffer))
        buffer.setWindowSizePixels(156, 84)
        assertEquals("", drain(buffer))

        buffer.resize(12, 4)
        assertEquals("", drain(buffer))
        buffer.setInBandResizeReportingEnabled(true)
        assertEquals("\u001B[48;4;12;84;156t", drain(buffer))
        buffer.resize(13, 4)
        assertEquals("\u001B[48;4;13;0;0t", drain(buffer))
        buffer.resize(13, 4)
        assertEquals("", drain(buffer))
        buffer.setInBandResizeReportingEnabled(false)
        buffer.resize(9, 2, 0, 90, 40)
        assertEquals("", drain(buffer))
    }

    @Test
    fun `same-width DECCOLM preserves known pixels while changed width discards stale pixels`() {
        val buffer = TerminalBuffers.create(132, 3)
        buffer.setWindowSizePixels(1584, 60)
        buffer.setInBandResizeReportingEnabled(true)
        assertEquals("\u001B[48;3;132;60;1584t", drain(buffer))

        buffer.resize(132, 3)
        assertEquals("", drain(buffer))
        buffer.executeDeccolm(132)
        assertEquals("", drain(buffer))
        buffer.requestWindowReport(14)
        buffer.setInBandResizeReportingEnabled(true)
        assertEquals("\u001B[4;60;1584t\u001B[48;3;132;60;1584t", drain(buffer))

        buffer.executeDeccolm(80)
        assertEquals("\u001B[48;3;80;0;0t", drain(buffer))
        buffer.requestWindowReport(14)
        assertEquals("", drain(buffer))
    }

    @Test
    fun `pixel-only resize preserves grid cursor margins history and render generations`() {
        val buffer = DefaultTerminalBuffer(6, 3)
        buffer.writeText("abcdefghijklmno")
        buffer.setScrollRegion(1, 2)
        buffer.positionCursor(2, 1)
        val screen = buffer.getAllAsString()
        val row = buffer.cursorRow
        val col = buffer.cursorCol
        val line = buffer.getLine(0)
        var generation = 0L
        buffer.readRenderFrame { generation = it.frameGeneration }
        buffer.setInBandResizeReportingEnabled(true)
        drain(buffer)

        val anchor = buffer.resize(6, 3, 0, 120, 60)
        assertEquals(Pair(0, buffer.historySize), anchor)
        assertEquals(screen, buffer.getAllAsString())
        assertEquals(row, buffer.cursorRow)
        assertEquals(col, buffer.cursorCol)
        assertSame(line, buffer.getLine(0))
        buffer.readRenderFrame { assertEquals(generation, it.frameGeneration) }
        buffer.queryStatusString("r")
        assertEquals("\u001B[48;3;6;60;120t\u001BP1\$r1;2r\u001B\\", drain(buffer))
    }

    @Test
    fun `invalid resize leaves pixels subscriptions content and queue untouched`() {
        val buffer = TerminalBuffers.create(10, 3)
        buffer.writeText("abc")
        val content = buffer.getAllAsString()
        buffer.setWindowSizePixels(100, 60)
        buffer.setInBandResizeReportingEnabled(true)
        drain(buffer)
        for (dimensions in listOf(
            intArrayOf(0, 3, 100, 60),
            intArrayOf(10, 0, 100, 60),
            intArrayOf(10, 3, -1, 60),
            intArrayOf(10, 3, 100, -1),
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                buffer.resize(dimensions[0], dimensions[1], 0, dimensions[2], dimensions[3])
            }
            assertEquals(10, buffer.width)
            assertEquals(3, buffer.height)
            assertEquals(content, buffer.getAllAsString())
            assertEquals("", drain(buffer))
        }
        buffer.setInBandResizeReportingEnabled(true)
        assertEquals("\u001B[48;3;10;60;100t", drain(buffer))
        buffer.setWindowSizePixels(Int.MAX_VALUE, Int.MAX_VALUE)
        assertEquals("\u001B[48;3;10;2147483647;2147483647t", drain(buffer))
        buffer.setWindowSizePixels(-1, 20)
        assertEquals("\u001B[48;3;10;0;0t", drain(buffer))
    }

    @Test
    fun `permission suppresses notifications without changing subscriptions or queuing catch-up`() {
        val buffer = TerminalBuffers.create(10, 3)
        buffer.setChangeNotificationsAllowed(false)
        buffer.setColorSchemeReportingEnabled(true)
        buffer.setInBandResizeReportingEnabled(true)
        buffer.setThemePalette(TerminalColorPalette(isDark = false))
        buffer.resize(12, 4, 0, 144, 80)
        buffer.setWindowSizePixels(156, 84)
        assertEquals("", drain(buffer))
        assertSubscriptions(buffer, 1)

        buffer.setChangeNotificationsAllowed(true)
        assertEquals("", drain(buffer))
        buffer.setInBandResizeReportingEnabled(true)
        buffer.setThemePalette(buffer.palette.copy(isDark = true))
        assertEquals("\u001B[48;4;12;84;156t\u001B[?997;1n", drain(buffer))
    }

    @Test
    fun `screen transitions and resize preserve global subscriptions and ordered partial output`() {
        val buffer = TerminalBuffers.create(10, 3)
        buffer.setColorSchemeReportingEnabled(true)
        buffer.setInBandResizeReportingEnabled(true)
        drain(buffer)
        buffer.enterAltBuffer()
        buffer.resize(11, 4)
        buffer.setThemePalette(TerminalColorPalette(isDark = false))
        buffer.exitAltBuffer()
        assertSubscriptions(buffer, 1, "\u001B[48;4;11;0;0t\u001B[?997;2n")
        repeat(20) { buffer.setInBandResizeReportingEnabled(true) }
        assertEquals("\u001B[48;4;11;0;0t".repeat(20), drain(buffer))
    }

    private fun assertSubscriptions(
        buffer: TerminalBuffer,
        status: Int,
        prefix: String = "",
    ) {
        buffer.requestModeStatus(DecPrivateMode.COLOR_SCHEME_REPORTING, true)
        buffer.requestModeStatus(DecPrivateMode.IN_BAND_RESIZE_REPORTING, true)
        assertEquals("$prefix\u001B[?2031;$status\$y\u001B[?2048;$status\$y", drain(buffer))
    }

    private fun drain(buffer: TerminalBuffer): String {
        val result = StringBuilder()
        val bytes = ByteArray(3)
        while (buffer.pendingResponseBytes > 0) {
            val count = buffer.readResponseBytes(bytes)
            result.append(bytes.decodeToString(0, count))
        }
        return result.toString()
    }
}
