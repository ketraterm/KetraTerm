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
package io.github.ketraterm.ui.swing.settings

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SwingShellSuggestionStyleTest {
    @Test
    fun `settings retain immutable styles through copying equality and reset`() {
        val defaults = SwingSettings()
        val style = SwingShellSuggestionStyle(foreground = 0xFFEEEEEE.toInt(), matchForeground = 0xFF1199FF.toInt())
        val customized = defaults.copy { it.shellSuggestionStyle = style }
        assertEquals(SwingShellSuggestionStyle(), defaults.shellSuggestionStyle)
        assertSame(style, customized.shellSuggestionStyle)
        assertEquals(customized, customized.copy {})
        assertEquals(customized.hashCode(), customized.copy {}.hashCode())
        assertNotEquals(defaults, customized)
        assertEquals(defaults, customized.copy { it.shellSuggestionStyle = SwingShellSuggestionStyle() })
        assertEquals(0, SwingShellSuggestionStyle(border = 0).border)
    }

    @Test
    fun `translucent text and fills are rejected for every color role`() {
        val invalid = 0x7F123456
        val styles =
            listOf<() -> SwingShellSuggestionStyle>(
                { SwingShellSuggestionStyle(background = invalid) },
                { SwingShellSuggestionStyle(foreground = invalid) },
                { SwingShellSuggestionStyle(selectionBackground = invalid) },
                { SwingShellSuggestionStyle(selectionForeground = invalid) },
                { SwingShellSuggestionStyle(matchForeground = invalid) },
                { SwingShellSuggestionStyle(selectionMatchForeground = invalid) },
                { SwingShellSuggestionStyle(matchBackground = invalid) },
                { SwingShellSuggestionStyle(selectionMatchBackground = invalid) },
                { SwingShellSuggestionStyle(secondaryForeground = invalid) },
            )
        styles.forEachIndexed { index, style ->
            assertThrows(IllegalArgumentException::class.java, { style() }, "Color role $index")
        }
    }
}
