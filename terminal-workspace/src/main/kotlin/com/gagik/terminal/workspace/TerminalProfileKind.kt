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
import java.util.Locale

/**
 * Stable presentation category for a terminal launch profile.
 *
 * This host-neutral value is computed when a profile is discovered or created.
 * UI layers can use it for icons and menus without parsing live terminal titles
 * or process output.
 */
enum class TerminalProfileKind {
    /** Windows PowerShell or cross-platform PowerShell Core. */
    POWERSHELL,

    /** Windows command prompt hosted by `cmd.exe`. */
    COMMAND_PROMPT,

    /** Git for Windows Bash or MSYS/MINGW Bash environments. */
    GIT_BASH,

    /** Ubuntu launched directly or through WSL. */
    UBUNTU,

    /** GNU Bash. */
    BASH,

    /** Z Shell. */
    ZSH,

    /** Friendly Interactive Shell. */
    FISH,

    /** Nushell. */
    NUSHELL,

    /** Windows Subsystem for Linux (WSL) general command. */
    WSL,

    /** Generic POSIX-family interactive shells such as sh, bash, zsh, or fish. */
    UNIX_SHELL,

    /** Unknown or custom terminal command. */
    DEFAULT,
    ;

    companion object {
        /**
         * Classifies a profile from stable launch metadata.
         *
         * @param id stable profile identifier.
         * @param displayName user-facing profile name.
         * @param command command and arguments passed to the local PTY child process.
         * @return stable presentation category for the profile.
         */
        fun classify(
            id: String,
            displayName: String,
            command: List<String>,
        ): TerminalProfileKind {
            val first = command.firstOrNull() ?: return DEFAULT
            val executable = executableName(first)
            val fullCommand = first.lowercase(Locale.ROOT).replace('\\', '/')
            val normalizedId = id.lowercase(Locale.ROOT)
            val normalizedName = displayName.lowercase(Locale.ROOT)

            if (isPowerShell(executable, normalizedId, normalizedName)) return POWERSHELL
            if (isCommandPrompt(executable, normalizedId, normalizedName)) return COMMAND_PROMPT
            if (isUbuntu(executable, normalizedId, normalizedName, command)) return UBUNTU
            if (isGitBash(executable, fullCommand, normalizedId, normalizedName)) return GIT_BASH
            if (isWsl(executable, normalizedId, normalizedName)) return WSL
            if (isBash(executable, normalizedId, normalizedName)) return BASH
            if (isZsh(executable, normalizedId, normalizedName)) return ZSH
            if (isFish(executable, normalizedId, normalizedName)) return FISH
            if (isNushell(executable, normalizedId, normalizedName)) return NUSHELL
            if (isUnixShell(executable)) return UNIX_SHELL
            return DEFAULT
        }

        private fun isPowerShell(
            executable: String,
            id: String,
            displayName: String,
        ): Boolean =
            executable == "powershell.exe" ||
                executable == "pwsh.exe" ||
                executable == "pwsh" ||
                id.contains("powershell") ||
                displayName.contains("powershell")

        private fun isCommandPrompt(
            executable: String,
            id: String,
            displayName: String,
        ): Boolean =
            executable == "cmd.exe" ||
                executable == "cmd" ||
                id == "cmd" ||
                displayName.contains("command prompt")

        private fun isUbuntu(
            executable: String,
            id: String,
            displayName: String,
            command: List<String>,
        ): Boolean =
            executable == "ubuntu.exe" ||
                executable == "ubuntu" ||
                id.contains("ubuntu") ||
                displayName.contains("ubuntu") ||
                (
                    executable == "wsl.exe" &&
                        command.any { it.equals("ubuntu", ignoreCase = true) }
                )

        private fun isGitBash(
            executable: String,
            fullCommand: String,
            id: String,
            displayName: String,
        ): Boolean =
            executable == "git-bash.exe" ||
                id.contains("git-bash") ||
                displayName.contains("git bash") ||
                fullCommand.contains("/git/") &&
                executable in gitBashExecutables ||
                fullCommand.contains("/mingw") ||
                fullCommand.contains("/msys")

        private fun isWsl(
            executable: String,
            id: String,
            displayName: String,
        ): Boolean =
            executable == "wsl.exe" ||
                executable == "wsl" ||
                id == "wsl" ||
                displayName.contains("wsl")

        private fun isBash(
            executable: String,
            id: String,
            displayName: String,
        ): Boolean =
            executable == "bash.exe" ||
                executable == "bash" ||
                id == "bash" ||
                displayName.contains("bash")

        private fun isZsh(
            executable: String,
            id: String,
            displayName: String,
        ): Boolean =
            executable == "zsh.exe" ||
                executable == "zsh" ||
                id == "zsh" ||
                displayName.contains("zsh")

        private fun isFish(
            executable: String,
            id: String,
            displayName: String,
        ): Boolean =
            executable == "fish.exe" ||
                executable == "fish" ||
                id == "fish" ||
                displayName.contains("fish")

        private fun isNushell(
            executable: String,
            id: String,
            displayName: String,
        ): Boolean =
            executable == "nu.exe" ||
                executable == "nu" ||
                id == "nushell" ||
                id == "nu" ||
                displayName.contains("nushell") ||
                displayName.contains("nu shell")

        private fun isUnixShell(executable: String): Boolean = executable in unixShellExecutables

        private fun executableName(command: String): String {
            val fileName =
                try {
                    Path.of(command).fileName?.toString()
                } catch (_: RuntimeException) {
                    command.substringAfterLast('\\').substringAfterLast('/')
                }
            return (fileName ?: command).lowercase(Locale.ROOT)
        }

        private val gitBashExecutables = setOf("bash.exe", "sh.exe")
        private val unixShellExecutables = setOf("sh", "bash", "zsh", "fish", "dash", "ksh", "ash")
    }
}
