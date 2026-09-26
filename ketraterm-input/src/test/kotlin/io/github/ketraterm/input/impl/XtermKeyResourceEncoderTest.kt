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
package io.github.ketraterm.input.impl

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.core.api.TerminalInputState
import io.github.ketraterm.input.event.TerminalKey
import io.github.ketraterm.input.event.TerminalKeyEvent
import io.github.ketraterm.input.event.TerminalKeyEventType
import io.github.ketraterm.input.event.TerminalModifiers
import io.github.ketraterm.protocol.host.TerminalHostOutput
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream

class XtermKeyResourceEncoderTest {
    @ParameterizedTest
    @ValueSource(ints = [-1, 0, 1, 2, 3, 4])
    fun `function resource levels preserve modified PF keypad sequences`(functionLevel: Int) {
        val terminal = TerminalBuffers.create(10, 3)
        terminal.setKeyModifierOption(2, functionLevel)
        val output = RecordingOutput()
        val encoder = DefaultTerminalInputEncoder(terminal, output)

        encoder.encodeKey(TerminalKeyEvent.key(TerminalKey.PF1, TerminalModifiers.CTRL))
        encoder.encodeKey(TerminalKeyEvent.key(TerminalKey.PF2, TerminalModifiers.CTRL))
        encoder.encodeKey(TerminalKeyEvent.key(TerminalKey.PF3, TerminalModifiers.CTRL))
        encoder.encodeKey(TerminalKeyEvent.key(TerminalKey.PF4, TerminalModifiers.CTRL))

        assertEquals(0, TerminalInputState.keyModifierOption(terminal.getInputModeBits(), 3))
        assertEquals("\u001B[1;5P\u001B[1;5Q\u001B[1;5R\u001B[1;5S", output.text())
    }

    @ParameterizedTest
    @CsvSource(
        "UP,1,-1,false,[A",
        "UP,1,-1,true,OA",
        "UP,1,0,false,[5A",
        "UP,1,0,true,O5A",
        "UP,1,1,true,[5A",
        "UP,1,2,true,[1;5A",
        "UP,1,3,false,[>1;5A",
        "F1,2,0,false,O5P",
        "F1,2,1,false,[5P",
        "F1,2,2,false,[1;5P",
        "F1,2,3,false,[>1;5P",
        "F5,2,0,false,[15;5~",
        "F5,2,1,false,[15;5~",
        "F5,2,2,false,[15;5~",
        "F5,2,3,false,[>15;5~",
        "PAGE_UP,1,-1,false,[5;5~",
        "PAGE_UP,1,3,false,[5;5~",
        "INSERT,1,0,false,[2;5~",
        "DELETE,1,1,false,[3;5~",
    )
    fun `cursor and function levels select exact modified sequences`(
        key: TerminalKey,
        resource: Int,
        level: Int,
        application: Boolean,
        expected: String,
    ) {
        val terminal = TerminalBuffers.create(10, 3)
        terminal.setApplicationCursorKeys(application)
        terminal.setKeyModifierOption(resource, level)
        val output = RecordingOutput()
        DefaultTerminalInputEncoder(terminal, output).encodeKey(TerminalKeyEvent.key(key, TerminalModifiers.CTRL))
        assertEquals("\u001B$expected", output.text())
    }

    @ParameterizedTest
    @CsvSource(
        "UP,1,57938",
        "INSERT,1,57955",
        "DELETE,1,127",
        "PAGE_UP,1,57941",
        "F1,2,58046",
        "F13,2,58058",
        "F35,2,58080",
        "MENU,2,57959",
        "NUMPAD_1,3,58033",
        "NUMPAD_ENTER,3,57997",
        "NUMPAD_LEFT,3,58006",
        "PF1,3,58001",
        "LEFT_SHIFT,6,58081",
        "RIGHT_CONTROL,6,58084",
        "CAPS_LOCK,6,58085",
        "NUM_LOCK,6,57983",
        "ENTER,7,13",
        "ESCAPE,7,27",
        "BACKSPACE,7,8",
        "TAB,7,9",
        "PAUSE,7,19",
        "SCROLL_LOCK,7,20",
    )
    fun `extended families use xterm identities in both formats`(
        key: TerminalKey,
        resource: Int,
        code: Int,
    ) {
        val terminal = TerminalBuffers.create(10, 3)
        terminal.setKeyModifierOption(resource, 4)
        val output = RecordingOutput()
        val encoder = DefaultTerminalInputEncoder(terminal, output)
        encoder.encodeKey(TerminalKeyEvent.key(key))
        terminal.setKeyFormatOption(resource, 1)
        encoder.encodeKey(TerminalKeyEvent.key(key, TerminalModifiers.CTRL or TerminalModifiers.SHIFT))
        encoder.encodeKey(TerminalKeyEvent.key(key, type = TerminalKeyEventType.RELEASE))
        assertEquals("\u001B[27;1;$code~\u001B[$code;6u", output.text())
    }

