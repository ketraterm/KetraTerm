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

import com.intellij.openapi.actionSystem.IdeActions
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KetraTermShellSuggestionKeymapTest {
    @Test
    fun `lookup navigation follows IntelliJ actions`() {
        assertEquals(
            SwingShellSuggestionAction.SELECT_NEXT,
            KetraTermShellSuggestionKeymap.actionFor(arrayOf(IdeActions.ACTION_LOOKUP_DOWN)),
        )
        assertEquals(
            SwingShellSuggestionAction.SELECT_PREVIOUS,
            KetraTermShellSuggestionKeymap.actionFor(arrayOf(IdeActions.ACTION_LOOKUP_UP)),
        )
    }

    @Test
    fun `both IntelliJ lookup acceptance actions accept suggestions`() {
        assertEquals(
            SwingShellSuggestionAction.ACCEPT,
            KetraTermShellSuggestionKeymap.actionFor(arrayOf(IdeActions.ACTION_CHOOSE_LOOKUP_ITEM)),
        )
        assertEquals(
            SwingShellSuggestionAction.ACCEPT,
            KetraTermShellSuggestionKeymap.actionFor(arrayOf(IdeActions.ACTION_CHOOSE_LOOKUP_ITEM_REPLACE)),
        )
    }

    @Test
    fun `editor escape dismisses and unrelated actions remain unclaimed`() {
        assertEquals(
            SwingShellSuggestionAction.DISMISS,
            KetraTermShellSuggestionKeymap.actionFor(arrayOf(IdeActions.ACTION_EDITOR_ESCAPE)),
        )
        assertNull(KetraTermShellSuggestionKeymap.actionFor(arrayOf(IdeActions.ACTION_EDITOR_COPY)))
    }
}
