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
package com.gagik.core.buffer

import com.gagik.core.TerminalBuffers
import com.gagik.core.api.TerminalBufferApi
import com.gagik.core.api.TerminalResponseChannel
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

        assertEquals("\u001B[?1;2c\u001B[>0;0;0c", drain(buffer))
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
    fun `hard reset clears pending host responses`() {
        val buffer = TerminalBuffers.create(width = 10, height = 5)

        buffer.requestDeviceStatusReport(mode = 5, decPrivate = false)
        buffer.reset()

        assertEquals(0, buffer.pendingResponseBytes)
    }

    private fun drain(buffer: TerminalBufferApi): String {
        val destination = ByteArray(128)
        val count = buffer.readResponseBytes(destination)
        return destination.decodeToString(0, count)
    }
}
