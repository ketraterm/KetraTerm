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
}
