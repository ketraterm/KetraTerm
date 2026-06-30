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

import io.github.ketraterm.workspace.config.TerminalWorkspaceConfigManager
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.*

/**
 * Applies host-neutral shell integration launch hooks to terminal profiles.
 *
 * The bootstrapper modifies only launch profiles. It does not parse terminal
 * output, mutate core state, or own PTY lifecycle.
 */
internal object TerminalShellIntegrationBootstrap {
    /**
     * Returns [profile] with shell integration startup hooks applied when
     * supported by the shell family and [enabled] is true.
     *
     * @param scriptDirectory persistent directory used for generated shell
     *   startup wrappers required by shells without an init-command argument.
     */
    fun apply(
        profile: TerminalProfile,
        enabled: Boolean,
        scriptDirectory: Path = defaultScriptDirectory(),
    ): TerminalProfile {
        if (!enabled) return profile

        val integrated =
            when (profile.kind) {
                TerminalProfileKind.POWERSHELL -> withPowerShellIntegration(profile)
                TerminalProfileKind.BASH,
                TerminalProfileKind.GIT_BASH,
                -> withBashIntegration(profile)
                TerminalProfileKind.ZSH -> withZshIntegration(profile, scriptDirectory)
                TerminalProfileKind.FISH -> withFishIntegration(profile)
                TerminalProfileKind.WSL,
                TerminalProfileKind.UBUNTU,
                -> withExplicitWslShellIntegration(profile, scriptDirectory)
                else -> profile
            }

        if (integrated === profile) return profile

        val configPath = TerminalWorkspaceConfigManager.getDefaultPath()
        val binDir = scriptDirectory.resolve("bin")
        writeWrapperScripts(binDir)

        val baseEnv = integrated.environment.toMutableMap()
        baseEnv["KetraTerm_VERSION"] = getAppVersion()
        baseEnv["KetraTerm_CONFIG_PATH"] = configPath.toAbsolutePath().toString()
        baseEnv["KetraTerm_OS"] = System.getProperty("os.name") + " (" + System.getProperty("os.arch") + ")"
        baseEnv["KetraTerm_JVM"] = System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")"

        val systemPathKey = System.getenv().keys.firstOrNull { it.equals("PATH", ignoreCase = true) } ?: "PATH"
        val pathKey = baseEnv.keys.firstOrNull { it.equals("PATH", ignoreCase = true) } ?: systemPathKey
        val existingPath = baseEnv[pathKey] ?: System.getenv(pathKey) ?: ""
        val separator = if (System.getProperty("os.name").lowercase(Locale.ROOT).contains("win")) ";" else ":"
        val newPath =
            if (existingPath.isNotEmpty()) {
                "${binDir.toAbsolutePath()}$separator$existingPath"
            } else {
                binDir.toAbsolutePath().toString()
            }
        val keysToRemove = baseEnv.keys.filter { it.equals("PATH", ignoreCase = true) && it != pathKey }
        for (k in keysToRemove) {
            baseEnv.remove(k)
        }
        baseEnv[pathKey] = newPath

        return integrated.copy(environment = baseEnv)
    }

    private fun withExplicitWslShellIntegration(
        profile: TerminalProfile,
        scriptDirectory: Path,
    ): TerminalProfile {
        val shellIndex = explicitWslShellIndex(profile.command)
        if (shellIndex < 0) return profile
        val shellCommand = profile.command.subList(shellIndex, profile.command.size)
        val shellName = executableName(shellCommand[0])
        val shellProfile = profile.copy(command = shellCommand)
        val integrated =
            when (shellName) {
                "bash", "bash.exe" -> withBashIntegration(shellProfile)
                "zsh", "zsh.exe" -> withZshIntegration(shellProfile, scriptDirectory)
                "fish", "fish.exe" -> withFishIntegration(shellProfile)
                else -> return profile
            }
        if (integrated === shellProfile) return profile

        var environment = integrated.environment
        environment =
            when (shellName) {
                "bash", "bash.exe" -> environment.withWslEnvironmentExport("PROMPT_COMMAND/u")
                "zsh", "zsh.exe" ->
                    environment
                        .withWslEnvironmentExport("KetraTerm_ORIGINAL_ZDOTDIR/up")
                        .withWslEnvironmentExport("ZDOTDIR/up")
                else -> environment
            }
        return profile.copy(
            command = profile.command.subList(0, shellIndex) + integrated.command,
            environment = environment,
        )
    }

