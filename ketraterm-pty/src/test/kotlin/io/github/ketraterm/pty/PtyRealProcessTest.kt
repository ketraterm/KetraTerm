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

import io.github.ketraterm.input.event.TerminalPasteEvent
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class PtyRealProcessTest {
    @Test
    fun `real PTY echo output reaches terminal core`() {
        assumeNativePty()

        val session =
            TerminalSessions.localPty(
                PtyOptions(
                    command = printCommand("hello"),
                    workingDirectory = Path.of(System.getProperty("user.home")),
                    columns = 40,
                    rows = 5,
                    maxHistory = 10,
                ),
            )

        waitUntil { session.exitCode == 0 && session.terminal.getAllAsString().contains("hello") }

        assertEquals(0, session.exitCode)
        assertTrue(session.terminal.getAllAsString().contains("hello"))
    }

    @Test
    fun `real PTY resize mutates session without deadlock`() {
        assumeNativePty()

        val session =
            TerminalSessions.localPty(
                PtyOptions(
                    command = sleepCommand(seconds = 2),
                    columns = 40,
                    rows = 5,
                ),
            )

        session.resize(columns = 100, rows = 30)
        session.close()

        assertEquals(100, session.terminal.width)
        assertEquals(30, session.terminal.height)
    }

    @Test
    fun `real PTY retains final geometry after rapid sequential resizes`() {
        assumeNativePty()

        val session =
            TerminalSessions.localPty(
                PtyOptions(
                    command = sleepCommand(seconds = 2),
                    columns = 40,
                    rows = 5,
                ),
            )

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
        assumeNativePty()

        val session =
            TerminalSessions.localPty(
                PtyOptions(
                    command = sleepCommand(seconds = 5),
                    columns = 40,
                    rows = 5,
                ),
            )

        session.close()

        assertNull(session.exitCode)
    }

    @Test
    fun `real PTY process exit sets session exit code`() {
        assumeNativePty()

        val session =
            TerminalSessions.localPty(
                PtyOptions(
                    command = exitCommand(7),
                    columns = 40,
                    rows = 5,
                ),
            )

        waitUntil { session.exitCode == 7 }

        assertEquals(7, session.exitCode)
    }

    @Test
    fun `real PTY large output does not lose bytes`() {
        assumeNativePty()

        val expectedCount = 12_000
        val session =
            TerminalSessions.localPty(
                PtyOptions(
                    command = repeatCommand('x', expectedCount),
                    columns = 200,
                    rows = 80,
                    maxHistory = 200,
                    readBufferSize = 257,
                ),
            )

        waitUntil(timeoutMillis = 5000) {
            session.exitCode == 0 &&
                session.terminal.getAllAsString().count { it == 'x' } == expectedCount
        }

        assertEquals(expectedCount, session.terminal.getAllAsString().count { it == 'x' })
    }

    @Test
    fun `real PTY one byte reads preserve mixed line terminator output`() {
        assumeNativePty()

        val session =
            TerminalSessions.localPty(
                PtyOptions(
                    command = mixedLineEndingCommand(),
                    columns = 40,
                    rows = 5,
                    maxHistory = 10,
                    readBufferSize = 1,
                ),
            )

        waitUntil { session.exitCode == 0 && session.terminal.getAllAsString().contains("A\nB\nC") }

        assertEquals(0, session.exitCode)
        assertTrue(session.terminal.getAllAsString().contains("A\nB\nC"))
    }

    @Test
    fun `real PTY shell redraw and alternate screen restore primary terminal state`() {
        assumeNativePty()

        val session =
            TerminalSessions.localPty(
                PtyOptions(
                    command = shellRedrawAndAlternateScreenCommand(),
                    columns = 40,
                    rows = 5,
                    maxHistory = 10,
                    readBufferSize = 1,
                ),
            )

        waitUntil { session.exitCode == 0 && session.terminal.getAllAsString().contains("after") }

        val primaryText = session.terminal.getAllAsString()
        assertAll(
            { assertTrue(primaryText.contains("prompt> done")) },
            { assertTrue(primaryText.contains("after")) },
            { assertFalse(primaryText.contains("FULL"), "alternate-screen content must not leak into primary") },
        )
    }

    @Test
    fun `real PTY accepts bracketed paste while a shell advertises paste mode`() {
        assumeNativePty()

        val session =
            TerminalSessions.localPty(
                PtyOptions(
                    command = bracketedPasteShellCommand(),
                    columns = 40,
                    rows = 5,
                    readBufferSize = 1,
                ),
            )

        waitUntil { session.terminal.getModeSnapshot().isBracketedPasteEnabled }
        session.encodePaste(TerminalPasteEvent("first\nsecond"))

        assertTrue(session.terminal.getModeSnapshot().isBracketedPasteEnabled)
        session.close()
    }

    private fun assumeNativePty() {
        assumeTrue(
            System.getProperty("terminal.pty.host") == "true",
            "Set -Dterminal.pty.host=true to run native PTY host tests",
        )
    }

    private fun printCommand(text: String): List<String> =
        if (isWindows()) {
            listOf("cmd.exe", "/c", "echo $text")
        } else {
            listOf("/bin/sh", "-lc", "printf '$text\n'")
        }

    private fun sleepCommand(seconds: Int): List<String> =
        if (isWindows()) {
            listOf("cmd.exe", "/c", "ping -n ${seconds + 1} 127.0.0.1 > nul")
        } else {
            listOf("/bin/sh", "-lc", "sleep $seconds")
        }

    private fun exitCommand(code: Int): List<String> =
        if (isWindows()) {
            listOf("cmd.exe", "/c", "exit $code")
        } else {
            listOf("/bin/sh", "-lc", "exit $code")
        }

    private fun repeatCommand(
        char: Char,
        count: Int,
    ): List<String> =
        if (isWindows()) {
            listOf(
                "powershell.exe",
                "-NoProfile",
                "-Command",
                "[Console]::Out.Write(('$char' * $count) + \"`n\")",
            )
        } else {
            listOf("/bin/sh", "-lc", "printf '%*s\n' $count '' | tr ' ' '$char'")
        }

    private fun mixedLineEndingCommand(): List<String> =
        if (isWindows()) {
            listOf("powershell.exe", "-NoProfile", "-Command", "[Console]::Out.Write(\"A`r`nB`rC`n\")")
        } else {
            listOf("/bin/sh", "-lc", "printf 'A\\r\\nB\\rC\\n'")
        }

    private fun shellRedrawAndAlternateScreenCommand(): List<String> =
        if (isWindows()) {
            listOf(
                "powershell.exe",
                "-NoProfile",
                "-Command",
                "[Console]::Out.Write('prompt> old' + [char]13 + [char]27 + '[2Kprompt> done' + [char]13 + [char]10 + [char]27 + '[?1049hFULL' + [char]27 + '[?1049lafter' + [char]13 + [char]10')",
            )
        } else {
            listOf("/bin/sh", "-lc", "printf 'prompt> old\\r\\033[2Kprompt> done\\r\\n\\033[?1049hFULL\\033[?1049lafter\\r\\n'")
        }

    private fun bracketedPasteShellCommand(): List<String> =
        if (isWindows()) {
            listOf(
                "powershell.exe",
                "-NoProfile",
                "-Command",
                "[Console]::Out.Write([char]27 + '[?2004h'); Start-Sleep -Seconds 2",
            )
        } else {
            listOf("/bin/sh", "-lc", "printf '\\033[?2004h'; sleep 2")
        }

    private fun isWindows(): Boolean = System.getProperty("os.name").lowercase().contains("windows")

    private fun waitUntil(
        timeoutMillis: Long = 3000,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        assertTrue(condition(), "condition was not met within ${timeoutMillis}ms")
    }
}
