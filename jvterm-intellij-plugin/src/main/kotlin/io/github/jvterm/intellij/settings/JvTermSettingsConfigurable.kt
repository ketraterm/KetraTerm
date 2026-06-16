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

import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import io.github.jvterm.intellij.JvTermBundle
import io.github.jvterm.ui.swing.settings.TerminalTheme
import io.github.jvterm.workspace.config.TerminalConfig
import java.util.Locale
import javax.swing.JComponent
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

/**
 * IntelliJ-native settings page for IDE-hosted JvTerm terminals.
 *
 * The page intentionally exposes only settings the plugin host can honor.
 * Standalone window-manipulation policy remains outside the IntelliJ UI.
 */
class JvTermSettingsConfigurable : SearchableConfigurable {
    private val settings: JvTermIntellijSettings
        get() = JvTermIntellijSettings.getInstance()

    private val themeCombo = ComboBox(themeOptions())
    private val fontFamilyField = JBTextField()
    private val fontSizeSpinner = spinner(0, 0, TerminalConfig.FONT_SIZE_MAX)
    private val columnsSpinner = spinner(TerminalConfig.DEFAULT_COLUMNS, TerminalConfig.COLUMNS_MIN, TerminalConfig.COLUMNS_MAX)
    private val rowsSpinner = spinner(TerminalConfig.DEFAULT_ROWS, TerminalConfig.ROWS_MIN, TerminalConfig.ROWS_MAX)
    private val scrollbackSpinner =
        spinner(TerminalConfig.DEFAULT_SCROLLBACK_LINES, TerminalConfig.SCROLLBACK_MIN, TerminalConfig.SCROLLBACK_MAX)
    private val cursorBlinkSpinner =
        spinner(TerminalConfig.DEFAULT_CURSOR_BLINK_MILLIS, TerminalConfig.CURSOR_BLINK_MIN, TerminalConfig.CURSOR_BLINK_MAX)
    private val lineHeightSpinner =
        spinner(
            (TerminalConfig.DEFAULT_LINE_HEIGHT * LINE_HEIGHT_SCALE).toInt(),
            (TerminalConfig.LINE_HEIGHT_MIN * LINE_HEIGHT_SCALE).toInt(),
            (TerminalConfig.LINE_HEIGHT_MAX * LINE_HEIGHT_SCALE).toInt(),
        )
    private val cursorShapeCombo = ComboBox(cursorShapeOptions())
    private val ambiguousWidthCheckBox = JBCheckBox(JvTermBundle.message("settings.jvterm.ambiguousWidth"))
    private val systemFallbackFontsCheckBox = JBCheckBox(JvTermBundle.message("settings.jvterm.systemFallbackFonts"))
    private val pasteOnMiddleClickCheckBox = JBCheckBox(JvTermBundle.message("settings.jvterm.pasteOnMiddleClick"))

    private var panel: JComponent? = null

    override fun getId(): String = ID

    override fun getDisplayName(): String = JvTermBundle.message("settings.jvterm.displayName")

    override fun createComponent(): JComponent {
        val created =
            panel {
                group(JvTermBundle.message("settings.jvterm.group.appearance")) {
                    row(JvTermBundle.message("settings.jvterm.theme")) {
                        cell(themeCombo)
                            .align(AlignX.LEFT)
                            .comment(JvTermBundle.message("settings.jvterm.theme.comment"))
                    }
                    row(JvTermBundle.message("settings.jvterm.fontFamily")) {
                        cell(fontFamilyField)
                            .align(AlignX.FILL)
                            .comment(JvTermBundle.message("settings.jvterm.fontFamily.comment"))
                    }
                    row(JvTermBundle.message("settings.jvterm.fontSize")) {
                        cell(fontSizeSpinner)
                            .comment(JvTermBundle.message("settings.jvterm.fontSize.comment"))
                    }
                    row(JvTermBundle.message("settings.jvterm.lineHeight")) {
                        cell(lineHeightSpinner)
                            .comment(JvTermBundle.message("settings.jvterm.lineHeight.comment"))
                    }
                }

                group(JvTermBundle.message("settings.jvterm.group.terminal")) {
                    row(JvTermBundle.message("settings.jvterm.initialColumns")) {
                        cell(columnsSpinner)
                    }
                    row(JvTermBundle.message("settings.jvterm.initialRows")) {
                        cell(rowsSpinner)
                    }
                    row(JvTermBundle.message("settings.jvterm.scrollback")) {
                        cell(scrollbackSpinner)
                    }
                }

                group(JvTermBundle.message("settings.jvterm.group.behavior")) {
                    row(JvTermBundle.message("settings.jvterm.cursorShape")) {
                        cell(cursorShapeCombo).align(AlignX.LEFT)
                    }
                    row(JvTermBundle.message("settings.jvterm.cursorBlink")) {
                        cell(cursorBlinkSpinner)
                            .comment(JvTermBundle.message("settings.jvterm.cursorBlink.comment"))
                    }
                    row {
                        cell(ambiguousWidthCheckBox)
                    }
                    row {
                        cell(systemFallbackFontsCheckBox)
                    }
                    row {
                        cell(pasteOnMiddleClickCheckBox)
                    }
                }
            }
        panel = created
        reset()
        return created
    }

