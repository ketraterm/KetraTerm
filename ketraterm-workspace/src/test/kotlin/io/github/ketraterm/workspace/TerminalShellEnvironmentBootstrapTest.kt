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

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.*
import kotlin.io.path.readText
import kotlin.test.*

class TerminalShellEnvironmentBootstrapTest {
    @Test
    fun `empty shell environment keeps launch profile identical`() {
        val profile = profile().copy(shellEnvironment = TerminalShellEnvironment.Empty)
        assertSame(profile, TerminalShellEnvironmentBootstrap.applyInitial(profile))
        assertSame(profile, TerminalShellEnvironmentBootstrap.withMarkers(profile))
    }

    @Test
    fun `selected environment overrides explicit values and prepends explicit path`() {
        val profile = profile(environment = mapOf("JAVA_HOME" to "/explicit/jdk", "PATH" to "/explicit/bin", "KEEP" to "value"))
        val launch =
            TerminalShellEnvironmentBootstrap.applyInitial(
                profile,
                mapOf("JAVA_HOME" to "/system/jdk", "PATH" to "/system/bin"),
                windows = false,
            )

        assertEquals(
            mapOf("JAVA_HOME" to "/project/jdk", "PATH" to "/project/jdk/bin:/explicit/bin", "KEEP" to "value"),
            launch.environment,
        )
        assertEquals("/explicit/jdk", profile.environment["JAVA_HOME"])
    }

    @Test
    fun `selected environment inherits path when no explicit path is configured`() {
        val launch = TerminalShellEnvironmentBootstrap.applyInitial(profile(), mapOf("PATH" to "/usr/bin"), windows = false)
        assertEquals("/project/jdk/bin:/usr/bin", launch.environment["PATH"])
    }

    @Test
    fun `missing or explicitly empty path has no trailing current directory entry`() {
        for (environment in listOf(emptyMap(), mapOf("PATH" to ""))) {
            val launch = TerminalShellEnvironmentBootstrap.applyInitial(profile(environment = environment), emptyMap(), windows = false)
            assertEquals("/project/jdk/bin", launch.environment["PATH"])
        }
    }

    @Test
    fun `windows overrides reuse inherited casing and remove explicit duplicates`() {
        val profile =
            profile(
                environment =
                    linkedMapOf(
                        "JAVA_HOME" to "explicit",
                        "java_home" to "other",
                        "PATH" to "first",
                        "Path" to "last",
                    ),
            )
        val launch =
            TerminalShellEnvironmentBootstrap.applyInitial(
                profile,
                mapOf("Java_Home" to "inherited", "pAtH" to "system"),
                windows = true,
            )

        assertEquals(mapOf("Java_Home" to "/project/jdk", "pAtH" to "/project/jdk/bin;last"), launch.environment)
    }

    @Test
    fun `posix environment keys remain case sensitive`() {
        val launch =
            TerminalShellEnvironmentBootstrap.applyInitial(
                profile(
                    environment =
                        mapOf(
                            "Path" to "unrelated",
                            "java_home" to "unrelated",
                        ),
                ),
                mapOf("PATH" to "/usr/bin"),
                windows = false,
            )

        assertEquals("unrelated", launch.environment["Path"])
        assertEquals("unrelated", launch.environment["java_home"])
        assertEquals("/project/jdk", launch.environment["JAVA_HOME"])
        assertEquals("/project/jdk/bin:/usr/bin", launch.environment["PATH"])
    }

    @Test
    fun `disabled unsupported and explicit command launches apply initial environment without markers`() {
        val profiles =
            listOf(
                profile(command = listOf("cmd.exe"), kind = TerminalProfileKind.COMMAND_PROMPT),
                profile(command = listOf("bash", "-c", "echo explicit")),
                profile(command = listOf("bash", "--", "-l")),
                profile(command = listOf("zsh", "--", "-l"), kind = TerminalProfileKind.ZSH),
                profile(command = listOf("fish", "--", "-l"), kind = TerminalProfileKind.FISH),
                profile(command = listOf("fish", "--"), kind = TerminalProfileKind.FISH),
                profile(command = listOf("bash", "--norc")),
                profile(command = listOf("bash", "--posix", "-i")),
                profile(command = listOf("C:\\Program Files\\Git\\bin\\sh.exe", "-i"), kind = TerminalProfileKind.GIT_BASH),
                profile(command = listOf("pwsh", "-Command", "Write-Host explicit"), kind = TerminalProfileKind.POWERSHELL),
            )
        for (profile in profiles) {
            val launch = TerminalShellIntegrationBootstrap.apply(profile, enabled = true)
            assertEquals(profile.command, launch.command)
            assertEquals("/project/jdk", launch.environment["JAVA_HOME"])
            assertFalse(launch.environment.keys.any { it.startsWith("_KetraTerm_FORCE_") })
        }
        val launch = TerminalShellIntegrationBootstrap.apply(profile(), enabled = false)
        assertEquals(profile().command, launch.command)
        assertEquals("/project/jdk", launch.environment["JAVA_HOME"])
        assertFalse(launch.environment.keys.any { it.startsWith("_KetraTerm_FORCE_") })
    }

