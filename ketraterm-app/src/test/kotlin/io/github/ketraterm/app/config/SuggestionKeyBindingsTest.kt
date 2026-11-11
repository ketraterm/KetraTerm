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
package io.github.ketraterm.app.config

import io.github.ketraterm.ui.swing.suggestion.ExperimentalShellSuggestionsApi
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionAction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.Component
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalShellSuggestionsApi::class)
class SuggestionKeyBindingsTest {
    @Test
    fun `defaults separate passive popup inline and selected acceptance`() {
        val bindings = SuggestionKeyBindings()
        assertEquals(SwingShellSuggestionAction.ACCEPT_SELECTED, bindings.popupAction(key(KeyEvent.VK_TAB)))
        assertEquals(SwingShellSuggestionAction.ACCEPT_SELECTED, bindings.popupAction(key(KeyEvent.VK_ENTER)))
        assertEquals(SwingShellSuggestionAction.ACCEPT_SELECTED, bindings.inlineAction(key(KeyEvent.VK_TAB)))
        assertEquals(SwingShellSuggestionAction.ACCEPT_SELECTED, bindings.inlineAction(key(KeyEvent.VK_RIGHT)))
        assertNull(bindings.inlineAction(key(KeyEvent.VK_ENTER)))
        assertTrue(bindings.triggers(key(KeyEvent.VK_SPACE, InputEvent.CTRL_DOWN_MASK)))
    }

    @Test
    fun `custom shortcuts replace defaults and blanks disable acceptance`() {
        val bindings = SuggestionKeyBindings("ctrl J", "ctrl K", "ctrl L", "alt SPACE")
        assertNull(bindings.popupAction(key(KeyEvent.VK_TAB)))
        assertNull(bindings.popupAction(key(KeyEvent.VK_ENTER)))
        assertNull(bindings.inlineAction(key(KeyEvent.VK_RIGHT)))
        assertEquals(SwingShellSuggestionAction.ACCEPT_SELECTED, bindings.popupAction(key(KeyEvent.VK_J, InputEvent.CTRL_DOWN_MASK)))
        assertEquals(SwingShellSuggestionAction.ACCEPT_SELECTED, bindings.popupAction(key(KeyEvent.VK_K, InputEvent.CTRL_DOWN_MASK)))
        assertEquals(SwingShellSuggestionAction.ACCEPT_SELECTED, bindings.inlineAction(key(KeyEvent.VK_L, InputEvent.CTRL_DOWN_MASK)))
        assertFalse(bindings.triggers(key(KeyEvent.VK_SPACE, InputEvent.CTRL_DOWN_MASK)))
        assertTrue(bindings.triggers(key(KeyEvent.VK_SPACE, InputEvent.ALT_DOWN_MASK)))
        val disabled = SuggestionKeyBindings("", "", "", "")
        assertNull(disabled.popupAction(key(KeyEvent.VK_TAB)))
        assertNull(disabled.inlineAction(key(KeyEvent.VK_TAB)))
        assertFalse(disabled.triggers(key(KeyEvent.VK_SPACE, InputEvent.CTRL_DOWN_MASK)))
        for (invalid in listOf("not a key", "typed x", "released TAB", "TAB,")) {
            assertFalse(SuggestionKeyBindings.isValid(invalid), invalid)
        }
    }

    @Test
    fun `independent presentation and bindings round trip with per-field malformed fallback`(
        @TempDir directory: Path,
    ) {
        val path = directory.resolve("config.toml")
        val manager = KetraTermConfigManager(path)
        val config =
            KetraTermConfig(
                popupSuggestionsEnabled = false,
                inlineHistorySuggestionsEnabled = true,
                suggestionKeyBindings = SuggestionKeyBindings("ctrl J", "", "RIGHT", "alt SPACE"),
            )
        manager.save(config)
        assertEquals(config, manager.load())
        Files.writeString(
            path,
            """
            [behavior]
            popup_suggestions_enabled = false
            popup_accept_keys = 'invalid'
            inline_accept_keys = 'ctrl L'
            suggestions_trigger_keys = ''
            """.trimIndent(),
        )
        val loaded = manager.load()
        assertFalse(loaded.popupSuggestionsEnabled)
        assertEquals("TAB", loaded.suggestionKeyBindings.popupAccept)
        assertEquals("ctrl L", loaded.suggestionKeyBindings.inlineAccept)
        assertEquals("", loaded.suggestionKeyBindings.trigger)
    }

    private fun key(
        code: Int,
        modifiers: Int = 0,
    ) = KeyEvent(object : Component() {}, KeyEvent.KEY_PRESSED, 0L, modifiers, code, KeyEvent.CHAR_UNDEFINED)
}
