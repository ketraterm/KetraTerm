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
import io.github.ketraterm.ui.swing.settings.SwingShellSuggestionStyle
import java.awt.*
import java.awt.event.*
import java.awt.geom.RoundRectangle2D
import java.beans.PropertyChangeListener
import java.util.regex.Pattern
import javax.swing.*
import kotlin.math.roundToInt

/** Standard Swing list presentation; the controller retains selection and acceptance ownership. */
internal class SwingCompletionPopupView(
    private val listener: SwingShellSuggestionViewListener,
    private val messages: SwingTerminalMessages = SwingTerminalMessages.forLocale(),
    initialStyle: SwingShellSuggestionStyle = SwingShellSuggestionStyle(),
) : JPanel(BorderLayout()),
    SwingShellSuggestionView {
    override val component: JComponent get() = this
    private var snapshot = SwingShellSuggestionViewSnapshot.EMPTY
    private var updating = false
    private var closed = false
    internal val list = JList<SwingShellSuggestion>()
    private val renderer = CompletionRenderer(messages, initialStyle)
    private val scrollPane = JScrollPane(list)
    private val position = JLabel()
    private var contentScale = 1f
    private val fontListener = PropertyChangeListener { event -> (event.newValue as? Font)?.let(::applyTypography) }
    private var borderColor = Color(initialStyle.border, true)
    private val selectionListener =
        javax.swing.event.ListSelectionListener {
            if (!updating && !closed && !it.valueIsAdjusting && list.selectedIndex >= 0) {
                listener.onSuggestionHovered(snapshot, list.selectedIndex)
            }
        }
    private val pointerHandler =
        object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) {
                if (!closed && SwingUtilities.isLeftMouseButton(event)) {
                    rowAt(event)?.let { listener.onSuggestionClicked(snapshot, it) }
                }
            }

            override fun mouseMoved(event: MouseEvent) {
                if (!closed) rowAt(event)?.let { listener.onSuggestionHovered(snapshot, it) }
            }

            override fun mouseWheelMoved(event: MouseWheelEvent) {
                if (!closed && event.wheelRotation != 0) {
                    listener.onSuggestionScrollRequested(snapshot, event.wheelRotation.coerceIn(-3, 3))
                    event.consume()
                }
            }
        }

    init {
        isFocusable = false
        isOpaque = false
        border = BorderFactory.createEmptyBorder(6, 6, 6, 6)
        list.isFocusable = false
        list.font = list.font.deriveFont(Font.PLAIN)
        list.background = background
        scrollPane.border = BorderFactory.createEmptyBorder()
        scrollPane.viewport.background = background
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = renderer
        list.visibleRowCount = maximumVisibleSuggestions
        list.accessibleContext.accessibleName = messages.message("completion.accessibleName")
        list.addListSelectionListener(selectionListener)
        list.addMouseListener(pointerHandler)
        list.addMouseMotionListener(pointerHandler)
        list.addMouseWheelListener(pointerHandler)
        ToolTipManager.sharedInstance().registerComponent(list)
        scrollPane.isWheelScrollingEnabled = false
        scrollPane.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        scrollPane.verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
        scrollPane.addMouseWheelListener(pointerHandler)
        scrollPane.viewport.addComponentListener(
            object : ComponentAdapter() {
                override fun componentResized(event: ComponentEvent) = revealSelection()
            },
        )
        position.border = BorderFactory.createEmptyBorder(2, 6, 2, 6)
        position.font = list.font.deriveFont((list.font.size2D - 1f).coerceAtLeast(10f))
        add(scrollPane, BorderLayout.CENTER)
        add(position, BorderLayout.SOUTH)
        addPropertyChangeListener("font", fontListener)
        applyTypography(list.font)
        applyStyle(initialStyle)
    }

    private fun applyTypography(font: Font) {
        contentScale = (font.size2D / COMPLETION_DESIGN_FONT_SIZE).coerceAtLeast(1f / COMPLETION_DESIGN_FONT_SIZE)
        list.font = font
        position.font = font.deriveFont(font.size2D * 12f / COMPLETION_DESIGN_FONT_SIZE)
        val outerInset = (6 * contentScale).roundToInt()
        val footerInset = (2 * contentScale).roundToInt()
        border = BorderFactory.createEmptyBorder(outerInset, outerInset, outerInset, outerInset)
        position.border = BorderFactory.createEmptyBorder(footerInset, outerInset, footerInset, outerInset)
        renderer.applyScale(contentScale)
        revalidate()
        repaint()
    }

    /**
     * Applies built-in colors without changing candidates, selection, or request lifetime.
     *
     * @param style complete immutable palette; each color is applied exactly.
     * @throws IllegalStateException if called outside the EDT.
     */
    fun applyStyle(style: SwingShellSuggestionStyle) {
        check(SwingUtilities.isEventDispatchThread()) { "completion style must update on the EDT" }
        background = Color(style.background, true)
        foreground = Color(style.foreground, true)
        list.background = background
        list.foreground = foreground
        list.selectionBackground = Color(style.selectionBackground, true)
        list.selectionForeground = Color(style.selectionForeground, true)
        scrollPane.background = background
        scrollPane.viewport.background = background
        position.foreground = Color(style.secondaryForeground, true)
        borderColor = Color(style.border, true)
        renderer.style = style
        repaint()
    }

    override fun paintComponent(graphics: Graphics) {
        val canvas = graphics.create() as Graphics2D
        try {
            canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            canvas.color = background
            val arc = (12 * contentScale).roundToInt()
            canvas.fillRoundRect(0, 0, width, height, arc, arc)
            canvas.color = borderColor
            // Keep the entire centered stroke inside the component, including at fractional display scales.
            canvas.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            canvas.stroke = BasicStroke(contentScale)
            val inset = contentScale / 2f
            canvas.draw(RoundRectangle2D.Float(inset, inset, width - contentScale, height - contentScale, arc.toFloat(), arc.toFloat()))
        } finally {
            canvas.dispose()
        }
    }

    override fun update(snapshot: SwingShellSuggestionViewSnapshot) {
        check(SwingUtilities.isEventDispatchThread()) { "completion view must update on the EDT" }
        check(!closed) { "completion view is closed" }
        updating = true
        try {
            if (this.snapshot.visibleSuggestions != snapshot.visibleSuggestions) {
                renderer.update(snapshot.visibleSuggestions)
                list.setListData(snapshot.visibleSuggestions.toTypedArray())
            }
            this.snapshot = snapshot
            list.selectedIndex = snapshot.selectedIndex
            list.visibleRowCount = snapshot.visibleSuggestions.size
            position.isVisible = snapshot.hasSuggestionsBefore || snapshot.hasSuggestionsAfter
            val first = if (snapshot.visibleSuggestions.isEmpty()) 0 else snapshot.viewportStartIndex + 1
            val last = snapshot.viewportStartIndex + snapshot.visibleSuggestions.size
            position.text = messages.message("completion.range", first, last, snapshot.totalSuggestionCount)
            list.accessibleContext.accessibleDescription =
                when (snapshot.totalSuggestionCount) {
                    0 -> messages.message("completion.empty")
                    1 -> messages.message("completion.description.one", snapshot.totalSuggestionCount, first, last)
                    else -> messages.message("completion.description.many", snapshot.totalSuggestionCount, first, last)
                }
            revealSelection()
        } finally {
            updating = false
        }
        revalidate()
        repaint()
    }

    override fun getPreferredSize(): Dimension =
        if (snapshot.visibleSuggestions.isEmpty()) {
            Dimension(0, 0)
        } else {
            super.getPreferredSize().apply {
                width = width.coerceIn((260 * contentScale).roundToInt(), (480 * contentScale).roundToInt())
            }
        }

    override fun doLayout() {
        super.doLayout()
        scrollPane.doLayout()
        scrollPane.viewport.doLayout()
        revealSelection()
    }

    private fun revealSelection() {
        if (list.selectedIndex >= 0) list.ensureIndexIsVisible(list.selectedIndex)
    }

    private fun rowAt(event: MouseEvent): Int? =
        list.locationToIndex(event.point).takeIf {
            it >= 0 && list.getCellBounds(it, it)?.contains(event.point) == true
        }

    override fun close() {
        check(SwingUtilities.isEventDispatchThread()) { "completion view must close on the EDT" }
        if (closed) return
        closed = true
        removePropertyChangeListener("font", fontListener)
        list.removeListSelectionListener(selectionListener)
        list.removeMouseListener(pointerHandler)
        list.removeMouseMotionListener(pointerHandler)
        list.removeMouseWheelListener(pointerHandler)
        scrollPane.removeMouseWheelListener(pointerHandler)
        ToolTipManager.sharedInstance().unregisterComponent(list)
        renderer.update(emptyList())
        list.setListData(emptyArray<SwingShellSuggestion>())
        snapshot = SwingShellSuggestionViewSnapshot.EMPTY
    }
}

