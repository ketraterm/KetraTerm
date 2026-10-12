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
    fun searchFitsNarrowPanesAndCollapsedOptionsKeepTheirState() {
        SwingUtilities.invokeAndWait {
            val terminal = SwingTerminal()
            val bar = SwingTerminalSearchBar(terminal)

            fun descendants(c: Component): List<Component> =
                listOf(c) + if (c is Container) c.components.flatMap(::descendants) else emptyList()

            fun layout(c: Container) {
                c.doLayout()
                c.components.filterIsInstance<Container>().forEach(::layout)
            }
            try {
                bar.open()
                val overlay = SwingTerminalOverlayPane(terminal, bar.component)
                val controls = descendants(bar.component)
                val toggles = controls.filterIsInstance<JToggleButton>()
                val options = controls.filterIsInstance<JButton>().single { it.text == "⋯" }
                for (width in listOf(800, 480, 320, 280, 800)) {
                    overlay.setSize(width, 400)
                    layout(overlay)
                    for (control in controls.filter { it.isVisible && it.parent != null }) {
                        assertTrue(
                            control.x >= 0 && control.x + control.width <= control.parent.width,
                            "${control.javaClass.simpleName} clips at pane width $width: ${control.bounds}",
                        )
                    }
                    assertEquals(width < 480, options.isVisible)
                    assertEquals(!options.isVisible, toggles.all { it.isVisible })
                    if (options.isVisible) {
                        options.doClick(0)
                        val item = options.componentPopupMenu.getComponent(0) as JCheckBoxMenuItem
                        val toggle = toggles.single { it.accessibleContext.accessibleName == item.text }
                        val before = toggle.isSelected
                        assertEquals(before, item.isSelected)
                        item.doClick(0)
                        assertEquals(!before, toggle.isSelected)
                    }
                }
            } finally {
                bar.close()
                terminal.dispose()
            }
        }
    }

    @Test
    fun tooltipsExplainOptionsAndReadLiveHostShortcutHintsAsPlainText() {
        SwingUtilities.invokeAndWait {
            val terminal = SwingTerminal()
            val bar = SwingTerminalSearchBar(terminal)
            val words = SwingHostMessages.forLocale(Locale.US)

            fun descendants(c: Component): List<Component> =
                listOf(c) + if (c is Container) c.components.flatMap(::descendants) else emptyList()
            try {
                val buttons = descendants(bar.component).filterIsInstance<AbstractButton>()
                val descriptions =
                    mapOf(
                        "search.matchCaseToolTip" to "search.matchCaseDescription",
                        "search.wholeWordToolTip" to "search.wholeWordDescription",
                        "search.regexToolTip" to "search.regexDescription",
                    )
                for ((title, description) in descriptions) {
                    val toggle = buttons.single { it.accessibleContext.accessibleName == words.message(title) }
                    assertTrue(toggle.toolTipText.contains(words.message(description)))
                    assertTrue(toggle.toolTipText.contains(words.message("search.optionKeyboardHelp")))
                    assertEquals(words.message(description), toggle.accessibleContext.accessibleDescription)
                    assertTrue(toggle.isFocusable)
                    assertFalse(toggle.isRequestFocusEnabled, "Mouse clicks should preserve query-field focus")
                    assertTrue(toggle.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke("pressed SPACE")) != null)
                }
                val matchCase = buttons.single { it.accessibleContext.accessibleName == words.message("search.matchCaseToolTip") }
                var hint: String? = "Ctrl+F8"
                bar.setShortcutTextProvider { if (it == SwingTerminalSearchAction.TOGGLE_MATCH_CASE) hint else null }
                assertTrue(matchCase.toolTipText.contains("Ctrl+F8"))
                hint = "<Alt&F9>"
                assertTrue(matchCase.toolTipText.contains("&lt;Alt&amp;F9&gt;"))
                assertFalse(matchCase.toolTipText.contains("Ctrl+F8"))
                hint = null
                assertFalse(matchCase.toolTipText.contains("F9"))
                assertFalse(bar.performAction(SwingTerminalSearchAction.TOGGLE_MATCH_CASE))
                bar.open()
                assertTrue(bar.performAction(SwingTerminalSearchAction.TOGGLE_MATCH_CASE))
                assertTrue((matchCase as JToggleButton).isSelected)
                assertTrue(bar.performAction(SwingTerminalSearchAction.TOGGLE_MATCH_CASE))
                assertFalse(matchCase.isSelected)
                assertTrue(bar.performAction(SwingTerminalSearchAction.CLOSE))
                assertFalse(bar.isOpen())
            } finally {
                bar.close()
                terminal.dispose()
            }
        }
    }

    @Test
    fun sharedBarDoesNotReserveSearchCommandKeystrokes() {
        SwingUtilities.invokeAndWait {
            val terminal = SwingTerminal()
            val bar = SwingTerminalSearchBar(terminal)

            fun descendants(c: Component): List<Component> =
                listOf(c) + if (c is Container) c.components.flatMap(::descendants) else emptyList()
            try {
                val field = descendants(bar.component).filterIsInstance<JTextField>().single()
                val strokes = listOf("ENTER", "shift ENTER", "ESCAPE", "alt C", "alt W", "alt R").map(KeyStroke::getKeyStroke)
                val fieldBindings =
                    field
                        .getInputMap(JComponent.WHEN_FOCUSED)
                        .keys()
                        .orEmpty()
                        .toSet()
                val barBindings = bar.component.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                for (stroke in strokes) {
                    assertFalse(stroke in fieldBindings, "The bar must not override the text field's host bindings: $stroke")
                    assertEquals(null, barBindings.get(stroke), "Command binding belongs to the host: $stroke")
                }
            } finally {
                bar.close()
                terminal.dispose()
            }
        }
    }

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
                val toggle = controls.filterIsInstance<JToggleButton>().single { it.text == words.message("search.matchCaseLabel") }
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
                        words.message("search.wholeWordToolTip"),
                        words.message("search.regexToolTip"),
                        words.message("search.optionsToolTip"),
                    ),
                    controls.mapNotNull { it.accessibleContext?.accessibleName }.toSet(),
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
    fun modeTogglesUpdateSearchAndInvalidRegexIsVisible() {
        val buffer = TerminalBuffers.create(80, 24)
        buffer.writeText("cat cats CAT a.b axb")
        val session = TerminalSession.create(buffer, MockConnector())
        val messages = SwingHostMessages.forLocale(Locale.US)
        lateinit var terminal: SwingTerminal
        lateinit var bar: SwingTerminalSearchBar
        lateinit var field: JTextField
        lateinit var counter: JLabel
        lateinit var toggles: List<JToggleButton>

        fun descendants(component: Component): List<Component> =
            listOf(component) + if (component is Container) component.components.flatMap(::descendants) else emptyList()
        SwingUtilities.invokeAndWait {
            terminal = SwingTerminal()
            bar = SwingTerminalSearchBar(terminal, messages)
            terminal.size = terminal.preferredSize
            terminal.bind(session)
            bar.open()
            val controls = descendants(bar.component)
            field = controls.filterIsInstance<JTextField>().single()
            counter = controls.filterIsInstance<JLabel>().single()
            toggles = controls.filterIsInstance<JToggleButton>()
            assertEquals(3, toggles.size)
            field.text = "cat"
            // All buttons fit inside the field without overlapping the editable text.
            val panel = field.parent
            panel.size = panel.preferredSize
            panel.doLayout()
            val ordered = toggles.sortedBy { it.x }
            assertTrue(field.width - field.insets.right <= ordered.first().x)
            for ((left, right) in ordered.zipWithNext()) assertTrue(left.x + left.width <= right.x)
            assertTrue(ordered.last().x + ordered.last().width <= panel.width)
        }

        fun completed() =
            runBlocking {
                withTimeout(10_000.milliseconds) { terminal.searchState.first { !it.isSearching } }
            }

        fun toggle(key: String) =
            SwingUtilities.invokeAndWait {
                toggles.single { it.accessibleContext.accessibleName == messages.message(key) }.doClick(0)
            }
        try {
            assertEquals(3, completed().resultCount)
            toggle("search.wholeWordToolTip")
            assertEquals(2, completed().resultCount)
            toggle("search.matchCaseToolTip")
            assertEquals(1, completed().resultCount)
            SwingUtilities.invokeAndWait { field.text = "a.b" }
            assertEquals(1, completed().resultCount)
            toggle("search.regexToolTip")
            assertEquals(2, completed().resultCount)
            SwingUtilities.invokeAndWait { field.text = "[" }
            assertTrue(completed().failure is java.util.regex.PatternSyntaxException)
            SwingUtilities.invokeAndWait {
                assertEquals(messages.message("search.invalidRegex"), counter.text)
                assertTrue(counter.toolTipText.isNotBlank())
                field.text = "cat|cats"
            }
            assertEquals(2, completed().resultCount)
            SwingUtilities.invokeAndWait {
                assertEquals(messages.message("search.counterToolTip"), counter.toolTipText)
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
    fun broadQueryUsesLocalizedLowerBoundCounter() {
        val buffer = TerminalBuffers.create(80, 24, 3000)
        repeat(2000) {
            buffer.writeText("needle")
            buffer.carriageReturn()
            buffer.newLine()
        }
        val session = TerminalSession.create(buffer, MockConnector())
        lateinit var terminal: SwingTerminal
        lateinit var bar: SwingTerminalSearchBar
        val messages = SwingHostMessages.forLocale(Locale.US)

        fun descendants(component: Component): List<Component> =
            listOf(component) + if (component is Container) component.components.flatMap(::descendants) else emptyList()
        SwingUtilities.invokeAndWait {
            terminal = SwingTerminal()
            bar = SwingTerminalSearchBar(terminal, messages)
            terminal.size = terminal.preferredSize
            terminal.bind(session)
            bar.open()
            descendants(bar.component).filterIsInstance<JTextField>().single().text = "needle"
        }
        try {
            val result =
                runBlocking {
                    withTimeout(10_000.milliseconds) { terminal.searchState.first { it.query == "needle" && !it.isSearching } }
                }
            assertTrue(result.isCountLimited)
            assertEquals(1001, result.resultCount)
            assertEquals(-1, result.activeResultIndex)
            SwingUtilities.invokeAndWait {
                assertEquals(
                    messages.message("search.matchCounterLimited", 1000),
                    descendants(bar.component).filterIsInstance<JLabel>().single().text,
                )
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
