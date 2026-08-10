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
import java.awt.event.HierarchyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.beans.PropertyChangeListener
import java.util.*
import javax.swing.*
import kotlin.math.min
import kotlin.math.roundToInt

/** Creates the IDE-themed terminal suggestion surface. */
internal object IntellijShellSuggestionViewFactory : SwingShellSuggestionViewFactory {
    override fun create(listener: SwingShellSuggestionViewListener): SwingShellSuggestionView =
        IntellijShellSuggestionView(listener)
}

/**
 * Compact IntelliJ host presentation for terminal suggestions.
 *
 * The view deliberately does not use IntelliJ's editor completion lookup: that
 * component requires a real editor, caret, and popup lifecycle. Instead, this
 * adapter uses stable IntelliJ Swing components and icons while preserving the
 * reusable terminal controller's ordering, navigation, acceptance, and
 * feedback behavior. Colors blend the current IDE lookup palette with the
 * terminal component's active foreground and background, and the item font is
 * inherited from the terminal.
 */
internal class IntellijShellSuggestionView(
    private val listener: SwingShellSuggestionViewListener,
) : SwingShellSuggestionView {
    private var activePalette = SuggestionPalette.defaults()
    private val model = DefaultListModel<SwingShellSuggestion>()
    internal val suggestionList = JBList(model)
    private val host = SuggestionSurface(suggestionList)
    private var suggestions: List<SwingShellSuggestion> = emptyList()
    private var terminalThemeSource: Component? = null
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

    private val terminalThemeListener =
        PropertyChangeListener { event ->
            if (event.propertyName in TERMINAL_THEME_PROPERTIES) refreshThemeOnEdt()
        }

    private val lookAndFeelListener =
        PropertyChangeListener { event ->
            if (event.propertyName == LOOK_AND_FEEL_PROPERTY) refreshThemeOnEdt()
        }

    init {
        suggestionList.apply {
            cellRenderer = SuggestionCellRenderer { activePalette }
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
        host.addHierarchyListener { event ->
            if (event.changeFlags and HierarchyEvent.PARENT_CHANGED.toLong() != 0L) {
                bindTerminalThemeSource()
            }
        }
        UIManager.addPropertyChangeListener(lookAndFeelListener)
        refreshTheme()
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
        refreshTheme()
    }

    override fun close() {
        if (closed) return
        closed = true
        UIManager.removePropertyChangeListener(lookAndFeelListener)
        terminalThemeSource?.removePropertyChangeListener(terminalThemeListener)
        terminalThemeSource = null
        suggestionList.removeMouseListener(pointerAdapter)
        suggestionList.removeMouseMotionListener(pointerAdapter)
        model.removeAllElements()
    }

    private fun bindTerminalThemeSource() {
        val nextSource = host.parent
        if (terminalThemeSource === nextSource) return
        terminalThemeSource?.removePropertyChangeListener(terminalThemeListener)
        terminalThemeSource = nextSource
        nextSource?.addPropertyChangeListener(terminalThemeListener)
        refreshTheme()
    }

    private fun refreshThemeOnEdt() {
        if (SwingUtilities.isEventDispatchThread()) {
            refreshTheme()
        } else {
            SwingUtilities.invokeLater {
                if (!closed) refreshTheme()
            }
        }
    }

    private fun refreshTheme() {
        if (closed) return
        activePalette = resolvePalette(host.parent)
        val terminalFont = host.parent?.font ?: UIUtil.getLabelFont()
        suggestionList.font = terminalFont
        suggestionList.background = activePalette.surface
        suggestionList.foreground = activePalette.foreground
        host.palette = activePalette
        updatePreferredSize()
        host.revalidate()
        host.repaint()
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
        private const val ROW_HEIGHT = 30
        private const val ROW_HORIZONTAL_INSET = 4
        private const val MIN_WIDTH = 300
        private const val MAX_WIDTH = 620
        private const val PARENT_HORIZONTAL_MARGIN = 24
        private const val LOOK_AND_FEEL_PROPERTY = "lookAndFeel"
        private val TERMINAL_THEME_PROPERTIES = setOf("background", "foreground", "font")
    }
}

private class SuggestionSurface(
    list: JList<SwingShellSuggestion>,
) : JPanel(BorderLayout()) {
    var palette: SuggestionPalette = SuggestionPalette.defaults()

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
            val shadowInset = JBUI.scale(SHADOW_INSET)
            val arc = JBUI.scale(CORNER_ARC)
            val contentWidth = width - shadowInset * 2
            val contentHeight = height - shadowInset * 2
            if (contentWidth <= 0 || contentHeight <= 0) return

            graphics2D.color = palette.shadow
            graphics2D.fillRoundRect(
                shadowInset - 1,
                shadowInset + 1,
                contentWidth + 2,
                contentHeight + 1,
                arc,
                arc,
            )
            graphics2D.color = palette.surface
            graphics2D.fillRoundRect(shadowInset, shadowInset, contentWidth, contentHeight, arc, arc)
            graphics2D.color = palette.border
            graphics2D.drawRoundRect(shadowInset, shadowInset, contentWidth - 1, contentHeight - 1, arc, arc)
        } finally {
            graphics2D.dispose()
        }
    }

    private companion object {
        private const val SURFACE_INSET = 4
        private const val SHADOW_INSET = 3
        private const val CORNER_ARC = 10
    }
}