private class CompletionRenderer(
    private val messages: SwingTerminalMessages,
    var style: SwingShellSuggestionStyle,
) : DefaultListCellRenderer() {
    private var suggestions = emptyList<SwingShellSuggestion>()
    private var rows = emptyArray<CompletionRow>()
    private var rowBorder = BorderFactory.createEmptyBorder(4, 6, 4, 6)
    private var icons = SwingShellSuggestionAccentRole.entries.map { CompletionIcon(it, COMPLETION_ICON_SIZE) }
    private var cornerArc = 8
    private var selected = false
    private val fallbackAccessibleName = messages.message("completion.suggestionAccessibleName")
    private val roles =
        Array(SwingShellSuggestionAccentRole.entries.size) {
            when (SwingShellSuggestionAccentRole.entries[it]) {
                SwingShellSuggestionAccentRole.COMMAND -> messages.message("completion.role.command")
                SwingShellSuggestionAccentRole.PATH -> messages.message("completion.role.path")
                SwingShellSuggestionAccentRole.OPTION -> messages.message("completion.role.option")
                SwingShellSuggestionAccentRole.HISTORY -> messages.message("completion.role.history")
                SwingShellSuggestionAccentRole.OTHER -> messages.message("completion.role.other")
            }
        }

    fun applyScale(scale: Float) {
        val horizontalInset = (6 * scale).roundToInt()
        val verticalInset = (4 * scale).roundToInt()
        rowBorder = BorderFactory.createEmptyBorder(verticalInset, horizontalInset, verticalInset, horizontalInset)
        iconTextGap = (8 * scale).roundToInt()
        cornerArc = (8 * scale).roundToInt()
        val iconSize = (COMPLETION_ICON_SIZE * scale).roundToInt().coerceAtLeast(1)
        icons = SwingShellSuggestionAccentRole.entries.map { CompletionIcon(it, iconSize) }
    }

    fun update(suggestions: List<SwingShellSuggestion>) {
        val preparedRows = Array(suggestions.size) { presentation(suggestions[it]) }
        this.suggestions = suggestions
        rows = preparedRows
    }

    override fun getListCellRendererComponent(
        list: JList<*>?,
        value: Any?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        super.getListCellRendererComponent(list, "", index, isSelected, cellHasFocus)
        val suggestion = value as? SwingShellSuggestion ?: return this
        val row = if (suggestions.getOrNull(index) === suggestion) rows[index] else presentation(suggestion)
        val matchBackground = if (isSelected) style.selectionMatchBackground else style.matchBackground
        val matchColor = if (isSelected) style.selectionMatchForeground else style.matchForeground
        val hintColor = if (isSelected) style.selectionArgumentHintForeground else style.argumentHintForeground
        text = row.html(matchColor, matchBackground, hintColor, isSelected)
        selected = isSelected
        isOpaque = false
        icon = icons[suggestion.accentRole.ordinal]
        border = rowBorder
        toolTipText = row.tooltip
        getAccessibleContext().accessibleName = row.accessibleName
        getAccessibleContext().accessibleDescription = row.accessibleDescription
        return this
    }

    override fun paintComponent(graphics: Graphics) {
        if (selected) {
            val canvas = graphics.create() as Graphics2D
            try {
                canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                canvas.color = background
                canvas.fillRoundRect(0, 0, width, height, cornerArc, cornerArc)
            } finally {
                canvas.dispose()
            }
        }
        super.paintComponent(graphics)
    }

    private fun presentation(suggestion: SwingShellSuggestion): CompletionRow {
        val primaryLimit = 4096
        val primary = displayText(suggestion.displayText, primaryLimit)
        val matches =
            if (suggestion.displayText.length > primaryLimit) {
                suggestion.matchedRanges.truncatedTo(suggestion.displayText, primary.length - 1)
            } else {
                suggestion.matchedRanges
            }
        val detail = displayText(suggestion.detail, 1024)
        val source = displayText(suggestion.sourceDisplayText, 128)
        val hint = displayText(suggestion.argumentHint, 512)
        val html =
            buildString {
                append("<html>")
                var offset = 0
                for (range in 0 until matches.rangeCount) {
                    val start = matches.startOffset(range)
                    val end = matches.endOffset(range)
                    append(escapeHtml(primary.substring(offset, start)))
                    append("<b>").append(escapeHtml(primary.substring(start, end))).append("</b>")
                    offset = end
                }
                append(escapeHtml(primary.substring(offset)))
                if (hint.isNotEmpty()) append("&nbsp;&nbsp;<span>").append(escapeHtml(hint)).append("</span>")
                append("</html>")
            }
        val role = roles[suggestion.accentRole.ordinal]
        val accessibleDescription =
            if (detail.isEmpty()) {
                messages.message("completion.row.descriptionWithoutDetail", source, role)
            } else {
                messages.message("completion.row.description", detail, source, role)
            }
        return CompletionRow(
            html = html,
            tooltip = detail.takeIf { it.isNotEmpty() }?.let { "<html>" + escapeHtml(it) + "</html>" },
            accessibleName = (primary + if (hint.isEmpty()) "" else " $hint").ifEmpty { fallbackAccessibleName },
            accessibleDescription = accessibleDescription,
        )
    }
}

