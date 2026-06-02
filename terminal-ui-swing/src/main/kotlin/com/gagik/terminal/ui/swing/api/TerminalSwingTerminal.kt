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
package com.gagik.terminal.ui.swing.api

import com.gagik.terminal.input.event.*
import com.gagik.terminal.protocol.MouseTrackingMode
import com.gagik.terminal.render.cache.TerminalRenderCache
import com.gagik.terminal.session.TerminalSession
import com.gagik.terminal.ui.swing.input.TerminalSwingKeyMapper
import com.gagik.terminal.ui.swing.render.TerminalGridPainter
import com.gagik.terminal.ui.swing.settings.TerminalClipboardAction
import com.gagik.terminal.ui.swing.settings.TerminalSwingMetrics
import com.gagik.terminal.ui.swing.settings.TerminalSwingSettings
import com.gagik.terminal.ui.swing.settings.TerminalSwingSettingsProvider
import com.gagik.terminal.ui.swing.viewport.TerminalRepaintSink
import com.gagik.terminal.ui.swing.viewport.TerminalSwingRepaintPlanner
import com.gagik.terminal.ui.swing.viewport.TerminalSwingScrollModel
import java.awt.*
import java.awt.event.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.swing.JComponent
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * Reusable Swing terminal component.
 *
 * The component consumes published render-cache snapshots from a
 * [TerminalSession] and paints terminal rows without knowing which transport
 * produced the bytes. Hosts own session creation, process lifecycle, and
 * connector choice outside this component.
 *
 * @param settingsProvider provider for immutable settings snapshots.
 */
