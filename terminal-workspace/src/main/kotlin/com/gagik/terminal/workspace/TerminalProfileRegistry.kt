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

import com.gagik.terminal.pty.TerminalPtyOptions
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/**
 * Discovers built-in local terminal launch profiles.
 *
 * Discovery is host-neutral startup work. Render and input hot paths never
 * consult this registry, and UI products may layer their own persisted profile
 * sources on top of these defaults.
 *
 * @property osName operating system name used for platform profile selection.
 * @property environment process environment used for path and shell discovery.
 * @property pathSeparator platform path separator.
 * @property executableExists predicate used to verify discovered executables.
 */
class TerminalProfileRegistry(
    private val osName: String = System.getProperty("os.name"),
    private val environment: Map<String, String> = System.getenv(),
    private val pathSeparator: String = File.pathSeparator,
    private val executableExists: (Path) -> Boolean = Files::isRegularFile,
) {
    /**
     * Returns built-in profiles in menu/default preference order.
     */
    fun availableProfiles(): List<TerminalProfile> =
        if (isWindows()) {
            windowsProfiles()
        } else {
            listOf(
                TerminalProfile(
                    id = "default-shell",
                    displayName = "Default Shell",
                    command = TerminalPtyOptions.defaultCommand(),
                ),
            )
        }

    /**
     * Returns the initial profile. Explicit command-line arguments are preserved
     * as a one-off custom profile so app startup remains scriptable.
     *
     * @param args command-line arguments passed by the product shell.
     * @return initial terminal launch profile.
     */
    fun initialProfile(args: List<String>): TerminalProfile =
        if (args.isNotEmpty()) {
            TerminalProfile(
                id = "command-line",
                displayName = args.first(),
                command = args,
            )
        } else {
            availableProfiles().first()
        }

    private fun windowsProfiles(): List<TerminalProfile> {
        val profiles = ArrayList<TerminalProfile>(WINDOWS_PROFILE_CAPACITY)
        profiles +=
            TerminalProfile(
                id = "windows-powershell",
                displayName = "Windows PowerShell",
                command = windowsPowerShellCommand(),
            )

        val pwsh = executableOnPath("pwsh.exe")
        if (pwsh != null) {
            profiles +=
                TerminalProfile(
                    id = "powershell",
                    displayName = "PowerShell",
                    command = listOf(pwsh.toString(), "-NoLogo"),
                )
        }

        profiles +=
            TerminalProfile(
                id = "cmd",
                displayName = "Command Prompt",
                command = listOf(commandPromptExecutable()),
            )
        return profiles
    }

    private fun windowsPowerShellCommand(): List<String> {
        val systemRoot = environment["SystemRoot"]
        if (!systemRoot.isNullOrBlank()) {
            val powershell =
                Path.of(
                    systemRoot,
                    "System32",
                    "WindowsPowerShell",
                    "v1.0",
                    "powershell.exe",
                )
            if (executableExists(powershell)) {
                return listOf(powershell.toString(), "-NoLogo")
            }
        }
        return listOf("powershell.exe", "-NoLogo")
    }

    private fun commandPromptExecutable(): String {
        val comspec = environment["COMSPEC"]
        return if (comspec.isNullOrBlank()) "cmd.exe" else comspec
    }

    private fun executableOnPath(executableName: String): Path? {
        val path = environment["PATH"] ?: return null
        val segments = path.split(pathSeparator)
        for (segment in segments) {
            if (segment.isBlank()) continue
            val candidate = Path.of(segment, executableName)
            if (executableExists(candidate)) return candidate
        }
        return null
    }

    private fun isWindows(): Boolean = osName.lowercase(Locale.ROOT).contains("windows")

    private companion object {
        private const val WINDOWS_PROFILE_CAPACITY = 3
    }
}
