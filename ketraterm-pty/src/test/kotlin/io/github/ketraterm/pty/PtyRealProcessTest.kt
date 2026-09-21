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
import io.github.ketraterm.render.api.TerminalRenderCursorShape
import io.github.ketraterm.render.api.TerminalRenderFrameReader
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.session.TerminalSessionState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

class PtyRealProcessTest {
    private val sessions = mutableListOf<TerminalSession>()

    @AfterEach
    fun closeSessions() {
        sessions.forEach(TerminalSession::close)
    }

    @Test
    fun `real PTY detects a running executable and clears it after exit`() =
        runBlocking {
            val session = startReadySession()
            val expected = if (isWindows()) "powershell.exe" else "sh"
            val detected =
                withTimeout(10.seconds) {
                    session.foregroundProcessName.first { it.equals(expected, ignoreCase = true) }
                }
            assertTrue(expected.equals(detected, ignoreCase = true))

            session.encodeKey(TerminalKeyEvent.key(TerminalKey.ENTER))
            withTimeout(10.seconds) { session.state.first { it is TerminalSessionState.Closed } }
            assertNull(withTimeout(10.seconds) { session.foregroundProcessName.first { it == null } })
        }

    @Test
    fun `real PTY echo output reaches terminal core`() {
        val session =
            startReadySession(
                outputScript = if (isWindows()) "[Console]::Out.Write('hello')" else "printf 'hello'",
            )

        assertTrue(session.terminal.getAllAsString().contains("hello"))
        releaseAndAwaitExit(session)
        assertEquals(0, session.exitCode)
    }

    @Test
    fun `real PTY resize mutates session without deadlock`() {
        val session = startReadySession()

        session.resize(columns = 100, rows = 30)
        session.close()

        assertEquals(100, session.terminal.width)
        assertEquals(30, session.terminal.height)
    }

    @Test
    fun `real PTY retains final geometry after rapid sequential resizes`() {
        val session = startReadySession()

        val sizes = listOf(80 to 24, 132 to 43, 17 to 9, 101 to 31)
        for ((columns, rows) in sizes) {
            session.resize(columns, rows)
        }
        session.close()

        assertEquals(101, session.terminal.width)
        assertEquals(31, session.terminal.height)
    }

    @Test
    fun `real PTY close requests local shutdown without fake exit code`() {
        val session = startReadySession()

        session.close()

        assertNull(session.exitCode)
    }

    @Test
    fun `real PTY process exit sets session exit code`() {
        val session = startReadySession(exitCode = 7)

        releaseAndAwaitExit(session)

        assertEquals(7, session.exitCode)
    }

    @Test
    fun `real PTY large output does not lose bytes`() {
        val expectedCount = 12_000
        val session =
            startReadySession(
                outputScript =
                    if (isWindows()) {
                        "[Console]::Out.Write(('x' * $expectedCount))"
                    } else {
                        "printf '%*s' $expectedCount '' | tr ' ' 'x'"
                    },
                columns = 200,
                rows = 80,
                readBufferSize = 257,
            )

        assertEquals(expectedCount, session.terminal.getAllAsString().count { it == 'x' })
        releaseAndAwaitExit(session)
        assertEquals(0, session.exitCode)
    }

    @Test
    fun `real PTY one byte reads preserve mixed line terminator output`() {
        val session =
            startReadySession(
                outputScript =
                    if (isWindows()) {
                        "[Console]::Out.Write('A' + [char]13 + [char]10 + 'B' + [char]13 + 'C' + [char]10)"
                    } else {
                        "printf 'A\\r\\nB\\rC\\n'"
                    },
                readBufferSize = 1,
            )

        // CR returns to column zero: C overwrites B on the same row.
        assertEquals("A", session.terminal.getLineAsString(0))
        assertEquals("C", session.terminal.getLineAsString(1))
        releaseAndAwaitExit(session)
        assertEquals(0, session.exitCode)
    }

    @Test
    fun `real PTY shell redraw and alternate screen restore primary terminal state`() {
        val session =
            startReadySession(
                outputScript =
                    if (isWindows()) {
                        "[Console]::Out.Write('prompt> old' + [char]13 + [char]27 + '[2Kprompt> done' + [char]13 + [char]10 + [char]27 + '[?1049hFULL' + [char]27 + '[?1049lafter' + [char]13 + [char]10)"
                    } else {
                        "printf 'prompt> old\\r\\033[2Kprompt> done\\r\\n\\033[?1049hFULL\\033[?1049lafter\\r\\n'"
                    },
                readBufferSize = 1,
            )

        val primaryText = session.terminal.getAllAsString()
        assertAll(
            { assertTrue(primaryText.contains("prompt> done")) },
            { assertTrue(primaryText.contains("after")) },
            { assertFalse(primaryText.contains("FULL"), "alternate-screen content must not leak into primary") },
        )
        releaseAndAwaitExit(session)
        assertEquals(0, session.exitCode)
    }

