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
package io.github.jvterm.intellij.settings

import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.SerializablePersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SettingsCategory
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.ui.JBFont
import io.github.jvterm.render.api.TerminalRenderCursorShape
import io.github.jvterm.ui.swing.settings.SwingSettings
import io.github.jvterm.ui.swing.settings.TerminalTheme
import io.github.jvterm.workspace.config.TerminalConfig
import java.awt.Font
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

private val TerminalTheme.id: String
    get() = name.lowercase(Locale.ROOT).replace('_', '-')

/**
 * Application-level IntelliJ settings service for IDE-hosted JvTerm terminals.
 *
 * IntelliJ persists this service through its native XML persistence model. The
 * standalone application keeps using TOML through `jvterm-workspace`; this
 * class is the IntelliJ-only adapter that converts persisted IDE state into a
 * reusable [SwingSettings] snapshot.
 */
@Service(Service.Level.APP)
@State(
    name = "JvTermIntellijSettings",
    storages = [Storage(value = "jvterm.xml", roamingType = RoamingType.DEFAULT)],
    category = SettingsCategory.TOOLS,
)
class JvTermIntellijSettings :
    SerializablePersistentStateComponent<JvTermIntellijSettings.State>(State()) {
    private val changeListeners = CopyOnWriteArrayList<() -> Unit>()

    /**
     * Returns the current immutable settings snapshot consumed by `SwingTerminal`.
     *
     * @return immutable Swing terminal settings.
     */
    fun current(): SwingSettings = JvTermIntellijSettingsMapper.toSwingSettings(state)

    /**
     * Replaces persisted IDE terminal settings with a normalized state.
     *
     * @param nextState new settings state produced by the IntelliJ settings UI.
     */
    fun replaceState(nextState: State) {
        val normalized = JvTermIntellijSettingsNormalizer.normalize(nextState)
        val oldState = state
        if (normalized == oldState) return
        updateState { normalized }
        for (listener in changeListeners) {
            listener()
        }
    }

    /**
     * Registers a listener notified after settings are changed through this service.
     *
     * @param listener callback invoked on the caller thread that applied settings.
     */
    fun addChangeListener(listener: () -> Unit) {
        changeListeners += listener
    }

    /**
     * Removes a previously registered settings listener.
     *
     * @param listener callback to remove.
     */
    fun removeChangeListener(listener: () -> Unit) {
        changeListeners -= listener
    }

    /**
     * Persistent XML state for IntelliJ-hosted terminal preferences.
     *
     * @property themeId `intellij` for IDE-derived colors, or a built-in theme id.
     * @property fontFamily terminal font family.
     * @property fallbackFontFamily preferred complex-text fallback font family.
     * @property fontSize terminal font size in points.
     * @property columns preferred initial terminal columns.
     * @property rows preferred initial terminal rows.
     * @property treatAmbiguousAsWide East Asian Ambiguous width policy.
     * @property cursorBlinkMillis cursor blink period; zero disables blinking.
     * @property useSystemFallbackFonts whether renderer may scan installed fonts.
     * @property cursorShape default cursor shape id.
     * @property pasteOnMiddleClick whether the middle click pastes clipboard text.
     * @property scrollbackLines maximum retained scrollback rows.
     * @property lineHeight font metric line-height multiplier.
     * @property shellPath command or executable path used for new local shells.
     * @property startDirectory initial working directory; blank means project root.
     * @property environmentVariables newline-separated `NAME=VALUE` environment entries.
     * @property defaultTabName user-visible name for newly opened tabs.
     */
    data class State(
        @JvmField val themeId: String = DEFAULT_THEME_ID,
        @JvmField val fontFamily: String = DEFAULT_FONT_FAMILY,
        @JvmField val fallbackFontFamily: String = DEFAULT_FONT_FAMILY,
        @JvmField val fontSize: Int = DEFAULT_FONT_SIZE,
        @JvmField val columns: Int = TerminalConfig.DEFAULT_COLUMNS,
        @JvmField val rows: Int = TerminalConfig.DEFAULT_ROWS,
        @JvmField val treatAmbiguousAsWide: Boolean = TerminalConfig.DEFAULT_TREAT_AMBIGUOUS_AS_WIDE,
        @JvmField val cursorBlinkMillis: Int = TerminalConfig.DEFAULT_CURSOR_BLINK_MILLIS,
        @JvmField val useSystemFallbackFonts: Boolean = TerminalConfig.DEFAULT_USE_SYSTEM_FALLBACK_FONTS,
        @JvmField val cursorShape: String = TerminalConfig.DEFAULT_CURSOR_SHAPE,
        @JvmField val pasteOnMiddleClick: Boolean = TerminalConfig.DEFAULT_PASTE_ON_MIDDLE_CLICK,
        @JvmField val scrollbackLines: Int = TerminalConfig.DEFAULT_SCROLLBACK_LINES,
        @JvmField val lineHeight: Float = TerminalConfig.DEFAULT_LINE_HEIGHT,
        @JvmField val shellPath: String = TerminalConfig.DEFAULT_SHELL_PATH,
        @JvmField val startDirectory: String = "",
        @JvmField val environmentVariables: String = "",
        @JvmField val defaultTabName: String = "Local",
    )

    companion object {
        /**
         * Theme id that derives colors from the active IntelliJ editor scheme.
         */
        const val DEFAULT_THEME_ID: String = "intellij"

        /**
         * Default IDE terminal font size.
         */
        const val DEFAULT_FONT_SIZE: Int = 13

        /**
         * Default IDE terminal font family.
         */
        const val DEFAULT_FONT_FAMILY: String = "JetBrains Mono"

        /**
         * Returns the application-level JvTerm settings service.
         *
         * @return IntelliJ settings service.
         */
        fun getInstance(): JvTermIntellijSettings = service()

        /**
         * Returns the current Swing settings snapshot from the application service.
         *
         * @return immutable Swing terminal settings.
         */
        fun current(): SwingSettings = getInstance().current()

        internal fun normalizeThemeId(themeId: String): String {
            val normalized = themeId.trim().lowercase(Locale.ROOT)
            if (normalized == DEFAULT_THEME_ID) return DEFAULT_THEME_ID
            return TerminalTheme.entries
                .firstOrNull { it.id == normalized }
                ?.id
                ?: DEFAULT_THEME_ID
        }
    }
}

