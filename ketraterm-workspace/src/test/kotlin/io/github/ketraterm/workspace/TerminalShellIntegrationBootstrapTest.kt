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

import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.*
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.*

class TerminalShellIntegrationBootstrapTest {
    @Test
    fun `interactive hook installation reports prompt expectation before output`(
        @TempDir directory: Path,
    ) {
        val commands =
            listOf(
                listOf("pwsh.exe", "-NoLogo"),
                listOf("bash", "-l"),
                listOf("zsh", "-l"),
                listOf("fish"),
                listOf("wsl.exe", "-e", "bash", "-l"),
            )
        for (command in commands) {
            val profile = TerminalProfile("test", "test", command)
            val launch = TerminalShellIntegrationBootstrap.apply(profile, true, directory)
            assertTrue(launch.promptMarkersExpected, command.toString())
        }
    }

    @Test
    fun `environment changes and skipped hooks do not imply prompt expectation`(
        @TempDir directory: Path,
    ) {
        val commands =
            listOf(
                listOf("cmd.exe"),
                listOf("pwsh.exe", "-Command", "Write-Host custom"),
                listOf("bash", "-c", "echo custom"),
                listOf("zsh", "-f"),
                listOf("fish", "-c", "echo custom"),
                listOf("wsl.exe"),
            )
        for (command in commands) {
            val profile =
                TerminalProfile(
                    "test",
                    "test",
                    command,
                    shellEnvironment = TerminalShellEnvironment(mapOf("KETRA_TEST" to "value")),
                )
            val launch = TerminalShellIntegrationBootstrap.apply(profile, true, directory)
            assertFalse(launch.promptMarkersExpected, command.toString())
            assertEquals("value", launch.profile.environment["KETRA_TEST"])
            assertEquals(command, launch.profile.command)
        }
        val disabled =
            TerminalShellIntegrationBootstrap.apply(
                TerminalProfile("test", "test", listOf("pwsh.exe")),
                false,
                directory,
            )
        assertFalse(disabled.promptMarkersExpected)
    }

    @Test
    fun `failed hook file preparation leaves prompt expectation unconfirmed`(
        @TempDir directory: Path,
    ) {
        val occupied =
            java.nio.file.Files
                .createFile(directory.resolve("occupied"))
        val launch =
            TerminalShellIntegrationBootstrap.apply(
                TerminalProfile("test", "test", listOf("zsh")),
                true,
                occupied,
            )
        assertFalse(launch.promptMarkersExpected)
        assertEquals(listOf("zsh"), launch.profile.command)
    }

    @Test
    fun `PowerShell profile receives interactive OSC 133 encoded bootstrap command`() {
        val profile =
            TerminalProfile(
                id = "powershell",
                displayName = "PowerShell",
                command = listOf("pwsh.exe", "-NoLogo"),
            )

        val integrated = TerminalShellIntegrationBootstrap.apply(profile, enabled = true).profile

        assertEquals("pwsh.exe", integrated.command[0])
        assertTrue("-NoLogo" in integrated.command)
        assertTrue("-NoExit" in integrated.command)
        assertEquals("-EncodedCommand", integrated.command[integrated.command.lastIndex - 1])

        val script = decodePowerShellScript(integrated.command.last())
        assertTrue(script.contains("function global:prompt"))
        assertTrue(script.contains("function global:PSConsoleHostReadLine"))
        assertTrue(script.contains("]133;"))
        assertTrue(script.contains("]7;"))
        assertTrue(script.contains("[System.UriBuilder]::new('file', 'localhost')"))
        assertTrue(script.contains("Provider.Name -ne 'FileSystem'"))
        assertTrue(script.contains("'D;' + ${'$'}exitCode"))
        assertFalse(script.contains('"'))
    }

    @Test
    fun `PowerShell bootstrap prefers changed native exit code before success fallback`() {
        val script = integratedPowerShellScript()

        assertTrue(
            script.indexOf("if (\$nativeExitCode -is [int]") < script.indexOf("elseif (\$success)"),
            "native LASTEXITCODE branch must be evaluated before PowerShell success fallback",
        )
    }

    @Test
    fun `PowerShell bootstrap restores native exit code even when user prompt fails`() {
        val script = integratedPowerShellScript()

        assertTrue(script.indexOf("try {") < script.indexOf("\$promptText = & \$global:__KetraTermOriginalPrompt"))
        assertTrue(script.indexOf("} finally {") < script.indexOf("\$global:LASTEXITCODE = \$nativeExitCode"))
        assertTrue(script.contains("\$global:LASTEXITCODE = \$nativeExitCode"))
    }

