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
package io.github.jvterm.app.ui

import io.github.jvterm.ui.swing.settings.TerminalTheme
import javax.swing.UIManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChromeTest {
    @Test
    fun builtInThemesDeriveReadableTabText() {
        TerminalTheme.entries.forEach { theme ->
            Chrome.applyPalette(theme.createPalette())

            assertContrast(
                theme = theme,
                label = "selected tab text",
                foreground = Chrome.textPrimary,
                background = Chrome.tabSelectedBackground,
            )
            assertContrast(
                theme = theme,
                label = "hovered tab text",
                foreground = Chrome.textHover,
                background = Chrome.tabHoverBackground,
            )
            assertContrast(
                theme = theme,
                label = "inactive tab text",
                foreground = Chrome.textSecondary,
                background = Chrome.surface,
            )
            assertContrast(
                theme = theme,
                label = "action icon text",
                foreground = Chrome.controlText,
                background = Chrome.surface,
            )
        }
    }

    @Test
    fun builtInThemesSeparateSelectedTabFromTitleBar() {
        TerminalTheme.entries.forEach { theme ->
            Chrome.applyPalette(theme.createPalette())

            val tabContrast = Chrome.contrastRatio(Chrome.tabSelectedBackground, Chrome.surface)
            val borderContrast = Chrome.contrastRatio(Chrome.border, Chrome.surface)

            assertTrue(
                tabContrast >= MINIMUM_SURFACE_CONTRAST,
                "${theme.name} selected tab contrast was $tabContrast",
            )
            assertTrue(
                borderContrast >= MINIMUM_BORDER_CONTRAST,
                "${theme.name} border contrast was $borderContrast",
            )
        }
    }

    @Test
    fun applyPalettePublishesChromeTokensToSwingDefaults() {
        Chrome.applyPalette(TerminalTheme.entries.first().createPalette())

        assertEquals(Chrome.topBarBackground, UIManager.getColor("TitlePane.background"))
        assertEquals(Chrome.textPrimary, UIManager.getColor("TitlePane.foreground"))
        assertEquals(Chrome.surface, UIManager.getColor("Panel.background"))
        assertEquals(Chrome.border, UIManager.getColor("Separator.foreground"))
        assertEquals(Chrome.controlHover, UIManager.getColor("MenuItem.selectionBackground"))
    }

    private fun assertContrast(
        theme: TerminalTheme,
        label: String,
        foreground: java.awt.Color,
        background: java.awt.Color,
    ) {
        val contrast = Chrome.contrastRatio(foreground, background)
        assertTrue(
            contrast >= MINIMUM_TEXT_CONTRAST,
            "${theme.name} $label contrast was $contrast",
        )
    }

    private companion object {
        private const val MINIMUM_TEXT_CONTRAST = 4.5
        private const val MINIMUM_SURFACE_CONTRAST = 1.08
        private const val MINIMUM_BORDER_CONTRAST = 1.5
    }
}
