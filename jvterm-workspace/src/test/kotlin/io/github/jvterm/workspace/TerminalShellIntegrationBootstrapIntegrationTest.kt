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
package io.github.jvterm.workspace

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TerminalShellIntegrationBootstrapIntegrationTest {
    @Test
    fun `generated PowerShell bootstrap emits encoded current directory before prompt when PowerShell is installed`(
        @TempDir tempDir: Path,
    ) {
        val powerShell = installedExecutable("pwsh", "pwsh.exe", "powershell.exe")
        assumeTrue(powerShell != null, "PowerShell is not installed")

        val profile =
            TerminalProfile(
                id = "powershell",
                displayName = "PowerShell",
                command = listOf(powerShell!!),
            )
        val integrated = TerminalShellIntegrationBootstrap.apply(profile, enabled = true)
        val bootstrap = String(Base64.getDecoder().decode(integrated.command.last()), Charsets.UTF_16LE)
        val invocation = Base64.getEncoder().encodeToString("$bootstrap\nprompt | Out-Host".toByteArray(Charsets.UTF_16LE))
        val workingDirectory = Files.createDirectory(tempDir.resolve("space % directory"))
        val result =
            runProcess(
                listOf(powerShell, "-NoProfile", "-EncodedCommand", invocation),
                workingDirectory = workingDirectory,
            )

        assertEquals(0, result.exitCode)
        assertMarkerOrder(result.stdout, "A")
        assertCurrentDirectoryBeforePrompts(result.stdout, expectedPromptCount = 1)
        assertTrue(result.stdout.contains("space%20%25%20directory"), visibleEscapes(result.stdout))
    }

    @Test
    fun `generated Bash bootstrap emits encoded current directory and lifecycle markers when Bash is installed`(
        @TempDir tempDir: Path,
    ) {
        val bash = installedExecutable("bash", "bash.exe")
        assumeTrue(bash != null, "bash is not installed")

        val profile =
            TerminalProfile(
                id = "bash",
                displayName = "Bash",
                command = listOf(bash!!),
            )
        val bootstrap = TerminalShellIntegrationBootstrap.apply(profile, enabled = true).environment.getValue("PROMPT_COMMAND")
        val workingDirectory = Files.createDirectory(tempDir.resolve("space % directory"))
        val result =
            runProcess(
                listOf(
                    bash,
                    "--noprofile",
                    "--norc",
                ),
                standardInput = "$bootstrap; __jvterm_preexec; false; __jvterm_prompt_command\n",
                workingDirectory = workingDirectory,
            )

        assertEquals(1, result.exitCode)
        assertMarkerOrder(result.stdout, "A", "C", "D;1", "A")
        assertCurrentDirectoryBeforePrompts(result.stdout, expectedPromptCount = 2)
        assertTrue(result.stdout.contains("space%20%25%20directory"), visibleEscapes(result.stdout))
    }

    @Test
    fun `generated Bash bootstrap continues markers after clear when Bash is installed`() {
        val bash = installedExecutable("bash", "bash.exe")
        assumeTrue(bash != null, "bash is not installed")

        val profile =
            TerminalProfile(
                id = "bash",
                displayName = "Bash",
                command = listOf(bash!!),
                environment = mapOf("TERM" to "xterm-256color"),
            )
        val integrated = TerminalShellIntegrationBootstrap.apply(profile, enabled = true)
        val bootstrap = integrated.environment.getValue("PROMPT_COMMAND")
        val result =
            runProcess(
                listOf(
                    bash,
                    "--noprofile",
                    "--norc",
                ),
                environment = integrated.environment,
                standardInput =
                    "$bootstrap; __jvterm_preexec; { clear >/dev/null 2>&1 || printf '\\033[H\\033[2J\\033[3J'; }; true; __jvterm_prompt_command; __jvterm_preexec; false; __jvterm_prompt_command\n",
            )

        assertEquals(1, result.exitCode)
        assertMarkerOrder(result.stdout, "A", "C", "D;0", "A", "C", "D;1", "A")
        assertCurrentDirectoryBeforePrompts(result.stdout, expectedPromptCount = 3)
    }

    @Test
    fun `generated zsh bootstrap emits prompt command and lifecycle markers when zsh is installed`(
        @TempDir tempDir: Path,
    ) {
        val zsh = installedExecutable("zsh", "zsh.exe")
        assumeTrue(zsh != null, "zsh is not installed")

        val originalZdotdir = tempDir.resolve("original")
        val profile =
            TerminalProfile(
                id = "zsh",
                displayName = "Zsh",
                command = listOf(zsh!!),
                environment = mapOf("JVTERM_ORIGINAL_ZDOTDIR" to originalZdotdir.toString()),
            )
        val integrated = TerminalShellIntegrationBootstrap.apply(profile, enabled = true, scriptDirectory = tempDir)
        val zshrc = Path.of(integrated.environment.getValue("ZDOTDIR")).resolve(".zshrc")
        val result =
            runProcess(
                listOf(
                    zsh,
                    "-f",
                    "-c",
                    "source ${shellSingleQuote(
                        zshrc.toString(),
                    )}; __jvterm_zsh_precmd; __jvterm_zsh_preexec false; false; __jvterm_zsh_precmd",
                ),
                environment = integrated.environment,
            )

        assertEquals(1, result.exitCode)
        assertMarkerOrder(result.stdout, "A", "C", "D;1", "A")
        assertCurrentDirectoryBeforePrompts(result.stdout, expectedPromptCount = 2)
    }

    @Test
    fun `generated fish bootstrap emits prompt command and lifecycle markers when fish is installed`() {
        val fish = installedExecutable("fish", "fish.exe")
        assumeTrue(fish != null, "fish is not installed")

        val profile =
            TerminalProfile(
                id = "fish",
                displayName = "Fish",
                command = listOf(fish!!),
            )
        val integrated = TerminalShellIntegrationBootstrap.apply(profile, enabled = true)
        val result =
            runProcess(
                integrated.command +
                    listOf(
                        "--no-config",
                        "-c",
                        "emit fish_prompt; emit fish_preexec false; false; emit fish_postexec false; emit fish_prompt",
                    ),
            )

        assertEquals(0, result.exitCode)
        assertMarkerOrder(result.stdout, "A", "C", "D;1", "A")
        assertCurrentDirectoryBeforePrompts(result.stdout, expectedPromptCount = 2)
    }

    private fun assertMarkerOrder(
        output: String,
        vararg markers: String,
    ) {
        var index = 0
        for (marker in markers) {
            val encoded = "\u001B]133;$marker\u0007"
            val nextIndex = output.indexOf(encoded, startIndex = index)
            assertTrue(nextIndex >= 0, "missing OSC 133 marker $marker in ${visibleEscapes(output)}")
            index = nextIndex + encoded.length
        }
    }

    private fun assertCurrentDirectoryBeforePrompts(
        output: String,
        expectedPromptCount: Int,
    ) {
        val osc7Prefix = "\u001B]7;"
        val promptStart = "\u001B]133;A\u0007"
        var searchFrom = 0
        repeat(expectedPromptCount) {
            val promptIndex = output.indexOf(promptStart, startIndex = searchFrom)
            assertTrue(promptIndex >= 0, "missing OSC 133 prompt marker in ${visibleEscapes(output)}")
            val osc7Index = output.lastIndexOf(osc7Prefix, startIndex = promptIndex)
            assertTrue(osc7Index >= searchFrom, "missing OSC 7 before prompt in ${visibleEscapes(output)}")
            val uriEnd = output.indexOf('\u0007', startIndex = osc7Index + osc7Prefix.length)
            assertTrue(uriEnd in (osc7Index + osc7Prefix.length)..<promptIndex)
            val uri = URI(output.substring(osc7Index + osc7Prefix.length, uriEnd))
            assertEquals("file", uri.scheme)
            assertTrue(!uri.rawPath.isNullOrEmpty(), "OSC 7 URI must contain a path: $uri")
            searchFrom = promptIndex + promptStart.length
        }
    }

    private fun runProcess(
        command: List<String>,
        environment: Map<String, String> = emptyMap(),
        standardInput: String? = null,
        workingDirectory: Path? = null,
    ): ProcessResult {
        val process =
            ProcessBuilder(command)
                .redirectErrorStream(true)
                .also { if (workingDirectory != null) it.directory(workingDirectory.toFile()) }
                .also { it.environment().putAll(environment) }
                .start()
        if (standardInput != null) {
            process.outputStream.use { input ->
                input.write(standardInput.toByteArray(StandardCharsets.UTF_8))
            }
        }
        val completed = process.waitFor(5, TimeUnit.SECONDS)
        if (!completed) {
            process.destroyForcibly()
            error("process timed out: ${command.joinToString(" ")}")
        }
        val stdout = String(process.inputStream.readAllBytes(), StandardCharsets.UTF_8)
        return ProcessResult(process.exitValue(), stdout)
    }

    private fun installedExecutable(vararg names: String): String? {
        val locator = if (isWindows()) listOf("where.exe") else listOf("sh", "-c")
        for (name in names) {
            val command =
                if (isWindows()) {
                    locator + name
                } else {
                    locator + "command -v ${shellSingleQuote(name)}"
                }
            val output = locate(command) ?: continue
            val firstLine = output.lineSequence().firstOrNull { isUsableShellPath(it) } ?: continue
            return firstLine.trim()
        }
        return null
    }

    private fun locate(command: List<String>): String? =
        try {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                null
            } else if (process.exitValue() == 0) {
                String(process.inputStream.readAllBytes(), StandardCharsets.UTF_8)
            } else {
                null
            }
        } catch (_: IOException) {
            null
        }

    private fun shellSingleQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun isUsableShellPath(path: String): Boolean {
        if (path.isBlank()) return false
        val normalized = path.trim().replace('\\', '/').lowercase()
        return normalized != "c:/windows/system32/bash.exe" &&
            !normalized.contains("/microsoft/windowsapps/")
    }

    private fun visibleEscapes(value: String): String =
        value
            .replace("\u001B", "<ESC>")
            .replace("\u0007", "<BEL>")

    private fun isWindows(): Boolean = System.getProperty("os.name").lowercase().contains("windows")

    private data class ProcessResult(
        val exitCode: Int,
        val stdout: String,
    )
}
