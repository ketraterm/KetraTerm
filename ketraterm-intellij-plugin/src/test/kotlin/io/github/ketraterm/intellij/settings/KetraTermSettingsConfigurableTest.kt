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
package io.github.ketraterm.intellij.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import io.github.ketraterm.host.TerminalClipboardPermission
import io.github.ketraterm.intellij.KetraTermBundle
import io.github.ketraterm.intellij.services.KetraTermCompletionService
import io.github.ketraterm.workspace.TerminalProfile
import java.awt.Color
import java.awt.Component
import java.awt.Container
import javax.swing.AbstractButton
import javax.swing.JComboBox
import javax.swing.JLabel

class KetraTermSettingsConfigurableTest : BasePlatformTestCase() {
    fun testFreshAndLegacyClipboardReadChoicesSurviveUnrelatedApplyAndFormReset() {
        val settings = KetraTermIntellijSettings.getInstance()
        val original = settings.state
        try {
            for ((state, expectedLabel) in listOf(
                KetraTermIntellijSettings().state to "Ask",
                KetraTermIntellijSettings.State() to "Deny",
            )) {
                settings.loadState(state)
                val configurable = KetraTermSettingsConfigurable(emptyList())
                try {
                    val component = configurable.createComponent()
                    val readPermission =
                        descendants(component)
                            .filterIsInstance<JComboBox<*>>()
                            .last { it.selectedItem?.toString() in listOf("Deny", "Ask", "Allow") }
                    assertEquals(expectedLabel, readPermission.selectedItem?.toString())
                    descendants(component)
                        .filterIsInstance<AbstractButton>()
                        .first { it.text == KetraTermBundle.message("settings.ketraterm.visualBell") }
                        .apply { isSelected = !isSelected }
                    configurable.apply()
                    assertEquals(state.clipboardRead, settings.state.clipboardRead)
                    readPermission.selectedIndex = 2
                    assertTrue(configurable.isModified())
                    configurable.reset()
                    assertEquals(expectedLabel, readPermission.selectedItem?.toString())
                    assertFalse(configurable.isModified())
                } finally {
                    configurable.disposeUIResources()
                }
            }
        } finally {
            settings.replaceState(original)
        }
    }

    fun testProcessTitleCheckboxAppliesAndResetsWithoutChangingOtherSettings() {
        val settings = KetraTermIntellijSettings.getInstance()
        val original = settings.state
        val configurable = KetraTermSettingsConfigurable(emptyList())
        try {
            settings.loadState(original.copy(showForegroundProcessName = true))
            val baseline = settings.state
            val checkbox =
                descendants(configurable.createComponent()).filterIsInstance<AbstractButton>().single {
                    it.text == KetraTermBundle.message("settings.ketraterm.showForegroundProcessName")
                }
            assertTrue(checkbox.isSelected)
            assertFalse(configurable.isModified())
            checkbox.isSelected = false
            assertTrue(configurable.isModified())
            configurable.apply()
            assertEquals(baseline.copy(showForegroundProcessName = false), settings.state)
            assertFalse(configurable.isModified())
            checkbox.isSelected = true
            configurable.reset()
            assertFalse(checkbox.isSelected)
            assertFalse(configurable.isModified())
        } finally {
            configurable.disposeUIResources()
            settings.replaceState(original)
        }
    }

    fun testPasteHandlingChoicesApplyAndResetWithoutChangingOtherSettings() {
        val settings = KetraTermIntellijSettings.getInstance()
        val original = settings.state
        val configurable = KetraTermSettingsConfigurable(emptyList())
        try {
            settings.loadState(original.copy(pasteSanitization = "raw"))
            val baseline = settings.state
            val component = configurable.createComponent()
            val preserveLabel = KetraTermBundle.message("settings.ketraterm.pasteSanitization.preserve")
            val combo =
                descendants(component)
                    .filterIsInstance<JComboBox<*>>()
                    .single { it.selectedItem?.toString() == preserveLabel }
            val choices =
                listOf(
                    "strip-c0" to KetraTermBundle.message("settings.ketraterm.pasteSanitization.stripC0"),
                    "preserve" to preserveLabel,
                )
            assertEquals(choices.size, combo.itemCount)
            assertFalse(configurable.isModified())
            for ((id, label) in choices) {
                combo.selectedIndex = (0 until combo.itemCount).single { combo.getItemAt(it).toString() == label }
                assertTrue(configurable.isModified())
                configurable.apply()
                assertEquals(baseline.copy(pasteSanitization = id), settings.state)
                assertFalse(configurable.isModified())
                combo.selectedIndex = (combo.selectedIndex + 1) % combo.itemCount
                configurable.reset()
                assertEquals(label, combo.selectedItem?.toString())
                assertFalse(configurable.isModified())
            }
        } finally {
            configurable.disposeUIResources()
            settings.replaceState(original)
        }
    }

