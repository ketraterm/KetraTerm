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
package com.gagik.terminal.standalone.ui

import com.gagik.terminal.workspace.TerminalProfileKind
import java.awt.*
import java.awt.event.*
import java.awt.geom.Path2D
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * A tab entry displayed in [LatticeTabBar].
 */
internal data class TabEntry(
    val id: String,
    var title: String,
    val profileKind: TerminalProfileKind,
)

/**
 * Custom-painted horizontal tab bar matching modern badge/pill designs.
 *
 * Features:
 * - Dynamic tab shrinking (water-filling layout).
 * - Horizontal scrolling with mouse wheel and buttons when tabs exceed available width.
 * - Single trailing dropdown for unified profile and settings menus.
 */
internal class LatticeTabBar(
    private val onTabSelected: (id: String) -> Unit,
    private val onTabClose: (id: String) -> Unit,
    private val onNewTab: () -> Unit,
    private val onMenuClick: (x: Int, y: Int) -> Unit,
) : JPanel() {
    private val entries = mutableListOf<TabEntry>()
    private var selectedId: String? = null
    private val tabShape = Path2D.Float()
    private val profileIconPainter = LatticeProfileIconPainter()

    // Layout state
    private var tabWidths: List<Int> = emptyList()
    private var scrollOffset = 0
    private var maxScrollOffset = 0
    private var layout = LatticeTabLayoutCalculator.compute(0, emptyList(), 0)

    // Hover state
    private var closeHoverIndex = -1
    private var tabHoverIndex = -1
    private var newTabHovered = false
    private var menuHovered = false
    private var scrollLeftHovered = false
    private var scrollRightHovered = false

    // Pressed state
    private var activePressedResult: HitResult = HitResult.None

    init {
        isOpaque = false
        cursor = Cursor.getDefaultCursor()
        toolTipText = "" // register with ToolTipManager for dynamic getToolTipText(MouseEvent)
        installMouseListeners()
        addComponentListener(
            object : ComponentAdapter() {
                override fun componentResized(e: ComponentEvent?) {
                    repaint() // trigger layout recalculation
                }
            },
        )
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    fun addTab(entry: TabEntry) {
        entries += entry
        selectedId = entry.id
        SwingUtilities.invokeLater {
            val index = entries.indexOfFirst { it.id == entry.id }
            if (index != -1) {
                scrollToVisible(index)
            }
            revalidate()
            repaint()
        }
    }

    fun removeTab(id: String) {
        entries.removeIf { it.id == id }
        if (selectedId == id) {
            selectedId = entries.lastOrNull()?.id
        }
        revalidate()
        repaint()
    }

    fun updateTitle(
        id: String,
        title: String,
    ) {
        entries.find { it.id == id }?.title = title
        repaint()
    }

    fun selectedId(): String? = selectedId

    fun selectedTitle(): String? = entries.find { it.id == selectedId }?.title

    fun getSelectedTabXRange(): Pair<Int, Int>? {
        val id = selectedId ?: return null
        val index = entries.indexOfFirst { it.id == id }
        if (index == -1 || index >= tabWidths.size) return null

        val (visibleStart, visibleEnd) = layout.visibleTabRange(index) ?: return null
        return Pair(x + visibleStart, x + visibleEnd)
    }

    private fun scrollToVisible(index: Int) {
        if (index !in entries.indices || maxScrollOffset <= 0) return
        val fm = getFontMetrics(font.deriveFont(13f))
        updateLayout(fm)

        val tabX = layout.tabX(index)
        val tabW = tabWidths[index]

        val relativeLeft = tabX - scrollOffset
        if (relativeLeft < TAB_START_X) {
            scrollOffset = tabX - TAB_START_X
        } else if (relativeLeft + tabW > TAB_START_X + layout.availableTabWidth) {
            scrollOffset = tabX + tabW - (TAB_START_X + layout.availableTabWidth)
        }

        scrollOffset = scrollOffset.coerceIn(0, maxScrollOffset)
        repaint()
    }

    // -------------------------------------------------------------------------
    // Layout
    // -------------------------------------------------------------------------

    override fun getPreferredSize(): Dimension {
        val fm = getFontMetrics(font.deriveFont(13f))
        val preferredWidths = entries.map { preferredTabWidth(it, fm) }
        val totalTabWidth = LatticeTabLayout.totalTabContentWidth(preferredWidths)
        val staticWidth = TAB_START_X + NEW_TAB_BUTTON_WIDTH + MENU_BUTTON_WIDTH + TRAILING_SPACE
        val prefW = totalTabWidth + staticWidth

        val window = SwingUtilities.getWindowAncestor(this)
        if (window != null) {
            val maxW = window.width - 150
            if (prefW > maxW) {
                return Dimension(maxW, TAB_BAR_HEIGHT)
            }
        }
        return Dimension(prefW, TAB_BAR_HEIGHT)
    }

    override fun getMinimumSize(): Dimension =
        Dimension(TAB_START_X + SCROLL_BUTTON_WIDTH * 2 + NEW_TAB_BUTTON_WIDTH + MENU_BUTTON_WIDTH + TRAILING_SPACE, TAB_BAR_HEIGHT)

    override fun getMaximumSize(): Dimension = preferredSize

    private fun updateLayout(fm: FontMetrics) {
        val prefWidths = entries.map { preferredTabWidth(it, fm) }
        layout = LatticeTabLayoutCalculator.compute(width, prefWidths, scrollOffset)
        tabWidths = layout.tabWidths
        scrollOffset = layout.scrollOffset
        maxScrollOffset = layout.maxScrollOffset
    }

    private fun preferredTabWidth(
        entry: TabEntry,
        fm: FontMetrics,
    ): Int = LatticeTabLayoutCalculator.preferredTabWidth(fm.stringWidth(entry.title))

    // -------------------------------------------------------------------------
    // Painting
    // -------------------------------------------------------------------------

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)

            val fm = g2.getFontMetrics(font.deriveFont(13f))
            updateLayout(fm)

            paintTabs(g2, fm)
            paintActionButtons(g2)
        } finally {
            g2.dispose()
        }
    }

    private fun paintTabs(
        g2: Graphics2D,
        fm: FontMetrics,
    ) {
        val clipWidth =
            if (layout.scrollButtonsVisible) {
                layout.availableTabWidth
            } else {
                width
            }

        val oldClip = g2.clip
        g2.clipRect(TAB_START_X, 0, clipWidth, height)
        g2.translate(-scrollOffset, 0)

        var x = TAB_START_X
        entries.forEachIndexed { index, entry ->
            val w = tabWidths[index]
            val selected = entry.id == selectedId
            paintTab(g2, entry, index, x, w, selected, fm)
            x += w + TAB_GAP
        }

        g2.translate(scrollOffset, 0)
        g2.clip = oldClip
    }

    private fun paintTab(
        g2: Graphics2D,
        entry: TabEntry,
        index: Int,
        x: Int,
        w: Int,
        selected: Boolean,
        fm: FontMetrics,
    ) {
        val y = TAB_TOP_PADDING
        val h = height - TAB_TOP_PADDING

        val isTabPressed = activePressedResult == HitResult.Tab(index)
        val bg =
            when {
                selected -> LatticeChrome.tabSelectedBackground
                index == tabHoverIndex -> {
                    if (isTabPressed) {
                        LatticeChrome.controlPressed
                    } else {
                        LatticeChrome.tabHoverBackground
                    }
                }
                else -> TRANSPARENT_COLOR
            }
        if (bg.alpha > 0) {
            g2.color = bg
            resetTabShape(x, y, w, h)
            g2.fill(tabShape)
        }

        if (selected || index == tabHoverIndex) {
            g2.color = LatticeChrome.border
            g2.stroke = HAIRLINE_STROKE
            resetTabShape(x, y, w, h)
            g2.draw(tabShape)
        }

        // Draw vertical divider between inactive tabs
        val nextSelected = entries.getOrNull(index + 1)?.id == selectedId
        val nextHovered = index + 1 == tabHoverIndex
        if (!selected && !nextSelected && !nextHovered && index < entries.size - 1 && index != tabHoverIndex) {
            g2.color = LatticeChrome.border
            g2.stroke = HAIRLINE_STROKE
            val divX = x + w + TAB_GAP / 2
            g2.drawLine(divX, y + 6, divX, y + h - 6)
        }

        g2.font = font.deriveFont(13f)
        val iconY = y + (h + fm.ascent - fm.descent) / 2

        val iconX = x + TAB_LABEL_PADDING_LEFT
        val iconH = 12
        val iconDrawY = y + (h - iconH) / 2
        profileIconPainter.paint(
            g2 = g2,
            profileKind = entry.profileKind,
            x = iconX,
            y = iconDrawY,
            selected = selected,
            highlighted = index == tabHoverIndex,
        )

        val titleFg =
            when {
                selected -> LatticeChrome.textPrimary
                index == tabHoverIndex -> LatticeChrome.textHover
                else -> LatticeChrome.textSecondary
            }
        g2.color = titleFg
        val labelMaxWidth = w - TAB_LABEL_PADDING_LEFT - ICON_TEXT_GAP - CLOSE_BUTTON_SIZE - CLOSE_BUTTON_MARGIN_RIGHT - 4
        if (labelMaxWidth > 10) {
            val labelX = x + TAB_LABEL_PADDING_LEFT + ICON_TEXT_GAP
            val clipped = clipText(entry.title, labelMaxWidth, fm)
            g2.drawString(clipped, labelX, iconY)
        }

        val closeBtnX = x + w - CLOSE_BUTTON_SIZE - CLOSE_BUTTON_MARGIN_RIGHT
        val closeBtnY = y + (h - CLOSE_BUTTON_SIZE) / 2
        paintCloseButton(g2, index, closeBtnX, closeBtnY, selected)
    }

    private fun paintCloseButton(
        g2: Graphics2D,
        index: Int,
        x: Int,
        y: Int,
        tabSelected: Boolean,
    ) {
        val hovered = index == closeHoverIndex
        val visible = tabSelected || index == tabHoverIndex || hovered

        if (visible) {
            if (hovered) {
                val isPressed = activePressedResult == HitResult.TabClose(index)
                g2.color = if (isPressed) LatticeChrome.controlPressed else LatticeChrome.controlHover
                g2.fillRoundRect(x, y, CLOSE_BUTTON_SIZE, CLOSE_BUTTON_SIZE, 3, 3)
            }
            g2.color = if (hovered) LatticeChrome.textPrimary else LatticeChrome.textSecondary
            g2.stroke = ICON_STROKE
            val pad = CLOSE_BUTTON_SIZE / 4
            g2.drawLine(x + pad, y + pad, x + CLOSE_BUTTON_SIZE - pad, y + CLOSE_BUTTON_SIZE - pad)
            g2.drawLine(x + CLOSE_BUTTON_SIZE - pad, y + pad, x + pad, y + CLOSE_BUTTON_SIZE - pad)
        }
    }

    private fun resetTabShape(
        x: Int,
        y: Int,
        w: Int,
        h: Int,
    ) {
        tabShape.reset()
        tabShape.moveTo(x.toFloat(), (y + h).toFloat())
        tabShape.lineTo(x.toFloat(), y + CORNER_RADIUS)
        tabShape.quadTo(x.toFloat(), y.toFloat(), x + CORNER_RADIUS, y.toFloat())
        tabShape.lineTo(x + w - CORNER_RADIUS, y.toFloat())
        tabShape.quadTo((x + w).toFloat(), y.toFloat(), (x + w).toFloat(), y + CORNER_RADIUS)
        tabShape.lineTo((x + w).toFloat(), (y + h).toFloat())
        tabShape.closePath()
    }

    private fun paintActionButtons(g2: Graphics2D) {
        var currentX = layout.actionButtonStartX
        val y = TAB_TOP_PADDING
        val h = height - TAB_TOP_PADDING

        if (layout.scrollButtonsVisible) {
            val isPressedL = activePressedResult == HitResult.ScrollLeft
            paintActionButton(
                g2 = g2,
                x = currentX,
                width = SCROLL_BUTTON_WIDTH,
                y = y,
                height = h,
                hovered = scrollLeftHovered,
                pressed = isPressedL,
                enabled = scrollOffset > 0,
            ) { cx, cy ->
                g2.drawLine(cx + 2, cy - 4, cx - 2, cy)
                g2.drawLine(cx - 2, cy, cx + 2, cy + 4)
            }
            currentX += SCROLL_BUTTON_WIDTH

            val isPressedR = activePressedResult == HitResult.ScrollRight
            paintActionButton(
                g2 = g2,
                x = currentX,
                width = SCROLL_BUTTON_WIDTH,
                y = y,
                height = h,
                hovered = scrollRightHovered,
                pressed = isPressedR,
                enabled = scrollOffset < maxScrollOffset,
            ) { cx, cy ->
                g2.drawLine(cx - 2, cy - 4, cx + 2, cy)
                g2.drawLine(cx + 2, cy, cx - 2, cy + 4)
            }
            currentX += SCROLL_BUTTON_WIDTH
        }

        val isPressedNew = activePressedResult == HitResult.NewTab
        paintActionButton(
            g2 = g2,
            x = currentX,
            width = NEW_TAB_BUTTON_WIDTH,
            y = y,
            height = h,
            hovered = newTabHovered,
            pressed = isPressedNew,
        ) { cx, cy ->
            val plusRadius = 4
            g2.drawLine(cx - plusRadius, cy, cx + plusRadius, cy)
            g2.drawLine(cx, cy - plusRadius, cx, cy + plusRadius)
        }
        currentX += NEW_TAB_BUTTON_WIDTH

        g2.color = LatticeChrome.divider
        g2.stroke = DIVIDER_STROKE
        val divX = currentX - 2
        g2.drawLine(divX, y + 6, divX, y + h - 6)

        val isPressedMenu = activePressedResult is HitResult.Menu
        paintActionButton(
            g2 = g2,
            x = currentX,
            width = MENU_BUTTON_WIDTH,
            y = y,
            height = h,
            hovered = menuHovered,
            pressed = isPressedMenu,
        ) { cx, cy ->
            val chevronRadius = 3
            g2.drawLine(cx - chevronRadius, cy - chevronRadius / 2, cx, cy + chevronRadius / 2)
            g2.drawLine(cx, cy + chevronRadius / 2, cx + chevronRadius, cy - chevronRadius / 2)
        }
    }

    private fun paintActionButton(
        g2: Graphics2D,
        x: Int,
        width: Int,
        y: Int,
        height: Int,
        hovered: Boolean,
        pressed: Boolean,
        enabled: Boolean = true,
        paintIcon: (centerX: Int, centerY: Int) -> Unit,
    ) {
        val centerX = x + width / 2
        val centerY = y + height / 2
        if (hovered || pressed) {
            g2.color = if (pressed) LatticeChrome.controlPressed else LatticeChrome.tabHoverBackground
            g2.fillRoundRect(
                centerX - ACTION_BUTTON_HOVER_SIZE / 2,
                centerY - ACTION_BUTTON_HOVER_SIZE / 2,
                ACTION_BUTTON_HOVER_SIZE,
                ACTION_BUTTON_HOVER_SIZE,
                ACTION_BUTTON_CORNER_RADIUS,
                ACTION_BUTTON_CORNER_RADIUS,
            )
        }

        g2.color =
            when {
                !enabled -> LatticeChrome.controlTextDisabled
                hovered || pressed -> LatticeChrome.textPrimary
                else -> LatticeChrome.controlText
            }
        g2.stroke = ACTION_ICON_STROKE
        paintIcon(centerX, centerY)
    }

    // -------------------------------------------------------------------------
    // Hit Testing
    // -------------------------------------------------------------------------

    private sealed class HitResult {
        object None : HitResult()

        data class Tab(
            val index: Int,
        ) : HitResult()

        data class TabClose(
            val index: Int,
        ) : HitResult()

        object ScrollLeft : HitResult()

        object ScrollRight : HitResult()

        object NewTab : HitResult()

        data class Menu(
            val btnX: Int,
        ) : HitResult()
    }

    private fun hitTest(
        px: Int,
        py: Int,
        fm: FontMetrics,
    ): HitResult {
        updateLayout(fm)

        val availableForTabs = layout.availableTabWidth
        var currentX = layout.actionButtonStartX

        if (layout.scrollButtonsVisible) {
            if (px in currentX until currentX + SCROLL_BUTTON_WIDTH) return HitResult.ScrollLeft
            currentX += SCROLL_BUTTON_WIDTH
            if (px in currentX until currentX + SCROLL_BUTTON_WIDTH) return HitResult.ScrollRight
            currentX += SCROLL_BUTTON_WIDTH
        }

        if (px in currentX until currentX + NEW_TAB_BUTTON_WIDTH) return HitResult.NewTab
        currentX += NEW_TAB_BUTTON_WIDTH

        if (px in currentX until currentX + MENU_BUTTON_WIDTH) return HitResult.Menu(currentX)

        if (px in TAB_START_X until (TAB_START_X + availableForTabs)) {
            val scrolledPx = px + scrollOffset
            var tx = TAB_START_X
            for (i in entries.indices) {
                val w = tabWidths[i]
                if (scrolledPx in tx until tx + w) {
                    val closeBtnX = tx + w - CLOSE_BUTTON_SIZE - CLOSE_BUTTON_MARGIN_RIGHT
                    val y = TAB_TOP_PADDING
                    val h = height - TAB_TOP_PADDING - TAB_BOTTOM_PADDING
                    val closeBtnY = y + (h - CLOSE_BUTTON_SIZE) / 2
                    if (scrolledPx in closeBtnX until closeBtnX + CLOSE_BUTTON_SIZE &&
                        py in closeBtnY until closeBtnY + CLOSE_BUTTON_SIZE
                    ) {
                        return HitResult.TabClose(i)
                    }
                    return HitResult.Tab(i)
                }
                tx += w + TAB_GAP
            }
        }

        return HitResult.None
    }

    // -------------------------------------------------------------------------
    // Mouse Interaction
    // -------------------------------------------------------------------------

    private fun installMouseListeners() {
        val adapter =
            object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    val fm = getFontMetrics(font.deriveFont(13f))
                    val hit = hitTest(e.x, e.y, fm)

                    if (SwingUtilities.isLeftMouseButton(e) || SwingUtilities.isMiddleMouseButton(e)) {
                        activePressedResult = hit
                        repaint()
                    }
                }

                override fun mouseReleased(e: MouseEvent) {
                    val fm = getFontMetrics(font.deriveFont(13f))
                    val hit = hitTest(e.x, e.y, fm)
                    val pressed = activePressedResult
                    activePressedResult = HitResult.None
                    repaint()

                    if (SwingUtilities.isLeftMouseButton(e)) {
                        if (pressed == hit) {
                            when (hit) {
                                is HitResult.TabClose -> onTabClose(entries[hit.index].id)
                                is HitResult.Tab -> {
                                    val id = entries[hit.index].id
                                    if (id != selectedId) {
                                        selectedId = id
                                        repaint()
                                        onTabSelected(id)
                                    }
                                }
                                HitResult.NewTab -> onNewTab()
                                is HitResult.Menu -> onMenuClick(hit.btnX, height)
                                HitResult.ScrollLeft -> {
                                    if (scrollOffset > 0) {
                                        scrollOffset = maxOf(0, scrollOffset - 120)
                                        repaint()
                                    }
                                }
                                HitResult.ScrollRight -> {
                                    if (scrollOffset < maxScrollOffset) {
                                        scrollOffset = minOf(maxScrollOffset, scrollOffset + 120)
                                        repaint()
                                    }
                                }
                                HitResult.None -> {
                                    if (e.clickCount == 2) {
                                        onNewTab()
                                    }
                                }
                            }
                        }
                    } else if (SwingUtilities.isMiddleMouseButton(e)) {
                        val index =
                            when (hit) {
                                is HitResult.Tab -> hit.index
                                is HitResult.TabClose -> hit.index
                                else -> -1
                            }
                        val pressedIndex =
                            when (pressed) {
                                is HitResult.Tab -> pressed.index
                                is HitResult.TabClose -> pressed.index
                                else -> -2
                            }
                        if (index >= 0 && index == pressedIndex) {
                            onTabClose(entries[index].id)
                        }
                    }
                }

                override fun mouseExited(e: MouseEvent) {
                    closeHoverIndex = -1
                    tabHoverIndex = -1
                    newTabHovered = false
                    menuHovered = false
                    scrollLeftHovered = false
                    scrollRightHovered = false
                    repaint()
                }

                override fun mouseMoved(e: MouseEvent) {
                    val fm = getFontMetrics(font.deriveFont(13f))
                    val hit = hitTest(e.x, e.y, fm)

                    val prevClose = closeHoverIndex
                    val prevTab = tabHoverIndex
                    val prevNew = newTabHovered
                    val prevMenu = menuHovered
                    val prevScrollL = scrollLeftHovered
                    val prevScrollR = scrollRightHovered

                    closeHoverIndex = if (hit is HitResult.TabClose) hit.index else -1
                    tabHoverIndex = if (hit is HitResult.Tab) hit.index else -1
                    newTabHovered = hit is HitResult.NewTab
                    menuHovered = hit is HitResult.Menu
                    scrollLeftHovered = hit is HitResult.ScrollLeft
                    scrollRightHovered = hit is HitResult.ScrollRight

                    if (prevClose != closeHoverIndex ||
                        prevTab != tabHoverIndex ||
                        prevNew != newTabHovered ||
                        prevMenu != menuHovered ||
                        prevScrollL != scrollLeftHovered ||
                        prevScrollR != scrollRightHovered
                    ) {
                        repaint()
                    }
                }

                override fun mouseDragged(e: MouseEvent) {
                    mouseMoved(e)
                }

                override fun mouseWheelMoved(e: MouseWheelEvent) {
                    if (maxScrollOffset > 0) {
                        scrollOffset = (scrollOffset + e.wheelRotation * 40).coerceIn(0, maxScrollOffset)
                        repaint()
                    }
                }
            }
        addMouseListener(adapter)
        addMouseMotionListener(adapter)
        addMouseWheelListener(adapter)
    }

    override fun getToolTipText(event: MouseEvent): String? {
        val fm = getFontMetrics(font.deriveFont(13f))
        val hit = hitTest(event.x, event.y, fm)
        if (hit is HitResult.Tab) {
            val entry = entries.getOrNull(hit.index) ?: return null
            val w = tabWidths.getOrNull(hit.index) ?: return null
            val labelMaxWidth =
                w - TAB_LABEL_PADDING_LEFT - ICON_TEXT_GAP - CLOSE_BUTTON_SIZE - CLOSE_BUTTON_MARGIN_RIGHT - 4
            if (fm.stringWidth(entry.title) > labelMaxWidth) {
                return entry.title
            }
        }
        return null
    }

    private fun clipText(
        text: String,
        maxWidth: Int,
        fm: FontMetrics,
    ): String {
        if (fm.stringWidth(text) <= maxWidth) return text
        val ellipsis = "\u2026"
        val ellipsisWidth = fm.stringWidth(ellipsis)
        var clipped = text
        while (clipped.isNotEmpty() && fm.stringWidth(clipped) + ellipsisWidth > maxWidth) {
            clipped = clipped.dropLast(1)
        }
        return clipped + ellipsis
    }

    private companion object {
        private const val TAB_BAR_HEIGHT = LatticeTabMetrics.TAB_BAR_HEIGHT
        private const val TAB_TOP_PADDING = LatticeTabMetrics.TAB_TOP_PADDING
        private const val TAB_BOTTOM_PADDING = LatticeTabMetrics.TAB_BOTTOM_PADDING
        private const val TAB_START_X = LatticeTabMetrics.TAB_START_X
        private const val TAB_GAP = LatticeTabMetrics.TAB_GAP
        private const val TAB_LABEL_PADDING_LEFT = LatticeTabMetrics.TAB_LABEL_PADDING_LEFT
        private const val ICON_TEXT_GAP = LatticeTabMetrics.ICON_TEXT_GAP
        private const val CLOSE_BUTTON_SIZE = LatticeTabMetrics.CLOSE_BUTTON_SIZE
        private const val CLOSE_BUTTON_MARGIN_RIGHT = LatticeTabMetrics.CLOSE_BUTTON_MARGIN_RIGHT
        private const val SCROLL_BUTTON_WIDTH = LatticeTabMetrics.SCROLL_BUTTON_WIDTH
        private const val NEW_TAB_BUTTON_WIDTH = LatticeTabMetrics.NEW_TAB_BUTTON_WIDTH
        private const val MENU_BUTTON_WIDTH = LatticeTabMetrics.MENU_BUTTON_WIDTH
        private const val TRAILING_SPACE = LatticeTabMetrics.TRAILING_SPACE
        private const val CORNER_RADIUS = LatticeTabMetrics.CORNER_RADIUS
        private const val ACTION_BUTTON_HOVER_SIZE = 24
        private const val ACTION_BUTTON_CORNER_RADIUS = 3
        private val TRANSPARENT_COLOR = Color(0, 0, 0, 0)
        private val HAIRLINE_STROKE = LatticeTabMetrics.HAIRLINE_STROKE
        private val DIVIDER_STROKE = LatticeTabMetrics.DIVIDER_STROKE
        private val ICON_STROKE = LatticeTabMetrics.ICON_STROKE
        private val ACTION_ICON_STROKE = LatticeTabMetrics.ACTION_ICON_STROKE
    }
}
