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

import io.github.jvterm.core.api.TerminalModeBits
import io.github.jvterm.input.event.TerminalModifiers
import io.github.jvterm.input.event.TerminalMouseButton
import io.github.jvterm.input.event.TerminalMouseEvent
import io.github.jvterm.input.event.TerminalMouseEventType
import io.github.jvterm.input.policy.MouseCoordinateLimitPolicy
import io.github.jvterm.input.policy.TerminalInputPolicy
import io.github.jvterm.protocol.host.TerminalHostOutput
import io.github.jvterm.protocol.mouse.MouseEncodingMode
import io.github.jvterm.protocol.mouse.MouseTrackingMode
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test

class MouseEncoderTest {
    @Test
    fun `tracking NONE suppresses all events`() {
        assertMouseBytes(bytes(), press(), tracking = MouseTrackingMode.NONE)
        assertMouseBytes(bytes(), release(), tracking = MouseTrackingMode.NONE)
        assertMouseBytes(bytes(), motion(TerminalMouseButton.LEFT), tracking = MouseTrackingMode.NONE)
        assertMouseBytes(bytes(), wheel(TerminalMouseButton.WHEEL_UP), tracking = MouseTrackingMode.NONE)
    }

    @Test
    fun `X10 emits press only`() {
        assertMouseBytes(esc("[<0;1;1M"), press(), tracking = MouseTrackingMode.X10)
        assertMouseBytes(bytes(), release(), tracking = MouseTrackingMode.X10)
        assertMouseBytes(bytes(), motion(TerminalMouseButton.LEFT), tracking = MouseTrackingMode.X10)
        assertMouseBytes(bytes(), wheel(TerminalMouseButton.WHEEL_UP), tracking = MouseTrackingMode.X10)
    }

    @Test
    fun `NORMAL emits press release and wheel but suppresses motion`() {
        assertMouseBytes(esc("[<0;1;1M"), press(), tracking = MouseTrackingMode.NORMAL)
        assertMouseBytes(esc("[<0;1;1m"), release(), tracking = MouseTrackingMode.NORMAL)
        assertMouseBytes(esc("[<64;1;1M"), wheel(TerminalMouseButton.WHEEL_UP), tracking = MouseTrackingMode.NORMAL)
        assertMouseBytes(bytes(), motion(TerminalMouseButton.LEFT), tracking = MouseTrackingMode.NORMAL)
    }

    @Test
    fun `BUTTON_EVENT emits button motion and suppresses no-button motion`() {
        assertMouseBytes(esc("[<32;1;1M"), motion(TerminalMouseButton.LEFT), tracking = MouseTrackingMode.BUTTON_EVENT)
        assertMouseBytes(bytes(), motion(TerminalMouseButton.NONE), tracking = MouseTrackingMode.BUTTON_EVENT)
    }

    @Test
    fun `ANY_EVENT emits no-button motion`() {
        assertMouseBytes(esc("[<35;1;1M"), motion(TerminalMouseButton.NONE), tracking = MouseTrackingMode.ANY_EVENT)
    }

    @Test
    fun `unknown tracking mode suppresses all events`() {
        assertMouseBytes(bytes(), press(), tracking = 15)
    }

    @Test
    fun `tracking suppression is independent of mouse encoding mode`() {
        assertTrackingMatrix(MouseEncodingMode.DEFAULT, esc("[M") + bytes(32, 33, 33))
        assertTrackingMatrix(MouseEncodingMode.UTF8, esc("[M") + bytes(32, 33, 33))
        assertTrackingMatrix(MouseEncodingMode.SGR, esc("[<0;1;1M"))
        assertTrackingMatrix(MouseEncodingMode.URXVT, esc("[32;1;1M"))
    }

