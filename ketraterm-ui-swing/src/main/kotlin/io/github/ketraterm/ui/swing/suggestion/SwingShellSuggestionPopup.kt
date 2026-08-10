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

import java.awt.*
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.SwingUtilities
import kotlin.math.min

internal class SwingShellSuggestionPopup(
    private val listener: SwingShellSuggestionViewListener,
) : JComponent(),
    SwingShellSuggestionView {
    private var suggestions: List<SwingShellSuggestion> = emptyList()
    private var selectedIndex: Int = NO_SELECTION
    private val preparedLayout = SwingShellSuggestionPopupLayout()
    private var colors = PopupColors.DEFAULT
    private var colorKey: Long = Long.MIN_VALUE

    override val component: JComponent get() = this

    init {
        isOpaque = false
        isFocusable = false
        addMouseMotionListener(
            object : MouseAdapter() {
                override fun mouseMoved(event: MouseEvent) {
                    val row = rowAt(event.y)
                    if (row != NO_SELECTION) listener.onSuggestionHovered(row)
                }
            },
        )
        addMouseListener(
            object : MouseAdapter() {
                override fun mousePressed(event: MouseEvent) {
                    if (!SwingUtilities.isLeftMouseButton(event)) return
                    val row = rowAt(event.y)
                    if (row != NO_SELECTION) {
                        listener.onSuggestionClicked(row)
                        event.consume()
                    }
                }
            },
        )
        addComponentListener(
            object : ComponentAdapter() {
                override fun componentResized(event: ComponentEvent) {
                    prepareLayout()
                }
            },
        )
        addPropertyChangeListener("font") {
            prepareLayout()
        }
    }

    override fun update(
        suggestions: List<SwingShellSuggestion>,
        selectedIndex: Int,
    ) {
        if (this.suggestions != suggestions) {
            this.suggestions = suggestions
            prepareLayout()
            revalidate()
        }
        this.selectedIndex = selectedIndex
        repaint()
    }

    override fun getPreferredSize(): Dimension {
        if (suggestions.isEmpty()) return Dimension(0, 0)
        val rows = min(suggestions.size, POPUP_MAX_VISIBLE_ROWS)
        return Dimension(
            preparedLayout.preferredWidth,
            SURFACE_INSET * 2 + VERTICAL_PADDING * 2 + rows * ROW_HEIGHT,
        )
    }

    override fun paintComponent(graphics: Graphics) {
        val g = graphics as? Graphics2D ?: return
        val previousColor = g.color
        val previousFont = g.font
        val previousAntialiasing = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING)
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            resolveColors()
            paintChrome(g)
            paintRows(g)
        } finally {
            g.color = previousColor
            g.font = previousFont
            g.setRenderingHint(
                RenderingHints.KEY_ANTIALIASING,
                previousAntialiasing ?: RenderingHints.VALUE_ANTIALIAS_DEFAULT,
            )
        }
    }

    private fun paintChrome(g: Graphics2D) {
        var shadowInset = 0
        while (shadowInset < SURFACE_INSET) {
            g.color = Color(0, 0, 0, SHADOW_ALPHA - shadowInset * SHADOW_ALPHA_STEP)
            g.drawRoundRect(
                shadowInset,
                shadowInset + 1,
                width - shadowInset * 2 - 1,
                height - shadowInset * 2 - 2,
                ARC + shadowInset,
                ARC + shadowInset,
            )
            shadowInset++
        }
        g.color = colors.background
        g.fillRoundRect(
            SURFACE_INSET,
            SURFACE_INSET,
            width - SURFACE_INSET * 2 - 1,
            height - SURFACE_INSET * 2 - 1,
            ARC,
            ARC,
        )
        g.color = colors.border
        g.drawRoundRect(
            SURFACE_INSET,
            SURFACE_INSET,
            width - SURFACE_INSET * 2 - 1,
            height - SURFACE_INSET * 2 - 1,
            ARC,
            ARC,
        )
    }

    private fun paintRows(g: Graphics2D) {
        val count = preparedLayout.rowCount
        var index = 0
        while (index < count) {
            val top = SURFACE_INSET + VERTICAL_PADDING + index * ROW_HEIGHT
            val row = preparedLayout.row(index)
            if (index == selectedIndex) {
                g.color = colors.selectedBackground
                g.fillRoundRect(
                    SURFACE_INSET + POPUP_ROW_INSET - 4,
                    top + 2,
                    width - (SURFACE_INSET + POPUP_ROW_INSET - 4) * 2,
                    ROW_HEIGHT - 4,
                    ROW_ARC,
                    ROW_ARC,
                )
            }

            paintMarker(g, row, top)

            val textX = SURFACE_INSET + POPUP_ROW_INSET + POPUP_TEXT_LEFT_OFFSET
            val rightLimit = width - SURFACE_INSET - POPUP_ROW_INSET
            g.font = preparedLayout.textFont
            g.color = colors.text
            g.drawString(row.displayText, textX, top + PRIMARY_BASELINE)

            if (row.detail.isNotEmpty()) {
                g.font = preparedLayout.detailFont
                g.color = colors.detail
                g.drawString(row.detail, textX, top + DETAIL_BASELINE)
            }

            if (row.sourceWidth > 0) {
                paintSource(g, row, rightLimit - row.sourceWidth, top + SOURCE_TOP)
            }
            index++
        }
    }

    private fun paintSource(
        g: Graphics2D,
        row: SwingShellSuggestionPopupRow,
        x: Int,
        y: Int,
    ) {
        g.color = colors.sourceBackground
        g.fillRoundRect(x, y, row.sourceWidth, SOURCE_HEIGHT, SOURCE_ARC, SOURCE_ARC)
        g.font = preparedLayout.sourceFont
        g.color = colors.sourceText
        g.drawString(row.sourceLabel, x + POPUP_SOURCE_HORIZONTAL_PADDING, y + SOURCE_BASELINE)
    }

    private fun prepareLayout() {
        preparedLayout.prepare(
            component = this,
            suggestions = suggestions,
            availableWidth = if (width > 0) width - SURFACE_INSET * 2 else POPUP_MAX_WIDTH,
        )
    }

    private fun paintMarker(
        g: Graphics2D,
        row: SwingShellSuggestionPopupRow,
        top: Int,
    ) {
        val accent = colors.accent(row.accentRole)
        val left = SURFACE_INSET + POPUP_ROW_INSET
        val markerTop = top + MARKER_TOP
        g.color = colors.markerBackground(row.accentRole)
        g.fillRoundRect(left, markerTop, MARKER_SIZE, MARKER_SIZE, MARKER_ARC, MARKER_ARC)
        g.color = accent
        g.fillOval(left + MARKER_DOT_INSET, markerTop + MARKER_DOT_INSET, MARKER_DOT_SIZE, MARKER_DOT_SIZE)
    }

    private fun resolveColors() {
        val parentBackground = parent?.background ?: DEFAULT_PARENT_BACKGROUND
        val parentForeground = parent?.foreground ?: DEFAULT_PARENT_FOREGROUND
        val key = (parentBackground.rgb.toLong() shl Integer.SIZE) xor parentForeground.rgb.toLong()
        if (key == colorKey) return
        colorKey = key
        colors = PopupColors.from(parentBackground, parentForeground)
    }

    private fun rowAt(y: Int): Int {
        val contentTop = SURFACE_INSET + VERTICAL_PADDING
        if (y < contentTop) return NO_SELECTION
        val row = (y - contentTop) / ROW_HEIGHT
        return if (row in suggestions.indices && row < POPUP_MAX_VISIBLE_ROWS) row else NO_SELECTION
    }

    private companion object {
        private const val NO_SELECTION = -1
        private const val ROW_HEIGHT = 52
        private const val VERTICAL_PADDING = 7
        private const val SURFACE_INSET = 4
        private const val ARC = 16
        private const val ROW_ARC = 10
        private const val MARKER_SIZE = 24
        private const val MARKER_TOP = 8
        private const val MARKER_ARC = 8
        private const val MARKER_DOT_INSET = 8
        private const val MARKER_DOT_SIZE = 8
        private const val PRIMARY_BASELINE = 21
        private const val DETAIL_BASELINE = 40
        private const val SOURCE_TOP = 10
        private const val SOURCE_HEIGHT = 18
        private const val SOURCE_BASELINE = 13
        private const val SOURCE_ARC = 8
        private const val SHADOW_ALPHA = 36
        private const val SHADOW_ALPHA_STEP = 7
        private val DEFAULT_PARENT_BACKGROUND = Color(0xFF101418.toInt(), true)
        private val DEFAULT_PARENT_FOREGROUND = Color(0xFFE5E7EB.toInt(), true)
    }
}