    override fun isModified(): Boolean =
        JvTermIntellijSettingsNormalizer.normalize(uiState()) !=
            JvTermIntellijSettingsNormalizer.normalize(settings.state)

    override fun apply() {
        settings.replaceState(uiState())
    }

    override fun reset() {
        applyState(settings.state)
    }

    override fun disposeUIResources() {
        panel = null
    }

    private fun applyState(state: JvTermIntellijSettings.State) {
        val normalized = JvTermIntellijSettingsNormalizer.normalize(state)
        themeCombo.selectedItem = themeOptions().first { it.id == normalized.themeId }
        fontFamilyField.text = normalized.fontFamily
        fontSizeSpinner.value = normalized.fontSize
        columnsSpinner.value = normalized.columns
        rowsSpinner.value = normalized.rows
        scrollbackSpinner.value = normalized.scrollbackLines
        cursorBlinkSpinner.value = normalized.cursorBlinkMillis
        lineHeightSpinner.value = (normalized.lineHeight * LINE_HEIGHT_SCALE).toInt()
        cursorShapeCombo.selectedItem = cursorShapeOptions().first { it.id == normalized.cursorShape }
        ambiguousWidthCheckBox.isSelected = normalized.treatAmbiguousAsWide
        systemFallbackFontsCheckBox.isSelected = normalized.useSystemFallbackFonts
        pasteOnMiddleClickCheckBox.isSelected = normalized.pasteOnMiddleClick
    }

    private fun uiState(): JvTermIntellijSettings.State =
        JvTermIntellijSettings.State(
            themeId = selectedThemeId(),
            fontFamily = fontFamilyField.text.trim(),
            fontSize = spinnerValue(fontSizeSpinner),
            columns = spinnerValue(columnsSpinner),
            rows = spinnerValue(rowsSpinner),
            treatAmbiguousAsWide = ambiguousWidthCheckBox.isSelected,
            cursorBlinkMillis = spinnerValue(cursorBlinkSpinner),
            useSystemFallbackFonts = systemFallbackFontsCheckBox.isSelected,
            cursorShape = selectedCursorShapeId(),
            pasteOnMiddleClick = pasteOnMiddleClickCheckBox.isSelected,
            scrollbackLines = spinnerValue(scrollbackSpinner),
            lineHeight = spinnerValue(lineHeightSpinner) / LINE_HEIGHT_SCALE.toFloat(),
        )

    private fun selectedThemeId(): String =
        (themeCombo.selectedItem as? ThemeOption)?.id ?: JvTermIntellijSettings.DEFAULT_THEME_ID

    private fun selectedCursorShapeId(): String =
        (cursorShapeCombo.selectedItem as? CursorShapeOption)?.id ?: "block"

    private fun spinnerValue(spinner: JSpinner): Int = (spinner.value as Number).toInt()

    private fun spinner(
        value: Int,
        minimum: Int,
        maximum: Int,
    ): JSpinner =
        JSpinner(SpinnerNumberModel(value, minimum, maximum, 1)).apply {
            editor = JSpinner.NumberEditor(this, "#")
        }

    private data class ThemeOption(
        val id: String,
        private val label: String,
    ) {
        override fun toString(): String = label
    }

    private data class CursorShapeOption(
        val id: String,
        private val label: String,
    ) {
        override fun toString(): String = label
    }

    private companion object {
        private const val ID = "io.github.jvterm.terminal.settings"
        private const val LINE_HEIGHT_SCALE = 100

        private fun themeOptions(): Array<ThemeOption> =
            arrayOf(
                ThemeOption(
                    JvTermIntellijSettings.DEFAULT_THEME_ID,
                    JvTermBundle.message("settings.jvterm.theme.intellij"),
                ),
                *TerminalTheme.entries
                    .map { theme ->
                        ThemeOption(
                            theme.name.lowercase(Locale.ROOT).replace('_', '-'),
                            theme.name
                                .lowercase(Locale.ROOT)
                                .split('_')
                                .joinToString(" ") { part -> part.replaceFirstChar(Char::titlecase) },
                        )
                    }.toTypedArray(),
            )

        private fun cursorShapeOptions(): Array<CursorShapeOption> =
            arrayOf(
                CursorShapeOption("block", JvTermBundle.message("settings.jvterm.cursorShape.block")),
                CursorShapeOption("beam", JvTermBundle.message("settings.jvterm.cursorShape.beam")),
                CursorShapeOption("underline", JvTermBundle.message("settings.jvterm.cursorShape.underline")),
            )
    }
}