    @Test
    fun `encodes SGR button press and release with preserved release button identity`() {
        assertMouseBytes(esc("[<0;1;1M"), press(TerminalMouseButton.LEFT, column = 0, row = 0))
        assertMouseBytes(esc("[<0;1;1m"), release(TerminalMouseButton.LEFT, column = 0, row = 0))
        assertMouseBytes(esc("[<1;3;4m"), release(TerminalMouseButton.MIDDLE, column = 2, row = 3))
        assertMouseBytes(esc("[<2;3;4m"), release(TerminalMouseButton.RIGHT, column = 2, row = 3))
        assertMouseBytes(bytes(), release(TerminalMouseButton.NONE))
    }

    @Test
    fun `encodes SGR wheel buttons`() {
        assertMouseBytes(esc("[<64;3;4M"), wheel(TerminalMouseButton.WHEEL_UP, column = 2, row = 3))
        assertMouseBytes(esc("[<65;3;4M"), wheel(TerminalMouseButton.WHEEL_DOWN, column = 2, row = 3))
        assertMouseBytes(esc("[<66;3;4M"), wheel(TerminalMouseButton.WHEEL_LEFT, column = 2, row = 3))
        assertMouseBytes(esc("[<67;3;4M"), wheel(TerminalMouseButton.WHEEL_RIGHT, column = 2, row = 3))
    }

    @Test
    fun `encodes SGR motion buttons`() {
        assertMouseBytes(esc("[<32;3;4M"), motion(TerminalMouseButton.LEFT, column = 2, row = 3))
        assertMouseBytes(esc("[<35;3;4M"), motion(TerminalMouseButton.NONE, column = 2, row = 3))
    }

    @Test
    fun `encodes SGR modifiers and ignores Meta`() {
        assertMouseBytes(esc("[<4;1;1M"), press(modifiers = TerminalModifiers.SHIFT))
        assertMouseBytes(esc("[<8;1;1M"), press(modifiers = TerminalModifiers.ALT))
        assertMouseBytes(esc("[<16;1;1M"), press(modifiers = TerminalModifiers.CTRL))
        assertMouseBytes(
            esc("[<28;1;1M"),
            press(modifiers = TerminalModifiers.SHIFT or TerminalModifiers.ALT or TerminalModifiers.CTRL),
        )
        assertMouseBytes(esc("[<0;1;1M"), press(modifiers = TerminalModifiers.META))
    }

    @Test
    fun `encodes large SGR coordinates as decimal without legacy limit`() {
        assertMouseBytes(esc("[<0;224;225M"), press(column = 223, row = 224))
    }

    @Test
    fun `encodes legacy default buttons and releases`() {
        assertMouseBytes(esc("[M") + bytes(32, 33, 33), press(), encoding = MouseEncodingMode.DEFAULT)
        assertMouseBytes(
            esc("[M") + bytes(33, 33, 33),
            press(TerminalMouseButton.MIDDLE),
            encoding = MouseEncodingMode.DEFAULT,
        )
        assertMouseBytes(
            esc("[M") + bytes(34, 33, 33),
            press(TerminalMouseButton.RIGHT),
            encoding = MouseEncodingMode.DEFAULT,
        )
        assertMouseBytes(
            esc("[M") + bytes(35, 33, 33),
            release(TerminalMouseButton.LEFT),
            encoding = MouseEncodingMode.DEFAULT,
        )
        assertMouseBytes(
            esc("[M") + bytes(35, 33, 33),
            release(TerminalMouseButton.MIDDLE),
            encoding = MouseEncodingMode.DEFAULT,
        )
        assertMouseBytes(
            esc("[M") + bytes(35, 33, 33),
            release(TerminalMouseButton.RIGHT),
            encoding = MouseEncodingMode.DEFAULT,
        )
    }

