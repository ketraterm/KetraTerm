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
package com.gagik.terminal.input.impl

import com.gagik.core.api.TerminalInputState
import com.gagik.core.api.TerminalModeBits
import com.gagik.terminal.input.event.*
import com.gagik.terminal.protocol.FormatOtherKeysMode
import com.gagik.terminal.protocol.ModifyOtherKeysMode
import com.gagik.terminal.protocol.host.TerminalHostOutput
import com.gagik.terminal.protocol.mouse.MouseEncodingMode
import com.gagik.terminal.protocol.mouse.MouseTrackingMode
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test

class XtermInputProfileTest {
    @Test
    fun `application cursor profile switches cursor-key wire sequences`() {
        assertProfileBytes(esc("[A"), bits = 0L) { it.encodeKey(TerminalKeyEvent.key(TerminalKey.UP)) }
        assertProfileBytes(esc("OA"), bits = TerminalModeBits.APPLICATION_CURSOR_KEYS) {
            it.encodeKey(TerminalKeyEvent.key(TerminalKey.UP))
        }
    }

    @Test
    fun `application keypad profile switches keypad wire sequences`() {
        assertProfileBytes(ascii("1"), bits = 0L) { it.encodeKey(TerminalKeyEvent.key(TerminalKey.NUMPAD_1)) }
        assertProfileBytes(esc("Oq"), bits = TerminalModeBits.APPLICATION_KEYPAD) {
            it.encodeKey(TerminalKeyEvent.key(TerminalKey.NUMPAD_1))
        }
    }

    @Test
    fun `bracketed paste profile wraps paste only when enabled`() {
        assertProfileBytes(ascii("a\nb")) { it.encodePaste(TerminalPasteEvent("a\nb")) }
        assertProfileBytes(
            esc("[200~") + ascii("a\nb") + esc("[201~"),
            bits = TerminalModeBits.BRACKETED_PASTE,
        ) {
            it.encodePaste(TerminalPasteEvent("a\nb"))
        }
    }

    @Test
    fun `focus profile emits focus reports only when enabled`() {
        assertProfileBytes(bytes()) {
            it.encodeFocus(TerminalFocusEvent(focused = true))
            it.encodeFocus(TerminalFocusEvent(focused = false))
        }
        assertProfileBytes(esc("[I") + esc("[O"), bits = TerminalModeBits.FOCUS_REPORTING) {
            it.encodeFocus(TerminalFocusEvent(focused = true))
            it.encodeFocus(TerminalFocusEvent(focused = false))
        }
    }

    @Test
    fun `mouse tracking profile controls which SGR mouse events report`() {
        assertProfileBytes(bytes(), bits = mouseBits(MouseTrackingMode.NONE, MouseEncodingMode.SGR)) {
            encodeMouseProfile(it)
        }
        assertProfileBytes(
            esc("[<0;1;1M"),
            bits = mouseBits(MouseTrackingMode.X10, MouseEncodingMode.SGR),
        ) {
            encodeMouseProfile(it)
        }
        assertProfileBytes(
            esc("[<0;1;1M") + esc("[<0;1;1m") + esc("[<64;1;1M"),
            bits = mouseBits(MouseTrackingMode.NORMAL, MouseEncodingMode.SGR),
        ) {
            encodeMouseProfile(it)
        }
        assertProfileBytes(
            esc("[<0;1;1M") + esc("[<0;1;1m") + esc("[<32;1;1M") + esc("[<64;1;1M"),
            bits = mouseBits(MouseTrackingMode.BUTTON_EVENT, MouseEncodingMode.SGR),
        ) {
            encodeMouseProfile(it)
        }
        assertProfileBytes(
            esc("[<0;1;1M") +
                esc("[<0;1;1m") +
                esc("[<32;1;1M") +
                esc("[<35;1;1M") +
                esc("[<64;1;1M"),
            bits = mouseBits(MouseTrackingMode.ANY_EVENT, MouseEncodingMode.SGR),
        ) {
            encodeMouseProfile(it)
        }
    }

    @Test
    fun `mouse encoding profile switches selected xterm mouse encoding`() {
        assertProfileBytes(
            esc("[M") + bytes(32, 33, 33),
            bits = mouseBits(MouseTrackingMode.NORMAL, MouseEncodingMode.DEFAULT),
        ) {
            it.encodeMouse(mousePress())
        }
        assertProfileBytes(
            esc("[M") + bytes(32, 33, 33),
            bits = mouseBits(MouseTrackingMode.NORMAL, MouseEncodingMode.UTF8),
        ) {
            it.encodeMouse(mousePress())
        }
        assertProfileBytes(
            esc("[<0;1;1M"),
            bits = mouseBits(MouseTrackingMode.NORMAL, MouseEncodingMode.SGR),
        ) {
            it.encodeMouse(mousePress())
        }
        assertProfileBytes(
            esc("[32;1;1M"),
            bits = mouseBits(MouseTrackingMode.NORMAL, MouseEncodingMode.URXVT),
        ) {
            it.encodeMouse(mousePress())
        }
    }