    @Test
    fun `real PTY alternate screen teardown restores the shells blinking bar cursor`() {
        // Neovim on Windows emits a steady block reset before leaving alternate.
        val stream = "\u001B[5 q\u001B[?1049h\u001B[2 q\u001B[?25h\u001B[2 q\u001B[?1049l\u001B[?25h"
        val session =
            startReadySession(
                outputScript =
                    if (isWindows()) {
                        "[Console]::Out.Write('${stream.replace("\u001B", "' + [char]27 + '")}')"
                    } else {
                        "printf '${stream.replace("\u001B", "\\033")}'"
                    },
                readBufferSize = 1,
            )
        (session.terminal as TerminalRenderFrameReader).readRenderFrame { frame ->
            assertEquals(TerminalRenderCursorShape.BAR, frame.cursor.shape)
            assertTrue(frame.cursor.blinking)
            assertTrue(frame.cursor.visible)
        }
        releaseAndAwaitExit(session)
    }

    @Test
    fun `real PTY accepts bracketed paste while a shell advertises paste mode`() {
        val session =
            startReadySession(
                outputScript =
                    if (isWindows()) {
                        "[Console]::Out.Write([char]27 + '[?2004h')"
                    } else {
                        "printf '\\033[?2004h'"
                    },
                readBufferSize = 1,
            )

        assertTrue(session.terminal.getModeSnapshot().isBracketedPasteEnabled)
        session.encodePaste(TerminalPasteEvent("first\nsecond"))
        assertTrue(session.terminal.getModeSnapshot().isBracketedPasteEnabled)
        session.close()
    }

    /**
     * BEL acknowledges every preceding output byte; the child then waits for input.
     * Readiness and exit are explicit events, so slow native startup cannot shorten
     * the test's observation window. The timeout only detects a stuck fixture.
     */
    private fun startReadySession(
        outputScript: String = "",
        exitCode: Int = 0,
        columns: Int = 40,
        rows: Int = 5,
        readBufferSize: Int = 8192,
    ): TerminalSession {
        assumeTrue(
            System.getProperty("terminal.pty.host") == "true",
            "Set -Dterminal.pty.host=true to run native PTY host tests",
        )
        val ready = CountDownLatch(1)
        val listener =
            object : PtyEventListener by PtyEventListener.NONE {
                override fun bell(session: TerminalSession) {
                    ready.countDown()
                }
            }
        val prefix = if (outputScript.isEmpty()) "" else "$outputScript; "
        val command =
            if (isWindows()) {
                // Keep a shell parent so the Windows detector exercises descendant selection.
                listOf("cmd.exe", "/d", "/c") +
                    powerShellCommand(
                        "$prefix[Console]::Out.Write([char]7); [void][Console]::ReadLine(); exit $exitCode",
                    )
            } else {
                listOf("/bin/sh", "-c", "${prefix}printf '\\007'; read -r release; exit $exitCode")
            }
        val session =
            TerminalSessions.localPty(
                PtyOptions(
                    command = command,
                    workingDirectory = Path.of(System.getProperty("user.home")),
                    columns = columns,
                    rows = rows,
                    maxHistory = 200,
                    readBufferSize = readBufferSize,
                    eventListener = listener,
                ),
            )
        sessions += session
        assertTrue(ready.await(10, TimeUnit.SECONDS), "native child did not acknowledge its output")
        return session
    }

    private fun releaseAndAwaitExit(session: TerminalSession) =
        runBlocking {
            session.encodeKey(TerminalKeyEvent.key(TerminalKey.ENTER))
            withTimeout(10.seconds) { session.state.first { it is TerminalSessionState.Closed } }
        }

    // Preserve script quotes across ConPTY's Windows command-line construction.
    private fun powerShellCommand(script: String): List<String> =
        listOf(
            "powershell.exe",
            "-NoProfile",
            "-EncodedCommand",
            Base64.getEncoder().encodeToString(script.toByteArray(StandardCharsets.UTF_16LE)),
        )

    private fun isWindows(): Boolean = System.getProperty("os.name").lowercase().contains("windows")
}