    private fun explicitWslShellIndex(command: List<String>): Int {
        var index = 1
        while (index < command.size) {
            val argument = command[index]
            if (argument == "--" || argument == "-e" || argument == "--exec" || argument == "run") {
                return (index + 1).takeIf { it < command.size } ?: -1
            }
            index++
        }
        return -1
    }

    private fun Map<String, String>.withWslEnvironmentExport(export: String): Map<String, String> {
        val variableName = export.substringBefore('/')
        val current = this["WSLENV"].orEmpty()
        val entries = current.split(':').filter(String::isNotEmpty)
        if (entries.any { it.substringBefore('/') == variableName }) return this
        return this + ("WSLENV" to (entries + export).joinToString(":"))
    }

    private fun executableName(command: String): String = command.substringAfterLast('\\').substringAfterLast('/').lowercase(Locale.ROOT)

    private fun withPowerShellIntegration(profile: TerminalProfile): TerminalProfile {
        val command = profile.command
        val encodedScript = encodedPowerShellScript()
        if (command.size <= 1) {
            return profile.copy(command = command + listOf("-NoExit", "-EncodedCommand", encodedScript))
        }
        if (hasExplicitPowerShellEntryPoint(command)) return profile

        val next = ArrayList<String>(command.size + 3)
        next += command
        if (!hasPowerShellArgument(command, "-NoExit")) {
            next += "-NoExit"
        }
        next += "-EncodedCommand"
        next += encodedScript
        return profile.copy(command = next)
    }

    private fun withBashIntegration(profile: TerminalProfile): TerminalProfile {
        val command = profile.command
        if (hasExplicitBashEntryPoint(command)) return profile

        val promptCommand = profile.environment["PROMPT_COMMAND"]
        val environment =
            profile.environment +
                mapOf(
                    "PROMPT_COMMAND" to joinedShellCommands(BASH_PROMPT_COMMAND, promptCommand),
                )
        return profile.copy(environment = environment)
    }

    private fun withZshIntegration(
        profile: TerminalProfile,
        scriptDirectory: Path,
    ): TerminalProfile {
        val command = profile.command
        if (hasExplicitZshEntryPoint(command)) return profile

        val zshDirectory = scriptDirectory.resolve("zsh")
        try {
            writeZshBootstrapFiles(zshDirectory)
        } catch (_: IOException) {
            return profile
        }

        val originalZdotdir = profile.environment["ZDOTDIR"].orEmpty()
        val environment =
            profile.environment +
                mapOf(
                    "KetraTerm_ORIGINAL_ZDOTDIR" to originalZdotdir,
                    "ZDOTDIR" to zshDirectory.toString(),
                )
        return profile.copy(environment = environment)
    }

    private fun withFishIntegration(profile: TerminalProfile): TerminalProfile {
        val command = profile.command
        if (hasExplicitFishEntryPoint(command)) return profile

        val next = ArrayList<String>(command.size + 2)
        next += command
        next += "--init-command"
        next += FISH_INIT_COMMAND
        return profile.copy(command = next)
    }

    private fun encodedPowerShellScript(): String = Base64.getEncoder().encodeToString(POWERSHELL_SCRIPT.toByteArray(Charsets.UTF_16LE))

    private fun hasExplicitPowerShellEntryPoint(command: List<String>): Boolean {
        var index = 1
        while (index < command.size) {
            if (powerShellArgumentName(command[index]) in explicitEntryPointArguments) return true
            index++
        }
        return false
    }

    private fun hasPowerShellArgument(
        command: List<String>,
        expected: String,
    ): Boolean {
        val normalizedExpected = normalizedPowerShellArgument(expected)
        var index = 1
        while (index < command.size) {
            if (powerShellArgumentName(command[index]) == normalizedExpected) return true
            index++
        }
        return false
    }

    private fun normalizedPowerShellArgument(argument: String): String =
        argument
            .trim()
            .lowercase(Locale.ROOT)
            .let { value -> if (value.startsWith('/')) "-${value.substring(1)}" else value }

