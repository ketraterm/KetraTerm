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
import io.github.ketraterm.protocol.TerminalCapabilityIdentity
import io.github.ketraterm.protocol.keyboard.KittyKeyboardProgressiveFlag
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TerminalResponseChannelTest {
    @Test
    fun `response queue starts empty and supports zero length reads`() {
        val buffer = TerminalBuffers.create(width = 10, height = 5)
        val destination = ByteArray(4)

        assertAll(
            { assertEquals(0, buffer.pendingResponseBytes) },
            { assertEquals(0, buffer.readResponseBytes(destination, length = 0)) },
        )
    }

    @Test
    fun `response reader defaults are available through response channel type`() {
        val channel: TerminalResponseChannel = TerminalBuffers.create(width = 10, height = 5)
        val destination = ByteArray(8)

        channel.requestDeviceStatusReport(mode = 5, decPrivate = false)

        val count = channel.readResponseBytes(destination)

        assertEquals("\u001B[0n", destination.decodeToString(0, count))
        assertEquals(0, channel.readResponseBytes(destination, length = 0))
    }

    @Test
    fun `DSR operating status queues OK response and supports partial reads`() {
        val buffer = TerminalBuffers.create(width = 10, height = 5)
        val destination = ByteArray(8)

        buffer.requestDeviceStatusReport(mode = 5, decPrivate = false)

        val first = buffer.readResponseBytes(destination, offset = 0, length = 2)
        val second = buffer.readResponseBytes(destination, offset = 2, length = destination.size - 2)

        assertAll(
            { assertEquals(2, first) },
            { assertEquals(2, second) },
            { assertEquals("\u001B[0n", destination.decodeToString(0, first + second)) },
            { assertEquals(0, buffer.pendingResponseBytes) },
        )
    }

    @Test
    fun `CPR queues one-based cursor position`() {
        val buffer = TerminalBuffers.create(width = 120, height = 50)

        buffer.positionCursor(col = 119, row = 49)
        buffer.requestDeviceStatusReport(mode = 6, decPrivate = false)

        assertEquals("\u001B[50;120R", drain(buffer))
    }

    @Test
    fun `DEC private CPR queues private cursor position report`() {
        val buffer = TerminalBuffers.create(width = 10, height = 5)

        buffer.positionCursor(col = 2, row = 1)
        buffer.requestDeviceStatusReport(mode = 6, decPrivate = true)

        assertEquals("\u001B[?2;3R", drain(buffer))
    }

    @Test
    fun `DA queues conservative primary and secondary identities while DA3 stays silent`() {
        val buffer = TerminalBuffers.create(width = 10, height = 5)

        buffer.requestDeviceAttributes(TerminalResponseChannel.DEVICE_ATTRIBUTES_PRIMARY, parameter = 0)
        buffer.requestDeviceAttributes(TerminalResponseChannel.DEVICE_ATTRIBUTES_SECONDARY, parameter = 0)
        buffer.requestDeviceAttributes(TerminalResponseChannel.DEVICE_ATTRIBUTES_TERTIARY, parameter = 0)

        assertEquals("\u001B[?64;1;6;22;28c\u001B[>41;0;0c", drain(buffer))
    }

    @Test
    fun `DA response bytes match the shared capability identity contract`() {
        val buffer = TerminalBuffers.create(width = 10, height = 5)

        buffer.requestDeviceAttributes(TerminalResponseChannel.DEVICE_ATTRIBUTES_PRIMARY, parameter = 0)
        buffer.requestDeviceAttributes(TerminalResponseChannel.DEVICE_ATTRIBUTES_SECONDARY, parameter = 0)

        assertEquals(
            "\u001B[?${TerminalCapabilityIdentity.PRIMARY_DA_TERMINAL_CLASS};" +
                "${TerminalCapabilityIdentity.PRIMARY_DA_132_COLUMNS};" +
                "${TerminalCapabilityIdentity.PRIMARY_DA_SELECTIVE_ERASE};" +
                "${TerminalCapabilityIdentity.PRIMARY_DA_ANSI_COLOR};" +
                "${TerminalCapabilityIdentity.PRIMARY_DA_RECTANGULAR_EDITING}c" +
                "\u001B[>${TerminalCapabilityIdentity.SECONDARY_DA_TERMINAL_ID};" +
                "${TerminalCapabilityIdentity.SECONDARY_DA_VERSION};" +
                "${TerminalCapabilityIdentity.SECONDARY_DA_OPTIONS}c",
            drain(buffer),
        )
    }

    @Test
    fun `Kitty keyboard query reports every active encoder-supported progressive flag`() {
        val buffer = TerminalBuffers.create(width = 10, height = 5)

        buffer.setKittyKeyboardFlags(
            KittyKeyboardProgressiveFlag.DISAMBIGUATE_ESCAPE_CODES or
                KittyKeyboardProgressiveFlag.REPORT_ALL_KEYS_AS_ESCAPE_CODES or
                KittyKeyboardProgressiveFlag.REPORT_EVENT_TYPES,
        )
        buffer.requestKittyKeyboardFlags()

        assertEquals("\u001B[?11u", drain(buffer))
    }

    @Test
    fun `xterm modify-other-keys query reports only the allowlisted resource and preserves explicit disable`() {
        val buffer = TerminalBuffers.create(width = 10, height = 5)
        buffer.setModifyOtherKeysMode(-1)

        buffer.requestKeyModifierOption(4)
        buffer.requestKeyModifierOption(1)

        assertEquals("\u001B[>4;-1m", drain(buffer))
    }

    @Test
    fun `unsupported status reports and DA parameters stay silent`() {
        val buffer = TerminalBuffers.create(width = 10, height = 5)

        buffer.requestDeviceStatusReport(mode = 0, decPrivate = false)
        buffer.requestDeviceStatusReport(mode = 5, decPrivate = true)
        buffer.requestDeviceAttributes(TerminalResponseChannel.DEVICE_ATTRIBUTES_PRIMARY, parameter = 1)

        assertEquals(0, buffer.pendingResponseBytes)
    }

    @Test
    fun `DECRQCRA returns VT420 checksum for visible cells and supported video attributes`() {
        val buffer = TerminalBuffers.create(width = 3, height = 1)

        buffer.setPenAttributes(
            fg = 0,
            bg = 0,
            bold = true,
            underlineStyle = io.github.ketraterm.core.model.UnderlineStyle.SINGLE,
            blink = true,
            inverse = true,
            conceal = true,
        )
        buffer.setSelectiveEraseProtection(true)
        buffer.writeCodepoint('A'.code)

        buffer.requestRectangleChecksum(requestId = 42, page = 1, top = 0, left = 0, bottom = 0, right = 0)

        assertEquals("\u001BP42!~FEC3\u001B\\", drain(buffer))
    }

    @Test
    fun `DECRQCRA stays silent for unsupported pages and inverted rectangles`() {
        val buffer = TerminalBuffers.create(width = 3, height = 1)
        buffer.writeCodepoint('A'.code)

        buffer.requestRectangleChecksum(requestId = 1, page = 2, top = 1, left = 1, bottom = 1, right = 1)
        buffer.requestRectangleChecksum(requestId = 1, page = 1, top = 2, left = 1, bottom = 1, right = 1)

        assertEquals(0, buffer.pendingResponseBytes)
    }

    @Test
    fun `grid size report queues current terminal dimensions`() {
        val buffer = TerminalBuffers.create(width = 120, height = 40)

        buffer.requestWindowReport(TerminalResponseChannel.WINDOW_REPORT_GRID_CELLS)

        assertEquals("\u001B[8;40;120t", drain(buffer))
    }

    @Test
    fun `pixel size report is silent until host provides positive pixel dimensions`() {
        val buffer = TerminalBuffers.create(width = 10, height = 5)

        buffer.requestWindowReport(TerminalResponseChannel.WINDOW_REPORT_PIXELS)

        assertEquals(0, buffer.pendingResponseBytes)

        buffer.setWindowSizePixels(width = 800, height = 400)
        buffer.requestWindowReport(TerminalResponseChannel.WINDOW_REPORT_PIXELS)

        assertEquals("\u001B[4;400;800t", drain(buffer))
    }

    @Test
    fun `unsupported window reports stay silent`() {
        val buffer = TerminalBuffers.create(width = 10, height = 5)

        buffer.requestWindowReport(8)
        buffer.requestWindowReport(999)

        assertEquals(0, buffer.pendingResponseBytes)
    }

    @Test
    fun `window state report returns normal or minimized based on host state`() {
        val buffer = TerminalBuffers.create(width = 10, height = 5)

        // Default: normal (not minimized)
        buffer.requestWindowReport(TerminalResponseChannel.WINDOW_REPORT_STATE)
        assertEquals("\u001B[1t", drain(buffer))

        // Update to minimized
        buffer.setWindowMinimized(true)
        buffer.requestWindowReport(TerminalResponseChannel.WINDOW_REPORT_STATE)
        assertEquals("\u001B[2t", drain(buffer))

        // Update back to normal
        buffer.setWindowMinimized(false)
        buffer.requestWindowReport(TerminalResponseChannel.WINDOW_REPORT_STATE)
        assertEquals("\u001B[1t", drain(buffer))
    }

    @Test
    fun `screen size report returns grid dimensions`() {
        val buffer = TerminalBuffers.create(width = 120, height = 40)

        buffer.requestWindowReport(TerminalResponseChannel.WINDOW_REPORT_SCREEN_SIZE)

        assertEquals("\u001B[9;40;120t", drain(buffer))
    }

    @Test
    fun `hard reset preserves responses generated before reset`() {
        val buffer = TerminalBuffers.create(width = 10, height = 5)

        buffer.requestDeviceStatusReport(mode = 5, decPrivate = false)
        buffer.reset()

        assertEquals("\u001B[0n", drain(buffer))
    }

    @Test
    fun `queryPaletteColor formats color in 16-bit hex and queues response`() {
        val buffer = TerminalBuffers.create(width = 10, height = 5)

        buffer.queryPaletteColor(1)

        assertEquals("\u001B]4;1;rgb:8080/0000/0000\u001B\\", drain(buffer))
    }

    @Test
    fun `queryDynamicColor formats target in 16-bit hex and queues response`() {
        val buffer = TerminalBuffers.create(width = 10, height = 5)

        buffer.queryDynamicColor(10)

        assertEquals("\u001B]10;rgb:ffff/ffff/ffff\u001B\\", drain(buffer))
    }

    @Test
    fun `queryStatusString returns valid or invalid status string responses`() {
        val buffer = TerminalBuffers.create(width = 80, height = 24)

        // Valid SGR (default pen)
        buffer.queryStatusString("m")
        assertEquals("\u001BP1\$r0m\u001B\\", drain(buffer))

        // Valid margins
        buffer.queryStatusString("r")
        assertEquals("\u001BP1\$r1;24r\u001B\\", drain(buffer))

        buffer.queryStatusString("s")
        assertEquals("\u001BP1\$r1;80s\u001B\\", drain(buffer))

        // Valid cursor style (default is blinking block -> 1)
        buffer.queryStatusString("q")
        assertEquals("\u001BP1\$r1 q\u001B\\", drain(buffer))

        // Invalid query
        buffer.queryStatusString("invalid")
        assertEquals("\u001BP0\$rinvalid\u001B\\", drain(buffer))
    }

    @Test
    fun `queryTerminfo returns matching capability or failure responses`() {
        val buffer = TerminalBuffers.create(width = 80, height = 24)

        // Querying colors ("Co" -> hex 436f) and name ("TN" -> hex 544e)
        buffer.queryTerminfo("436f;544e")
        assertEquals("\u001BP1+r436f=323536;544e=787465726d2d323536636f6c6f72\u001B\\", drain(buffer))

        // Querying boolean RGB ("RGB" -> hex 524742)
        buffer.queryTerminfo("524742")
        assertEquals("\u001BP1+r524742\u001B\\", drain(buffer))

        // Querying unsupported
        buffer.queryTerminfo("invalid")
        assertEquals("\u001BP0+r\u001B\\", drain(buffer))
    }

    @Test
    fun `queryTerminfo aliases resolve to the shared capability identity contract`() {
        val buffer = TerminalBuffers.create(width = 80, height = 24)

        // colors; name; Tc
        buffer.queryTerminfo("636f6c6f7273;6e616d65;5463")

        assertEquals(
            "\u001BP1+r636f6c6f7273=323536;6e616d65=787465726d2d323536636f6c6f72;5463\u001B\\",
            drain(buffer),
        )
        assertEquals("256", TerminalCapabilityIdentity.TERMINFO_COLOR_COUNT)
        assertEquals("xterm-256color", TerminalCapabilityIdentity.TERM_NAME)
    }

    private fun drain(buffer: TerminalBuffer): String {
        val destination = ByteArray(128)
        val count = buffer.readResponseBytes(destination)
        return destination.decodeToString(0, count)
    }
}