/** Distinct shapes and contrast-aware accents identify categories without relying on color alone. */
private class CompletionIcon(
    private val role: SwingShellSuggestionAccentRole,
    private val size: Int,
) : Icon {
    override fun getIconWidth(): Int = size

    override fun getIconHeight(): Int = size

    override fun paintIcon(
        component: Component,
        graphics: Graphics,
        x: Int,
        y: Int,
    ) {
        val canvas = graphics.create() as Graphics2D
        try {
            canvas.translate(x, y)
            val scale = size.toDouble() / COMPLETION_ICON_SIZE
            canvas.scale(scale, scale)
            canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val light = LIGHT_SURFACE_COLORS[role.ordinal]
            val dark = DARK_SURFACE_COLORS[role.ordinal]
            val background = component.background.rgb
            val accent =
                if (SwingColors.contrastRatio(light.rgb, background) >=
                    SwingColors.contrastRatio(dark.rgb, background)
                ) {
                    light
                } else {
                    dark
                }
            canvas.color = if (SwingColors.contrastRatio(accent.rgb, background) >= 3.0) accent else component.foreground
            canvas.stroke = STROKE
            when (role) {
                SwingShellSuggestionAccentRole.COMMAND -> {
                    val composite = canvas.composite
                    canvas.composite = TINT
                    canvas.fillRoundRect(1, 2, 13, 11, 3, 3)
                    canvas.composite = composite
                    canvas.drawRoundRect(1, 2, 13, 11, 3, 3)
                    canvas.drawLine(4, 5, 6, 7)
                    canvas.drawLine(6, 7, 4, 9)
                    canvas.drawLine(8, 10, 11, 10)
                }
                SwingShellSuggestionAccentRole.PATH -> {
                    val composite = canvas.composite
                    canvas.composite = TINT
                    canvas.fillRoundRect(1, 6, 13, 7, 2, 2)
                    canvas.composite = composite
                    canvas.drawLine(1, 4, 6, 4)
                    canvas.drawLine(6, 4, 8, 6)
                    canvas.drawLine(8, 6, 14, 6)
                    canvas.drawLine(14, 6, 14, 13)
                    canvas.drawLine(14, 13, 1, 13)
                    canvas.drawLine(1, 13, 1, 4)
                }
                SwingShellSuggestionAccentRole.HISTORY -> {
                    canvas.drawArc(2, 2, 12, 12, 135, -300)
                    canvas.drawLine(1, 2, 1, 6)
                    canvas.drawLine(1, 6, 5, 6)
                    canvas.drawLine(8, 5, 8, 8)
                    canvas.drawLine(8, 8, 11, 9)
                }
                SwingShellSuggestionAccentRole.OPTION -> {
                    canvas.drawLine(2, 5, 5, 5)
                    canvas.drawOval(5, 3, 4, 4)
                    canvas.drawLine(9, 5, 14, 5)
                    canvas.drawLine(2, 11, 8, 11)
                    canvas.drawOval(8, 9, 4, 4)
                    canvas.drawLine(12, 11, 14, 11)
                }
                SwingShellSuggestionAccentRole.OTHER -> {
                    canvas.drawLine(2, 3, 8, 3)
                    canvas.drawLine(8, 3, 14, 9)
                    canvas.drawLine(14, 9, 9, 14)
                    canvas.drawLine(9, 14, 2, 7)
                    canvas.drawLine(2, 7, 2, 3)
                    canvas.fillOval(4, 5, 2, 2)
                }
            }
        } finally {
            canvas.dispose()
        }
    }

    private companion object {
        val STROKE = BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        val TINT: AlphaComposite = AlphaComposite.SrcOver.derive(0.14f)
        val LIGHT_SURFACE_COLORS = arrayOf(Color(0x2E62AE), Color(0x8A6200), Color(0x7549AF), Color(0x18796E), Color(0x526479))
        val DARK_SURFACE_COLORS = arrayOf(Color(0x82AFFC), Color(0xE6B95A), Color(0xBD9AF7), Color(0x6FCFC0), Color(0xAEBBCF))
    }
}

