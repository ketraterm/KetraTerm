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
package io.github.ketraterm.app.ui

import io.github.ketraterm.ui.swing.host.SwingTerminalHostAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TerminalPaneShortcutControllerTest {
    @Test
    fun openSearchActionOpensHostSearchChrome() {
        val target = RecordingActionTarget()

        val performed = TerminalPaneActionRegistry.perform(SwingTerminalHostAction.OPEN_SEARCH, target)

        assertTrue(performed)
        assertEquals(1, target.openSearchCount)
        assertEquals(0, target.copyCount)
        assertEquals(0, target.pasteCount)
    }

    private class RecordingActionTarget : TerminalPaneActionTarget {
        var openSearchCount: Int = 0
            private set
        var copyCount: Int = 0
            private set
        var pasteCount: Int = 0
            private set

        override fun hasSelection(): Boolean = false

        override fun copySelectionToClipboard(): Boolean {
            copyCount++
            return true
        }

        override fun pasteClipboardText(): Boolean {
            pasteCount++
            return true
        }

        override fun openSearch() {
            openSearchCount++
        }

        override fun scrollPageUp() = Unit

        override fun scrollPageDown() = Unit
    }
}