    @Test
    fun `PowerShell profile with existing NoExit does not duplicate it`() {
        val profile =
            TerminalProfile(
                id = "powershell",
                displayName = "PowerShell",
                command = listOf("powershell.exe", "-NoLogo", "/NoExit"),
            )

        val integrated = TerminalShellIntegrationBootstrap.apply(profile, enabled = true).profile

        assertEquals(
            1,
            integrated.command.count { it.equals("-NoExit", ignoreCase = true) || it.equals("/NoExit", ignoreCase = true) },
        )
    }

    @Test
    fun `explicit PowerShell entrypoint is not rewritten`() {
        val profile =
            TerminalProfile(
                id = "powershell",
                displayName = "PowerShell",
                command = listOf("pwsh.exe", "-NoLogo", "-Command", "Write-Host already-custom"),
            )

        val integrated = TerminalShellIntegrationBootstrap.apply(profile, enabled = true).profile

        assertSame(profile, integrated)
    }

    @Test
    fun `explicit PowerShell entrypoint with inline value is not rewritten`() {
        val profile =
            TerminalProfile(
                id = "powershell",
                displayName = "PowerShell",
                command = listOf("pwsh.exe", "/Command:Write-Host already-custom"),
            )

        val integrated = TerminalShellIntegrationBootstrap.apply(profile, enabled = true).profile

        assertSame(profile, integrated)
    }

    @Test
    fun `disabled shell integration leaves PowerShell profile unchanged`() {
        val profile =
            TerminalProfile(
                id = "powershell",
                displayName = "PowerShell",
                command = listOf("pwsh.exe", "-NoLogo"),
                environment = mapOf("Path" to "host-bin", "KetraTerm_CONFIG_PATH" to "host-settings.xml"),
            )

        val integrated = TerminalShellIntegrationBootstrap.apply(profile, enabled = false).profile

        assertSame(profile, integrated)
    }

    @Test
    fun `Bash profile receives prompt command OSC 133 bootstrap environment`() {
        val profile =
            TerminalProfile(
                id = "bash",
                displayName = "Bash",
                command = listOf("/bin/bash", "-l"),
                environment = mapOf("PROMPT_COMMAND" to "history -a"),
            )

        val integrated = TerminalShellIntegrationBootstrap.apply(profile, enabled = true).profile

        assertEquals(profile.command, integrated.command)
        val promptCommand = integrated.environment.getValue("PROMPT_COMMAND")
        assertTrue(promptCommand.contains("__ketraterm_prompt_command"))
        assertTrue(promptCommand.contains("]133;"))
        assertTrue(promptCommand.contains("__ketraterm_osc7"))
        assertTrue(promptCommand.contains("KetraTerm_OSC7_AUTHORITY"))
        assertEquals("localhost", integrated.environment["KetraTerm_OSC7_AUTHORITY"])
        assertTrue(promptCommand.contains("'%%%02X'"))
        assertTrue(promptCommand.endsWith("history -a"))
    }

    @Test
    fun `Git Bash profile receives Bash-compatible OSC 133 bootstrap environment`() {
        val profile =
            TerminalProfile(
                id = "git-bash",
                displayName = "Git Bash",
                command = listOf("C:\\Program Files\\Git\\bin\\bash.exe", "--login", "-i"),
            )

        val integrated = TerminalShellIntegrationBootstrap.apply(profile, enabled = true).profile

        assertEquals(profile.command, integrated.command)
        assertTrue(integrated.environment.getValue("PROMPT_COMMAND").contains("__ketraterm_preexec"))
    }

    @Test
    fun `explicit Bash command profile is not rewritten`() {
        val profile =
            TerminalProfile(
                id = "bash",
                displayName = "Bash",
                command = listOf("/bin/bash", "-lc", "echo already-custom"),
            )

        val integrated = TerminalShellIntegrationBootstrap.apply(profile, enabled = true).profile

        assertSame(profile, integrated)
    }

