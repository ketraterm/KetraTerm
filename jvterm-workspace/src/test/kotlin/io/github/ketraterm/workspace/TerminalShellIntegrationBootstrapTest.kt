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

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.*
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.*

class TerminalShellIntegrationBootstrapTest {
    @Test
    fun `PowerShell profile receives interactive OSC 133 encoded bootstrap command`() {
        val profile =
            TerminalProfile(
                id = "powershell",
                displayName = "PowerShell",
                command = listOf("pwsh.exe", "-NoLogo"),
            )

        val integrated = TerminalShellIntegrationBootstrap.apply(profile, enabled = true)

        assertEquals("pwsh.exe", integrated.command[0])
        assertTrue("-NoLogo" in integrated.command)
        assertTrue("-NoExit" in integrated.command)
        assertEquals("-EncodedCommand", integrated.command[integrated.command.lastIndex - 1])

        val script = decodePowerShellScript(integrated.command.last())
        assertTrue(script.contains("function global:prompt"))
        assertTrue(script.contains("function global:PSConsoleHostReadLine"))
        assertTrue(script.contains("]133;"))
        assertTrue(script.contains("]7;"))
        assertTrue(script.contains("[System.UriBuilder]::new('file', [Environment]::MachineName)"))
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

        assertTrue(script.indexOf("try {") < script.indexOf("\$promptText = & \$global:__JvTermOriginalPrompt"))
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

        val integrated = TerminalShellIntegrationBootstrap.apply(profile, enabled = true)

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

        val integrated = TerminalShellIntegrationBootstrap.apply(profile, enabled = true)

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

        val integrated = TerminalShellIntegrationBootstrap.apply(profile, enabled = true)

        assertSame(profile, integrated)
    }

    @Test
    fun `disabled shell integration leaves PowerShell profile unchanged`() {
        val profile =
            TerminalProfile(
                id = "powershell",
                displayName = "PowerShell",
                command = listOf("pwsh.exe", "-NoLogo"),
            )

        val integrated = TerminalShellIntegrationBootstrap.apply(profile, enabled = false)

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

        val integrated = TerminalShellIntegrationBootstrap.apply(profile, enabled = true)

        assertEquals(profile.command, integrated.command)
        val promptCommand = integrated.environment.getValue("PROMPT_COMMAND")
        assertTrue(promptCommand.contains("__jvterm_prompt_command"))
        assertTrue(promptCommand.contains("]133;"))
        assertTrue(promptCommand.contains("__jvterm_osc7"))
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

        val integrated = TerminalShellIntegrationBootstrap.apply(profile, enabled = true)

        assertEquals(profile.command, integrated.command)
        assertTrue(integrated.environment.getValue("PROMPT_COMMAND").contains("__jvterm_preexec"))
    }

    @Test
    fun `explicit Bash command profile is not rewritten`() {
        val profile =
            TerminalProfile(
                id = "bash",
                displayName = "Bash",
                command = listOf("/bin/bash", "-lc", "echo already-custom"),
            )

        val integrated = TerminalShellIntegrationBootstrap.apply(profile, enabled = true)

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

        val integrated = TerminalShellIntegrationBootstrap.apply(profile, enabled = true, scriptDirectory = tempDir)
        val zshDirectory = tempDir.resolve("zsh")

        assertEquals(profile.command, integrated.command)
        assertEquals(originalZdotdir.toString(), integrated.environment.getValue("JVTERM_ORIGINAL_ZDOTDIR"))
        assertEquals(zshDirectory.toString(), integrated.environment.getValue("ZDOTDIR"))
        assertTrue(zshDirectory.resolve(".zshenv").exists())
        assertTrue(zshDirectory.resolve(".zprofile").exists())
        assertTrue(zshDirectory.resolve(".zshrc").readText().contains("__jvterm_zsh_preexec"))
        assertTrue(zshDirectory.resolve(".zshrc").readText().contains("]133;"))
        assertTrue(zshDirectory.resolve(".zshrc").readText().contains("__jvterm_zsh_osc7"))
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

        val integrated = TerminalShellIntegrationBootstrap.apply(profile, enabled = true, scriptDirectory = tempDir)

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

        val integrated = TerminalShellIntegrationBootstrap.apply(profile, enabled = true)

        assertEquals(listOf("/usr/bin/fish", "-l", "--init-command"), integrated.command.dropLast(1))
        assertTrue(integrated.command.last().contains("__jvterm_fish_preexec"))
        assertTrue(integrated.command.last().contains("]133;"))
        assertTrue(integrated.command.last().contains("__jvterm_osc7"))
        assertTrue(integrated.command.last().contains("string escape --style=url"))
    }

    @Test
    fun `explicit fish command profile is not rewritten`() {
        val profile =
            TerminalProfile(
                id = "fish",
                displayName = "Fish",
                command = listOf("/usr/bin/fish", "-c", "echo already-custom"),
            )

        val integrated = TerminalShellIntegrationBootstrap.apply(profile, enabled = true)

        assertSame(profile, integrated)
    }

    @Test
    fun `default profiles remain unchanged`() {
        val profile =
            TerminalProfile(
                id = "custom",
                displayName = "Custom",
                command = listOf("custom-shell"),
                kind = TerminalProfileKind.DEFAULT,
            )

        val integrated = TerminalShellIntegrationBootstrap.apply(profile, enabled = true)

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

        val integrated = TerminalShellIntegrationBootstrap.apply(profile, enabled = true)

        assertEquals(profile.command, integrated.command)
        assertTrue(integrated.environment.getValue("PROMPT_COMMAND").contains("]133;"))
        assertEquals("EXISTING/u:PROMPT_COMMAND/u", integrated.environment["WSLENV"])
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

        val integrated = TerminalShellIntegrationBootstrap.apply(profile, enabled = true, scriptDirectory = tempDir)

        assertEquals(profile.command, integrated.command)
        assertEquals(tempDir.resolve("zsh").toString(), integrated.environment["ZDOTDIR"])
        assertEquals("JVTERM_ORIGINAL_ZDOTDIR/up:ZDOTDIR/up", integrated.environment["WSLENV"])
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

        val integrated = TerminalShellIntegrationBootstrap.apply(fish, enabled = true)

        assertEquals(listOf("wsl.exe", "-e", "fish", "-l", "--init-command"), integrated.command.dropLast(1))
        assertTrue(integrated.command.last().contains("]133;"))
        assertSame(defaultShell, TerminalShellIntegrationBootstrap.apply(defaultShell, enabled = true))
    }

    private fun decodePowerShellScript(encoded: String): String = String(Base64.getDecoder().decode(encoded), Charsets.UTF_16LE)

    private fun integratedPowerShellScript(): String {
        val profile =
            TerminalProfile(
                id = "powershell",
                displayName = "PowerShell",
                command = listOf("pwsh.exe"),
            )
        return decodePowerShellScript(TerminalShellIntegrationBootstrap.apply(profile, enabled = true).command.last())
    }
}