private class SuggestionCellRenderer(
    private val paletteProvider: () -> SuggestionPalette,
) : ColoredListCellRenderer<SwingShellSuggestion>() {

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
        val palette = paletteProvider()
        val foreground = if (selected) palette.selectionForeground else palette.foreground
        val muted = if (selected) palette.selectionMuted else palette.muted
        background = if (selected) palette.selection else palette.surface
        this.foreground = foreground
        font = list.font ?: UIUtil.getLabelFont()
        icon = iconFor(value)

        append(value.displayText, textAttributes(foreground), true)
        val detail = value.detail.trim()
        if (detail.isNotEmpty()) {
            append("  $detail", textAttributes(muted))
        }

        val source = sourceLabel(value.source)
        if (source.isNotEmpty()) {
            val sourceWidth = getFontMetrics(font).stringWidth(source)
            val sourceStart = list.width - sourceWidth - JBUI.scale(SOURCE_RIGHT_INSET)
            if (sourceStart > 0) appendTextPadding(sourceStart) else append("  ", textAttributes(muted))
            append(source, textAttributes(muted))
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

private fun textAttributes(color: Color): SimpleTextAttributes =
    SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, color)

private data class SuggestionPalette(
    val surface: Color,
    val foreground: Color,
    val muted: Color,
    val selection: Color,
    val selectionForeground: Color,
    val selectionMuted: Color,
    val border: Color,
    val shadow: Color,
) {
    companion object {
        fun defaults(): SuggestionPalette = resolvePalette(null)
    }
}

private fun resolvePalette(terminal: Component?): SuggestionPalette {
    val terminalBackground = terminal?.background ?: UIUtil.getListBackground()
    val terminalForeground = terminal?.foreground ?: UIUtil.getListForeground()
    val ideSurface = namedColor("CompletionPopup.background", UIUtil.getListBackground())
    val ideForeground = namedColor("CompletionPopup.foreground", UIUtil.getListForeground())
    val ideMuted = namedColor("Label.infoForeground", JBColor.GRAY)
    val ideSelection =
        namedColor(
            "CompletionPopup.selectionBackground",
            UIManager.getColor("List.selectionBackground") ?: JBColor(0xDCEBFC, 0x2F65A7),
        )
    val ideSelectionForeground =
        namedColor(
            "CompletionPopup.selectionForeground",
            UIManager.getColor("List.selectionForeground") ?: ideForeground,
        )
    val ideBorder =
        namedColor(
            "Component.borderColor",
            UIManager.getColor("Separator.foreground") ?: blend(terminalBackground, terminalForeground, 0.25),
        )
    val surface = blend(terminalBackground, ideSurface, 0.28)
    val foreground = blend(terminalForeground, ideForeground, 0.35)
    val selection = blend(surface, ideSelection, 0.62)
    val selectionForeground = blend(terminalForeground, ideSelectionForeground, 0.60)
    return SuggestionPalette(
        surface = surface,
        foreground = foreground,
        muted = blend(foreground, ideMuted, 0.55),
        selection = selection,
        selectionForeground = selectionForeground,
        selectionMuted = blend(selectionForeground, selection, 0.38),
        border = blend(surface, ideBorder, 0.55),
        shadow = Color(0, 0, 0, if (isDark(surface)) 105 else 50),
    )
}

private fun namedColor(
    key: String,
    fallback: Color,
): Color = JBColor.namedColor(key, fallback)

private fun blend(
    base: Color,
    overlay: Color,
    overlayRatio: Double,
): Color {
    val ratio = overlayRatio.coerceIn(0.0, 1.0)
    val baseRatio = 1.0 - ratio
    return Color(
        (base.red * baseRatio + overlay.red * ratio).roundToInt(),
        (base.green * baseRatio + overlay.green * ratio).roundToInt(),
        (base.blue * baseRatio + overlay.blue * ratio).roundToInt(),
    )
}

private fun isDark(color: Color): Boolean =
    color.red * 0.2126 + color.green * 0.7152 + color.blue * 0.0722 < 128.0

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
