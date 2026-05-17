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

import com.gagik.core.api.TerminalResponseChannel
import com.gagik.core.state.TerminalState
import com.gagik.terminal.protocol.ControlCode

internal class TerminalResponseChannelImpl(
    private val state: TerminalState,
) : TerminalResponseChannel {
    override val pendingResponseBytes: Int
        get() = state.hostResponses.pendingByteCount

    override fun readResponseBytes(
        dst: ByteArray,
        offset: Int,
        length: Int,
    ): Int = state.hostResponses.read(dst, offset, length)

    override fun clearResponseBytes() {
        state.hostResponses.clear()
    }

    override fun requestDeviceStatusReport(
        mode: Int,
        decPrivate: Boolean,
    ) {
        when {
            !decPrivate && mode == 5 -> enqueueOperatingStatusReport()
            mode == 6 -> enqueueCursorPositionReport(decPrivate)
        }
    }

    override fun requestDeviceAttributes(
        kind: Int,
        parameter: Int,
    ) {
        if (parameter != 0) return

        when (kind) {
            TerminalResponseChannel.DEVICE_ATTRIBUTES_PRIMARY -> {
                // Conservative VT100-with-advanced-video identity. Avoid overclaiming xterm.
                enqueuePrimaryDeviceAttributes()
            }
            TerminalResponseChannel.DEVICE_ATTRIBUTES_SECONDARY -> {
                // Generic versionless secondary DA. Avoid leaking product/version identity.
                enqueueSecondaryDeviceAttributes()
            }
            TerminalResponseChannel.DEVICE_ATTRIBUTES_TERTIARY -> {
                // DA3 can expose a stable terminal unit id. Keep it silent until policy exists.
            }
        }
    }

    override fun setWindowSizePixels(
        width: Int,
        height: Int,
    ) {
        if (width <= 0 || height <= 0) {
            state.windowPixelWidth = 0
            state.windowPixelHeight = 0
            return
        }

        state.windowPixelWidth = width
        state.windowPixelHeight = height
    }

    override fun requestWindowReport(mode: Int) {
        when (mode) {
            TerminalResponseChannel.WINDOW_REPORT_PIXELS -> {
                if (state.windowPixelWidth > 0 && state.windowPixelHeight > 0) {
                    enqueueWindowReport(
                        reportType = 4,
                        height = state.windowPixelHeight,
                        width = state.windowPixelWidth,
                    )
                }
            }
            TerminalResponseChannel.WINDOW_REPORT_GRID_CELLS ->
                enqueueWindowReport(
                    reportType = 8,
                    height = state.dimensions.height,
                    width = state.dimensions.width,
                )
        }
    }

    private fun enqueueOperatingStatusReport() {
        enqueueCsiPrefix()
        state.hostResponses.enqueueByte('0'.code)
        state.hostResponses.enqueueByte('n'.code)
    }

    private fun enqueuePrimaryDeviceAttributes() {
        enqueueCsiPrefix()
        state.hostResponses.enqueueByte('?'.code)
        state.hostResponses.enqueueByte('1'.code)
        state.hostResponses.enqueueByte(';'.code)
        state.hostResponses.enqueueByte('2'.code)
        state.hostResponses.enqueueByte('c'.code)
    }

    private fun enqueueSecondaryDeviceAttributes() {
        enqueueCsiPrefix()
        state.hostResponses.enqueueByte('>'.code)
        state.hostResponses.enqueueByte('0'.code)
        state.hostResponses.enqueueByte(';'.code)
        state.hostResponses.enqueueByte('0'.code)
        state.hostResponses.enqueueByte(';'.code)
        state.hostResponses.enqueueByte('0'.code)
        state.hostResponses.enqueueByte('c'.code)
    }

    private fun enqueueCursorPositionReport(decPrivate: Boolean) {
        enqueueCsiPrefix()
        if (decPrivate) {
            state.hostResponses.enqueueByte('?'.code)
        }
        state.hostResponses.enqueuePositiveDecimal(state.cursor.row + 1)
        state.hostResponses.enqueueByte(';'.code)
        state.hostResponses.enqueuePositiveDecimal(state.cursor.col + 1)
        state.hostResponses.enqueueByte('R'.code)
    }

    private fun enqueueWindowReport(
        reportType: Int,
        height: Int,
        width: Int,
    ) {
        enqueueCsiPrefix()
        state.hostResponses.enqueuePositiveDecimal(reportType)
        state.hostResponses.enqueueByte(';'.code)
        state.hostResponses.enqueuePositiveDecimal(height)
        state.hostResponses.enqueueByte(';'.code)
        state.hostResponses.enqueuePositiveDecimal(width)
        state.hostResponses.enqueueByte('t'.code)
    }

    private fun enqueueCsiPrefix() {
        state.hostResponses.enqueueByte(ControlCode.ESC)
        state.hostResponses.enqueueByte('['.code)
    }
}
