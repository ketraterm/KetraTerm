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

import io.github.ketraterm.host.HostPolicy
import io.github.ketraterm.host.TerminalClipboardPermission
import io.github.ketraterm.host.TerminalClipboardPolicy
import io.github.ketraterm.host.TerminalClipboardReadRequest
import io.github.ketraterm.input.event.TerminalKey
import io.github.ketraterm.input.event.TerminalKeyEvent
import io.github.ketraterm.input.event.TerminalPasteEvent
import io.github.ketraterm.protocol.TerminalCapabilityIdentity
import io.github.ketraterm.render.api.TerminalRenderCellFlags
import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.session.*
import io.github.ketraterm.shell.integration.OscShellIntegration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.*
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds

class PtySessionTest {
    @Test
    fun `option snapshots detach launch collections and immutable updates preserve the original`() {
        val command = mutableListOf("shell", "-l")
        val environment = mutableMapOf("TERM" to "test")
        val original =
            PtyOptions.create {
                it.command = command
                it.environment = environment
                it.readBufferSize = 4096
            }
        command.clear()
        environment.clear()
        assertEquals(listOf("shell", "-l"), original.command)
        assertEquals(mapOf("TERM" to "test"), original.environment)
        assertThrows(UnsupportedOperationException::class.java) { (original.command as MutableList<String>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (original.environment as MutableMap<String, String>).clear() }
        val copy = original.copy { it.readBufferSize = 2048 }
        assertEquals(4096, original.readBufferSize)
        assertEquals(2048, copy.readBufferSize)
        assertEquals(original, copy.copy { it.readBufferSize = 4096 })
        assertThrows(IllegalArgumentException::class.java) { original.copy { it.command = emptyList() } }
        assertThrows(IllegalArgumentException::class.java) { original.copy { it.readBufferSize = 0 } }
        assertEquals(original.hashCode(), original.toBuilder().build().hashCode())
    }

    private val sessions = mutableListOf<TerminalSession>()

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `PTY assembly retains the host shell source through creation and start`(startImmediately: Boolean) {
        val first = TerminalShellCommandLineSnapshot("git status", 4, 6, 1)
        val source = MutableStateFlow<TerminalShellCommandLineSnapshot?>(first)
        val process = FakePtyProcess.running()
        val state = TerminalShellIntegrationState()
        val options =
            PtyOptions.create { draft ->
                draft.command = listOf("fake")
                draft.shellIntegration = TerminalShellIntegrationFactory.host(state, source)
            }
        val factory = FixedProcessFactory(process)
        val session = if (startImmediately) PtySessions.start(options, factory) else PtySessions.create(options, factory)
        sessions += session

        assertSame(state, session.shellIntegrationState)
        assertSame(first, session.activeShellCommandLine())
        val changed = TerminalShellCommandLineSnapshot("git diff", 8, 10, 1)
        source.value = changed
        assertSame(changed, session.activeShellCommandLine())
        source.value = null
        assertNull(session.activeShellCommandLine())

        session.close()
        source.value = first
        assertNull(session.activeShellCommandLine())
        assertTrue(process.destroyed)
        assertSame(first, source.value, "Closing the session must not mutate host-owned state")
    }

    @Test
    fun `PTY options require selected shell integration before starting a startup command`() {
        assertThrows(IllegalArgumentException::class.java) {
            PtyOptions.create { draft ->
                draft.command = listOf("fake")
                draft.startupCommand = TerminalStartupCommand("echo ready")
            }
        }
    }

    @Test
    fun `host prompt readiness submits a PTY startup command without OSC integration`() {
        val ready = MutableStateFlow(false)
        val state = TerminalShellIntegrationState()
        val expected = "echo ready\r"
        val process = FakePtyProcess.running(expectedOutputBytes = expected.length)
        val session =
            PtySessions.start(
                PtyOptions.create { draft ->
                    draft.command = listOf("fake")
                    draft.startupCommand = TerminalStartupCommand("echo ready")
                    draft.shellIntegration = TerminalShellIntegrationFactory.host(state, promptReady = ready)
                },
                FixedProcessFactory(process),
            )
        sessions += session
        assertEquals(TerminalStartupCommandStatus.WAITING, session.startupCommandStatus?.value)
        assertEquals("", process.outputText())

        ready.value = true
        process.awaitWrite()
        runBlocking {
            withTimeout(10.seconds) { requireNotNull(session.startupCommandStatus).first { it == TerminalStartupCommandStatus.SUBMITTED } }
        }
        assertEquals(expected, process.outputText())
        assertEquals(0, state.recordCount())
        session.close()
        assertTrue(ready.value, "Closing the session must preserve the host's readiness source")
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `PTY assembly applies shell protocol metadata only when explicitly selected`(integrationEnabled: Boolean) {
        val output =
            "\u001B]7;file:///workspace\u0007\u001B]133;A\u0007$ \u001B]133;B\u0007echo ready\r\n" +
                "\u001B]133;C\u0007ready\r\n\u001B]133;D;0\u0007\u001B[6n"
        val process = FakePtyProcess.running(inputBytes = output.ascii())
        val session =
            PtySessions.start(
                PtyOptions.create { draft ->
                    draft.command = listOf("fake")
                    draft.shellIntegration = if (integrationEnabled) OscShellIntegration else null
                },
                FixedProcessFactory(process),
            )
        sessions += session
        process.awaitWrite()

        assertEquals(if (integrationEnabled) 1 else 0, session.shellIntegrationState.recordCount())
        assertEquals(if (integrationEnabled) "file:///workspace" else null, session.currentWorkingDirectoryUri())
        if (integrationEnabled) {
            val state = session.shellIntegrationState
            assertEquals("echo ready", state.commandMetadata(state.latestCommandRecordId())?.commandText)
        }
    }

    @Test
    fun createdSessionDefersOutputUntilExplicitStart() {
        val process = FakePtyProcess.running(inputBytes = "\u001b[6n".ascii())
        val session =
            PtySessions.create(
                PtyOptions.create { draft ->
                    draft.command = listOf("fake")
                    draft.columns = 10
                    draft.rows = 3
                },
                FixedProcessFactory(process),
            )
        sessions += session
        assertSame(TerminalSessionState.Created, session.state.value)
        session.start(10, 3)
        process.awaitWrite()
        assertEquals("\u001b[1;1R", process.outputText())
    }

    @Test
    fun closingAnUnstartedSessionDestroysItsProcess() {
        val process = FakePtyProcess.running()
        val session =
            PtySessions.create(
                PtyOptions.create { draft ->
                    draft.command = listOf("fake")
                },
                FixedProcessFactory(process),
            )
        sessions += session
        assertSame(TerminalSessionState.Created, session.state.value)
        session.close()
        assertTrue(process.destroyed)
        assertTrue(session.isClosed)
    }

    @Test
    fun immediateStartupFailureDestroysTheCreatedProcess() {
        val failure = IOException("resize failed")
        val process = FakePtyProcess.running().apply { resizeFailure = failure }
        assertSame(
            failure,
            assertThrows(IOException::class.java) {
                PtySessions.start(
                    PtyOptions.create { draft ->
                        draft.command = listOf("fake")
                    },
                    FixedProcessFactory(process),
                )
            },
        )
        assertTrue(process.destroyed)
    }

    @ParameterizedTest
    @ValueSource(strings = ["text", "denied", "unavailable", "failed"])
    fun clipboardReadUsesTheAttachedSessionAndReturnsACompleteFrame(result: String) {
        val expected = "\u001b]52;pc;" + (if (result == "text") "cHR5" else "") + "\u001b\\"
        val process =
            FakePtyProcess.running(
                inputBytes = "\u001b]52;ppcc;?\u0007".ascii(),
                expectedOutputBytes = expected.length,
            )
        val requestedSession = AtomicReference<TerminalSession>()
        val requestedRead = AtomicReference<TerminalClipboardReadRequest>()
        val reportedFailure = AtomicReference<Exception>()
        val listener =
            object : PtyEventListener by PtyEventListener.NONE {
                override suspend fun readClipboard(
                    session: TerminalSession,
                    request: TerminalClipboardReadRequest,
                ): TerminalClipboardReadResult {
                    requestedSession.set(session)
                    requestedRead.set(request)
                    return when (result) {
                        "text" -> TerminalClipboardReadResult.Text("pty")
                        "denied" -> TerminalClipboardReadResult.Denied
                        "unavailable" -> TerminalClipboardReadResult.Unavailable
                        else -> error("private provider detail")
                    }
                }

                override fun listenerFailed(
                    session: TerminalSession,
                    exception: Exception,
                ) {
                    reportedFailure.set(exception)
                }
            }
        val session =
            startSession(
                options =
                    PtyOptions.create { draft ->
                        draft.command = listOf("fake")
                        draft.eventListener = listener
                        draft.hostPolicy =
                            HostPolicy(
                                clipboardPolicy = TerminalClipboardPolicy(readPermission = TerminalClipboardPermission.ALLOW),
                            )
                    },
                processFactory = FixedProcessFactory(process),
            )
        process.awaitWrite()
        assertSame(session, requestedSession.get())
        assertEquals("pc", requestedRead.get().selection.value)
        assertEquals(TerminalClipboardPermission.ALLOW, requestedRead.get().permission)
        assertEquals(expected, process.outputText())
        assertNull(reportedFailure.get(), "Provider details must not reach generic listener logging")
    }

    @AfterEach
    fun closeSessions() {
        sessions.forEach(TerminalSession::close)
    }

    @Test
    fun `default environment advertises the shared terminal capability identity`() {
        val environment = PtyOptions.defaultEnvironment()
        assertEquals(TerminalCapabilityIdentity.TERM_NAME, environment.getValue("TERM"))
        assertEquals(TerminalCapabilityIdentity.COLOR_TERM_TRUECOLOR, environment.getValue("COLORTERM"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["hello", "hello  ", "e\u0301 \u754c "])
    fun `pty stdout is parsed into terminal core through shared session`(text: String) {
        val process = FakePtyProcess(inputBytes = "$text\u001B[5n".toByteArray(StandardCharsets.UTF_8))
        val session =
            startSession(
                options =
                    PtyOptions.create { draft ->
                        draft.command = listOf("fake")
                        draft.columns = 10
                        draft.rows = 3
                        draft.readerThreadName = "terminal-pty-test-reader"
                    },
                processFactory = FixedProcessFactory(process),
            )

        awaitClosed(session)

        assertEquals(text, session.retainedText().lineSequence().first())
    }

    @Test
    fun `parser core responses are written back to pty stdin`() {
        val expected = "\u001B[1;1R\u001B[0n"
        val process = FakePtyProcess.running(inputBytes = "\u001B[6n\u001B[5n".ascii(), expectedOutputBytes = expected.length)
        startSession(
            options =
                PtyOptions.create { draft ->
                    draft.command = listOf("fake")
                    draft.columns = 10
                    draft.rows = 3
                },
            processFactory = FixedProcessFactory(process),
        )

        process.awaitWrite()

        assertEquals(expected, process.outputText())
    }

    @Test
    fun invalidModeReportCapabilitiesCannotLaunchAProcess() {
        var starts = 0
        val factory =
            object : PtyProcessFactory {
                override fun start(options: PtyOptions): PtyProcess {
                    starts++
                    error("must not launch")
                }
            }
        for (invalid in listOf(-1, 8)) {
            assertThrows(IllegalArgumentException::class.java) {
                startSession(
                    options =
                        PtyOptions.create { draft ->
                            draft.command = listOf("fake")
                            draft.modeReportCapabilities = invalid
                        },
                    processFactory = factory,
                )
            }
        }
        assertEquals(0, starts)
    }

    @Test
    fun modeReportCapabilitiesReachPtyResponses() {
        for (capabilities in listOf(0, io.github.ketraterm.protocol.TerminalHostModeCapability.POP_ON_BELL)) {
            val process = FakePtyProcess.running(inputBytes = "\u001B[?1043h\u001B[?1043\$p".ascii())

            startSession(
                options =
                    PtyOptions.create { draft ->
                        draft.command = listOf("fake")
                        draft.columns = 10
                        draft.rows = 3
                        draft.modeReportCapabilities = capabilities
                    },
                processFactory = FixedProcessFactory(process),
            )
            process.awaitWrite()
            assertEquals(if (capabilities == 0) "\u001B[?1043;0\$y" else "\u001B[?1043;1\$y", process.outputText())
        }
    }

    @Test
    fun `input events are encoded to pty stdin through session serialization point`() {
        val process = FakePtyProcess.running()
        val session =
            startSession(
                options =
                    PtyOptions.create { draft ->
                        draft.command = listOf("fake")
                        draft.columns = 10
                        draft.rows = 3
                    },
                processFactory = FixedProcessFactory(process),
            )

        session.encodeKey(TerminalKeyEvent.codepoint('a'.code))

        process.awaitWrite()
        assertEquals("a", process.outputText())
    }

    @Test
    fun `default pty input policy sends Return as CR even when newline mode is active`() {
        val modeApplied = CountDownLatch(1)
        val process = FakePtyProcess.running(inputBytes = "\u001B[20h\u0007".ascii())
        val session =
            startSession(
                options =
                    PtyOptions.create { draft ->
                        draft.command = listOf("fake")
                        draft.columns = 10
                        draft.rows = 3
                        draft.eventListener =
                            object : PtyEventListener by PtyEventListener.NONE {
                                override fun bell(session: TerminalSession) {
                                    modeApplied.countDown()
                                }
                            }
                    },
                processFactory = FixedProcessFactory(process),
            )

        assertTrue(modeApplied.await(10, TimeUnit.SECONDS), "newline mode output was not processed")
        assertTrue(session.modeSnapshot.isNewLineMode)
        session.encodeKey(TerminalKeyEvent.key(TerminalKey.ENTER))

        process.awaitWrite()
        assertEquals("\r", process.outputText())
    }

    @Test
    fun `default PTY paste canonicalizes unbracketed clipboard newlines to CR`() {
        val process = FakePtyProcess.running()
        val session =
            startSession(
                options =
                    PtyOptions.create { draft ->
                        draft.command = listOf("fake")
                        draft.columns = 10
                        draft.rows = 3
                    },
                processFactory = FixedProcessFactory(process),
            )

        session.encodePaste(TerminalPasteEvent("first\r\nsecond\nthird\rfourth"))

        process.awaitWrite()
        assertEquals("first\rsecond\rthird\rfourth", process.outputText())
    }

    @Test
    fun `resize updates process and terminal dimensions`() {
        val process = FakePtyProcess.running()
        val session =
            startSession(
                options =
                    PtyOptions.create { draft ->
                        draft.command = listOf("fake")
                        draft.columns = 10
                        draft.rows = 3
                    },
                processFactory = FixedProcessFactory(process),
            )

        session.resize(columns = 20, rows = 5)

        session.readRenderFrame { frame ->
            assertEquals(20, frame.columns)
            assertEquals(5, frame.rows)
        }
        assertEquals(listOf(10 to 3, 20 to 5), process.sizes)
    }

    @Test
    fun `ambiguous width option is applied before pty output is parsed`() {
        val process = FakePtyProcess(inputBytes = "\u20ACX".toByteArray(StandardCharsets.UTF_8))
        val session =
            startSession(
                options =
                    PtyOptions.create { draft ->
                        draft.command = listOf("fake")
                        draft.columns = 6
                        draft.rows = 2
                        draft.treatAmbiguousAsWide = true
                    },
                processFactory = FixedProcessFactory(process),
            )

        awaitClosed(session)

        val frame = TerminalRenderCache(6, 2).apply { updateFrom(session) }
        assertAll(
            { assertEquals(0x20AC, frame.codeWords[0]) },
            { assertEquals(TerminalRenderCellFlags.WIDE_TRAILING, frame.flags[1]) },
            { assertEquals('X'.code, frame.codeWords[2]) },
            { assertTrue(session.modeSnapshot.treatAmbiguousAsWide) },
        )
    }

    @Test
    fun `close destroys process and does not fake an exit code`() {
        val process = FakePtyProcess.running()
        val session =
            startSession(
                options =
                    PtyOptions.create { draft ->
                        draft.command = listOf("fake")
                        draft.columns = 10
                        draft.rows = 3
                    },
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
            startSession(
                options =
                    PtyOptions.create { draft ->
                        draft.command = listOf("fake")
                        draft.columns = 10
                        draft.rows = 3
                    },
                processFactory = FixedProcessFactory(process),
            )

        awaitClosed(session)

        assertEquals(7, session.exitCode)
    }

    @Test
    fun `large output is parsed without losing bytes across connector chunks`() {
        val text = "x".repeat(20_000) + "\n"
        val process = FakePtyProcess(inputBytes = text.ascii())
        val session =
            startSession(
                options =
                    PtyOptions.create { draft ->
                        draft.command = listOf("fake")
                        draft.columns = 200
                        draft.rows = 120
                        draft.maxHistory = 200
                        draft.readBufferSize = 17
                    },
                processFactory = FixedProcessFactory(process),
            )

        awaitClosed(session)

        assertEquals(20_000, session.retainedText().count { it == 'x' })
    }

    @Test
    fun `bell and title changes are delivered to PTY listener`() {
        val listener = RecordingPtyEventListener()
        val input = "\u0007\u001B]0;both\u001B\\".ascii()
        val process = FakePtyProcess(inputBytes = input)
        val session =
            startSession(
                options =
                    PtyOptions.create { draft ->
                        draft.command = listOf("fake")
                        draft.columns = 10
                        draft.rows = 3
                        draft.eventListener = listener
                    },
                processFactory = FixedProcessFactory(process),
            )

        awaitClosed(session)

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
        val session =
            startSession(
                options =
                    PtyOptions.create { draft ->
                        draft.command = listOf("fake")
                        draft.eventListener = listener
                    },
                processFactory = FixedProcessFactory(process),
            )

        awaitClosed(session)

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
        private val expectedOutputBytes: Int = 1,
    ) : PtyProcess {
        constructor(
            inputBytes: ByteArray,
            exitCode: Int = 0,
        ) : this(DrainingByteArrayInputStream(inputBytes), null, exitCode)

        private val capturedOutput = ByteArrayOutputStream()
        private val firstWrite = CountDownLatch(1)
        override val output: OutputStream =
            object : OutputStream() {
                override fun write(byte: Int) {
                    capturedOutput.write(byte)
                    if (capturedOutput.size() >= expectedOutputBytes) firstWrite.countDown()
                }

                override fun write(
                    bytes: ByteArray,
                    offset: Int,
                    length: Int,
                ) {
                    capturedOutput.write(bytes, offset, length)
                    if (capturedOutput.size() >= expectedOutputBytes) firstWrite.countDown()
                }
            }

        fun awaitWrite() {
            check(firstWrite.await(10, TimeUnit.SECONDS)) { "PTY did not receive expected output" }
        }

        @Volatile
        var destroyed: Boolean = false
            private set
        val sizes = mutableListOf<Pair<Int, Int>>()
        var resizeFailure: IOException? = null

        override fun isAlive(): Boolean = !destroyed

        override fun waitFor(): Int {
            inputDrained?.await()
            if (input is DrainingByteArrayInputStream) {
                input.drained.await()
            }
            return exitCode
        }

        override fun destroy() {
            destroyed = true
            when (input) {
                is BlockingInputStream -> input.release()
                is DrainingByteArrayInputStream -> input.drained.countDown()
            }
        }

        override fun resize(
            columns: Int,
            rows: Int,
        ) {
            resizeFailure?.let { throw it }
            sizes += columns to rows
        }

        fun outputText(): String = capturedOutput.toString(StandardCharsets.UTF_8)

        companion object {
            fun running(
                exitCode: Int = 0,
                inputBytes: ByteArray = byteArrayOf(),
                expectedOutputBytes: Int = 1,
            ): FakePtyProcess {
                val input = BlockingInputStream(inputBytes)
                return FakePtyProcess(input, input.released, exitCode, expectedOutputBytes)
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

    private class BlockingInputStream(
        bytes: ByteArray,
    ) : InputStream() {
        private val initial = ByteArrayInputStream(bytes)
        val released = CountDownLatch(1)

        override fun read(): Int {
            if (initial.available() > 0) return initial.read()
            released.await()
            return -1
        }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            if (initial.available() > 0) return initial.read(buffer, offset, length)
            released.await()
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

    private fun startSession(
        options: PtyOptions,
        processFactory: PtyProcessFactory,
    ): TerminalSession = PtySessions.start(options, processFactory).also(sessions::add)

    private fun awaitClosed(session: TerminalSession) =
        runBlocking {
            withTimeout(10.seconds) { session.state.first { it is TerminalSessionState.Closed } }
        }
}

/** Copies the retained output while holding the session's frame-read boundary. */
internal fun TerminalSession.retainedText(): String {
    val cache = TerminalRenderCache(1, 1).apply { updateFromAbsoluteRange(this@retainedText, 0, Long.MAX_VALUE) }
    return (0 until cache.rows).joinToString("\n") { row ->
        val start = cache.rowOffset(row)
        var last = cache.columns - 1
        while (last >= 0 && cache.flags[start + last] and TerminalRenderCellFlags.EMPTY != 0) last--
        buildString {
            for (column in 0..last) {
                val index = start + column
                when {
                    cache.flags[index] and TerminalRenderCellFlags.WIDE_TRAILING != 0 -> Unit
                    cache.flags[index] and TerminalRenderCellFlags.CLUSTER != 0 -> append(checkNotNull(cache.clusterText(row, column)))
                    cache.flags[index] and TerminalRenderCellFlags.CODEPOINT != 0 -> appendCodePoint(cache.codeWords[index])
                    else -> append(' ')
                }
            }
        }
    }
}