    @Test
    fun `encodes legacy default motion and wheel codes`() {
        assertMouseBytes(
            esc("[M") + bytes(64, 33, 33),
            motion(TerminalMouseButton.LEFT),
            tracking = MouseTrackingMode.BUTTON_EVENT,
            encoding = MouseEncodingMode.DEFAULT,
        )
        assertMouseBytes(
            esc("[M") + bytes(96, 33, 33),
            wheel(TerminalMouseButton.WHEEL_UP),
            tracking = MouseTrackingMode.NORMAL,
            encoding = MouseEncodingMode.DEFAULT,
        )
        assertMouseBytes(
            esc("[M") + bytes(97, 33, 33),
            wheel(TerminalMouseButton.WHEEL_DOWN),
            tracking = MouseTrackingMode.NORMAL,
            encoding = MouseEncodingMode.DEFAULT,
        )
        assertMouseBytes(
            esc("[M") + bytes(98, 33, 33),
            wheel(TerminalMouseButton.WHEEL_LEFT),
            tracking = MouseTrackingMode.NORMAL,
            encoding = MouseEncodingMode.DEFAULT,
        )
        assertMouseBytes(
            esc("[M") + bytes(99, 33, 33),
            wheel(TerminalMouseButton.WHEEL_RIGHT),
            tracking = MouseTrackingMode.NORMAL,
            encoding = MouseEncodingMode.DEFAULT,
        )
    }

    @Test
    fun `encodes legacy default no-button motion and release without button identity`() {
        assertMouseBytes(
            esc("[M") + bytes(67, 33, 33),
            motion(TerminalMouseButton.NONE),
            tracking = MouseTrackingMode.ANY_EVENT,
            encoding = MouseEncodingMode.DEFAULT,
        )
        assertMouseBytes(
            esc("[M") + bytes(35, 33, 33),
            release(TerminalMouseButton.NONE),
            tracking = MouseTrackingMode.NORMAL,
            encoding = MouseEncodingMode.DEFAULT,
        )
    }

    @Test
    fun `encodes legacy default modifiers and ignores Meta`() {
        assertMouseBytes(
            esc("[M") + bytes(36, 33, 33),
            press(modifiers = TerminalModifiers.SHIFT),
            encoding = MouseEncodingMode.DEFAULT,
        )
        assertMouseBytes(
            esc("[M") + bytes(40, 33, 33),
            press(modifiers = TerminalModifiers.ALT),
            encoding = MouseEncodingMode.DEFAULT,
        )
        assertMouseBytes(
            esc("[M") + bytes(48, 33, 33),
            press(modifiers = TerminalModifiers.CTRL),
            encoding = MouseEncodingMode.DEFAULT,
        )
        assertMouseBytes(
            esc("[M") + bytes(32, 33, 33),
            press(modifiers = TerminalModifiers.META),
            encoding = MouseEncodingMode.DEFAULT,
        )
    }

    @Test
    fun `bounds legacy default coordinates by policy`() {
        assertMouseBytes(
            esc("[M") + bytes(32, 255, 255),
            press(column = 222, row = 222),
            encoding = MouseEncodingMode.DEFAULT,
        )
        assertMouseBytes(
            bytes(),
            press(column = 223, row = 223),
            encoding = MouseEncodingMode.DEFAULT,
        )
        assertMouseBytes(
            esc("[M") + bytes(32, 255, 255),
            press(column = 223, row = 223),
            encoding = MouseEncodingMode.DEFAULT,
            policy =
                TerminalInputPolicy(
                    mouseCoordinateLimitPolicy = MouseCoordinateLimitPolicy.CLAMP_TO_MAX,
                ),
        )
    }

    @Test
    fun `encodes UTF8 extended mouse mode`() {
        assertMouseBytes(
            esc("[M") + bytes(32, 33, 33),
            press(),
            encoding = MouseEncodingMode.UTF8,
        )
        assertMouseBytes(
            esc("[M") + utf8Scalars(32, 2047, 2047),
            press(column = 2014, row = 2014),
            encoding = MouseEncodingMode.UTF8,
        )
        assertMouseBytes(
            bytes(),
            press(column = 2015, row = 2015),
            encoding = MouseEncodingMode.UTF8,
        )
        assertMouseBytes(
            esc("[M") + bytes(35, 33, 33),
            release(TerminalMouseButton.LEFT),
            encoding = MouseEncodingMode.UTF8,
        )
        assertMouseBytes(
            esc("[M") + bytes(96, 33, 33),
            wheel(TerminalMouseButton.WHEEL_UP),
            tracking = MouseTrackingMode.NORMAL,
            encoding = MouseEncodingMode.UTF8,
        )
        assertMouseBytes(
            esc("[M") + bytes(67, 33, 33),
            motion(TerminalMouseButton.NONE),
            tracking = MouseTrackingMode.ANY_EVENT,
            encoding = MouseEncodingMode.UTF8,
        )
        assertMouseBytes(
            esc("[M") + bytes(36, 33, 33),
            press(modifiers = TerminalModifiers.SHIFT),
            encoding = MouseEncodingMode.UTF8,
        )
    }

