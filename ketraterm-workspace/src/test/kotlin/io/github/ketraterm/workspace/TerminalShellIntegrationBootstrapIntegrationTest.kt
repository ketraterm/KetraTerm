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

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TerminalShellIntegrationBootstrapIntegrationTest {
    @Test
    fun `Bash startup applies project toolchain after bashrc replaces environment and prompt hook`(
        @TempDir tempDir: Path,
    ) {
        val bash = installedExecutable("bash", "bash.exe")
        assumeTrue(bash != null, "bash is not installed")
        val home = Files.createDirectory(tempDir.resolve("user home"))
        val jdk = Files.createDirectory(tempDir.resolve("project JDK ${'$'}literal 'quote"))
        val bin = Files.createDirectory(jdk.resolve("bin"))
        Files.writeString(
            home.resolve(".bashrc"),
            """
            export JAVA_HOME=rc-jdk
            export PATH=/usr/bin:/bin
            PROMPT_COMMAND=': user-prompt-hook'
            export USER_RC_RAN=yes
            """.trimIndent(),
        )
        Files.writeString(bin.resolve("java"), "#!/bin/sh\nprintf 'PROJECT_JAVA\\n'\n")
        assertTrue(bin.resolve("java").toFile().setExecutable(true))
        val profile =
            TerminalProfile(
                id = "bash",
                displayName = "Bash",
                command = listOf(bash!!, "-i"),
                environment = mapOf("HOME" to home.toString(), "JAVA_HOME" to "explicit-jdk", "TERM" to "xterm-256color"),
                shellEnvironment = TerminalShellEnvironment(mapOf("JAVA_HOME" to jdk.toString()), bin.toString()),
            )
        val launch = TerminalShellIntegrationBootstrap.apply(profile, enabled = true, scriptDirectory = tempDir.resolve("bootstrap"))
        val result =
            runProcess(
                launch.command,
                environment = launch.environment,
                standardInput =
                    """
                    printf 'HOME_RESULT=%s\nRC_RESULT=%s\n' "${'$'}JAVA_HOME" "${'$'}USER_RC_RAN"
                    [[ "${'$'}{PATH%%:*}/java" -ef "${'$'}JAVA_HOME/bin/java" ]] && printf 'PROJECT_BIN_FIRST=yes\n'
                    java
                    export -p | command grep _KetraTerm_FORCE_ || :
                    exit
                    """.trimIndent() + "\n",
            )

        assertEquals(0, result.exitCode, visibleEscapes(result.stdout))
        assertTrue(result.stdout.contains("HOME_RESULT=$jdk"), visibleEscapes(result.stdout))
        assertTrue(result.stdout.contains("RC_RESULT=yes"), visibleEscapes(result.stdout))
        assertTrue(result.stdout.lineSequence().any { it.trimEnd('\r') == "PROJECT_BIN_FIRST=yes" }, visibleEscapes(result.stdout))
        assertTrue(result.stdout.contains("PROJECT_JAVA"), visibleEscapes(result.stdout))
        assertTrue(!result.stdout.contains("declare -x _KetraTerm_FORCE_"), visibleEscapes(result.stdout))
        assertTrue(result.stdout.contains("\u001B]133;A\u0007"), visibleEscapes(result.stdout))
    }

    @Test
    fun `Bash login startup sources first login file and reapplies environment once`(
        @TempDir tempDir: Path,
    ) {
        val bash = installedExecutable("bash", "bash.exe")
        assumeTrue(bash != null, "bash is not installed")
        val home = Files.createDirectory(tempDir.resolve("login home"))
        Files.writeString(
            home.resolve(".bash_profile"),
            """
            export JAVA_HOME=login-jdk
            export PATH=/usr/bin:/bin
            export LOGIN_FILE=profile
            """.trimIndent(),
        )
        Files.writeString(home.resolve(".bash_login"), "export LOGIN_FILE=wrong\n")
        val profile =
            TerminalProfile(
                id = "bash",
                displayName = "Bash",
                command = listOf(bash!!, "--login", "-i"),
                environment = mapOf("HOME" to home.toString(), "TERM" to "xterm-256color"),
                shellEnvironment = TerminalShellEnvironment(mapOf("JAVA_HOME" to "project-jdk"), "/project/bin"),
            )
        val launch = TerminalShellIntegrationBootstrap.apply(profile, enabled = true, scriptDirectory = tempDir.resolve("bootstrap"))
        val result =
            runProcess(
                launch.command,
                environment = launch.environment,
                standardInput =
                    """
                    printf 'LOGIN_RESULT=%s:%s:%s\n' "${'$'}JAVA_HOME" "${'$'}LOGIN_FILE" "${'$'}PATH"
                    export JAVA_HOME=user-change
                    printf 'USER_RESULT=%s\n' "${'$'}JAVA_HOME"
                    exit
                    """.trimIndent() + "\n",
            )

        assertEquals(0, result.exitCode, visibleEscapes(result.stdout))
        assertTrue(result.stdout.contains("LOGIN_RESULT=project-jdk:profile:/project/bin:/usr/bin:/bin"), visibleEscapes(result.stdout))
        assertTrue(result.stdout.contains("USER_RESULT=user-change"), visibleEscapes(result.stdout))
    }

    @Test
    fun `PowerShell startup reapplies toolchain after profile code and clears launch markers`(
        @TempDir tempDir: Path,
    ) {
        val powerShell = installedExecutable("pwsh", "pwsh.exe", "powershell.exe")
        assumeTrue(powerShell != null, "PowerShell is not installed")
        val javaHome = "project JDK ${'$'}literal 'quoted'"
        val bin = tempDir.resolve("project JDK ${'$'}literal 'quoted'/bin").toString()
        val profile =
            TerminalProfile(
                id = "powershell",
                displayName = "PowerShell",
                command = listOf(powerShell!!),
                shellEnvironment = TerminalShellEnvironment(mapOf("JAVA_HOME" to javaHome), bin),
            )
        val launch = TerminalShellIntegrationBootstrap.apply(profile, enabled = true, scriptDirectory = tempDir.resolve("bootstrap"))
        val bootstrap = String(Base64.getDecoder().decode(launch.command.last()), Charsets.UTF_16LE)
        val startupFile = tempDir.resolve("profile.ps1")
        Files.writeString(startupFile, "${'$'}env:JAVA_HOME = 'profile-jdk'\n${'$'}env:PATH = 'profile-bin'\n")
        val startup =
            """
            . '${startupFile.toString().replace("'", "''")}'
            $bootstrap
            [Console]::WriteLine('HOME_RESULT=' + ${'$'}env:JAVA_HOME)
            [Console]::WriteLine('PATH_RESULT=' + ${'$'}env:PATH)
            [Console]::WriteLine('MARKERS=' + @(Get-ChildItem Env: | Where-Object Name -Like '_KetraTerm_FORCE_*').Count)
            ${'$'}env:JAVA_HOME = 'user-change'
            prompt | Out-Null
            [Console]::WriteLine('USER_RESULT=' + ${'$'}env:JAVA_HOME)
            """.trimIndent()
        val encoded = Base64.getEncoder().encodeToString(startup.toByteArray(Charsets.UTF_16LE))
        val result = runProcess(listOf(powerShell, "-NoProfile", "-EncodedCommand", encoded), environment = launch.environment)

        assertEquals(0, result.exitCode, visibleEscapes(result.stdout))
        assertTrue(result.stdout.contains("HOME_RESULT=$javaHome"), visibleEscapes(result.stdout))
        assertTrue(result.stdout.contains("PATH_RESULT=$bin${java.io.File.pathSeparator}profile-bin"), visibleEscapes(result.stdout))
        assertTrue(result.stdout.contains("MARKERS=0"), visibleEscapes(result.stdout))
        assertTrue(result.stdout.contains("USER_RESULT=user-change"), visibleEscapes(result.stdout))
    }

    @Test
    fun `zsh startup reapplies toolchain after login file and follows user ZDOTDIR changes`(
        @TempDir tempDir: Path,
    ) {
        val zsh = installedExecutable("zsh", "zsh.exe")
        assumeTrue(zsh != null, "zsh is not installed")
        val original = Files.createDirectory(tempDir.resolve("original"))
        val relocated = Files.createDirectory(tempDir.resolve("relocated"))
        Files.writeString(original.resolve(".zshenv"), "export ZDOTDIR=${shellSingleQuote(relocated.toString())}\n")
        Files.writeString(relocated.resolve(".zshrc"), "export JAVA_HOME=rc-jdk\n")
        Files.writeString(
            relocated.resolve(".zlogin"),
            """
            export JAVA_HOME=login-jdk
            export PATH=/usr/bin:/bin
            export LOGIN_FILE=relocated
            """.trimIndent(),
        )
        val profile =
            TerminalProfile(
                id = "zsh",
                displayName = "Zsh",
                command = listOf(zsh!!, "-l", "-i"),
                environment = mapOf("ZDOTDIR" to original.toString(), "TERM" to "xterm-256color"),
                shellEnvironment = TerminalShellEnvironment(mapOf("JAVA_HOME" to "project-jdk"), "/project/bin"),
            )
        val launch = TerminalShellIntegrationBootstrap.apply(profile, enabled = true, scriptDirectory = tempDir.resolve("bootstrap"))
        val assertions =
            """
            printf 'LOGIN_RESULT=%s:%s:%s\n' "${'$'}JAVA_HOME" "${'$'}LOGIN_FILE" "${'$'}PATH"
            print -l -- ${'$'}{parameters[(I)_KetraTerm_FORCE_*]}
            exit 0
            """.trimIndent()
        val result =
            runProcess(
                launch.command + listOf("-c", assertions),
                environment = launch.environment,
            )
        assertEquals(0, result.exitCode, visibleEscapes(result.stdout))
        assertTrue(result.stdout.contains("LOGIN_RESULT=project-jdk:relocated:/project/bin:/usr/bin:/bin"), visibleEscapes(result.stdout))
        assertTrue(!result.stdout.lineSequence().any { it.startsWith("_KetraTerm_FORCE_SET_") }, visibleEscapes(result.stdout))
    }

    @Test
    fun `fish startup reapplies toolchain after user config and consumes markers`(
        @TempDir tempDir: Path,
    ) {
        val fish = installedExecutable("fish", "fish.exe")
        assumeTrue(fish != null, "fish is not installed")
        val config = Files.createDirectories(tempDir.resolve("config/fish"))
        Files.writeString(
            config.resolve("config.fish"),
            """
            set -gx JAVA_HOME config-jdk
            set -gx PATH /usr/bin /bin
            set -gx USER_CONFIG_RAN yes
            """.trimIndent(),
        )
        val profile =
            TerminalProfile(
                id = "fish",
                displayName = "Fish",
                command = listOf(fish!!, "-i"),
                environment = mapOf("XDG_CONFIG_HOME" to config.parent.toString(), "TERM" to "xterm-256color"),
                shellEnvironment = TerminalShellEnvironment(mapOf("JAVA_HOME" to "project-jdk"), "/project/bin"),
            )
        val launch = TerminalShellIntegrationBootstrap.apply(profile, enabled = true, scriptDirectory = tempDir.resolve("bootstrap"))
        val assertions =
            """
            printf 'RESULT=%s:%s:%s\n' "${'$'}JAVA_HOME" "${'$'}USER_CONFIG_RAN" "${'$'}PATH[1]"
            set --names | string match '_KetraTerm_FORCE_*'
            exit 0
            """.trimIndent()
        val result =
            runProcess(
                launch.command + listOf("-c", assertions),
                environment = launch.environment,
            )
        assertEquals(0, result.exitCode, visibleEscapes(result.stdout))
        assertTrue(result.stdout.contains("RESULT=project-jdk:yes:/project/bin"), visibleEscapes(result.stdout))
        assertTrue(!result.stdout.lineSequence().any { it.startsWith("_KetraTerm_FORCE_SET_") }, visibleEscapes(result.stdout))
    }

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
    fun `process runner drains PowerShell output larger than an operating system pipe buffer`() {
        val powerShell = installedExecutable("pwsh", "pwsh.exe", "powershell.exe")
        assumeTrue(powerShell != null, "PowerShell is not installed")
        val script = "[Console]::Out.Write('x' * $OUTPUT_STRESS_LENGTH)"
        val invocation = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))

        val result = runProcess(listOf(powerShell!!, "-NoProfile", "-EncodedCommand", invocation))

        assertEquals(0, result.exitCode)
        assertEquals(OUTPUT_STRESS_LENGTH, result.stdout.count { it == 'x' })
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
                standardInput = "$bootstrap; __ketraterm_preexec; false; __ketraterm_prompt_command\n",
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
                    "$bootstrap; __ketraterm_preexec; { clear >/dev/null 2>&1 || printf '\\033[H\\033[2J\\033[3J'; }; true; __ketraterm_prompt_command; __ketraterm_preexec; false; __ketraterm_prompt_command\n",
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
                environment = mapOf("KetraTerm_ORIGINAL_ZDOTDIR" to originalZdotdir.toString()),
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
                    )}; __ketraterm_zsh_precmd; __ketraterm_zsh_preexec false; false; __ketraterm_zsh_precmd",
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
        val outputTask = FutureTask { process.inputStream.use { it.readAllBytes() } }
        Thread.ofVirtual().name("shell-integration-test-output").start(outputTask)
        if (standardInput != null) {
            process.outputStream.use { input ->
                input.write(standardInput.toByteArray(StandardCharsets.UTF_8))
            }
        } else {
            process.outputStream.close()
        }
        val completed = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!completed) {
            process.destroyForcibly()
            if (!process.waitFor(PROCESS_TERMINATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                outputTask.cancel(true)
                error("process did not terminate after forced destruction: ${command.joinToString(" ")}")
            }
            val stdout = completedOutput(outputTask, command)
            error(
                "process timed out after $PROCESS_TIMEOUT_SECONDS seconds: ${command.joinToString(" ")}\n" +
                    visibleEscapes(stdout),
            )
        }
        val stdout = completedOutput(outputTask, command)
        return ProcessResult(process.exitValue(), stdout)
    }

    private fun completedOutput(
        outputTask: FutureTask<ByteArray>,
        command: List<String>,
    ): String =
        try {
            String(outputTask.get(PROCESS_TERMINATION_TIMEOUT_SECONDS, TimeUnit.SECONDS), StandardCharsets.UTF_8)
        } catch (_: TimeoutException) {
            outputTask.cancel(true)
            error("process output stream did not close: ${command.joinToString(" ")}")
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
        if (isWindows() && names.any { it == "bash" || it == "bash.exe" }) {
            val gitBash = Path.of(System.getenv("ProgramFiles") ?: "C:\\Program Files", "Git", "bin", "bash.exe")
            if (Files.isRegularFile(gitBash)) return gitBash.toString()
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

    private companion object {
        private const val OUTPUT_STRESS_LENGTH = 256 * 1024
        private const val PROCESS_TERMINATION_TIMEOUT_SECONDS = 5L
        private const val PROCESS_TIMEOUT_SECONDS = 30L
    }

    private data class ProcessResult(
        val exitCode: Int,
        val stdout: String,
    )
}
