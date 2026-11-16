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

import io.github.ketraterm.shell.launch.TerminalShellLaunch
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalShellLaunchZshTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `host directory resolves independently while zsh keeps its bootstrap`(
        wsl: Boolean,
        @TempDir scripts: Path,
    ) {
        val command = if (wsl) listOf("wsl.exe", "--", "zsh", "-l") else listOf("zsh", "-l")
        val profile = TerminalProfile("test", "Test", command, kind = if (wsl) TerminalProfileKind.WSL else TerminalProfileKind.ZSH)
        TerminalShellLaunch.prepare(profile, scriptDirectory = scripts).use { launch ->
            assertTrue(launch.promptMarkersExpected)
            val defaultEnvironment = launch.profile.environment.toMap()
            for (directory in listOf("first-host-directory", "second-host-directory", "", null)) {
                val inherited = linkedMapOf("HOME" to "host-home", "KetraTerm_ORIGINAL_ZDOTDIR" to "stale-marker")
                if (directory != null) inherited["ZDOTDIR"] = directory
                val originalInherited = inherited.toMap()
                val environment = launch.environment(inherited)

                assertEquals(scripts.resolve("zsh").toString(), environment["ZDOTDIR"])
                assertEquals(directory, environment["KetraTerm_ORIGINAL_ZDOTDIR"])
                assertEquals(directory != null, environment.containsKey("KetraTerm_ORIGINAL_ZDOTDIR"))
                assertEquals("host-home", environment["HOME"])
                assertEquals(environment, launch.environment(inherited))
                assertEquals(originalInherited, inherited)
                assertEquals(defaultEnvironment, launch.profile.environment)
                if (wsl) {
                    val exports = environment.getValue("WSLENV").split(':')
                    val originalExports = exports.filter { it.substringBefore('/') == "KetraTerm_ORIGINAL_ZDOTDIR" }
                    val expectedExports =
                        when (directory) {
                            null -> emptyList()
                            "" -> listOf("KetraTerm_ORIGINAL_ZDOTDIR/u")
                            else -> listOf("KetraTerm_ORIGINAL_ZDOTDIR/up")
                        }
                    assertEquals(expectedExports, originalExports)
                    assertTrue("ZDOTDIR/up" in exports)
                }
            }
            assertFalse(launch.environment(emptyMap()).containsKey("KetraTerm_ORIGINAL_ZDOTDIR"))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["profile", "empty profile", "selected", "empty selected"])
    fun `directory overrides preserve precedence without bypassing zsh hooks`(
        source: String,
        @TempDir scripts: Path,
    ) {
        val explicit = mutableMapOf("ZDOTDIR" to if (source == "empty profile") "" else "profile-directory")
        val variables =
            when (source) {
                "selected" -> mutableMapOf("ZDOTDIR" to "selected-directory")
                "empty selected" -> mutableMapOf("ZDOTDIR" to "")
                else -> mutableMapOf()
            }
        val expected = variables["ZDOTDIR"] ?: explicit.getValue("ZDOTDIR")
        val profile =
            TerminalProfile(
                "zsh",
                "Zsh",
                listOf("zsh", "-l"),
                environment = explicit,
                shellEnvironment = TerminalShellEnvironment(variables),
            )
        TerminalShellLaunch.prepare(profile, scriptDirectory = scripts).use { launch ->
            explicit["ZDOTDIR"] = "changed-profile"
            variables["ZDOTDIR"] = "changed-selection"
            for (environment in listOf(launch.profile.environment, launch.environment(mapOf("ZDOTDIR" to "host-directory")))) {
                assertEquals(expected, environment["KetraTerm_ORIGINAL_ZDOTDIR"])
                assertEquals(scripts.resolve("zsh").toString(), environment["ZDOTDIR"])
                if (source.endsWith("selected")) {
                    assertEquals(expected, environment["_KetraTerm_FORCE_SET_ZDOTDIR"])
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["disabled", "command", "other shell"])
    fun `launches without zsh hooks retain normal directory overrides`(
        mode: String,
        @TempDir scripts: Path,
    ) {
        val command =
            when (mode) {
                "command" -> listOf("zsh", "-c", "print explicit")
                "other shell" -> listOf("bash", "-i")
                else -> listOf("zsh")
            }
        val profile =
            TerminalProfile(
                "test",
                "Test",
                command,
                shellEnvironment = TerminalShellEnvironment(mapOf("ZDOTDIR" to "selected-directory")),
            )
        TerminalShellLaunch.prepare(profile, enabled = mode != "disabled", scriptDirectory = scripts).use { launch ->
            val environment = launch.environment(mapOf("ZDOTDIR" to "host-directory", "KetraTerm_ORIGINAL_ZDOTDIR" to "host-marker"))
            assertEquals("selected-directory", environment["ZDOTDIR"])
            assertEquals("host-marker", environment["KetraTerm_ORIGINAL_ZDOTDIR"])
            assertEquals(mode == "other shell", launch.promptMarkersExpected)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `directory resolution follows platform case rules`(windows: Boolean) {
        val inherited = mapOf("zdotdir" to "host-directory", "ketraterm_original_zdotdir" to "stale-marker")
        val profile = TerminalProfile("zsh", "Zsh", listOf("zsh"), environment = mapOf("ZDOTDIR" to "bootstrap"))
        val launch = ShellIntegrationLaunch(profile, true, zshStartupDirectory = "bootstrap")
        val environment = launch.environment(inherited, windows)
        assertEquals("bootstrap", environment["ZDOTDIR"])
        if (windows) {
            assertEquals("host-directory", environment["KetraTerm_ORIGINAL_ZDOTDIR"])
            assertEquals("bootstrap", environment["zdotdir"])
            assertEquals(1, environment.keys.count { it.equals("ZDOTDIR", ignoreCase = true) })
            assertEquals(1, environment.keys.count { it.equals("KetraTerm_ORIGINAL_ZDOTDIR", ignoreCase = true) })
        } else {
            assertFalse(environment.containsKey("KetraTerm_ORIGINAL_ZDOTDIR"))
            assertEquals("host-directory", environment["zdotdir"])
            assertEquals("stale-marker", environment["ketraterm_original_zdotdir"])
        }
    }

    @Test
    fun `failed hook setup leaves directory overrides unchanged`(
        @TempDir scripts: Path,
    ) {
        Files.writeString(scripts.resolve("zsh"), "occupied")
        val profile =
            TerminalProfile(
                "zsh",
                "Zsh",
                listOf("zsh"),
                shellEnvironment = TerminalShellEnvironment(mapOf("ZDOTDIR" to "selected-directory")),
            )
        TerminalShellLaunch.prepare(profile, scriptDirectory = scripts).use { launch ->
            assertFalse(launch.promptMarkersExpected)
            val environment = launch.environment(mapOf("ZDOTDIR" to "host-directory", "KetraTerm_ORIGINAL_ZDOTDIR" to "host-marker"))
            assertEquals("selected-directory", environment["ZDOTDIR"])
            assertEquals("host-marker", environment["KetraTerm_ORIGINAL_ZDOTDIR"])
        }
    }

    @Test
    fun `default profile resolves original zsh directory from the JVM`(
        @TempDir scripts: Path,
    ) {
        val windows = File.pathSeparatorChar == ';'
        val expected =
            System
                .getenv()
                .entries
                .lastOrNull { it.key.equals("ZDOTDIR", ignoreCase = windows) }
                ?.value
        TerminalShellLaunch.prepare(TerminalProfile("zsh", "Zsh", listOf("zsh")), scriptDirectory = scripts).use { launch ->
            assertEquals(expected, launch.profile.environment["KetraTerm_ORIGINAL_ZDOTDIR"])
            assertEquals(expected != null, launch.profile.environment.containsKey("KetraTerm_ORIGINAL_ZDOTDIR"))
            assertEquals(scripts.resolve("zsh").toString(), launch.profile.environment["ZDOTDIR"])
        }
    }
}