    @Test
    fun `bash login wrapper preserves remaining flags and applies variables after user startup`(
        @TempDir directory: Path,
    ) {
        val profile = profile(command = listOf("bash", "--noprofile", "-il"))
        val launch = TerminalShellIntegrationBootstrap.apply(profile, enabled = true, scriptDirectory = directory)

        assertEquals(listOf("bash", "--rcfile", directory.resolve("bash/rcfile.bash").toString(), "--noprofile", "-i"), launch.command)
        assertEquals("1", launch.environment["_KetraTerm_BASH_LOGIN_SHELL"])
        assertEquals("1", launch.environment["_KetraTerm_BASH_NO_PROFILE"])
        assertEquals("/project/jdk", launch.environment["_KetraTerm_FORCE_SET_JAVA_HOME"])
        assertEquals("/project/jdk/bin", launch.environment["_KetraTerm_FORCE_PREPEND_PATH"])
        val script = directory.resolve("bash/rcfile.bash").readText()
        assertTrue(script.indexOf("source /etc/profile") < script.indexOf("_KetraTerm_FORCE_SET_"))
        assertTrue(script.indexOf("source \"${'$'}HOME/.bashrc\"") < script.indexOf("_KetraTerm_FORCE_SET_"))
        assertFalse(script.contains("/project/jdk"))
    }

    @Test
    fun `bare option terminators preserve interactive shell startup hooks`(
        @TempDir directory: Path,
    ) {
        val profiles =
            listOf(
                profile(command = listOf("bash", "--")),
                profile(command = listOf("zsh", "--"), kind = TerminalProfileKind.ZSH),
            )
        for (profile in profiles) {
            val launch = TerminalShellIntegrationBootstrap.apply(profile, enabled = true, scriptDirectory = directory)
            assertEquals(profile.command[0], launch.command.first())
            assertEquals("--", launch.command.last())
            assertEquals(1, launch.command.count { it == "--" })
            assertEquals("/project/jdk", launch.environment["_KetraTerm_FORCE_SET_JAVA_HOME"])
        }
    }

    @Test
    fun `zsh enforces environment after final startup file for login and nonlogin shells`(
        @TempDir directory: Path,
    ) {
        TerminalShellIntegrationBootstrap.apply(
            profile(command = listOf("zsh", "-l"), kind = TerminalProfileKind.ZSH),
            enabled = true,
            scriptDirectory = directory,
        )
        val rc = directory.resolve("zsh/.zshrc").readText()
        val login = directory.resolve("zsh/.zlogin").readText()
        assertTrue(rc.contains("if [[ ! -o login ]]"))
        assertTrue(rc.indexOf("source ") < rc.indexOf("_KetraTerm_FORCE_SET_"))
        assertTrue(login.indexOf("source ") < login.indexOf("_KetraTerm_FORCE_SET_"))
    }

    @Test
    fun `PowerShell and fish startup hooks consume environment payload without embedding values`(
        @TempDir directory: Path,
    ) {
        val powerShell =
            TerminalShellIntegrationBootstrap.apply(
                profile(command = listOf("pwsh"), kind = TerminalProfileKind.POWERSHELL),
                enabled = true,
                scriptDirectory = directory,
            )
        val script = String(Base64.getDecoder().decode(powerShell.command.last()), Charsets.UTF_16LE)
        assertTrue(script.indexOf("_KetraTerm_FORCE_SET_") < script.indexOf("function global:prompt"))
        assertFalse(script.contains("/project/jdk"))
        val fish =
            TerminalShellIntegrationBootstrap.apply(
                profile(command = listOf("fish"), kind = TerminalProfileKind.FISH),
                enabled = true,
                scriptDirectory = directory,
            )
        assertTrue(fish.command.last().indexOf("_KetraTerm_FORCE_SET_") < fish.command.last().indexOf("function __ketraterm_fish_prompt"))
        assertFalse(fish.command.last().contains("/project/jdk"))
    }

    @Test
    fun `shell payload rejects unsafe identifiers and impossible process environment values`() {
        for (name in listOf("", "1VAR", "A=B", "A-B", "A;echo bad", "é", "_KetraTerm_FORCE_SET_X")) {
            assertFailsWith<IllegalArgumentException> { TerminalShellEnvironment(mapOf(name to "value")) }
        }
        assertFailsWith<IllegalArgumentException> { TerminalShellEnvironment(mapOf("JAVA_HOME" to "jdk\u0000invalid")) }
        assertFailsWith<IllegalArgumentException> { TerminalShellEnvironment(pathPrefix = "") }
        assertFailsWith<IllegalArgumentException> { TerminalShellEnvironment(pathPrefix = "jdk\u0000invalid") }
    }

    private fun profile(
        command: List<String> = listOf("bash"),
        environment: Map<String, String> = emptyMap(),
        kind: TerminalProfileKind = TerminalProfileKind.BASH,
    ): TerminalProfile =
        TerminalProfile(
            id = "test",
            displayName = "Test",
            command = command,
            environment = environment,
            kind = kind,
            shellEnvironment = TerminalShellEnvironment(mapOf("JAVA_HOME" to "/project/jdk"), "/project/jdk/bin"),
        )
}
