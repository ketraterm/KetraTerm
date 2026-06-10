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

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * A tab entry displayed in [LatticeTabBar].
 */
internal data class TabEntry(
    val id: String,
    var title: String,
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

    // Layout state
    private var tabWidths: List<Int> = emptyList()
    private var scrollOffset = 0
    private var maxScrollOffset = 0

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

    fun selectTab(id: String) {
        selectedId = id
        val index = entries.indexOfFirst { it.id == id }
        if (index != -1) {
            scrollToVisible(index)
        }
        repaint()
    }

    fun selectedId(): String? = selectedId

    fun selectedTitle(): String? = entries.find { it.id == selectedId }?.title

    private fun scrollToVisible(index: Int) {
        if (index !in entries.indices || maxScrollOffset <= 0) return
        val fm = getFontMetrics(font.deriveFont(13f))
        updateLayout(fm)

        var tabX = TAB_START_X
        for (i in 0 until index) {
            tabX += tabWidths[i] + TAB_GAP
        }
        val tabW = tabWidths[index]

        val visibleWidth = width - TAB_START_X - SCROLL_BUTTON_WIDTH * 2 - NEW_TAB_BUTTON_WIDTH - MENU_BUTTON_WIDTH - TRAILING_SPACE

        val relativeLeft = tabX - scrollOffset
        if (relativeLeft < TAB_START_X) {
            scrollOffset = tabX - TAB_START_X
        } else if (relativeLeft + tabW > TAB_START_X + visibleWidth) {
            scrollOffset = tabX + tabW - (TAB_START_X + visibleWidth)
        }

        scrollOffset = scrollOffset.coerceIn(0, maxScrollOffset)
        repaint()
    }

    // -------------------------------------------------------------------------
    // Layout
    // -------------------------------------------------------------------------

    override fun getPreferredSize(): Dimension {
        val fm = getFontMetrics(font.deriveFont(13f))
        val totalTabWidth = entries.sumOf { preferredTabWidth(it, fm) + TAB_GAP }
        val staticWidth = TAB_START_X + NEW_TAB_BUTTON_WIDTH + MENU_BUTTON_WIDTH + TRAILING_SPACE
        return Dimension(totalTabWidth + staticWidth, TAB_BAR_HEIGHT)
    }

    override fun getMinimumSize(): Dimension =
        Dimension(TAB_START_X + SCROLL_BUTTON_WIDTH * 2 + NEW_TAB_BUTTON_WIDTH + MENU_BUTTON_WIDTH + TRAILING_SPACE, TAB_BAR_HEIGHT)

    private fun updateLayout(fm: FontMetrics) {
        val count = entries.size
        if (count == 0) {
            tabWidths = emptyList()
            maxScrollOffset = 0
            scrollOffset = 0
            return
        }

        val prefWidths = entries.map { preferredTabWidth(it, fm) }
        val totalPref = prefWidths.sum() + (count - 1) * TAB_GAP

        val spaceWithoutScroll = width - TAB_START_X - NEW_TAB_BUTTON_WIDTH - MENU_BUTTON_WIDTH - TRAILING_SPACE

        val widths = IntArray(count)
        var needsScroll = false
        var availableForTabs = spaceWithoutScroll

        if (totalPref <= spaceWithoutScroll) {
            prefWidths.forEachIndexed { i, w -> widths[i] = w }
        } else {
            val totalMin = count * MIN_TAB_WIDTH + (count - 1) * TAB_GAP
            if (totalMin > spaceWithoutScroll) {
                needsScroll = true
                availableForTabs = spaceWithoutScroll - SCROLL_BUTTON_WIDTH * 2
                for (i in 0 until count) widths[i] = MIN_TAB_WIDTH
            } else {
                var remainingSpace = spaceWithoutScroll - (count - 1) * TAB_GAP
                var remainingCount = count
                val sortedIndices = prefWidths.indices.sortedBy { prefWidths[it] }

                for (i in sortedIndices) {
                    val pref = prefWidths[i]
                    val fairShare = remainingSpace / remainingCount
                    if (pref <= fairShare) {
                        widths[i] = pref
                        remainingSpace -= pref
                    } else {
                        widths[i] = fairShare
                        remainingSpace -= fairShare
                    }
                    remainingCount--
                }
            }
        }

        tabWidths = widths.toList()

        if (needsScroll) {
            val totalWidth = tabWidths.sum() + (count - 1) * TAB_GAP
            maxScrollOffset = maxOf(0, totalWidth - availableForTabs)
            scrollOffset = scrollOffset.coerceIn(0, maxScrollOffset)
        } else {
            maxScrollOffset = 0
            scrollOffset = 0
        }
    }

    private fun preferredTabWidth(
        entry: TabEntry,
        fm: FontMetrics,
    ): Int {
        val textWidth = fm.stringWidth(entry.title).coerceIn(MIN_LABEL_TEXT_WIDTH, MAX_LABEL_TEXT_WIDTH)
        return TAB_LABEL_PADDING_LEFT + textWidth + CLOSE_BUTTON_SIZE + CLOSE_BUTTON_MARGIN_RIGHT + TAB_PADDING_RIGHT
    }

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
            if (maxScrollOffset > 0) {
                width - TAB_START_X - SCROLL_BUTTON_WIDTH * 2 - NEW_TAB_BUTTON_WIDTH - MENU_BUTTON_WIDTH - TRAILING_SPACE
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
        val h = height - TAB_TOP_PADDING - TAB_BOTTOM_PADDING

        val isTabPressed = activePressedResult == HitResult.Tab(index)
        val bg =
            when {
                selected -> {
                    if (isTabPressed) {
                        Color(0x2E, 0x3B, 0x52)
                    } else {
                        LatticeChrome.TAB_SELECTED_BG
                    }
                }
                index == tabHoverIndex -> {
                    if (isTabPressed) {
                        LatticeChrome.CONTROL_PRESSED
                    } else {
                        LatticeChrome.TAB_HOVER_BG
                    }
                }
                else -> Color(0, 0, 0, 0)
            }
        if (bg.alpha > 0) {
            g2.color = bg
            g2.fill(RoundRectangle2D.Float(x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat(), CORNER_RADIUS, CORNER_RADIUS))
        }

        g2.font = font.deriveFont(13f)
        val titleFg = if (selected) LatticeChrome.TEXT_PRIMARY else LatticeChrome.TEXT_SECONDARY
        g2.color = titleFg

        val labelMaxWidth = w - TAB_LABEL_PADDING_LEFT - CLOSE_BUTTON_SIZE - CLOSE_BUTTON_MARGIN_RIGHT - 4
        if (labelMaxWidth > 10) {
            val labelX = x + TAB_LABEL_PADDING_LEFT
            val clipped = clipText(entry.title, labelMaxWidth, fm)
            val textY = y + (h + fm.ascent - fm.descent) / 2
            g2.drawString(clipped, labelX, textY)
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
                g2.color = if (isPressed) LatticeChrome.CONTROL_PRESSED else LatticeChrome.CONTROL_HOVER
                g2.fillRoundRect(x, y, CLOSE_BUTTON_SIZE, CLOSE_BUTTON_SIZE, 4, 4)
            }
            g2.color = if (hovered) LatticeChrome.TEXT_PRIMARY else LatticeChrome.TEXT_SECONDARY
            g2.stroke = BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            val pad = CLOSE_BUTTON_SIZE / 4
            g2.drawLine(x + pad, y + pad, x + CLOSE_BUTTON_SIZE - pad, y + CLOSE_BUTTON_SIZE - pad)
            g2.drawLine(x + CLOSE_BUTTON_SIZE - pad, y + pad, x + pad, y + CLOSE_BUTTON_SIZE - pad)
        }
    }

    private fun paintActionButtons(g2: Graphics2D) {
        val availableForTabs =
            if (maxScrollOffset > 0) {
                width - TAB_START_X - SCROLL_BUTTON_WIDTH * 2 - NEW_TAB_BUTTON_WIDTH - MENU_BUTTON_WIDTH - TRAILING_SPACE
            } else {
                width - TAB_START_X - NEW_TAB_BUTTON_WIDTH - MENU_BUTTON_WIDTH - TRAILING_SPACE
            }

        var currentX = TAB_START_X + if (maxScrollOffset > 0) availableForTabs else (tabWidths.sum() + maxOf(0, entries.size - 1) * TAB_GAP)
        val y = TAB_TOP_PADDING
        val h = height - TAB_TOP_PADDING - TAB_BOTTOM_PADDING

        if (maxScrollOffset > 0) {
            // < button
            val isPressedL = activePressedResult == HitResult.ScrollLeft
            if (scrollLeftHovered || isPressedL) {
                g2.color = if (isPressedL) LatticeChrome.CONTROL_PRESSED else LatticeChrome.TAB_HOVER_BG
                g2.fill(RoundRectangle2D.Float(currentX.toFloat(), y.toFloat(), SCROLL_BUTTON_WIDTH.toFloat(), h.toFloat(), 6f, 6f))
            }
            g2.color =
                if (scrollOffset > 0) {
                    if (scrollLeftHovered || isPressedL) LatticeChrome.TEXT_PRIMARY else LatticeChrome.TEXT_SECONDARY
                } else {
                    LatticeChrome.BORDER
                }
            g2.stroke = BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            val cx = currentX + SCROLL_BUTTON_WIDTH / 2
            val cy = y + h / 2
            g2.drawLine(cx + 2, cy - 4, cx - 2, cy)
            g2.drawLine(cx - 2, cy, cx + 2, cy + 4)
            currentX += SCROLL_BUTTON_WIDTH

            // > button
            val isPressedR = activePressedResult == HitResult.ScrollRight
            if (scrollRightHovered || isPressedR) {
                g2.color = if (isPressedR) LatticeChrome.CONTROL_PRESSED else LatticeChrome.TAB_HOVER_BG
                g2.fill(RoundRectangle2D.Float(currentX.toFloat(), y.toFloat(), SCROLL_BUTTON_WIDTH.toFloat(), h.toFloat(), 6f, 6f))
            }
            g2.color =
                if (scrollOffset < maxScrollOffset) {
                    if (scrollRightHovered || isPressedR) LatticeChrome.TEXT_PRIMARY else LatticeChrome.TEXT_SECONDARY
                } else {
                    LatticeChrome.BORDER
                }
            val cx2 = currentX + SCROLL_BUTTON_WIDTH / 2
            g2.drawLine(cx2 - 2, cy - 4, cx2 + 2, cy)
            g2.drawLine(cx2 + 2, cy, cx2 - 2, cy + 4)
            currentX += SCROLL_BUTTON_WIDTH
        }

        // + button
        val isPressedNew = activePressedResult == HitResult.NewTab
        if (newTabHovered || isPressedNew) {
            g2.color = if (isPressedNew) LatticeChrome.CONTROL_PRESSED else LatticeChrome.TAB_HOVER_BG
            g2.fill(RoundRectangle2D.Float(currentX.toFloat(), y.toFloat(), NEW_TAB_BUTTON_WIDTH.toFloat(), h.toFloat(), 6f, 6f))
        }
        g2.color = if (newTabHovered || isPressedNew) LatticeChrome.TEXT_PRIMARY else LatticeChrome.TEXT_SECONDARY
        g2.stroke = BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        val plusCx = currentX + NEW_TAB_BUTTON_WIDTH / 2
        val plusCy = y + h / 2
        val plusR = 4
        g2.drawLine(plusCx - plusR, plusCy, plusCx + plusR, plusCy)
        g2.drawLine(plusCx, plusCy - plusR, plusCx, plusCy + plusR)

        currentX += NEW_TAB_BUTTON_WIDTH

        // v button
        val isPressedMenu = activePressedResult is HitResult.Menu
        if (menuHovered || isPressedMenu) {
            g2.color = if (isPressedMenu) LatticeChrome.CONTROL_PRESSED else LatticeChrome.TAB_HOVER_BG
            g2.fill(RoundRectangle2D.Float(currentX.toFloat(), y.toFloat(), MENU_BUTTON_WIDTH.toFloat(), h.toFloat(), 6f, 6f))
        }
        g2.color = if (menuHovered || isPressedMenu) LatticeChrome.TEXT_PRIMARY else LatticeChrome.TEXT_SECONDARY
        val vCx = currentX + MENU_BUTTON_WIDTH / 2
        val vCy = y + h / 2
        val vR = 3
        g2.drawLine(vCx - vR, vCy - vR / 2, vCx, vCy + vR / 2)
        g2.drawLine(vCx, vCy + vR / 2, vCx + vR, vCy - vR / 2)
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

        val availableForTabs =
            if (maxScrollOffset > 0) {
                width - TAB_START_X - SCROLL_BUTTON_WIDTH * 2 - NEW_TAB_BUTTON_WIDTH - MENU_BUTTON_WIDTH - TRAILING_SPACE
            } else {
                width - TAB_START_X - NEW_TAB_BUTTON_WIDTH - MENU_BUTTON_WIDTH - TRAILING_SPACE
            }
        var currentX = TAB_START_X + if (maxScrollOffset > 0) availableForTabs else (tabWidths.sum() + maxOf(0, entries.size - 1) * TAB_GAP)

        if (maxScrollOffset > 0) {
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
            val labelMaxWidth = w - TAB_LABEL_PADDING_LEFT - CLOSE_BUTTON_SIZE - CLOSE_BUTTON_MARGIN_RIGHT - 4
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
        private const val TAB_BAR_HEIGHT = 40
        private const val TAB_TOP_PADDING = 8
        private const val TAB_BOTTOM_PADDING = 6
        private const val TAB_START_X = 8
        private const val TAB_GAP = 4
        private const val TAB_LABEL_PADDING_LEFT = 12
        private const val TAB_PADDING_RIGHT = 6
        private const val CLOSE_BUTTON_SIZE = 16
        private const val CLOSE_BUTTON_MARGIN_RIGHT = 6
        private const val SCROLL_BUTTON_WIDTH = 24
        private const val NEW_TAB_BUTTON_WIDTH = 28
        private const val MENU_BUTTON_WIDTH = 24
        private const val TRAILING_SPACE = 8
        private const val CORNER_RADIUS = 8f
        private const val MIN_LABEL_TEXT_WIDTH = 50
        private const val MAX_LABEL_TEXT_WIDTH = 160
        private const val MIN_TAB_WIDTH = 90
    }
}