    private fun powerShellArgumentName(argument: String): String {
        val normalized = normalizedPowerShellArgument(argument)
        val colon = normalized.indexOf(':')
        val equals = normalized.indexOf('=')
        val separator =
            when {
                colon < 0 -> equals
                equals < 0 -> colon
                else -> minOf(colon, equals)
            }
        return if (separator < 0) normalized else normalized.substring(0, separator)
    }

    private fun hasExplicitBashEntryPoint(command: List<String>): Boolean {
        var index = 1
        while (index < command.size) {
            val argument = command[index]
            if (argument == "-c" || argument == "--command" || isShortOptionCluster(argument, 'c')) return true
            if (!argument.startsWith("-")) return true
            index++
        }
        return false
    }

    private fun hasExplicitZshEntryPoint(command: List<String>): Boolean {
        var index = 1
        while (index < command.size) {
            val argument = command[index]
            if (argument == "-c" || argument == "-s" || isShortOptionCluster(argument, 'c') || isShortOptionCluster(argument, 's')) {
                return true
            }
            if (argument == "-f" || argument == "--no-rcs" || isShortOptionCluster(argument, 'f')) return true
            if (!argument.startsWith("-")) return true
            index++
        }
        return false
    }

    private fun hasExplicitFishEntryPoint(command: List<String>): Boolean {
        var index = 1
        while (index < command.size) {
            val argument = command[index]
            if (argument == "-c" || argument.startsWith("--command") || isShortOptionCluster(argument, 'c')) return true
            if (!argument.startsWith("-")) return true
            index += if (argument == "-C" || argument == "--init-command") 2 else 1
        }
        return false
    }

    private fun isShortOptionCluster(
        argument: String,
        option: Char,
    ): Boolean =
        argument.length > 2 &&
            argument[0] == '-' &&
            argument[1] != '-' &&
            argument.indexOf(option, startIndex = 1) >= 0

    private fun joinedShellCommands(
        first: String,
        second: String?,
    ): String =
        if (second.isNullOrBlank()) {
            first
        } else {
            "$first; $second"
        }

    private fun writeZshBootstrapFiles(directory: Path) {
        Files.createDirectories(directory)
        writeIfChanged(directory.resolve(".zshenv"), zshSourceOriginalStartupFile(".zshenv"))
        writeIfChanged(directory.resolve(".zprofile"), zshSourceOriginalStartupFile(".zprofile"))
        writeIfChanged(
            directory.resolve(".zshrc"),
            joinedShellCommands(
                zshSourceOriginalStartupFile(".zshrc"),
                ZSH_INTEGRATION_SCRIPT,
            ),
        )
        writeIfChanged(directory.resolve(".zlogin"), zshSourceOriginalStartupFile(".zlogin"))
        writeIfChanged(directory.resolve(".zlogout"), zshSourceOriginalStartupFile(".zlogout"))
    }

    private fun zshSourceOriginalStartupFile(fileName: String): String =
        """
        __ketraterm_original_zdotdir=${'$'}{KetraTerm_ORIGINAL_ZDOTDIR:-${'$'}HOME}
        if [[ -n "${'$'}__ketraterm_original_zdotdir" && "${'$'}__ketraterm_original_zdotdir" != "${'$'}ZDOTDIR" && -r "${'$'}__ketraterm_original_zdotdir/$fileName" ]]; then
            source "${'$'}__ketraterm_original_zdotdir/$fileName"
        fi
        unset __ketraterm_original_zdotdir
        """.trimIndent()

    private fun writeIfChanged(
        path: Path,
        content: String,
    ) {
        if (Files.exists(path) && Files.readString(path) == content) return
        Files.writeString(path, content)
    }

    private fun defaultScriptDirectory(): Path =
        Path.of(
            System.getProperty("java.io.tmpdir"),
            "ketraterm-shell-integration",
            "v1",
        )

    private val explicitEntryPointArguments =
        setOf(
            "-command",
            "-c",
            "-encodedcommand",
            "-enc",
            "-file",
            "-f",
        )

