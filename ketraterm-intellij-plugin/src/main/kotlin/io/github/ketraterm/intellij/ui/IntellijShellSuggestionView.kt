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

import com.intellij.icons.AllIcons
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestion
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionView
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionViewFactory
import io.github.ketraterm.ui.swing.suggestion.SwingShellSuggestionViewListener
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.*
import javax.swing.*
import kotlin.math.min

/** Creates the IDE-themed terminal suggestion surface. */
internal object IntellijShellSuggestionViewFactory : SwingShellSuggestionViewFactory {
    override fun create(listener: SwingShellSuggestionViewListener): SwingShellSuggestionView =
        IntellijShellSuggestionView(listener)
}

/**
 * Native IntelliJ presentation for terminal suggestions.
 *
 * Employs standard IntelliJ [JBList] and [ColoredListCellRenderer] components,
 * automatically acquiring IDE theme colors, fonts, selection highlights, and HiDPI scaling.
 */
internal class IntellijShellSuggestionView(
    private val listener: SwingShellSuggestionViewListener,
) : SwingShellSuggestionView {
    private val model = DefaultListModel<SwingShellSuggestion>()
    internal val suggestionList = JBList(model)
    private val host = SuggestionSurface(suggestionList)
    private var suggestions: List<SwingShellSuggestion> = emptyList()
    private var closed = false

    private val pointerAdapter =
        object : MouseAdapter() {
            override fun mouseMoved(event: MouseEvent) {
                suggestionList.indexAt(event.point)?.let(listener::onSuggestionHovered)
            }

            override fun mousePressed(event: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(event)) return
                suggestionList.indexAt(event.point)?.let(listener::onSuggestionClicked)
                event.consume()
            }
        }

    init {
        suggestionList.apply {
            cellRenderer = SuggestionCellRenderer()
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            fixedCellHeight = JBUI.scale(ROW_HEIGHT)
            visibleRowCount = MAX_VISIBLE_ROWS
            isFocusable = false
            isOpaque = false
            border = JBUI.Borders.empty(0, JBUI.scale(ROW_HORIZONTAL_INSET))
            accessibleContext.accessibleName = "Terminal suggestions"
            addMouseListener(pointerAdapter)
            addMouseMotionListener(pointerAdapter)
        }
    }

    override val component: JComponent get() = host

    override fun update(
        suggestions: List<SwingShellSuggestion>,
        selectedIndex: Int,
    ) {
        check(!closed) { "Suggestion view is closed" }
        val visibleSuggestions = suggestions.take(MAX_VISIBLE_ROWS)
        if (this.suggestions != visibleSuggestions) {
            this.suggestions = visibleSuggestions
            model.removeAllElements()
            visibleSuggestions.forEach(model::addElement)
        }
        suggestionList.selectedIndex = selectedIndex.takeIf { it in visibleSuggestions.indices } ?: NO_SELECTION
        if (suggestionList.selectedIndex >= 0) {
            suggestionList.ensureIndexIsVisible(suggestionList.selectedIndex)
        }
        updatePreferredSize()
        host.revalidate()
        host.repaint()
    }

    override fun close() {
        if (closed) return
        closed = true
        suggestionList.removeMouseListener(pointerAdapter)
        suggestionList.removeMouseMotionListener(pointerAdapter)
        model.removeAllElements()
    }

    private fun updatePreferredSize() {
        if (suggestions.isEmpty()) {
            host.preferredSize = Dimension(0, 0)
            return
        }
        val insets = host.insets
        val availableWidth =
            host.parent
                ?.width
                ?.minus(JBUI.scale(PARENT_HORIZONTAL_MARGIN))
                ?.takeIf { it > 0 }
                ?: JBUI.scale(MAX_WIDTH)
        val maximumWidth = min(JBUI.scale(MAX_WIDTH), availableWidth).coerceAtLeast(1)
        val minimumWidth = min(JBUI.scale(MIN_WIDTH), maximumWidth)
        val naturalWidth = suggestionList.preferredSize.width + insets.left + insets.right
        val width = naturalWidth.coerceIn(minimumWidth, maximumWidth)
        val height = suggestionList.fixedCellHeight * suggestions.size + insets.top + insets.bottom
        host.preferredSize = Dimension(width, height)
    }

    private fun JList<*>.indexAt(point: Point): Int? {
        val index = locationToIndex(point)
        if (index !in suggestions.indices) return null
        return index.takeIf { getCellBounds(index, index)?.contains(point) == true }
    }

    private companion object {
        private const val MAX_VISIBLE_ROWS = 8
        private const val NO_SELECTION = -1
        private const val ROW_HEIGHT = 28
        private const val ROW_HORIZONTAL_INSET = 4
        private const val MIN_WIDTH = 300
        private const val MAX_WIDTH = 620
        private const val PARENT_HORIZONTAL_MARGIN = 24
    }
}

