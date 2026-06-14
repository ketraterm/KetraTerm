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

import io.github.jvterm.pty.PtyOptions
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.*

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
     *
     * @return a list of discovered built-in [TerminalProfile]s.
     */
    fun availableProfiles(): List<TerminalProfile> =
        if (isWindows()) {
            windowsProfiles()
        } else {
            unixProfiles()
        }

    /**
     * Builds a [TerminalProfile] from a user-configured shell path.
     *
     * This is the bridge between the persisted `shellPath` setting and the
     * PTY launch pipeline. It is called at settings-apply time (never on the
     * hot render/input path) and therefore may perform path resolution work.
     *
     * Display name resolution priority:
     * 1. If [shellPath] matches the primary executable of a known built-in
     *    profile (by basename, case-insensitive), that profile's polished
     *    display name is used.
     * 2. Otherwise the basename of [shellPath] is used.
     *
     * [TerminalProfileKind] is always derived from the command so that UI icons
     * remain correct for all recognised shells.
     *
     * @param shellPath raw shell path from the persisted configuration (e.g.
     *   `"powershell.exe"`, `"C:\\tools\\nu.exe"`, `"/bin/zsh"`).
     * @param workingDirectory initial PTY working directory, or `null` for the
     *   platform default.
     * @return launch profile ready for use by the PTY session.
     */
    fun configuredProfile(
        shellPath: String,
        workingDirectory: Path? = null,
    ): TerminalProfile {
        val basename = shellPath.substringAfterLast('\\').substringAfterLast('/')
        val basenameLower = basename.lowercase(Locale.ROOT)
        val builtIn =
            availableProfiles().firstOrNull { profile ->
                val profileExec =
                    profile.command
                        .first()
                        .substringAfterLast('\\')
                        .substringAfterLast('/')
                profileExec.lowercase(Locale.ROOT) == basenameLower
            }
        val command =
            if (builtIn != null) {
                listOf(shellPath) + builtIn.command.drop(1)
            } else {
                listOf(shellPath)
            }
        val displayName = builtIn?.displayName ?: basename
        return TerminalProfile(
            id = CONFIGURED_SHELL_ID,
            displayName = displayName,
            command = command,
            workingDirectory = workingDirectory,
        )
    }

    /**
     * Checks if a shell path is a valid executable file or an executable command on the PATH.
     *
     * @param shellPath shell path to validate.
     * @return true if the shell path exists and is executable, false otherwise.
     */
    fun isValidShellPath(shellPath: String): Boolean {
        if (shellPath.isBlank()) return false
        val path =
            try {
                Path.of(shellPath)
            } catch (_: Exception) {
                return false
            }
        if (path.isAbsolute) {
            return executableExists(path)
        }
        if (executableOnPath(shellPath) != null) {
            return true
        }
        return executableExists(path)
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

        val pwsh = pwshExecutable()
        if (pwsh != null) {
            profiles +=
                TerminalProfile(
                    id = "powershell",
                    displayName = "PowerShell",
                    command = listOf(pwsh.toString(), "-NoLogo"),
                )
        }

        val gitBashCmd = gitBashCommand()
        if (gitBashCmd != null) {
            profiles +=
                TerminalProfile(
                    id = "git-bash",
                    displayName = "Git Bash",
                    command = gitBashCmd,
                )
        }

        val wsl = wslExecutable()
        if (wsl != null) {
            profiles +=
                TerminalProfile(
                    id = "wsl",
                    displayName = "WSL",
                    command = listOf(wsl.toString()),
                )
        }

        val ubuntu = executableOnPath("ubuntu.exe")
        if (ubuntu != null) {
            profiles +=
                TerminalProfile(
                    id = "ubuntu",
                    displayName = "Ubuntu",
                    command = listOf(ubuntu.toString()),
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

    private fun unixProfiles(): List<TerminalProfile> {
        val profiles = ArrayList<TerminalProfile>(5)

        val zsh = unixShellExecutable("zsh")
        if (zsh != null) {
            profiles +=
                TerminalProfile(
                    id = "zsh",
                    displayName = "Zsh",
                    command = listOf(zsh.toString(), "-l"),
                )
        }

        val bash = unixShellExecutable("bash")
        if (bash != null) {
            profiles +=
                TerminalProfile(
                    id = "bash",
                    displayName = "Bash",
                    command = listOf(bash.toString(), "-l"),
                )
        }

        val fish = unixShellExecutable("fish")
        if (fish != null) {
            profiles +=
                TerminalProfile(
                    id = "fish",
                    displayName = "Fish",
                    command = listOf(fish.toString(), "-l"),
                )
        }

        val nu = unixShellExecutable("nu")
        if (nu != null) {
            profiles +=
                TerminalProfile(
                    id = "nushell",
                    displayName = "Nushell",
                    command = listOf(nu.toString()),
                )
        }

        val sh = unixShellExecutable("sh")
        if (sh != null) {
            profiles +=
                TerminalProfile(
                    id = "sh",
                    displayName = "Sh",
                    command = listOf(sh.toString()),
                )
        }

        if (profiles.isEmpty()) {
            profiles +=
                TerminalProfile(
                    id = "default-shell",
                    displayName = "Default Shell",
                    command = PtyOptions.defaultCommand(),
                )
        }
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

    private fun wslExecutable(): Path? {
        val wsl = executableOnPath("wsl.exe")
        if (wsl != null) return wsl

        val systemRoot = environment["SystemRoot"]
        if (!systemRoot.isNullOrBlank()) {
            val candidate = Path.of(systemRoot, "System32", "wsl.exe")
            if (executableExists(candidate)) return candidate
        }
        return null
    }

    private fun pwshExecutable(): Path? =
        executableOnPath("pwsh.exe")
            ?: firstExistingPath(
                programFilesPath("PowerShell", "7", "pwsh.exe"),
                programFilesPath("PowerShell", "6", "pwsh.exe"),
                programFilesX86Path("PowerShell", "7", "pwsh.exe"),
                programFilesX86Path("PowerShell", "6", "pwsh.exe"),
            )

    private fun unixShellExecutable(name: String): Path? =
        executableOnPath(name)
            ?: firstExistingPath(
                Path.of("/usr/bin", name),
                Path.of("/bin", name),
                Path.of("/usr/local/bin", name),
                Path.of("/opt/homebrew/bin", name),
            )

    private fun gitBashCommand(): List<String>? {
        val launcher = gitBashExecutable() ?: return null
        val gitRoot = launcher.parent
        if (gitRoot != null) {
            val bash = gitRoot.resolve(Path.of("bin", "bash.exe"))
            if (executableExists(bash)) {
                return listOf(bash.toString(), "--login", "-i")
            }
        }
        return listOf(launcher.toString())
    }

    private fun gitBashExecutable(): Path? =
        executableOnPath("git-bash.exe")
            ?: firstExistingPath(
                programFilesPath("Git", "git-bash.exe"),
                programFilesX86Path("Git", "git-bash.exe"),
                localAppDataPath("Programs", "Git", "git-bash.exe"),
            )

    private fun firstExistingPath(vararg candidates: Path?): Path? {
        for (candidate in candidates) {
            if (candidate != null && executableExists(candidate)) return candidate
        }
        return null
    }

    private fun programFilesPath(vararg segments: String): Path? = environmentPath("ProgramFiles", *segments)

    private fun programFilesX86Path(vararg segments: String): Path? = environmentPath("ProgramFiles(x86)", *segments)

    private fun localAppDataPath(vararg segments: String): Path? = environmentPath("LocalAppData", *segments)

    private fun environmentPath(
        key: String,
        vararg segments: String,
    ): Path? {
        val root = environment[key]
        if (root.isNullOrBlank()) return null
        return Path.of(root, *segments)
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
        private const val WINDOWS_PROFILE_CAPACITY = 5
        private const val CONFIGURED_SHELL_ID = "configured-shell"
    }
}
