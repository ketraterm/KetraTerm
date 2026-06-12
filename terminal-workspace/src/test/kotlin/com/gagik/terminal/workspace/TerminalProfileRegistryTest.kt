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
package com.gagik.terminal.workspace

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TerminalProfileRegistryTest {
    @Test
    fun windowsProfilesPreferWindowsPowerShellThenPowerShellThenCommandPrompt() {
        val registry =
            TerminalProfileRegistry(
                osName = "Windows 11",
                environment =
                    mapOf(
                        "SystemRoot" to "C:\\Windows",
                        "PATH" to "C:\\Program Files\\PowerShell\\7",
                        "COMSPEC" to "C:\\Windows\\System32\\cmd.exe",
                    ),
                pathSeparator = ";",
                executableExists = { path ->
                    path == Path.of("C:\\Windows", "System32", "WindowsPowerShell", "v1.0", "powershell.exe") ||
                        path == Path.of("C:\\Program Files\\PowerShell\\7", "pwsh.exe")
                },
            )

        val profiles = registry.availableProfiles()

        assertEquals(listOf("windows-powershell", "powershell", "cmd"), profiles.map { it.id })
        assertEquals(listOf("C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe", "-NoLogo"), profiles[0].command)
        assertEquals(listOf("C:\\Program Files\\PowerShell\\7\\pwsh.exe", "-NoLogo"), profiles[1].command)
        assertEquals(listOf("C:\\Windows\\System32\\cmd.exe"), profiles[2].command)
        assertEquals(
            listOf(
                TerminalProfileKind.POWERSHELL,
                TerminalProfileKind.POWERSHELL,
                TerminalProfileKind.COMMAND_PROMPT,
            ),
            profiles.map { it.kind },
        )
    }

    @Test
    fun commandLineArgumentsBecomeOneOffInitialProfile() {
        val registry =
            TerminalProfileRegistry(
                osName = "Linux",
                environment = emptyMap(),
            )

        val profile = registry.initialProfile(listOf("/bin/zsh", "-l"))

        assertEquals("command-line", profile.id)
        assertEquals("/bin/zsh", profile.displayName)
        assertEquals(listOf("/bin/zsh", "-l"), profile.command)
        assertEquals(TerminalProfileKind.ZSH, profile.kind)
    }

    @Test
    fun nonWindowsRegistryExposesPortableDefaultShellProfile() {
        val registry =
            TerminalProfileRegistry(
                osName = "Linux",
                environment = emptyMap(),
            )

        val profiles = registry.availableProfiles()

        assertEquals(1, profiles.size)
        assertEquals("default-shell", profiles.first().id)
        assertTrue(profiles.first().command.isNotEmpty())
    }

    @Test
    fun windowsProfilesIncludeGitBashAndUbuntuWhenLaunchersExist() {
        val registry =
            TerminalProfileRegistry(
                osName = "Windows 11",
                environment =
                    mapOf(
                        "SystemRoot" to "C:\\Windows",
                        "PATH" to "C:\\Program Files\\PowerShell\\7;C:\\Users\\me\\AppData\\Local\\Microsoft\\WindowsApps",
                        "COMSPEC" to "C:\\Windows\\System32\\cmd.exe",
                        "ProgramFiles" to "C:\\Program Files",
                    ),
                pathSeparator = ";",
                executableExists = { path ->
                    path == Path.of("C:\\Windows", "System32", "WindowsPowerShell", "v1.0", "powershell.exe") ||
                        path == Path.of("C:\\Program Files\\PowerShell\\7", "pwsh.exe") ||
                        path == Path.of("C:\\Program Files", "Git", "git-bash.exe") ||
                        path == Path.of("C:\\Users\\me\\AppData\\Local\\Microsoft\\WindowsApps", "ubuntu.exe")
                },
            )

        val profiles = registry.availableProfiles()

        assertEquals(listOf("windows-powershell", "powershell", "git-bash", "ubuntu", "cmd"), profiles.map { it.id })
        assertEquals(TerminalProfileKind.GIT_BASH, profiles[2].kind)
        assertEquals(listOf("C:\\Program Files\\Git\\git-bash.exe"), profiles[2].command)
        assertEquals(TerminalProfileKind.UBUNTU, profiles[3].kind)
        assertEquals(listOf("C:\\Users\\me\\AppData\\Local\\Microsoft\\WindowsApps\\ubuntu.exe"), profiles[3].command)
    }

    @Test
    fun profileKindClassifiesCommonShellCommands() {
        assertEquals(
            TerminalProfileKind.GIT_BASH,
            TerminalProfileKind.classify("custom", "Custom", listOf("C:\\Program Files\\Git\\bin\\bash.exe")),
        )
        assertEquals(
            TerminalProfileKind.UBUNTU,
            TerminalProfileKind.classify("custom", "Custom", listOf("wsl.exe", "-d", "Ubuntu")),
        )
        assertEquals(
            TerminalProfileKind.DEFAULT,
            TerminalProfileKind.classify("custom", "Custom", listOf("custom-shell.exe")),
        )
    }

    @Test
    fun configuredProfileInheritsDisplayNameFromKnownWindowsShell() {
        val registry =
            TerminalProfileRegistry(
                osName = "Windows 11",
                environment =
                    mapOf(
                        "SystemRoot" to "C:\\Windows",
                        "COMSPEC" to "C:\\Windows\\System32\\cmd.exe",
                    ),
                pathSeparator = ";",
                executableExists = { path ->
                    path == Path.of("C:\\Windows", "System32", "WindowsPowerShell", "v1.0", "powershell.exe")
                },
            )

        val profile = registry.configuredProfile("powershell.exe")

        assertEquals("configured-shell", profile.id)
        assertEquals("Windows PowerShell", profile.displayName)
        assertEquals(listOf("powershell.exe"), profile.command)
        assertEquals(TerminalProfileKind.POWERSHELL, profile.kind)
    }

    @Test
    fun configuredProfileUsesBasenameForUnknownCustomShell() {
        val registry =
            TerminalProfileRegistry(
                osName = "Windows 11",
                environment = mapOf("COMSPEC" to "cmd.exe"),
                pathSeparator = ";",
                executableExists = { false },
            )

        val profile = registry.configuredProfile("C:\\tools\\nu.exe")

        assertEquals("configured-shell", profile.id)
        assertEquals("nu.exe", profile.displayName)
        assertEquals(listOf("C:\\tools\\nu.exe"), profile.command)
        assertEquals(TerminalProfileKind.NUSHELL, profile.kind)
    }

    @Test
    fun configuredProfileClassifiesUnixShellCorrectly() {
        val registry =
            TerminalProfileRegistry(
                osName = "Linux",
                environment = emptyMap(),
            )

        val profile = registry.configuredProfile("/bin/zsh")

        assertEquals("configured-shell", profile.id)
        assertEquals(TerminalProfileKind.ZSH, profile.kind)
        assertEquals(listOf("/bin/zsh"), profile.command)
    }

    @Test
    fun configuredProfileForwardsWorkingDirectory() {
        val registry =
            TerminalProfileRegistry(
                osName = "Linux",
                environment = emptyMap(),
            )
        val dir = Path.of("/home/user/projects")

        val profile = registry.configuredProfile("/bin/bash", workingDirectory = dir)

        assertEquals(dir, profile.workingDirectory)
    }
}
