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
package io.github.ketraterm.ui.swing.input

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.core.api.TerminalInputState
import io.github.ketraterm.host.HostCommandAdapter
import io.github.ketraterm.input.TerminalInputEncoders
import io.github.ketraterm.parser.api.TerminalParsers
import io.github.ketraterm.protocol.host.TerminalHostOutput
import org.junit.jupiter.api.Test
import java.awt.Canvas
import java.awt.event.KeyEvent
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SwingKeypadEncodingTest {
    @Test
    fun `AWT keypad strokes honor current Num Lock without changing application mode`() =
        onEdt {
            val corners =
                listOf(
                    Triple(KeyEvent.VK_NUMPAD7, '7', "\u001bOw"),
                    Triple(KeyEvent.VK_NUMPAD9, '9', "\u001bOy"),
                    Triple(KeyEvent.VK_NUMPAD1, '1', "\u001bOq"),
                    Triple(KeyEvent.VK_NUMPAD3, '3', "\u001bOs"),
                )
            for ((enable, disable) in listOf("\u001b=" to "\u001b>", "\u001b[?66h" to "\u001b[?66l")) {
                var numLock = false
                val fixture = Fixture { numLock }
                for ((keyCode, digit, _) in corners) {
                    assertEquals(digit.toString(), fixture.stroke(keyCode, digit))
                }
                fixture.receive(enable)
                for (locked in listOf(false, true, false)) {
                    numLock = locked
                    for ((keyCode, digit, application) in corners) {
                        assertEquals(if (locked) digit.toString() else application, fixture.stroke(keyCode, digit))
                    }
                    assertTrue(TerminalInputState.isApplicationKeypad(fixture.terminal.getInputModeBits()))
                }
                fixture.receive(disable)
                for ((keyCode, digit, _) in corners) {
                    assertEquals(digit.toString(), fixture.stroke(keyCode, digit))
                }
            }
        }

    @Test
    fun `AWT Num Lock metadata preserves Kitty and xterm keypad reporting`() =
        onEdt {
            val fixture = Fixture { true }
            fixture.receive("\u001b=\u001b[=1u")
            assertEquals("\u001b[57406;129u", fixture.stroke(KeyEvent.VK_NUMPAD7, '7'))
            fixture.receive("\u001b[=0u\u001b[>3;4m")
            assertEquals("\u001b[27;1;58039~", fixture.stroke(KeyEvent.VK_NUMPAD7, '7'))
            fixture.receive("\u001b[>3;1f")
            assertEquals("\u001b[58039;1u", fixture.stroke(KeyEvent.VK_NUMPAD7, '7'))
            fixture.receive("\u001b[>3m")
            assertEquals("7", fixture.stroke(KeyEvent.VK_NUMPAD7, '7'))
            assertTrue(TerminalInputState.isApplicationKeypad(fixture.terminal.getInputModeBits()))
        }

    @Test
    fun `unavailable platform Num Lock state preserves legacy keypad behavior`() =
        onEdt {
            var queries = 0
            val fixture =
                Fixture {
                    queries++
                    throw UnsupportedOperationException()
                }
            fixture.receive("\u001b=")
            assertEquals("\u001bOw", fixture.stroke(KeyEvent.VK_NUMPAD7, '7'))
            assertEquals("\u001bOw", fixture.stroke(KeyEvent.VK_NUMPAD7, '7'))
            fixture.receive("\u001b>")
            assertEquals("7", fixture.stroke(KeyEvent.VK_NUMPAD7, '7'))
            assertEquals(1, queries)
        }

    private class Fixture(
        numLockState: () -> Boolean,
    ) {
        val terminal = TerminalBuffers.create(10, 3)
        private val parser = TerminalParsers.create(HostCommandAdapter(terminal))
        private val mapper = SwingKeyMapper(numLockState)
        private val source = Canvas()
        private val output = ByteArrayOutputStream()
        private val encoder =
            TerminalInputEncoders.create(
                terminal,
                object : TerminalHostOutput {
                    override fun writeByte(byte: Int) = output.write(byte)

                    override fun writeBytes(
                        bytes: ByteArray,
                        offset: Int,
                        length: Int,
                    ) = output.write(bytes, offset, length)

                    override fun writeAscii(text: String) = output.writeBytes(text.toByteArray(StandardCharsets.US_ASCII))

                    override fun writeUtf8(text: String) = output.writeBytes(text.encodeToByteArray())
                },
            )

        fun receive(sequence: String) = parser.accept(sequence.encodeToByteArray())

        fun stroke(
            keyCode: Int,
            character: Char,
        ): String {
            output.reset()
            val pressed = KeyEvent(source, KeyEvent.KEY_PRESSED, 0L, 0, keyCode, character, KeyEvent.KEY_LOCATION_NUMPAD)
            val typed = KeyEvent(source, KeyEvent.KEY_TYPED, 0L, 0, KeyEvent.VK_UNDEFINED, character)
            val released = KeyEvent(source, KeyEvent.KEY_RELEASED, 0L, 0, keyCode, character, KeyEvent.KEY_LOCATION_NUMPAD)
            encoder.encodeKey(assertNotNull(mapper.keyPressed(pressed)))
            assertNull(mapper.keyTyped(typed))
            encoder.encodeKey(assertNotNull(mapper.keyReleased(released)))
            return output.toString(StandardCharsets.US_ASCII)
        }
    }

    private fun onEdt(block: () -> Unit) = SwingUtilities.invokeAndWait(block)
}