private data class PopupColors(
    val background: Color,
    val border: Color,
    val selectedBackground: Color,
    val text: Color,
    val detail: Color,
    val sourceBackground: Color,
    val sourceText: Color,
    val commandAccent: Color,
    val pathAccent: Color,
    val optionAccent: Color,
    val historyAccent: Color,
    val otherAccent: Color,
    val commandMarkerBackground: Color,
    val pathMarkerBackground: Color,
    val optionMarkerBackground: Color,
    val historyMarkerBackground: Color,
    val otherMarkerBackground: Color,
) {
    fun accent(role: SwingShellSuggestionAccentRole): Color =
        when (role) {
            SwingShellSuggestionAccentRole.COMMAND -> commandAccent
            SwingShellSuggestionAccentRole.PATH -> pathAccent
            SwingShellSuggestionAccentRole.OPTION -> optionAccent
            SwingShellSuggestionAccentRole.HISTORY -> historyAccent
            SwingShellSuggestionAccentRole.OTHER -> otherAccent
        }

    fun markerBackground(role: SwingShellSuggestionAccentRole): Color =
        when (role) {
            SwingShellSuggestionAccentRole.COMMAND -> commandMarkerBackground
            SwingShellSuggestionAccentRole.PATH -> pathMarkerBackground
            SwingShellSuggestionAccentRole.OPTION -> optionMarkerBackground
            SwingShellSuggestionAccentRole.HISTORY -> historyMarkerBackground
            SwingShellSuggestionAccentRole.OTHER -> otherMarkerBackground
        }

    companion object {
        val DEFAULT = from(Color(0xFF101418.toInt(), true), Color(0xFFE5E7EB.toInt(), true))

        fun from(
            parentBackground: Color,
            parentForeground: Color,
        ): PopupColors {
            val dark = luminance(parentBackground) < 0.5
            val background = mix(parentBackground, if (dark) Color.WHITE else Color.BLACK, if (dark) 0.10 else 0.04)
            val border = mix(background, parentForeground, if (dark) 0.20 else 0.14)
            val selectionAccent = if (dark) Color(0xFF4C8DFF.toInt(), true) else Color(0xFF2F6FDE.toInt(), true)
            val commandAccent = if (dark) Color(0xFF70D6B0.toInt(), true) else Color(0xFF168A62.toInt(), true)
            val pathAccent = if (dark) Color(0xFF69A7FF.toInt(), true) else Color(0xFF2F6FDE.toInt(), true)
            val optionAccent = if (dark) Color(0xFFFFC857.toInt(), true) else Color(0xFFB87500.toInt(), true)
            val historyAccent = if (dark) Color(0xFFD892FF.toInt(), true) else Color(0xFF8C45B5.toInt(), true)
            val otherAccent = if (dark) Color(0xFFA7B0BE.toInt(), true) else Color(0xFF667085.toInt(), true)
            return PopupColors(
                background = background,
                border = border,
                selectedBackground = mix(background, selectionAccent, if (dark) 0.26 else 0.15),
                text = mix(parentForeground, background, if (dark) 0.04 else 0.08),
                detail = mix(parentForeground, background, if (dark) 0.42 else 0.38),
                sourceBackground = mix(background, parentForeground, if (dark) 0.12 else 0.08),
                sourceText = mix(parentForeground, background, if (dark) 0.28 else 0.30),
                commandAccent = commandAccent,
                pathAccent = pathAccent,
                optionAccent = optionAccent,
                historyAccent = historyAccent,
                otherAccent = otherAccent,
                commandMarkerBackground = withAlpha(commandAccent, MARKER_BACKGROUND_ALPHA),
                pathMarkerBackground = withAlpha(pathAccent, MARKER_BACKGROUND_ALPHA),
                optionMarkerBackground = withAlpha(optionAccent, MARKER_BACKGROUND_ALPHA),
                historyMarkerBackground = withAlpha(historyAccent, MARKER_BACKGROUND_ALPHA),
                otherMarkerBackground = withAlpha(otherAccent, MARKER_BACKGROUND_ALPHA),
            )
        }

        private const val MARKER_BACKGROUND_ALPHA = 44

        private fun luminance(color: Color): Double = (color.red * 0.2126 + color.green * 0.7152 + color.blue * 0.0722) / 255.0

        private fun mix(
            first: Color,
            second: Color,
            secondWeight: Double,
        ): Color {
            val firstWeight = 1.0 - secondWeight
            return Color(
                (first.red * firstWeight + second.red * secondWeight).toInt().coerceIn(0, 255),
                (first.green * firstWeight + second.green * secondWeight).toInt().coerceIn(0, 255),
                (first.blue * firstWeight + second.blue * secondWeight).toInt().coerceIn(0, 255),
            )
        }

        private fun withAlpha(
            color: Color,
            alpha: Int,
        ): Color = Color(color.red, color.green, color.blue, alpha)
    }
}