    @Test
    fun `zsh profile receives generated ZDOTDIR bootstrap files`(
        @TempDir tempDir: Path,
    ) {
        val originalZdotdir = tempDir.resolve("original")
        val profile =
            TerminalProfile(
                id = "zsh",
                displayName = "Zsh",
                command = listOf("/bin/zsh", "-l"),
                environment = mapOf("ZDOTDIR" to originalZdotdir.toString()),
            )

        val integrated = TerminalShellIntegrationBootstrap.apply(profile, enabled = true, scriptDirectory = tempDir).profile
        val zshDirectory = tempDir.resolve("zsh")

        assertEquals(profile.command, integrated.command)
        assertEquals(originalZdotdir.toString(), integrated.environment.getValue("KetraTerm_ORIGINAL_ZDOTDIR"))
        assertEquals(zshDirectory.toString(), integrated.environment.getValue("ZDOTDIR"))
        assertEquals("localhost", integrated.environment["KetraTerm_OSC7_AUTHORITY"])
        assertTrue(zshDirectory.resolve(".zshenv").exists())
        assertTrue(zshDirectory.resolve(".zprofile").exists())
        assertTrue(zshDirectory.resolve(".zshrc").readText().contains("__ketraterm_zsh_preexec"))
        assertTrue(zshDirectory.resolve(".zshrc").readText().contains("]133;"))
        assertTrue(zshDirectory.resolve(".zshrc").readText().contains("__ketraterm_zsh_osc7"))
        assertTrue(zshDirectory.resolve(".zshrc").readText().contains("'%%%02X'"))
        assertTrue(zshDirectory.resolve(".zlogin").exists())
        assertTrue(zshDirectory.resolve(".zlogout").exists())
    }

    @Test
    fun `explicit zsh command profile is not rewritten`(
        @TempDir tempDir: Path,
    ) {
        val profile =
            TerminalProfile(
                id = "zsh",
                displayName = "Zsh",
                command = listOf("/bin/zsh", "-c", "echo already-custom"),
            )

        val integrated = TerminalShellIntegrationBootstrap.apply(profile, enabled = true, scriptDirectory = tempDir).profile

        assertSame(profile, integrated)
        assertFalse(tempDir.resolve("zsh").exists())
    }

    @Test
    fun `fish profile receives init-command OSC 133 bootstrap`() {
        val profile =
            TerminalProfile(
                id = "fish",
                displayName = "Fish",
                command = listOf("/usr/bin/fish", "-l"),
            )

        val integrated = TerminalShellIntegrationBootstrap.apply(profile, enabled = true).profile

        assertEquals(listOf("/usr/bin/fish", "-l", "--init-command"), integrated.command.dropLast(1))
        assertTrue(integrated.command.last().contains("__ketraterm_fish_preexec"))
        assertTrue(integrated.command.last().contains("]133;"))
        assertTrue(integrated.command.last().contains("__ketraterm_osc7"))
        assertTrue(integrated.command.last().contains("string escape --style=url"))
        assertEquals("localhost", integrated.environment["KetraTerm_OSC7_AUTHORITY"])
    }

    @Test
    fun `explicit fish command profile is not rewritten`() {
        val profile =
            TerminalProfile(
                id = "fish",
                displayName = "Fish",
                command = listOf("/usr/bin/fish", "-c", "echo already-custom"),
            )

        val integrated = TerminalShellIntegrationBootstrap.apply(profile, enabled = true).profile

        assertSame(profile, integrated)
    }

    @Test
    fun `default profiles remain unchanged`() {
        val profile =
            TerminalProfile(
                id = "custom",
                displayName = "Custom",
                command = listOf("custom-shell"),
                environment = mapOf("PATH" to "host-bin", "KetraTerm_CONFIG_PATH" to "host-settings.toml"),
                kind = TerminalProfileKind.DEFAULT,
            )

        val integrated = TerminalShellIntegrationBootstrap.apply(profile, enabled = true).profile

        assertSame(profile, integrated)
    }

    @Test
    fun `explicit WSL bash receives integration through WSLENV`() {
        val profile =
            TerminalProfile(
                id = "wsl",
                displayName = "WSL Bash",
                command = listOf("wsl.exe", "--distribution", "Ubuntu", "--exec", "/bin/bash", "-l"),
                environment = mapOf("WSLENV" to "EXISTING/u"),
                kind = TerminalProfileKind.WSL,
            )

        val integrated = TerminalShellIntegrationBootstrap.apply(profile, enabled = true).profile

        assertEquals(profile.command, integrated.command)
        assertTrue(integrated.environment.getValue("PROMPT_COMMAND").contains("]133;"))
        assertEquals("EXISTING/u:PROMPT_COMMAND/u", integrated.environment["WSLENV"])
        assertNull(integrated.environment["KetraTerm_OSC7_AUTHORITY"])
    }