/**
 * Normalizes persisted IntelliJ settings before they are saved or mapped.
 */
internal object JvTermIntellijSettingsNormalizer {
    /**
     * Returns a state with validated bounds and canonical identifiers.
     *
     * @param state persisted or UI-produced state.
     * @return normalized state safe to persist.
     */
    fun normalize(state: JvTermIntellijSettings.State): JvTermIntellijSettings.State =
        state.copy(
            themeId = JvTermIntellijSettings.normalizeThemeId(state.themeId),
            fontFamily = normalizeFontFamily(state.fontFamily),
            fallbackFontFamily = normalizeFontFamily(state.fallbackFontFamily),
            fontSize =
                state.fontSize.coerceIn(TerminalConfig.FONT_SIZE_MIN, TerminalConfig.FONT_SIZE_MAX),
            columns = state.columns.coerceIn(TerminalConfig.COLUMNS_MIN, TerminalConfig.COLUMNS_MAX),
            rows = state.rows.coerceIn(TerminalConfig.ROWS_MIN, TerminalConfig.ROWS_MAX),
            cursorBlinkMillis = state.cursorBlinkMillis.coerceIn(
                TerminalConfig.CURSOR_BLINK_MIN,
                TerminalConfig.CURSOR_BLINK_MAX,
            ),
            cursorShape = normalizeCursorShape(state.cursorShape),
            scrollbackLines = state.scrollbackLines.coerceIn(
                TerminalConfig.SCROLLBACK_MIN,
                TerminalConfig.SCROLLBACK_MAX,
            ),
            lineHeight = coerceLineHeight(state.lineHeight),
            shellPath = state.shellPath.trim().ifBlank { TerminalConfig.DEFAULT_SHELL_PATH },
            startDirectory = state.startDirectory.trim(),
            environmentVariables = normalizeEnvironmentText(state.environmentVariables),
            defaultTabName = state.defaultTabName.trim().ifBlank { "Local" },
        )