    @Test
    fun `encodes URXVT mouse mode`() {
        assertMouseBytes(
            esc("[32;1;1M"),
            press(),
            encoding = MouseEncodingMode.URXVT,
        )
        assertMouseBytes(
            esc("[35;1;1M"),
            release(TerminalMouseButton.LEFT),
            encoding = MouseEncodingMode.URXVT,
        )
        assertMouseBytes(
            esc("[96;3;4M"),
            wheel(TerminalMouseButton.WHEEL_UP, column = 2, row = 3),
            tracking = MouseTrackingMode.NORMAL,
            encoding = MouseEncodingMode.URXVT,
        )
        assertMouseBytes(
            esc("[67;1;1M"),
            motion(TerminalMouseButton.NONE),
            tracking = MouseTrackingMode.ANY_EVENT,
            encoding = MouseEncodingMode.URXVT,
        )
        assertMouseBytes(
            esc("[36;1;1M"),
            press(modifiers = TerminalModifiers.SHIFT),
            encoding = MouseEncodingMode.URXVT,
        )
        assertMouseBytes(
            esc("[32;5000;6000M"),
            press(column = 4999, row = 5999),
            encoding = MouseEncodingMode.URXVT,
        )
    }

    private fun assertTrackingMatrix(
        encoding: Int,
        encodedPress: ByteArray,
    ) {
        assertMouseBytes(encodedPress, press(), tracking = MouseTrackingMode.X10, encoding = encoding)
        assertMouseBytes(bytes(), release(), tracking = MouseTrackingMode.X10, encoding = encoding)
        assertMouseBytes(bytes(), motion(TerminalMouseButton.LEFT), tracking = MouseTrackingMode.X10, encoding = encoding)
        assertMouseBytes(bytes(), wheel(TerminalMouseButton.WHEEL_UP), tracking = MouseTrackingMode.X10, encoding = encoding)

        assertMouseBytes(encodedPress, press(), tracking = MouseTrackingMode.NORMAL, encoding = encoding)
        assertMouseBytes(bytes(), motion(TerminalMouseButton.LEFT), tracking = MouseTrackingMode.NORMAL, encoding = encoding)

        assertMouseBytes(
            bytes(),
            motion(TerminalMouseButton.NONE),
            tracking = MouseTrackingMode.BUTTON_EVENT,
            encoding = encoding,
        )
        assertMouseBytes(
            expectedMotion(TerminalMouseButton.LEFT, encoding),
            motion(TerminalMouseButton.LEFT),
            tracking = MouseTrackingMode.BUTTON_EVENT,
            encoding = encoding,
        )
        assertMouseBytes(
            expectedMotion(TerminalMouseButton.NONE, encoding),
            motion(TerminalMouseButton.NONE),
            tracking = MouseTrackingMode.ANY_EVENT,
            encoding = encoding,
        )
    }

    private fun expectedMotion(
        button: TerminalMouseButton,
        encoding: Int,
    ): ByteArray {
        val code = if (button == TerminalMouseButton.NONE) 67 else 64
        return when (encoding) {
            MouseEncodingMode.DEFAULT,
            MouseEncodingMode.UTF8,
            -> esc("[M") + bytes(code, 33, 33)
            MouseEncodingMode.SGR -> esc("[<${code - 32};1;1M")
            MouseEncodingMode.URXVT -> esc("[$code;1;1M")
            else -> bytes()
        }
    }

