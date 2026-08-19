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
package io.github.ketraterm.ui.swing.suggestion

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.Font

class SwingCompletionPopupPaletteTest {
    @Test
    fun `dark palette maintains text contrast`() {
        assertPaletteContrast(Color(0x10, 0x14, 0x18), Color(0xE5, 0xE7, 0xEB))
    }

    @Test
    fun `light palette maintains text contrast`() {
        assertPaletteContrast(Color(0xFA, 0xFA, 0xFA), Color(0x20, 0x20, 0x20))
    }

    @Test
    fun `low contrast host colors are corrected`() {
        assertPaletteContrast(Color(0x70, 0x70, 0x70), Color(0x75, 0x75, 0x75))
    }

    private fun assertPaletteContrast(
        background: Color,
        foreground: Color,
    ) {
        val palette =
            SwingCompletionPopupAppearanceResolver
                .resolve(Font(Font.SANS_SERIF, Font.PLAIN, 13), background, foreground)
                .palette
        assertTrue(SwingCompletionPopupAppearanceResolver.contrastRatio(palette.foreground, palette.background) >= 4.5)
        assertTrue(SwingCompletionPopupAppearanceResolver.contrastRatio(palette.matchForeground, palette.background) >= 4.5)
        assertTrue(
            SwingCompletionPopupAppearanceResolver.contrastRatio(
                palette.selectedForeground,
                palette.selectionBackground,
            ) >= 4.5,
        )
        assertTrue(
            SwingCompletionPopupAppearanceResolver.contrastRatio(
                palette.selectedMatchForeground,
                palette.selectionBackground,
            ) >= 4.5,
        )
    }
}
