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
package io.github.ketraterm.workspace

import io.github.ketraterm.input.event.TerminalKey
import io.github.ketraterm.input.event.TerminalKeyEvent
import io.github.ketraterm.input.event.TerminalPasteEvent
import io.github.ketraterm.protocol.ShellIntegrationEvent
import io.github.ketraterm.protocol.ShellIntegrationMarker
import io.github.ketraterm.pty.PtyEventListener
import io.github.ketraterm.pty.PtyOptions
import io.github.ketraterm.pty.TerminalSessions
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.session.TerminalStartupCommand
import io.github.ketraterm.session.TerminalStartupCommandStatus
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.milliseconds

class TerminalStartupCommandPtyTest {
    @Test
    fun `PowerShell runs startup once and accepts another command`(
        @TempDir directory: Path,
    ) {
        verifyInteractiveShell("powershell", directory)
    }

    @Test
    fun `Bash runs startup once and accepts another command`(
        @TempDir directory: Path,
    ) {
        verifyInteractiveShell("bash", directory)
    }

    private fun verifyInteractiveShell(
        shell: String,
        directory: Path,
    ): Unit =
        runBlocking {
            assumeTrue(System.getenv("KETRATERM_TEST_NATIVE_PTY") == "true", "Set KETRATERM_TEST_NATIVE_PTY=true to run native PTY tests")
            val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
            val executable =
                if (shell == "powershell") {
                    if (windows) {
                        Path.of(
                            System.getenv("SystemRoot"),
                            "System32/WindowsPowerShell/v1.0/powershell.exe",
                        )
                    } else {
                        Path.of("/usr/bin/pwsh")
                    }
                } else {
                    if (windows) Path.of(System.getenv("ProgramFiles"), "Git/bin/bash.exe") else Path.of("/bin/bash")
                }
            assumeTrue(Files.isRegularFile(executable), "$shell is not installed")
            val journal = directory.resolve("journal.txt")
            val result = directory.resolve("result.txt")
            val startup =
                if (shell == "powershell") {
                    "${'$'}startupRuns = 1 + [int]${'$'}startupRuns; Add-Content -LiteralPath '${journal.toString().replace(
                        "'",
                        "''",
                    )}' -Value startup"
                } else {
                    "STARTUP_RUNS=${'$'}(( ${'$'}{STARTUP_RUNS:-0} + 1 )); printf 'startup\\n' >> '${journal.toString().replace(
                        '\\',
                        '/',
                    ).replace("'", "'\\''")}'"
                }
            val next =
                if (shell == "powershell") {
                    "[IO.File]::WriteAllText('${result.toString().replace("'", "''")}', [string]${'$'}startupRuns)"
                } else {
                    "printf '%s' \"${'$'}STARTUP_RUNS\" > '${result.toString().replace('\\', '/').replace("'", "'\\''")}'"
                }
            val profile =
                TerminalProfile(
                    id = shell,
                    displayName = shell,
                    command = listOf(executable.toString(), if (shell == "powershell") "-NoProfile" else "-i"),
                    workingDirectory = directory,
                    environment = if (shell == "bash") mapOf("HOME" to directory.toString()) else emptyMap(),
                    startupCommand = TerminalStartupCommand(startup),
                )
            val launch = TerminalShellIntegrationBootstrap.apply(profile, true, directory.resolve("bootstrap"))
            val prompts = Channel<Unit>(Channel.UNLIMITED)
            val listener =
                object : PtyEventListener by PtyEventListener.NONE {
                    override fun shellIntegrationMarker(
                        session: TerminalSession,
                        event: ShellIntegrationEvent,
                    ) {
                        if (event.marker == ShellIntegrationMarker.PROMPT_END) prompts.trySend(Unit)
                    }
                }
            try {
                TerminalSessions
                    .localPty(
                        PtyOptions(
                            command = launch.command,
                            environment = PtyOptions.defaultEnvironment() + launch.environment,
                            workingDirectory = directory,
                            columns = 120,
                            rows = 20,
                            startupCommand = launch.startupCommand,
                            eventListener = listener,
                        ),
                    ).use { session ->
                        // The initial prompt submits startup; the next prompt proves that command finished.
                        withTimeout(20_000.milliseconds) { repeat(2) { prompts.receive() } }
                        assertEquals(TerminalStartupCommandStatus.SUBMITTED, session.startupCommandStatus?.value)
                        assertEquals(listOf("startup"), Files.readAllLines(journal).map { it.removePrefix("\uFEFF") })
                        session.encodePaste(TerminalPasteEvent(next))
                        session.encodeKey(TerminalKeyEvent(key = TerminalKey.ENTER))
                        withTimeout(20_000.milliseconds) { prompts.receive() }
                        assertEquals("1", Files.readString(result))
                        assertEquals(listOf("startup"), Files.readAllLines(journal).map { it.removePrefix("\uFEFF") })
                        assertFalse(session.isClosed)
                    }
            } finally {
                prompts.close()
            }
        }
}
