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

import io.github.ketraterm.ui.swing.api.SwingTerminalMessages
import io.github.ketraterm.ui.swing.render.SwingColors
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.awt.Color
import java.awt.Font
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.image.BufferedImage
import java.io.StringReader
import java.util.*
import javax.accessibility.AccessibleRole
import javax.accessibility.AccessibleSelection
import javax.swing.JPanel
import javax.swing.SwingUtilities

class SwingCompletionPopupViewTest {
    @ParameterizedTest
    @ValueSource(doubles = [1.0, 1.25, 1.5, 2.0])
    fun `popup border is fully visible on every edge at fractional scales`(scale: Double) =
        onEdt {
            val previous = javax.swing.UIManager.get("Component.borderColor")
            javax.swing.UIManager.put("Component.borderColor", Color.WHITE)
            val view = createView(RecordingListener())
            try {
                view.background = Color.BLACK
                view.update(snapshot(listOf(suggestion("cd")), 0, 1, 0))
                view.setSize(160, 100)
                view.doLayout()
                val image = BufferedImage((view.width * scale).toInt(), (view.height * scale).toInt(), BufferedImage.TYPE_INT_ARGB)
                val graphics = image.createGraphics()
                try {
                    graphics.scale(scale, scale)
                    view.paint(graphics)
                } finally {
                    graphics.dispose()
                }
                assertEquals(Color.WHITE.rgb, image.getRGB(0, image.height / 2), "Left edge must not be clipped")
                assertEquals(Color.WHITE.rgb, image.getRGB(image.width - 1, image.height / 2), "Right edge must not be clipped")
                assertEquals(Color.WHITE.rgb, image.getRGB(image.width / 2, 0), "Top edge must not be clipped")
                assertEquals(Color.WHITE.rgb, image.getRGB(image.width / 2, image.height - 1), "Bottom edge must not be clipped")
            } finally {
                view.close()
                javax.swing.UIManager.put("Component.borderColor", previous)
            }
        }

    @ParameterizedTest
    @CsvSource("true, 1", "true, 2", "false, 1", "false, 2")
    fun `rounded popup retains theme colors and selection at display scales`(
        dark: Boolean,
        scale: Int,
    ) = onEdt {
        val surface = if (dark) Color(0x292A2D) else Color(0xFAFAFA)
        val selected = if (dark) Color(0x3A3D44) else Color(0xDDE7F5)
        val keys =
            mapOf(
                "PopupMenu.background" to surface,
                "Panel.background" to surface,
                "List.background" to surface,
                "List.foreground" to if (dark) Color(0xDFE1E5) else Color(0x27282B),
                "List.selectionBackground" to selected,
                "List.selectionForeground" to if (dark) Color.WHITE else Color(0x17181B),
                "Component.borderColor" to if (dark) Color(0x45474C) else Color(0xCBCDD2),
            )
        val old = keys.mapValues { javax.swing.UIManager.get(it.key) }
        try {
            keys.forEach { (key, value) -> javax.swing.UIManager.put(key, value) }
            val view = createView(RecordingListener())
            try {
                val items =
                    listOf("git", "src/", "--verbose", "git status", "production").mapIndexed { index, text ->
                        suggestion(
                            text,
                            "Suggestion details",
                            "PowerShell",
                        ).copy(accentRole = SwingShellSuggestionAccentRole.entries[index])
                    }
                view.update(snapshot(items, 0, items.size, 0))
                view.size = view.preferredSize
                view.doLayout()
                val image = BufferedImage(view.width * scale, view.height * scale, BufferedImage.TYPE_INT_ARGB)
                val graphics = image.createGraphics()
                try {
                    graphics.scale(scale.toDouble(), scale.toDouble())
                    view.paint(graphics)
                } finally {
                    graphics.dispose()
                }
                assertEquals(0, image.getRGB(0, 0) ushr 24, "Rounded corners must expose the terminal underneath")
                val row = view.list.getCellBounds(0, 0)
                val sample = SwingUtilities.convertPoint(view.list, row.width - 10, row.y + row.height / 2, view)
                assertEquals(selected.rgb, image.getRGB(sample.x * scale, sample.y * scale))
                val preferred = view.preferredSize
                view.update(snapshot(items, 2, items.size, 0))
                assertEquals(preferred, view.preferredSize, "Selection must not resize the popup")
            } finally {
                view.close()
            }
        } finally {
            old.forEach { (key, value) -> javax.swing.UIManager.put(key, value) }
        }
    }

