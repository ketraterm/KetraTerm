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

import io.github.jvterm.render.api.TerminalRenderCursorShape
import io.github.jvterm.ui.swing.settings.TerminalTheme
import io.github.jvterm.workspace.config.TerminalConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Tests IntelliJ settings persistence mapping without opening an IDE window.
 */
class JvTermIntellijSettingsTest {
    @Test
    fun `default theme id follows IntelliJ`() {
        val state = JvTermIntellijSettings.State()

        assertEquals(JvTermIntellijSettings.DEFAULT_THEME_ID, state.themeId)
    }

    @Test
    fun `normalizes unknown theme ids to IntelliJ native theme`() {
        assertEquals(
            JvTermIntellijSettings.DEFAULT_THEME_ID,
            JvTermIntellijSettings.normalizeThemeId("missing-theme"),
        )
    }

    @Test
    fun `maps built in theme ids to built in palettes`() {
        val settings =
            JvTermIntellijSettingsMapper.toSwingSettings(
                JvTermIntellijSettings.State(themeId = "one-dark"),
            )
        val expected = TerminalTheme.ONE_DARK.createPalette()

        assertEquals(expected.defaultForeground, settings.palette.defaultForeground)
        assertEquals(expected.defaultBackground, settings.palette.defaultBackground)
        assertEquals(expected.indexedColor(1), settings.palette.indexedColor(1))
    }

    @Test
    fun `clamps hostile persisted dimensions before creating Swing settings`() {
        val settings =
            JvTermIntellijSettingsMapper.toSwingSettings(
                JvTermIntellijSettings.State(
                    themeId = "nord",
                    columns = Int.MAX_VALUE,
                    rows = Int.MIN_VALUE,
                    fontSize = Int.MAX_VALUE,
                    cursorBlinkMillis = -1,
                    scrollbackLines = Int.MAX_VALUE,
                    lineHeight = Float.NaN,
                ),
            )

        assertEquals(TerminalConfig.COLUMNS_MAX, settings.columns)
        assertEquals(TerminalConfig.ROWS_MIN, settings.rows)
        assertEquals(TerminalConfig.FONT_SIZE_MAX, settings.font.size)
        assertEquals(TerminalConfig.CURSOR_BLINK_MIN, settings.cursorBlinkMillis)
        assertEquals(TerminalConfig.SCROLLBACK_MAX, settings.scrollbackLines)
        assertEquals(TerminalConfig.DEFAULT_LINE_HEIGHT, settings.lineHeight)
        assertFalse(settings.shellRequestResizeWindow)
        assertFalse(settings.shellRequestWindowManipulation)
    }

    @Test
    fun `normalizer canonicalizes persisted ui state`() {
        val state =
            JvTermIntellijSettingsNormalizer.normalize(
                JvTermIntellijSettings.State(
                    themeId = "TOKYO-NIGHT",
                    fontSize = 1,
                    columns = 1,
                    rows = Int.MAX_VALUE,
                    cursorBlinkMillis = Int.MAX_VALUE,
                    cursorShape = "bar",
                    scrollbackLines = -1,
                    lineHeight = Float.POSITIVE_INFINITY,
                ),
            )

        assertEquals("tokyo-night", state.themeId)
        assertEquals(TerminalConfig.FONT_SIZE_MIN, state.fontSize)
        assertEquals(TerminalConfig.COLUMNS_MIN, state.columns)
        assertEquals(TerminalConfig.ROWS_MAX, state.rows)
        assertEquals(TerminalConfig.CURSOR_BLINK_MAX, state.cursorBlinkMillis)
        assertEquals("beam", state.cursorShape)
        assertEquals(TerminalConfig.SCROLLBACK_MIN, state.scrollbackLines)
        assertEquals(TerminalConfig.DEFAULT_LINE_HEIGHT, state.lineHeight)
    }

    @Test
    fun `maps cursor shape ids`() {
        val settings =
            JvTermIntellijSettingsMapper.toSwingSettings(
                JvTermIntellijSettings.State(themeId = "nord", cursorShape = "beam"),
            )

        assertEquals(TerminalRenderCursorShape.BAR, settings.cursorShape)
    }

    @Test
    fun `native palette uses editor foreground background selection and cursor colors`() {
        val palette =
            JvTermIntellijThemePalette.fromSource(
                JvTermIntellijThemePalette.ColorSource(
                    foreground = 0xFF202124.toInt(),
                    background = 0xFFFAFAFA.toInt(),
                    selectionForeground = 0xFFFFFFFF.toInt(),
                    selectionBackground = 0xFF3366CC.toInt(),
                    cursor = 0xFF112233.toInt(),
                ),
            )

        assertEquals(0xFF202124.toInt(), palette.defaultForeground)
        assertEquals(0xFFFAFAFA.toInt(), palette.defaultBackground)
        assertEquals(0xFFFFFFFF.toInt(), palette.selectionForeground)
        assertEquals(0xFF3366CC.toInt(), palette.selectionBackground)
        assertEquals(0xFF112233.toInt(), palette.cursorBackground)
        assertEquals(0xFFFAFAFA.toInt(), palette.indexedColor(0))
        assertEquals(0xFF202124.toInt(), palette.indexedColor(7))
        assertNotEquals(palette.cursorBackground, palette.cursorForeground)
    }
}
