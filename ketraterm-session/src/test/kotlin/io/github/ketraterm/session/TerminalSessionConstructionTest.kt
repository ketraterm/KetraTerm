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
import io.github.ketraterm.core.api.TerminalBuffer
import io.github.ketraterm.host.*
import io.github.ketraterm.input.TerminalInputEncoders
import io.github.ketraterm.input.api.TerminalInputEncoder
import io.github.ketraterm.input.api.TerminalInputEncoderFactory
import io.github.ketraterm.input.event.TerminalKey
import io.github.ketraterm.input.event.TerminalKeyEvent
import io.github.ketraterm.input.event.TerminalPasteEvent
import io.github.ketraterm.input.event.TerminalTextReplacementEvent
import io.github.ketraterm.input.policy.BackspacePolicy
import io.github.ketraterm.input.policy.TerminalInputPolicy
import io.github.ketraterm.parser.api.TerminalOutputParser
import io.github.ketraterm.parser.api.TerminalOutputParserFactory
import io.github.ketraterm.parser.api.TerminalParsers
import io.github.ketraterm.testkit.MockConnector
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.*

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalSessionConstructionTest {
    @Test
    fun `custom encoders share ordered output and retain bulk admission modes and policy`() =
        runTest {
            val connector = MockConnector()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val calls = mutableListOf<String>()
            val factory =
                TerminalInputEncoderFactory { modes, output, policy ->
                    val delegate = TerminalInputEncoders.create(modes, output, policy)
                    object : TerminalInputEncoder by delegate {
                        override fun encodeKey(event: TerminalKeyEvent) {
                            calls += "key"
                            delegate.encodeKey(event)
                        }

                        override fun encodePaste(event: TerminalPasteEvent) {
                            calls += "paste"
                            delegate.encodePaste(event)
                        }

                        override fun encodeTextReplacement(event: TerminalTextReplacementEvent) {
                            calls += "replacement"
                            delegate.encodeTextReplacement(event)
                        }

                        override fun setInputPolicy(policy: TerminalInputPolicy) {
                            calls += "policy"
                            delegate.setInputPolicy(policy)
                        }
                    }
                }
            TerminalSession
                .create(
                    TerminalBuffers.create(10, 3),
                    connector,
                    inputPolicy = TerminalInputPolicy(backspacePolicy = BackspacePolicy.BACKSPACE),
                    inputEncoderFactory = factory,
                    workerDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                ).use { session ->
                    session.start(10, 3)
                    connector.feedFromHost("\u001b[5n\u001b[?2004h".toByteArray())
                    session.encodeKey(TerminalKeyEvent.codepoint('k'.code))
                    session.encodePaste(TerminalPasteEvent("p"))
                    session.encodeTextReplacement(TerminalTextReplacementEvent(0, 1, "r"))
                    session.setInputPolicy(TerminalInputPolicy(backspacePolicy = BackspacePolicy.DELETE))
                    connector.feedFromHost("\u001b[?2004l\u001b[5n".toByteArray())
                    session.encodeKey(TerminalKeyEvent.key(TerminalKey.BACKSPACE))
                    assertArrayEquals(byteArrayOf(), connector.writtenBytes)
                    runCurrent()
                    val expected =
                        "\u001b[0nk\u001b[200~p\u001b[201~" +
                            "\u0008\u001b[200~r\u001b[201~\u001b[0n\u007f"
                    assertEquals(expected, connector.writtenBytes.toString(Charsets.UTF_8))
                    assertEquals(2, calls.count { it == "key" })
                    assertEquals(1, calls.count { it == "paste" })
                    assertEquals(1, calls.count { it == "replacement" })
                }
        }

    @Test
    fun `split core and render collaborators assemble with services`() =
        runTest {
            val backing = TerminalBuffers.create(10, 3)
            val core = object : TerminalBuffer by backing {}
            val connector = MockConnector()
            val dispatcher = StandardTestDispatcher(testScheduler)
            TerminalSession
                .create(
                    core,
                    backing,
                    connector,
                    workerDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                ).use { session ->
                    session.start(10, 3)
                    connector.feedFromHost("ok\u001b[5n".toByteArray())
                    session.readRenderFrame { frame ->
                        assertEquals(10, frame.columns)
                        assertEquals(2, frame.cursor.column)
                    }
                    runCurrent()
                    assertEquals("\u001b[0n", connector.writtenBytes.toString(Charsets.UTF_8))
                }
        }

    @Test
    fun `incompatible render dimensions reject before connector ownership or factories`() {
        val connector = MockConnector()
        var created = false
        assertThrows(IllegalArgumentException::class.java) {
            TerminalSession.create(
                TerminalBuffers.create(10, 3),
                TerminalBuffers.create(11, 3),
                connector,
                parserFactory =
                    TerminalOutputParserFactory { _, _ ->
                        created = true
                        error("must not create parser")
                    },
            )
        }
        assertFalse(created)
        assertFalse(connector.isClosed)
    }

    @ParameterizedTest
    @ValueSource(strings = ["parser", "first encoder", "second encoder", "shared encoder", "construction output", "construction modes"])
    fun `factory failures leave connector ownership with caller`(stage: String) {
        val connector = MockConnector()
        val failure = IllegalStateException("construction failed")
        var created = 0
        var first: TerminalInputEncoder? = null
        val factory =
            TerminalInputEncoderFactory { modes, output, policy ->
                created++
                if (stage == "first encoder" || (stage == "second encoder" && created == 2)) throw failure
                if (stage == "construction output") output.writeAscii("invalid")
                if (stage == "construction modes") modes.getInputModeBits()
                if (stage == "shared encoder" && created == 2) {
                    first!!
                } else {
                    TerminalInputEncoders.create(modes, output, policy).also { first = it }
                }
            }
        val thrown =
            assertThrows(RuntimeException::class.java) {
                TerminalSession.create(
                    TerminalBuffers.create(10, 3),
                    connector,
                    inputEncoderFactory = factory,
                    parserFactory = if (stage == "parser") TerminalOutputParserFactory { _, _ -> throw failure } else null,
                )
            }
        if (stage in listOf("parser", "first encoder", "second encoder")) assertSame(failure, thrown)
        assertFalse(connector.isClosed)
        assertTrue(connector.resizeCalls.isEmpty())
        assertArrayEquals(byteArrayOf(), connector.writtenBytes)
    }

    @Test
    fun `rejected input policy leaves admitted bulk and mode reports consistent`() =
        runTest {
            val connector = MockConnector()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val failure = UnsupportedOperationException("unsupported policy")
            val factory =
                TerminalInputEncoderFactory { modes, output, policy ->
                    val delegate = TerminalInputEncoders.create(modes, output, policy)
                    object : TerminalInputEncoder by delegate {
                        override fun setInputPolicy(policy: TerminalInputPolicy) {
                            if (policy.backspacePolicy == BackspacePolicy.DELETE) throw failure
                            delegate.setInputPolicy(policy)
                        }
                    }
                }
            TerminalSession
                .create(
                    TerminalBuffers.create(10, 3),
                    connector,
                    inputPolicy = TerminalInputPolicy(backspacePolicy = BackspacePolicy.BACKSPACE),
                    inputEncoderFactory = factory,
                    workerDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                ).use { session ->
                    session.start(10, 3)
                    assertSame(
                        failure,
                        assertThrows(UnsupportedOperationException::class.java) {
                            session.setInputPolicy(TerminalInputPolicy(backspacePolicy = BackspacePolicy.DELETE))
                        },
                    )
                    session.encodeTextReplacement(TerminalTextReplacementEvent(0, 1, "x"))
                    session.encodeKey(TerminalKeyEvent.key(TerminalKey.BACKSPACE))
                    connector.feedFromHost("\u001b[?67\$p".toByteArray())
                    runCurrent()
                    assertEquals("\u0008x\u0008\u001b[?67;1\$y", connector.writtenBytes.toString(Charsets.UTF_8))
                    assertNull(session.failure)
                }
        }

    @Test
    fun `custom bulk encoding rejects overflowing work before invocation`() =
        runTest {
            val connector = MockConnector()
            var encoded = false
            val factory =
                TerminalInputEncoderFactory { modes, output, policy ->
                    val delegate = TerminalInputEncoders.create(modes, output, policy)
                    object : TerminalInputEncoder by delegate {
                        override fun encodeTextReplacement(event: TerminalTextReplacementEvent) {
                            encoded = true
                            fail<Unit>("Over-budget replacement reached custom encoder")
                        }
                    }
                }
            TerminalSession
                .create(
                    TerminalBuffers.create(10, 3),
                    connector,
                    inputEncoderFactory = factory,
                    workerDispatcher = StandardTestDispatcher(testScheduler),
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                ).use { session ->
                    session.start(10, 3)
                    session.encodeTextReplacement(TerminalTextReplacementEvent(Int.MAX_VALUE, Int.MAX_VALUE, "x"))
                    runCurrent()
                    assertInstanceOf(OutboundCapacityException::class.java, session.failure)
                    assertFalse(encoded)
                    assertEquals(1, connector.closeCount)
                    assertArrayEquals(byteArrayOf(), connector.writtenBytes)
                }
        }

    @Test
    fun `parser factory receives the live clipboard write budget`() =
        runTest {
            val connector = MockConnector()
            val writes = mutableListOf<String>()
            val text = "a".repeat(6000)
            val stream = ("\u001b]52;c;" + Base64.getEncoder().encodeToString(text.toByteArray()) + "\u0007").toByteArray()
            val policy =
                HostPolicy(
                    clipboardPolicy = TerminalClipboardPolicy(writePermission = TerminalClipboardPermission.ALLOW, maxDecodedBytes = 6000),
                )
            TerminalSession
                .create(
                    TerminalBuffers.create(10, 3),
                    connector,
                    hostPolicy = policy,
                    hostEvents =
                        object : HostEventSink by HostEventSink.NONE {
                            override fun terminalClipboardWrite(event: TerminalClipboardWriteEvent) {
                                writes += event.text
                            }
                        },
                    parserFactory = TerminalOutputParserFactory(TerminalParsers::create),
                    workerDispatcher = StandardTestDispatcher(testScheduler),
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                ).use { session ->
                    session.start(10, 3)
                    for (byte in stream) connector.feedFromHost(byteArrayOf(byte))
                    assertEquals(listOf(text), writes)
                    session.setHostPolicy(policy.copy(clipboardPolicy = policy.clipboardPolicy.copy(maxDecodedBytes = 5999)))
                    connector.feedFromHost(stream)
                    assertEquals(listOf(text), writes)
                    session.setHostPolicy(policy)
                    connector.feedFromHost(stream)
                    assertEquals(listOf(text, text), writes)
                }
        }

    @Test
    fun `parser customization retains clipboard policy startup resize and EOF`() =
        runTest {
            val connector = MockConnector()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val ready = MutableStateFlow(false)
            var eof = 0
            var reads = 0
            val terminal = TerminalBuffers.create(10, 3)
            val factory =
                TerminalOutputParserFactory { sink, budget ->
                    val delegate = TerminalParsers.create(sink, budget)
                    object : TerminalOutputParser by delegate {
                        override fun endOfInput() {
                            eof++
                            delegate.endOfInput()
                        }
                    }
                }
            val session =
                TerminalSession.create(
                    terminal,
                    connector,
                    parserFactory = factory,
                    hostPolicy = HostPolicy(clipboardPolicy = TerminalClipboardPolicy(readPermission = TerminalClipboardPermission.ALLOW)),
                    clipboardReader =
                        TerminalClipboardReader {
                            reads++
                            TerminalClipboardReadResult.Text("ok")
                        },
                    shellIntegration =
                        TerminalShellIntegrationFactory.host(
                            TerminalShellIntegrationState(),
                            MutableStateFlow(null),
                            ready,
                        ),
                    startupCommand = TerminalStartupCommand("hello"),
                    workerDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                )
            session.use {
                session.start(10, 3)
                connector.feedFromHost("\u001b]52;c;?\u001b\\".toByteArray())
                runCurrent()
                assertEquals("\u001b]52;c;b2s=\u001b\\", connector.writtenBytes.toString(Charsets.UTF_8))
                assertEquals(1, reads)
                session.setHostPolicy(
                    HostPolicy(clipboardPolicy = TerminalClipboardPolicy(readPermission = TerminalClipboardPermission.DENY)),
                )
                connector.feedFromHost("\u001b]52;c;?\u001b\\".toByteArray())
                runCurrent()
                assertEquals(1, reads)
                assertEquals("\u001b]52;c;b2s=\u001b\\\u001b]52;c;\u001b\\", connector.writtenBytes.toString(Charsets.UTF_8))
                ready.value = true
                runCurrent()
                assertEquals(TerminalStartupCommandStatus.SUBMITTED, session.startupCommandStatus!!.value)
                assertTrue(connector.writtenBytes.toString(Charsets.UTF_8).endsWith("hello\r"))
                connector.feedFromHost("\u001b[?3hwide".toByteArray())
                assertEquals(132, terminal.width)
                assertEquals(132 to 3, connector.resizeCalls.last())
                assertEquals("wide", terminal.getLineAsString(0))
                connector.feedFromHost(byteArrayOf(0xe2.toByte(), 0x82.toByte()))
            }
            assertEquals(1, eof)
            assertTrue(connector.isClosed)
            assertEquals("wide�", terminal.getLineAsString(0))
        }
}
