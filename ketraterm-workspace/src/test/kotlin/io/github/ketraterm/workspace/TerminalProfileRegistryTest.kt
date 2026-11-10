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
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TerminalProfileRegistryTest {
    @ParameterizedTest
    @ValueSource(strings = ["", "bad\u0000path;", "\"unterminated;", "\"\";"])
    fun windowsDiscoverySkipsMalformedPathEntriesAndFindsQuotedDirectories(
        prefix: String,
        @TempDir directory: Path,
    ) {
        val shellDirectory = directory.resolve("PowerShell 7")
        val executable = shellDirectory.resolve("pwsh.exe")
        val registry =
            TerminalProfileRegistry(
                osName = "Windows 11",
                environment = mapOf("PATH" to "$prefix\"$shellDirectory\""),
                pathSeparator = ";",
                executableExists = { it == executable },
            )

        val profile = registry.availableProfiles().single { it.id == "powershell" }
        assertEquals(listOf(executable.toString(), "-NoLogo"), profile.command)
        assertTrue(registry.isValidShellPath("pwsh.exe"))
        assertFalse(registry.isValidShellPath("missing.exe"))
    }

    @Test
    fun unixValidationAndDiscoveryRequireExecutePermission(
        @TempDir directory: Path,
    ) {
        assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"))
        val executable = Files.createFile(directory.resolve("bash"))
        val permissions = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        Files.setPosixFilePermissions(executable, permissions)
        val registry =
            TerminalProfileRegistry(
                osName = "Linux",
                environment = mapOf("PATH" to directory.toString()),
            )

        assertFalse(registry.isValidShellPath(executable.toString()))
        assertFalse(registry.isValidShellPath("bash"))
        assertTrue(registry.availableProfiles().none { it.command.first() == executable.toString() })

        val injected =
            TerminalProfileRegistry(
                osName = "Linux",
                environment = mapOf("PATH" to directory.toString()),
                executableExists = { it == executable },
            )
        assertTrue(injected.isValidShellPath(executable.toString()))
        assertEquals(
            executable.toString(),
            injected
                .availableProfiles()
                .single { it.id == "bash" }
                .command
                .first(),
        )

        Files.setPosixFilePermissions(executable, permissions + PosixFilePermission.OWNER_EXECUTE)
        assertTrue(registry.isValidShellPath(executable.toString()))
        assertTrue(registry.isValidShellPath("bash"))
        assertEquals(
            executable.toString(),
            registry
                .availableProfiles()
                .single { it.id == "bash" }
                .command
                .first(),
        )
    }

    @Test
    fun unixPathDirectoriesRetainLiteralQuotes(
        @TempDir directory: Path,
    ) {
        assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"))
        val shellDirectory = Files.createDirectory(directory.resolve("\"shells\""))
        val executable = shellDirectory.resolve("bash")
        val registry =
            TerminalProfileRegistry(
                osName = "Linux",
                environment = mapOf("PATH" to "bad\u0000path:$shellDirectory"),
                pathSeparator = ":",
                executableExists = { it == executable },
            )
        assertTrue(registry.isValidShellPath("bash"))
        assertEquals(
            executable.toString(),
            registry
                .availableProfiles()
                .single { it.id == "bash" }
                .command
                .first(),
        )
    }

    @Test
    fun profilesPreserveEmptyArgumentsThroughConstructionCopyAndInitialSelection() {
        val command = listOf("tool", "", "value with spaces", "", "")
        val profile = TerminalProfile(id = "custom", displayName = "Custom", command = command)
        assertEquals(command, profile.command)
        assertEquals(command, profile.copy(displayName = "Copy").command)
        val registry = TerminalProfileRegistry(osName = "Linux", environment = emptyMap(), executableExists = { false })
        assertEquals(command, registry.initialProfile(command).command)

        for (invalid in listOf(emptyList(), listOf(""), listOf("", "argument"))) {
            assertFailsWith<IllegalArgumentException> { TerminalProfile(id = "custom", displayName = "Custom", command = invalid) }
            assertFailsWith<IllegalArgumentException> { profile.copy(command = invalid) }
            if (invalid.isNotEmpty()) {
                assertFailsWith<IllegalArgumentException> { registry.initialProfile(invalid) }
            }
        }
    }

    @Test
    fun windowsDefaultValidationAcceptsRegularFilesAndRejectsDirectories(
        @TempDir directory: Path,
    ) {
        val executable = Files.createFile(directory.resolve("shell.exe"))
        val registry = TerminalProfileRegistry(osName = "Windows 11", environment = mapOf("PATH" to directory.toString()))
        assertTrue(registry.isValidShellPath(executable.toString()))
        assertTrue(registry.isValidShellPath("shell.exe"))
        assertFalse(registry.isValidShellPath(directory.toString()))
    }

    @Test
    fun localizedCommandPromptNamePreservesLaunchMetadataAndConfiguredProfileKind() {
        val registry =
            TerminalProfileRegistry(
                osName = "Windows 11",
                environment = mapOf("COMSPEC" to "cmd.exe"),
                executableExists = { false },
                commandPromptDisplayName = "Invite de commandes",
            )

        val profile = registry.availableProfiles().single { it.id == "cmd" }
        assertEquals("Invite de commandes", profile.displayName)
        assertEquals(listOf("cmd.exe"), profile.command)
        assertEquals(TerminalProfileKind.COMMAND_PROMPT, profile.kind)
        val configured = registry.configuredProfile("cmd.exe")
        assertEquals("Invite de commandes", configured.displayName)
        assertEquals(TerminalProfileKind.COMMAND_PROMPT, configured.kind)
    }

    @Test
    fun localizedFallbackNamePreservesDefaultLaunchCommand() {
        val registry =
            TerminalProfileRegistry(
                osName = "Linux",
                environment = emptyMap(),
                executableExists = { false },
                defaultShellDisplayName = "Shell par défaut",
            )
        val profile = registry.availableProfiles().single()
        val english =
            TerminalProfileRegistry(
                osName = "Linux",
                environment = emptyMap(),
                executableExists = { false },
            ).availableProfiles().single()

        assertEquals("default-shell", profile.id)
        assertEquals("Shell par défaut", profile.displayName)
        assertEquals(english.command, profile.command)
        assertEquals(english.kind, profile.kind)
    }

    @Test
    fun windowsProfilesHonorEnvironmentNameCasingAndPreserveMenuOrder() {
        val registry =
            TerminalProfileRegistry(
                osName = "Windows 11",
                environment =
                    mapOf(
                        "sYsTeMrOoT" to "C:\\Windows",
                        "Path" to "C:\\Program Files\\PowerShell\\7",
                        "comspec" to "C:\\Windows\\System32\\cmd.exe",
                    ),
                pathSeparator = ";",
                executableExists = { path ->
                    path == Path.of("C:\\Windows", "System32", "WindowsPowerShell", "v1.0", "powershell.exe") ||
                        path == Path.of("C:\\Program Files\\PowerShell\\7", "pwsh.exe")
                },
            )

        val profiles = registry.availableProfiles()

        assertEquals(listOf("windows-powershell", "powershell", "cmd"), profiles.map { it.id })
        val expectedPowerShell = Path.of("C:\\Windows", "System32", "WindowsPowerShell", "v1.0", "powershell.exe").toString()
        val expectedPwsh = Path.of("C:\\Program Files\\PowerShell\\7", "pwsh.exe").toString()
        val expectedCmd = "C:\\Windows\\System32\\cmd.exe"

        assertEquals(listOf(expectedPowerShell, "-NoLogo"), profiles[0].command)
        assertEquals(listOf(expectedPwsh, "-NoLogo"), profiles[1].command)
        assertEquals(listOf(expectedCmd), profiles[2].command)
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
    fun unixDiscoveryKeepsEnvironmentNamesCaseSensitive() {
        val executable = Path.of("/custom", "bash")
        for ((key, expectedId) in listOf("Path" to "default-shell", "PATH" to "bash")) {
            val registry =
                TerminalProfileRegistry(
                    osName = "Linux",
                    environment = mapOf(key to "/custom"),
                    pathSeparator = ":",
                    executableExists = { it == executable },
                )
            assertEquals(listOf(expectedId), registry.availableProfiles().map { it.id })
        }
    }

    @Test
    fun nonWindowsRegistryExposesPortableDefaultShellProfile() {
        val registry =
            TerminalProfileRegistry(
                osName = "Linux",
                environment = emptyMap(),
                executableExists = { false },
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
                        path == Path.of("C:\\Program Files", "Git", "bin", "bash.exe") ||
                        path == Path.of("C:\\Windows", "System32", "wsl.exe") ||
                        path == Path.of("C:\\Users\\me\\AppData\\Local\\Microsoft\\WindowsApps", "ubuntu.exe")
                },
            )

        val profiles = registry.availableProfiles()

        assertEquals(listOf("windows-powershell", "powershell", "git-bash", "wsl", "ubuntu", "cmd"), profiles.map { it.id })
        assertEquals(TerminalProfileKind.GIT_BASH, profiles[2].kind)
        val expectedGitBash = Path.of("C:\\Program Files", "Git", "bin", "bash.exe").toString()
        val expectedWsl = Path.of("C:\\Windows", "System32", "wsl.exe").toString()
        val expectedUbuntu = Path.of("C:\\Users\\me\\AppData\\Local\\Microsoft\\WindowsApps", "ubuntu.exe").toString()

        assertEquals(listOf(expectedGitBash, "--login", "-i"), profiles[2].command)
        assertEquals(TerminalProfileKind.WSL, profiles[3].kind)
        assertEquals(listOf(expectedWsl), profiles[3].command)
        assertEquals(TerminalProfileKind.UBUNTU, profiles[4].kind)
        assertEquals(listOf(expectedUbuntu), profiles[4].command)
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
        assertEquals(listOf("powershell.exe", "-NoLogo"), profile.command)
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
                executableExists = { false },
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
                executableExists = { false },
            )
        val dir = Path.of("/home/user/projects")

        val profile = registry.configuredProfile("/bin/bash", workingDirectory = dir)

        assertEquals(dir, profile.workingDirectory)
    }

    @Test
    fun configuredProfileResolvesKnownShellCommandArguments() {
        val registry =
            TerminalProfileRegistry(
                osName = "Windows 11",
                environment =
                    mapOf(
                        "SystemRoot" to "C:\\Windows",
                        "ProgramFiles" to "C:\\Program Files",
                        "COMSPEC" to "C:\\Windows\\System32\\cmd.exe",
                    ),
                pathSeparator = ";",
                executableExists = { path ->
                    path == Path.of("C:\\Windows", "System32", "WindowsPowerShell", "v1.0", "powershell.exe") ||
                        path == Path.of("C:\\Program Files", "Git", "bin", "bash.exe") ||
                        path == Path.of("C:\\Program Files", "Git", "git-bash.exe")
                },
            )

        val pwshProfile = registry.configuredProfile("powershell.exe")
        assertEquals(listOf("powershell.exe", "-NoLogo"), pwshProfile.command)

        val gitBashProfile = registry.configuredProfile("C:\\Program Files\\Git\\bin\\bash.exe")
        val expectedGitBash = "C:\\Program Files\\Git\\bin\\bash.exe"
        assertEquals(listOf(expectedGitBash, "--login", "-i"), gitBashProfile.command)
    }

    @Test
    fun configuredProfileResolvesUnixShellCommandArguments() {
        val registry =
            TerminalProfileRegistry(
                osName = "Linux",
                environment = emptyMap(),
                executableExists = { path ->
                    path == Path.of("/bin/zsh")
                },
            )

        val profile = registry.configuredProfile("/bin/zsh")
        assertEquals(listOf("/bin/zsh", "-l"), profile.command)
    }

    @Test
    fun unixProfilesDiscoversAvailableShells() {
        val registry =
            TerminalProfileRegistry(
                osName = "Linux",
                environment = mapOf("PATH" to "/usr/bin:/opt/homebrew/bin"),
                pathSeparator = ":",
                executableExists = { path ->
                    path == Path.of("/usr/bin", "zsh") ||
                        path == Path.of("/opt/homebrew/bin", "fish") ||
                        path == Path.of("/bin", "bash")
                },
            )

        val profiles = registry.availableProfiles()
        assertEquals(listOf("zsh", "bash", "fish"), profiles.map { it.id })

        assertEquals(TerminalProfileKind.ZSH, profiles[0].kind)
        assertEquals(listOf(Path.of("/usr/bin", "zsh").toString(), "-l"), profiles[0].command)

        assertEquals(TerminalProfileKind.BASH, profiles[1].kind)
        assertEquals(listOf(Path.of("/bin/bash").toString(), "-l"), profiles[1].command)

        assertEquals(TerminalProfileKind.FISH, profiles[2].kind)
        assertEquals(listOf(Path.of("/opt/homebrew/bin/fish").toString(), "-l"), profiles[2].command)
    }

    @Test
    fun windowsProfilesUsesGitBashLauncherIfBashExecutableIsMissing() {
        val registry =
            TerminalProfileRegistry(
                osName = "Windows 11",
                environment =
                    mapOf(
                        "SystemRoot" to "C:\\Windows",
                        "ProgramFiles" to "C:\\Program Files",
                        "COMSPEC" to "C:\\Windows\\System32\\cmd.exe",
                    ),
                pathSeparator = ";",
                executableExists = { path ->
                    path == Path.of("C:\\Program Files", "Git", "git-bash.exe")
                },
            )

        val profiles = registry.availableProfiles()
        val gitBash = profiles.firstOrNull { it.id == "git-bash" }
        assertNotNull(gitBash)
        val expectedGitBash = Path.of("C:\\Program Files", "Git", "git-bash.exe").toString()
        assertEquals(listOf(expectedGitBash), gitBash.command)
    }

    @Test
    fun testIsValidShellPath() {
        val registry =
            TerminalProfileRegistry(
                osName = "Linux",
                environment = mapOf("PATH" to "/usr/bin"),
                pathSeparator = ":",
                executableExists = { path ->
                    path == Path.of("/usr/bin", "zsh") ||
                        path == Path.of("/bin/bash")
                },
            )

        // Blank
        assertTrue(!registry.isValidShellPath(""))
        assertTrue(!registry.isValidShellPath("   "))

        // Absolute existing
        assertTrue(registry.isValidShellPath("/bin/bash"))

        // Absolute missing
        assertTrue(!registry.isValidShellPath("/bin/missing-shell"))

        // Command on PATH
        assertTrue(registry.isValidShellPath("zsh"))

        // Command not on PATH
        assertTrue(!registry.isValidShellPath("missing-shell"))
    }
}