    private fun assertMouseBytes(
        expected: ByteArray,
        event: TerminalMouseEvent,
        tracking: Int = MouseTrackingMode.ANY_EVENT,
        encoding: Int = MouseEncodingMode.SGR,
        policy: TerminalInputPolicy = TerminalInputPolicy(),
    ) {
        val output = RecordingHostOutput()
        val encoder = MouseEncoder(output, InputScratchBuffer(), policy)

        encoder.encode(event, mouseBits(tracking, encoding))

        assertArrayEquals(expected, output.bytes)
    }

    private fun mouseBits(
        tracking: Int,
        encoding: Int,
    ): Long {
        val withTracking =
            TerminalModeBits.withPackedValue(
                bits = 0L,
                mask = TerminalModeBits.MOUSE_TRACKING_MASK,
                shift = TerminalModeBits.MOUSE_TRACKING_SHIFT,
                value = tracking,
            )
        return TerminalModeBits.withPackedValue(
            bits = withTracking,
            mask = TerminalModeBits.MOUSE_ENCODING_MASK,
            shift = TerminalModeBits.MOUSE_ENCODING_SHIFT,
            value = encoding,
        )
    }

    private fun press(
        button: TerminalMouseButton = TerminalMouseButton.LEFT,
        column: Int = 0,
        row: Int = 0,
        modifiers: Int = TerminalModifiers.NONE,
    ): TerminalMouseEvent = TerminalMouseEvent(column, row, button, TerminalMouseEventType.PRESS, modifiers)

    private fun release(
        button: TerminalMouseButton = TerminalMouseButton.LEFT,
        column: Int = 0,
        row: Int = 0,
    ): TerminalMouseEvent = TerminalMouseEvent(column, row, button, TerminalMouseEventType.RELEASE)

    private fun motion(
        button: TerminalMouseButton,
        column: Int = 0,
        row: Int = 0,
    ): TerminalMouseEvent = TerminalMouseEvent(column, row, button, TerminalMouseEventType.MOTION)

    private fun wheel(
        button: TerminalMouseButton,
        column: Int = 0,
        row: Int = 0,
    ): TerminalMouseEvent = TerminalMouseEvent(column, row, button, TerminalMouseEventType.WHEEL)

    private fun esc(textAfterEsc: String): ByteArray = bytes(0x1b) + textAfterEsc.encodeToByteArray()

    private fun bytes(vararg values: Int): ByteArray {
        val bytes = ByteArray(values.size)
        var i = 0
        while (i < values.size) {
            bytes[i] = values[i].toByte()
            i++
        }
        return bytes
    }

    private fun utf8Scalars(vararg values: Int): ByteArray {
        var bytes = ByteArray(0)
        var i = 0
        while (i < values.size) {
            bytes += utf8Scalar(values[i])
            i++
        }
        return bytes
    }

    private fun utf8Scalar(value: Int): ByteArray =
        when {
            value <= 0x7f -> bytes(value)
            value <= 0x7ff ->
                bytes(
                    0xc0 or (value shr 6),
                    0x80 or (value and 0x3f),
                )
            value <= 0xffff ->
                bytes(
                    0xe0 or (value shr 12),
                    0x80 or ((value shr 6) and 0x3f),
                    0x80 or (value and 0x3f),
                )
            else ->
                bytes(
                    0xf0 or (value shr 18),
                    0x80 or ((value shr 12) and 0x3f),
                    0x80 or ((value shr 6) and 0x3f),
                    0x80 or (value and 0x3f),
                )
        }

    private class RecordingHostOutput : TerminalHostOutput {
        var bytes: ByteArray = ByteArray(0)
            private set

        override fun writeByte(byte: Int) {
            bytes += byte.toByte()
        }

        override fun writeBytes(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) {
            this.bytes += bytes.copyOfRange(offset, offset + length)
        }

        override fun writeAscii(text: String) {
            bytes += text.encodeToByteArray()
        }

        override fun writeUtf8(text: String) {
            bytes += text.encodeToByteArray()
        }
    }
}
