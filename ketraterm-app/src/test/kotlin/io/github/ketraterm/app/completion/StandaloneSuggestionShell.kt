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
package io.github.ketraterm.app.completion

import io.github.ketraterm.workspace.TerminalProfile
import io.github.ketraterm.workspace.TerminalProfileRegistry
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.awt.GraphicsEnvironment
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue

/** Installed launch variants with automatic prompt hooks; availability is resolved only after opting in. */
internal enum class StandaloneSuggestionShell(
    private val profileId: String,
    private val executable: String,
) {
    WINDOWS_POWERSHELL("windows-powershell", "powershell.exe"),
    POWERSHELL("powershell", "pwsh"),
    BASH("bash", "bash"),
    GIT_BASH("git-bash", "bash"),
    ZSH("zsh", "zsh"),
    FISH("fish", "fish"),
    WSL_BASH("wsl", "bash"),
    WSL_ZSH("wsl", "zsh"),
    WSL_FISH("wsl", "fish"),
    UBUNTU_BASH("ubuntu", "bash"),
    UBUNTU_ZSH("ubuntu", "zsh"),
    UBUNTU_FISH("ubuntu", "fish"),
    ;

    val isGuest: Boolean get() = profileId == "wsl" || profileId == "ubuntu"
    val isPowerShell: Boolean get() = this == WINDOWS_POWERSHELL || this == POWERSHELL

    private fun requireAvailable(
        available: Boolean,
        reason: String,
    ) {
        val required =
            System
                .getProperty("terminal.test.requiredShells", "")
                .split(',')
                .filter(String::isNotBlank)
                .map { valueOf(it.trim()) }
                .toSet()
        if (this in required) {
            assertTrue(available, "$name: $reason")
        } else {
            assumeTrue(available, reason)
        }
    }

    fun profile(directory: Path): TerminalProfile {
        assertTrue(!GraphicsEnvironment.isHeadless(), "Run desktopTest with a desktop or virtual display")
        val registry = TerminalProfileRegistry()
        val discovered =
            registry.availableProfiles().firstOrNull { it.id == profileId }
                ?: if (this == POWERSHELL && registry.isValidShellPath("pwsh")) {
                    // Unix profile discovery does not offer PowerShell, but an explicit pwsh launch is supported.
                    registry.configuredProfile("pwsh")
                } else {
                    null
                }
        requireAvailable(discovered != null, "$profileId is not installed or discoverable on this host")
        val base = requireNotNull(discovered)
        val prefix =
            when (profileId) {
                "wsl" -> {
                    val distribution = System.getProperty("terminal.pty.wsl.distribution", "")
                    requireAvailable(distribution.isNotBlank(), "Set terminal.pty.wsl.distribution to enable WSL cases")
                    listOf(base.command.first(), "-d", distribution, "--")
                }
                "ubuntu" -> listOf(base.command.first(), "run")
                else -> emptyList()
            }
        if (isGuest) {
            assertTrue(probe(prefix + "true"), "$profileId distribution could not be started")
            requireAvailable(probe(prefix + listOf("sh", "-c", "command -v $executable")), "$executable is not installed in $profileId")
        }
        val arguments =
            when {
                isPowerShell -> listOf("-NoLogo", "-NoProfile")
                executable == "bash" -> listOf("--noprofile", "--norc", "-i")
                executable == "zsh" -> listOf("-d", "-i")
                executable == "fish" -> listOf("-C", "set -g fish_history ''", "-i")
                else -> listOf("-i")
            }
        return base.copy(
            id = name,
            command = prefix + (if (isGuest) executable else base.command.first()) + arguments,
            workingDirectory = directory,
        )
    }

    /** Missing optional inventory prerequisites must not skip the common popup/edit scenario. */
    fun supportsLiveCommands(profile: TerminalProfile): Boolean =
        when {
            isPowerShell -> true
            executable == "bash" ->
                probe(
                    profile.command.dropLast(1) + listOf("-c", "compgen -V names -A builtin && command -v base64 >/dev/null"),
                )
            executable == "zsh" ->
                probe(
                    profile.command.dropLast(1) + listOf("-c", "zmodload zsh/parameter && (( ${'$'}+commands[base64] ))"),
                )
            else -> false
        }.also { supported ->
            if (executable != "fish") requireAvailable(supported, "Live command discovery prerequisites are missing")
        }

    private fun probe(command: List<String>): Boolean {
        val process =
            ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Shell prerequisite probe timed out: $command")
            return process.exitValue() == 0
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
                assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Shell prerequisite probe did not stop: $command")
            }
        }
    }
}