    @Test
    fun `explicit WSL zsh translates generated startup directory into WSL`(
        @TempDir tempDir: Path,
    ) {
        val profile =
            TerminalProfile(
                id = "wsl",
                displayName = "WSL Zsh",
                command = listOf("wsl.exe", "--", "zsh", "-l"),
                kind = TerminalProfileKind.WSL,
            )

        val integrated = TerminalShellIntegrationBootstrap.apply(profile, enabled = true, scriptDirectory = tempDir).profile

        assertEquals(profile.command, integrated.command)
        assertEquals(tempDir.resolve("zsh").toString(), integrated.environment["ZDOTDIR"])
        assertEquals("KetraTerm_ORIGINAL_ZDOTDIR/up:ZDOTDIR/up", integrated.environment["WSLENV"])
        assertNull(integrated.environment["KetraTerm_OSC7_AUTHORITY"])
        assertTrue(tempDir.resolve("zsh/.zshrc").toFile().isFile)
    }

    @Test
    fun `explicit WSL fish receives init command while unknown default shell remains untouched`() {
        val fish =
            TerminalProfile(
                id = "wsl-fish",
                displayName = "WSL Fish",
                command = listOf("wsl.exe", "-e", "fish", "-l"),
                kind = TerminalProfileKind.WSL,
            )
        val defaultShell =
            TerminalProfile(
                id = "wsl",
                displayName = "WSL",
                command = listOf("wsl.exe", "--distribution", "Ubuntu"),
                kind = TerminalProfileKind.WSL,
            )

        val integrated = TerminalShellIntegrationBootstrap.apply(fish, enabled = true).profile

        assertEquals(listOf("wsl.exe", "-e", "fish", "-l", "--init-command"), integrated.command.dropLast(1))
        assertTrue(integrated.command.last().contains("]133;"))
        assertNull(integrated.environment["KetraTerm_OSC7_AUTHORITY"])
        assertSame(defaultShell, TerminalShellIntegrationBootstrap.apply(defaultShell, enabled = true).profile)
    }

    @Test
    fun `Bash shell hooks do not install standalone configuration or commands`(
        @TempDir tempDir: Path,
    ) {
        val profile =
            TerminalProfile(
                id = "bash",
                displayName = "Bash",
                command = listOf("bash"),
            )

        val integrated = TerminalShellIntegrationBootstrap.apply(profile, enabled = true, scriptDirectory = tempDir).profile

        val promptCommand = integrated.environment.getValue("PROMPT_COMMAND")
        assertTrue(promptCommand.contains("]133;"))
        assertTrue(promptCommand.contains("]7;"))
        assertEquals("localhost", integrated.environment["KetraTerm_OSC7_AUTHORITY"])
        assertAll(
            { assertNull(integrated.environment["KetraTerm_CONFIG_PATH"], "The host chooses its settings location") },
            { assertNull(integrated.environment["KetraTerm_VERSION"], "The host supplies its product version") },
            { assertNull(integrated.environment["KetraTerm_OS"], "Shell hooks must not inject diagnostic metadata") },
            { assertNull(integrated.environment["KetraTerm_JVM"], "Shell hooks must not inject diagnostic metadata") },
            {
                assertTrue(
                    integrated.environment.keys.none {
                        it.equals("PATH", ignoreCase = true)
                    },
                    "Shell hooks must retain the inherited executable search path",
                )
            },
            { assertFalse(tempDir.resolve("bin/ketra").exists(), "Shell hooks must not install the standalone POSIX command") },
            { assertFalse(tempDir.resolve("bin/ketra.bat").exists(), "Shell hooks must not install the standalone Windows command") },
        )
    }

    @Test
    fun `Bash shell hooks preserve host selected configuration and executable search path`(
        @TempDir tempDir: Path,
    ) {
        val hostPath = tempDir.resolve("host-bin").toString()
        val hostConfig = tempDir.resolve("host-settings.toml").toString()
        val profile =
            TerminalProfile(
                id = "bash",
                displayName = "Bash",
                command = listOf("bash"),
                environment = mapOf("PATH" to hostPath, "KetraTerm_CONFIG_PATH" to hostConfig, "PROMPT_COMMAND" to "history -a"),
            )

        val integrated = TerminalShellIntegrationBootstrap.apply(profile, enabled = true, scriptDirectory = tempDir).profile

        val promptCommand = integrated.environment.getValue("PROMPT_COMMAND")
        assertTrue(promptCommand.contains("]133;"))
        assertTrue(promptCommand.contains("]7;"))
        assertTrue(promptCommand.endsWith("history -a"))
        assertAll(
            { assertEquals(hostConfig, integrated.environment["KetraTerm_CONFIG_PATH"]) },
            { assertEquals(mapOf("PATH" to hostPath), integrated.environment.filterKeys { it.equals("PATH", ignoreCase = true) }) },
            { assertFalse(tempDir.resolve("bin/ketra").exists()) },
            { assertFalse(tempDir.resolve("bin/ketra.bat").exists()) },
        )
    }

