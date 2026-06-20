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

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.ArrayList
import java.util.Base64
import java.util.Locale

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
        return when (profile.kind) {
            TerminalProfileKind.POWERSHELL -> withPowerShellIntegration(profile)
            TerminalProfileKind.BASH,
            TerminalProfileKind.GIT_BASH,
            -> withBashIntegration(profile)
            TerminalProfileKind.ZSH -> withZshIntegration(profile, scriptDirectory)
            TerminalProfileKind.FISH -> withFishIntegration(profile)
            else -> profile
        }
    }

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
                    "JVTERM_ORIGINAL_ZDOTDIR" to originalZdotdir,
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
        __jvterm_original_zdotdir=${'$'}{JVTERM_ORIGINAL_ZDOTDIR:-${'$'}HOME}
        if [[ -n "${'$'}__jvterm_original_zdotdir" && "${'$'}__jvterm_original_zdotdir" != "${'$'}ZDOTDIR" && -r "${'$'}__jvterm_original_zdotdir/$fileName" ]]; then
            source "${'$'}__jvterm_original_zdotdir/$fileName"
        fi
        unset __jvterm_original_zdotdir
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
            "jvterm-shell-integration",
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
        if [[ -z ${'$'}{__JVTERM_BASH_SHELL_INTEGRATION_INSTALLED:-} ]]; then
            __JVTERM_BASH_SHELL_INTEGRATION_INSTALLED=1
            __jvterm_command_started=0
            __jvterm_in_prompt=0
            __jvterm_osc133() { printf '\033]133;%s\a' "${'$'}1"; }
            __jvterm_hostname=${'$'}{HOSTNAME:-${'$'}(hostname 2>/dev/null)}
            [[ -n "${'$'}__jvterm_hostname" ]] || __jvterm_hostname=localhost
            __jvterm_encode_uri_path() {
                local LC_ALL=C __jvterm_value=${'$'}1 __jvterm_encoded= __jvterm_char __jvterm_hex __jvterm_index
                for (( __jvterm_index=0; __jvterm_index<${'$'}{${'#'}__jvterm_value}; __jvterm_index++ )); do
                    __jvterm_char=${'$'}{__jvterm_value:__jvterm_index:1}
                    case "${'$'}__jvterm_char" in
                        [a-zA-Z0-9/._~-]) __jvterm_encoded+=${'$'}__jvterm_char ;;
                        *) printf -v __jvterm_hex '%%%02X' "'${'$'}__jvterm_char"; __jvterm_encoded+=${'$'}__jvterm_hex ;;
                    esac
                done
                REPLY=${'$'}__jvterm_encoded
            }
            __jvterm_osc7() {
                __jvterm_encode_uri_path "${'$'}PWD"
                printf '\033]7;file://%s%s\a' "${'$'}__jvterm_hostname" "${'$'}REPLY"
            }
            __jvterm_install_prompt_end() {
                case "${'$'}PS1" in
                    *${'$'}'\033]133;B\a'*) ;;
                    *) PS1="${'$'}PS1"${'$'}'\[\033]133;B\a\]' ;;
                esac
            }
            __jvterm_prompt_command() {
                local __jvterm_status=${'$'}?
                __jvterm_in_prompt=1
                if [[ ${'$'}{__jvterm_command_started:-0} == 1 ]]; then
                    __jvterm_osc133 "D;${'$'}__jvterm_status"
                    __jvterm_command_started=0
                fi
                __jvterm_install_prompt_end
                __jvterm_osc7
                __jvterm_osc133 A
                __jvterm_in_prompt=0
                return ${'$'}__jvterm_status
            }
            __jvterm_preexec() {
                if [[ ${'$'}{__jvterm_in_prompt:-0} == 1 || ${'$'}{__jvterm_command_started:-0} == 1 ]]; then
                    return
                fi
                case "${'$'}BASH_COMMAND" in
                    __jvterm_*|*__jvterm_prompt_command*) return ;;
                esac
                __jvterm_command_started=1
                __jvterm_osc133 C
            }
            trap '__jvterm_preexec' DEBUG
        fi
        __jvterm_prompt_command
        """.trimIndent()

    internal val FISH_INIT_COMMAND =
        """
        if not set -q __JVTERM_FISH_SHELL_INTEGRATION_INSTALLED
            set -g __JVTERM_FISH_SHELL_INTEGRATION_INSTALLED 1
            set -g __jvterm_fish_command_started 0
            function __jvterm_osc133 --argument-names marker
                printf '\e]133;%s\a' "${'$'}marker"
            end
            set -g __jvterm_fish_hostname (hostname 2>/dev/null)
            if test -z "${'$'}__jvterm_fish_hostname"
                set -g __jvterm_fish_hostname localhost
            end
            function __jvterm_osc7
                set -l encoded_path (string escape --style=url -- "${'$'}PWD" | string replace -a '%2F' '/' | string replace -a '%2f' '/')
                printf '\e]7;file://%s%s\a' "${'$'}__jvterm_fish_hostname" "${'$'}encoded_path"
            end
            function __jvterm_fish_prompt --on-event fish_prompt
                set -l code ${'$'}status
                if test "${'$'}__jvterm_fish_command_started" = 1
                    __jvterm_osc133 "D;${'$'}code"
                    set -g __jvterm_fish_command_started 0
                end
                __jvterm_osc7
                __jvterm_osc133 A
            end
            function __jvterm_fish_preexec --on-event fish_preexec
                set -g __jvterm_fish_command_started 1
                __jvterm_osc133 C
            end
            function __jvterm_fish_postexec --on-event fish_postexec
                set -l code ${'$'}status
                if test "${'$'}__jvterm_fish_command_started" = 1
                    __jvterm_osc133 "D;${'$'}code"
                    set -g __jvterm_fish_command_started 0
                end
            end
            set -l __jvterm_prompt_end (printf '\e]133;B\a')
            if not string match -q "*${'$'}__jvterm_prompt_end*" -- "${'$'}SHELL_PROMPT_SUFFIX"
                set -gx SHELL_PROMPT_SUFFIX "${'$'}SHELL_PROMPT_SUFFIX${'$'}__jvterm_prompt_end"
            end
        end
        """.trimIndent()

    internal val ZSH_INTEGRATION_SCRIPT =
        """
        if [[ -z ${'$'}{__JVTERM_ZSH_SHELL_INTEGRATION_INSTALLED:-} ]]; then
            typeset -g __JVTERM_ZSH_SHELL_INTEGRATION_INSTALLED=1
            typeset -g __jvterm_zsh_command_started=0
            function __jvterm_osc133() {
                printf '\033]133;%s\a' "${'$'}1"
            }
            typeset -g __jvterm_zsh_hostname=${'$'}{HOST:-${'$'}(hostname 2>/dev/null)}
            [[ -n "${'$'}__jvterm_zsh_hostname" ]] || __jvterm_zsh_hostname=localhost
            function __jvterm_zsh_encode_uri_path() {
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
            function __jvterm_zsh_osc7() {
                __jvterm_zsh_encode_uri_path "${'$'}PWD"
                printf '\033]7;file://%s%s\a' "${'$'}__jvterm_zsh_hostname" "${'$'}REPLY"
            }
            function __jvterm_zsh_preexec() {
                __jvterm_zsh_command_started=1
                __jvterm_osc133 C
            }
            function __jvterm_zsh_precmd() {
                local code=${'$'}?
                if [[ ${'$'}__jvterm_zsh_command_started == 1 ]]; then
                    __jvterm_osc133 "D;${'$'}code"
                    __jvterm_zsh_command_started=0
                fi
                __jvterm_zsh_osc7
                __jvterm_osc133 A
                return ${'$'}code
            }
            autoload -Uz add-zsh-hook
            add-zsh-hook preexec __jvterm_zsh_preexec
            add-zsh-hook precmd __jvterm_zsh_precmd
            case "${'$'}PROMPT" in
                *${'$'}'\033]133;B\a'*) ;;
                *) PROMPT="${'$'}PROMPT"${'$'}'%{\033]133;B\a%}' ;;
            esac
        fi
        """.trimIndent()

    private val POWERSHELL_SCRIPT =
        """
        if (-not ${'$'}global:__JvTermShellIntegrationInstalled) {
            ${'$'}global:__JvTermShellIntegrationInstalled = ${'$'}true
            ${'$'}global:__JvTermCommandStarted = ${'$'}false
            ${'$'}global:__JvTermLastExitCodeBeforeCommand = ${'$'}global:LASTEXITCODE
            function global:__JvTermOsc133([string] ${'$'}Marker) {
                [Console]::Write(([string][char]27) + ']133;' + ${'$'}Marker + ([string][char]7))
            }
            function global:__JvTermOsc7 {
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
            ${'$'}global:__JvTermOriginalPrompt = (Get-Item Function:\prompt).ScriptBlock
            function global:prompt {
                ${'$'}success = ${'$'}?
                ${'$'}nativeExitCode = ${'$'}global:LASTEXITCODE
                if (${'$'}global:__JvTermCommandStarted) {
                    if (${'$'}nativeExitCode -is [int] -and ${'$'}nativeExitCode -ne ${'$'}global:__JvTermLastExitCodeBeforeCommand) {
                        ${'$'}exitCode = ${'$'}nativeExitCode
                    } elseif (${'$'}success) {
                        ${'$'}exitCode = 0
                    } else {
                        ${'$'}exitCode = 1
                    }
                    global:__JvTermOsc133 ('D;' + ${'$'}exitCode)
                    ${'$'}global:__JvTermCommandStarted = ${'$'}false
                }
                global:__JvTermOsc7
                global:__JvTermOsc133 'A'
                try {
                    ${'$'}promptText = & ${'$'}global:__JvTermOriginalPrompt
                } finally {
                    if (${'$'}nativeExitCode -is [int]) {
                        ${'$'}global:LASTEXITCODE = ${'$'}nativeExitCode
                    }
                }
                ${'$'}promptText + ([string][char]27) + ']133;B' + ([string][char]7)
            }
            ${'$'}command = Get-Command PSConsoleHostReadLine -CommandType Function -ErrorAction SilentlyContinue
            if (${'$'}command -ne ${'$'}null) {
                ${'$'}global:__JvTermOriginalPSConsoleHostReadLine = ${'$'}command.ScriptBlock
                function global:PSConsoleHostReadLine {
                    ${'$'}line = & ${'$'}global:__JvTermOriginalPSConsoleHostReadLine
                    if (${'$'}line -ne ${'$'}null -and ${'$'}line.Trim().Length -gt 0) {
                        ${'$'}global:__JvTermLastExitCodeBeforeCommand = ${'$'}global:LASTEXITCODE
                        ${'$'}global:__JvTermCommandStarted = ${'$'}true
                        global:__JvTermOsc133 'C'
                    }
                    ${'$'}line
                }
            }
        }
        """.trimIndent()
}
