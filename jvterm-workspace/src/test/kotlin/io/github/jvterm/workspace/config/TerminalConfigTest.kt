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
package io.github.jvterm.workspace.config

import io.github.jvterm.workspace.TerminalSshProfile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class TerminalConfigTest {
    @Test
    fun `test TomlParser parses sections keys and values correctly`() {
        val toml =
            """
            # Global comment
            [window]
            columns = 120 # custom columns
            rows = 40

            [theme]
            name = "tokyo-night"

            [font]
            family = 'Fira Code' # single quotes
            size = 14
            """.trimIndent()

        val parsed = TomlParser.parse(toml)

        assertEquals("120", parsed["window"]?.get("columns"))
        assertEquals("40", parsed["window"]?.get("rows"))
        assertEquals("tokyo-night", parsed["theme"]?.get("name"))
        assertEquals("Fira Code", parsed["font"]?.get("family"))
        assertEquals("14", parsed["font"]?.get("size"))
    }

    @Test
    fun `test TomlParser ignores comments and preserves quotes inside strings`() {
        val toml =
            """
            [theme]
            color = "#ff0000" # hex value containing hash
            description = "This is a #comment text inside quotes"
            """.trimIndent()

        val parsed = TomlParser.parse(toml)
        assertEquals("#ff0000", parsed["theme"]?.get("color"))
        assertEquals("This is a #comment text inside quotes", parsed["theme"]?.get("description"))
    }

    @Test
    fun `test TerminalWorkspaceConfigManager path resolution`() {
        // Overrides
        // Clean default system property check (which might be set or not during tests, but we can verify our overrides work)
        System.setProperty("jvterm.config.path", "/custom/sys/path.toml")
        assertEquals(Path.of("/custom/sys/path.toml"), TerminalWorkspaceConfigManager.getDefaultPath(osName = "Windows 11"))
        System.clearProperty("jvterm.config.path")

        // Env override
        val env = mapOf("JVTERM_CONFIG_PATH" to "/custom/env/path.toml")
        assertEquals(Path.of("/custom/env/path.toml"), TerminalWorkspaceConfigManager.getDefaultPath(osName = "Windows 11", env = env))

        // Windows resolution
        val winEnv = mapOf("APPDATA" to "C:\\Users\\User\\AppData\\Roaming")
        val winPath = TerminalWorkspaceConfigManager.getDefaultPath(osName = "Windows 11", env = winEnv)
        assertEquals(Path.of("C:\\Users\\User\\AppData\\Roaming", "JvTerm", "config.toml"), winPath)

        // Mac resolution
        val macPath = TerminalWorkspaceConfigManager.getDefaultPath(osName = "macOS Big Sur", userHome = "/Users/username")
        assertEquals(Path.of("/Users/username/Library/Application Support/JvTerm/config.toml"), macPath)

        // Linux resolution
        val linuxEnv = mapOf("XDG_CONFIG_HOME" to "/home/username/.custom_config")
        val linuxPath = TerminalWorkspaceConfigManager.getDefaultPath(osName = "Linux", env = linuxEnv)
        assertEquals(Path.of("/home/username/.custom_config/jvterm/config.toml"), linuxPath)

        val linuxFallbackPath =
            TerminalWorkspaceConfigManager.getDefaultPath(
                osName = "Linux",
                env = emptyMap(),
                userHome = "/home/username",
            )
        assertEquals(Path.of("/home/username/.config/jvterm/config.toml"), linuxFallbackPath)
    }

    @Test
    fun `test TerminalWorkspaceConfigManager load default config if file does not exist`() {
        val tempDir = Files.createTempDirectory("jvterm-config-test")
        val configFile = tempDir.resolve("config.toml")
        val manager = TerminalWorkspaceConfigManager(configFile)

        assertFalse(Files.exists(configFile))
        val config = manager.load()

        assertTrue(Files.exists(configFile))
        assertEquals("one-dark", config.theme)
        assertEquals(100, config.columns)
        assertEquals(30, config.rows)
        val expectedFont =
            when {
                System.getProperty("os.name").lowercase(java.util.Locale.ROOT).contains("windows") -> "Cascadia Mono"
                System.getProperty("os.name").lowercase(java.util.Locale.ROOT).contains("mac") -> "Menlo"
                else -> "Monospaced"
            }
        assertEquals(expectedFont, config.fontFamily)
        assertEquals(16, config.fontSize)
        assertFalse(config.treatAmbiguousAsWide)
        assertEquals(600, config.cursorBlinkMillis)
        assertFalse(config.useSystemFallbackFonts)
        assertEquals("block", config.cursorShape)
        assertTrue(config.visualBell)
        assertFalse(config.shellRequestResizeWindow)
        assertFalse(config.shellRequestWindowManipulation)

        // Clean up
        Files.deleteIfExists(configFile)
        Files.deleteIfExists(tempDir)
    }

    @Test
    fun `test TerminalWorkspaceConfigManager saves and loads custom config correctly`() {
        val tempDir = Files.createTempDirectory("jvterm-config-test-custom")
        val configFile = tempDir.resolve("config.toml")
        val manager = TerminalWorkspaceConfigManager(configFile)

        val customConfig =
            TerminalConfig(
                theme = "nord",
                treatAmbiguousAsWide = true,
                fontFamily = "JetBrains Mono",
                fontSize = 18,
                columns = 110,
                rows = 35,
                cursorBlinkMillis = 500,
                useSystemFallbackFonts = true,
                cursorShape = "beam",
                audibleBell = false,
                visualBell = false,
                shellRequestResizeWindow = true,
                shellRequestWindowManipulation = true,
                desktopNotificationsEnabled = false,
            )

        manager.save(customConfig)
        assertTrue(Files.exists(configFile))

        val loaded = manager.load()
        assertEquals(customConfig, loaded)

        // Clean up
        Files.deleteIfExists(configFile)
        Files.deleteIfExists(tempDir)
    }

    @Test
    fun `test TerminalWorkspaceConfigManager clamps hand edited numeric values`() {
        val tempDir = Files.createTempDirectory("jvterm-config-test-clamped")
        val configFile = tempDir.resolve("config.toml")
        val manager = TerminalWorkspaceConfigManager(configFile)

        Files.writeString(
            configFile,
            """
            [window]
            columns = 999999
            rows = -42
            scrollback_lines = 999999999999999999999999

            [font]
            family = "JetBrains Mono"
            size = -9
            line_height = 0.01

            [theme]
            name = "nord"

            [behavior]
            cursor_blink_millis = 999999999999999999999999
            cursor_shape = "beam"
            """.trimIndent(),
        )

        val loaded = manager.load()

        assertEquals(TerminalConfig.COLUMNS_MAX, loaded.columns)
        assertEquals(TerminalConfig.ROWS_MIN, loaded.rows)
        assertEquals(TerminalConfig.SCROLLBACK_MAX, loaded.scrollbackLines)
        assertEquals(TerminalConfig.FONT_SIZE_MIN, loaded.fontSize)
        assertEquals(TerminalConfig.LINE_HEIGHT_MIN, loaded.lineHeight)
        assertEquals(TerminalConfig.CURSOR_BLINK_MAX, loaded.cursorBlinkMillis)

        Files.deleteIfExists(configFile)
        Files.deleteIfExists(tempDir)
    }

    @Test
    fun `test TerminalWorkspaceConfigManager loads ssh profiles without secrets`() {
        val tempDir = Files.createTempDirectory("jvterm-config-test-ssh")
        val configFile = tempDir.resolve("config.toml")
        val manager = TerminalWorkspaceConfigManager(configFile)

        Files.writeString(
            configFile,
            """
            [shell]
            path = "powershell.exe"

            [ssh.profile.prod]
            display_name = "Production"
            host = "prod.example.com"
            username = "deploy"
            port = 2222
            terminal_type = "xterm-256color"
            known_hosts = "C:\Users\me\.ssh\known_hosts"

            [ssh.profile.incomplete]
            display_name = "Missing User"
            host = "missing-user.example.com"

            [ssh.profile.dev]
            host = "dev.example.com"
            username = "me"
            port = 999999
            """.trimIndent(),
        )

        val loaded = manager.load()

        assertEquals(2, loaded.sshProfiles.size)
        assertEquals(
            TerminalSshProfile(
                id = "prod",
                displayName = "Production",
                host = "prod.example.com",
                username = "deploy",
                port = 2222,
                terminalType = "xterm-256color",
                knownHostsPath = Path.of("C:\\Users\\me\\.ssh\\known_hosts"),
            ),
            loaded.sshProfiles[0],
        )
        assertEquals(
            TerminalSshProfile(
                id = "dev",
                displayName = "me@dev.example.com",
                host = "dev.example.com",
                username = "me",
                port = 65535,
            ),
            loaded.sshProfiles[1],
        )

        Files.deleteIfExists(configFile)
        Files.deleteIfExists(tempDir)
    }

    @Test
    fun `test TerminalWorkspaceConfigManager saves and loads ssh profiles`() {
        val tempDir = Files.createTempDirectory("jvterm-config-test-ssh-save")
        val configFile = tempDir.resolve("config.toml")
        val manager = TerminalWorkspaceConfigManager(configFile)
        val profile =
            TerminalSshProfile(
                id = "staging",
                displayName = "Staging",
                host = "staging.example.com",
                username = "deploy",
                knownHostsPath = Path.of("C:\\Users\\me\\.ssh\\known_hosts"),
            )
        val config = TerminalConfig(sshProfiles = listOf(profile))

        manager.save(config)
        val raw = Files.readString(configFile)
        val loaded = manager.load()

        assertEquals(listOf(profile), loaded.sshProfiles)
        assertContains(raw, "[ssh.profile.staging]")
        assertContains(raw, "known_hosts = \"C:\\Users\\me\\.ssh\\known_hosts\"")
        assertFalse(raw.contains("password", ignoreCase = true))
        assertFalse(raw.contains("passphrase", ignoreCase = true))

        Files.deleteIfExists(configFile)
        Files.deleteIfExists(tempDir)
    }

    @Test
    fun `test TerminalConfig rejects duplicate ssh profile ids`() {
        val first =
            TerminalSshProfile(
                id = "prod",
                displayName = "Prod A",
                host = "a.example.com",
                username = "deploy",
            )
        val second =
            TerminalSshProfile(
                id = "prod",
                displayName = "Prod B",
                host = "b.example.com",
                username = "deploy",
            )

        assertFailsWith<IllegalArgumentException> {
            TerminalConfig(sshProfiles = listOf(first, second))
        }
    }

    @Test
    fun `test TerminalConfig rejects direct out of bounds values`() {
        assertFailsWith<IllegalArgumentException> {
            TerminalConfig(columns = TerminalConfig.COLUMNS_MIN - 1)
        }
        assertFailsWith<IllegalArgumentException> {
            TerminalConfig(rows = TerminalConfig.ROWS_MAX + 1)
        }
        assertFailsWith<IllegalArgumentException> {
            TerminalConfig(scrollbackLines = TerminalConfig.SCROLLBACK_MAX + 1)
        }
        assertFailsWith<IllegalArgumentException> {
            TerminalConfig(lineHeight = TerminalConfig.LINE_HEIGHT_MIN - 0.1f)
        }
    }

    @Test
    fun `test TerminalWorkspaceConfigManager fallback on invalid format`() {
        val tempDir = Files.createTempDirectory("jvterm-config-test-invalid")
        val configFile = tempDir.resolve("config.toml")
        val brokenFile = tempDir.resolve("config.toml.broken")
        val manager = TerminalWorkspaceConfigManager(configFile)

        Files.writeString(configFile, "invalid toml syntax here [[[[")

        val loaded = manager.load()
        // Should return default config instead of crashing
        assertEquals("one-dark", loaded.theme)
        assertEquals(100, loaded.columns)

        // Verify it backed up the broken file and generated a new valid file
        assertTrue(Files.exists(brokenFile))
        assertEquals("invalid toml syntax here [[[[", Files.readString(brokenFile))
        assertTrue(Files.exists(configFile))
        assertNotEquals("invalid toml syntax here [[[[", Files.readString(configFile))

        // Clean up
        Files.deleteIfExists(configFile)
        Files.deleteIfExists(brokenFile)
        Files.deleteIfExists(tempDir)
    }
}
