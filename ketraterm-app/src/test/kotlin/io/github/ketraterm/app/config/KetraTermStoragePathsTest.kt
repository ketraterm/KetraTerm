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

import io.github.ketraterm.completion.persistence.TerminalCompletionLearningCoordinator
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class KetraTermStoragePathsTest {
    @TempDir
    lateinit var directory: Path

    private val fileName get() = TerminalCompletionLearningCoordinator.currentFileName()

    @Test
    fun `Windows separates roaming preferences from local learning`() {
        val roaming = directory.resolve("Roaming")
        val local = directory.resolve("Local")
        val paths = paths("Windows 11", mapOf("APPDATA" to roaming.toString(), "LOCALAPPDATA" to local.toString()))

        assertEquals(roaming.resolve("KetraTerm/config.toml"), paths.configPath)
        assertEquals(local.resolve("KetraTerm").resolve(fileName), paths.learningPathFor(paths.configPath))
    }

    @Test
    fun `Windows local learning has a user profile fallback`() {
        val paths = paths("Windows 11")
        assertEquals(directory.resolve("AppData/Local/KetraTerm").resolve(fileName), paths.learningPathFor(paths.configPath))
        assertEquals(directory.resolve(".config/ketraterm/config.toml"), paths.configPath)
    }

    @Test
    fun `macOS keeps app-managed files in Application Support`() {
        val paths = paths("macOS")
        val support = directory.resolve("Library/Application Support/KetraTerm")
        assertEquals(support.resolve("config.toml"), paths.configPath)
        assertEquals(support.resolve(fileName), paths.learningPathFor(paths.configPath))
    }

    @Test
    fun `Linux separates default configuration and persistent state`() {
        val paths = paths("Linux")
        assertEquals(directory.resolve(".config/ketraterm/config.toml"), paths.configPath)
        assertEquals(directory.resolve(".local/state/ketraterm").resolve(fileName), paths.learningPathFor(paths.configPath))
    }

    @Test
    fun `Linux honors independent absolute XDG configuration and state roots`() {
        val config = directory.resolve("settings")
        val state = directory.resolve("state")
        val paths = paths("Linux", mapOf("XDG_CONFIG_HOME" to config.toString(), "XDG_STATE_HOME" to state.toString()))
        assertEquals(config.resolve("ketraterm/config.toml"), paths.configPath)
        assertEquals(state.resolve("ketraterm").resolve(fileName), paths.learningPathFor(paths.configPath))
    }

    @Test
    fun `unset blank relative and malformed XDG roots use their defaults`() {
        for (value in listOf("", " ", "relative/root", "invalid\u0000root")) {
            val paths = paths("Linux", mapOf("XDG_CONFIG_HOME" to value, "XDG_STATE_HOME" to value))
            assertEquals(directory.resolve(".config/ketraterm/config.toml"), paths.configPath, value)
            assertEquals(directory.resolve(".local/state/ketraterm").resolve(fileName), paths.learningPathFor(paths.configPath), value)
        }
    }

    @Test
    fun `explicit system and environment configuration paths retain portable learning and precedence`() {
        val system = directory.resolve("system/config.toml")
        val environment = directory.resolve("environment/config.toml")
        val env = mapOf("KetraTerm_CONFIG_PATH" to environment.toString())
        val systemPaths = paths("Windows 11", env, system.toString())
        assertEquals(system, systemPaths.configPath)
        assertEquals(system.resolveSibling(fileName), systemPaths.learningPathFor(systemPaths.configPath))
        for (os in listOf("Windows 11", "Linux", "macOS")) {
            val paths = paths(os, env)
            assertEquals(environment, paths.configPath)
            assertEquals(environment.resolveSibling(fileName), paths.learningPathFor(paths.configPath))
        }
    }

    @Test
    fun `the companion environment preserves platform learning for the standard configuration`() {
        for (os in listOf("Windows 11", "Linux", "macOS")) {
            val defaults = paths(os)
            val inherited = paths(os, mapOf("KetraTerm_CONFIG_PATH" to defaults.configPath.toString()))
            val explicit = paths(os, systemPath = defaults.configPath.toString())
            assertEquals(defaults.learningPathFor(defaults.configPath), inherited.learningPathFor(inherited.configPath))
            assertEquals(defaults.learningPathFor(defaults.configPath), explicit.learningPathFor(explicit.configPath))
        }
    }

    @Test
    fun `an explicitly constructed configuration manager keeps its own learning file`() {
        val paths = paths("Linux")
        val custom = directory.resolve("portable/custom.toml")
        assertEquals(custom.resolveSibling(fileName), paths.learningPathFor(custom))
    }

    private fun paths(
        os: String,
        env: Map<String, String> = emptyMap(),
        systemPath: String? = null,
    ) = KetraTermStoragePaths(osName = os, env = env, userHome = directory.toString(), systemConfigPath = systemPath)
}
