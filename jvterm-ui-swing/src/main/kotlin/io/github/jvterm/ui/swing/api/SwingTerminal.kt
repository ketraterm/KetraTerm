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
package io.github.jvterm.ui.swing.api

import io.github.jvterm.input.event.*
import io.github.jvterm.protocol.MouseTrackingMode
import io.github.jvterm.render.cache.TerminalRenderCache
import io.github.jvterm.session.TerminalSession
import io.github.jvterm.ui.swing.input.SwingKeyMapper
import io.github.jvterm.ui.swing.render.GridPainter
import io.github.jvterm.ui.swing.search.*
import io.github.jvterm.ui.swing.settings.SwingMetrics
import io.github.jvterm.ui.swing.settings.SwingSettings
import io.github.jvterm.ui.swing.settings.SwingSettingsProvider
import io.github.jvterm.ui.swing.settings.TerminalClipboardAction
import io.github.jvterm.ui.swing.viewport.SwingRepaintPlanner
import io.github.jvterm.ui.swing.viewport.SwingScrollModel
import io.github.jvterm.ui.swing.viewport.TerminalRepaintSink
import java.awt.*
import java.awt.event.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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
 * @param hostServices host-provided non-render services.
 */
class SwingTerminal
    @JvmOverloads
    constructor(
        private val settingsProvider: SwingSettingsProvider =
            SwingSettingsProvider { SwingSettings() },
        private val hostServices: SwingHostServices = SwingHostServices(),
    ) : JComponent() {
        private var session: TerminalSession? = null
        private var settings: SwingSettings = settingsProvider.currentSettings()
        private var metrics: SwingMetrics = buildMetrics(settings)
        private var terminalFocused: Boolean = false
        internal var cursorBlinkVisible: Boolean = true
        internal val cursorPresentationEnabled: Boolean
            get() = terminalFocused
        private var lastResizedColumns: Int = NO_RESIZE_DIMENSION
        private var lastResizedRows: Int = NO_RESIZE_DIMENSION
        private var searchQuery: String = ""
        private var searchHighlights: TerminalSearchHighlights? = null
        private var searchIgnoreCase: Boolean = true
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

        private val painter = GridPainter()
        private val repaintPlanner = SwingRepaintPlanner()
        private val scrollModel = SwingScrollModel()
        private val renderCache = TerminalRenderCache(settings.columns, settings.rows)
        private val searchCache = TerminalRenderCache(settings.columns, settings.rows)
        private val keyMapper = SwingKeyMapper()
        private val searchModel = TerminalSearchModel()
        private val searchViewportHighlights = TerminalSearchViewportHighlights()

        private val selectionController =
            TerminalSelectionController(
                object : TerminalSelectionHost {
                    override val settings: SwingSettings get() = this@SwingTerminal.settings
                    override val metrics: SwingMetrics get() = this@SwingTerminal.metrics
                    override val renderCache: TerminalRenderCache get() = this@SwingTerminal.renderCache
                    override val contentYOffset: Double get() =
                        this@SwingTerminal.contentYOffset(
                            this@SwingTerminal.renderCache,
                        )
                    override val componentWidth: Int get() = this@SwingTerminal.width
                    override val componentHeight: Int get() = this@SwingTerminal.height

                    override fun cellAt(
                        x: Int,
                        y: Int,
                    ): Long = this@SwingTerminal.cellAt(x, y, this@SwingTerminal.renderCache)

                    override fun scrollViewportBy(
                        delta: Double,
                        historySize: Int,
                    ): Boolean = this@SwingTerminal.scrollViewportByOnEdt(delta, historySize)

                    override fun repaint() = this@SwingTerminal.repaint()

                    override fun requestFocusInWindow(): Boolean = this@SwingTerminal.requestFocusInWindow()
                },
            )

        private val hyperlinkController =
            TerminalHyperlinkController(
                object : TerminalHyperlinkHost {
                    override val renderCache: TerminalRenderCache get() = this@SwingTerminal.renderCache
                    override val session: TerminalSession? get() = this@SwingTerminal.session
                    override val hostServices: SwingHostServices get() = this@SwingTerminal.hostServices
                    override var cursor: Cursor
                        get() = this@SwingTerminal.cursor
                        set(value) {
                            this@SwingTerminal.cursor = value
                        }

                    override fun cellAt(
                        x: Int,
                        y: Int,
                    ): Long = this@SwingTerminal.cellAt(x, y, this@SwingTerminal.renderCache)

                    override fun repaint() = this@SwingTerminal.repaint()
                },
            )
        private val searchOverlay =
            TerminalSearchOverlay(
                object : SearchOverlayListener {
                    override fun onQueryChanged(query: String) {
                        applySearchQueryOnEdt(query)
                    }

                    override fun onFindNext() {
                        findNextOnEdt()
                    }

                    override fun onFindPrevious() {
                        findPreviousOnEdt()
                    }

                    override fun onCloseSearch() {
                        closeSearchOnEdt()
                    }

                    override fun onCaseSensitivityChanged(ignoreCase: Boolean) {
                        searchIgnoreCase = ignoreCase
                    }
                },
            )
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
            Timer(cursorTimerDelay(settings)) {
                cursorBlinkVisible = !cursorBlinkVisible
                repaintBlinkState()
            }

        private val inputKeyListener =
            object : KeyAdapter() {
                override fun keyPressed(event: KeyEvent) {
                    hyperlinkController.updateHyperlinkActivationHover(event.isControlDown)
                    resetCursorBlinkOnEdt(forceRepaint = true)
                    if (handleSearchShortcut(event)) return
                    if (handleClipboardShortcut(event)) return

                    val keyEvent = keyMapper.keyPressed(event) ?: return
                    session?.encodeKey(keyEvent)
                    event.consume()
                }

                override fun keyReleased(event: KeyEvent) {
                    hyperlinkController.updateHyperlinkActivationHover(event.isControlDown)
                }

                override fun keyTyped(event: KeyEvent) {
                    resetCursorBlinkOnEdt(forceRepaint = true)
                    val keyEvent = keyMapper.keyTyped(event) ?: return
                    session?.encodeKey(keyEvent)
                    event.consume()
                }
            }

        private val terminalFocusListener =
            object : FocusAdapter() {
                override fun focusGained(event: FocusEvent) {
                    terminalFocused = true
                    resetCursorBlinkOnEdt(forceRepaint = false)
                    repaintCursorState()
                }

                override fun focusLost(event: FocusEvent) {
                    terminalFocused = false
                    repaintCursorState()
                }
            }

        private val viewportWheelListener =
            MouseWheelListener { event ->
                handleMouseWheel(event)
            }

        private val selectionMouseListener =
            object : MouseAdapter() {
                override fun mousePressed(event: MouseEvent) {
                    if (handleMouseTracking(event, TerminalMouseEventType.PRESS)) return
                    if (hyperlinkController.handleMousePressed(event)) return
                    if (SwingUtilities.isMiddleMouseButton(event)) {
                        if (settings.pasteOnMiddleClick) {
                            pasteClipboardText()
                            event.consume()
                            return
                        }
                    }
                    selectionController.handleSelectionMousePressed(event)
                }

                override fun mouseReleased(event: MouseEvent) {
                    if (handleMouseTracking(event, TerminalMouseEventType.RELEASE)) return
                    selectionController.handleSelectionMouseReleased(event)
                }

                override fun mouseExited(event: MouseEvent) {
                    hyperlinkController.handleMouseExited()
                }
            }

        private val selectionMouseMotionListener =
            object : MouseMotionAdapter() {
                override fun mouseDragged(event: MouseEvent) {
                    if (handleMouseTracking(event, TerminalMouseEventType.MOTION)) return
                    selectionController.handleSelectionMouseDragged(event)
                }

                override fun mouseMoved(event: MouseEvent) {
                    if (handleMouseTracking(event, TerminalMouseEventType.MOTION)) {
                        hyperlinkController.clearHyperlinkHover()
                        return
                    }
                    hyperlinkController.handleMouseMoved(event)
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
            addFocusListener(terminalFocusListener)
            addKeyListener(inputKeyListener)
            addMouseListener(selectionMouseListener)
            addMouseMotionListener(selectionMouseMotionListener)
            addMouseWheelListener(viewportWheelListener)
            addComponentListener(resizeListener)
            add(searchOverlay)
            searchOverlay.isVisible = false
            preferredSize = preferredGridSize(settings.columns, settings.rows)
            cursorTimer.isRepeats = true
            configureCursorTimerOnEdt()
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
            terminalFocused = isFocusOwner
            configureCursorTimerOnEdt()
        }

        override fun removeNotify() {
            terminalFocused = false
            cursorTimer.stop()
            selectionController.stopSelectionDrag()
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
                    cursorVisible = cursorPresentationEnabled,
                    cursorBlinkVisible = cursorBlinkVisible,
                    textBlinkVisible = cursorBlinkVisible,
                    contentYOffset = contentYOffset(renderCache),
                    selection = selectionController.getViewportSelection(renderCache),
                    searchHighlights = searchViewportHighlights,
                    hoveredHyperlinkId = hyperlinkController.hoveredHyperlinkId,
                    hyperlinkActivationHover = hyperlinkController.hyperlinkActivationHover,
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
            selectionController.clearSelection()
            clearSearch()
            selectionController.stopSelectionDrag()
            lastResizedColumns = NO_RESIZE_DIMENSION
            lastResizedRows = NO_RESIZE_DIMENSION
            repaintPlanner.reset()
            renderPending.set(false)
            hyperlinkController.clearHyperlinkHover()
            resizeSessionToVisibleGridOnEdt()
            refreshRenderCacheFromSession(session)
            publishViewportState(renderCache.historySize)
            repaint()
        }

        private fun unbindOnEdt() {
            session?.removeDirtyListener(dirtyListener)
            session = null
            resetScrollbackState()
            selectionController.clearSelection()
            clearSearch()
            selectionController.stopSelectionDrag()
            lastResizedColumns = NO_RESIZE_DIMENSION
            lastResizedRows = NO_RESIZE_DIMENSION
            repaintPlanner.reset()
            renderPending.set(false)
            hyperlinkController.clearHyperlinkHover()
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
            configureCursorTimerOnEdt()
            session?.let { applySettingsToSession(it, settings) }
            selectionController.clearSelection()
            updateSearchViewportHighlights()
            hyperlinkController.clearHyperlinkHover()
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
            return selectionController.getViewportSelection(renderCache)
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
            settings: SwingSettings,
        ) {
            session.setTreatAmbiguousAsWide(settings.treatAmbiguousAsWide)
            session.setThemePalette(settings.palette)
            session.setCursorShape(settings.cursorShape)
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
            if (!event.isShiftDown) return false
            if (!event.isControlDown && !event.isMetaDown) return false
            openSearchOnEdt()
            event.consume()
            return true
        }

        fun copySelectionToClipboard(): Boolean {
            if (session == null) return false
            val selectedText = selectionController.getSelectedText(renderCache) ?: return false
            hostServices.clipboardHandler.copyText(selectedText)
            return true
        }

        fun pasteClipboardText(): Boolean {
            val text = hostServices.clipboardHandler.readText() ?: return false
            if (text.isEmpty()) return false
            session?.encodePaste(TerminalPasteEvent(text))
            return true
        }

        private fun clearSearch() {
            searchQuery = ""
            searchHighlights = null
            searchViewportHighlights.reset(renderCache.rows)
            searchOverlay.setQueryText("")
            searchOverlay.updateResultCounter(0, NO_ACTIVE_SEARCH_RESULT)
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
            if (terminalFocused) {
                repaintPlanner.requestCursorBlinkRepaint(
                    cache = renderCache,
                    metrics = metrics,
                    componentWidth = width,
                    componentHeight = height,
                    contentYOffset = yOffset,
                    padding = settings.padding,
                    repaintSink = repaintSink,
                )
            }
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

        private fun repaintCursorState() {
            if (session == null) return
            val yOffset = contentYOffset(renderCache)
            repaintPlanner.requestCursorRepaint(
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
            val wasVisible = cursorBlinkVisible
            cursorBlinkVisible = true
            if (settings.cursorBlinkMillis > 0) {
                cursorTimer.restart()
            }
            if (forceRepaint && !wasVisible) {
                repaintBlinkState()
            }
        }

        private fun configureCursorTimerOnEdt() {
            cursorTimer.delay = cursorTimerDelay(settings)
            cursorTimer.initialDelay = cursorTimer.delay
            cursorBlinkVisible = true
            if (isDisplayable && settings.cursorBlinkMillis > 0) {
                cursorTimer.restart()
            } else {
                cursorTimer.stop()
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

        fun preferredGridSize(
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

            // Capture the integer scrollback offset and fractional sub-row position before
            // reflow changes history size and line wrapping. The resizer uses the integer
            // offset to locate the top visible logical line; we restore the fractional part
            // afterwards so smooth-scroll state survives the resize.
            val oldOffset = scrollModel.requestedOffset
            val oldFraction = scrollModel.preciseScrollbackOffset - scrollModel.offset

            val (newOffset, newHistorySize) = boundSession.resize(columns, rows, oldOffset)

            // Re-anchor the scroll model to the reflowed position, preserving the
            // fractional sub-row that was in-flight before the resize.
            val newPrecise = (newOffset + oldFraction).coerceIn(0.0, newHistorySize.toDouble())
            scrollModel.scrollTo(newPrecise, newHistorySize)
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

        private fun buildMetrics(settings: SwingSettings): SwingMetrics {
            val metricsSource: FontMetrics = getFontMetrics(settings.font)
            return SwingMetrics.from(metricsSource, settings.lineHeight)
        }

        private fun runOnEdt(action: Runnable) {
            if (SwingUtilities.isEventDispatchThread()) {
                action.run()
            } else {
                hostServices.uiDispatcher.dispatch(action)
            }
        }

        private companion object {
            private const val NO_RESIZE_DIMENSION = -1
            private const val NO_ACTIVE_SEARCH_RESULT = -1
            private const val NO_ACTIVE_SEARCH_ROW = Long.MIN_VALUE
            private const val MIN_TIMER_DELAY_MILLIS = 1

            private fun cursorTimerDelay(settings: SwingSettings): Int = maxOf(MIN_TIMER_DELAY_MILLIS, settings.cursorBlinkMillis)

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