    @Test
    fun `explicit function disable selects extended function numbers and reset restores modifiers`() {
        val terminal = TerminalBuffers.create(10, 3)
        val output = RecordingOutput()
        val encoder = DefaultTerminalInputEncoder(terminal, output)
        terminal.setKeyModifierOption(2, -1)
        encoder.encodeKey(TerminalKeyEvent.key(TerminalKey.F1, TerminalModifiers.SHIFT))
        encoder.encodeKey(TerminalKeyEvent.key(TerminalKey.F1, TerminalModifiers.CTRL))
        encoder.encodeKey(TerminalKeyEvent.key(TerminalKey.F1, TerminalModifiers.SHIFT or TerminalModifiers.CTRL))
        encoder.encodeKey(TerminalKeyEvent.key(TerminalKey.F13))
        terminal.resetKeyModifierOption(2)
        encoder.encodeKey(TerminalKeyEvent.key(TerminalKey.F1, TerminalModifiers.SHIFT))
        assertEquals("\u001B[25~\u001B[46~\u001B[58~\u001B[25~\u001B[1;2P", output.text())
    }

    @Test
    fun `Kitty precedence text commits and mode snapshot remain independent of xterm resources`() {
        val terminal = TerminalBuffers.create(10, 3)
        terminal.setKeyModifierOption(1, 4)
        terminal.setKeyFormatOption(1, 1)
        terminal.setKeyModifierOption(7, 4)
        terminal.setKittyKeyboardFlags(1)
        val output = RecordingOutput()
        var reads = 0
        val state =
            object : TerminalInputState {
                override fun getInputModeBits(): Long {
                    reads++
                    return terminal.getInputModeBits()
                }
            }
        val encoder = DefaultTerminalInputEncoder(state, output)
        encoder.encodeKey(TerminalKeyEvent.key(TerminalKey.UP, TerminalModifiers.CTRL))
        terminal.setKittyKeyboardFlags(0)
        encoder.encodeKey(TerminalKeyEvent.key(TerminalKey.UP, TerminalModifiers.CTRL))
        encoder.encodeKey(TerminalKeyEvent.text("text", TerminalModifiers.CTRL))
        assertEquals(3, reads)
        assertEquals("\u001B[1;5A\u001B[57938;5utext", output.text())
    }

    @Test
    fun `one family cannot change another family format or ordinary key handling`() {
        val terminal = TerminalBuffers.create(10, 3)
        terminal.setKeyModifierOption(1, 4)
        terminal.setKeyFormatOption(2, 1)
        terminal.setKeyModifierOption(0, 15)
        terminal.setKeyFormatOption(0, 1)
        val output = RecordingOutput()
        val encoder = DefaultTerminalInputEncoder(terminal, output)
        encoder.encodeKey(TerminalKeyEvent.key(TerminalKey.UP))
        encoder.encodeKey(TerminalKeyEvent.key(TerminalKey.F1))
        encoder.encodeKey(TerminalKeyEvent.codepoint('c'.code, TerminalModifiers.CTRL))
        assertEquals("\u001B[27;1;57938~\u001BOP\u0003", output.text())
    }

    private class RecordingOutput : TerminalHostOutput {
        private val bytes = ByteArrayOutputStream()

        override fun writeByte(byte: Int) {
            bytes.write(byte)
        }

        override fun writeBytes(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) {
            this.bytes.write(bytes, offset, length)
        }

        override fun writeAscii(text: String) {
            bytes.write(text.encodeToByteArray())
        }

        override fun writeUtf8(text: String) {
            bytes.write(text.encodeToByteArray())
        }

        fun text(): String = bytes.toByteArray().decodeToString()
    }
}
