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
import javax.swing.Timer

/**
 * A tab entry displayed in [LatticeTabBar].
 */
internal data class TabEntry(
    val id: String,
    var title: String,
    val profileKind: TerminalProfileKind,
    var color: Color? = null,
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
    private val onTabColorChanged: (id: String, colorHex: String?) -> Unit,
    private val onTabRenameRequested: (id: String, newName: String?) -> Unit,
) : JPanel() {
    private val entries = mutableListOf<TabEntry>()
    private var selectedId: String? = null
    private val tabFillShape = Path2D.Double()
    private val tabBorderShape = Path2D.Double()
    private val profileIcons = LatticeProfileIcons()

    // Layout state
    private var tabWidths: List<Int> = emptyList()
    private var scrollOffset = 0
    private var maxScrollOffset = 0
    private var layout = LatticeTabLayoutCalculator.compute(0, emptyList(), 0)

    // Editing state
    private var editingIndex = -1
    private var renameTextField: javax.swing.JTextField? = null

    // Hover state
    private var closeHoverIndex = -1
    private var tabHoverIndex = -1
    private var newTabHovered = false
    private var menuHovered = false
    private var scrollLeftHovered = false
    private var scrollRightHovered = false

    // Mouse hover and width lock state
    private var isMouseOver = false
    private var lastMouseX = -1
    private var lastMouseY = -1
    private var hoverTabWidthLock: Int? = null
    private var lastWindowWidth = -1

    // Pressed state
    private var activePressedResult: HitResult = HitResult.None

    // Selection fade state
    private var fadingOutId: String? = null
    private var fadingInId: String? = null
    private var fadeProgress = 1.0f
    private var fadeTimer: Timer? = null

    init {
        setLayout(null)
        isOpaque = false
        alignmentY = BOTTOM_ALIGNMENT
        cursor = Cursor.getDefaultCursor()
        toolTipText = "" // register with ToolTipManager for dynamic getToolTipText(MouseEvent)
        installMouseListeners()
        addComponentListener(
            object : ComponentAdapter() {
                override fun componentResized(e: ComponentEvent?) {
                    val window = SwingUtilities.getWindowAncestor(this@LatticeTabBar)
                    val currentWindowWidth = window?.width ?: -1
                    if (lastWindowWidth != -1 && currentWindowWidth != lastWindowWidth) {
                        hoverTabWidthLock = null
                    }
                    lastWindowWidth = currentWindowWidth
                    repaint() // trigger layout recalculation
                }
            },
        )
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    fun addTab(entry: TabEntry) {
        hoverTabWidthLock = null
        val oldId = selectedId
        entries += entry
        selectedId = entry.id
        if (oldId != null) {
            startSelectionFade(oldId, entry.id)
        }
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
        val activeEditId = if (editingIndex in entries.indices) entries[editingIndex].id else null
        if (isMouseOver && tabWidths.isNotEmpty()) {
            if (hoverTabWidthLock == null) {
                hoverTabWidthLock = tabWidths.first()
            }
        }
        entries.removeIf { it.id == id }
        if (selectedId == id) {
            selectedId = entries.lastOrNull()?.id
        }
        if (fadingOutId == id || fadingInId == id) {
            fadeTimer?.stop()
            fadeTimer = null
            fadingOutId = null
            fadingInId = null
            fadeProgress = 1.0f
        }
        if (activeEditId == id) {
            cancelEditing()
        } else if (activeEditId != null) {
            editingIndex = entries.indexOfFirst { it.id == activeEditId }
        }
        if (entries.isEmpty()) {
            hoverTabWidthLock = null
        }
        val fm = getFontMetrics(font.deriveFont(13f))
        updateHoverState(lastMouseX, lastMouseY, fm)
        revalidate()
        repaint()
    }

    private fun startSelectionFade(
        oldId: String?,
        newId: String?,
    ) {
        fadeTimer?.stop()
        fadingOutId = oldId
        fadingInId = newId
        fadeProgress = 0.0f

        val step = FADE_DELAY_MS.toFloat() / FADE_DURATION_MS.toFloat()

        fadeTimer =
            Timer(FADE_DELAY_MS) {
                fadeProgress = (fadeProgress + step).coerceAtMost(1.0f)
                repaint()
                if (fadeProgress >= 1.0f) {
                    fadeTimer?.stop()
                    fadeTimer = null
                    fadingOutId = null
                    fadingInId = null
                }
            }.apply {
                start()
            }
    }

    private fun blendColors(
        c1: Color,
        c2: Color,
        ratio: Float,
    ): Color {
        val r = (c1.red + (c2.red - c1.red) * ratio).toInt().coerceIn(0, 255)
        val g = (c1.green + (c2.green - c1.green) * ratio).toInt().coerceIn(0, 255)
        val b = (c1.blue + (c2.blue - c1.blue) * ratio).toInt().coerceIn(0, 255)
        val a = (c1.alpha + (c2.alpha - c1.alpha) * ratio).toInt().coerceIn(0, 255)
        return Color(r, g, b, a)
    }

    fun updateTitle(
        id: String,
        title: String,
    ) {
        entries.find { it.id == id }?.title = title
        repaint()
    }

    fun updateColor(
        id: String,
        color: Color?,
    ) {
        entries.find { it.id == id }?.color = color
        repaint()
    }

    fun selectTab(id: String) {
        if (selectedId != id) {
            val oldId = selectedId
            selectedId = id
            startSelectionFade(oldId, id)
            val index = entries.indexOfFirst { it.id == id }
            if (index != -1) {
                scrollToVisible(index)
            }
            revalidate()
            repaint()
        }
    }

    fun selectedId(): String? = selectedId

    fun selectedTitle(): String? = entries.find { it.id == selectedId }?.title

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
        val lock = hoverTabWidthLock
        val preferredWidths =
            if (lock != null) {
                List(entries.size) { lock }
            } else {
                entries.map { preferredTabWidth(it, fm) }
            }
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

    override fun getMaximumSize(): Dimension = Dimension(preferredSize.width, 32767)

    private fun updateLayout(fm: FontMetrics) {
        val lock = hoverTabWidthLock
        val prefWidths =
            if (lock != null) {
                List(entries.size) { lock }
            } else {
                entries.mapIndexed { i, entry ->
                    val basePref = preferredTabWidth(entry, fm)
                    if (i == editingIndex) {
                        maxOf(basePref, 220) // Expand the tab being renamed
                    } else {
                        basePref
                    }
                }
            }
        layout = LatticeTabLayoutCalculator.compute(width, prefWidths, scrollOffset, editingIndex)
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
            updateRenameTextFieldBounds(fm)

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
        g2.clipRect(
            (TAB_START_X - TAB_CLIP_OVERPAINT).coerceAtLeast(0),
            0,
            clipWidth + TAB_CLIP_OVERPAINT * 2,
            height,
        )
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
                entry.id == selectedId -> {
                    if (entry.id == fadingInId && fadeProgress < 1.0f) {
                        val startColor =
                            if (index == tabHoverIndex) {
                                if (isTabPressed) LatticeChrome.controlPressed else LatticeChrome.tabHoverBackground
                            } else {
                                TRANSPARENT_COLOR
                            }
                        blendColors(startColor, LatticeChrome.tabSelectedBackground, fadeProgress)
                    } else {
                        LatticeChrome.tabSelectedBackground
                    }
                }
                entry.id == fadingOutId -> {
                    val endColor =
                        if (index == tabHoverIndex) {
                            if (isTabPressed) LatticeChrome.controlPressed else LatticeChrome.tabHoverBackground
                        } else {
                            TRANSPARENT_COLOR
                        }
                    blendColors(LatticeChrome.tabSelectedBackground, endColor, fadeProgress)
                }
                index == tabHoverIndex -> {
                    if (isTabPressed) {
                        LatticeChrome.controlPressed
                    } else {
                        LatticeChrome.tabHoverBackground
                    }
                }
                else -> TRANSPARENT_COLOR
            }

        val bounds = TabShapeBounds(x, y, w, h)
        val useSelectedShape = selected || entry.id == fadingOutId
        if (bg.alpha > 0) {
            g2.color = bg
            resetTabShape(tabFillShape, bounds, useSelectedShape, closePath = true)
            g2.fill(tabFillShape)
            if (useSelectedShape) {
                g2.fillRect(x, height - SELECTED_TAB_JOIN_OVERLAP, w, SELECTED_TAB_JOIN_OVERLAP)
            }
        }

        val tabColor = entry.color
        if (tabColor != null) {
            val isFadingIn = entry.id == selectedId && entry.id == fadingInId && fadeProgress < 1.0f
            val isFadingOut = entry.id == fadingOutId
            val lineAlpha =
                when {
                    isFadingIn -> 0.63f + 0.37f * fadeProgress
                    isFadingOut -> 1.0f - 0.37f * fadeProgress
                    entry.id == selectedId -> 1.0f
                    else -> 0.63f
                }
            g2.color =
                Color(
                    tabColor.red,
                    tabColor.green,
                    tabColor.blue,
                    (tabColor.alpha * lineAlpha).toInt().coerceIn(0, 255),
                )
            g2.stroke = BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            val stripePath = Path2D.Double()
            stripePath.moveTo(bounds.left, bounds.top + bounds.corner)
            stripePath.quadTo(bounds.left, bounds.top, bounds.left + bounds.corner, bounds.top)
            stripePath.lineTo(bounds.right - bounds.corner, bounds.top)
            stripePath.quadTo(bounds.right, bounds.top, bounds.right, bounds.top + bounds.corner)
            g2.draw(stripePath)
        }

        val drawBorder = index == tabHoverIndex
        if (drawBorder) {
            val borderAlpha =
                when {
                    entry.id == selectedId -> {
                        if (entry.id == fadingInId && fadeProgress < 1.0f) {
                            1.0f - fadeProgress
                        } else {
                            0.0f
                        }
                    }
                    entry.id == fadingOutId -> {
                        fadeProgress
                    }
                    else -> 1.0f
                }
            if (borderAlpha > 0f) {
                val baseBorderColor = LatticeChrome.border
                g2.color =
                    Color(
                        baseBorderColor.red,
                        baseBorderColor.green,
                        baseBorderColor.blue,
                        (baseBorderColor.alpha * borderAlpha).toInt().coerceIn(0, 255),
                    )
                g2.stroke = HAIRLINE_STROKE
                resetTabShape(tabBorderShape, bounds, useSelectedShape, closePath = false)
                g2.draw(tabBorderShape)
            }
        }

        // Draw vertical divider between inactive tabs
        val nextId = entries.getOrNull(index + 1)?.id
        val isSelfSelectedOrFading = selected || entry.id == fadingOutId
        val isNextSelectedOrFading = nextId == selectedId || nextId == fadingOutId
        val nextHovered = index + 1 == tabHoverIndex
        if (!isSelfSelectedOrFading && !isNextSelectedOrFading && !nextHovered && index < entries.size - 1 && index != tabHoverIndex) {
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
        profileIcons.paint(
            g2 = g2,
            profileKind = entry.profileKind,
            x = iconX,
            y = iconDrawY,
            selected = useSelectedShape,
            highlighted = index == tabHoverIndex,
        )

        val titleFg =
            when {
                entry.id == selectedId -> {
                    if (entry.id == fadingInId && fadeProgress < 1.0f) {
                        val startColor = if (index == tabHoverIndex) LatticeChrome.textHover else LatticeChrome.textSecondary
                        blendColors(startColor, LatticeChrome.textPrimary, fadeProgress)
                    } else {
                        LatticeChrome.textPrimary
                    }
                }
                entry.id == fadingOutId -> {
                    val endColor = if (index == tabHoverIndex) LatticeChrome.textHover else LatticeChrome.textSecondary
                    blendColors(LatticeChrome.textPrimary, endColor, fadeProgress)
                }
                index == tabHoverIndex -> LatticeChrome.textHover
                else -> LatticeChrome.textSecondary
            }
        g2.color = titleFg
        val labelMaxWidth = w - TAB_LABEL_PADDING_LEFT - ICON_TEXT_GAP - CLOSE_BUTTON_SIZE - CLOSE_BUTTON_MARGIN_RIGHT - 4
        if (index != editingIndex && labelMaxWidth > 10) {
            val labelX = x + TAB_LABEL_PADDING_LEFT + ICON_TEXT_GAP
            val clipped = clipText(entry.title, labelMaxWidth, fm)
            g2.drawString(clipped, labelX, iconY)
        }

        val closeBtnX = x + w - CLOSE_BUTTON_SIZE - CLOSE_BUTTON_MARGIN_RIGHT
        val closeBtnY = y + (h - CLOSE_BUTTON_SIZE) / 2
        paintCloseButton(
            g2 = g2,
            index = index,
            x = closeBtnX,
            y = closeBtnY,
            tabSelected = selected,
            fadingOut = entry.id == fadingOutId,
            fadingIn = entry.id == selectedId && entry.id == fadingInId && fadeProgress < 1.0f,
            fadeProgress = fadeProgress,
        )
    }

    private fun paintCloseButton(
        g2: Graphics2D,
        index: Int,
        x: Int,
        y: Int,
        tabSelected: Boolean,
        fadingOut: Boolean,
        fadingIn: Boolean,
        fadeProgress: Float,
    ) {
        val hovered = index == closeHoverIndex
        val visible = tabSelected || index == tabHoverIndex || hovered || fadingOut

        if (visible) {
            val closeAlpha =
                when {
                    fadingIn -> {
                        val startAlpha = if (index == tabHoverIndex || hovered) 1.0f else 0.0f
                        startAlpha + (1.0f - startAlpha) * fadeProgress
                    }
                    fadingOut -> {
                        val endAlpha = if (index == tabHoverIndex || hovered) 1.0f else 0.0f
                        1.0f - (1.0f - endAlpha) * fadeProgress
                    }
                    else -> 1.0f
                }

            if (closeAlpha > 0f) {
                val baseBg =
                    if (hovered) {
                        val isPressed = activePressedResult == HitResult.TabClose(index)
                        if (isPressed) LatticeChrome.controlPressed else LatticeChrome.controlHover
                    } else {
                        null
                    }
                if (baseBg != null) {
                    g2.color = Color(baseBg.red, baseBg.green, baseBg.blue, (baseBg.alpha * closeAlpha).toInt().coerceIn(0, 255))
                    g2.fillRoundRect(x, y, CLOSE_BUTTON_SIZE, CLOSE_BUTTON_SIZE, 3, 3)
                }

                val baseFg = if (hovered) LatticeChrome.textPrimary else LatticeChrome.textSecondary
                g2.color = Color(baseFg.red, baseFg.green, baseFg.blue, (baseFg.alpha * closeAlpha).toInt().coerceIn(0, 255))
                g2.stroke = ICON_STROKE
                val pad = CLOSE_BUTTON_SIZE / 4
                g2.drawLine(x + pad, y + pad, x + CLOSE_BUTTON_SIZE - pad, y + CLOSE_BUTTON_SIZE - pad)
                g2.drawLine(x + CLOSE_BUTTON_SIZE - pad, y + pad, x + pad, y + CLOSE_BUTTON_SIZE - pad)
            }
        }
    }

    private fun resetTabShape(
        shape: Path2D.Double,
        bounds: TabShapeBounds,
        selected: Boolean,
        closePath: Boolean,
    ) {
        shape.reset()
        appendTabStart(shape, bounds, selected)
        appendTabTop(shape, bounds)
        appendTabEnd(shape, bounds, selected)
        if (closePath) shape.closePath()
    }

    private fun appendTabStart(
        shape: Path2D.Double,
        bounds: TabShapeBounds,
        selected: Boolean,
    ) {
        if (selected) {
            shape.moveTo(bounds.left - bounds.bottomCorner, bounds.bottom)
            shape.quadTo(bounds.left, bounds.bottom, bounds.left, bounds.bottom - bounds.bottomCorner)
        } else {
            shape.moveTo(bounds.left, bounds.bottom)
        }
    }

    private fun appendTabTop(
        shape: Path2D.Double,
        bounds: TabShapeBounds,
    ) {
        shape.lineTo(bounds.left, bounds.top + bounds.corner)
        shape.quadTo(bounds.left, bounds.top, bounds.left + bounds.corner, bounds.top)
        shape.lineTo(bounds.right - bounds.corner, bounds.top)
        shape.quadTo(bounds.right, bounds.top, bounds.right, bounds.top + bounds.corner)
    }

    private fun appendTabEnd(
        shape: Path2D.Double,
        bounds: TabShapeBounds,
        selected: Boolean,
    ) {
        if (selected) {
            shape.lineTo(bounds.right, bounds.bottom - bounds.bottomCorner)
            shape.quadTo(bounds.right, bounds.bottom, bounds.right + bounds.bottomCorner, bounds.bottom)
        } else {
            shape.lineTo(bounds.right, bounds.bottom)
        }
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
                    if (e.isPopupTrigger) {
                        showContextMenuIfTabHit(e)
                        return
                    }
                    val fm = getFontMetrics(font.deriveFont(13f))
                    val hit = hitTest(e.x, e.y, fm)

                    if (SwingUtilities.isLeftMouseButton(e) || SwingUtilities.isMiddleMouseButton(e)) {
                        activePressedResult = hit
                        repaint()
                    }
                }

                override fun mouseReleased(e: MouseEvent) {
                    if (e.isPopupTrigger) {
                        showContextMenuIfTabHit(e)
                        return
                    }
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
                                    if (e.clickCount == 2) {
                                        triggerRenameDialog(hit.index)
                                    } else if (id != selectedId) {
                                        val oldId = selectedId
                                        selectedId = id
                                        startSelectionFade(oldId, id)
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

                override fun mouseEntered(e: MouseEvent) {
                    isMouseOver = true
                    lastMouseX = e.x
                    lastMouseY = e.y
                    val fm = getFontMetrics(font.deriveFont(13f))
                    updateHoverState(e.x, e.y, fm)
                }

                override fun mouseExited(e: MouseEvent) {
                    isMouseOver = false
                    lastMouseX = -1
                    lastMouseY = -1
                    hoverTabWidthLock = null
                    val fm = getFontMetrics(font.deriveFont(13f))
                    updateHoverState(-1, -1, fm)
                    revalidate()
                    repaint()
                }

                override fun mouseMoved(e: MouseEvent) {
                    isMouseOver = true
                    lastMouseX = e.x
                    lastMouseY = e.y
                    val fm = getFontMetrics(font.deriveFont(13f))
                    updateHoverState(e.x, e.y, fm)
                }

                override fun mouseDragged(e: MouseEvent) {
                    isMouseOver = true
                    lastMouseX = e.x
                    lastMouseY = e.y
                    val fm = getFontMetrics(font.deriveFont(13f))
                    updateHoverState(e.x, e.y, fm)
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

    private fun updateHoverState(
        x: Int,
        y: Int,
        fm: FontMetrics,
    ) {
        val hit = if (isMouseOver) hitTest(x, y, fm) else HitResult.None

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

    private fun showContextMenuIfTabHit(e: MouseEvent) {
        val fm = getFontMetrics(font.deriveFont(13f))
        val hit = hitTest(e.x, e.y, fm)
        if (hit is HitResult.Tab) {
            showTabContextMenu(hit.index, e.x, e.y)
        }
    }

    private fun showTabContextMenu(
        index: Int,
        x: Int,
        y: Int,
    ) {
        val tabId = entries[index].id
        val menu = javax.swing.JPopupMenu()

        val colorMenu = javax.swing.JMenu("Tab Color")

        val colors =
            listOf(
                "Blue" to "#3b82f6",
                "Red" to "#ef4444",
                "Green" to "#10b981",
                "Orange" to "#f97316",
                "Purple" to "#a855f7",
                "Yellow" to "#eab308",
                "Gray" to "#6b7280",
            )

        colors.forEach { (name, hex) ->
            val item = javax.swing.JMenuItem(name)
            item.icon = ColorIcon(Color.decode(hex))
            item.addActionListener {
                onTabColorChanged(tabId, hex)
            }
            colorMenu.add(item)
        }

        colorMenu.addSeparator()
        val resetItem = javax.swing.JMenuItem("Reset Color")
        resetItem.addActionListener {
            onTabColorChanged(tabId, null)
        }
        colorMenu.add(resetItem)

        menu.add(colorMenu)
        menu.addSeparator()

        val renameItem = javax.swing.JMenuItem("Rename Tab...")
        renameItem.addActionListener {
            triggerRenameDialog(index)
        }
        menu.add(renameItem)

        val closeItem = javax.swing.JMenuItem("Close Tab")
        closeItem.addActionListener {
            onTabClose(tabId)
        }
        menu.add(closeItem)

        menu.show(this, x, y)
    }

    private fun triggerRenameDialog(index: Int) {
        startEditing(index)
    }

    private fun startEditing(index: Int) {
        val entry = entries.getOrNull(index) ?: return
        editingIndex = index

        fadeTimer?.stop()
        fadeTimer = null
        fadingOutId = null
        fadingInId = null
        fadeProgress = 1.0f

        renameTextField?.let { remove(it) }

        val fm = getFontMetrics(font.deriveFont(13f))
        updateLayout(fm)
        scrollToVisible(index)
        revalidate()
        repaint()

        val tf = javax.swing.JTextField(entry.title)
        tf.font = font.deriveFont(13f)
        tf.foreground = LatticeChrome.textPrimary
        tf.caretColor = LatticeChrome.textPrimary
        tf.background = LatticeChrome.tabSelectedBackground
        tf.border =
            javax.swing.BorderFactory.createCompoundBorder(
                javax.swing.BorderFactory.createLineBorder(LatticeChrome.accent, 1),
                javax.swing.BorderFactory.createEmptyBorder(0, 4, 0, 4),
            )

        tf.addActionListener {
            commitEditing()
        }
        tf.addKeyListener(
            object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    if (e.keyCode == KeyEvent.VK_ESCAPE) {
                        cancelEditing()
                    }
                }
            },
        )

        tf.addFocusListener(
            object : FocusAdapter() {
                override fun focusLost(e: FocusEvent) {
                    commitEditing()
                }
            },
        )

        renameTextField = tf
        add(tf)
        tf.requestFocusInWindow()
        tf.selectAll()

        updateRenameTextFieldBounds(fm)
    }

    private fun commitEditing() {
        val index = editingIndex
        val tf = renameTextField
        if (index in entries.indices && tf != null) {
            val trimmed = tf.text.trim()
            val newTitle = trimmed.ifEmpty { null }
            onTabRenameRequested(entries[index].id, newTitle)
        }
        cancelEditing()
    }

    private fun cancelEditing() {
        val tf = renameTextField
        if (tf != null) {
            remove(tf)
            renameTextField = null
        }
        editingIndex = -1
        revalidate()
        repaint()
    }

    private fun updateRenameTextFieldBounds(fm: FontMetrics) {
        val tf = renameTextField ?: return
        val index = editingIndex
        if (index !in entries.indices) {
            cancelEditing()
            return
        }
        val w = tabWidths[index]
        val x = layout.tabX(index)
        val y = TAB_TOP_PADDING
        val h = height - TAB_TOP_PADDING

        val labelX = x + TAB_LABEL_PADDING_LEFT + ICON_TEXT_GAP
        val labelMaxWidth = w - TAB_LABEL_PADDING_LEFT - ICON_TEXT_GAP - CLOSE_BUTTON_SIZE - CLOSE_BUTTON_MARGIN_RIGHT - 4

        val tfX = labelX - scrollOffset
        val tfY = y + 2
        val tfW = labelMaxWidth
        val tfH = h - 4

        if (tf.x != tfX || tf.y != tfY || tf.width != tfW || tf.height != tfH) {
            tf.setBounds(tfX, tfY, tfW, tfH)
        }
    }

    private class ColorIcon(
        private val color: Color,
    ) : javax.swing.Icon {
        override fun paintIcon(
            c: Component?,
            g: Graphics?,
            x: Int,
            y: Int,
        ) {
            val g2 = g?.create() as? Graphics2D ?: return
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = color
                g2.fillRoundRect(x + 2, y + 2, iconWidth - 4, iconHeight - 4, 3, 3)
            } finally {
                g2.dispose()
            }
        }

        override fun getIconWidth(): Int = 16

        override fun getIconHeight(): Int = 16
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
        private const val FADE_DURATION_MS = LatticeTabMetrics.FADE_DURATION_MS
        private const val FADE_DELAY_MS = LatticeTabMetrics.FADE_DELAY_MS
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
        private const val BOTTOM_OUTSIDE_RADIUS = LatticeTabMetrics.BOTTOM_OUTSIDE_RADIUS
        private const val TAB_CLIP_OVERPAINT = BOTTOM_OUTSIDE_RADIUS + 1
        private const val SELECTED_TAB_JOIN_OVERLAP = 2
        private const val ACTION_BUTTON_HOVER_SIZE = 24
        private const val ACTION_BUTTON_CORNER_RADIUS = 3
        private val TRANSPARENT_COLOR = Color(0, 0, 0, 0)
        private val HAIRLINE_STROKE = LatticeTabMetrics.HAIRLINE_STROKE
        private val DIVIDER_STROKE = LatticeTabMetrics.DIVIDER_STROKE
        private val ICON_STROKE = LatticeTabMetrics.ICON_STROKE
        private val ACTION_ICON_STROKE = LatticeTabMetrics.ACTION_ICON_STROKE
    }

    private data class TabShapeBounds(
        val left: Double,
        val top: Double,
        val right: Double,
        val bottom: Double,
        val corner: Double,
        val bottomCorner: Double,
    ) {
        constructor(
            x: Int,
            y: Int,
            w: Int,
            h: Int,
        ) : this(
            left = x.toDouble(),
            top = y.toDouble(),
            right = (x + w).toDouble(),
            bottom = (y + h).toDouble(),
            corner = CORNER_RADIUS.toDouble(),
            bottomCorner = BOTTOM_OUTSIDE_RADIUS.toDouble(),
        )
    }
}