    private val BASH_PROMPT_COMMAND =
        """
        if [[ -z ${'$'}{__KetraTerm_BASH_SHELL_INTEGRATION_INSTALLED:-} ]]; then
            __KetraTerm_BASH_SHELL_INTEGRATION_INSTALLED=1
            __ketraterm_command_started=0
            __ketraterm_in_prompt=0
            __ketraterm_osc133() { printf '\033]133;%s\a' "${'$'}1"; }
            __ketraterm_hostname=${'$'}{HOSTNAME:-${'$'}(hostname 2>/dev/null)}
            [[ -n "${'$'}__ketraterm_hostname" ]] || __ketraterm_hostname=localhost
            __ketraterm_encode_uri_path() {
                local LC_ALL=C __ketraterm_value=${'$'}1 __ketraterm_encoded= __ketraterm_char __ketraterm_hex __ketraterm_index
                for (( __ketraterm_index=0; __ketraterm_index<${'$'}{${'#'}__ketraterm_value}; __ketraterm_index++ )); do
                    __ketraterm_char=${'$'}{__ketraterm_value:__ketraterm_index:1}
                    case "${'$'}__ketraterm_char" in
                        [a-zA-Z0-9/._~-]) __ketraterm_encoded+=${'$'}__ketraterm_char ;;
                        *) printf -v __ketraterm_hex '%%%02X' "'${'$'}__ketraterm_char"; __ketraterm_encoded+=${'$'}__ketraterm_hex ;;
                    esac
                done
                REPLY=${'$'}__ketraterm_encoded
            }
            __ketraterm_osc7() {
                __ketraterm_encode_uri_path "${'$'}PWD"
                printf '\033]7;file://%s%s\a' "${'$'}__ketraterm_hostname" "${'$'}REPLY"
            }
            __ketraterm_install_prompt_end() {
                case "${'$'}PS1" in
                    *${'$'}'\033]133;B\a'*) ;;
                    *) PS1="${'$'}PS1"${'$'}'\[\033]133;B\a\]' ;;
                esac
            }
            __ketraterm_prompt_command() {
                local __ketraterm_status=${'$'}?
                __ketraterm_in_prompt=1
                if [[ ${'$'}{__ketraterm_command_started:-0} == 1 ]]; then
                    __ketraterm_osc133 "D;${'$'}__ketraterm_status"
                    __ketraterm_command_started=0
                fi
                __ketraterm_install_prompt_end
                __ketraterm_osc7
                __ketraterm_osc133 A
                __ketraterm_in_prompt=0
                return ${'$'}__ketraterm_status
            }
            __ketraterm_preexec() {
                if [[ ${'$'}{__ketraterm_in_prompt:-0} == 1 || ${'$'}{__ketraterm_command_started:-0} == 1 ]]; then
                    return
                fi
                case "${'$'}BASH_COMMAND" in
                    __ketraterm_*|*__ketraterm_prompt_command*) return ;;
                esac
                __ketraterm_command_started=1
                __ketraterm_osc133 C
            }
            trap '__ketraterm_preexec' DEBUG
        fi
        __ketraterm_prompt_command
        """.trimIndent()

    internal val FISH_INIT_COMMAND =
        """
        if not set -q __KetraTerm_FISH_SHELL_INTEGRATION_INSTALLED
            set -g __KetraTerm_FISH_SHELL_INTEGRATION_INSTALLED 1
            set -g __ketraterm_fish_command_started 0
            function __ketraterm_osc133 --argument-names marker
                printf '\e]133;%s\a' "${'$'}marker"
            end
            set -g __ketraterm_fish_hostname (hostname 2>/dev/null)
            if test -z "${'$'}__ketraterm_fish_hostname"
                set -g __ketraterm_fish_hostname localhost
            end
            function __ketraterm_osc7
                set -l encoded_path (string escape --style=url -- "${'$'}PWD" | string replace -a '%2F' '/' | string replace -a '%2f' '/')
                printf '\e]7;file://%s%s\a' "${'$'}__ketraterm_fish_hostname" "${'$'}encoded_path"
            end
            function __ketraterm_fish_prompt --on-event fish_prompt
                set -l code ${'$'}status
                if test "${'$'}__ketraterm_fish_command_started" = 1
                    __ketraterm_osc133 "D;${'$'}code"
                    set -g __ketraterm_fish_command_started 0
                end
                __ketraterm_osc7
                __ketraterm_osc133 A
            end
            function __ketraterm_fish_preexec --on-event fish_preexec
                set -g __ketraterm_fish_command_started 1
                __ketraterm_osc133 C
            end
            function __ketraterm_fish_postexec --on-event fish_postexec
                set -l code ${'$'}status
                if test "${'$'}__ketraterm_fish_command_started" = 1
                    __ketraterm_osc133 "D;${'$'}code"
                    set -g __ketraterm_fish_command_started 0
                end
            end
            set -l __ketraterm_prompt_end (printf '\e]133;B\a')
            if not string match -q "*${'$'}__ketraterm_prompt_end*" -- "${'$'}SHELL_PROMPT_SUFFIX"
                set -gx SHELL_PROMPT_SUFFIX "${'$'}SHELL_PROMPT_SUFFIX${'$'}__ketraterm_prompt_end"
            end
        end
        """.trimIndent()