    @ParameterizedTest
    @CsvSource("292A2D, DFE1E5", "FAFAFA, 27282B", "2459A6, FFFFFF")
    fun `category icons have distinct silhouettes and readable accents`(
        backgroundHex: String,
        foregroundHex: String,
    ) = onEdt {
        val view = createView(RecordingListener())
        try {
            val silhouettes = mutableSetOf<List<Boolean>>()
            for (role in SwingShellSuggestionAccentRole.entries) {
                val candidate = suggestion("example").copy(accentRole = role)
                val row = view.list.cellRenderer.getListCellRendererComponent(view.list, candidate, 0, false, false) as javax.swing.JLabel
                row.background = Color(backgroundHex.toInt(16))
                row.foreground = Color(foregroundHex.toInt(16))
                val image = BufferedImage(row.icon.iconWidth, row.icon.iconHeight, BufferedImage.TYPE_INT_ARGB)
                val graphics = image.createGraphics()
                try {
                    row.icon.paintIcon(row, graphics, 0, 0)
                } finally {
                    graphics.dispose()
                }
                val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
                val strokes = pixels.filter { it ushr 24 == 255 }
                assertTrue(strokes.isNotEmpty(), "$role must have a visible silhouette")
                assertTrue(strokes.all { SwingColors.contrastRatio(it, row.background.rgb) >= 3.0 }, "$role must contrast with its row")
                assertTrue(silhouettes.add(pixels.map { it ushr 24 >= 128 }), "$role must be distinguishable without color")
            }
        } finally {
            view.close()
        }
    }

    @Test
    fun `compact rows show command text and retain details in tooltip and accessibility`() =
        onEdt {
            val view = createView(RecordingListener())
            try {
                val candidate = suggestion("btop.exe", detail = "C:\\tools\\btop.exe", sourceDisplayText = "PowerShell")
                view.update(snapshot(listOf(candidate), 0, 1, 0))
                val row = view.list.cellRenderer.getListCellRendererComponent(view.list, candidate, 0, true, false) as javax.swing.JLabel
                assertTrue(row.text.contains("btop.exe"))
                assertFalse(row.text.contains("C:"))
                assertFalse(row.text.contains("PowerShell"))
                assertTrue(row.toolTipText.contains("PowerShell"))
                assertTrue(row.toolTipText.contains("C:\\tools\\btop.exe"))
                assertTrue(row.accessibleContext.accessibleDescription.contains("PowerShell"))
                assertTrue(view.preferredSize.width < 320)
            } finally {
                view.close()
            }
        }

    @Test
    fun `static bundle text preserves braces and apostrophes verbatim`() {
        val messages =
            SwingTerminalMessages.forLocale(
                Locale.FRANCE,
                PropertyResourceBundle(StringReader("completion.accessibleName=Commandes { d'utilisateur")),
            )
        assertEquals("Commandes { d'utilisateur", messages.message("completion.accessibleName"))
        assertEquals("No suggestions", messages.message("completion.empty"))
    }

    @Test
    fun `host text controls range accessibility and escaped tooltip outside repeated rendering`() =
        onEdt {
            var rowMessages = 0
            val localized =
                SwingTerminalMessages.forLocale(
                    Locale.FRANCE,
                    PropertyResourceBundle(
                        StringReader(
                            """
                            completion.accessibleName=Suggestions de commande
                            completion.range={2,number,integer} au total : {0,number,integer} à {1,number,integer}
                            completion.description.many={0,number,integer} possibilités ; affichage {1,number,integer} à {2,number,integer}
                            completion.empty=0 possibilités ; affichage 0 à 0
                            completion.role.command=Commande
                            completion.row.description={2} de {1} : {0}
                            completion.row.tooltip=Depuis {2} : <{0}> — {1}
                            """.trimIndent(),
                        ),
                    ),
                )
            val messages =
                SwingTerminalMessages { key, arguments ->
                    assertTrue(SwingUtilities.isEventDispatchThread())
                    if (key == "completion.row.description") rowMessages++
                    localized.message(key, *arguments)
                }
            val view = createView(RecordingListener(), messages)
            try {
                val candidate = suggestion("git", detail = "Branche", sourceDisplayText = "Projet")
                view.update(snapshot(listOf(candidate), 0, 12, 4))
                assertEquals("Suggestions de commande", view.list.accessibleContext.accessibleName)
                assertEquals("12 possibilités ; affichage 5 à 5", view.list.accessibleContext.accessibleDescription)
                val position = view.components.filterIsInstance<javax.swing.JLabel>().single()
                assertEquals("12 au total : 5 à 5", position.text)

                repeat(5) {
                    val renderer =
                        view.list.cellRenderer.getListCellRendererComponent(view.list, candidate, 0, false, false) as javax.swing.JLabel
                    assertEquals("Commande de Projet : Branche", renderer.accessibleContext.accessibleDescription)
                    assertTrue(renderer.toolTipText.contains("Depuis&nbsp;Projet&nbsp;:&nbsp;&lt;git&gt;"))
                    assertFalse(renderer.toolTipText.contains("<git>"))
                }
                assertEquals(1, rowMessages)

                view.update(snapshot(listOf(candidate), -1, 12, 4))
                assertEquals(1, rowMessages)
                view.update(SwingShellSuggestionViewSnapshot.EMPTY)
                assertEquals("0 possibilités ; affichage 0 à 0", view.list.accessibleContext.accessibleDescription)
            } finally {
                view.close()
            }
        }

