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

import io.github.ketraterm.session.TerminalStartupCommand
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class TerminalStartupCommandProfileTest {
    @Test
    fun `supported interactive profiles retain startup command independently of process arguments`(
        @TempDir directory: Path,
    ) {
        for (executable in listOf("bash", "zsh", "fish", "pwsh")) {
            val profile = TerminalProfile(executable, executable, listOf(executable), startupCommand = TerminalStartupCommand("echo ready"))
            val launch = TerminalShellIntegrationBootstrap.apply(profile, true, directory)
            assertSame(profile.startupCommand, launch.startupCommand)
            assertFalse(launch.command.any { it.contains("echo ready") })
            if (executable == "bash") assertTrue("--rcfile" in launch.command)
        }
    }

    @Test
    fun `unsupported and explicit program launches fail clearly before spawning a process`(
        @TempDir directory: Path,
    ) {
        val commands =
            listOf(
                listOf("cmd.exe"),
                listOf("wsl.exe"),
                listOf("wsl.exe", "--exec", "bash", "-i"),
                listOf("ubuntu.exe", "run", "fish", "-i"),
                listOf("sh"),
                listOf("bash", "-c", "echo explicit"),
                listOf("bash", "--norc"),
                listOf("zsh", "-f"),
                listOf("fish", "-c", "echo explicit"),
                listOf("pwsh", "-Command", "echo explicit"),
            )
        for (command in commands) {
            val profile = TerminalProfile("custom", "Custom", command, startupCommand = TerminalStartupCommand("echo ready"))
            val error =
                assertFailsWith<IllegalArgumentException>(command.toString()) {
                    TerminalShellIntegrationBootstrap.apply(profile, true, directory)
                }
            assertTrue(error.message.orEmpty().contains("Startup commands require"))
            TerminalShellIntegrationBootstrap.apply(profile.copy(startupCommand = null), true, directory)
        }
    }

    @Test
    fun `disabled integration rejects configured command rather than silently skipping it`(
        @TempDir directory: Path,
    ) {
        val profile = TerminalProfile("bash", "Bash", listOf("bash"), startupCommand = TerminalStartupCommand("echo ready"))
        assertFailsWith<IllegalArgumentException> { TerminalShellIntegrationBootstrap.apply(profile, false, directory) }
    }
}
