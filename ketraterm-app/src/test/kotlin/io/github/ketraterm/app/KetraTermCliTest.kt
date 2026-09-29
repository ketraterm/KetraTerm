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
package io.github.ketraterm.app

import io.github.ketraterm.session.TerminalStartupCommand
import io.github.ketraterm.workspace.TerminalProfile
import io.github.ketraterm.workspace.TerminalProfileKind
import io.github.ketraterm.workspace.TerminalShellEnvironment
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlin.test.*

class KetraTermCliTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `preparation supplies product metadata and preserves the launch contract`() {
        val shellEnvironment = TerminalShellEnvironment(mapOf("JAVA_HOME" to "chosen-jdk"), "chosen-jdk/bin")
        val original =
            profile(mapOf("CUSTOM" to "unchanged", "KetraTerm_CONFIG_PATH" to "stale-config"))
                .copy(
                    command = listOf("custom-shell", "--explicit-command", "echo test"),
                    workingDirectory = directory.resolve("working directory"),
                    shellEnvironment = shellEnvironment,
                    startupCommand = TerminalStartupCommand("echo ready"),
                )
        val config = directory.resolve("custom settings/config.toml")

        val prepared = KetraTermCli(config, directory.resolve("helpers")).prepare(original, emptyMap())

        assertEquals(original.copy(environment = prepared.environment), prepared)
        assertSame(shellEnvironment, prepared.shellEnvironment)
        assertEquals("unchanged", prepared.environment["CUSTOM"])
        assertEquals(config.toAbsolutePath().toString(), prepared.environment["KetraTerm_CONFIG_PATH"])
        assertEquals(appVersion, prepared.environment["KetraTerm_VERSION"])
        assertEquals("${System.getProperty("os.name")} (${System.getProperty("os.arch")})", prepared.environment["KetraTerm_OS"])
        assertEquals("${System.getProperty("java.version")} (${System.getProperty("java.vendor")})", prepared.environment["KetraTerm_JVM"])
        assertEquals(mapOf("CUSTOM" to "unchanged", "KetraTerm_CONFIG_PATH" to "stale-config"), original.environment)
        assertTrue(Files.isRegularFile(directory.resolve("helpers/ketra")))
        assertTrue(Files.isRegularFile(directory.resolve("helpers/ketra.bat")))
    }

    @Test
    fun `Windows PATH uses inherited spelling and explicit value without duplicate aliases`() {
        val helper = directory.resolve("helpers")
        val prepared =
            KetraTermCli(directory.resolve("config.toml"), helper, windows = true).prepare(
                profile(mapOf("PATH" to "C:\\custom", "USER_VALUE" to "keep")),
                mapOf("Path" to "C:\\system"),
            )

        assertEquals(mapOf("Path" to "$helper;C:\\custom"), prepared.environment.filterKeys { it.equals("PATH", ignoreCase = true) })
        assertEquals("keep", prepared.environment["USER_VALUE"])
    }

    @Test
    fun `POSIX PATH remains distinct from mixed case variables`() {
        val helper = directory.resolve("helpers")
        val prepared =
            KetraTermCli(directory.resolve("config.toml"), helper, windows = false).prepare(
                profile(mapOf("Path" to "case-sensitive-value", "PATH" to "/custom/bin")),
                mapOf("PATH" to "/system/bin"),
            )

        assertEquals("$helper:/custom/bin", prepared.environment["PATH"])
        assertEquals("case-sensitive-value", prepared.environment["Path"])
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `missing PATH falls back to the inherited value`(windows: Boolean) {
        val helper = directory.resolve("helpers")
        val pathKey = if (windows) "Path" else "PATH"
        val separator = if (windows) ';' else ':'

        val prepared =
            KetraTermCli(directory.resolve("config.toml"), helper, windows).prepare(
                profile(),
                mapOf(pathKey to "inherited-bin"),
            )

        assertEquals("$helper${separator}inherited-bin", prepared.environment[pathKey])
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `absent PATH adds only the helper directory`(windows: Boolean) {
        val helper = directory.resolve("helpers")
        val prepared = KetraTermCli(directory.resolve("config.toml"), helper, windows).prepare(profile(), emptyMap())

        assertEquals(helper.toString(), prepared.environment["PATH"])
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `explicit empty PATH does not restore inherited executable search paths`(windows: Boolean) {
        val helper = directory.resolve("helpers")
        val separator = if (windows) ';' else ':'
        val prepared =
            KetraTermCli(directory.resolve("config.toml"), helper, windows).prepare(
                profile(mapOf("PATH" to "")),
                mapOf("PATH" to "inherited-bin"),
            )

        assertEquals("$helper$separator", prepared.environment["PATH"])
    }

    @Test
    fun `reusing a prepared profile does not duplicate the helper directory`() {
        val cli = KetraTermCli(directory.resolve("config.toml"), directory.resolve("helpers"), windows)
        val prepared = cli.prepare(profile(mapOf("PATH" to "original-bin")), emptyMap())

        assertEquals(prepared, cli.prepare(prepared, emptyMap()))
    }

    @Test
    fun `Windows product metadata replaces stale aliases using inherited spelling`() {
        val config = directory.resolve("active settings.toml")
        val prepared =
            KetraTermCli(config, directory.resolve("helpers"), windows = true).prepare(
                profile(mapOf("ketraterm_config_path" to "stale", "KETRATERM_VERSION" to "old-version")),
                mapOf("KETRATERM_CONFIG_PATH" to "inherited-config", "ketraterm_version" to "inherited-version"),
            )

        assertEquals(
            mapOf("KETRATERM_CONFIG_PATH" to config.toAbsolutePath().toString()),
            prepared.environment.filterKeys { it.equals("KetraTerm_CONFIG_PATH", ignoreCase = true) },
        )
        assertEquals(
            mapOf("ketraterm_version" to appVersion),
            prepared.environment.filterKeys { it.equals("KetraTerm_VERSION", ignoreCase = true) },
        )
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `helper PATH comparison follows platform casing rules`(windows: Boolean) {
        val helper = directory.resolve("Helpers")
        val separator = if (windows) ';' else ':'
        val differentlyCasedPath = "${helper.toString().uppercase(java.util.Locale.ROOT)}${separator}original-bin"
        val prepared =
            KetraTermCli(directory.resolve("config.toml"), helper, windows).prepare(
                profile(mapOf("PATH" to differentlyCasedPath)),
                emptyMap(),
            )

        val expected = if (windows) differentlyCasedPath else "$helper$separator$differentlyCasedPath"
        assertEquals(expected, prepared.environment["PATH"])
    }

    @ParameterizedTest
    @EnumSource(value = TerminalProfileKind::class, names = ["WSL", "UBUNTU"])
    fun `guest profiles remain unchanged and do not install host helpers`(kind: TerminalProfileKind) {
        val helper = directory.resolve("helpers")
        val original =
            profile(mapOf("WSLENV" to "MY_VALUE/u", "PATH" to "guest-owned")).copy(
                command = listOf("wsl.exe", "--exec", "bash", "-l"),
                kind = kind,
            )

        val prepared = KetraTermCli(directory.resolve("config.toml"), helper).prepare(original, emptyMap())

        assertSame(original, prepared)
        assertFalse(Files.exists(helper))
    }

    @Test
    fun `unchanged scripts retain their modification times and damaged scripts are repaired`() {
        val helper = directory.resolve("helpers")
        val cli = KetraTermCli(directory.resolve("config.toml"), helper)
        cli.prepare(profile(), emptyMap())
        val paths = listOf(helper.resolve("ketra"), helper.resolve("ketra.bat"))
        val originalContents = paths.map(Files::readString)
        val timestamp = FileTime.fromMillis(1_600_000_000_000L)
        paths.forEach { Files.setLastModifiedTime(it, timestamp) }
        val storedTimes = paths.map(Files::getLastModifiedTime)
        Files.getFileAttributeView(paths.first(), PosixFileAttributeView::class.java)?.setPermissions(
            PosixFilePermissions.fromString("rw-------"),
        )

        cli.prepare(profile(), emptyMap())
        assertEquals(storedTimes, paths.map(Files::getLastModifiedTime))
        Files.getFileAttributeView(paths.first(), PosixFileAttributeView::class.java)?.let {
            assertTrue(Files.isExecutable(paths.first()), "Preparation restores executable permission without rewriting unchanged content")
        }

        paths.forEach { Files.writeString(it, "incomplete old helper") }
        cli.prepare(profile(), emptyMap())
        assertEquals(originalContents, paths.map(Files::readString))
        Files.list(helper).use { entries ->
            assertEquals(setOf("ketra", "ketra.bat"), entries.map { it.fileName.toString() }.toList().toSet())
        }
        Files.getFileAttributeView(paths.first(), PosixFileAttributeView::class.java)?.let {
            assertTrue(Files.isExecutable(paths.first()))
        }
    }

    @Test
    fun `installation failure is reported without modifying the requested environment`() {
        val helper = Files.writeString(directory.resolve("not-a-directory"), "occupied")
        val original = profile(mapOf("PATH" to "original-bin"))

        assertFailsWith<IOException> {
            KetraTermCli(directory.resolve("config.toml"), helper).prepare(original, emptyMap())
        }

        assertEquals(mapOf("PATH" to "original-bin"), original.environment)
        assertEquals("occupied", Files.readString(helper))
    }

    @Test
    fun `native helper prints the active version and diagnostic configuration path`() {
        val config = directory.resolve("custom settings & extra!(profile)/config.toml")
        val prepared = KetraTermCli(config, directory.resolve("helpers")).prepare(profile(), emptyMap())
        val version = runHelper(prepared.environment, "version")

        assertEquals(0, version.first, version.second)
        assertEquals("KetraTerm version $appVersion", version.second.trim())

        val info = runHelper(prepared.environment, "info")
        assertEquals(0, info.first, info.second)
        assertContains(info.second, config.toAbsolutePath().toString())
        assertContains(info.second, prepared.environment.getValue("KetraTerm_OS"))
        assertContains(info.second, prepared.environment.getValue("KetraTerm_JVM"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["help", "--help", "-h", ""])
    fun `native helper documents its supported commands`(argument: String) {
        val prepared = KetraTermCli(directory.resolve("config.toml"), directory.resolve("helpers")).prepare(profile(), emptyMap())

        val (exitCode, output) = runHelper(prepared.environment, argument.takeIf(String::isNotEmpty))

        assertEquals(0, exitCode, output)
        assertContains(output, "ketra <command> [options]")
        assertContains(output, "version")
        assertContains(output, "config")
        assertContains(output, "info")
    }

    @Test
    fun `native helper rejects an unknown command and missing configuration`() {
        val prepared = KetraTermCli(directory.resolve("config.toml"), directory.resolve("helpers")).prepare(profile(), emptyMap())
        val unknown = runHelper(prepared.environment, "unsupported-command")
        assertEquals(1, unknown.first, unknown.second)
        assertContains(unknown.second, "Usage: ketra")

        val missing = runHelper(prepared.environment - "KetraTerm_CONFIG_PATH", "config", clearConfig = true)
        assertEquals(1, missing.first, missing.second)
        assertContains(missing.second, "KetraTerm_CONFIG_PATH is not set.")
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 9])
    fun `native helper opens the configuration as one editor argument and preserves editor exit status`(editorExitCode: Int) {
        val config = directory.resolve("custom settings & extra!(profile)/config file.toml")
        Files.createDirectories(config.parent)
        Files.writeString(config, "test = true")
        val editor = directory.resolve(if (windows) "editor with spaces & punctuation!.bat" else "editor with spaces & punctuation!")
        val script =
            if (windows) {
                "@echo off\r\nsetlocal DisableDelayedExpansion\r\nif not \"%~2\"==\"\" exit /b 7\r\n" +
                    "set \"EDITOR_TARGET=%~1\"\r\nsetlocal EnableDelayedExpansion\r\necho editor-target:!EDITOR_TARGET!\r\nexit /b $editorExitCode\r\n"
            } else {
                "#!/bin/sh\n[ \"${'$'}#\" -eq 1 ] || exit 7\nprintf 'editor-target:%s\\n' \"${'$'}1\"\nexit $editorExitCode\n"
            }
        Files.writeString(editor, script)
        Files.getFileAttributeView(editor, PosixFileAttributeView::class.java)?.setPermissions(PosixFilePermissions.fromString("rwx------"))
        val prepared = KetraTermCli(config, directory.resolve("helpers")).prepare(profile(), emptyMap())

        val (exitCode, output) = runHelper(prepared.environment + ("EDITOR" to editor.toString()), "config")

        assertEquals(editorExitCode, exitCode, output)
        assertEquals("editor-target:${config.toAbsolutePath()}", output.trim())
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `Windows helper retains editor command arguments`() {
        val config = directory.resolve("custom settings/config.toml")
        val editor = directory.resolve("editor with spaces.bat")
        Files.writeString(
            editor,
            "@echo off\r\nif not \"%~1\"==\"--wait\" exit /b 7\r\n" +
                "if not \"%~3\"==\"\" exit /b 8\r\n" +
                "set \"EDITOR_TARGET=%~2\"\r\nsetlocal EnableDelayedExpansion\r\n" +
                "echo editor-target:!EDITOR_TARGET!\r\nexit /b 0\r\n",
        )
        val prepared = KetraTermCli(config, directory.resolve("helpers")).prepare(profile(), emptyMap())

        val (exitCode, output) = runHelper(prepared.environment + ("EDITOR" to "\"$editor\" --wait"), "config")

        assertEquals(0, exitCode, output)
        assertEquals("editor-target:${config.toAbsolutePath()}", output.trim())
    }

    private fun profile(environment: Map<String, String> = emptyMap()): TerminalProfile =
        TerminalProfile(
            id = "test-shell",
            displayName = "Test shell",
            command = listOf(if (windows) "cmd.exe" else "/bin/sh"),
            environment = environment,
        )

    private fun runHelper(
        environment: Map<String, String>,
        argument: String?,
        clearConfig: Boolean = false,
    ): Pair<Int, String> {
        val helper = directory.resolve("helpers")
        val command =
            if (windows) {
                listOf(System.getenv("ComSpec") ?: "cmd.exe", "/d", "/c", "ketra.bat")
            } else {
                listOf(helper.resolve("ketra").toString())
            } + listOfNotNull(argument)
        val output = Files.createTempFile(directory, "helper-output-", ".txt")
        val builder = ProcessBuilder(command).directory(helper.toFile()).redirectErrorStream(true).redirectOutput(output.toFile())
        builder.environment().putAll(environment)
        if (clearConfig) builder.environment().keys.removeAll { it.equals("KetraTerm_CONFIG_PATH", ignoreCase = windows) }
        val process = builder.start()
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "CLI did not finish: $command")
            return process.exitValue() to Files.readString(output)
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
                assertTrue(process.waitFor(10, TimeUnit.SECONDS), "CLI did not stop after forced cleanup")
            }
        }
    }

    private val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
}
