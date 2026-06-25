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

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import io.github.jvterm.intellij.JvTermBundle
import io.github.jvterm.ui.swing.settings.SwingSettings
import io.github.jvterm.ui.swing.settings.TerminalTheme
import io.github.jvterm.workspace.TerminalProfile
import io.github.jvterm.workspace.TerminalProfileRegistry
import io.github.jvterm.workspace.config.TerminalConfig
import java.awt.Component
import java.util.Locale
import javax.swing.DefaultListCellRenderer
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

private const val JVTERM_SETTINGS_CONFIGURABLE_ID = "io.github.jvterm.terminal.settings"

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
    private val fontFamilyCombo = ComboBox(fontFamilyOptions()).apply { isEditable = false }
    private val fallbackFontFamilyCombo = ComboBox(fontFamilyOptions()).apply { isEditable = false }
    private val fontSizeSpinner = integerSpinner(JvTermIntellijSettings.DEFAULT_FONT_SIZE, TerminalConfig.FONT_SIZE_MIN, TerminalConfig.FONT_SIZE_MAX)
    private val columnsSpinner = spinner(TerminalConfig.DEFAULT_COLUMNS, TerminalConfig.COLUMNS_MIN, TerminalConfig.COLUMNS_MAX)
    private val rowsSpinner = spinner(TerminalConfig.DEFAULT_ROWS, TerminalConfig.ROWS_MIN, TerminalConfig.ROWS_MAX)
    private val scrollbackSpinner =
        spinner(TerminalConfig.DEFAULT_SCROLLBACK_LINES, TerminalConfig.SCROLLBACK_MIN, TerminalConfig.SCROLLBACK_MAX)
    private val cursorBlinkSpinner =
        spinner(TerminalConfig.DEFAULT_CURSOR_BLINK_MILLIS, TerminalConfig.CURSOR_BLINK_MIN, TerminalConfig.CURSOR_BLINK_MAX)
    private val lineHeightSpinner =
        decimalSpinner(
            TerminalConfig.DEFAULT_LINE_HEIGHT.toDouble(),
            TerminalConfig.LINE_HEIGHT_MIN.toDouble(),
            TerminalConfig.LINE_HEIGHT_MAX.toDouble(),
        )
    private val shellPathCombo =
        ComboBox<Any>(shellPathOptions()).apply {
            isEditable = true
            renderer = ShellPathOptionRenderer()
            prototypeDisplayValue = "Windows PowerShell"
        }
    private val startDirectoryField = TextFieldWithBrowseButton()
    private val environmentVariablesField = JBTextField()
    private val defaultTabNameField = JBTextField()
    private val cursorShapeCombo = ComboBox(cursorShapeOptions())
    private val ambiguousWidthCheckBox = JBCheckBox(JvTermBundle.message("settings.jvterm.ambiguousWidth"))
    private val systemFallbackFontsCheckBox = JBCheckBox(JvTermBundle.message("settings.jvterm.systemFallbackFonts"))
    private val visualBellCheckBox = JBCheckBox(JvTermBundle.message("settings.jvterm.visualBell"))
    private val pasteOnMiddleClickCheckBox = JBCheckBox(JvTermBundle.message("settings.jvterm.pasteOnMiddleClick"))

    private val pasteSanitizationCombo = ComboBox(pasteSanitizationOptions())
    private val clipboardLocalWriteCombo = ComboBox(permissionOptions())
    private val clipboardRemoteWriteCombo = ComboBox(permissionOptions())
    private val clipboardReadCombo = ComboBox(permissionOptions())
    private val clipboardMaxDecodedBytesSpinner = spinner(TerminalConfig.DEFAULT_CLIPBOARD_MAX_DECODED_BYTES, 0, Int.MAX_VALUE)
    private val titleLocalPermissionCheckBox = JBCheckBox(JvTermBundle.message("settings.jvterm.titleLocalPermission"))
    private val titleRemotePermissionCheckBox = JBCheckBox(JvTermBundle.message("settings.jvterm.titleRemotePermission"))

    private var panel: JComponent? = null

    init {
        startDirectoryField.addActionListener {
            val chosen =
                FileChooser.chooseFile(
                    FileChooserDescriptorFactory.createSingleFolderDescriptor(),
                    null,
                    null,
                )
            if (chosen != null) {
                startDirectoryField.text = chosen.path
            }
        }
    }

    override fun getId(): String = JVTERM_SETTINGS_CONFIGURABLE_ID

    override fun getDisplayName(): String = JvTermBundle.message("settings.jvterm.displayName")

    override fun createComponent(): JComponent {
        val created =
            panel {
                group(JvTermBundle.message("settings.jvterm.group.session")) {
                    row(JvTermBundle.message("settings.jvterm.shellPath")) {
                        cell(shellPathCombo)
                            .align(AlignX.FILL)
                    }
                    row(JvTermBundle.message("settings.jvterm.startDirectory")) {
                        cell(startDirectoryField)
                            .align(AlignX.FILL)
                            .comment(JvTermBundle.message("settings.jvterm.startDirectory.comment"))
                    }
                    row(JvTermBundle.message("settings.jvterm.environmentVariables")) {
                        cell(environmentVariablesField)
                            .align(AlignX.FILL)
                            .comment(JvTermBundle.message("settings.jvterm.environmentVariables.comment"))
                    }
                    row(JvTermBundle.message("settings.jvterm.defaultTabName")) {
                        cell(defaultTabNameField)
                            .align(AlignX.FILL)
                    }
                }

                group(JvTermBundle.message("settings.jvterm.group.font")) {
                    row {
                        label(JvTermBundle.message("settings.jvterm.fontFamily"))
                        cell(fontFamilyCombo)
                            .align(AlignX.FILL)
                        label(JvTermBundle.message("settings.jvterm.fallbackFontFamily"))
                        cell(fallbackFontFamilyCombo)
                            .align(AlignX.FILL)
                    }
                    row {
                        label(JvTermBundle.message("settings.jvterm.fontSize"))
                        cell(fontSizeSpinner)
                        label(JvTermBundle.message("settings.jvterm.lineHeight"))
                        cell(lineHeightSpinner)
                    }
                    row(JvTermBundle.message("settings.jvterm.theme")) {
                        cell(themeCombo)
                            .align(AlignX.LEFT)
                            .comment(JvTermBundle.message("settings.jvterm.theme.comment"))
                    }
                    row {
                        cell(systemFallbackFontsCheckBox)
                    }
                }

                group(JvTermBundle.message("settings.jvterm.group.application")) {
                    row(JvTermBundle.message("settings.jvterm.initialColumns")) {
                        cell(columnsSpinner)
                    }
                    row(JvTermBundle.message("settings.jvterm.initialRows")) {
                        cell(rowsSpinner)
                    }
                    row(JvTermBundle.message("settings.jvterm.scrollback")) {
                        cell(scrollbackSpinner)
                    }
                    row(JvTermBundle.message("settings.jvterm.cursorShape")) {
                        cell(cursorShapeCombo).align(AlignX.LEFT)
                    }
                    row(JvTermBundle.message("settings.jvterm.cursorBlink")) {
                        cell(cursorBlinkSpinner)
                            .comment(JvTermBundle.message("settings.jvterm.cursorBlink.comment"))
                    }
                }

                group(JvTermBundle.message("settings.jvterm.group.behavior")) {
                    row {
                        cell(ambiguousWidthCheckBox)
                    }
                    row {
                        cell(visualBellCheckBox)
                    }
                    row {
                        cell(pasteOnMiddleClickCheckBox)
                    }
                }

                group(JvTermBundle.message("settings.jvterm.group.security")) {
                    row(JvTermBundle.message("settings.jvterm.pasteSanitization")) {
                        cell(pasteSanitizationCombo).align(AlignX.LEFT)
                    }
                    row(JvTermBundle.message("settings.jvterm.clipboardLocalWrite")) {
                        cell(clipboardLocalWriteCombo).align(AlignX.LEFT)
                    }
                    row(JvTermBundle.message("settings.jvterm.clipboardRemoteWrite")) {
                        cell(clipboardRemoteWriteCombo).align(AlignX.LEFT)
                    }
                    row(JvTermBundle.message("settings.jvterm.clipboardRead")) {
                        cell(clipboardReadCombo).align(AlignX.LEFT)
                    }
                    row(JvTermBundle.message("settings.jvterm.clipboardMaxDecodedBytes")) {
                        cell(clipboardMaxDecodedBytesSpinner)
                    }
                    row {
                        cell(titleLocalPermissionCheckBox)
                    }
                    row {
                        cell(titleRemotePermissionCheckBox)
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
        fontFamilyCombo.selectedItem = normalized.fontFamily
        fallbackFontFamilyCombo.selectedItem = normalized.fallbackFontFamily
        fontSizeSpinner.value = normalized.fontSize
        columnsSpinner.value = normalized.columns
        rowsSpinner.value = normalized.rows
        scrollbackSpinner.value = normalized.scrollbackLines
        cursorBlinkSpinner.value = normalized.cursorBlinkMillis
        lineHeightSpinner.value = normalized.lineHeight.toDouble()
        shellPathCombo.selectedItem = shellPathOptionFor(normalized.shellPath) ?: normalized.shellPath
        startDirectoryField.text = normalized.startDirectory
        environmentVariablesField.text = normalized.environmentVariables
        defaultTabNameField.text = normalized.defaultTabName
        cursorShapeCombo.selectedItem = cursorShapeOptions().first { it.id == normalized.cursorShape }
        ambiguousWidthCheckBox.isSelected = normalized.treatAmbiguousAsWide
        systemFallbackFontsCheckBox.isSelected = normalized.useSystemFallbackFonts
        visualBellCheckBox.isSelected = normalized.visualBell
        pasteOnMiddleClickCheckBox.isSelected = normalized.pasteOnMiddleClick
        pasteSanitizationCombo.selectedItem = pasteSanitizationOptions().firstOrNull { it.id == normalized.pasteSanitization }
        clipboardLocalWriteCombo.selectedItem = permissionOptions().firstOrNull { it.id == normalized.clipboardLocalWrite }
        clipboardRemoteWriteCombo.selectedItem = permissionOptions().firstOrNull { it.id == normalized.clipboardRemoteWrite }
        clipboardReadCombo.selectedItem = permissionOptions().firstOrNull { it.id == normalized.clipboardRead }
        clipboardMaxDecodedBytesSpinner.value = normalized.clipboardMaxDecodedBytes
        titleLocalPermissionCheckBox.isSelected = normalized.titleLocalPermission == "allow"
        titleRemotePermissionCheckBox.isSelected = normalized.titleRemotePermission == "allow"
    }

    private fun uiState(): JvTermIntellijSettings.State =
        JvTermIntellijSettings.State(
            themeId = selectedThemeId(),
            fontFamily = selectedString(fontFamilyCombo),
            fallbackFontFamily = selectedString(fallbackFontFamilyCombo),
            fontSize = spinnerValue(fontSizeSpinner),
            columns = spinnerValue(columnsSpinner),
            rows = spinnerValue(rowsSpinner),
            treatAmbiguousAsWide = ambiguousWidthCheckBox.isSelected,
            cursorBlinkMillis = spinnerValue(cursorBlinkSpinner),
            useSystemFallbackFonts = systemFallbackFontsCheckBox.isSelected,
            cursorShape = selectedCursorShapeId(),
            visualBell = visualBellCheckBox.isSelected,
            pasteOnMiddleClick = pasteOnMiddleClickCheckBox.isSelected,
            scrollbackLines = spinnerValue(scrollbackSpinner),
            lineHeight = spinnerDoubleValue(lineHeightSpinner).toFloat(),
            shellPath = selectedShellPath(),
            startDirectory = startDirectoryField.text.trim(),
            environmentVariables = environmentVariablesField.text.trim(),
            defaultTabName = defaultTabNameField.text.trim(),
            pasteSanitization = (pasteSanitizationCombo.selectedItem as? PasteSanitizationOption)?.id ?: "raw",
            clipboardLocalWrite = (clipboardLocalWriteCombo.selectedItem as? PermissionOption)?.id ?: "prompt",
            clipboardRemoteWrite = (clipboardRemoteWriteCombo.selectedItem as? PermissionOption)?.id ?: "deny",
            clipboardRead = (clipboardReadCombo.selectedItem as? PermissionOption)?.id ?: "deny",
            clipboardMaxDecodedBytes = spinnerValue(clipboardMaxDecodedBytesSpinner),
            titleLocalPermission = if (titleLocalPermissionCheckBox.isSelected) "allow" else "deny",
            titleRemotePermission = if (titleRemotePermissionCheckBox.isSelected) "allow" else "deny",
        )

    private fun selectedThemeId(): String =
        (themeCombo.selectedItem as? ThemeOption)?.id ?: JvTermIntellijSettings.DEFAULT_THEME_ID

    private fun selectedCursorShapeId(): String =
        (cursorShapeCombo.selectedItem as? CursorShapeOption)?.id ?: "block"

    private fun selectedString(comboBox: ComboBox<String>): String = comboBox.selectedItem?.toString()?.trim().orEmpty()

    private fun selectedShellPath(): String {
        val selected = shellPathCombo.selectedItem
        return when (selected) {
            is ShellPathOption -> selected.profile.command.first()
            else -> shellPathCombo.editor.item?.toString()?.trim().orEmpty()
        }
    }

    private fun spinnerValue(spinner: JSpinner): Int = (spinner.value as Number).toInt()

    private fun spinnerDoubleValue(spinner: JSpinner): Double = (spinner.value as Number).toDouble()

    private fun spinner(
        value: Int,
        minimum: Int,
        maximum: Int,
    ): JSpinner = integerSpinner(value, minimum, maximum)

    private fun integerSpinner(
        value: Int,
        minimum: Int,
        maximum: Int,
    ): JSpinner =
        JSpinner(SpinnerNumberModel(value, minimum, maximum, 1)).apply {
            editor = JSpinner.NumberEditor(this, "#")
        }

    private fun decimalSpinner(
        value: Double,
        minimum: Double,
        maximum: Double,
    ): JSpinner =
        JSpinner(SpinnerNumberModel(value, minimum, maximum, 0.1)).apply {
            editor = JSpinner.NumberEditor(this, "0.0")
        }

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

private data class ShellPathOption(
    val profile: TerminalProfile,
) {
    override fun toString(): String = profile.displayName
}

private class ShellPathOptionRenderer : DefaultListCellRenderer() {
    private val profileIcons = JvTermIntellijProfileIcons()

    override fun getListCellRendererComponent(
        list: JList<*>?,
        value: Any?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        val label =
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as JLabel
        when (value) {
            is ShellPathOption -> {
                label.text = value.profile.displayName
                label.icon = profileIcons.icon(value.profile.kind)
            }
            else -> {
                label.text = value?.toString().orEmpty()
                label.icon = null
            }
        }
        return label
    }
}

private fun fontFamilyOptions(): Array<String> =
    LinkedHashSet<String>().apply {
        add(JvTermIntellijSettings.DEFAULT_FONT_FAMILY)
        addAll(SwingSettings.getMonospaceFontFamilies())
    }.toTypedArray()

private fun shellPathOptions(): Array<Any> =
    TerminalProfileRegistry()
        .availableProfiles()
        .map(::ShellPathOption)
        .toTypedArray()

private fun shellPathOptionFor(shellPath: String): ShellPathOption? =
    shellPathOptions()
        .asSequence()
        .filterIsInstance<ShellPathOption>()
        .firstOrNull { option -> option.profile.command.first().shellPathMatches(shellPath) }

private fun String.shellPathMatches(shellPath: String): Boolean {
    if (equals(shellPath, ignoreCase = true)) return true
    val executableName = substringAfterLast('\\').substringAfterLast('/')
    return executableName.equals(shellPath, ignoreCase = true)
}

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

private data class PermissionOption(
    val id: String,
    private val label: String,
) {
    override fun toString(): String = label
}

private fun permissionOptions(): Array<PermissionOption> =
    arrayOf(
        PermissionOption("allow", JvTermBundle.message("settings.jvterm.permission.allow")),
        PermissionOption("prompt", JvTermBundle.message("settings.jvterm.permission.prompt")),
        PermissionOption("allowlist", JvTermBundle.message("settings.jvterm.permission.allowlist")),
        PermissionOption("deny", JvTermBundle.message("settings.jvterm.permission.deny")),
    )



private data class PasteSanitizationOption(
    val id: String,
    private val label: String,
) {
    override fun toString(): String = label
}

private fun pasteSanitizationOptions(): Array<PasteSanitizationOption> =
    arrayOf(
        PasteSanitizationOption("raw", JvTermBundle.message("settings.jvterm.pasteSanitization.raw")),
        PasteSanitizationOption("strip-c0", JvTermBundle.message("settings.jvterm.pasteSanitization.stripC0")),
        PasteSanitizationOption("normalize-line-endings", JvTermBundle.message("settings.jvterm.pasteSanitization.normalize")),
    )