    @Test
    fun `PowerShell hooks preserve host configuration and mixed case executable search path`(
        @TempDir tempDir: Path,
    ) {
        val hostPath = tempDir.resolve("host-bin").toString()
        val hostConfig = tempDir.resolve("host-settings.xml").toString()
        val profile =
            TerminalProfile(
                id = "powershell",
                displayName = "PowerShell",
                command = listOf("pwsh.exe"),
                environment = mapOf("Path" to hostPath, "KetraTerm_CONFIG_PATH" to hostConfig),
            )

        val integrated = TerminalShellIntegrationBootstrap.apply(profile, enabled = true, scriptDirectory = tempDir).profile

        val script = decodePowerShellScript(integrated.command.last())
        assertTrue(script.contains("]133;"))
        assertTrue(script.contains("]7;"))
        assertAll(
            { assertEquals(hostConfig, integrated.environment["KetraTerm_CONFIG_PATH"]) },
            { assertEquals(mapOf("Path" to hostPath), integrated.environment.filterKeys { it.equals("PATH", ignoreCase = true) }) },
            { assertFalse(tempDir.resolve("bin/ketra").exists()) },
            { assertFalse(tempDir.resolve("bin/ketra.bat").exists()) },
        )
    }

    @Test
    fun `shell families preserve host environment without introducing standalone policy`(
        @TempDir tempDir: Path,
    ) {
        val profiles =
            listOf(
                TerminalProfile("powershell", "PowerShell", listOf("pwsh.exe")),
                TerminalProfile("git-bash", "Git Bash", listOf("C:\\Program Files\\Git\\bin\\bash.exe", "-l")),
                TerminalProfile("zsh", "Zsh", listOf("zsh", "-l")),
                TerminalProfile("fish", "Fish", listOf("fish", "-l")),
                TerminalProfile("wsl-bash", "WSL Bash", listOf("wsl.exe", "-e", "bash", "-l"), kind = TerminalProfileKind.WSL),
                TerminalProfile("wsl-zsh", "WSL Zsh", listOf("wsl.exe", "-e", "zsh", "-l"), kind = TerminalProfileKind.WSL),
                TerminalProfile("wsl-fish", "WSL Fish", listOf("wsl.exe", "-e", "fish", "-l"), kind = TerminalProfileKind.WSL),
                TerminalProfile("ubuntu", "Ubuntu", listOf("ubuntu.exe", "run", "bash", "-l")),
            )
        val hostEnvironment =
            mapOf(
                "Path" to tempDir.resolve("host-bin").toString(),
                "KetraTerm_CONFIG_PATH" to tempDir.resolve("host-settings.xml").toString(),
                "KetraTerm_VERSION" to "host-version",
                "KetraTerm_OS" to "host-os",
                "KetraTerm_JVM" to "host-runtime",
                "HOST_CUSTOM_VALUE" to "retained",
            )

        for (profile in profiles) {
            val scriptDirectory = tempDir.resolve(profile.id)
            val inherited = TerminalShellIntegrationBootstrap.apply(profile, enabled = true, scriptDirectory = scriptDirectory).profile
            val explicit =
                TerminalShellIntegrationBootstrap
                    .apply(
                        profile.copy(environment = hostEnvironment),
                        enabled = true,
                        scriptDirectory = scriptDirectory,
                    ).profile

            assertAll(
                profile.displayName,
                { assertNotEquals(profile, inherited, "The supported shell still receives integration hooks") },
                { assertTrue(inherited.environment.keys.none { it.equals("PATH", ignoreCase = true) }) },
                { assertTrue(inherited.environment.keys.none { it in hostEnvironment }, "The host owns product metadata") },
                { assertEquals(hostEnvironment, explicit.environment.filterKeys { it in hostEnvironment }) },
                {
                    assertEquals(
                        mapOf("Path" to hostEnvironment.getValue("Path")),
                        explicit.environment.filterKeys { it.equals("PATH", true) },
                    )
                },
                { assertFalse(scriptDirectory.resolve("bin").exists(), "Shell hooks must not install product commands") },
            )
        }
    }

    private fun decodePowerShellScript(encoded: String): String = String(Base64.getDecoder().decode(encoded), Charsets.UTF_16LE)

    private fun integratedPowerShellScript(): String {
        val profile =
            TerminalProfile(
                id = "powershell",
                displayName = "PowerShell",
                command = listOf("pwsh.exe"),
            )
        return decodePowerShellScript(
            TerminalShellIntegrationBootstrap
                .apply(profile, enabled = true)
                .profile.command
                .last(),
        )
    }
}
