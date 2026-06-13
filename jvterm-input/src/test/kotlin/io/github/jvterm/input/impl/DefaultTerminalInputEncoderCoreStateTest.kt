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

import io.github.jvterm.core.TerminalBuffers
import io.github.jvterm.input.event.*
import io.github.jvterm.protocol.MouseEncodingMode
import io.github.jvterm.protocol.MouseTrackingMode
import io.github.jvterm.protocol.host.TerminalHostOutput
import io.github.jvterm.protocol.keyboard.FormatOtherKeysMode
import io.github.jvterm.protocol.keyboard.ModifyOtherKeysMode
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test

class DefaultTerminalInputEncoderCoreStateTest {
    @Test
    fun `uses real core application cursor mode for keyboard encoding`() {
        val terminal = TerminalBuffers.create(width = 4, height = 2)
        val output = RecordingHostOutput()
        val encoder = DefaultTerminalInputEncoder(terminal, output)

        encoder.encodeKey(TerminalKeyEvent.key(TerminalKey.UP))
        terminal.setApplicationCursorKeys(true)
        encoder.encodeKey(TerminalKeyEvent.key(TerminalKey.UP))

        assertArrayEquals(esc("[A") + esc("OA"), output.bytes)
    }

    @Test
    fun `uses real core new line mode for enter encoding`() {
        val terminal = TerminalBuffers.create(width = 4, height = 2)
        val output = RecordingHostOutput()
        val encoder = DefaultTerminalInputEncoder(terminal, output)

        encoder.encodeKey(TerminalKeyEvent.key(TerminalKey.ENTER))
        terminal.setNewLineMode(true)
        encoder.encodeKey(TerminalKeyEvent.key(TerminalKey.ENTER))

        assertArrayEquals(byteArrayOf(0x0d, 0x0d, 0x0a), output.bytes)
    }

    @Test
    fun `uses real core application keypad mode for keypad encoding`() {
        val terminal = TerminalBuffers.create(width = 4, height = 2)
        val output = RecordingHostOutput()
        val encoder = DefaultTerminalInputEncoder(terminal, output)

        encoder.encodeKey(TerminalKeyEvent.key(TerminalKey.NUMPAD_1))
        terminal.setApplicationKeypad(true)
        encoder.encodeKey(TerminalKeyEvent.key(TerminalKey.NUMPAD_1))

        assertArrayEquals("1".encodeToByteArray() + esc("Oq"), output.bytes)
    }

    @Test
    fun `uses real core modifyOtherKeys mode for keyboard encoding`() {
        val terminal = TerminalBuffers.create(width = 4, height = 2)
        val output = RecordingHostOutput()
        val encoder = DefaultTerminalInputEncoder(terminal, output)

        encoder.encodeKey(TerminalKeyEvent.codepoint(0x00e9, TerminalModifiers.CTRL))
        terminal.setModifyOtherKeysMode(ModifyOtherKeysMode.MODE_2)
        encoder.encodeKey(TerminalKeyEvent.codepoint(0x00e9, TerminalModifiers.CTRL))
        encoder.encodeKey(TerminalKeyEvent.key(TerminalKey.ENTER, TerminalModifiers.SHIFT))

        assertArrayEquals(
            esc("[27;5;233~") + esc("[27;2;13~"),
            output.bytes,
        )
    }

    @Test
    fun `uses real core formatOtherKeys mode for CSI-u keyboard encoding`() {
        val terminal = TerminalBuffers.create(width = 4, height = 2)
        val output = RecordingHostOutput()
        val encoder = DefaultTerminalInputEncoder(terminal, output)

        terminal.setModifyOtherKeysMode(ModifyOtherKeysMode.MODE_3)
        encoder.encodeKey(TerminalKeyEvent.codepoint(' '.code))
        terminal.setFormatOtherKeysMode(FormatOtherKeysMode.CSI_U)
        encoder.encodeKey(TerminalKeyEvent.codepoint(' '.code))

        assertArrayEquals(
            esc("[27;1;32~") + esc("[32;1u"),
            output.bytes,
        )
    }

    @Test
    fun `uses real core bracketed paste mode for paste encoding`() {
        val terminal = TerminalBuffers.create(width = 4, height = 2)
        val output = RecordingHostOutput()
        val encoder = DefaultTerminalInputEncoder(terminal, output)

        encoder.encodePaste(TerminalPasteEvent("a"))
        terminal.setBracketedPasteEnabled(true)
        encoder.encodePaste(TerminalPasteEvent("b"))

        assertArrayEquals("a".encodeToByteArray() + esc("[200~") + "b".encodeToByteArray() + esc("[201~"), output.bytes)
    }

    @Test
    fun `uses real core focus reporting mode for focus encoding`() {
        val terminal = TerminalBuffers.create(width = 4, height = 2)
        val output = RecordingHostOutput()
        val encoder = DefaultTerminalInputEncoder(terminal, output)

        encoder.encodeFocus(TerminalFocusEvent(focused = true))
        terminal.setFocusReportingEnabled(true)
        encoder.encodeFocus(TerminalFocusEvent(focused = true))
        encoder.encodeFocus(TerminalFocusEvent(focused = false))

        assertArrayEquals(esc("[I") + esc("[O"), output.bytes)
    }

