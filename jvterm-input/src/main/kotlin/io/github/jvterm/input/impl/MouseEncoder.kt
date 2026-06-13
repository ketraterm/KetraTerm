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
package io.github.jvterm.input.impl

import io.github.jvterm.core.api.TerminalInputState
import io.github.jvterm.input.event.TerminalModifiers
import io.github.jvterm.input.event.TerminalMouseButton
import io.github.jvterm.input.event.TerminalMouseEvent
import io.github.jvterm.input.event.TerminalMouseEventType
import io.github.jvterm.input.policy.MouseCoordinateLimitPolicy
import io.github.jvterm.input.policy.TerminalInputPolicy
import io.github.jvterm.protocol.host.TerminalHostOutput
import io.github.jvterm.protocol.mouse.MouseEncodingMode
import io.github.jvterm.protocol.mouse.MouseTrackingMode

internal class MouseEncoder(
    private val output: TerminalHostOutput,
    private val scratch: InputScratchBuffer,
    private val policy: TerminalInputPolicy,
) {
    fun encode(
        event: TerminalMouseEvent,
        modeBits: Long,
    ) {
        val trackingMode = TerminalInputState.mouseTrackingMode(modeBits)
        if (!shouldReport(event, trackingMode)) {
            return
        }

        when (TerminalInputState.mouseEncodingMode(modeBits)) {
            MouseEncodingMode.SGR -> encodeSgr(event)

            MouseEncodingMode.DEFAULT -> encodeLegacyDefault(event)

            MouseEncodingMode.UTF8 -> encodeUtf8Extended(event)

            MouseEncodingMode.URXVT -> encodeUrxvt(event)
        }
    }

    private fun shouldReport(
        event: TerminalMouseEvent,
        trackingMode: Int,
    ): Boolean =
        when (trackingMode) {
            MouseTrackingMode.NONE -> false

            MouseTrackingMode.X10 ->
                event.type == TerminalMouseEventType.PRESS

            MouseTrackingMode.NORMAL ->
                event.type == TerminalMouseEventType.PRESS ||
                    event.type == TerminalMouseEventType.RELEASE ||
                    event.type == TerminalMouseEventType.WHEEL

            MouseTrackingMode.BUTTON_EVENT ->
                event.type == TerminalMouseEventType.PRESS ||
                    event.type == TerminalMouseEventType.RELEASE ||
                    event.type == TerminalMouseEventType.WHEEL ||
                    (
                        event.type == TerminalMouseEventType.MOTION &&
                            event.button != TerminalMouseButton.NONE
                    )

            MouseTrackingMode.ANY_EVENT ->
                event.type == TerminalMouseEventType.PRESS ||
                    event.type == TerminalMouseEventType.RELEASE ||
                    event.type == TerminalMouseEventType.WHEEL ||
                    event.type == TerminalMouseEventType.MOTION

            else -> false
        }

    private fun encodeSgr(event: TerminalMouseEvent) {
        val x = oneBasedCoordinate(event.column)
        val y = oneBasedCoordinate(event.row)

        val cb = mouseButtonCode(event, legacyRelease = false)
        if (cb < 0) {
            return
        }

        scratch.clear()
        scratch.appendByte(ESC)
        scratch.appendByte('['.code)
        scratch.appendByte('<'.code)
        scratch.appendDecimal(cb)
        scratch.appendByte(';'.code)
        scratch.appendDecimal(x)
        scratch.appendByte(';'.code)
        scratch.appendDecimal(y)
        scratch.appendByte(
            if (event.type == TerminalMouseEventType.RELEASE) 'm'.code else 'M'.code,
        )
        scratch.writeTo(output)
    }

    private fun encodeUtf8Extended(event: TerminalMouseEvent) {
        val x = oneBasedCoordinate(event.column)
        val y = oneBasedCoordinate(event.row)

        if (x > UTF8_MAX_COORDINATE || y > UTF8_MAX_COORDINATE) {
            return
        }

        val cb = mouseButtonCode(event, legacyRelease = true)
        if (cb < 0) {
            return
        }

        val encodedButton = 32 + cb
        val encodedX = 32 + x
        val encodedY = 32 + y

        scratch.clear()
        scratch.appendByte(ESC)
        scratch.appendByte('['.code)
        scratch.appendByte('M'.code)
        appendUtf8Scalar(encodedButton)
        appendUtf8Scalar(encodedX)
        appendUtf8Scalar(encodedY)
        scratch.writeTo(output)
    }

    private fun encodeUrxvt(event: TerminalMouseEvent) {
        val x = oneBasedCoordinate(event.column)
        val y = oneBasedCoordinate(event.row)

        val cb = mouseButtonCode(event, legacyRelease = true)
        if (cb < 0) {
            return
        }

        scratch.clear()
        scratch.appendByte(ESC)
        scratch.appendByte('['.code)
        scratch.appendDecimal(32 + cb)
        scratch.appendByte(';'.code)
        scratch.appendDecimal(x)
        scratch.appendByte(';'.code)
        scratch.appendDecimal(y)
        scratch.appendByte('M'.code)
        scratch.writeTo(output)
    }

    private fun encodeLegacyDefault(event: TerminalMouseEvent) {
        val x = oneBasedCoordinate(event.column)
        val y = oneBasedCoordinate(event.row)

        val boundedX = boundedLegacyCoordinate(x) ?: return
        val boundedY = boundedLegacyCoordinate(y) ?: return

        val cb = mouseButtonCode(event, legacyRelease = true)
        if (cb < 0) {
            return
        }

        val encodedButton = 32 + cb
        val encodedX = 32 + boundedX
        val encodedY = 32 + boundedY

        if (encodedButton !in 0..255 || encodedX !in 0..255 || encodedY !in 0..255) {
            return
        }

        scratch.clear()
        scratch.appendByte(ESC)
        scratch.appendByte('['.code)
        scratch.appendByte('M'.code)
        scratch.appendByte(encodedButton)
        scratch.appendByte(encodedX)
        scratch.appendByte(encodedY)
        scratch.writeTo(output)
    }

    private fun oneBasedCoordinate(zeroBased: Int): Int = zeroBased + 1

    private fun boundedLegacyCoordinate(oneBased: Int): Int? {
        if (oneBased <= LEGACY_MAX_COORDINATE) {
            return oneBased
        }

        return when (policy.mouseCoordinateLimitPolicy) {
            MouseCoordinateLimitPolicy.SUPPRESS_OUT_OF_RANGE -> null
            MouseCoordinateLimitPolicy.CLAMP_TO_MAX -> LEGACY_MAX_COORDINATE
        }
    }

    private fun mouseButtonCode(
        event: TerminalMouseEvent,
        legacyRelease: Boolean,
    ): Int {
        var code =
            when (event.type) {
                TerminalMouseEventType.RELEASE -> {
                    if (legacyRelease) {
                        3
                    } else {
                        baseButtonCode(event.button)
                    }
                }

                TerminalMouseEventType.WHEEL -> {
                    when (event.button) {
                        TerminalMouseButton.WHEEL_UP -> 64
                        TerminalMouseButton.WHEEL_DOWN -> 65
                        TerminalMouseButton.WHEEL_LEFT -> 66
                        TerminalMouseButton.WHEEL_RIGHT -> 67
                        else -> return -1
                    }
                }

                TerminalMouseEventType.PRESS,
                TerminalMouseEventType.MOTION,
                -> {
                    if (event.button == TerminalMouseButton.NONE) {
                        if (event.type == TerminalMouseEventType.MOTION) {
                            3
                        } else {
                            return -1
                        }
                    } else {
                        baseButtonCode(event.button)
                    }
                }
            }

        if (code < 0) {
            return -1
        }

        if (event.type == TerminalMouseEventType.MOTION) {
            code += 32
        }

        if (TerminalModifiers.hasShift(event.modifiers)) code += 4
        if (TerminalModifiers.hasAlt(event.modifiers)) code += 8
        if (TerminalModifiers.hasCtrl(event.modifiers)) code += 16

        return code
    }

    private fun baseButtonCode(button: TerminalMouseButton): Int =
        when (button) {
            TerminalMouseButton.LEFT -> 0
            TerminalMouseButton.MIDDLE -> 1
            TerminalMouseButton.RIGHT -> 2
            else -> -1
        }

    private fun appendUtf8Scalar(codepoint: Int) {
        when {
            codepoint <= 0x7f -> scratch.appendByte(codepoint)

            codepoint <= 0x7ff -> {
                scratch.appendByte(0xc0 or (codepoint shr 6))
                scratch.appendByte(0x80 or (codepoint and 0x3f))
            }

            codepoint <= 0xffff -> {
                scratch.appendByte(0xe0 or (codepoint shr 12))
                scratch.appendByte(0x80 or ((codepoint shr 6) and 0x3f))
                scratch.appendByte(0x80 or (codepoint and 0x3f))
            }

            else -> {
                scratch.appendByte(0xf0 or (codepoint shr 18))
                scratch.appendByte(0x80 or ((codepoint shr 12) and 0x3f))
                scratch.appendByte(0x80 or ((codepoint shr 6) and 0x3f))
                scratch.appendByte(0x80 or (codepoint and 0x3f))
            }
        }
    }

    private companion object {
        private const val ESC: Int = 0x1b
        private const val LEGACY_MAX_COORDINATE: Int = 223
        private const val UTF8_MAX_COORDINATE: Int = 2015
    }
}
