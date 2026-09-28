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

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.testkit.MockConnector
import io.github.ketraterm.ui.swing.api.SwingTerminal
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.awt.Component
import java.awt.Container
import javax.swing.JLabel
import javax.swing.JTextField
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SwingTerminalSearchBarTest {
    @Test
    fun counterObservesBackgroundCompletionWithoutAnotherKeyPress() {
        val buffer = TerminalBuffers.create(80, 24)
        buffer.writeText("needle")
        val session = TerminalSession.create(buffer, MockConnector())
        val terminal = SwingTerminal()
        val bar = SwingTerminalSearchBar(terminal)

        fun descendants(component: Component): List<Component> =
            listOf(component) + if (component is Container) component.components.flatMap(::descendants) else emptyList()
        try {
            SwingUtilities.invokeAndWait {
                terminal.size = terminal.preferredSize
                terminal.bind(session)
                bar.open()
                descendants(bar.component).filterIsInstance<JTextField>().single().text = "needle"
                assertTrue(descendants(bar.component).filterIsInstance<JLabel>().any { it.text == "Searching…" })
            }
            val result =
                runBlocking {
                    withTimeout(10_000) { terminal.searchState.first { it.query == "needle" && !it.isSearching } }
                }
            assertEquals(1, result.resultCount)
            SwingUtilities.invokeAndWait {
                assertTrue(descendants(bar.component).filterIsInstance<JLabel>().any { it.text == "1/1" })
                bar.close()
                assertEquals("", terminal.currentSearchState().query)
            }
        } finally {
            SwingUtilities.invokeAndWait {
                bar.close()
                terminal.dispose()
            }
            session.close()
        }
    }

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
