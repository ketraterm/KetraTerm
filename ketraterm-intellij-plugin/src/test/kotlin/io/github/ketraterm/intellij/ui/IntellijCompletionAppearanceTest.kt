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
package io.github.ketraterm.intellij.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.ketraterm.intellij.KetraTermBundle
import io.github.ketraterm.intellij.settings.KetraTermIntellijSettings
import io.github.ketraterm.intellij.settings.KetraTermIntellijSettingsMapper
import io.github.ketraterm.ui.swing.api.SwingHostServices
import io.github.ketraterm.ui.swing.api.SwingTerminal
import io.github.ketraterm.ui.swing.api.SwingTerminalMessages
import io.github.ketraterm.ui.swing.settings.SwingSettings
import io.github.ketraterm.ui.swing.suggestion.*
import java.awt.Component
import java.awt.Container
import java.awt.Font
import javax.swing.JList
import javax.swing.UIManager

class IntellijCompletionAppearanceTest : BasePlatformTestCase() {
    fun testSharedPopupTracksIdePaletteWithoutReplacingItsPublication() {
        val light =
            mapOf(
                "List.background" to java.awt.Color(0xFAFAFA),
                "List.foreground" to java.awt.Color(0x202020),
                "List.selectionBackground" to java.awt.Color(0x315F9D),
                "List.selectionForeground" to java.awt.Color.WHITE,
            )
        var settings = mappedSettings(13)
        val terminal = terminal { settings }
        val previous = light.mapValues { (key, value) -> UIManager.put(key, value) }
        try {
            settings = mappedSettings(13)
            terminal.reloadSettings()
            val interaction = present(terminal)
            val list = completionList(terminal)
            val model = list.model
            val publication = interaction.snapshot
            assertEquals(light.getValue("List.background").rgb, list.background.rgb)
            assertEquals(light.getValue("List.foreground").rgb, list.foreground.rgb)
            assertEquals(light.getValue("List.selectionBackground").rgb, list.selectionBackground.rgb)
            assertEquals(settings.font, list.font)
            assertEquals(
                SwingTerminalMessages.forLocale(KetraTermBundle.locale).message("completion.accessibleName"),
                list.accessibleContext.accessibleName,
            )
            assertNotNull(list.accessibleContext.accessibleDescription)
            assertTrue(list.accessibleContext.accessibleChildrenCount >= 2)

            val darkBackground = java.awt.Color(0x23262B)
            val darkForeground = java.awt.Color(0xE0E2E5)
            UIManager.put("List.background", darkBackground)
            UIManager.put("List.foreground", darkForeground)
            settings = mappedSettings(13)
            terminal.reloadSettings()

            assertEquals(darkBackground.rgb, list.background.rgb)
            assertEquals(darkForeground.rgb, list.foreground.rgb)
            assertSame(model, list.model)
            assertSame(publication, interaction.snapshot)
            assertEquals(1, list.selectedIndex)
            assertTrue(interaction.isActive)
        } finally {
            try {
                terminal.dispose()
            } finally {
                previous.forEach { (key, value) -> UIManager.put(key, value) }
            }
        }
    }

    fun testSharedPopupFollowsScaledSettingsFontAndClosesWithTheTerminal() {
        var settings = mappedSettings(13)
        val terminal = terminal { settings }
        try {
            val interaction = present(terminal)
            val list = completionList(terminal)
            val model = list.model
            val initialHeight = list.getCellBounds(0, 0).height
            val publication = interaction.snapshot
            assertEquals(settings.font, list.font)
            settings = mappedSettings(26)

            terminal.reloadSettings()
            terminal.doLayout()

            assertEquals(settings.font, list.font)
            assertTrue(list.getCellBounds(0, 0).height >= initialHeight * 1.8)
            assertSame(model, list.model)
            assertSame(publication, interaction.snapshot)
            assertEquals(1, list.selectedIndex)
            terminal.dispose()
            assertTrue(interaction.isClosed)
            assertEquals(0, list.model.size)
            assertFalse(interaction.publish(suggestions(), selectedIndex = 0))
        } finally {
            terminal.dispose()
        }
    }

    private fun mappedSettings(fontSize: Int): SwingSettings =
        KetraTermIntellijSettingsMapper.toSwingSettings(
            KetraTermIntellijSettings.State(themeId = "nord", fontFamily = Font.MONOSPACED, fontSize = fontSize),
        )

    private fun terminal(settings: () -> SwingSettings): SwingTerminal =
        SwingTerminal(
            settingsProvider = settings,
            hostServices =
                SwingHostServices.create {
                    it.shellSuggestionKeymap = KetraTermShellSuggestionKeymap
                    it.shellSuggestionViewFactory =
                        SwingShellSuggestionViewFactory.createDefault(SwingTerminalMessages.forLocale(KetraTermBundle.locale))
                },
        ).apply {
            setSize(900, 600)
            doLayout()
        }

    private fun present(terminal: SwingTerminal): SwingShellSuggestionInteraction {
        val interaction =
            checkNotNull(
                terminal.beginShellSuggestionInteraction(
                    SwingShellSuggestionRequest("git sw", 6),
                    editTarget = SwingShellSuggestionEditTarget.NONE,
                ),
            )
        terminal.presentShellSuggestions(interaction)
        check(interaction.publish(suggestions(), selectedIndex = 1))
        terminal.doLayout()
        return interaction
    }

    private fun suggestions(): List<SwingShellSuggestion> =
        listOf("switch", "show").map { text ->
            SwingShellSuggestion(
                replacementText = text,
                replacementStartOffset = 4,
                replacementEndOffset = 6,
                source = "spec",
                kind = "SUBCOMMAND",
                displayText = text,
                detail = "Description for $text",
                argumentHint = "<branch>",
                matchedRanges = SwingShellSuggestionMatchRanges.fromPackedOffsets(text, intArrayOf(0, 1)),
            )
        }

    private fun completionList(root: Container): JList<*> = descendants(root).filterIsInstance<JList<*>>().single()

    private fun descendants(root: Container): Sequence<Component> =
        sequence {
            for (child in root.components) {
                yield(child)
                if (child is Container) yieldAll(descendants(child))
            }
        }
}