    @Test
    fun `modifyOtherKeys profile separates disabled mode1 and mode2 behavior`() {
        assertProfileBytes(bytes()) {
            it.encodeKey(TerminalKeyEvent.codepoint(0x00e9, TerminalModifiers.CTRL))
        }
        assertProfileBytes(
            esc("[27;5;233~") + bytes(0x09),
            bits = modifyOtherKeysBits(ModifyOtherKeysMode.MODE_1),
        ) {
            it.encodeKey(TerminalKeyEvent.codepoint(0x00e9, TerminalModifiers.CTRL))
            it.encodeKey(TerminalKeyEvent.codepoint('i'.code, TerminalModifiers.CTRL))
        }
        assertProfileBytes(
            esc("[27;5;233~") + esc("[27;5;105~") + esc("[27;2;13~"),
            bits = modifyOtherKeysBits(ModifyOtherKeysMode.MODE_2),
        ) {
            it.encodeKey(TerminalKeyEvent.codepoint(0x00e9, TerminalModifiers.CTRL))
            it.encodeKey(TerminalKeyEvent.codepoint('i'.code, TerminalModifiers.CTRL))
            it.encodeKey(TerminalKeyEvent.key(TerminalKey.ENTER, TerminalModifiers.SHIFT))
        }
    }

    @Test
    fun `formatOtherKeys profile switches modified ordinary keys to CSI-u`() {
        assertProfileBytes(
            esc("[233;5u") + esc("[9;3u") + esc("[32;1u"),
            bits =
                modifyOtherKeysBits(ModifyOtherKeysMode.MODE_3) or
                    formatOtherKeysBits(FormatOtherKeysMode.CSI_U),
        ) {
            it.encodeKey(TerminalKeyEvent.codepoint(0x00e9, TerminalModifiers.CTRL))
            it.encodeKey(TerminalKeyEvent.key(TerminalKey.TAB, TerminalModifiers.ALT))
            it.encodeKey(TerminalKeyEvent.codepoint(' '.code))
        }
    }

    private fun assertProfileBytes(
        expected: ByteArray,
        bits: Long = 0L,
        block: (DefaultTerminalInputEncoder) -> Unit,
    ) {
        val inputState = FixedInputState(bits)
        val output = RecordingHostOutput()
        val encoder = DefaultTerminalInputEncoder(inputState, output)

        block(encoder)

        assertArrayEquals(expected, output.bytes)
    }

    private fun encodeMouseProfile(encoder: DefaultTerminalInputEncoder) {
        encoder.encodeMouse(mousePress())
        encoder.encodeMouse(mouseRelease())
        encoder.encodeMouse(mouseMotion(TerminalMouseButton.LEFT))
        encoder.encodeMouse(mouseMotion(TerminalMouseButton.NONE))
        encoder.encodeMouse(mouseWheel())
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

    private fun modifyOtherKeysBits(mode: Int): Long =
        TerminalModeBits.withPackedValue(
            bits = 0L,
            mask = TerminalModeBits.MODIFY_OTHER_KEYS_MASK,
            shift = TerminalModeBits.MODIFY_OTHER_KEYS_SHIFT,
            value = mode,
        )

    private fun formatOtherKeysBits(mode: Int): Long =
        TerminalModeBits.withPackedValue(
            bits = 0L,
            mask = TerminalModeBits.FORMAT_OTHER_KEYS_MASK,
            shift = TerminalModeBits.FORMAT_OTHER_KEYS_SHIFT,
            value = mode,
        )

    private fun mousePress(): TerminalMouseEvent = TerminalMouseEvent(0, 0, TerminalMouseButton.LEFT, TerminalMouseEventType.PRESS)

    private fun mouseRelease(): TerminalMouseEvent = TerminalMouseEvent(0, 0, TerminalMouseButton.LEFT, TerminalMouseEventType.RELEASE)

    private fun mouseMotion(button: TerminalMouseButton): TerminalMouseEvent =
        TerminalMouseEvent(0, 0, button, TerminalMouseEventType.MOTION)

    private fun mouseWheel(): TerminalMouseEvent = TerminalMouseEvent(0, 0, TerminalMouseButton.WHEEL_UP, TerminalMouseEventType.WHEEL)

    private fun esc(textAfterEsc: String): ByteArray = bytes(0x1b) + ascii(textAfterEsc)

    private fun ascii(text: String): ByteArray {
        val bytes = ByteArray(text.length)
        var i = 0
        while (i < text.length) {
            bytes[i] = text[i].code.toByte()
            i++
        }
        return bytes
    }

    private fun bytes(vararg values: Int): ByteArray {
        val bytes = ByteArray(values.size)
        var i = 0
        while (i < values.size) {
            bytes[i] = values[i].toByte()
            i++
        }
        return bytes
    }

    private class FixedInputState(
        private val bits: Long,
    ) : TerminalInputState {
        override fun getInputModeBits(): Long = bits
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