    fun testProjectJdkCheckboxAppliesResetsAndPreservesEnvironmentSettings() {
        val settings = KetraTermIntellijSettings.getInstance()
        val original = settings.state
        val configurable = KetraTermSettingsConfigurable(emptyList())
        try {
            val configured = original.copy(addProjectJdkToPath = true, environmentVariables = "JAVA_HOME=/custom/jdk")
            settings.loadState(configured)
            val component = configurable.createComponent()
            val checkbox =
                descendants(component)
                    .filterIsInstance<AbstractButton>()
                    .single { it.text == KetraTermBundle.message("settings.ketraterm.addProjectJdkToPath") }
            assertTrue(checkbox.isSelected)
            assertFalse(configurable.isModified())

            checkbox.isSelected = false
            assertTrue(configurable.isModified())
            configurable.apply()
            assertEquals(configured.copy(addProjectJdkToPath = false), settings.state)
            assertFalse(configurable.isModified())

            checkbox.isSelected = true
            configurable.reset()
            assertFalse(checkbox.isSelected)
            assertFalse(configurable.isModified())
        } finally {
            configurable.disposeUIResources()
            settings.replaceState(original)
        }
    }

    fun testHiddenSuggestionPreferencesSurviveApply() {
        val settings = KetraTermIntellijSettings.getInstance()
        val original = settings.state
        val configurable = KetraTermSettingsConfigurable()
        try {
            settings.replaceState(
                original.copy(
                    smartSuggestionsEnabled = true,
                    shellSuggestionsEnabled = false,
                    acceptSelectedSuggestionWithEnter = false,
                    completionLearningPersistenceEnabled = true,
                ),
            )
            val component = configurable.createComponent()
            configurable.reset()
            val texts =
                descendants(component).mapNotNull {
                    when (it) {
                        is AbstractButton -> it.text
                        is JLabel -> it.text
                        else -> null
                    }
                }
            assertFalse(texts.any { it.contains("suggest", ignoreCase = true) || it.contains("learning", ignoreCase = true) })
            configurable.apply()
            assertTrue(settings.state.smartSuggestionsEnabled)
            assertFalse(settings.state.shellSuggestionsEnabled)
            assertFalse(settings.state.acceptSelectedSuggestionWithEnter)
            assertTrue(settings.state.completionLearningPersistenceEnabled)
        } finally {
            configurable.disposeUIResources()
            settings.replaceState(original)
        }
    }

    fun testPersistencePreferenceIsEffectiveOnlyWithMasterEnabled() {
        val settings = KetraTermIntellijSettings.getInstance()
        val original = settings.state
        try {
            settings.replaceState(original.copy(smartSuggestionsEnabled = false, completionLearningPersistenceEnabled = true))
            assertFalse(settings.completionLearningPersistenceEnabled())
            settings.replaceState(settings.state.copy(smartSuggestionsEnabled = true))
            assertTrue(settings.completionLearningPersistenceEnabled())
        } finally {
            settings.replaceState(original)
        }
    }

    fun testConfiguredMissingFontsSurviveUnrelatedApply() {
        val settings = KetraTermIntellijSettings.getInstance()
        val original = settings.state
        val configurable = KetraTermSettingsConfigurable()
        try {
            settings.loadState(
                original.copy(
                    fontFamily = "KetraTerm Missing Primary Font",
                    fallbackFontFamily = "KetraTerm Missing Fallback Font",
                ),
            )
            val loaded = settings.state
            val component = configurable.createComponent()
            assertFalse(configurable.isModified())
            configurable.apply()
            assertEquals(loaded, settings.state)

            descendants(component)
                .filterIsInstance<AbstractButton>()
                .first { it.text == KetraTermBundle.message("settings.ketraterm.visualBell") }
                .apply { isSelected = !isSelected }
            configurable.apply()
            assertEquals(loaded.copy(visualBell = !loaded.visualBell), settings.state)
            configurable.reset()
            assertFalse(configurable.isModified())
        } finally {
            configurable.disposeUIResources()
            settings.replaceState(original)
        }
    }

    fun testShellPathAliasRemainsUnchangedWhenApplyingUnrelatedSettings() {
        val settings = KetraTermIntellijSettings.getInstance()
        val original = settings.state
        val configurable =
            KetraTermSettingsConfigurable(
                listOf(
                    TerminalProfile(
                        id = "powershell",
                        displayName = "PowerShell",
                        command = listOf("C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe"),
                    ),
                ),
            )
        try {
            settings.loadState(original.copy(shellPath = "powershell.exe"))
            val loaded = settings.state
            val component = configurable.createComponent()
            assertFalse(configurable.isModified())
            configurable.apply()
            assertEquals(loaded, settings.state)

            descendants(component)
                .filterIsInstance<AbstractButton>()
                .first { it.text == KetraTermBundle.message("settings.ketraterm.visualBell") }
                .apply { isSelected = !isSelected }
            configurable.apply()
            assertEquals(loaded.copy(visualBell = !loaded.visualBell), settings.state)
            configurable.reset()
            assertFalse(configurable.isModified())
        } finally {
            configurable.disposeUIResources()
            settings.replaceState(original)
        }
    }

