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

/**
 * Host-neutral configuration settings for the terminal emulator.
 *
 * This data class is immutable. For updates, a new instance is created via [copy].
 * The settings are serialized to/from a TOML configuration file on disk.
 *
 * All numeric bounds are published in the [Limits] companion object so that the UI
 * layer, the TOML config manager, and the data class validation all share one source
 * of truth instead of repeating magic numbers.
 *
 * @property theme built-in color theme id, such as `one-dark` or `nord`.
 * @property treatAmbiguousAsWide whether East Asian Ambiguous characters occupy
 * two cells in core width policy.
 * @property fontFamily primary terminal font family name.
 * @property fontSize primary terminal font size in points.
 * @property columns preferred initial terminal columns.
 * @property rows preferred initial terminal rows.
 * @property cursorBlinkMillis cursor blink period in milliseconds. Zero disables blinking.
 * @property useSystemFallbackFonts whether Swing rendering may scan installed
 * system fonts after configured fallback fonts fail.
 * @property cursorShape default cursor shape id: `block`, `underline`, or `beam`.
 * @property shellPath command or executable path used when opening a local shell.
 * @property startDirectory initial working directory for newly opened shells.
 * @property audibleBell whether host UI should play a system bell for BEL events.
 * @property pasteOnMiddleClick whether middle mouse click should paste clipboard text.
 * @property scrollbackLines maximum retained scrollback lines.
 * @property lineHeight font metric line-height multiplier.
 * @property windowOpacity requested host window opacity.
 */
data class TerminalConfig(
    val theme: String = "one-dark",
    val treatAmbiguousAsWide: Boolean = false,
    val fontFamily: String = "Cascadia Mono",
    val fontSize: Int = 16,
    val columns: Int = 100,
    val rows: Int = 30,
    val cursorBlinkMillis: Int = 600,
    val useSystemFallbackFonts: Boolean = false,
    val cursorShape: String = "block",
    val shellPath: String = "powershell.exe",
    val startDirectory: String = System.getProperty("user.home"),
    val audibleBell: Boolean = true,
    val pasteOnMiddleClick: Boolean = true,
    val scrollbackLines: Int = 1000,
    val lineHeight: Float = 1.0f,
    val windowOpacity: Float = 1.0f,
) {
    init {
        require(columns in COLUMNS_MIN..COLUMNS_MAX) {
            "columns must be in ${COLUMNS_MIN}..${COLUMNS_MAX}, was $columns"
        }
        require(rows in ROWS_MIN..ROWS_MAX) {
            "rows must be in ${ROWS_MIN}..${ROWS_MAX}, was $rows"
        }
        require(fontSize in FONT_SIZE_MIN..FONT_SIZE_MAX) {
            "fontSize must be in ${FONT_SIZE_MIN}..${FONT_SIZE_MAX}, was $fontSize"
        }
        require(cursorBlinkMillis in CURSOR_BLINK_MIN..CURSOR_BLINK_MAX) {
            "cursorBlinkMillis must be in ${CURSOR_BLINK_MIN}..${CURSOR_BLINK_MAX}, was $cursorBlinkMillis"
        }
        require(theme.isNotBlank()) { "theme must not be blank" }
        require(fontFamily.isNotBlank()) { "fontFamily must not be blank" }
        require(cursorShape.isNotBlank()) { "cursorShape must not be blank" }
        require(shellPath.isNotBlank()) { "shellPath must not be blank" }
        require(scrollbackLines in SCROLLBACK_MIN..SCROLLBACK_MAX) {
            "scrollbackLines must be in ${SCROLLBACK_MIN}..${SCROLLBACK_MAX}, was $scrollbackLines"
        }
        require(lineHeight in LINE_HEIGHT_MIN..LINE_HEIGHT_MAX) {
            "lineHeight must be in ${LINE_HEIGHT_MIN}..${LINE_HEIGHT_MAX}, was $lineHeight"
        }
        require(windowOpacity in WINDOW_OPACITY_MIN..WINDOW_OPACITY_MAX) {
            "windowOpacity must be in ${WINDOW_OPACITY_MIN}..${WINDOW_OPACITY_MAX}, was $windowOpacity"
        }
    }

    /**
     * Published bounds for all numeric terminal configuration fields.
     *
     * These constants are the single source of truth shared by:
     * - [TerminalConfig.init] validity assertions.
     * - [TerminalWorkspaceConfigManager] TOML clamping before constructing a config.
     * - The standalone settings dialog spinner min/max values.
     *
     * If you need to change a limit, change it here and it will be reflected
     * everywhere automatically.
     */
    companion object Limits {
        const val COLUMNS_MIN: Int = 10
        const val COLUMNS_MAX: Int = 1000

        const val ROWS_MIN: Int = 10
        const val ROWS_MAX: Int = 500

        const val FONT_SIZE_MIN: Int = 10
        const val FONT_SIZE_MAX: Int = 56

        /** Zero disables blinking. */
        const val CURSOR_BLINK_MIN: Int = 0
        const val CURSOR_BLINK_MAX: Int = 10_000

        const val SCROLLBACK_MIN: Int = 0
        const val SCROLLBACK_MAX: Int = 1_000_000

        const val LINE_HEIGHT_MIN: Float = 0.7f
        const val LINE_HEIGHT_MAX: Float = 1.5f

        const val WINDOW_OPACITY_MIN: Float = 0.1f
        const val WINDOW_OPACITY_MAX: Float = 1.0f
    }
}