    @Test
    fun `uses real core mouse tracking and encoding modes for mouse encoding`() {
        val terminal = TerminalBuffers.create(width = 4, height = 2)
        val output = RecordingHostOutput()
        val encoder = DefaultTerminalInputEncoder(terminal, output)

        encoder.encodeMouse(mousePress())
        terminal.setMouseTrackingMode(MouseTrackingMode.NORMAL)
        terminal.setMouseEncodingMode(MouseEncodingMode.SGR)
        encoder.encodeMouse(mousePress())

        assertArrayEquals(esc("[<0;1;1M"), output.bytes)
    }

    @Test
    fun `uses real core mouse encoding selectors`() {
        val terminal = TerminalBuffers.create(width = 4, height = 2)
        val output = RecordingHostOutput()
        val encoder = DefaultTerminalInputEncoder(terminal, output)

        terminal.setMouseTrackingMode(MouseTrackingMode.NORMAL)

        terminal.setMouseEncodingMode(MouseEncodingMode.DEFAULT)
        encoder.encodeMouse(mousePress())

        terminal.setMouseEncodingMode(MouseEncodingMode.UTF8)
        encoder.encodeMouse(mousePress())

        terminal.setMouseEncodingMode(MouseEncodingMode.SGR)
        encoder.encodeMouse(mousePress())

        terminal.setMouseEncodingMode(MouseEncodingMode.URXVT)
        encoder.encodeMouse(mousePress())

        assertArrayEquals(
            esc("[M") + byteArrayOf(32, 33, 33) +
                esc("[M") + byteArrayOf(32, 33, 33) +
                esc("[<0;1;1M") +
                esc("[32;1;1M"),
            output.bytes,
        )
    }

    @Test
    fun `uses real core mouse tracking selectors`() {
        val terminal = TerminalBuffers.create(width = 4, height = 2)
        val output = RecordingHostOutput()
        val encoder = DefaultTerminalInputEncoder(terminal, output)

        terminal.setMouseEncodingMode(MouseEncodingMode.SGR)

        terminal.setMouseTrackingMode(MouseTrackingMode.X10)
        encoder.encodeMouse(mousePress())
        encoder.encodeMouse(mouseRelease())

        terminal.setMouseTrackingMode(MouseTrackingMode.NORMAL)
        encoder.encodeMouse(mouseRelease())
        encoder.encodeMouse(mouseMotion(TerminalMouseButton.LEFT))

        terminal.setMouseTrackingMode(MouseTrackingMode.BUTTON_EVENT)
        encoder.encodeMouse(mouseMotion(TerminalMouseButton.LEFT))
        encoder.encodeMouse(mouseMotion(TerminalMouseButton.NONE))

        terminal.setMouseTrackingMode(MouseTrackingMode.ANY_EVENT)
        encoder.encodeMouse(mouseMotion(TerminalMouseButton.NONE))

        assertArrayEquals(
            esc("[<0;1;1M") +
                esc("[<0;1;1m") +
                esc("[<32;1;1M") +
                esc("[<35;1;1M"),
            output.bytes,
        )
    }

    @Test
    fun `real core disabled mouse tracking suppresses mouse encoding`() {
        val terminal = TerminalBuffers.create(width = 4, height = 2)
        val output = RecordingHostOutput()
        val encoder = DefaultTerminalInputEncoder(terminal, output)

        terminal.setMouseEncodingMode(MouseEncodingMode.SGR)
        encoder.encodeMouse(mousePress())

        assertArrayEquals(byteArrayOf(), output.bytes)
    }

    @Test
    fun `uses fresh core mode bits across consecutive mixed events`() {
        val terminal = TerminalBuffers.create(width = 4, height = 2)
        val output = RecordingHostOutput()
        val encoder = DefaultTerminalInputEncoder(terminal, output)

        encoder.encodeKey(TerminalKeyEvent.key(TerminalKey.UP))
        terminal.setApplicationCursorKeys(true)
        encoder.encodeKey(TerminalKeyEvent.key(TerminalKey.UP))
        encoder.encodePaste(TerminalPasteEvent("a"))
        terminal.setBracketedPasteEnabled(true)
        encoder.encodePaste(TerminalPasteEvent("b"))
        encoder.encodeFocus(TerminalFocusEvent(focused = true))
        terminal.setFocusReportingEnabled(true)
        encoder.encodeFocus(TerminalFocusEvent(focused = true))
        terminal.setApplicationKeypad(true)
        encoder.encodeKey(TerminalKeyEvent.key(TerminalKey.NUMPAD_1))
        terminal.setNewLineMode(true)
        encoder.encodeKey(TerminalKeyEvent.key(TerminalKey.ENTER))

        assertArrayEquals(
            esc("[A") +
                esc("OA") +
                "a".encodeToByteArray() +
                esc("[200~") +
                "b".encodeToByteArray() +
                esc("[201~") +
                esc("[I") +
                esc("Oq") +
                byteArrayOf(0x0d, 0x0a),
            output.bytes,
        )
    }

    private fun esc(textAfterEsc: String): ByteArray = byteArrayOf(0x1b) + textAfterEsc.encodeToByteArray()

    private fun mousePress(): TerminalMouseEvent =
        TerminalMouseEvent(
            column = 0,
            row = 0,
            button = TerminalMouseButton.LEFT,
            type = TerminalMouseEventType.PRESS,
        )

    private fun mouseRelease(): TerminalMouseEvent =
        TerminalMouseEvent(
            column = 0,
            row = 0,
            button = TerminalMouseButton.LEFT,
            type = TerminalMouseEventType.RELEASE,
        )

    private fun mouseMotion(button: TerminalMouseButton): TerminalMouseEvent =
        TerminalMouseEvent(
            column = 0,
            row = 0,
            button = button,
            type = TerminalMouseEventType.MOTION,
        )

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
