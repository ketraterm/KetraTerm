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
package io.github.ketraterm.session

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.input.TerminalInputEncoders
import io.github.ketraterm.input.api.TerminalInputEncoderFactory
import io.github.ketraterm.input.event.TerminalKey
import io.github.ketraterm.input.event.TerminalKeyEvent
import io.github.ketraterm.input.event.TerminalModifiers
import io.github.ketraterm.protocol.host.TerminalHostOutput
import io.github.ketraterm.testkit.MockConnector
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalKeyboardProtocolTest {
    @Test
    fun `ordinary Kitty admission rolls back all streamed chunks after an encoding failure`() =
        runTest {
            val connector = MockConnector()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val failure = IllegalArgumentException("encoding failed after staging chunks")
            val factory =
                TerminalInputEncoderFactory { modes, output, policy ->
                    var writes = 0
                    val sink =
                        object : TerminalHostOutput by output {
                            override fun writeBytes(
                                bytes: ByteArray,
                                offset: Int,
                                length: Int,
                            ) {
                                output.writeBytes(bytes, offset, length)
                                if (++writes == 2) throw failure
                            }
                        }
                    TerminalInputEncoders.create(modes, sink, policy)
                }
            TerminalSession
                .create(
                    TerminalBuffers.create(20, 3),
                    connector,
                    kittyKeyboardSupportedFlags = 31,
                    workerDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                    cleanupDispatcher = dispatcher,
                    inputEncoderFactory = factory,
                ).use { session ->
                    session.start(20, 3)
                    connector.feedFromHost("\u001b[>24u".encodeToByteArray())
                    session.submitBytes("before".encodeToByteArray())
                    assertSame(
                        failure,
                        assertThrows(IllegalArgumentException::class.java) {
                            session.submitInput(TerminalKeyEvent.text("a".repeat(100)))
                        },
                    )
                    assertEquals(TerminalInputAdmission.ACCEPTED, session.submitInput(TerminalKeyEvent.text("z")))
                    runCurrent()
                    assertArrayEquals("before\u001b[0;;122u".encodeToByteArray(), connector.writtenBytes)
                    assertFalse(session.isClosed)
                    assertNull(session.failure)
                }
            runCurrent()
        }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `wire Kitty negotiation supports long ordinary and compound key events`(compound: Boolean) =
        runTest {
            val connector = MockConnector()
            val dispatcher = StandardTestDispatcher(testScheduler)
            TerminalSession
                .create(
                    TerminalBuffers.create(20, 3),
                    connector,
                    kittyKeyboardSupportedFlags = 31,
                    workerDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                    cleanupDispatcher = dispatcher,
                ).use { session ->
                    session.start(20, 3)
                    connector.feedFromHost("\u001b[>24u".encodeToByteArray())
                    val text = "a".repeat(20) + "👩‍💻界".repeat(32)
                    val event = TerminalKeyEvent.text(text)
                    val admission =
                        if (compound) {
                            session.submitInput(
                                listOf(
                                    TerminalKeyEvent.key(TerminalKey.UP),
                                    event,
                                    TerminalKeyEvent.key(TerminalKey.ENTER),
                                ),
                            )
                        } else {
                            session.submitInput(event)
                        }
                    assertEquals(TerminalInputAdmission.ACCEPTED, admission)
                    session.submitInput(TerminalKeyEvent.text("z"))
                    runCurrent()
                    val encodedText = "\u001b[0;;" + text.codePoints().toArray().joinToString(":") + "u"
                    val expected = (if (compound) "\u001b[A" + encodedText + "\u001b[13u" else encodedText) + "\u001b[0;;122u"
                    assertArrayEquals(expected.encodeToByteArray(), connector.writtenBytes)
                    assertFalse(session.isClosed)
                    assertNull(session.failure)
                }
            runCurrent()
        }

    @ParameterizedTest
    @ValueSource(ints = [64, 128, 192])
    fun `wire mode transitions preserve legacy lock routing NumLock and Kitty metadata`(locks: Int) =
        runTest {
            val connector = MockConnector()
            val dispatcher = StandardTestDispatcher(testScheduler)
            TerminalSession
                .create(
                    TerminalBuffers.create(20, 3),
                    connector,
                    kittyKeyboardSupportedFlags = 31,
                    workerDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                    cleanupDispatcher = dispatcher,
                ).use { session ->
                    session.start(20, 3)
                    session.encodeKey(TerminalKeyEvent.key(TerminalKey.TAB, locks))
                    connector.feedFromHost("\u001b[?1h\u001b=\u001b[>4;2m".encodeToByteArray())
                    session.encodeKey(TerminalKeyEvent.key(TerminalKey.TAB, locks))
                    session.encodeKey(TerminalKeyEvent.key(TerminalKey.UP, locks))
                    session.encodeKey(TerminalKeyEvent.codepoint('A'.code, locks))
                    session.encodeKey(TerminalKeyEvent.key(TerminalKey.NUMPAD_7, locks))
                    session.encodeKey(TerminalKeyEvent.key(TerminalKey.TAB, locks or TerminalModifiers.CTRL))
                    connector.feedFromHost("\u001b[>8u".encodeToByteArray())
                    session.encodeKey(TerminalKeyEvent.key(TerminalKey.TAB, locks))
                    session.encodeKey(TerminalKeyEvent.key(TerminalKey.NUMPAD_7, locks))
                    connector.feedFromHost("\u001b[<u\u001b[?1l\u001b>\u001b[>4;0m".encodeToByteArray())
                    session.encodeKey(TerminalKeyEvent.key(TerminalKey.TAB, locks))
                    session.encodeKey(TerminalKeyEvent.key(TerminalKey.UP, locks))
                    runCurrent()
                    val keypad = if (TerminalModifiers.hasNumLock(locks)) "7" else "\u001bOw"
                    val expected =
                        "\t\t\u001bOAA" + keypad +
                            "\u001b[27;5;9~\u001b[9;${locks + 1}u\u001b[57406;${locks + 1}u\t\u001b[A"
                    assertArrayEquals(expected.encodeToByteArray(), connector.writtenBytes)
                    assertFalse(session.isClosed)
                }
            runCurrent()
        }
}
