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
import java.awt.event.*
import java.beans.PropertyChangeListener
import java.util.*
import javax.accessibility.*
import javax.swing.JComponent
import javax.swing.SwingUtilities
import javax.swing.ToolTipManager
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Adaptive custom-painted completion list used by the standalone Swing host. */
internal class SwingCompletionPopupView(
    private val listener: SwingShellSuggestionViewListener,
) : JComponent(),
    SwingShellSuggestionView,
    Accessible {
    override val component: JComponent
        get() = this

    private var snapshot: SwingShellSuggestionViewSnapshot = SwingShellSuggestionViewSnapshot.EMPTY
    private val popupLayout = SwingCompletionPopupLayout()
    private var appearance: SwingCompletionPopupAppearance? = null
    private var appearanceFont: Font? = null
    private var appearanceBackground: Color? = null
    private var appearanceForeground: Color? = null
    private var appearanceParent: Component? = null
    private var preparedComponentWidth = -1
    private var closed = false

    internal var layoutPreparationCount: Int = 0
        private set

    private val pointerHandler =
        object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(event)) return
                val row = rowAt(event.y)
                if (row >= 0) listener.onSuggestionClicked(row)
            }

            override fun mouseMoved(event: MouseEvent) {
                val row = rowAt(event.y)
                if (row >= 0) listener.onSuggestionHovered(row)
            }

            override fun mouseWheelMoved(event: MouseWheelEvent) {
                val rotation = event.wheelRotation.coerceIn(-MAX_WHEEL_STEP, MAX_WHEEL_STEP)
                if (rotation == 0) return
                listener.onSuggestionScrollRequested(rotation)
                event.consume()
            }
        }
    private val resizeHandler =
        object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent) {
                if (width != preparedComponentWidth) prepareLayout(revalidateComponent = false)
            }
        }
    private val appearanceChangeHandler =
        PropertyChangeListener { event ->
            if (event.propertyName in APPEARANCE_PROPERTIES) {
                invalidateAppearance()
                prepareLayout(revalidateComponent = true)
            }
        }
    private val parentAppearanceChangeHandler =
        PropertyChangeListener { event ->
            if (event.propertyName in APPEARANCE_PROPERTIES) {
                invalidateAppearance()
                prepareLayout(revalidateComponent = true)
                repaint()
            }
        }
    private val hierarchyHandler =
        HierarchyListener { event ->
            if (event.changeFlags and HierarchyEvent.PARENT_CHANGED.toLong() != 0L) synchronizeAppearanceParent()
        }

    init {
        isOpaque = false
        isFocusable = false
        name = "commandCompletionList"
        toolTipText = ""
        addMouseListener(pointerHandler)
        addMouseMotionListener(pointerHandler)
        addMouseWheelListener(pointerHandler)
        addComponentListener(resizeHandler)
        addPropertyChangeListener(appearanceChangeHandler)
        addHierarchyListener(hierarchyHandler)
        ToolTipManager.sharedInstance().registerComponent(this)
    }

    override fun update(snapshot: SwingShellSuggestionViewSnapshot) {
        check(!closed) { "completion view is closed" }
        val oldSnapshot = this.snapshot
        this.snapshot = snapshot
        if (oldSnapshot.visibleSuggestions != snapshot.visibleSuggestions) {
            prepareLayout(revalidateComponent = true)
        }
        accessibleContextOrNull()?.let { context ->
            context.firePropertyChange(AccessibleContext.ACCESSIBLE_VISIBLE_DATA_PROPERTY, oldSnapshot, snapshot)
            if (oldSnapshot.selectedIndex != snapshot.selectedIndex) {
                context.firePropertyChange(
                    AccessibleContext.ACCESSIBLE_SELECTION_PROPERTY,
                    oldSnapshot.selectedSuggestion,
                    snapshot.selectedSuggestion,
                )
            }
        }
        repaint()
    }

    override fun close() {
        if (closed) return
        closed = true
        snapshot = SwingShellSuggestionViewSnapshot.EMPTY
        popupLayout.clear()
        preparedComponentWidth = -1
        ToolTipManager.sharedInstance().unregisterComponent(this)
        removeMouseListener(pointerHandler)
        removeMouseMotionListener(pointerHandler)
        removeMouseWheelListener(pointerHandler)
        removeComponentListener(resizeHandler)
        removePropertyChangeListener(appearanceChangeHandler)
        removeHierarchyListener(hierarchyHandler)
        appearanceParent?.removePropertyChangeListener(parentAppearanceChangeHandler)
        appearanceParent = null
    }

    override fun addNotify() {
        super.addNotify()
        synchronizeAppearanceParent()
        invalidateAppearance()
        prepareLayout(revalidateComponent = true)
    }

    override fun getPreferredSize(): Dimension =
        if (snapshot.visibleSuggestions.isEmpty()) {
            Dimension(0, 0)
        } else {
            Dimension(popupLayout.preferredWidth, popupLayout.preferredHeight)
        }

    override fun getToolTipText(event: MouseEvent): String? {
        val row = rowAt(event.y)
        return if (row >= 0) popupLayout.row(row).tooltipText.takeIf { it.isNotEmpty() } else null
    }

    override fun getToolTipLocation(event: MouseEvent): Point? {
        val row = rowAt(event.y)
        if (row < 0) return null
        val physicalRow = row - firstPaintedRow()
        return Point(
            SwingCompletionPopupLayout.PRIMARY_X,
            SwingCompletionPopupLayout.SURFACE_PADDING + (physicalRow + 1) * popupLayout.rowHeight,
        )
    }

    override fun paintComponent(graphics: Graphics) {
        if (snapshot.visibleSuggestions.isEmpty() || width <= 0 || height <= 0) return
        val graphicsCopy = graphics.create()
        if (graphicsCopy !is Graphics2D) {
            graphicsCopy.dispose()
            return
        }
        val graphics2D = graphicsCopy
        try {
            configureRendering(graphics2D)
            val previousAppearance = appearance
            val resolvedAppearance = ensureAppearance()
            val appearanceChanged = previousAppearance !== resolvedAppearance
            if (appearanceChanged || popupLayout.fontRenderContext != graphics2D.fontRenderContext) {
                prepareLayout(graphics2D.fontRenderContext, revalidateComponent = appearanceChanged)
            }
            paintSurface(graphics2D, resolvedAppearance)
            paintRows(graphics2D, resolvedAppearance)
            paintScrollPosition(graphics2D, resolvedAppearance)
        } finally {
            graphics2D.dispose()
        }
    }

    override fun getAccessibleContext(): AccessibleContext {
        if (accessibleContext == null) {
            accessibleContext = AccessibleCompletionList()
        }
        return accessibleContext
    }

    private fun paintSurface(
        graphics: Graphics2D,
        appearance: SwingCompletionPopupAppearance,
    ) {
        val palette = appearance.palette
        val surfaceWidth = max(1, width - SHADOW_INSET)
        val surfaceHeight = max(1, height - SHADOW_INSET)
        graphics.color = palette.shadow
        graphics.fillRoundRect(
            SHADOW_INSET,
            SHADOW_INSET + 1,
            max(1, width - SHADOW_INSET * 2),
            max(1, height - SHADOW_INSET * 2),
            SURFACE_ARC,
            SURFACE_ARC,
        )
        graphics.color = palette.background
        graphics.fillRoundRect(0, 0, surfaceWidth, surfaceHeight, SURFACE_ARC, SURFACE_ARC)
        graphics.color = palette.border
        graphics.drawRoundRect(0, 0, max(0, surfaceWidth - 1), max(0, surfaceHeight - 1), SURFACE_ARC, SURFACE_ARC)
    }

    private fun paintRows(
        graphics: Graphics2D,
        appearance: SwingCompletionPopupAppearance,
    ) {
        val capacity = popupLayout.visibleRowCapacity(height)
        if (capacity == 0) return
        val firstRow = firstPaintedRow()
        val endRow = min(popupLayout.rowCount, firstRow + capacity)
        var rowIndex = firstRow
        while (rowIndex < endRow) {
            val row = popupLayout.row(rowIndex)
            val physicalIndex = rowIndex - firstRow
            val top = SwingCompletionPopupLayout.SURFACE_PADDING + physicalIndex * popupLayout.rowHeight
            val selected = rowIndex == snapshot.selectedIndex
            if (selected) {
                graphics.color = appearance.palette.selectionBackground
                graphics.fillRoundRect(
                    ROW_HORIZONTAL_INSET,
                    top + ROW_VERTICAL_INSET,
                    width - ROW_HORIZONTAL_INSET * 2 - SHADOW_INSET,
                    popupLayout.rowHeight - ROW_VERTICAL_INSET * 2,
                    SELECTION_ARC,
                    SELECTION_ARC,
                )
            }

            paintRoleIcon(
                graphics,
                x = ICON_X,
                centerY = top + popupLayout.rowHeight / 2,
                size = popupLayout.iconSize,
                role = row.accentRole,
                color = appearance.palette.accent(row.accentRole),
            )
            val primary = if (selected) row.selectedPrimaryLayout else row.primaryLayout
            primary.draw(
                graphics,
                SwingCompletionPopupLayout.PRIMARY_X.toFloat(),
                (top + popupLayout.primaryBaseline).toFloat(),
            )
            val detailX =
                SwingCompletionPopupLayout.PRIMARY_X + ceil(primary.advance.toDouble()).toInt() + SwingCompletionPopupLayout.DETAIL_GAP
            row.detailLayout?.let { detail ->
                graphics.color = if (selected) appearance.palette.selectedForeground else appearance.palette.mutedForeground
                detail.draw(graphics, detailX.toFloat(), (top + popupLayout.detailBaseline).toFloat())
            }
            row.sourceLayout?.let { source ->
                val sourceX =
                    width -
                        SHADOW_INSET -
                        SwingCompletionPopupLayout.RIGHT_CONTENT_INSET -
                        SwingCompletionPopupLayout.SCROLLBAR_RESERVE -
                        row.sourceBadgeWidth
                val badgeHeight = ceil(source.ascent + source.descent).toInt() + SwingCompletionPopupLayout.SOURCE_VERTICAL_PADDING * 2
                val badgeY = top + (popupLayout.rowHeight - badgeHeight) / 2
                graphics.color = appearance.palette.sourceBackground
                graphics.fillRoundRect(sourceX, badgeY, row.sourceBadgeWidth, badgeHeight, SOURCE_ARC, SOURCE_ARC)
                graphics.color = appearance.palette.sourceForeground
                source.draw(
                    graphics,
                    (sourceX + SwingCompletionPopupLayout.SOURCE_HORIZONTAL_PADDING).toFloat(),
                    (top + popupLayout.sourceBaseline).toFloat(),
                )
            }
            rowIndex++
        }
    }

    private fun paintRoleIcon(
        graphics: Graphics2D,
        x: Int,
        centerY: Int,
        size: Int,
        role: SwingShellSuggestionAccentRole,
        color: Color,
    ) {
        val top = centerY - size / 2
        val right = x + size - 1
        graphics.color = color
        val previousStroke = graphics.stroke
        graphics.stroke = ICON_STROKE
        when (role) {
            SwingShellSuggestionAccentRole.COMMAND -> {
                graphics.drawLine(x + 1, top + 2, x + size / 2, centerY)
                graphics.drawLine(x + size / 2, centerY, x + 1, top + size - 2)
                graphics.drawLine(x + size / 2 + 2, top + size - 2, right, top + size - 2)
            }

            SwingShellSuggestionAccentRole.PATH -> {
                graphics.drawLine(x + 1, top + 3, x + size / 2 - 1, top + 3)
                graphics.drawLine(x + size / 2 - 1, top + 3, x + size / 2 + 1, top + 5)
                graphics.drawRoundRect(x + 1, top + 5, size - 2, size - 7, 2, 2)
            }

            SwingShellSuggestionAccentRole.OPTION -> {
                graphics.drawLine(x + 1, centerY - 3, right, centerY - 3)
                graphics.drawLine(x + 1, centerY + 3, right, centerY + 3)
                graphics.fillOval(x + size / 3, centerY - 5, 4, 4)
                graphics.fillOval(x + size * 2 / 3, centerY + 1, 4, 4)
            }

            SwingShellSuggestionAccentRole.HISTORY -> {
                graphics.drawOval(x + 1, top + 1, size - 2, size - 2)
                graphics.drawLine(x + size / 2, centerY, x + size / 2, top + 3)
                graphics.drawLine(x + size / 2, centerY, right - 2, centerY + 2)
            }

            SwingShellSuggestionAccentRole.OTHER -> graphics.fillOval(x + size / 2 - 2, centerY - 2, 5, 5)
        }
        graphics.stroke = previousStroke
    }

    private fun paintScrollPosition(
        graphics: Graphics2D,
        appearance: SwingCompletionPopupAppearance,
    ) {
        if (snapshot.totalSuggestionCount <= popupLayout.visibleRowCapacity(height)) return
        val trackHeight = max(1, height - SCROLL_INSET * 2 - SHADOW_INSET)
        val trackX = width - SCROLL_INSET - SCROLL_WIDTH - SHADOW_INSET
        graphics.color = appearance.palette.scrollTrack
        graphics.fillRoundRect(trackX, SCROLL_INSET, SCROLL_WIDTH, trackHeight, SCROLL_WIDTH, SCROLL_WIDTH)

        val capacity = popupLayout.visibleRowCapacity(height).coerceAtLeast(1)
        val firstAbsolute = snapshot.viewportStartIndex + firstPaintedRow()
        val thumbHeight = max(MIN_SCROLL_THUMB_HEIGHT, trackHeight * capacity / snapshot.totalSuggestionCount).coerceAtMost(trackHeight)
        val availableTravel = trackHeight - thumbHeight
        val maximumStart = max(1, snapshot.totalSuggestionCount - capacity)
        val thumbY = SCROLL_INSET + availableTravel * firstAbsolute.coerceAtMost(maximumStart) / maximumStart
        graphics.color = appearance.palette.scrollThumb
        graphics.fillRoundRect(trackX, thumbY, SCROLL_WIDTH, thumbHeight, SCROLL_WIDTH, SCROLL_WIDTH)
    }

    private fun prepareLayout(revalidateComponent: Boolean) {
        prepareLayout(renderContext = null, revalidateComponent = revalidateComponent)
    }

    private fun prepareLayout(
        renderContext: java.awt.font.FontRenderContext?,
        revalidateComponent: Boolean,
    ) {
        if (closed || snapshot.visibleSuggestions.isEmpty()) {
            popupLayout.clear()
            preparedComponentWidth = -1
            if (revalidateComponent) revalidate()
            return
        }
        val resolvedAppearance = ensureAppearance()
        val availableWidth = if (width > 0) width else SWING_COMPLETION_POPUP_MAX_WIDTH
        if (renderContext == null) {
            popupLayout.prepare(this, snapshot.visibleSuggestions, resolvedAppearance, availableWidth)
        } else {
            popupLayout.prepare(this, snapshot.visibleSuggestions, resolvedAppearance, availableWidth, renderContext)
        }
        preparedComponentWidth = width
        layoutPreparationCount++
        if (revalidateComponent) revalidate()
    }

    private fun ensureAppearance(): SwingCompletionPopupAppearance {
        val currentFont = parent?.font ?: font
        val currentBackground = parent?.background ?: background
        val currentForeground = parent?.foreground ?: foreground
        val cached = appearance
        if (
            cached != null &&
            appearanceFont == currentFont &&
            appearanceBackground == currentBackground &&
            appearanceForeground == currentForeground
        ) {
            return cached
        }
        return SwingCompletionPopupAppearanceResolver.resolve(currentFont, currentBackground, currentForeground).also {
            appearance = it
            appearanceFont = currentFont
            appearanceBackground = currentBackground
            appearanceForeground = currentForeground
        }
    }

    private fun invalidateAppearance() {
        appearance = null
        appearanceFont = null
        appearanceBackground = null
        appearanceForeground = null
    }

    private fun synchronizeAppearanceParent() {
        val currentParent = parent
        if (appearanceParent === currentParent) return
        appearanceParent?.removePropertyChangeListener(parentAppearanceChangeHandler)
        appearanceParent = currentParent
        currentParent?.addPropertyChangeListener(parentAppearanceChangeHandler)
        invalidateAppearance()
    }

    private fun configureRendering(graphics: Graphics2D) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
    }

    private fun firstPaintedRow(): Int = popupLayout.firstVisibleRow(height, snapshot.selectedIndex)

    private fun rowAt(y: Int): Int {
        if (y < SwingCompletionPopupLayout.SURFACE_PADDING) return -1
        val physicalRow = (y - SwingCompletionPopupLayout.SURFACE_PADDING) / popupLayout.rowHeight
        val capacity = popupLayout.visibleRowCapacity(height)
        if (physicalRow !in 0 until capacity) return -1
        val row = firstPaintedRow() + physicalRow
        return row.takeIf { it in 0 until popupLayout.rowCount } ?: -1
    }

    private fun accessibleContextOrNull(): AccessibleContext? = accessibleContext

    private inner class AccessibleCompletionList :
        AccessibleJComponent(),
        AccessibleSelection {
        override fun getAccessibleName(): String = super.getAccessibleName() ?: "Command completions"

        override fun getAccessibleDescription(): String {
            val count = snapshot.totalSuggestionCount
            val selectedIndex = snapshot.selectedIndex
            return if (selectedIndex !in 0 until popupLayout.rowCount) {
                "$count suggestions"
            } else {
                "$count suggestions, selected ${snapshot.absoluteSelectedIndex + 1}: ${popupLayout.row(selectedIndex).accessibleName}"
            }
        }

        override fun getAccessibleRole(): AccessibleRole = AccessibleRole.LIST

        override fun getAccessibleChildrenCount(): Int = snapshot.visibleSuggestions.size

        override fun getAccessibleChild(index: Int): Accessible? =
            index.takeIf { it in snapshot.visibleSuggestions.indices }?.let(::AccessibleCompletionRow)

        override fun getAccessibleSelection(): AccessibleSelection = this

        override fun getAccessibleSelectionCount(): Int = if (snapshot.selectedIndex >= 0) 1 else 0

        override fun getAccessibleSelection(index: Int): Accessible? =
            if (index == 0 && snapshot.selectedIndex >= 0) AccessibleCompletionRow(snapshot.selectedIndex) else null

        override fun isAccessibleChildSelected(index: Int): Boolean = index == snapshot.selectedIndex

        override fun addAccessibleSelection(index: Int) {
            if (index !in snapshot.visibleSuggestions.indices) return
            if (SwingUtilities.isEventDispatchThread()) {
                listener.onSuggestionHovered(index)
            } else {
                SwingUtilities.invokeLater { listener.onSuggestionHovered(index) }
            }
        }

        override fun removeAccessibleSelection(index: Int) = Unit

        override fun clearAccessibleSelection() = Unit

        override fun selectAllAccessibleSelection() = Unit
    }

    private inner class AccessibleCompletionRow(
        private val rowIndex: Int,
    ) : Accessible {
        private val row = popupLayout.row(rowIndex)
        private val selected = rowIndex == snapshot.selectedIndex
        private val showing =
            rowIndex in firstPaintedRow() until firstPaintedRow() + popupLayout.visibleRowCapacity(height)
        private val context =
            object : AccessibleContext() {
                override fun getAccessibleName(): String = row.accessibleName

                override fun getAccessibleDescription(): String = row.accessibleDescription

                override fun getAccessibleRole(): AccessibleRole = AccessibleRole.LIST_ITEM

                override fun getAccessibleStateSet(): AccessibleStateSet =
                    AccessibleStateSet().apply {
                        add(AccessibleState.ENABLED)
                        add(AccessibleState.VISIBLE)
                        add(AccessibleState.SELECTABLE)
                        if (selected) add(AccessibleState.SELECTED)
                        if (showing) add(AccessibleState.SHOWING)
                    }

                override fun getAccessibleIndexInParent(): Int = rowIndex

                override fun getAccessibleChildrenCount(): Int = 0

                override fun getAccessibleChild(index: Int): Accessible? = null

                override fun getLocale(): Locale = this@SwingCompletionPopupView.locale ?: Locale.getDefault()
            }

        init {
            context.accessibleParent = this@SwingCompletionPopupView
        }

        override fun getAccessibleContext(): AccessibleContext = context
    }

    private companion object {
        private const val MAX_WHEEL_STEP = 3
        private const val SHADOW_INSET = 2
        private const val SURFACE_ARC = 10
        private const val SELECTION_ARC = 7
        private const val SOURCE_ARC = 6
        private const val ROW_HORIZONTAL_INSET = 4
        private const val ROW_VERTICAL_INSET = 1
        private const val ICON_X = 10
        private const val SCROLL_INSET = 7
        private const val SCROLL_WIDTH = 3
        private const val MIN_SCROLL_THUMB_HEIGHT = 12
        private val ICON_STROKE = BasicStroke(1.35f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        private val APPEARANCE_PROPERTIES = setOf("font", "background", "foreground")
    }
}
