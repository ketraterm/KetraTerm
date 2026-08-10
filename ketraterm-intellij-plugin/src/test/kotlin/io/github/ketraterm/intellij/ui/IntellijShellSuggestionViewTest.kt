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

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestion
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionViewListener
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Container
import java.awt.Font
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.JPanel

class IntellijShellSuggestionViewTest : BasePlatformTestCase() {
    fun testViewRendersCustomIdeThemedRowsWithoutEditorLookup() {
        val view = IntellijShellSuggestionView(RecordingListener())
        val parent = terminalParent()
        parent.add(view.component, BorderLayout.CENTER)
        view.update(suggestions(3), selectedIndex = 1)

        try {
            val list = view.suggestionList
            assertEquals(3, list.model.size)
            assertEquals(1, list.selectedIndex)
            assertSame(list, view.component.getComponent(0))
            assertFalse(descendants(view.component).any { it.javaClass.name.contains("codeInsight.lookup") })
            assertTrue(view.component.preferredSize.width in 300..620)
            assertTrue(view.component.preferredSize.height > 0)
            assertTrue("suggestion surface rendered as a blank block", renderedColorCount(view.component) > 8)
            assertTrue(
                "suggestion rows rendered without visible content",
                renderedFirstRowInk(view.component, list.background, list.fixedCellHeight) > 40,
            )
        } finally {
            view.close()
        }
    }

    fun testViewTracksTerminalPaletteAndFont() {
        val view = IntellijShellSuggestionView(RecordingListener())
        val parent = terminalParent()
        parent.add(view.component, BorderLayout.CENTER)
        view.update(suggestions(2), selectedIndex = -1)

        try {
            val darkSurface = view.suggestionList.background
            assertTrue(darkSurface.red < 100 && darkSurface.green < 100 && darkSurface.blue < 100)
            parent.background = Color(245, 246, 248)
            parent.foreground = Color(25, 27, 31)
            parent.font = Font(Font.MONOSPACED, Font.PLAIN, 17)

            assertFalse(darkSurface == view.suggestionList.background)
            assertTrue(view.suggestionList.background.red > 150)
            assertEquals(parent.font, view.suggestionList.font)
        } finally {
            view.close()
        }
    }

    fun testViewCapsSnapshotsAndRejectsSelectionOutsideVisibleRows() {
        val view = IntellijShellSuggestionView(RecordingListener())
        view.update(suggestions(20), selectedIndex = 19)

        try {
            assertEquals(8, view.suggestionList.model.size)
            assertEquals(-1, view.suggestionList.selectedIndex)

            view.update(suggestions(20), selectedIndex = 7)
            assertEquals(7, view.suggestionList.selectedIndex)
        } finally {
            view.close()
        }
    }

    fun testPointerHoverAndClickReportVisibleRow() {
        val listener = RecordingListener()
        val view = IntellijShellSuggestionView(listener)
        view.update(suggestions(3), selectedIndex = -1)
        val list = view.suggestionList
        list.setSize(400, list.fixedCellHeight * 3)
        list.doLayout()
        val row = checkNotNull(list.getCellBounds(1, 1))
        val x = row.x + row.width / 2
        val y = row.y + row.height / 2

        try {
            list.dispatchEvent(mouseEvent(list, MouseEvent.MOUSE_MOVED, x, y, MouseEvent.NOBUTTON))
            list.dispatchEvent(mouseEvent(list, MouseEvent.MOUSE_PRESSED, x, y, MouseEvent.BUTTON1))

            assertEquals(1, listener.hoveredIndex)
            assertEquals(1, listener.clickedIndex)
        } finally {
            view.close()
        }
    }

    private fun suggestions(count: Int): List<SwingShellSuggestion> =
        List(count) { index ->
            SwingShellSuggestion(
                replacementText = "candidate-$index",
                replacementStartOffset = 0,
                replacementEndOffset = 0,
                source = if (index % 2 == 0) "intellij-gradle-task" else "intellij-project-file",
                kind = if (index % 2 == 0) "SUBCOMMAND" else "PATH",
                displayText = "candidate-$index",
                detail = "detail-$index",
            )
        }

    private fun terminalParent(): JPanel =
        JPanel(BorderLayout()).apply {
            background = Color(24, 26, 29)
            foreground = Color(220, 223, 228)
            font = Font(Font.MONOSPACED, Font.PLAIN, 15)
            setSize(900, 500)
        }

    private fun descendants(root: Container): Sequence<java.awt.Component> =
        sequence {
            root.components.forEach { child ->
                yield(child)
                if (child is Container) yieldAll(descendants(child))
            }
        }

    private fun renderedColorCount(component: java.awt.Component): Int {
        val image = render(component)
        val colors = HashSet<Int>()
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val color = image.getRGB(x, y)
                if (color ushr 24 != 0) colors += color
            }
        }
        return colors.size
    }

    private fun renderedFirstRowInk(
        component: java.awt.Component,
        rowBackground: Color,
        rowHeight: Int,
    ): Int {
        val image = render(component)
        var pixels = 0
        for (y in 8 until minOf(image.height, rowHeight)) {
            for (x in 16 until image.width - 16) {
                val color = image.getRGB(x, y)
                if (color ushr 24 != 0 && color != rowBackground.rgb) pixels++
            }
        }
        return pixels
    }

    private fun render(component: java.awt.Component): BufferedImage {
        component.size = component.preferredSize
        layoutTree(component as Container)
        val image = BufferedImage(component.width, component.height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            component.paint(graphics)
        } finally {
            graphics.dispose()
        }
        return image
    }

    private fun layoutTree(container: Container) {
        container.doLayout()
        container.components.filterIsInstance<Container>().forEach(::layoutTree)
    }

    private fun mouseEvent(
        source: java.awt.Component,
        id: Int,
        x: Int,
        y: Int,
        button: Int,
    ): MouseEvent =
        MouseEvent(
            source,
            id,
            System.currentTimeMillis(),
            0,
            x,
            y,
            1,
            false,
            button,
        )

    private class RecordingListener : SwingShellSuggestionViewListener {
        var hoveredIndex: Int = -1
            private set
        var clickedIndex: Int = -1
            private set

        override fun onSuggestionHovered(index: Int) {
            hoveredIndex = index
        }

        override fun onSuggestionClicked(index: Int) {
            clickedIndex = index
        }
    }
}
