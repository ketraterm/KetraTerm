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
package com.gagik.terminal.workspace.config

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.*

/**
 * Manages loading and saving the [TerminalConfig] TOML file.
 *
 * It resolves standard OS-specific directories for config files unless overridden
 * by a system property or environment variable.
 *
 * @property configPath the path to the configuration TOML file on disk.
 */
class TerminalWorkspaceConfigManager(
    val configPath: Path,
) {
    /**
     * Loads the configuration from the TOML file.
     *
     * If the file does not exist, it creates a default configuration file with comments
     * and returns the default settings. If the file is invalid or unreadable, it falls back
     * gracefully to default settings.
     */
    fun load(): TerminalConfig {
        if (!Files.exists(configPath)) {
            val defaultConfig = TerminalConfig()
            save(defaultConfig)
            return defaultConfig
        }

        return try {
            val content = Files.readString(configPath)
            val parsed = TomlParser.parse(content)

            val window = parsed["window"] ?: emptyMap()
            val font = parsed["font"] ?: emptyMap()
            val themeSection = parsed["theme"] ?: emptyMap()
            val behavior = parsed["behavior"] ?: emptyMap()
            val shell = parsed["shell"] ?: emptyMap()

            val default = TerminalConfig()

            val theme = themeSection["name"] ?: default.theme
            val shellPath = shell["path"] ?: default.shellPath
            val startDirectory = shell["start_directory"] ?: default.startDirectory
            val treatAmbiguousAsWide =
                behavior["treat_ambiguous_as_wide"]?.toBooleanStrictOrNull()
                    ?: default.treatAmbiguousAsWide
            val fontFamily = font["family"] ?: default.fontFamily
            val fontSize = font["size"]?.toIntOrNull() ?: default.fontSize
            val lineHeight = font["line_height"]?.toFloatOrNull() ?: default.lineHeight
            val columns = window["columns"]?.toIntOrNull() ?: default.columns
            val rows = window["rows"]?.toIntOrNull() ?: default.rows
            val windowOpacity = window["opacity"]?.toFloatOrNull() ?: default.windowOpacity
            val cursorBlinkMillis =
                behavior["cursor_blink_millis"]?.toIntOrNull()
                    ?: default.cursorBlinkMillis
            val useSystemFallbackFonts =
                font["use_system_fallback_fonts"]?.toBooleanStrictOrNull()
                    ?: default.useSystemFallbackFonts
            val cursorShape = behavior["cursor_shape"] ?: default.cursorShape
            val audibleBell = behavior["audible_bell"]?.toBooleanStrictOrNull() ?: default.audibleBell
            val pasteOnMiddleClick = behavior["paste_on_middle_click"]?.toBooleanStrictOrNull() ?: default.pasteOnMiddleClick
            val scrollbackLines = window["scrollback_lines"]?.toIntOrNull() ?: default.scrollbackLines

            // Clean and validate inputs to ensure safety boundaries are respected
            val cleanColumns = if (columns > 0) columns else default.columns
            val cleanRows = if (rows > 0) rows else default.rows
            val cleanFontSize = if (fontSize > 0) fontSize else default.fontSize
            val cleanLineHeight = if (lineHeight > 0f) lineHeight else default.lineHeight
            val cleanCursorBlinkMillis =
                if (cursorBlinkMillis > 0) cursorBlinkMillis else default.cursorBlinkMillis
            val cleanTheme = if (theme.isNotBlank()) theme else default.theme
            val cleanFontFamily = if (fontFamily.isNotBlank()) fontFamily else default.fontFamily
            val cleanCursorShape = if (cursorShape.isNotBlank()) cursorShape else default.cursorShape
            val cleanShellPath = if (shellPath.isNotBlank()) shellPath else default.shellPath
            val cleanScrollbackLines = if (scrollbackLines >= 0) scrollbackLines else default.scrollbackLines
            val cleanWindowOpacity = if (windowOpacity in 0.1f..1.0f) windowOpacity else default.windowOpacity

            TerminalConfig(
                theme = cleanTheme,
                treatAmbiguousAsWide = treatAmbiguousAsWide,
                fontFamily = cleanFontFamily,
                fontSize = cleanFontSize,
                columns = cleanColumns,
                rows = cleanRows,
                cursorBlinkMillis = cleanCursorBlinkMillis,
                useSystemFallbackFonts = useSystemFallbackFonts,
                cursorShape = cleanCursorShape,
                shellPath = cleanShellPath,
                startDirectory = startDirectory,
                audibleBell = audibleBell,
                pasteOnMiddleClick = pasteOnMiddleClick,
                scrollbackLines = cleanScrollbackLines,
                lineHeight = cleanLineHeight,
                windowOpacity = cleanWindowOpacity,
            )
        } catch (e: Exception) {
            try {
                val backupPath = configPath.resolveSibling("${configPath.fileName}.broken")
                Files.move(configPath, backupPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                System.err.println("Configuration file was malformed and has been backed up to $backupPath")
            } catch (ioe: IOException) {
                System.err.println("Failed to back up malformed configuration file: ${ioe.message}")
            }
            val defaultConfig = TerminalConfig()
            save(defaultConfig)
            defaultConfig
        }
    }

    /**
     * Saves the provided configuration to the TOML file.
     *
     * Creates any missing parent directories before writing. Writes the values
     * mapped onto a documented TOML template for power user readability.
     */
    fun save(config: TerminalConfig) {
        try {
            val parent = configPath.parent
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent)
            }
            val toml = generateToml(config)
            Files.writeString(configPath, toml)
        } catch (e: IOException) {
            System.err.println("Failed to save configuration to $configPath: ${e.message}")
        }
    }

    private fun generateToml(config: TerminalConfig): String =
        """
        # Lattice Terminal Emulator Configuration File
        # Power users can edit this file directly to customize behavior.
        # Changes will take effect on next application launch.

        [shell]
        # Command or path to the shell executable to run
        path = "${config.shellPath}"
        # Initial working directory when opening a new tab
        start_directory = "${config.startDirectory}"

        [window]
        # Preferred default terminal size in columns and rows
        columns = ${config.columns}
        rows = ${config.rows}
        # Maximum number of lines to retain in the scrollback buffer
        # TODO(scrollback): implement history truncation in the core grid
        scrollback_lines = ${config.scrollbackLines}
        # Window opacity (1.0 = fully opaque, 0.1 = mostly transparent)
        # TODO(opacity): support translucent Swing windows in the UI host
        opacity = ${config.windowOpacity}

        [font]
        # Primary monospace font family
        family = "${config.fontFamily}"
        # Font size in points
        size = ${config.fontSize}
        # Line height multiplier
        # TODO(typography): support custom line spacing metrics in the text renderer
        line_height = ${config.lineHeight}
        # Whether the complex-text renderer may use installed system fonts as fallback
        use_system_fallback_fonts = ${config.useSystemFallbackFonts}

        [theme]
        # Resolved terminal color palette theme.
        # Supported themes: campbell, one-dark, nord, tokyo-night, everforest
        name = "${config.theme}"

        [behavior]
        # Whether East Asian Ambiguous characters should occupy two terminal cells in width policy
        treat_ambiguous_as_wide = ${config.treatAmbiguousAsWide}
        # Cursor blink period in milliseconds
        cursor_blink_millis = ${config.cursorBlinkMillis}
        # Style of the text cursor (block, underline, beam)
        cursor_shape = "${config.cursorShape}"
        # Play a system beep when the terminal receives a BEL character
        # TODO(parser/core/integration): in-band BEL (\u0007) byte recognition and dispatch
        audible_bell = ${config.audibleBell}
        # Automatically paste clipboard contents when the middle mouse button is clicked
        paste_on_middle_click = ${config.pasteOnMiddleClick}
        """.trimIndent()

    companion object {
        /**
         * Resolves the default configuration path on disk for this operating system.
         */
        fun getDefaultPath(
            osName: String = System.getProperty("os.name"),
            env: Map<String, String> = System.getenv(),
            userHome: String = System.getProperty("user.home"),
        ): Path {
            // 1. System property override
            val sysProp = System.getProperty("lattice.config.path")
            if (!sysProp.isNullOrBlank()) {
                return Path.of(sysProp)
            }

            // 2. Env variable override
            val envVar = env["LATTICE_CONFIG_PATH"]
            if (!envVar.isNullOrBlank()) {
                return Path.of(envVar)
            }

            // 3. OS-specific default configuration directories
            val os = osName.lowercase(Locale.ROOT)
            return when {
                os.contains("windows") -> {
                    val appData = env["APPDATA"]
                    if (!appData.isNullOrBlank()) {
                        Path.of(appData, "Lattice", "config.toml")
                    } else {
                        Path.of(userHome, ".config", "lattice", "config.toml")
                    }
                }
                os.contains("mac") -> {
                    Path.of(userHome, "Library", "Application Support", "Lattice", "config.toml")
                }
                else -> {
                    val xdgConfig = env["XDG_CONFIG_HOME"]
                    if (!xdgConfig.isNullOrBlank()) {
                        Path.of(xdgConfig, "lattice", "config.toml")
                    } else {
                        Path.of(userHome, ".config", "lattice", "config.toml")
                    }
                }
            }
        }

        /**
         * Returns a manager configured with the OS-specific default configuration path.
         */
        fun getDefault(): TerminalWorkspaceConfigManager = TerminalWorkspaceConfigManager(getDefaultPath())
    }
}