    @Test
    fun `localized standard factory creates independently owned views`() =
        onEdt {
            val messages =
                SwingTerminalMessages.forLocale(
                    Locale.FRANCE,
                    PropertyResourceBundle(StringReader("completion.accessibleName=Commandes")),
                )
            val factory = SwingShellSuggestionViewFactory.createDefault(messages)
            val first = factory.create(RecordingListener()) as SwingCompletionPopupView
            val second = factory.create(RecordingListener()) as SwingCompletionPopupView
            try {
                assertNotSame(first, second)
                assertEquals("Commandes", first.list.accessibleContext.accessibleName)
                assertEquals("Commandes", second.list.accessibleContext.accessibleName)
                first.close()
                second.update(snapshot(listOf(suggestion("git")), 0, 1, 0))
                assertEquals("1 suggestion, 1–1 of 1", second.list.accessibleContext.accessibleDescription)
            } finally {
                first.close()
                second.close()
            }
        }

    @Test
    fun `view exposes list semantics and selected item to accessibility`() =
        onEdt {
            val listener = RecordingListener()
            val view = createView(listener)
            val suggestions = List(3) { index -> suggestion("candidate-$index", detail = "detail-$index") }
            view.update(snapshot(suggestions, selectedIndex = 1, total = 12, start = 4))

            val context = view.list.accessibleContext
            assertEquals(AccessibleRole.LIST, context.accessibleRole)
            assertEquals("Command completions", context.accessibleName)
            assertEquals(3, context.accessibleChildrenCount)
            assertTrue(context.accessibleDescription.contains("12 suggestions"))
            val selection = context.accessibleSelection as AccessibleSelection
            assertEquals(1, selection.accessibleSelectionCount)
            assertEquals("candidate-1", selection.getAccessibleSelection(0).accessibleContext.accessibleName)
            assertEquals("candidate-1", context.getAccessibleChild(1).accessibleContext.accessibleName)
        }

    @Test
    fun `clipped popup paints selected tail row and maps pointer to snapshot index`() =
        onEdt {
            val listener = RecordingListener()
            val view = createView(listener)
            val suggestions = List(8) { index -> suggestion("candidate-$index") }
            view.update(snapshot(suggestions, selectedIndex = 7, total = 20, start = 8))
            val rowHeight = view.list.getCellBounds(0, 0).height
            view.setSize(440, rowHeight * 2 + 25)
            view.doLayout()

            val image = BufferedImage(view.width, view.height, BufferedImage.TYPE_INT_ARGB)
            view.paint(image.createGraphics())
            val event =
                MouseEvent(
                    view.list,
                    MouseEvent.MOUSE_MOVED,
                    0L,
                    0,
                    40,
                    view.list.getCellBounds(7, 7).let { it.y + it.height / 2 },
                    0,
                    false,
                )
            view.list.dispatchEvent(event)

            assertEquals(7, listener.hoveredIndex)
            assertTrue(view.list.visibleRect.intersects(view.list.getCellBounds(7, 7)))
            assertTrue(imageContainsNonTransparentPixel(image))
        }

    @Test
    @Tag("desktop") // Swing's list mouse handler queries the platform shortcut modifier through Toolkit.
    fun `mouse click and wheel remain controller-owned interactions`() =
        onEdt {
            val listener = RecordingListener()
            val view = createView(listener)
            val suggestions = List(2) { suggestion("candidate-$it") }
            val first = snapshot(suggestions, selectedIndex = 0, total = 2, start = 0)
            val latest = snapshot(suggestions, selectedIndex = 0, total = 2, start = 0)
            view.update(first)
            view.update(latest)
            view.size = view.preferredSize
            view.doLayout()

            view.list.dispatchEvent(
                MouseEvent(
                    view.list,
                    MouseEvent.MOUSE_PRESSED,
                    0L,
                    0,
                    40,
                    view.list.visibleRect.y + 2,
                    1,
                    false,
                    MouseEvent.BUTTON1,
                ),
            )
            val wheel =
                MouseWheelEvent(
                    view.list,
                    MouseEvent.MOUSE_WHEEL,
                    0L,
                    0,
                    40,
                    10,
                    0,
                    false,
                    MouseWheelEvent.WHEEL_UNIT_SCROLL,
                    1,
                    2,
                )
            view.list.dispatchEvent(wheel)

            assertEquals(0, listener.clickedIndex)
            assertEquals(2, listener.scrollDelta)
            assertSame(latest, listener.clickedSnapshot)
            assertSame(latest, listener.scrollSnapshot)
            assertTrue(wheel.isConsumed)
        }

