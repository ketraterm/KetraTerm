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

import java.util.ArrayList
import java.util.Base64
import java.util.Locale

/**
 * Applies host-neutral shell integration launch hooks to terminal profiles.
 *
 * The bootstrapper modifies only shell process arguments. It does not parse
 * terminal output, mutate core state, or own PTY lifecycle.
 */
internal object TerminalShellIntegrationBootstrap {
    /**
     * Returns [profile] with shell integration startup hooks applied when
     * supported by the shell family and [enabled] is true.
     */
    fun apply(
        profile: TerminalProfile,
        enabled: Boolean,
    ): TerminalProfile {
        if (!enabled) return profile
        return when (profile.kind) {
            TerminalProfileKind.POWERSHELL -> withPowerShellIntegration(profile)
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

    private val explicitEntryPointArguments =
        setOf(
            "-command",
            "-c",
            "-encodedcommand",
            "-enc",
            "-file",
            "-f",
        )

    private val POWERSHELL_SCRIPT =
        """
        if (-not ${'$'}global:__JvTermShellIntegrationInstalled) {
            ${'$'}global:__JvTermShellIntegrationInstalled = ${'$'}true
            ${'$'}global:__JvTermCommandStarted = ${'$'}false
            ${'$'}global:__JvTermLastExitCodeBeforeCommand = ${'$'}global:LASTEXITCODE
            function global:__JvTermOsc133([string] ${'$'}Marker) {
                [Console]::Write(([string][char]27) + ']133;' + ${'$'}Marker + ([string][char]7))
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