    internal val ZSH_INTEGRATION_SCRIPT =
        """
        if [[ -z ${'$'}{__KetraTerm_ZSH_SHELL_INTEGRATION_INSTALLED:-} ]]; then
            typeset -g __KetraTerm_ZSH_SHELL_INTEGRATION_INSTALLED=1
            typeset -g __ketraterm_zsh_command_started=0
            function __ketraterm_osc133() {
                printf '\033]133;%s\a' "${'$'}1"
            }
            typeset -g __ketraterm_zsh_hostname=${'$'}{HOST:-${'$'}(hostname 2>/dev/null)}
            [[ -n "${'$'}__ketraterm_zsh_hostname" ]] || __ketraterm_zsh_hostname=localhost
            function __ketraterm_zsh_encode_uri_path() {
                local LC_ALL=C value=${'$'}1 encoded='' char hex
                local -i index
                for (( index=1; index<=${'$'}{${'#'}value}; index++ )); do
                    char=${'$'}{value[index]}
                    case "${'$'}char" in
                        [a-zA-Z0-9/._~-]) encoded+=${'$'}char ;;
                        *) printf -v hex '%%%02X' "'${'$'}char"; encoded+=${'$'}hex ;;
                    esac
                done
                REPLY=${'$'}encoded
            }
            function __ketraterm_zsh_osc7() {
                __ketraterm_zsh_encode_uri_path "${'$'}PWD"
                printf '\033]7;file://%s%s\a' "${'$'}__ketraterm_zsh_hostname" "${'$'}REPLY"
            }
            function __ketraterm_zsh_preexec() {
                __ketraterm_zsh_command_started=1
                __ketraterm_osc133 C
            }
            function __ketraterm_zsh_precmd() {
                local code=${'$'}?
                if [[ ${'$'}__ketraterm_zsh_command_started == 1 ]]; then
                    __ketraterm_osc133 "D;${'$'}code"
                    __ketraterm_zsh_command_started=0
                fi
                __ketraterm_zsh_osc7
                __ketraterm_osc133 A
                return ${'$'}code
            }
            autoload -Uz add-zsh-hook
            add-zsh-hook preexec __ketraterm_zsh_preexec
            add-zsh-hook precmd __ketraterm_zsh_precmd
            case "${'$'}PROMPT" in
                *${'$'}'\033]133;B\a'*) ;;
                *) PROMPT="${'$'}PROMPT"${'$'}'%{\033]133;B\a%}' ;;
            esac
        fi
        """.trimIndent()

