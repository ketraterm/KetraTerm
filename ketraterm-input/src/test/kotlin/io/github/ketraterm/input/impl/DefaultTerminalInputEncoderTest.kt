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

import io.github.ketraterm.core.api.TerminalInputState
import io.github.ketraterm.core.api.TerminalModeBits
import io.github.ketraterm.input.event.*
import io.github.ketraterm.input.policy.PasteSanitizationPolicy
import io.github.ketraterm.input.policy.TerminalInputPolicy
import io.github.ketraterm.input.policy.UnsupportedModifiedKeyPolicy
import io.github.ketraterm.protocol.host.TerminalHostOutput
import io.github.ketraterm.protocol.mouse.MouseEncodingMode
import io.github.ketraterm.protocol.mouse.MouseTrackingMode
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DefaultTerminalInputEncoderTest {
    @Test
    fun `encodeKey reads mode bits once per event`() {
        val inputState = RecordingInputState(TerminalModeBits.APPLICATION_CURSOR_KEYS)
        val output = RecordingHostOutput()
        val encoder = DefaultTerminalInputEncoder(inputState, output)

        encoder.encodeKey(TerminalKeyEvent.key(TerminalKey.UP))

        assertEquals(1, inputState.reads)
        assertArrayEquals(byteArrayOf(0x1b, 'O'.code.toByte(), 'A'.code.toByte()), output.bytes)
    }

    @Test
    fun `encodePaste reads mode bits once per event`() {
        val inputState = RecordingInputState(TerminalModeBits.BRACKETED_PASTE)
        val output = RecordingHostOutput()
        val encoder =
            DefaultTerminalInputEncoder(
                inputState = inputState,
                output = output,
            )

        encoder.encodePaste(TerminalPasteEvent("text"))

        assertEquals(1, inputState.reads)
        assertArrayEquals(esc("[200~") + "text".encodeToByteArray() + esc("[201~"), output.bytes)
    }

    @Test
    fun `encode text replacement reads modes once and preserves edit ordering`() {
        val inputState = RecordingInputState(0L)
        val output = RecordingHostOutput()
        val encoder = DefaultTerminalInputEncoder(inputState, output)

        encoder.encodeTextReplacement(
            TerminalTextReplacementEvent(
                deleteAfterCursorCount = 2,
                deleteBeforeCursorCount = 3,
                replacementText = "status",
            ),
        )

        assertEquals(1, inputState.reads)
        assertArrayEquals(
            esc("[3~") + esc("[3~") + byteArrayOf(0x7f, 0x7f, 0x7f) + "status".encodeToByteArray(),
            output.bytes,
        )
        assertEquals(2, output.writeCalls)
    }

    @Test
    fun `long text replacement coalesces deletion sequences into bounded writes`() {
        val inputState = RecordingInputState(0L)
        val output = RecordingHostOutput()
        val encoder = DefaultTerminalInputEncoder(inputState, output)

        encoder.encodeTextReplacement(
            TerminalTextReplacementEvent(
                deleteAfterCursorCount = 4_096,
                deleteBeforeCursorCount = 4_096,
                replacementText = "done",
            ),
        )

        assertEquals(1, inputState.reads)
        assertEquals(6, output.writeCalls)
        assertEquals(4_096 * 5 + 4, output.bytes.size)
        assertArrayEquals("done".encodeToByteArray(), output.bytes.copyOfRange(output.bytes.size - 4, output.bytes.size))
    }

    @Test
    fun `encodeFocus reads mode bits once per event`() {
        val inputState = RecordingInputState(TerminalModeBits.FOCUS_REPORTING)
        val output = RecordingHostOutput()
        val encoder =
            DefaultTerminalInputEncoder(
                inputState = inputState,
                output = output,
            )

        encoder.encodeFocus(TerminalFocusEvent(focused = true))

        assertEquals(1, inputState.reads)
        assertArrayEquals(esc("[I"), output.bytes)
    }

    @Test
    fun `encodeKey applies injected keyboard policy`() {
        val inputState = RecordingInputState(0L)
        val output = RecordingHostOutput()
        val encoder =
            DefaultTerminalInputEncoder(
                inputState = inputState,
                output = output,
                policy =
                    TerminalInputPolicy(
                        unsupportedModifiedKeyPolicy = UnsupportedModifiedKeyPolicy.EMIT_UNMODIFIED,
                    ),
            )

        encoder.encodeKey(TerminalKeyEvent.codepoint(0x00e9, TerminalModifiers.CTRL))

        assertEquals(1, inputState.reads)
        assertArrayEquals(byteArrayOf(0xc3.toByte(), 0xa9.toByte()), output.bytes)
    }

    @Test
    fun `encodeMouse reads mode bits once per event and emits SGR`() {
        val inputState = RecordingInputState(mouseBits(MouseTrackingMode.NORMAL, MouseEncodingMode.SGR))
        val output = RecordingHostOutput()
        val encoder =
            DefaultTerminalInputEncoder(
                inputState = inputState,
                output = output,
            )

        encoder.encodeMouse(
            TerminalMouseEvent(
                column = 0,
                row = 0,
                button = TerminalMouseButton.LEFT,
                type = TerminalMouseEventType.PRESS,
            ),
        )

        assertEquals(1, inputState.reads)
        assertArrayEquals(esc("[<0;1;1M"), output.bytes)
    }

    @Test
    fun `encodeMouse emits nothing when tracking is disabled`() {
        val inputState = RecordingInputState(mouseBits(MouseTrackingMode.NONE, MouseEncodingMode.SGR))
        val output = RecordingHostOutput()
        val encoder =
            DefaultTerminalInputEncoder(
                inputState = inputState,
                output = output,
            )

        encoder.encodeMouse(
            TerminalMouseEvent(
                column = 0,
                row = 0,
                button = TerminalMouseButton.LEFT,
                type = TerminalMouseEventType.PRESS,
            ),
        )

        assertEquals(1, inputState.reads)
        assertArrayEquals(byteArrayOf(), output.bytes)
    }

    @Test
    fun `encodeMouse reuses shared scratch across consecutive events`() {
        val inputState = RecordingInputState(mouseBits(MouseTrackingMode.ANY_EVENT, MouseEncodingMode.SGR))
        val output = RecordingHostOutput()
        val encoder =
            DefaultTerminalInputEncoder(
                inputState = inputState,
                output = output,
            )

        encoder.encodeMouse(
            TerminalMouseEvent(
                column = 0,
                row = 0,
                button = TerminalMouseButton.LEFT,
                type = TerminalMouseEventType.PRESS,
            ),
        )
        encoder.encodeMouse(
            TerminalMouseEvent(
                column = 1,
                row = 2,
                button = TerminalMouseButton.NONE,
                type = TerminalMouseEventType.MOTION,
            ),
        )

        assertEquals(2, inputState.reads)
        assertArrayEquals(esc("[<0;1;1M") + esc("[<35;2;3M"), output.bytes)
    }

    @Test
    fun `encodePaste applies injected paste sanitization policy`() {
        val inputState = RecordingInputState(0L)
        val output = RecordingHostOutput()
        val encoder =
            DefaultTerminalInputEncoder(
                inputState = inputState,
                output = output,
                policy =
                    TerminalInputPolicy(
                        pasteSanitizationPolicy = PasteSanitizationPolicy.STRIP_C0_EXCEPT_TAB_CR_LF,
                    ),
            )

        encoder.encodePaste(TerminalPasteEvent("a\u001bb"))

        assertEquals(1, inputState.reads)
        assertArrayEquals("ab".encodeToByteArray(), output.bytes)
    }

    private fun esc(textAfterEsc: String): ByteArray = byteArrayOf(0x1b) + textAfterEsc.encodeToByteArray()

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

    private class RecordingInputState(
        private val bits: Long,
    ) : TerminalInputState {
        var reads: Int = 0
            private set

        override fun getInputModeBits(): Long {
            reads++
            return bits
        }
    }

    private class RecordingHostOutput : TerminalHostOutput {
        var bytes: ByteArray = ByteArray(0)
            private set
        var writeCalls: Int = 0
            private set

        override fun writeByte(byte: Int) {
            writeCalls++
            bytes += byte.toByte()
        }

        override fun writeBytes(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) {
            writeCalls++
            this.bytes += bytes.copyOfRange(offset, offset + length)
        }

        override fun writeAscii(text: String) {
            writeCalls++
            bytes += text.encodeToByteArray()
        }

        override fun writeUtf8(text: String) {
            writeCalls++
            bytes += text.encodeToByteArray()
        }
    }
}
