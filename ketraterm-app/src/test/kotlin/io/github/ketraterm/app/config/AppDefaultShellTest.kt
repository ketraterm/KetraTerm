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
package io.github.ketraterm.app.config

import io.github.ketraterm.workspace.TerminalProfileRegistry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class AppDefaultShellTest {
    @Test
    fun `Windows prefers discovered portable PowerShell over Windows PowerShell`(
        @TempDir directory: Path,
    ) {
        val first = directory.resolve("portable")
        val second = directory.resolve("other")
        val expected = first.resolve("pwsh.exe")
        val environment = mapOf("Path" to "$first;$second", "pRoGrAmFiLeS" to directory.toString())
        val known = setOf(expected, second.resolve("pwsh.exe"), directory.resolve("PowerShell/7/pwsh.exe"))
        assertEquals(expected.toString(), windowsDefault(environment, known))
    }

    @Test
    fun `Windows discovers installed PowerShell outside PATH`(
        @TempDir directory: Path,
    ) {
        val expected = directory.resolve("PowerShell/7/pwsh.exe")
        assertEquals(expected.toString(), windowsDefault(mapOf("programfiles" to directory.toString()), setOf(expected)))
    }

    @Test
    fun `Windows falls back to Windows PowerShell when modern PowerShell is absent`() {
        assertEquals("powershell.exe", windowsDefault(emptyMap(), emptySet()))
    }

    @Test
    fun `Unix defaults preserve the login shell environment and existing fallback`() {
        assertEquals("/bin/zsh", defaultShellPath("macOS", mapOf("SHELL" to "/bin/zsh")))
        assertEquals("/usr/bin/fish", defaultShellPath("Linux", mapOf("SHELL" to "/usr/bin/fish")))
        assertEquals("/bin/bash", defaultShellPath("Linux", emptyMap()))
        assertEquals("/bin/bash", defaultShellPath("Linux", mapOf("SHELL" to " ")))
    }

    @Test
    fun `explicit saved Windows shell choices are never replaced`(
        @TempDir directory: Path,
    ) {
        val file = directory.resolve("config.toml")
        val manager = KetraTermConfigManager(file)
        for (shell in listOf("powershell.exe", "PowerShell.EXE", "cmd.exe", "C:/custom/pwsh.exe")) {
            java.nio.file.Files
                .writeString(file, "[shell]\npath = \"$shell\"\n")
            val loaded = manager.load()
            assertEquals(shell, loaded.shellPath)
            manager.save(loaded.copy(fontSize = 18))
            assertEquals(shell, manager.load().shellPath)
        }
    }

    private fun windowsDefault(
        environment: Map<String, String>,
        known: Set<Path>,
    ): String =
        defaultShellPath(
            osName = "Windows 11",
            environment = environment,
            profileRegistry =
                TerminalProfileRegistry(
                    osName = "Windows 11",
                    environment = environment,
                    pathSeparator = ";",
                    executableExists = { it in known },
                ),
        )
}