class TerminalSwingTerminal(
    private val settingsProvider: TerminalSwingSettingsProvider =
        TerminalSwingSettingsProvider { TerminalSwingSettings() },
) : JComponent() {
    private var session: TerminalSession? = null
    private var settings: TerminalSwingSettings = settingsProvider.currentSettings()
    private var metrics: TerminalSwingMetrics = buildMetrics(settings)
    internal var cursorBlinkVisible: Boolean = true
    private var lastResizedColumns: Int = NO_RESIZE_DIMENSION
    private var lastResizedRows: Int = NO_RESIZE_DIMENSION
    private var selectionAnchorAbsoluteRow: Long? = null
    private var selectionAnchorColumn: Int = 0
    private var selectionAnchorRow: Int = 0
    private var selectionCaretAbsoluteRow: Long? = null
    private var selectionCaretColumn: Int = 0
    private var selectingWithMouse: Boolean = false
    private var selectionIsBlock: Boolean = false
    private var lastSelectionDragX: Int = 0
    private var lastSelectionDragY: Int = 0
    private var hoveredHyperlinkId: Int = NO_HYPERLINK_ID
    private var hyperlinkActivationHover: Boolean = false
    private val selectionAutoscrollTimer =
        Timer(50) {
            handleSelectionAutoscrollTick()
        }
    private val renderPending = AtomicBoolean(false)
    private val visibleGridSizeSnapshot = AtomicLong(packVisibleGridSize(1, 1))

    private val painter = TerminalGridPainter()
    private val repaintPlanner = TerminalSwingRepaintPlanner()
    private val scrollModel = TerminalSwingScrollModel()
    private val keyMapper = TerminalSwingKeyMapper()
    private val selectionTextExtractor = TerminalSelectionTextExtractor()
    private val repaintSink =
        object : TerminalRepaintSink {
            override fun requestFullRepaint() {
                repaint()
            }

            override fun requestRegionRepaint(
                x: Int,
                y: Int,
                width: Int,
                height: Int,
            ) {
                repaint(x, y, width, height)
            }
        }
    internal val cursorTimer =
        Timer(settings.cursorBlinkMillis) {
            cursorBlinkVisible = !cursorBlinkVisible
            repaintBlinkingCursor()
        }

    private val inputKeyListener =
        object : KeyAdapter() {
            override fun keyPressed(event: KeyEvent) {
                updateHyperlinkActivationHover(event.isControlDown)
                resetCursorBlinkOnEdt(forceRepaint = true)
                if (handleClipboardShortcut(event)) return

                val keyEvent = keyMapper.keyPressed(event) ?: return
                session?.encodeKey(keyEvent)
                event.consume()
            }

            override fun keyReleased(event: KeyEvent) {
                updateHyperlinkActivationHover(event.isControlDown)
            }

            override fun keyTyped(event: KeyEvent) {
                resetCursorBlinkOnEdt(forceRepaint = true)
                val keyEvent = keyMapper.keyTyped(event) ?: return
                session?.encodeKey(keyEvent)
                event.consume()
            }
        }

    private val viewportWheelListener =
        MouseWheelListener { event ->
            handleMouseWheel(event)
        }

    private val selectionMouseListener =
        object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) {
                handleSelectionMousePressed(event)
            }

            override fun mouseReleased(event: MouseEvent) {
                handleSelectionMouseReleased(event)
            }

            override fun mouseExited(event: MouseEvent) {
                clearHyperlinkHover()
            }
        }

    private val selectionMouseMotionListener =
        object : MouseMotionAdapter() {
            override fun mouseDragged(event: MouseEvent) {
                handleSelectionMouseDragged(event)
            }

            override fun mouseMoved(event: MouseEvent) {
                handleSelectionMouseMoved(event)
            }
        }

    private val resizeListener =
        object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent) {
                resizeSessionToVisibleGridOnEdt()
            }
        }

    init {
        font = settings.font
        background = Color(settings.palette.defaultBackground, true)
        foreground = Color(settings.palette.defaultForeground, true)
        isOpaque = true
        isFocusable = true
        focusTraversalKeysEnabled = false
        addKeyListener(inputKeyListener)
        addMouseListener(selectionMouseListener)
        addMouseMotionListener(selectionMouseMotionListener)
        addMouseWheelListener(viewportWheelListener)
        addComponentListener(resizeListener)
        preferredSize = preferredGridSize(settings.columns, settings.rows)
        cursorTimer.isRepeats = true
    }

    /**
     * Binds this component to [session].
     *
     * The session remains host-owned; this component only observes dirty render
     * notifications and repaints itself on the EDT. This method may be called
     * from any thread; component state is updated asynchronously on the EDT.
     *
     * @param session terminal session to display.
     */
    fun bind(session: TerminalSession) {
        runOnEdt {
            bindOnEdt(session)
        }
    }

    /**
     * Removes the current session binding.
     *
     * This method may be called from any thread; component state is updated
     * asynchronously on the EDT.
     */
    fun unbind() {
        runOnEdt {
            unbindOnEdt()
        }
    }

    /**
     * Rebuilds settings, metrics, preferred size, and repaint state.
     *
     * This method may be called from any thread; component state is updated
     * asynchronously on the EDT.
     */
    fun reloadSettings() {
        runOnEdt {
            reloadSettingsOnEdt()
        }
    }

    /**
     * Returns the grid size that fits in this component's current bounds.
     *
     * This method may be called from any thread. EDT callers refresh the
     * snapshot from live component state before reading it; off-EDT callers read
     * the last EDT-published snapshot without blocking the event queue.
     *
     * @return dimension where width is columns and height is rows.
     */
    fun visibleGridSize(): Dimension {
        val packed =
            if (SwingUtilities.isEventDispatchThread()) {
                updateVisibleGridSizeOnEdt()
            } else {
                visibleGridSizeSnapshot.get()
            }
        return Dimension(unpackVisibleColumns(packed), unpackVisibleRows(packed))
    }

    override fun addNotify() {
        super.addNotify()
        cursorTimer.start()
    }

    override fun removeNotify() {
        cursorTimer.stop()
        selectionAutoscrollTimer.stop()
        super.removeNotify()
    }

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)

        val g = graphics.create() as Graphics2D
        try {
            val publisher = session?.publisher
            if (publisher == null) {
                painter.clear(g, settings.palette, width, height)
                return
            }

            val painted =
                publisher.readCurrent { cache ->
                    painter.paint(
                        g = g,
                        cache = cache,
                        settings = settings,
                        metrics = metrics,
                        width = width,
                        height = height,
                        cursorBlinkVisible = cursorBlinkVisible,
                        contentYOffset = contentYOffset(cache),
                        selection = getViewportSelection(cache),
                        hoveredHyperlinkId = hoveredHyperlinkId,
                        hyperlinkActivationHover = hyperlinkActivationHover,
                    )
                }
            if (painted == null) {
                painter.clear(g, settings.palette, width, height)
            }
        } finally {
            g.dispose()
        }
    }

    private fun bindOnEdt(session: TerminalSession) {
        this.session?.onDirty = null
        this.session = session
        applySettingsToSession(session, settings)
        session.onDirty = {
            schedulePublishedFrame()
        }
        resetScrollbackState()
        clearSelection()
        stopSelectionDrag()
        lastResizedColumns = NO_RESIZE_DIMENSION
        lastResizedRows = NO_RESIZE_DIMENSION
        repaintPlanner.reset()
        renderPending.set(false)
        clearHyperlinkHover()
        resizeSessionToVisibleGridOnEdt()
        repaint()
    }

    private fun unbindOnEdt() {
        session?.onDirty = null
        session = null
        resetScrollbackState()
        clearSelection()
        stopSelectionDrag()
        lastResizedColumns = NO_RESIZE_DIMENSION
        lastResizedRows = NO_RESIZE_DIMENSION
        repaintPlanner.reset()
        renderPending.set(false)
        clearHyperlinkHover()
        repaint()
    }

    private fun reloadSettingsOnEdt() {
        settings = settingsProvider.currentSettings()
        font = settings.font
        background = Color(settings.palette.defaultBackground, true)
        foreground = Color(settings.palette.defaultForeground, true)
        isOpaque = true
        metrics = buildMetrics(settings)
        preferredSize = preferredGridSize(settings.columns, settings.rows)
        cursorTimer.delay = settings.cursorBlinkMillis
        session?.let { applySettingsToSession(it, settings) }
        clearSelection()
        clearHyperlinkHover()
        resizeSessionToVisibleGridOnEdt()
        revalidate()
        repaint()
    }

    /**
     * Returns the current visible cell selection, or `null` when nothing is
     * selected.
     *
     * This method may be called from any thread. Off-EDT callers receive a
     * snapshot from the EDT.
     *
     * @return current selection, or `null`.
     */
    fun currentSelection(): CellSelection? {
        val pub = session?.publisher ?: return null
        return pub.readCurrent { cache -> getViewportSelection(cache) }
    }

    private fun applySettingsToSession(
        session: TerminalSession,
        settings: TerminalSwingSettings,
    ) {
        session.setTreatAmbiguousAsWide(settings.treatAmbiguousAsWide)
    }

    private fun handleMouseWheel(event: MouseWheelEvent) {
        if (handleMouseTracking(event, TerminalMouseEventType.WHEEL)) return
        val boundSession = session ?: return
        val historySize = boundSession.publisher.readCurrent { cache -> cache.historySize } ?: return
        if (historySize == 0) return

        val delta = wheelScrollLines(event)
        if (delta == 0.0) return

        val previousRequestedOffset = scrollModel.requestedOffset
        val previousRequestedRows = requestedRenderRows()
        if (!scrollModel.scrollBy(delta, historySize)) return

        val nextRequestedOffset = scrollModel.requestedOffset
        val nextRequestedRows = requestedRenderRows()
        if (nextRequestedOffset != previousRequestedOffset || nextRequestedRows != previousRequestedRows) {
            boundSession.requestRender(nextRequestedOffset, nextRequestedRows)
        } else {
            repaint()
        }
        event.consume()
    }

    private fun isMouseTrackingIntercepted(event: MouseEvent): Boolean {
        if (event.isShiftDown) return false
        val trackingMode = session?.terminal?.getModeSnapshot()?.mouseTrackingMode ?: MouseTrackingMode.OFF
        return trackingMode != MouseTrackingMode.OFF
    }

    private fun handleMouseTracking(
        event: MouseEvent,
        type: TerminalMouseEventType,
    ): Boolean {
        val boundSession = session ?: return false
        if (!isMouseTrackingIntercepted(event)) return false

        boundSession.publisher.readCurrent { cache ->
            val cell = cellAt(event, cache)
            val column = unpackCellColumn(cell)
            val row = unpackCellRow(cell)

            val button =
                if (event is MouseWheelEvent) {
                    if (event.wheelRotation < 0) TerminalMouseButton.WHEEL_UP else TerminalMouseButton.WHEEL_DOWN
                } else {
                    when {
                        SwingUtilities.isLeftMouseButton(event) -> TerminalMouseButton.LEFT
                        SwingUtilities.isMiddleMouseButton(event) -> TerminalMouseButton.MIDDLE
                        SwingUtilities.isRightMouseButton(event) -> TerminalMouseButton.RIGHT
                        else -> TerminalMouseButton.NONE
                    }
                }

            var mods = TerminalModifiers.NONE
            if (event.isShiftDown) mods = mods or TerminalModifiers.SHIFT
            if (event.isAltDown) mods = mods or TerminalModifiers.ALT
            if (event.isControlDown) mods = mods or TerminalModifiers.CTRL
            if (event.isMetaDown) mods = mods or TerminalModifiers.META

            val mouseEvent =
                TerminalMouseEvent(
                    column = column,
                    row = row,
                    button = button,
                    type = type,
                    modifiers = mods,
                )
            boundSession.encodeMouse(mouseEvent)
        }
        event.consume()
        return true
    }

    private fun handleSelectionMouseMoved(event: MouseEvent) {
        if (handleMouseTracking(event, TerminalMouseEventType.MOTION)) {
            clearHyperlinkHover()
            return
        }
        updateHyperlinkHover(resolvableHyperlinkIdAt(event), activationHover = event.isControlDown)
    }

    private fun handleSelectionMousePressed(event: MouseEvent) {
        if (handleMouseTracking(event, TerminalMouseEventType.PRESS)) return
        if (handleHyperlinkActivation(event)) return
        if (!SwingUtilities.isLeftMouseButton(event)) return
        requestFocusInWindow()

        selectingWithMouse = true
        selectionIsBlock = event.isAltDown
        lastSelectionDragX = event.x
        lastSelectionDragY = event.y

        val publisher = session?.publisher ?: return
        publisher.readCurrent { cache ->
            val cell = cellAt(event, cache)
            val column = unpackCellColumn(cell)
            val row = unpackCellRow(cell)

            when {
                event.clickCount >= 3 -> {
                    val absRow = cache.discardedCount + cache.historySize - cache.scrollbackOffset + row
                    selectionAnchorAbsoluteRow = absRow
                    selectionAnchorColumn = 0
                    selectionCaretAbsoluteRow = absRow
                    selectionCaretColumn = cache.columns
                }

                event.clickCount == 2 -> {
                    val wordSel = selectionTextExtractor.wordSelectionAt(cache, row, column)
                    if (wordSel != null) {
                        selectionAnchorAbsoluteRow = cache.discardedCount + cache.historySize - cache.scrollbackOffset + wordSel.anchorRow
                        selectionAnchorColumn = wordSel.anchorColumn
                        selectionCaretAbsoluteRow = cache.discardedCount + cache.historySize - cache.scrollbackOffset + wordSel.caretRow
                        selectionCaretColumn = wordSel.caretColumn
                    } else {
                        clearSelection()
                    }
                }

                else -> {
                    selectionAnchorColumn = column
                    selectionAnchorRow = row
                    selectionAnchorAbsoluteRow = cache.discardedCount + cache.historySize - cache.scrollbackOffset + row
                    selectionCaretAbsoluteRow = null
                }
            }
        }
        updateSelectionAutoscroll()
        repaint()
        event.consume()
    }

    private fun handleSelectionMouseDragged(event: MouseEvent) {
        if (handleMouseTracking(event, TerminalMouseEventType.MOTION)) return
        if (event.modifiersEx and MouseEvent.BUTTON1_DOWN_MASK == 0) return

        selectingWithMouse = true
        selectionIsBlock = event.isAltDown
        lastSelectionDragX = event.x
        lastSelectionDragY = event.y

        val publisher = session?.publisher ?: return
        publisher.readCurrent { cache ->
            val cell = cellAt(event, cache)
            val column = unpackCellColumn(cell)
            val row = unpackCellRow(cell)

            val caretAbsRow = cache.discardedCount + cache.historySize - cache.scrollbackOffset + row
            val anchorAbsRow =
                selectionAnchorAbsoluteRow ?: (cache.discardedCount + cache.historySize - cache.scrollbackOffset + selectionAnchorRow)

            val caretColumn =
                if (
                    caretAbsRow < anchorAbsRow ||
                    (caretAbsRow == anchorAbsRow && column < selectionAnchorColumn)
                ) {
                    column
                } else {
                    column + 1
                }

            selectionAnchorAbsoluteRow = anchorAbsRow
            selectionCaretAbsoluteRow = caretAbsRow
            selectionCaretColumn = caretColumn
        }
        updateSelectionAutoscroll()
        repaint()
        event.consume()
    }

    private fun handleClipboardShortcut(event: KeyEvent): Boolean {
        val handled =
            when (settings.clipboardShortcuts.actionFor(event.keyCode, event.modifiersEx)) {
                TerminalClipboardAction.COPY -> copySelectionToClipboard()
                TerminalClipboardAction.PASTE -> pasteClipboardText()
                TerminalClipboardAction.NONE -> false
            }

        if (!handled) return false
        event.consume()
        return true
    }

    private fun copySelectionToClipboard(): Boolean {
        val publisher = session?.publisher ?: return false
        val selectedText =
            publisher.readCurrent { cache ->
                val currentSelection = getViewportSelection(cache) ?: return@readCurrent null
                selectionTextExtractor.selectedText(cache, currentSelection)
            } ?: return false
        if (selectedText.isEmpty()) return false
        settings.clipboardHandler.copyText(selectedText)
        return true
    }

    private fun pasteClipboardText(): Boolean {
        val text = settings.clipboardHandler.readText() ?: return false
        if (text.isEmpty()) return false
        session?.encodePaste(TerminalPasteEvent(text))
        return true
    }

    private fun clearSelection() {
        selectionAnchorAbsoluteRow = null
        selectionCaretAbsoluteRow = null
    }

    private fun getViewportSelection(cache: TerminalRenderCache?): CellSelection? {
        val anchorAbsRow = selectionAnchorAbsoluteRow ?: return null
        val caretAbsRow = selectionCaretAbsoluteRow ?: return null
        val c = cache ?: return null

        val isForward =
            caretAbsRow > anchorAbsRow ||
                (caretAbsRow == anchorAbsRow && selectionCaretColumn >= selectionAnchorColumn)

        val startAbsRow = if (isForward) anchorAbsRow else caretAbsRow
        val startCol = if (isForward) selectionAnchorColumn else selectionCaretColumn
        val endAbsRow = if (isForward) caretAbsRow else anchorAbsRow
        val endCol = if (isForward) selectionCaretColumn else selectionAnchorColumn

        val startViewportRow = (startAbsRow - c.discardedCount - (c.historySize - c.scrollbackOffset)).toInt()
        val endViewportRow = (endAbsRow - c.discardedCount - (c.historySize - c.scrollbackOffset)).toInt()

        if (endViewportRow < 0 || startViewportRow >= c.rows) {
            return null
        }

        val clampedStartRow = startViewportRow.coerceIn(0, c.rows - 1)
        val clampedStartCol = if (startViewportRow < 0) 0 else startCol.coerceIn(0, c.columns)

        val clampedEndRow = endViewportRow.coerceIn(0, c.rows - 1)
        val clampedEndCol = if (endViewportRow >= c.rows) c.columns else endCol.coerceIn(0, c.columns)

        return if (isForward) {
            CellSelection(
                anchorColumn = clampedStartCol,
                anchorRow = clampedStartRow,
                caretColumn = clampedEndCol,
                caretRow = clampedEndRow,
                isBlock = selectionIsBlock,
            )
        } else {
            CellSelection(
                anchorColumn = clampedEndCol,
                anchorRow = clampedEndRow,
                caretColumn = clampedStartCol,
                caretRow = clampedStartRow,
                isBlock = selectionIsBlock,
            )
        }
    }

    private fun cellAt(
        event: MouseEvent,
        cache: TerminalRenderCache,
    ): Long = cellAt(event.x, event.y, cache)

    private fun cellAt(
        x: Int,
        y: Int,
        cache: TerminalRenderCache,
    ): Long {
        val column = (x / metrics.cellWidth).coerceIn(0, cache.columns - 1)
        val row =
            ((y - contentYOffset(cache)) / metrics.cellHeight)
                .toInt()
                .coerceIn(0, cache.rows - 1)
        return packCell(column, row)
    }

    private fun handleSelectionMouseReleased(event: MouseEvent) {
        if (handleMouseTracking(event, TerminalMouseEventType.RELEASE)) return
        if (SwingUtilities.isLeftMouseButton(event)) {
            stopSelectionDrag()
            event.consume()
        }
    }

    private fun stopSelectionDrag() {
        selectingWithMouse = false
        selectionAutoscrollTimer.stop()
    }

    private fun handleHyperlinkActivation(event: MouseEvent): Boolean {
        if (!SwingUtilities.isLeftMouseButton(event) || !event.isControlDown) return false
        val uri = hyperlinkUriAt(event) ?: return false
        if (!settings.hyperlinkHandler.openHyperlink(uri)) return false
        event.consume()
        return true
    }

    private fun hyperlinkUriAt(event: MouseEvent): String? {
        val boundSession = session ?: return null
        return boundSession.publisher.readCurrent { cache ->
            val hyperlinkId = hyperlinkIdAt(event, cache)
            if (hyperlinkId == NO_HYPERLINK_ID) null else boundSession.hyperlinkUri(hyperlinkId)
        }
    }

    private fun resolvableHyperlinkIdAt(event: MouseEvent): Int {
        val boundSession = session ?: return NO_HYPERLINK_ID
        return boundSession.publisher.readCurrent { cache ->
            val hyperlinkId = hyperlinkIdAt(event, cache)
            if (hyperlinkId != NO_HYPERLINK_ID && boundSession.hyperlinkUri(hyperlinkId) != null) {
                hyperlinkId
            } else {
                NO_HYPERLINK_ID
            }
        } ?: NO_HYPERLINK_ID
    }

    private fun hyperlinkIdAt(
        event: MouseEvent,
        cache: TerminalRenderCache,
    ): Int {
        if (cache.columns <= 0 || cache.rows <= 0) return NO_HYPERLINK_ID
        val cell = cellAt(event, cache)
        val column = unpackCellColumn(cell)
        val row = unpackCellRow(cell)
        return cache.hyperlinkIds[cache.rowOffset(row) + column]
    }

    private fun updateHyperlinkActivationHover(active: Boolean) {
        if (hoveredHyperlinkId == NO_HYPERLINK_ID || hyperlinkActivationHover == active) return
        hyperlinkActivationHover = active
        repaint()
    }

    private fun clearHyperlinkHover() {
        updateHyperlinkHover(NO_HYPERLINK_ID, activationHover = false)
    }

    private fun updateHyperlinkHover(
        hyperlinkId: Int,
        activationHover: Boolean,
    ) {
        val normalizedActivationHover = hyperlinkId != NO_HYPERLINK_ID && activationHover
        val changed = hoveredHyperlinkId != hyperlinkId || hyperlinkActivationHover != normalizedActivationHover
        hoveredHyperlinkId = hyperlinkId
        hyperlinkActivationHover = normalizedActivationHover
        val nextCursor = if (hyperlinkId != NO_HYPERLINK_ID) HAND_CURSOR else DEFAULT_CURSOR
        if (cursor !== nextCursor) cursor = nextCursor
        if (changed) repaint()
    }

    private fun updateSelectionAutoscroll() {
        if (selectingWithMouse && selectionAutoscrollDelta(lastSelectionDragY) != 0.0) {
            if (!selectionAutoscrollTimer.isRunning) selectionAutoscrollTimer.start()
        } else {
            selectionAutoscrollTimer.stop()
        }
    }

    private fun selectionAutoscrollDelta(y: Int): Double =
        when {
            y < 0 -> {
                val distance = -y
                kotlin.math.floor(1.0 + (distance / 20.0))
            }
            y >= height -> {
                val distance = y - height
                -kotlin.math.floor(1.0 + (distance / 20.0))
            }
            else -> 0.0
        }

    private fun handleSelectionAutoscrollTick() {
        if (!selectingWithMouse) {
            selectionAutoscrollTimer.stop()
            return
        }

        val boundSession = session ?: return
        val publisher = boundSession.publisher
        publisher.readCurrent { cache ->
            val delta = selectionAutoscrollDelta(lastSelectionDragY)
            if (delta == 0.0) {
                selectionAutoscrollTimer.stop()
                return@readCurrent
            }

            val changed = scrollViewportBy(boundSession, delta, cache.historySize)
            if (changed) {
                val cell = cellAt(lastSelectionDragX, lastSelectionDragY, cache)
                val column = unpackCellColumn(cell)
                val row = unpackCellRow(cell)

                val caretAbsRow = cache.discardedCount + cache.historySize - cache.scrollbackOffset + row
                val anchorAbsRow =
                    selectionAnchorAbsoluteRow ?: (cache.discardedCount + cache.historySize - cache.scrollbackOffset + selectionAnchorRow)

                val caretColumn =
                    if (
                        caretAbsRow < anchorAbsRow ||
                        (caretAbsRow == anchorAbsRow && column < selectionAnchorColumn)
                    ) {
                        column
                    } else {
                        column + 1
                    }

                selectionAnchorAbsoluteRow = anchorAbsRow
                selectionCaretAbsoluteRow = caretAbsRow
                selectionCaretColumn = caretColumn
            }
        }
    }

    private fun scrollViewportBy(
        boundSession: TerminalSession,
        delta: Double,
        historySize: Int,
    ): Boolean {
        val previousRequestedOffset = scrollModel.requestedOffset
        val previousRequestedRows = requestedRenderRows()
        if (!scrollModel.scrollBy(delta, historySize)) return false

        val nextRequestedOffset = scrollModel.requestedOffset
        val nextRequestedRows = requestedRenderRows()
        if (nextRequestedOffset != previousRequestedOffset || nextRequestedRows != previousRequestedRows) {
            boundSession.requestRender(nextRequestedOffset, nextRequestedRows)
        } else {
            repaint()
        }
        return true
    }

    /**
     * Coalesces high-frequency render requests from the background IO thread.
     * * The [TerminalSession] may fire `onDirty` thousands of times per second
     * during heavy output. To prevent flooding the Swing EventQueue and causing
     * UI lockups, this method uses an atomic flag to ensure only one EDT layout
     * pass is ever queued at a time. The flag is cleared immediately before the
     * EDT executes the frame evaluation.
     */
    private fun schedulePublishedFrame() {
        if (!renderPending.compareAndSet(false, true)) return

        SwingUtilities.invokeLater {
            renderPending.set(false)
            handlePublishedFrame()
        }
    }

    private fun handlePublishedFrame() {
        val boundSession = session ?: return
        boundSession.publisher.readCurrent { cache ->
            resetCursorBlinkOnEdt(forceRepaint = false)
            when {
                scrollModel.clamp(cache.historySize) -> {
                    boundSession.requestRender(scrollModel.requestedOffset, requestedRenderRows())
                }

                cache.scrollbackOffset != scrollModel.requestedOffset -> {
                    boundSession.requestRender(scrollModel.requestedOffset, requestedRenderRows())
                }

                else -> {
                    val yOffset = contentYOffset(cache)
                    repaintPlanner.requestFrameRepaint(
                        cache = cache,
                        metrics = metrics,
                        componentWidth = width,
                        componentHeight = height,
                        contentYOffset = yOffset,
                        repaintSink = repaintSink,
                    )
                }
            }
        } ?: repaint()
    }

    private fun repaintBlinkingCursor() {
        val publisher = session?.publisher ?: return
        publisher.readCurrent { cache ->
            repaintPlanner.requestCursorBlinkRepaint(
                cache = cache,
                metrics = metrics,
                componentWidth = width,
                componentHeight = height,
                contentYOffset = contentYOffset(cache),
                repaintSink = repaintSink,
            )
        }
    }

    private fun resetCursorBlinkOnEdt(forceRepaint: Boolean) {
        if (cursorTimer.isRunning) {
            val wasVisible = cursorBlinkVisible
            cursorBlinkVisible = true
            cursorTimer.restart()
            if (forceRepaint && !wasVisible) {
                repaintBlinkingCursor()
            }
        }
    }

    private fun resetScrollbackState() {
        scrollModel.reset()
    }

    private fun preferredGridSize(
        columns: Int,
        rows: Int,
    ): Dimension = Dimension(columns * metrics.cellWidth, rows * metrics.cellHeight)

    private fun resizeSessionToVisibleGridOnEdt() {
        val packedGridSize = updateVisibleGridSizeOnEdt()
        val boundSession = session ?: return
        if (width <= 0 || height <= 0) return

        val columns = unpackVisibleColumns(packedGridSize)
        val rows = unpackVisibleRows(packedGridSize)
        if (columns == lastResizedColumns && rows == lastResizedRows) return

        lastResizedColumns = columns
        lastResizedRows = rows
        boundSession.resize(columns, rows)
    }

    private fun updateVisibleGridSizeOnEdt(): Long {
        val columns = maxOf(1, width / metrics.cellWidth)
        val rows = maxOf(1, height / metrics.cellHeight)
        val packed = packVisibleGridSize(columns, rows)
        visibleGridSizeSnapshot.set(packed)
        return packed
    }

    private fun wheelScrollLines(event: MouseWheelEvent): Double =
        when (event.scrollType) {
            MouseWheelEvent.WHEEL_UNIT_SCROLL -> -event.preciseWheelRotation * event.scrollAmount
            MouseWheelEvent.WHEEL_BLOCK_SCROLL -> -event.preciseWheelRotation * visibleGridRows()
            else -> -event.preciseWheelRotation
        }

    private fun visibleGridRows(): Int = maxOf(1, height / metrics.cellHeight)

    private fun requestedRenderRows(): Int = scrollModel.requestedRows(visibleGridRows())

    private fun contentYOffset(cache: TerminalRenderCache): Double {
        if (cache.rows < requestedRenderRows()) return 0.0
        return scrollModel.contentYOffset(metrics.cellHeight)
    }

    private fun buildMetrics(settings: TerminalSwingSettings): TerminalSwingMetrics {
        val metricsSource: FontMetrics = getFontMetrics(settings.font)
        return TerminalSwingMetrics.from(metricsSource)
    }

    private fun runOnEdt(action: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) {
            action()
        } else {
            SwingUtilities.invokeLater(action)
        }
    }

    private companion object {
        private const val NO_RESIZE_DIMENSION = -1
        private const val NO_HYPERLINK_ID = 0
        private val HAND_CURSOR: Cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        private val DEFAULT_CURSOR: Cursor = Cursor.getDefaultCursor()

        private fun packVisibleGridSize(
            columns: Int,
            rows: Int,
        ): Long = (columns.toLong() shl 32) or (rows.toLong() and 0xffff_ffffL)

        private fun unpackVisibleColumns(packed: Long): Int = (packed ushr 32).toInt()

        private fun unpackVisibleRows(packed: Long): Int = packed.toInt()

        private fun packCell(
            column: Int,
            row: Int,
        ): Long = (column.toLong() shl 32) or (row.toLong() and 0xffff_ffffL)

        private fun unpackCellColumn(packed: Long): Int = (packed ushr 32).toInt()

        private fun unpackCellRow(packed: Long): Int = packed.toInt()
    }
}
