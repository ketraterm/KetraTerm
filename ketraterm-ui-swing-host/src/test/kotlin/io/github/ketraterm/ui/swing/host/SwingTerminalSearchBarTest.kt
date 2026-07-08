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
package io.github.ketraterm.ui.swing.host

import io.github.ketraterm.ui.swing.api.SwingTerminal
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SwingTerminalSearchBarTest {
    @Test
    fun openAndCloseToggleHostSearchChrome() {
        val terminal = SwingTerminal()
        val searchBar = SwingTerminalSearchBar(terminal)

        SwingUtilities.invokeAndWait {
            assertFalse(searchBar.isOpen())

            searchBar.open()
            assertTrue(searchBar.isOpen())

            searchBar.close()
            assertFalse(searchBar.isOpen())
            assertEquals("", terminal.currentSearchState().query)
        }
    }

    @Test
    fun preferredWidthStaysCompactAcrossLookAndFeels() {
        val terminal = SwingTerminal()
        val searchBar = SwingTerminalSearchBar(terminal)

        SwingUtilities.invokeAndWait {
            val width = searchBar.component.preferredSize.width

            assertTrue(width in 420..620, "search bar preferred width was $width")
        }
    }
}
