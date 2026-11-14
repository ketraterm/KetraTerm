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
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.*

/** Resolves standalone configuration and learning paths without filesystem access. */
internal class KetraTermStoragePaths(
    osName: String = System.getProperty("os.name"),
    private val env: Map<String, String> = System.getenv(),
    private val userHome: String = System.getProperty("user.home"),
    systemConfigPath: String? = System.getProperty("ketraterm.config.path"),
) {
    private val os = osName.lowercase(Locale.ROOT)
    private val customConfigPath = systemConfigPath?.takeIf(String::isNotBlank) ?: env["KetraTerm_CONFIG_PATH"]?.takeIf(String::isNotBlank)

    private val platformConfigPath: Path =
        when {
            os.contains("windows") ->
                env["APPDATA"]?.takeIf(String::isNotBlank)?.let { Path.of(it, "KetraTerm", "config.toml") }
                    ?: Path.of(userHome, ".config", "ketraterm", "config.toml")
            os.contains("mac") -> Path.of(userHome, "Library", "Application Support", "KetraTerm", "config.toml")
            else -> (xdgPath("XDG_CONFIG_HOME") ?: Path.of(userHome, ".config")).resolve("ketraterm/config.toml")
        }

    val configPath: Path = customConfigPath?.let(Path::of) ?: platformConfigPath

    /** Nondefault configuration paths retain their existing portable, colocated learning file. */
    fun learningPathFor(configPath: Path): Path {
        val fileName = TerminalCompletionLearningCoordinator.currentFileName()
        if (configPath.toAbsolutePath().normalize() != platformConfigPath.toAbsolutePath().normalize()) {
            return configPath.resolveSibling(fileName)
        }
        val directory =
            when {
                os.contains("windows") ->
                    env["LOCALAPPDATA"]?.takeIf(String::isNotBlank)?.let { Path.of(it, "KetraTerm") }
                        ?: Path.of(userHome, "AppData", "Local", "KetraTerm")
                os.contains("mac") -> platformConfigPath.parent
                else -> (xdgPath("XDG_STATE_HOME") ?: Path.of(userHome, ".local", "state")).resolve("ketraterm")
            }
        return directory.resolve(fileName)
    }

    private fun xdgPath(name: String): Path? {
        val value = env[name]?.takeIf(String::isNotBlank) ?: return null
        return try {
            Path.of(value).takeIf(Path::isAbsolute)
        } catch (_: InvalidPathException) {
            null
        }
    }
}