private class SuggestionSurface(
    list: JList<SwingShellSuggestion>,
) : JPanel(BorderLayout()) {
    init {
        isOpaque = false
        isFocusable = false
        border = JBUI.Borders.empty(JBUI.scale(SURFACE_INSET))
        add(list, BorderLayout.CENTER)
    }

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val graphics2D = graphics.create() as Graphics2D
        try {
            graphics2D.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val arc = JBUI.scale(CORNER_ARC)
            if (width <= 0 || height <= 0) return

            graphics2D.color = JBColor.namedColor("CompletionPopup.background", UIUtil.getListBackground())
            graphics2D.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
            graphics2D.color = JBColor.border()
            graphics2D.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
        } finally {
            graphics2D.dispose()
        }
    }

    private companion object {
        private const val SURFACE_INSET = 4
        private const val CORNER_ARC = 10
    }
}

private class SuggestionCellRenderer : ColoredListCellRenderer<SwingShellSuggestion>() {
    init {
        isOpaque = true
        ipad = JBUI.insets(0, CELL_HORIZONTAL_INSET)
        iconTextGap = JBUI.scale(ICON_TEXT_GAP)
    }

    override fun customizeCellRenderer(
        list: JList<out SwingShellSuggestion>,
        value: SwingShellSuggestion,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean,
    ) {
        font = list.font ?: UIUtil.getLabelFont()
        icon = iconFor(value)

        append(value.displayText, SimpleTextAttributes.REGULAR_ATTRIBUTES, true)
        val detail = value.detail.trim()
        if (detail.isNotEmpty()) {
            append("  $detail", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }

        val source = sourceLabel(value.source)
        if (source.isNotEmpty()) {
            val sourceWidth = getFontMetrics(font).stringWidth(source)
            val sourceStart = list.width - sourceWidth - JBUI.scale(SOURCE_RIGHT_INSET)
            if (sourceStart > 0) appendTextPadding(sourceStart) else append("  ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            append(source, SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }

        accessibleContext?.accessibleName =
            buildString {
                append(value.displayText)
                if (detail.isNotEmpty()) append(", $detail")
                if (source.isNotEmpty()) append(", $source")
            }
    }

    private companion object {
        private const val CELL_HORIZONTAL_INSET = 8
        private const val ICON_TEXT_GAP = 6
        private const val SOURCE_RIGHT_INSET = 12
    }
}

private fun iconFor(suggestion: SwingShellSuggestion): Icon {
    val kind = suggestion.kind.uppercase(Locale.ROOT)
    return when (kind) {
        "PATH" -> AllIcons.Nodes.Folder
        "OPTION", "ARGUMENT" -> AllIcons.Nodes.Parameter
        else -> AllIcons.Actions.Execute
    }
}

private fun sourceLabel(source: String): String {
    val normalized = source.lowercase(Locale.ROOT)
    return when {
        "gradle" in normalized -> "Gradle"
        "git" in normalized -> "Git"
        "path" in normalized || "file" in normalized -> "Path"
        "mru" in normalized || "history" in normalized -> "Recent"
        "stats" in normalized -> "Learned"
        normalized == "spec" -> "Built-in"
        else ->
            normalized
                .removePrefix("intellij-")
                .replace('-', ' ')
                .replaceFirstChar { character -> character.titlecase(Locale.ROOT) }
    }
}
