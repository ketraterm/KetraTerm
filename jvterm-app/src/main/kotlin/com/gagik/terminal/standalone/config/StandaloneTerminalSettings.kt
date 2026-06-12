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
package com.gagik.terminal.standalone.config

import com.gagik.terminal.render.api.TerminalRenderCursorShape
import com.gagik.terminal.ui.swing.settings.TerminalSwingSettings
import com.gagik.terminal.ui.swing.settings.TerminalTheme
import com.gagik.terminal.workspace.config.TerminalConfig
import com.gagik.terminal.workspace.config.TerminalWorkspaceConfigManager
import java.awt.Font
import java.util.*

/**
 * Standalone application settings model integrated with TOML configuration.
 *
 * It acts as the bridge between host-neutral persisted [TerminalConfig] and
 * host-specific Swing settings. Changes to configuration parameters are immediately
 * saved to disk.
 */
internal class StandaloneTerminalSettings(
    private val configManager: TerminalWorkspaceConfigManager = TerminalWorkspaceConfigManager.getDefault(),
) {
    private var config: TerminalConfig = configManager.load()

    var theme: TerminalTheme
        get() =
            TerminalTheme.entries.firstOrNull {
                it.name.lowercase(Locale.ROOT).replace('_', '-') == config.theme
            } ?: TerminalTheme.ONE_DARK
        set(value) {
            updateConfig(config.copy(theme = value.name.lowercase(Locale.ROOT).replace('_', '-')))
        }

    var treatAmbiguousAsWide: Boolean
        get() = config.treatAmbiguousAsWide
        set(value) {
            updateConfig(config.copy(treatAmbiguousAsWide = value))
        }

    var columns: Int
        get() = config.columns
        set(value) {
            updateConfig(config.copy(columns = value))
        }

    var rows: Int
        get() = config.rows
        set(value) {
            updateConfig(config.copy(rows = value))
        }

    var fontFamily: String
        get() = config.fontFamily
        set(value) {
            updateConfig(config.copy(fontFamily = value))
        }

    var fontSize: Int
        get() = config.fontSize
        set(value) {
            updateConfig(config.copy(fontSize = value))
        }

    var useSystemFallbackFonts: Boolean
        get() = config.useSystemFallbackFonts
        set(value) {
            updateConfig(config.copy(useSystemFallbackFonts = value))
        }

    var cursorBlinkMillis: Int
        get() = config.cursorBlinkMillis
        set(value) {
            updateConfig(config.copy(cursorBlinkMillis = value))
        }

    var cursorShape: String
        get() = config.cursorShape
        set(value) {
            updateConfig(config.copy(cursorShape = value))
        }

    var shellPath: String
        get() = config.shellPath
        set(value) {
            updateConfig(config.copy(shellPath = value))
        }

    var startDirectory: String
        get() = config.startDirectory
        set(value) {
            updateConfig(config.copy(startDirectory = value))
        }

    var audibleBell: Boolean
        get() = config.audibleBell
        set(value) {
            updateConfig(config.copy(audibleBell = value))
        }

    var pasteOnMiddleClick: Boolean
        get() = config.pasteOnMiddleClick
        set(value) {
            updateConfig(config.copy(pasteOnMiddleClick = value))
        }

    var scrollbackLines: Int
        get() = config.scrollbackLines
        set(value) {
            updateConfig(config.copy(scrollbackLines = value))
        }

    var lineHeight: Float
        get() = config.lineHeight
        set(value) {
            updateConfig(config.copy(lineHeight = value))
        }

    var shellRequestResizeWindow: Boolean
        get() = config.shellRequestResizeWindow
        set(value) {
            updateConfig(config.copy(shellRequestResizeWindow = value))
        }

    fun current(): TerminalSwingSettings =
        TerminalSwingSettings(
            font = Font(config.fontFamily, Font.PLAIN, config.fontSize),
            columns = config.columns,
            rows = config.rows,
            palette = theme.createPalette(),
            treatAmbiguousAsWide = config.treatAmbiguousAsWide,
            cursorBlinkMillis = config.cursorBlinkMillis,
            useSystemFallbackFonts = config.useSystemFallbackFonts,
            pasteOnMiddleClick = config.pasteOnMiddleClick,
            cursorShape = parseCursorShape(config.cursorShape),
            scrollbackLines = config.scrollbackLines,
            lineHeight = config.lineHeight,
            shellRequestResizeWindow = config.shellRequestResizeWindow,
        )

    private fun parseCursorShape(shape: String): TerminalRenderCursorShape =
        when (shape.lowercase(Locale.ROOT)) {
            "beam" -> TerminalRenderCursorShape.BAR
            "underline" -> TerminalRenderCursorShape.UNDERLINE
            else -> TerminalRenderCursorShape.BLOCK
        }

    private fun updateConfig(newConfig: TerminalConfig) {
        config = newConfig
        configManager.save(newConfig)
    }
}
