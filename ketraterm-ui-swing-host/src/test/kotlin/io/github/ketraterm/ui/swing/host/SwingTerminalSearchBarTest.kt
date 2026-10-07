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
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.image.BufferedImage
import java.util.*
import javax.swing.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class SwingTerminalSearchBarTest {
    @Test
    fun localizedChromeUsesHostLabelsAndFitsLongStatusAndToggleText() {
        val words =
            SwingHostMessages.forLocale(
                Locale.FRENCH,
                ResourceBundle.getBundle("io.github.ketraterm.ui.swing.host.LocalizedTerminalMessages", Locale.FRENCH),
            )
        SwingUtilities.invokeAndWait {
            val terminal = SwingTerminal()
            val bar = SwingTerminalSearchBar(terminal, words)

            fun descendants(component: Component): List<Component> =
                listOf(component) + if (component is Container) component.components.flatMap(::descendants) else emptyList()
            try {
                val controls = descendants(bar.component).filterIsInstance<JComponent>()
                val field = controls.filterIsInstance<JTextField>().single()
                val counter = controls.filterIsInstance<JLabel>().single()
                val toggle = controls.filterIsInstance<JToggleButton>().single()
                assertEquals("Rechercher dans le terminal", field.toolTipText)
                assertEquals("Rechercher dans le terminal", field.accessibleContext.accessibleName)
                assertEquals("Majuscules", toggle.text)
                assertTrue(toggle.preferredSize.width > toggle.getFontMetrics(toggle.font).stringWidth(toggle.text))
                assertTrue(
                    counter.preferredSize.width > counter.getFontMetrics(counter.font).stringWidth(words.message("search.searching")),
                )
                assertEquals(
                    setOf(
                        "Rechercher dans le terminal",
                        "Résultat actif et nombre total",
                        "Résultat précédent",
                        "Résultat suivant",
                        "Fermer la recherche",
                        "Respecter la casse",
                    ),
                    controls.mapNotNull { it.toolTipText }.toSet(),
                )
                bar.open()
                assertEquals("0 résultats ; actif 0", counter.text)
                field.text = "requête conservée"
                bar.refreshColors()
                assertEquals("requête conservée", field.text)
                bar.close()
                bar.open()
                assertEquals("0 résultats ; actif 0", counter.text)
            } finally {
                bar.close()
                terminal.dispose()
            }
        }
    }

    @Test
    fun offEdtColorRefreshIsOrderedAndSurvivesReopening() {
        lateinit var terminal: SwingTerminal
        lateinit var bar: SwingTerminalSearchBar
        SwingUtilities.invokeAndWait {
            terminal = SwingTerminal()
            bar = SwingTerminalSearchBar(terminal)
            assertEquals(Color(0xFFE8EAED.toInt(), true), bar.component.foreground)
        }
        try {
            bar.refreshColors(SwingTerminalSearchColors.create { it.foreground = Color.BLUE })
            bar.refreshColors(SwingTerminalSearchColors.create { it.foreground = Color.RED })
            SwingUtilities.invokeAndWait {
                assertEquals(Color.RED, bar.component.foreground)
                bar.open()
                bar.close()
                bar.open()
                assertEquals(Color.RED, bar.component.foreground)
            }
        } finally {
            SwingUtilities.invokeAndWait {
                bar.close()
                terminal.dispose()
            }
        }
    }

    @Test
    fun colorsFreezeDraftsAndDynamicHostColors() {
        var argb = 0x12345678
        val hostColor =
            object : Color(0) {
                override fun getRGB(): Int = argb
            }
        val draft = SwingTerminalSearchColors.builder()
        draft.panelBackground = hostColor
        val frozen = draft.build()
        argb = 0x76543210
        draft.panelBackground = Color.RED
        assertEquals(0x12345678, frozen.panelBackground.rgb)
        assertEquals(0x12345678, frozen.copy { it.foreground = Color.BLUE }.panelBackground.rgb)
    }

    @Test
    fun hostColorsRefreshPaintedChromeWithoutResettingSearch() {
        SwingUtilities.invokeAndWait {
            val terminal = SwingTerminal()
            val bar = SwingTerminalSearchBar(terminal)

            fun descendants(component: Component): List<Component> =
                listOf(component) + if (component is Container) component.components.flatMap(::descendants) else emptyList()

            fun pixel(
                component: JComponent,
                x: Int,
                y: Int,
            ): Int {
                component.setSize(300, 40)
                val image = BufferedImage(300, 40, BufferedImage.TYPE_INT_ARGB)
                val graphics = image.createGraphics()
                try {
                    component.paint(graphics)
                } finally {
                    graphics.dispose()
                }
                return image.getRGB(x, y)
            }
            try {
                bar.open()
                val field = descendants(bar.component).filterIsInstance<JTextField>().single()
                field.text = "retained query"
                val colors =
                    SwingTerminalSearchColors.create {
                        it.panelBackground = Color.WHITE
                        it.foreground = Color.BLACK
                        it.textFieldBackground = Color.YELLOW
                        it.counterForeground = Color.BLUE
                    }
                bar.refreshColors(colors)
                assertEquals(Color.BLACK, field.foreground)
                assertEquals(Color.BLACK, field.caretColor)
                assertEquals(Color.YELLOW.rgb, pixel(field, 5, 20))
                assertEquals(Color.WHITE.rgb, pixel(bar.component.components.single() as JComponent, 150, 35))
                assertEquals(Color.BLUE, descendants(bar.component).filterIsInstance<JLabel>().single().foreground)
                val updated = colors.copy { it.textFieldBackground = Color.GREEN }
                bar.refreshColors(updated)
                bar.refreshColors()
                assertEquals(Color.GREEN.rgb, pixel(field, 5, 20))
                assertEquals(Color.YELLOW, colors.textFieldBackground)
                assertEquals("retained query", field.text)
                assertTrue(bar.isOpen())
            } finally {
                bar.close()
                terminal.dispose()
            }
        }
    }

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
                    withTimeout(10_000.milliseconds) { terminal.searchState.first { it.query == "needle" && !it.isSearching } }
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
