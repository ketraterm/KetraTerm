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
import io.github.ketraterm.input.event.*
import io.github.ketraterm.testkit.MockConnector
import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.transport.TerminalConnectorListener
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TerminalStartupCommandTest {
    @Test
    fun `prompt delivered synchronously during connector start executes once`() {
        val delegate = MockConnector()
        val connector =
            object : TerminalConnector by delegate {
                override fun start(listener: TerminalConnectorListener) {
                    delegate.start(listener)
                    delegate.feedFromHost(PROMPT.toByteArray())
                }
            }
        session(connector).use { session ->
            assertEquals("echo ready\r", delegate.writtenBytes.decodeToString())
            assertEquals(TerminalStartupCommandStatus.SUBMITTED, session.startupCommandStatus?.value)
        }
    }

    @Test
    fun `unconfigured sessions do not submit commands on prompt markers`() {
        val connector = MockConnector()
        TerminalSession.create(TerminalBuffers.create(40, 4), connector).use { session ->
            session.start(40, 4)
            connector.feedFromHost(PROMPT.toByteArray())
            assertNull(session.startupCommandStatus)
            assertEquals("", connector.writtenBytes.decodeToString())
        }
    }

    @Test
    fun `every prompt byte split submits exact command once and leaves session interactive`() {
        val prompt = PROMPT.toByteArray()
        for (split in 0..prompt.size) {
            val connector = MockConnector()
            session(connector, "  echo 'héllo'  ").use { session ->
                connector.feedFromHost(prompt, 0, split)
                if (split < prompt.size) assertEquals("", connector.writtenBytes.decodeToString(), "split=$split")
                connector.feedFromHost(prompt, split, prompt.size - split)
                assertEquals("  echo 'héllo'  \r", connector.writtenBytes.decodeToString(), "split=$split")
                assertEquals(TerminalStartupCommandStatus.SUBMITTED, session.startupCommandStatus?.value)
                connector.feedFromHost(("\u001B]133;C\u0007output\r\n\u001B]133;D;0\u0007" + PROMPT + "\u001Bc" + PROMPT).toByteArray())
                session.encodeKey(TerminalKeyEvent(codepoint = 'x'.code))
                assertEquals("  echo 'héllo'  \rx", connector.writtenBytes.decodeToString())
                assertFalse(session.isClosed)
            }
        }
    }

    @Test
    fun `ordinary output and orphan prompt end never trigger execution`() {
        val connector = MockConnector()
        session(connector).use { session ->
            connector.feedFromHost("Welcome\r\n$ \u001B]133;B\u0007".toByteArray())
            assertEquals("", connector.writtenBytes.decodeToString())
            assertEquals(TerminalStartupCommandStatus.WAITING, session.startupCommandStatus?.value)
        }
    }

    @Test
    fun `submission uses final input modes and follows pending responses`() {
        val connector = MockConnector()
        session(connector).use {
            connector.feedFromHost((PROMPT + "\u001B[?2004h\u001B[5n").toByteArray())
            assertEquals("\u001B[0n\u001B[200~echo ready\u001B[201~\r", connector.writtenBytes.decodeToString())
        }
    }

    @Test
    fun `a command starting in the same chunk invalidates readiness`() {
        val connector = MockConnector()
        session(connector).use {
            connector.feedFromHost((PROMPT + "\u001B]133;C\u0007").toByteArray())
            assertEquals("", connector.writtenBytes.decodeToString())
            connector.feedFromHost(("\u001B]133;D;0\u0007" + PROMPT).toByteArray())
            assertEquals("echo ready\r", connector.writtenBytes.decodeToString())
        }
    }

    @Test
    fun `alternate prompt markers do not submit or become ready after exit`() {
        val connector = MockConnector()
        session(connector).use {
            connector.feedFromHost(("\u001B[?1049h" + PROMPT).toByteArray())
            connector.feedFromHost("\u001B[?1049l".toByteArray())
            assertEquals("", connector.writtenBytes.decodeToString())
            connector.feedFromHost(PROMPT.toByteArray())
            assertEquals("echo ready\r", connector.writtenBytes.decodeToString())
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["key", "paste", "replacement"])
    fun `early user input cancels instead of combining commands`(kind: String) {
        val connector = MockConnector()
        session(connector).use { session ->
            when (kind) {
                "key" -> session.encodeKey(TerminalKeyEvent(codepoint = 'x'.code))
                "paste" -> session.encodePaste(TerminalPasteEvent("x"))
                else -> session.encodeTextReplacement(TerminalTextReplacementEvent(0, 0, "x"))
            }
            connector.feedFromHost(PROMPT.toByteArray())
            assertEquals("x", connector.writtenBytes.decodeToString())
            assertEquals(TerminalStartupCommandStatus.CANCELLED_BY_INPUT, session.startupCommandStatus?.value)
        }
    }

    @Test
    fun `focus empty paste and key release leave startup pending`() {
        val connector = MockConnector()
        session(connector).use { session ->
            session.encodePaste(TerminalPasteEvent(""))
            session.encodeFocus(TerminalFocusEvent(true))
            session.encodeKey(TerminalKeyEvent(codepoint = 'x'.code, type = TerminalKeyEventType.RELEASE))
            connector.feedFromHost(PROMPT.toByteArray())
            assertEquals("echo ready\r", connector.writtenBytes.decodeToString())
        }
    }

    @Test
    fun `close before readiness discards pending submission`() {
        val connector = MockConnector()
        val session = session(connector)
        session.close()
        session.onBytes(PROMPT.toByteArray(), 0, PROMPT.length)
        assertEquals(TerminalStartupCommandStatus.CLOSED, session.startupCommandStatus?.value)
        assertEquals("", connector.writtenBytes.decodeToString())
    }

    @Test
    fun `write failure cannot retry a partially submitted command`() {
        val delegate = MockConnector()
        val connector =
            object : TerminalConnector by delegate {
                override fun write(
                    bytes: ByteArray,
                    offset: Int,
                    length: Int,
                ): Unit = throw IOException("write failed")
            }
        session(connector).use { session ->
            assertThrows(IOException::class.java) { delegate.feedFromHost(PROMPT.toByteArray()) }
            assertEquals(TerminalStartupCommandStatus.FAILED, session.startupCommandStatus?.value)
            assertDoesNotThrow { delegate.feedFromHost(PROMPT.toByteArray()) }
        }
    }

    @Test
    fun `command and Enter cannot interleave with concurrent typing`() {
        val enteredWrite = CountDownLatch(1)
        val continueWrite = CountDownLatch(1)
        val delegate = MockConnector()
        val connector =
            object : TerminalConnector by delegate {
                override fun write(
                    bytes: ByteArray,
                    offset: Int,
                    length: Int,
                ) {
                    enteredWrite.countDown()
                    continueWrite.await()
                    delegate.write(bytes, offset, length)
                }
            }
        session(connector).use { session ->
            SessionTestThread("startup-prompt") { delegate.feedFromHost(PROMPT.toByteArray()) }.use { prompt ->
                try {
                    assertTrue(enteredWrite.await(SESSION_THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    SessionTestThread("startup-concurrent-input") {
                        session.encodeKey(TerminalKeyEvent(codepoint = 'x'.code))
                    }.use { typing ->
                        try {
                            typing.awaitBlockedBy(prompt)
                            assertEquals("", delegate.writtenBytes.decodeToString())
                        } finally {
                            continueWrite.countDown()
                        }
                        prompt.awaitCompletion()
                        typing.awaitCompletion()
                    }
                } finally {
                    continueWrite.countDown()
                }
            }
            assertEquals("echo ready\rx", delegate.writtenBytes.decodeToString())
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["", " ", "echo x\nexit", "echo x\r", "echo\tx", "\u001B[200~x", "x\u0000"])
    fun `invalid command lines are rejected before launch`(text: String) {
        assertThrows(IllegalArgumentException::class.java) { TerminalStartupCommand(text) }
    }

    @Test
    fun `blank settings disable startup and length limit is exact`() {
        assertNull(TerminalStartupCommand.fromText("  "))
        assertEquals(TerminalStartupCommand.MAX_LENGTH, TerminalStartupCommand("x".repeat(TerminalStartupCommand.MAX_LENGTH)).text.length)
        assertThrows(IllegalArgumentException::class.java) { TerminalStartupCommand("x".repeat(TerminalStartupCommand.MAX_LENGTH + 1)) }
    }

    private fun session(
        connector: TerminalConnector,
        command: String = "echo ready",
    ): TerminalSession =
        TerminalSession
            .create(
                terminal = TerminalBuffers.create(width = 40, height = 4),
                connector = connector,
                startupCommand = TerminalStartupCommand(command),
                workerDispatcher = StandardTestDispatcher(),
            ).also { it.start(40, 4) }

    private companion object {
        const val PROMPT = "\u001B]133;A\u0007$ \u001B]133;B\u0007"
    }
}