    /**
     * Parses newline-separated environment entries.
     *
     * @param text environment text using `NAME=VALUE` entries separated by newlines.
     * @return parsed environment map.
     */
    fun parseEnvironmentVariables(text: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val separator = trimmed.indexOf('=')
            if (separator <= 0) continue
            val name = trimmed.substring(0, separator).trim()
            if (name.isEmpty()) continue
            result[name] = trimmed.substring(separator + 1)
        }
        return result
    }

    private fun normalizeFontFamily(fontFamily: String): String =
        fontFamily.trim().ifBlank { JvTermIntellijSettings.DEFAULT_FONT_FAMILY }

    private fun normalizeEnvironmentText(text: String): String =
        parseEnvironmentVariables(text)
            .entries
            .joinToString("\n") { (key, value) -> "$key=$value" }

    private fun normalizeCursorShape(shape: String): String =
        when (shape.lowercase(Locale.ROOT)) {
            "beam", "bar" -> "beam"
            "underline" -> "underline"
            else -> "block"
        }

    private fun coerceLineHeight(lineHeight: Float): Float =
        if (lineHeight.isFinite()) {
            lineHeight.coerceIn(TerminalConfig.LINE_HEIGHT_MIN, TerminalConfig.LINE_HEIGHT_MAX)
        } else {
            TerminalConfig.DEFAULT_LINE_HEIGHT
        }
}

/**
 * Converts IntelliJ persisted state into host-neutral Swing terminal settings.
 */
internal object JvTermIntellijSettingsMapper {
    /**
     * Creates a Swing settings snapshot from [state].
     *
     * @param state persisted IntelliJ settings state.
     * @return immutable Swing terminal settings.
     */
    fun toSwingSettings(state: JvTermIntellijSettings.State): SwingSettings {
        val normalized = JvTermIntellijSettingsNormalizer.normalize(state)
        val fontSize = normalized.fontSize
        val fontFamily = SwingSettings.resolveFontFamily(normalized.fontFamily)
        val fallbackFontFamily = SwingSettings.resolveFontFamily(normalized.fallbackFontFamily)

        return SwingSettings(
            font = JBFont.create(Font(fontFamily, Font.PLAIN, fontSize)),
            fallbackFonts = listOf(Font(fallbackFontFamily, Font.PLAIN, fontSize)),
            columns = normalized.columns,
            rows = normalized.rows,
            palette = paletteForThemeId(normalized.themeId),
            treatAmbiguousAsWide = normalized.treatAmbiguousAsWide,
            cursorBlinkMillis = normalized.cursorBlinkMillis,
            useSystemFallbackFonts = normalized.useSystemFallbackFonts,
            pasteOnMiddleClick = normalized.pasteOnMiddleClick,
            cursorShape = parseCursorShape(normalized.cursorShape),
            scrollbackLines = normalized.scrollbackLines,
            lineHeight = normalized.lineHeight,
            shellRequestResizeWindow = false,
            shellRequestWindowManipulation = false,
        )
    }

    private fun paletteForThemeId(themeId: String) =
        when (val normalized = JvTermIntellijSettings.normalizeThemeId(themeId)) {
            JvTermIntellijSettings.DEFAULT_THEME_ID -> JvTermIntellijThemePalette.currentIntellijPalette()
            else -> TerminalTheme.entries.first { it.id == normalized }.createPalette()
        }

    private fun parseCursorShape(shape: String): TerminalRenderCursorShape =
        when (shape.lowercase(Locale.ROOT)) {
            "beam", "bar" -> TerminalRenderCursorShape.BAR
            "underline" -> TerminalRenderCursorShape.UNDERLINE
            else -> TerminalRenderCursorShape.BLOCK
        }
}