    @Test
    fun `tooltips retain bounded full row context`() =
        onEdt {
            val view = createView(RecordingListener())
            view.update(
                snapshot(
                    listOf(suggestion("git checkout feature/terminal", detail = "Switch branch", sourceDisplayText = "Git")),
                    selectedIndex = 0,
                    total = 1,
                    start = 0,
                ),
            )
            view.size = view.preferredSize
            view.doLayout()
            val event =
                MouseEvent(
                    view.list,
                    MouseEvent.MOUSE_MOVED,
                    0L,
                    0,
                    40,
                    view.list.visibleRect.y + 2,
                    0,
                    false,
                )

            val tooltip = view.list.getToolTipText(event)
            assertNotNull(tooltip)
            assertTrue(tooltip!!.contains("git&nbsp;checkout&nbsp;feature/terminal"))
            assertTrue(tooltip.contains("Switch&nbsp;branch"))
            assertTrue(tooltip.contains("Git"))
        }

    @Test
    fun `selection-only updates retain the list model`() =
        onEdt {
            val view = createView(RecordingListener())
            val suggestions = List(3) { suggestion("candidate-$it") }
            view.update(snapshot(suggestions, selectedIndex = 0, total = 3, start = 0))
            val model = view.list.model

            view.update(snapshot(suggestions, selectedIndex = 1, total = 3, start = 0))

            assertSame(model, view.list.model)
        }

    @Test
    fun `list font changes recompute row metrics`() =
        onEdt {
            val view = createView(RecordingListener())
            view.update(snapshot(listOf(suggestion("candidate")), selectedIndex = 0, total = 1, start = 0))
            val initialHeight = view.preferredSize.height

            view.list.font = Font(Font.MONOSPACED, Font.PLAIN, 32)

            assertTrue(view.preferredSize.height > initialHeight)
        }

    @Test
    fun `renderer escapes markup neutralizes controls and bounds hostile graphemes`() =
        onEdt {
            val view = createView(RecordingListener())
            val candidate = suggestion("<html><img src='file:/secret'>\u202e\n" + "x".repeat(8000), detail = "<script>&")
            view.update(snapshot(listOf(candidate), 0, 1, 0))
            val renderer = view.list.cellRenderer.getListCellRendererComponent(view.list, candidate, 0, false, false) as javax.swing.JLabel
            assertTrue(renderer.text.contains("&lt;html&gt;"))
            assertFalse(renderer.text.contains("<img"))
            assertFalse(renderer.text.contains('\u202e'))
            assertFalse(renderer.text.contains('\n'))
            assertTrue(renderer.text.length < 5000)
            val cluster = suggestion("a" + "\u0301".repeat(8000))
            val clusterRenderer =
                view.list.cellRenderer.getListCellRendererComponent(view.list, cluster, 0, false, false) as javax.swing.JLabel
            assertEquals("\u2026", clusterRenderer.accessibleContext.accessibleName)
            view.close()
        }

    @Test
    fun `look and feel colors and accessible selection use the standard list`() =
        onEdt {
            val listener = RecordingListener()
            val view = createView(listener)
            val candidates = List(2) { suggestion("candidate-$it") }
            view.update(snapshot(candidates, -1, 2, 0))
            assertEquals(-1, view.list.selectedIndex)
            view.list.selectionBackground = Color.MAGENTA
            view.list.selectionForeground = Color.YELLOW
            val renderer = view.list.cellRenderer.getListCellRendererComponent(view.list, candidates[1], 1, true, false)
            assertEquals(Color.MAGENTA, renderer.background)
            assertEquals(Color.YELLOW, renderer.foreground)
            view.list.accessibleContext.accessibleSelection
                .addAccessibleSelection(1)
            assertEquals(1, listener.hoveredIndex)
            view.close()
            assertEquals(0, view.list.model.size)
        }

