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
import com.gagik.terminal.ui.swing.search.TerminalSearchHighlights
import com.gagik.terminal.ui.swing.search.TerminalSearchModel
import com.gagik.terminal.ui.swing.search.TerminalSearchState
import com.gagik.terminal.ui.swing.search.TerminalSearchViewportHighlights
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Reusable Swing terminal component.
 *
 * The component consumes published render-cache snapshots from a
 * [TerminalSession] and paints terminal rows without knowing which transport
 * produced the bytes. Hosts own session creation, process lifecycle, and
 * connector choice outside this component.
 *
 * @param settingsProvider provider for immutable settings snapshots.
 * @param hostServices host-provided non-render services.
 */
class TerminalSwingTerminal(
    private val settingsProvider: TerminalSwingSettingsProvider =
        TerminalSwingSettingsProvider { TerminalSwingSettings() },
    private val hostServices: TerminalSwingHostServices = TerminalSwingHostServices(),
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
    private var searchQuery: String = ""
    private var searchHighlights: TerminalSearchHighlights? = null
    private var searchIgnoreCase: Boolean = true
    private val selectionAutoscrollTimer =
        Timer(50) {
            handleSelectionAutoscrollTick()
        }
    private val renderPending = AtomicBoolean(false)
    private val visibleGridSizeSnapshot = AtomicLong(packVisibleGridSize(1, 1))
    private val viewportHistorySizeSnapshot = AtomicInteger(0)
    private val viewportScrollbackOffsetSnapshot = AtomicLong(doubleToRawLongBits(0.0))
    private val viewportRenderOffsetSnapshot = AtomicInteger(0)
    private val viewportVisibleRowsSnapshot = AtomicInteger(1)
    private val viewportRequestedRowsSnapshot = AtomicInteger(1)
    private val publishedFrameRunnable =
        Runnable {
            renderPending.set(false)
            handlePublishedFrame()
        }
    private val unbindRunnable = Runnable { unbindOnEdt() }
    private val reloadSettingsRunnable = Runnable { reloadSettingsOnEdt() }

    private val painter = TerminalGridPainter()
    private val repaintPlanner = TerminalSwingRepaintPlanner()
    private val scrollModel = TerminalSwingScrollModel()
    private val renderCache = TerminalRenderCache(settings.columns, settings.rows)
    private val searchCache = TerminalRenderCache(settings.columns, settings.rows)
    private val keyMapper = TerminalSwingKeyMapper()
    private val selectionTextExtractor = TerminalSelectionTextExtractor()
    private val searchModel = TerminalSearchModel()
    private val searchViewportHighlights = TerminalSearchViewportHighlights()
    private val searchOverlay = SearchOverlay()
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
    private val dirtyListener = { schedulePublishedFrame() }

    internal val cursorTimer =
        Timer(settings.cursorBlinkMillis) {
            cursorBlinkVisible = !cursorBlinkVisible
            repaintBlinkState()
        }

    private val inputKeyListener =
        object : KeyAdapter() {
            override fun keyPressed(event: KeyEvent) {
                updateHyperlinkActivationHover(event.isControlDown)
                resetCursorBlinkOnEdt(forceRepaint = true)
                if (handleSearchShortcut(event)) return
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
        add(searchOverlay)
        searchOverlay.isVisible = false
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
        runOnEdt(
            Runnable {
                bindOnEdt(session)
            },
        )
    }

    /**
     * Removes the current session binding.
     *
     * This method may be called from any thread; component state is updated
     * asynchronously on the EDT.
     */
    fun unbind() {
        runOnEdt(unbindRunnable)
    }

    /**
     * Rebuilds settings, metrics, preferred size, and repaint state.
     *
     * This method may be called from any thread; component state is updated
     * asynchronously on the EDT.
     */
    fun reloadSettings() {
        runOnEdt(reloadSettingsRunnable)
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

    /**
     * Returns the latest terminal-native scrollback viewport snapshot.
     *
     * This method may be called from any thread. EDT callers refresh the
     * snapshot from live component and render-cache state before reading it;
     * off-EDT callers read the last EDT-published snapshot without blocking.
     *
     * @return current scrollback viewport state.
     */
    fun viewportState(): TerminalViewportState {
        if (SwingUtilities.isEventDispatchThread()) {
            publishViewportState(renderCache.historySize, notifyListener = false)
        }
        return TerminalViewportState(
            historySize = viewportHistorySizeSnapshot.get(),
            scrollbackOffset = longBitsToDouble(viewportScrollbackOffsetSnapshot.get()),
            renderOffset = viewportRenderOffsetSnapshot.get(),
            visibleRows = viewportVisibleRowsSnapshot.get(),
            requestedRows = viewportRequestedRowsSnapshot.get(),
        )
    }

    /**
     * Returns to the live terminal viewport.
     *
     * This method may be called from any thread; component state is updated
     * asynchronously on the EDT.
     */
    fun scrollToLiveViewport() {
        scrollToScrollbackOffset(0)
    }

    /**
     * Scrolls to an absolute scrollback offset.
     *
     * The offset uses terminal-native coordinates: `0` is live output and
     * larger values move farther back into scrollback history. Values beyond
     * available history are clamped on the EDT.
     *
     * @param scrollbackOffset requested whole-row offset from live output.
     */
    fun scrollToScrollbackOffset(scrollbackOffset: Int) {
        runOnEdt(
            Runnable {
                scrollViewportToOnEdt(scrollbackOffset.toDouble())
            },
        )
    }

    /**
     * Applies a signed scrollback delta in terminal rows.
     *
     * Positive values move farther back into scrollback; negative values move
     * toward the live viewport. Fractional values are preserved for smooth
     * wheel and trackpad composition.
     *
     * @param deltaLines signed row delta.
     */
    fun scrollViewportBy(deltaLines: Double) {
        require(!deltaLines.isNaN()) { "deltaLines must not be NaN" }
        runOnEdt(
            Runnable {
                scrollViewportByOnEdt(deltaLines)
            },
        )
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

    override fun doLayout() {
        super.doLayout()
        val preferred = searchOverlay.preferredSize
        val availableWidth = width - settings.padding.left - settings.padding.right
        val overlayWidth = minOf(availableWidth, preferred.width).coerceAtLeast(0)
        if (overlayWidth == 0) {
            searchOverlay.setBounds(0, 0, 0, 0)
            return
        }
        val x = maxOf(settings.padding.left, width - settings.padding.right - overlayWidth)
        searchOverlay.setBounds(x, settings.padding.top, overlayWidth, preferred.height)
    }

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)

        val g = graphics.create() as Graphics2D
        try {
            if (session == null) {
                painter.clear(g, settings.palette, width, height)
                return
            }

            painter.paint(
                g = g,
                cache = renderCache,
                settings = settings,
                metrics = metrics,
                width = width,
                height = height,
                cursorBlinkVisible = cursorBlinkVisible,
                textBlinkVisible = cursorBlinkVisible,
                contentYOffset = contentYOffset(renderCache),
                selection = getViewportSelection(renderCache),
                searchHighlights = searchViewportHighlights,
                hoveredHyperlinkId = hoveredHyperlinkId,
                hyperlinkActivationHover = hyperlinkActivationHover,
            )
        } finally {
            g.dispose()
        }
    }

    private fun bindOnEdt(session: TerminalSession) {
        this.session?.removeDirtyListener(dirtyListener)
        this.session = session
        applySettingsToSession(session, settings)
        session.addDirtyListener(dirtyListener)
        resetScrollbackState()
        clearSelection()
        clearSearch()
        stopSelectionDrag()
        lastResizedColumns = NO_RESIZE_DIMENSION
        lastResizedRows = NO_RESIZE_DIMENSION
        repaintPlanner.reset()
        renderPending.set(false)
        clearHyperlinkHover()
        resizeSessionToVisibleGridOnEdt()
        refreshRenderCacheFromSession(session)
        publishViewportState(renderCache.historySize)
        repaint()
    }

    private fun unbindOnEdt() {
        session?.removeDirtyListener(dirtyListener)
        session = null
        resetScrollbackState()
        clearSelection()
        clearSearch()
        stopSelectionDrag()
        lastResizedColumns = NO_RESIZE_DIMENSION
        lastResizedRows = NO_RESIZE_DIMENSION
        repaintPlanner.reset()
        renderPending.set(false)
        clearHyperlinkHover()
        publishViewportState(0)
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
        updateSearchViewportHighlights()
        clearHyperlinkHover()
        resizeSessionToVisibleGridOnEdt()
        session?.let { refreshRenderCacheFromSession(it) }
        publishViewportState(renderCache.historySize)
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
        if (session == null) return null
        return getViewportSelection(renderCache)
    }

    /**
     * Opens the overlay search bar and focuses its query field.
     *
     * This method may be called from any thread; component state is updated on
     * the EDT.
     */
    fun openSearch() {
        runOnEdt(
            Runnable {
                openSearchOnEdt()
            },
        )
    }

    /**
     * Closes the overlay search bar and clears visible search highlights.
     *
     * This method may be called from any thread; component state is updated on
     * the EDT.
     */
    fun closeSearch() {
        runOnEdt(
            Runnable {
                closeSearchOnEdt()
            },
        )
    }

    /**
     * Applies a literal terminal-buffer search query.
     *
     * The search covers retained scrollback plus the live grid snapshot exposed
     * through the bound session's render-frame reader.
     *
     * @param query literal text to find.
     */
    fun search(query: String) {
        runOnEdt(
            Runnable {
                searchOverlay.setQueryText(query)
                applySearchQueryOnEdt(query)
            },
        )
    }

    /**
     * Activates the next search result, wrapping at the end.
     *
     * @return `true` when a result was activated.
     */
    fun findNext(): Boolean {
        if (SwingUtilities.isEventDispatchThread()) return findNextOnEdt()
        runOnEdt(
            Runnable {
                findNextOnEdt()
            },
        )
        return true
    }

    /**
     * Activates the previous search result, wrapping at the beginning.
     *
     * @return `true` when a result was activated.
     */
    fun findPrevious(): Boolean {
        if (SwingUtilities.isEventDispatchThread()) return findPreviousOnEdt()
        runOnEdt(
            Runnable {
                findPreviousOnEdt()
            },
        )
        return true
    }

    /**
     * Returns the current search UI and result snapshot.
     */
    fun currentSearchState(): TerminalSearchState =
        TerminalSearchState(
            visible = searchOverlay.isVisible,
            query = searchQuery,
            resultCount = searchHighlights?.resultCount ?: 0,
            activeResultIndex = searchHighlights?.activeResultIndex ?: NO_ACTIVE_SEARCH_RESULT,
        )

    private fun applySettingsToSession(
        session: TerminalSession,
        settings: TerminalSwingSettings,
    ) {
        session.setTreatAmbiguousAsWide(settings.treatAmbiguousAsWide)
        session.setThemePalette(settings.palette)
    }

    private fun handleMouseWheel(event: MouseWheelEvent) {
        if (handleMouseTracking(event, TerminalMouseEventType.WHEEL)) return
        val historySize = renderCache.historySize
        if (historySize == 0) return

        val delta = wheelScrollLines(event)
        if (delta == 0.0) return

        scrollViewportByOnEdt(delta, historySize)
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

        val cell = cellAt(event, renderCache)
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

        if (session == null) return
        val cache = renderCache
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

        if (session == null) return
        val cache = renderCache
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

    private fun handleSearchShortcut(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.VK_F) return false
        if (!event.isControlDown && !event.isMetaDown) return false
        openSearchOnEdt()
        event.consume()
        return true
    }

    fun copySelectionToClipboard(): Boolean {
        if (session == null) return false
        val selectedText =
            getViewportSelection(renderCache)?.let { currentSelection ->
                selectionTextExtractor.selectedText(renderCache, currentSelection)
            } ?: return false
        if (selectedText.isEmpty()) return false
        hostServices.clipboardHandler.copyText(selectedText)
        return true
    }

    fun pasteClipboardText(): Boolean {
        val text = hostServices.clipboardHandler.readText() ?: return false
        if (text.isEmpty()) return false
        session?.encodePaste(TerminalPasteEvent(text))
        return true
    }

    private fun clearSelection() {
        selectionAnchorAbsoluteRow = null
        selectionCaretAbsoluteRow = null
    }

    private fun clearSearch() {
        searchQuery = ""
        searchHighlights = null
        searchViewportHighlights.reset(renderCache.rows)
        searchOverlay.setQueryText("")
        searchOverlay.updateResultCounter(0, NO_ACTIVE_SEARCH_RESULT)
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
        val padding = settings.padding
        val column = ((x - padding.left) / metrics.cellWidth).coerceIn(0, cache.columns - 1)
        val row =
            ((y - padding.top - contentYOffset(cache)) / metrics.cellHeight)
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
        if (!hostServices.hyperlinkHandler.openHyperlink(uri)) return false
        event.consume()
        return true
    }

    private fun hyperlinkUriAt(event: MouseEvent): String? {
        val boundSession = session ?: return null
        val hyperlinkId = hyperlinkIdAt(event, renderCache)
        return if (hyperlinkId == NO_HYPERLINK_ID) null else boundSession.hyperlinkUri(hyperlinkId)
    }

    private fun resolvableHyperlinkIdAt(event: MouseEvent): Int {
        val boundSession = session ?: return NO_HYPERLINK_ID
        val hyperlinkId = hyperlinkIdAt(event, renderCache)
        return if (hyperlinkId != NO_HYPERLINK_ID && boundSession.hyperlinkUri(hyperlinkId) != null) {
            hyperlinkId
        } else {
            NO_HYPERLINK_ID
        }
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

    private fun selectionAutoscrollDelta(y: Int): Double {
        val padding = settings.padding
        return when {
            y < padding.top -> {
                val distance = padding.top - y
                kotlin.math.floor(1.0 + (distance / 20.0))
            }
            y >= height - padding.bottom -> {
                val distance = y - (height - padding.bottom)
                -kotlin.math.floor(1.0 + (distance / 20.0))
            }
            else -> 0.0
        }
    }

    private fun handleSelectionAutoscrollTick() {
        if (!selectingWithMouse) {
            selectionAutoscrollTimer.stop()
            return
        }

        val boundSession = session ?: return
        val cache = renderCache
        val delta = selectionAutoscrollDelta(lastSelectionDragY)
        if (delta == 0.0) {
            selectionAutoscrollTimer.stop()
            return
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

    private fun scrollViewportBy(
        boundSession: TerminalSession,
        delta: Double,
        historySize: Int,
    ): Boolean = scrollViewportByOnEdt(delta, historySize, boundSession)

    private fun scrollViewportByOnEdt(
        delta: Double,
        historySize: Int = renderCache.historySize,
        boundSession: TerminalSession? = session,
    ): Boolean {
        if (!scrollModel.scrollBy(delta, historySize)) return false
        if (boundSession != null) {
            refreshRenderCacheFromSession(boundSession)
        }
        updateSearchViewportHighlights()
        publishViewportState(renderCache.historySize)
        repaint()
        return true
    }

    private fun scrollViewportToOnEdt(
        offsetLines: Double,
        historySize: Int = renderCache.historySize,
        boundSession: TerminalSession? = session,
    ): Boolean {
        if (!scrollModel.scrollTo(offsetLines, historySize)) return false
        if (boundSession != null) {
            refreshRenderCacheFromSession(boundSession)
        }
        updateSearchViewportHighlights()
        publishViewportState(renderCache.historySize)
        repaint()
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

        hostServices.uiDispatcher.dispatch(publishedFrameRunnable)
    }

    private fun handlePublishedFrame() {
        val boundSession = session ?: return
        resetCursorBlinkOnEdt(forceRepaint = false)
        refreshRenderCacheFromSession(boundSession)
        if (scrollModel.clamp(renderCache.historySize) || renderCache.scrollbackOffset != scrollModel.requestedOffset) {
            refreshRenderCacheFromSession(boundSession)
        }
        refreshSearchForFrameOnEdt()
        publishViewportState(renderCache.historySize)
        val yOffset = contentYOffset(renderCache)
        repaintPlanner.requestFrameRepaint(
            cache = renderCache,
            metrics = metrics,
            componentWidth = width,
            componentHeight = height,
            contentYOffset = yOffset,
            padding = settings.padding,
            repaintSink = repaintSink,
        )
    }

    private fun repaintBlinkState() {
        if (session == null) return
        val yOffset = contentYOffset(renderCache)
        repaintPlanner.requestCursorBlinkRepaint(
            cache = renderCache,
            metrics = metrics,
            componentWidth = width,
            componentHeight = height,
            contentYOffset = yOffset,
            padding = settings.padding,
            repaintSink = repaintSink,
        )
        repaintPlanner.requestBlinkingTextRepaint(
            cache = renderCache,
            metrics = metrics,
            componentWidth = width,
            componentHeight = height,
            contentYOffset = yOffset,
            padding = settings.padding,
            repaintSink = repaintSink,
        )
    }

    private fun resetCursorBlinkOnEdt(forceRepaint: Boolean) {
        if (cursorTimer.isRunning) {
            val wasVisible = cursorBlinkVisible
            cursorBlinkVisible = true
            cursorTimer.restart()
            if (forceRepaint && !wasVisible) {
                repaintBlinkState()
            }
        }
    }

    private fun resetScrollbackState() {
        scrollModel.reset()
    }

    private fun openSearchOnEdt() {
        searchOverlay.isVisible = true
        searchOverlay.setQueryText(searchQuery)
        revalidate()
        searchOverlay.focusQuery()
        if (searchQuery.isNotEmpty()) {
            refreshSearchForFrameOnEdt()
        }
        repaint()
    }

    private fun closeSearchOnEdt() {
        searchOverlay.isVisible = false
        searchHighlights = null
        searchViewportHighlights.reset(renderCache.rows)
        revalidate()
        requestFocusInWindow()
        repaint()
    }

    private fun applySearchQueryOnEdt(query: String) {
        searchQuery = query
        if (query.isEmpty()) {
            searchHighlights = null
            searchViewportHighlights.reset(renderCache.rows)
            searchOverlay.updateResultCounter(0, NO_ACTIVE_SEARCH_RESULT)
            repaint()
            return
        }

        val boundSession = session ?: return
        refreshSearchCache(boundSession)
        searchHighlights = searchModel.search(searchCache, query, ignoreCase = searchIgnoreCase)
        searchOverlay.updateResultCounter(
            resultCount = searchHighlights?.resultCount ?: 0,
            activeResultIndex = searchHighlights?.activeResultIndex ?: NO_ACTIVE_SEARCH_RESULT,
        )
        scrollToActiveSearchResult()
        updateSearchViewportHighlights()
        repaint()
    }

    private fun refreshSearchForFrameOnEdt() {
        if (searchQuery.isEmpty()) {
            updateSearchViewportHighlights()
            return
        }
        val boundSession = session ?: return
        val oldActive = searchHighlights?.activeResultIndex ?: NO_ACTIVE_SEARCH_RESULT
        refreshSearchCache(boundSession)
        val nextHighlights = searchModel.search(searchCache, searchQuery, ignoreCase = searchIgnoreCase)
        if (oldActive in 0 until nextHighlights.resultCount) {
            nextHighlights.activate(oldActive)
        }
        searchHighlights = nextHighlights
        searchOverlay.updateResultCounter(nextHighlights.resultCount, nextHighlights.activeResultIndex)
        updateSearchViewportHighlights()
    }

    private fun refreshSearchCache(boundSession: TerminalSession) {
        val historySize = renderCache.historySize
        searchCache.updateFrom(
            reader = boundSession,
            scrollbackOffset = historySize,
            viewportRows = (historySize + visibleGridRows()).coerceAtLeast(1),
        )
    }

    private fun findNextOnEdt(): Boolean = activateRelativeSearchResult(1)

    private fun findPreviousOnEdt(): Boolean = activateRelativeSearchResult(-1)

    private fun activateRelativeSearchResult(delta: Int): Boolean {
        val highlights = searchHighlights ?: return false
        if (highlights.resultCount == 0) return false
        val current =
            if (highlights.activeResultIndex in 0 until highlights.resultCount) {
                highlights.activeResultIndex
            } else {
                0
            }
        val next = (current + delta + highlights.resultCount) % highlights.resultCount
        highlights.activate(next)
        searchOverlay.updateResultCounter(highlights.resultCount, highlights.activeResultIndex)
        scrollToActiveSearchResult()
        updateSearchViewportHighlights()
        repaint()
        return true
    }

    private fun scrollToActiveSearchResult() {
        val highlights = searchHighlights ?: return
        val activeRow = highlights.activeStartAbsoluteRow()
        if (activeRow == NO_ACTIVE_SEARCH_ROW) return
        val centerRow = visibleGridRows() / 2
        val desiredOffset = renderCache.discardedCount + renderCache.historySize + centerRow - activeRow
        scrollViewportToOnEdt(desiredOffset.toDouble())
    }

    private fun updateSearchViewportHighlights() {
        val highlights = searchHighlights
        if (highlights == null) {
            searchViewportHighlights.reset(renderCache.rows)
            return
        }
        highlights.buildViewportHighlights(renderCache, searchViewportHighlights)
    }

    private fun publishViewportState(
        historySize: Int,
        notifyListener: Boolean = true,
    ) {
        val visibleRows = visibleGridRows()
        val requestedRows = scrollModel.requestedRows(visibleRows)
        val scrollbackOffset = scrollModel.preciseScrollbackOffset
        val renderOffset = scrollModel.requestedOffset

        viewportHistorySizeSnapshot.set(historySize)
        viewportScrollbackOffsetSnapshot.set(doubleToRawLongBits(scrollbackOffset))
        viewportRenderOffsetSnapshot.set(renderOffset)
        viewportVisibleRowsSnapshot.set(visibleRows)
        viewportRequestedRowsSnapshot.set(requestedRows)
        if (!notifyListener) return

        hostServices.viewportListener.viewportChanged(
            historySize = historySize,
            scrollbackOffset = scrollbackOffset,
            renderOffset = renderOffset,
            visibleRows = visibleRows,
            requestedRows = requestedRows,
        )
    }

    private fun preferredGridSize(
        columns: Int,
        rows: Int,
    ): Dimension {
        val padding = settings.padding
        return Dimension(
            columns * metrics.cellWidth + padding.left + padding.right,
            rows * metrics.cellHeight + padding.top + padding.bottom,
        )
    }

    private fun resizeSessionToVisibleGridOnEdt() {
        val packedGridSize = updateVisibleGridSizeOnEdt()
        publishViewportState(renderCache.historySize)
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
        val padding = settings.padding
        val columns = maxOf(1, (width - padding.left - padding.right) / metrics.cellWidth)
        val rows = maxOf(1, (height - padding.top - padding.bottom) / metrics.cellHeight)
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

    private fun visibleGridRows(): Int {
        val padding = settings.padding
        return maxOf(1, (height - padding.top - padding.bottom) / metrics.cellHeight)
    }

    private fun requestedRenderRows(): Int = scrollModel.requestedRows(visibleGridRows())

    private fun refreshRenderCacheFromSession(session: TerminalSession) {
        renderCache.updateFrom(
            reader = session,
            scrollbackOffset = scrollModel.requestedOffset,
            viewportRows = requestedRenderRows(),
        )
    }

    private fun contentYOffset(cache: TerminalRenderCache): Double {
        if (cache.rows < requestedRenderRows()) return 0.0
        return scrollModel.contentYOffset(metrics.cellHeight)
    }

    private fun buildMetrics(settings: TerminalSwingSettings): TerminalSwingMetrics {
        val metricsSource: FontMetrics = getFontMetrics(settings.font)
        return TerminalSwingMetrics.from(metricsSource)
    }

    private fun runOnEdt(action: Runnable) {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run()
        } else {
            hostServices.uiDispatcher.dispatch(action)
        }
    }

    private inner class SearchOverlay : JPanel(GridBagLayout()) {
        private var suppressDocumentEvents = false
        private val queryField = JTextField(24)
        private val counterLabel = JLabel("0/0")
        private val previousButton = JButton("Prev")
        private val nextButton = JButton("Next")
        private val caseToggle = JCheckBox("Aa")
        private val closeButton = JButton("x")

        init {
            isOpaque = true
            background = Color(0xEE202124.toInt(), true)
            foreground = Color(0xFFE8EAED.toInt(), true)
            border =
                BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(Color(0x663C4043, true)),
                    BorderFactory.createEmptyBorder(6, 8, 6, 8),
                )

            queryField.columns = 22
            queryField.border = BorderFactory.createEmptyBorder(4, 6, 4, 6)
            counterLabel.foreground = foreground
            previousButton.margin = Insets(2, 8, 2, 8)
            nextButton.margin = Insets(2, 8, 2, 8)
            closeButton.margin = Insets(2, 8, 2, 8)
            caseToggle.isOpaque = false
            caseToggle.foreground = foreground

            queryField.document.addDocumentListener(
                object : DocumentListener {
                    override fun insertUpdate(event: DocumentEvent) = queryChanged()

                    override fun removeUpdate(event: DocumentEvent) = queryChanged()

                    override fun changedUpdate(event: DocumentEvent) = queryChanged()
                },
            )

            queryField.registerKeyboardAction(
                { findNextOnEdt() },
                KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0),
                WHEN_FOCUSED,
            )
            queryField.registerKeyboardAction(
                { findPreviousOnEdt() },
                KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK),
                WHEN_FOCUSED,
            )
            queryField.registerKeyboardAction(
                { closeSearchOnEdt() },
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                WHEN_FOCUSED,
            )

            previousButton.addActionListener { findPreviousOnEdt() }
            nextButton.addActionListener { findNextOnEdt() }
            closeButton.addActionListener { closeSearchOnEdt() }
            caseToggle.addActionListener {
                searchIgnoreCase = !caseToggle.isSelected
                applySearchQueryOnEdt(queryField.text)
            }

            var gridX = 0
            add(queryField, constraints(gridX++, weightX = 1.0, fill = GridBagConstraints.HORIZONTAL))
            add(counterLabel, constraints(gridX++))
            add(previousButton, constraints(gridX++))
            add(nextButton, constraints(gridX++))
            add(caseToggle, constraints(gridX++))
            add(closeButton, constraints(gridX))
        }

        fun setQueryText(query: String) {
            if (queryField.text == query) return
            suppressDocumentEvents = true
            try {
                queryField.text = query
            } finally {
                suppressDocumentEvents = false
            }
        }

        fun updateResultCounter(
            resultCount: Int,
            activeResultIndex: Int,
        ) {
            counterLabel.text =
                if (resultCount == 0) {
                    "0/0"
                } else {
                    "${activeResultIndex + 1}/$resultCount"
                }
        }

        fun focusQuery() {
            queryField.requestFocusInWindow()
            queryField.selectAll()
        }

        private fun queryChanged() {
            if (suppressDocumentEvents) return
            applySearchQueryOnEdt(queryField.text)
        }

        private fun constraints(
            gridX: Int,
            weightX: Double = 0.0,
            fill: Int = GridBagConstraints.NONE,
        ): GridBagConstraints =
            GridBagConstraints().apply {
                this.gridx = gridX
                this.gridy = 0
                this.weightx = weightX
                this.fill = fill
                this.insets = Insets(0, if (gridX == 0) 0 else 6, 0, 0)
                this.anchor = GridBagConstraints.CENTER
            }
    }

    private companion object {
        private const val NO_RESIZE_DIMENSION = -1
        private const val NO_HYPERLINK_ID = 0
        private const val NO_ACTIVE_SEARCH_RESULT = -1
        private const val NO_ACTIVE_SEARCH_ROW = Long.MIN_VALUE
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

        private fun doubleToRawLongBits(value: Double): Long = java.lang.Double.doubleToRawLongBits(value)

        private fun longBitsToDouble(value: Long): Double = java.lang.Double.longBitsToDouble(value)
    }
}