private class CompletionRow(
    private val html: String,
    val tooltip: String?,
    val accessibleName: String,
    val accessibleDescription: String,
) {
    private val hasMatches = "<b>" in html
    private val hasHint = "<span>" in html
    private val matchColors = IntArray(2)
    private val hintColors = IntArray(2)
    private val matchBackgrounds = IntArray(2)
    private val coloredHtml = arrayOfNulls<String>(2)

    /**
     * Colors prepared match spans without reprocessing provider text or matching again.
     * Selected and ordinary markup have separate cache entries; theme changes replace them.
     *
     * @param matchColor opaque ARGB foreground for matched fragments.
     * @param matchBackground opaque ARGB fragment fill.
     * @param hintColor opaque ARGB foreground for display-only argument placeholders.
     * @param selected whether this markup is for the selected row.
     * @return escaped label markup, retaining the ordinary foreground outside matched spans.
     */
    fun html(
        matchColor: Int,
        matchBackground: Int,
        hintColor: Int,
        selected: Boolean,
    ): String {
        if (!hasMatches && !hasHint) return html
        val index = if (selected) 1 else 0
        val cached = coloredHtml[index]
        if (cached != null &&
            matchColors[index] == matchColor &&
            matchBackgrounds[index] == matchBackground &&
            hintColors[index] == hintColor
        ) {
            return cached
        }
        val css =
            buildString {
                append("color:").append(matchColor.toHtmlColor())
                append(";background-color:").append(matchBackground.toHtmlColor())
            }
        val colored =
            html
                .replace("<b>", "<b style=\"$css\">")
                .replace("<span>", "<span style=\"color:${hintColor.toHtmlColor()}\">")
        hintColors[index] = hintColor
        matchColors[index] = matchColor
        matchBackgrounds[index] = matchBackground
        coloredHtml[index] = colored
        return colored
    }
}

private fun Int.toHtmlColor(): String = "#" + (this and 0xFFFFFF).toString(16).padStart(6, '0')

/** Bound hostile provider text without splitting an extended grapheme or exposing display controls. */
private fun displayText(
    text: String,
    limit: Int,
): String {
    var end = text.length
    if (end > limit) {
        val matcher = GRAPHEME.matcher(text).region(0, minOf(text.length, limit + 1))
        end = 0
        while (matcher.find() && matcher.end() <= limit) end = matcher.end()
    }
    return buildString {
        for (index in 0 until end) {
            val char = text[index]
            append(
                if (Character.isISOControl(char) ||
                    char == '\u061c' ||
                    char in '\u200e'..'\u200f' ||
                    char in '\u202a'..'\u202e' ||
                    char in '\u2066'..'\u2069'
                ) {
                    ' '
                } else {
                    char
                },
            )
        }
        if (end < text.length) append('…')
    }
}

private fun escapeHtml(text: String): String =
    text
        .replace("&", "&amp;")
        .replace(" ", "&nbsp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

private val GRAPHEME = Pattern.compile("\\X")
private const val COMPLETION_DESIGN_FONT_SIZE = 13f
private const val COMPLETION_ICON_SIZE = 16