    private val POWERSHELL_SCRIPT =
        """
        if (-not ${'$'}global:__KetraTermShellIntegrationInstalled) {
            ${'$'}global:__KetraTermShellIntegrationInstalled = ${'$'}true
            ${'$'}global:__KetraTermCommandStarted = ${'$'}false
            ${'$'}global:__KetraTermLastExitCodeBeforeCommand = ${'$'}global:LASTEXITCODE
            function global:__KetraTermOsc133([string] ${'$'}Marker) {
                [Console]::Write(([string][char]27) + ']133;' + ${'$'}Marker + ([string][char]7))
            }
            function global:__KetraTermOsc7 {
                try {
                    ${'$'}location = Get-Location
                    if (${'$'}location.Provider.Name -ne 'FileSystem') {
                        return
                    }
                    ${'$'}builder = [System.UriBuilder]::new('file', [Environment]::MachineName)
                    ${'$'}builder.Path = ${'$'}location.Path
                    [Console]::Write(([string][char]27) + ']7;' + ${'$'}builder.Uri.AbsoluteUri + ([string][char]7))
                } catch {
                }
            }
            ${'$'}global:__KetraTermOriginalPrompt = (Get-Item Function:\prompt).ScriptBlock
            function global:prompt {
                ${'$'}success = ${'$'}?
                ${'$'}nativeExitCode = ${'$'}global:LASTEXITCODE
                if (${'$'}global:__KetraTermCommandStarted) {
                    if (${'$'}nativeExitCode -is [int] -and ${'$'}nativeExitCode -ne ${'$'}global:__KetraTermLastExitCodeBeforeCommand) {
                        ${'$'}exitCode = ${'$'}nativeExitCode
                    } elseif (${'$'}success) {
                        ${'$'}exitCode = 0
                    } else {
                        ${'$'}exitCode = 1
                    }
                    global:__KetraTermOsc133 ('D;' + ${'$'}exitCode)
                    ${'$'}global:__KetraTermCommandStarted = ${'$'}false
                }
                global:__KetraTermOsc7
                global:__KetraTermOsc133 'A'
                try {
                    ${'$'}promptText = & ${'$'}global:__KetraTermOriginalPrompt
                } finally {
                    if (${'$'}nativeExitCode -is [int]) {
                        ${'$'}global:LASTEXITCODE = ${'$'}nativeExitCode
                    }
                }
                ${'$'}promptText + ([string][char]27) + ']133;B' + ([string][char]7)
            }
            ${'$'}command = Get-Command PSConsoleHostReadLine -CommandType Function -ErrorAction SilentlyContinue
            if (${'$'}command -ne ${'$'}null) {
                ${'$'}global:__KetraTermOriginalPSConsoleHostReadLine = ${'$'}command.ScriptBlock
                function global:PSConsoleHostReadLine {
                    ${'$'}line = & ${'$'}global:__KetraTermOriginalPSConsoleHostReadLine
                    if (${'$'}line -ne ${'$'}null -and ${'$'}line.Trim().Length -gt 0) {
                        ${'$'}global:__KetraTermLastExitCodeBeforeCommand = ${'$'}global:LASTEXITCODE
                        ${'$'}global:__KetraTermCommandStarted = ${'$'}true
                        global:__KetraTermOsc133 'C'
                    }
                    ${'$'}line
                }
            }
        }
        """.trimIndent()

    private fun getAppVersion(): String =
        try {
            val properties = Properties()
            val inputStream: InputStream? =
                TerminalShellIntegrationBootstrap::class.java.classLoader
                    .getResourceAsStream("io/github/ketraterm/app/version.properties")
            if (inputStream != null) {
                properties.load(inputStream)
                properties.getProperty("version") ?: "0.1.0"
            } else {
                "0.1.0"
            }
        } catch (_: Exception) {
            "0.1.0"
        }

    private fun writeWrapperScripts(directory: Path) {
        try {
            Files.createDirectories(directory)
            val posixPath = directory.resolve("ketra")
            writeIfChanged(posixPath, KETRA_POSIX_SCRIPT)
            try {
                val perms = Files.getPosixFilePermissions(posixPath)
                val newPerms =
                    perms + PosixFilePermission.OWNER_EXECUTE + PosixFilePermission.GROUP_EXECUTE + PosixFilePermission.OTHERS_EXECUTE
                Files.setPosixFilePermissions(posixPath, newPerms)
            } catch (_: UnsupportedOperationException) {
                // Non-POSIX filesystem (e.g. Windows)
            } catch (_: Exception) {
                // Ignored
            }

            val batchPath = directory.resolve("ketra.bat")
            writeIfChanged(batchPath, KETRA_BATCH_SCRIPT)
        } catch (_: IOException) {
            // Ignored
        }
    }