    fun testRemovedClipboardModeFallsBackAndSettingsOfferOnlySupportedChoices() {
        val settings = KetraTermIntellijSettings.getInstance()
        val original = settings.state
        val configurable = KetraTermSettingsConfigurable()
        try {
            settings.loadState(original.copy(clipboardWrite = "allowlist", clipboardRead = "allowlist"))
            val component = configurable.createComponent()
            assertFalse(configurable.isModified())
            val expectedLabels = listOf("Deny", "Ask", "Allow")
            val permissionCombos =
                descendants(component)
                    .filterIsInstance<JComboBox<*>>()
                    .filter { it.selectedItem?.toString() in expectedLabels }
                    .toList()
            assertEquals(2, permissionCombos.size)
            for (combo in permissionCombos) {
                assertEquals(expectedLabels, (0 until combo.itemCount).map { combo.getItemAt(it).toString() })
            }
            assertEquals("Allow", permissionCombos[0].selectedItem?.toString())
            assertEquals("Deny", permissionCombos[1].selectedItem?.toString())
            descendants(component)
                .filterIsInstance<AbstractButton>()
                .first { it.text == KetraTermBundle.message("settings.ketraterm.visualBell") }
                .apply { isSelected = !isSelected }
            configurable.apply()
            assertEquals("allow", settings.state.clipboardWrite)
            assertEquals("deny", settings.state.clipboardRead)
            assertEquals(TerminalClipboardPermission.ALLOW, settings.createHostPolicy().clipboardPolicy.writePermission)

            permissionCombos[0].selectedIndex = 1
            configurable.apply()
            assertEquals("prompt", settings.state.clipboardWrite)
            assertEquals("deny", settings.state.clipboardRead)
            permissionCombos[0].selectedIndex = 0
            configurable.reset()
            assertEquals("Ask", permissionCombos[0].selectedItem?.toString())
            assertFalse(configurable.isModified())
        } finally {
            configurable.disposeUIResources()
            settings.replaceState(original)
        }
    }

    fun testPlatformReloadReachesExistingCompletionService() {
        val settings = KetraTermIntellijSettings.getInstance()
        val original = settings.state
        val completion = KetraTermCompletionService.getInstance()
        var resourceChanges = 0
        val listener: () -> Unit = { resourceChanges++ }
        completion.addResourceListener(listener)
        try {
            settings.loadState(original.copy(smartSuggestionsEnabled = !original.smartSuggestionsEnabled))
            UIUtil.dispatchAllInvocationEvents()
            assertEquals(1, resourceChanges)
            settings.loadState(settings.state)
            UIUtil.dispatchAllInvocationEvents()
            assertEquals(1, resourceChanges)
        } finally {
            completion.removeResourceListener(listener)
            settings.replaceState(original)
        }
    }

    // Keep explicit setters: globalScheme is read-only in Kotlin against the IntelliJ 2026.2 API.
    @Suppress("UsePropertyAccessSyntax")
    fun testCurrentSnapshotUsesUpdatedEditorColorsOnlyWhenFollowingIde() {
        val settings = KetraTermIntellijSettings.getInstance()
        val original = settings.state
        val colors = EditorColorsManager.getInstance()
        val originalScheme = colors.globalScheme
        val nextScheme = originalScheme.clone() as EditorColorsScheme
        try {
            settings.replaceState(original.copy(themeId = "nord"))
            val builtInPalette = settings.current().palette
            nextScheme.setColor(EditorColors.CARET_COLOR, Color.MAGENTA)
            ApplicationManager.getApplication().runWriteAction { colors.setGlobalScheme(nextScheme) }
            UIUtil.dispatchAllInvocationEvents()
            assertEquals(builtInPalette.cursorBackground, settings.current().palette.cursorBackground)

            settings.replaceState(settings.state.copy(themeId = KetraTermIntellijSettings.DEFAULT_THEME_ID))
            assertEquals(Color.MAGENTA.rgb, settings.current().palette.cursorBackground)
        } finally {
            ApplicationManager.getApplication().runWriteAction { colors.setGlobalScheme(originalScheme) }
            settings.replaceState(original)
        }
    }

    private fun descendants(component: Component): Sequence<Component> =
        sequence {
            yield(component)
            if (component is Container) component.components.forEach { yieldAll(descendants(it)) }
        }
}