    @Test
    fun `renderer retains matched fragments and bounded preferred width`() =
        onEdt {
            val view = createView(RecordingListener())
            val text = "git status"
            val candidate =
                suggestion(text).copy(
                    matchedRanges = SwingShellSuggestionMatchRanges.fromPackedOffsets(text, intArrayOf(0, 3, 4, 6)),
                )
            view.update(snapshot(listOf(candidate), 0, 1, 0))
            val renderer = view.list.cellRenderer.getListCellRendererComponent(view.list, candidate, 0, false, false) as javax.swing.JLabel
            assertTrue(renderer.text.contains("<b>git</b>&nbsp;<b>st</b>atus"))
            view.update(snapshot(listOf(suggestion("x".repeat(10000))), 0, 1, 0))
            assertTrue(view.preferredSize.width in 220..480)
            view.update(SwingShellSuggestionViewSnapshot.EMPTY)
            assertEquals(java.awt.Dimension(0, 0), view.preferredSize)
            view.close()
        }

    @Test
    fun `renderer and tooltip preserve leading repeated and trailing spaces`() =
        onEdt {
            val view = createView(RecordingListener())
            try {
                fun label(text: String): javax.swing.JLabel =
                    view.list.cellRenderer.getListCellRendererComponent(
                        view.list,
                        suggestion(text),
                        0,
                        false,
                        false,
                    ) as javax.swing.JLabel
                val singleSpaceWidth = label("folder name").preferredSize.width
                assertTrue(label("folder  name").preferredSize.width > singleSpaceWidth)
                val renderer = label(" folder  name ")
                assertTrue(renderer.text.contains("&nbsp;folder&nbsp;&nbsp;name&nbsp;"))
                assertTrue(renderer.toolTipText.contains("&nbsp;folder&nbsp;&nbsp;name&nbsp;"))
                assertEquals(" folder  name ", renderer.accessibleContext.accessibleName)
            } finally {
                view.close()
            }
        }

    private fun createView(
        listener: RecordingListener,
        messages: SwingTerminalMessages = SwingTerminalMessages.forLocale(Locale.ROOT),
    ): SwingCompletionPopupView {
        val parent =
            JPanel(null).apply {
                setSize(800, 600)
                font = Font(Font.MONOSPACED, Font.PLAIN, 13)
                background = Color(0x10, 0x14, 0x18)
                foreground = Color(0xE5, 0xE7, 0xEB)
            }
        return SwingCompletionPopupView(listener, messages).also {
            it.font = Font(Font.MONOSPACED, Font.PLAIN, 13)
            parent.add(it)
        }
    }

    private fun snapshot(
        suggestions: List<SwingShellSuggestion>,
        selectedIndex: Int,
        total: Int,
        start: Int,
    ): SwingShellSuggestionViewSnapshot =
        SwingShellSuggestionViewSnapshot.create(
            visibleSuggestions = suggestions,
            selectedIndex = selectedIndex,
            viewportStartIndex = start,
            totalSuggestionCount = total,
        )

    private fun suggestion(
        displayText: String,
        detail: String = "",
        sourceDisplayText: String = "Built-in",
    ): SwingShellSuggestion =
        SwingShellSuggestion(
            replacementText = displayText,
            replacementStartOffset = 0,
            replacementEndOffset = 0,
            source = "spec",
            kind = "COMMAND",
            displayText = displayText,
            detail = detail,
            sourceDisplayText = sourceDisplayText,
        )

    private fun imageContainsNonTransparentPixel(image: BufferedImage): Boolean {
        var y = 0
        while (y < image.height) {
            var x = 0
            while (x < image.width) {
                if (image.getRGB(x, y) ushr 24 != 0) return true
                x++
            }
            y++
        }
        return false
    }

    private fun onEdt(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) {
            block()
        } else {
            SwingUtilities.invokeAndWait(block)
        }
    }

    private class RecordingListener : SwingShellSuggestionViewListener {
        var hoveredIndex = -1
        var clickedIndex = -1
        var scrollDelta = 0
        var clickedSnapshot: SwingShellSuggestionViewSnapshot? = null
        var scrollSnapshot: SwingShellSuggestionViewSnapshot? = null

        override fun onSuggestionHovered(
            snapshot: SwingShellSuggestionViewSnapshot,
            index: Int,
        ) {
            hoveredIndex = index
        }

        override fun onSuggestionClicked(
            snapshot: SwingShellSuggestionViewSnapshot,
            index: Int,
        ) {
            clickedIndex = index
            clickedSnapshot = snapshot
        }

        override fun onSuggestionScrollRequested(
            snapshot: SwingShellSuggestionViewSnapshot,
            delta: Int,
        ) {
            scrollDelta = delta
            scrollSnapshot = snapshot
        }
    }
}
