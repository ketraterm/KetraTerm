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
package consumer

import io.github.ketraterm.shell.launch.TerminalShellLaunch
import io.github.ketraterm.workspace.TerminalProfile
import io.github.ketraterm.workspace.TerminalShellEnvironment
import java.io.File
import java.nio.file.Files

fun main() {
    JavaConsumer.verify()
    TerminalShellLaunch.prepare(TerminalProfile("custom", "Custom", listOf("custom")), enabled = false).use { launch ->
        check(!launch.promptMarkersExpected)
        check(launch.profile.command == listOf("custom"))
        check(launch.environment(mapOf("HOST_VALUE" to "retained"))["HOST_VALUE"] == "retained")
    }
    val prefix = "consumer-toolchain"
    val inherited = linkedMapOf("PATH" to "host-first", "HOST_VALUE" to "retained")
    val profile =
        TerminalProfile(
            "environment",
            "Environment",
            listOf("custom"),
            shellEnvironment = TerminalShellEnvironment(pathPrefix = prefix),
        )
    TerminalShellLaunch.prepare(profile, enabled = false).use { launch ->
        val first = launch.environment(inherited)
        check(first["PATH"] == "$prefix${File.pathSeparator}host-first")
        check(first["HOST_VALUE"] == "retained")
        check(launch.environment(mapOf("PATH" to "host-second"))["PATH"] == "$prefix${File.pathSeparator}host-second")
        check(launch.environment(inherited) == first)
        check(inherited == mapOf("PATH" to "host-first", "HOST_VALUE" to "retained"))
    }
    val scripts = Files.createTempDirectory("ketraterm-zsh-consumer")
    try {
        val zshProfile = TerminalProfile("zsh", "Zsh", listOf("zsh"))
        TerminalShellLaunch.prepare(zshProfile, scriptDirectory = scripts).use { launch ->
            check(launch.promptMarkersExpected)
            val hookDirectory = scripts.resolve("zsh").toString()
            val firstDirectory = scripts.resolve("host-first").toString()
            val first = launch.environment(mapOf("ZDOTDIR" to firstDirectory))
            check(first["KetraTerm_ORIGINAL_ZDOTDIR"] == firstDirectory)
            check(first["ZDOTDIR"] == hookDirectory)
            val secondDirectory = scripts.resolve("host-second").toString()
            val second = launch.environment(mapOf("ZDOTDIR" to secondDirectory))
            check(second["KetraTerm_ORIGINAL_ZDOTDIR"] == secondDirectory)
            check(second["ZDOTDIR"] == hookDirectory)
            val absent = launch.environment(emptyMap())
            check("KetraTerm_ORIGINAL_ZDOTDIR" !in absent)
            check(absent["ZDOTDIR"] == hookDirectory)
            check(launch.environment(mapOf("ZDOTDIR" to firstDirectory)) == first)
        }
    } finally {
        check(scripts.toFile().deleteRecursively())
    }
    check(runCatching { Class.forName("io.github.ketraterm.workspace.TerminalWorkspace") }.isFailure)
    check(runCatching { Class.forName("io.github.ketraterm.pty.TerminalSessions") }.isFailure)
}
