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
package io.github.ketraterm.pty

import io.github.ketraterm.input.event.TerminalKey
import io.github.ketraterm.input.event.TerminalKeyEvent
import io.github.ketraterm.input.event.TerminalPasteEvent
import io.github.ketraterm.protocol.TerminalCapabilityIdentity
import io.github.ketraterm.session.TerminalSession
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PtySessionTest {
    @Test
    fun `default environment advertises the shared terminal capability identity`() {
        val environment = PtyOptions.defaultEnvironment()
        assertEquals(TerminalCapabilityIdentity.TERM_NAME, environment.getValue("TERM"))
        assertEquals(TerminalCapabilityIdentity.COLOR_TERM_TRUECOLOR, environment.getValue("COLORTERM"))
    }

    @Test
    fun `pty stdout is parsed into terminal core through shared session`() {
        val process = FakePtyProcess(inputBytes = "hello\u001B[5n".ascii())
        val session =
            PtySessions.start(
                options =
                    PtyOptions(
                        command = listOf("fake"),
                        columns = 10,
                        rows = 3,
                        readerThreadName = "terminal-pty-test-reader",
                    ),
                processFactory = FixedProcessFactory(process),
            )

        waitUntil { session.terminal.getLineAsString(0) == "hello" }

        assertEquals("hello", session.terminal.getLineAsString(0))
    }

    @Test
    fun `parser core responses are written back to pty stdin`() {
        val process = FakePtyProcess(inputBytes = "\u001B[6n".ascii())
        val session =
            PtySessions.start(
                options = PtyOptions(command = listOf("fake"), columns = 10, rows = 3),
                processFactory = FixedProcessFactory(process),
            )

        waitUntil { process.outputText() == "\u001B[1;1R" }

        assertEquals("\u001B[1;1R", process.outputText())
        assertEquals(0, session.terminal.pendingResponseBytes)
    }

    @Test
    fun `input events are encoded to pty stdin through session serialization point`() {
        val process = FakePtyProcess.running()
        val session =
            PtySessions.start(
                options = PtyOptions(command = listOf("fake"), columns = 10, rows = 3),
                processFactory = FixedProcessFactory(process),
            )

        session.encodeKey(TerminalKeyEvent.codepoint('a'.code))

        assertEquals("a", process.outputText())
    }

    @Test
    fun `default pty input policy sends Return as CR even when newline mode is active`() {
        val process = FakePtyProcess.running()
        val session =
            PtySessions.start(
                options = PtyOptions(command = listOf("fake"), columns = 10, rows = 3),
                processFactory = FixedProcessFactory(process),
            )

        session.terminal.setNewLineMode(true)
        session.encodeKey(TerminalKeyEvent.key(TerminalKey.ENTER))

        assertEquals("\r", process.outputText())
    }

    @Test
    fun `default PTY paste canonicalizes unbracketed clipboard newlines to CR`() {
        val process = FakePtyProcess.running()
        val session =
            PtySessions.start(
                options = PtyOptions(command = listOf("fake"), columns = 10, rows = 3),
                processFactory = FixedProcessFactory(process),
            )

        session.encodePaste(TerminalPasteEvent("first\r\nsecond\nthird\rfourth"))

        assertEquals("first\rsecond\rthird\rfourth", process.outputText())
    }

    @Test
    fun `resize updates process and terminal dimensions`() {
        val process = FakePtyProcess.running()
        val session =
            PtySessions.start(
                options = PtyOptions(command = listOf("fake"), columns = 10, rows = 3),
                processFactory = FixedProcessFactory(process),
            )

        session.resize(columns = 20, rows = 5)

        assertEquals(20, session.terminal.width)
        assertEquals(5, session.terminal.height)
        assertEquals(listOf(10 to 3, 20 to 5), process.sizes)
    }

    @Test
    fun `ambiguous width option is applied before pty output is parsed`() {
        val process = FakePtyProcess(inputBytes = "\u20ACX".toByteArray(StandardCharsets.UTF_8))
        val session =
            PtySessions.start(
                options =
                    PtyOptions(
                        command = listOf("fake"),
                        columns = 6,
                        rows = 2,
                        treatAmbiguousAsWide = true,
                    ),
                processFactory = FixedProcessFactory(process),
            )

        waitUntil { session.terminal.getCodepointAt(2, 0) == 'X'.code }

        assertAll(
            { assertEquals(0x20AC, session.terminal.getCodepointAt(0, 0)) },
            { assertEquals(-1, session.terminal.getCodepointAt(1, 0)) },
            { assertEquals('X'.code, session.terminal.getCodepointAt(2, 0)) },
            { assertTrue(session.terminal.getModeSnapshot().treatAmbiguousAsWide) },
        )
    }

    @Test
    fun `close destroys process and does not fake an exit code`() {
        val process = FakePtyProcess.running()
        val session =
            PtySessions.start(
                options = PtyOptions(command = listOf("fake"), columns = 10, rows = 3),
                processFactory = FixedProcessFactory(process),
            )

        session.close()

        assertTrue(process.destroyed)
        assertNull(session.exitCode)
    }

    @Test
    fun `process exit is captured on shared session`() {
        val process = FakePtyProcess(inputBytes = ByteArray(0), exitCode = 7)
        val session =
            PtySessions.start(
                options = PtyOptions(command = listOf("fake"), columns = 10, rows = 3),
                processFactory = FixedProcessFactory(process),
            )

        waitUntil { session.exitCode == 7 }

        assertEquals(7, session.exitCode)
    }

    @Test
    fun `large output is parsed without losing bytes across connector chunks`() {
        val text = "x".repeat(20_000) + "\n"
        val process = FakePtyProcess(inputBytes = text.ascii())
        val session =
            PtySessions.start(
                options =
                    PtyOptions(
                        command = listOf("fake"),
                        columns = 200,
                        rows = 120,
                        maxHistory = 200,
                        readBufferSize = 17,
                    ),
                processFactory = FixedProcessFactory(process),
            )

        waitUntil { session.terminal.getAllAsString().count { it == 'x' } == 20_000 }

        assertEquals(20_000, session.terminal.getAllAsString().count { it == 'x' })
    }

    @Test
    fun `bell and title changes are delivered to PTY listener`() {
        val listener = RecordingPtyEventListener()
        val input = "\u0007\u001B]0;both\u001B\\".ascii()
        val process = FakePtyProcess(inputBytes = input)
        PtySessions.start(
            options =
                PtyOptions(
                    command = listOf("fake"),
                    columns = 10,
                    rows = 3,
                    eventListener = listener,
                ),
            processFactory = FixedProcessFactory(process),
        )

        waitUntil { listener.bells == 1 && listener.iconTitles == listOf("both") }

        assertEquals(1, listener.bells)
        assertEquals(listOf("both"), listener.iconTitles)
        assertEquals(listOf("both"), listener.windowTitles)
    }

    @Test
    fun `listener exception is reported through listenerFailed`() {
        val listener =
            object : PtyEventListener by PtyEventListener.NONE {
                val failures = mutableListOf<Exception>()

                override fun bell(session: TerminalSession): Unit = throw IllegalStateException("bell failed")

                override fun listenerFailed(
                    session: TerminalSession,
                    exception: Exception,
                ) {
                    failures += exception
                }
            }
        val process = FakePtyProcess(inputBytes = "\u0007".ascii())
        PtySessions.start(
            options = PtyOptions(command = listOf("fake"), eventListener = listener),
            processFactory = FixedProcessFactory(process),
        )

        waitUntil { listener.failures.isNotEmpty() }

        assertEquals(listOf("bell failed"), listener.failures.map { it.message })
    }

    private class FixedProcessFactory(
        private val process: FakePtyProcess,
    ) : PtyProcessFactory {
        override fun start(options: PtyOptions): PtyProcess = process
    }

    private class FakePtyProcess private constructor(
        override val input: InputStream,
        private val inputDrained: CountDownLatch?,
        private val exitCode: Int,
    ) : PtyProcess {
        constructor(
            inputBytes: ByteArray,
            exitCode: Int = 0,
        ) : this(DrainingByteArrayInputStream(inputBytes), null, exitCode)

        private val capturedOutput = ByteArrayOutputStream()
        override val output: OutputStream = capturedOutput
        var destroyed: Boolean = false
            private set
        val sizes = mutableListOf<Pair<Int, Int>>()

        override fun isAlive(): Boolean = !destroyed

        override fun waitFor(): Int {
            inputDrained?.await(1, TimeUnit.SECONDS)
            if (input is DrainingByteArrayInputStream) {
                input.drained.await(1, TimeUnit.SECONDS)
            }
            return exitCode
        }

        override fun destroy() {
            destroyed = true
            if (input is BlockingInputStream) {
                input.release()
            }
        }

        override fun resize(
            columns: Int,
            rows: Int,
        ) {
            sizes += columns to rows
        }

        fun outputText(): String = capturedOutput.toString(StandardCharsets.UTF_8)

        companion object {
            fun running(exitCode: Int = 0): FakePtyProcess {
                val input = BlockingInputStream()
                return FakePtyProcess(input, input.released, exitCode)
            }
        }
    }

    private class DrainingByteArrayInputStream(
        bytes: ByteArray,
    ) : ByteArrayInputStream(bytes) {
        val drained = CountDownLatch(1)

        override fun read(): Int {
            val value = super.read()
            if (value < 0) drained.countDown()
            return value
        }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            val count = super.read(buffer, offset, length)
            if (count < 0) drained.countDown()
            return count
        }
    }

    private class BlockingInputStream : InputStream() {
        val released = CountDownLatch(1)

        override fun read(): Int {
            released.await(1, TimeUnit.SECONDS)
            return -1
        }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            released.await(1, TimeUnit.SECONDS)
            return -1
        }

        fun release() {
            released.countDown()
        }
    }

    private class RecordingPtyEventListener : PtyEventListener {
        var bells: Int = 0
        val iconTitles = mutableListOf<String>()
        val windowTitles = mutableListOf<String>()

        override fun bell(session: TerminalSession) {
            bells++
        }

        override fun iconTitleChanged(
            session: TerminalSession,
            title: String,
        ) {
            iconTitles += title
        }

        override fun windowTitleChanged(
            session: TerminalSession,
            title: String,
        ) {
            windowTitles += title
        }

        override fun resizeWindow(
            session: TerminalSession,
            rows: Int,
            columns: Int,
        ) {
            // No-op for tests
        }

        override fun listenerFailed(
            session: TerminalSession,
            exception: Exception,
        ) = Unit
    }

    private fun String.ascii(): ByteArray = toByteArray(StandardCharsets.US_ASCII)

    private fun waitUntil(
        timeoutMillis: Long = 1000,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue(condition(), "condition was not met within ${timeoutMillis}ms")
    }
}