    private val KETRA_POSIX_SCRIPT =
        """
        #!/bin/sh
        case "${'$'}1" in
            version)
                echo "KetraTerm version ${'$'}{KetraTerm_VERSION:-unknown}"
                ;;
            config)
                if [ -n "${'$'}KetraTerm_CONFIG_PATH" ]; then
                    if [ -n "${'$'}EDITOR" ]; then
                        "${'$'}EDITOR" "${'$'}KetraTerm_CONFIG_PATH"
                    elif command -v nano >/dev/null 2>&1; then
                        nano "${'$'}KetraTerm_CONFIG_PATH"
                    elif command -v vim >/dev/null 2>&1; then
                        vim "${'$'}KetraTerm_CONFIG_PATH"
                    else
                        cat "${'$'}KetraTerm_CONFIG_PATH"
                    fi
                else
                    echo "KetraTerm_CONFIG_PATH is not set."
                    exit 1
                fi
                ;;
            info)
                echo "KetraTerm System Information:"
                echo "  Version:       ${'$'}{KetraTerm_VERSION:-unknown}"
                echo "  Config Path:   ${'$'}{KetraTerm_CONFIG_PATH:-unknown}"
                echo "  OS:            ${'$'}{KetraTerm_OS:-unknown}"
                echo "  JVM:           ${'$'}{KetraTerm_JVM:-unknown}"
                echo ""
                echo "Environment Variables:"
                echo "  KetraTerm_VERSION       Active version of the terminal application"
                echo "  KetraTerm_CONFIG_PATH   Path to workspace settings file (config.toml)"
                echo "  KetraTerm_OS            Current OS and architecture details"
                echo "  KetraTerm_JVM           Java runtime version and vendor details"
                echo ""
                echo "Useful Commands:"
                echo "  cat \"${'$'}{KetraTerm_CONFIG_PATH}\"       - Display the configuration file"
                echo "  nano \"${'$'}{KetraTerm_CONFIG_PATH}\"      - Edit the configuration file in nano"
                ;;
            help|--help|-h|"")
                echo "KetraTerm Companion CLI CLI Tool"
                echo ""
                echo "Usage:"
                echo "  ketra <command> [options]"
                echo ""
                echo "Commands:"
                echo "  version           Display active KetraTerm version"
                echo "  config            Open config.toml in your default editor"
                echo "  info              Print system diagnostic and path information"
                echo "  help              Display this help instructions"
                ;;
            *)
                echo "Usage: ketra [version | config | info | help]"
                exit 1
                ;;
        esac
        """.trimIndent()

    private val KETRA_BATCH_SCRIPT =
        """
        @echo off
        if "%1"=="version" goto run_version
        if "%1"=="config" goto run_config
        if "%1"=="info" goto run_info
        if "%1"=="help" goto run_help
        if "%1"=="--help" goto run_help
        if "%1"=="-h" goto run_help
        if "%1"=="" goto run_help

        echo Usage: ketra [version ^| config ^| info ^| help]
        exit /b 1

        :run_version
        echo KetraTerm version %KetraTerm_VERSION%
        goto end

        :run_config
        if "%KetraTerm_CONFIG_PATH%"=="" (
            echo KetraTerm_CONFIG_PATH is not set.
            exit /b 1
        )
        if not "%EDITOR%"=="" (
            %EDITOR% "%KetraTerm_CONFIG_PATH%"
        ) else (
            notepad "%KetraTerm_CONFIG_PATH%"
        )
        goto end

        :run_info
        echo KetraTerm System Information:
        echo   Version:       %KetraTerm_VERSION%
        echo   Config Path:   %KetraTerm_CONFIG_PATH%
        echo   OS:            %KetraTerm_OS%
        echo   JVM:           %KetraTerm_JVM%
        echo.
        echo Environment Variables:
        echo   KetraTerm_VERSION       Active version of the terminal application
        echo   KetraTerm_CONFIG_PATH   Path to workspace settings file (config.toml)
        echo   KetraTerm_OS            Current OS and architecture details
        echo   KetraTerm_JVM           Java runtime version and vendor details
        echo.
        echo Useful Commands:
        echo   notepad "%%KetraTerm_CONFIG_PATH%%"      - Edit configuration in Notepad
        goto end

        :run_help
        echo KetraTerm Companion CLI CLI Tool
        echo.
        echo Usage:
        echo   ketra ^<command^> [options]
        echo.
        echo Commands:
        echo   version           Display active KetraTerm version
        echo   config            Open config.toml in your default editor
        echo   info              Print system diagnostic and path information
        echo   help              Display this help instructions
        goto end

        :end
        """.trimIndent()
}
