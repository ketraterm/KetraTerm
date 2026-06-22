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

import io.github.jvterm.input.event.TerminalMouseEvent
import io.github.jvterm.input.event.TerminalPasteEvent
import io.github.jvterm.protocol.MouseTrackingMode
import io.github.jvterm.render.cache.TerminalRenderCache
import io.github.jvterm.session.TerminalSession
import io.github.jvterm.session.TerminalShellIntegrationCommandRecord
import io.github.jvterm.ui.swing.input.SwingTerminalInputController
import io.github.jvterm.ui.swing.input.SwingTerminalInputHost
import io.github.jvterm.ui.swing.input.SwingTerminalMouseController
import io.github.jvterm.ui.swing.input.SwingTerminalMouseHost
import io.github.jvterm.ui.swing.render.GridPainter
import io.github.jvterm.ui.swing.render.SwingRenderFrameController
import io.github.jvterm.ui.swing.render.SwingRenderFrameHost
import io.github.jvterm.ui.swing.render.TerminalShellIntegrationViewportDecorations
import io.github.jvterm.ui.swing.render.TerminalVisualViewportGeometry
import io.github.jvterm.ui.swing.search.*
import io.github.jvterm.ui.swing.settings.SwingMetrics
import io.github.jvterm.ui.swing.settings.SwingSettings
import io.github.jvterm.ui.swing.settings.SwingSettingsProvider
import io.github.jvterm.ui.swing.viewport.SmoothScrollAnimationHost
import io.github.jvterm.ui.swing.viewport.SmoothScrollAnimator
import io.github.jvterm.ui.swing.viewport.SwingViewportController
import java.awt.*
import java.awt.event.*
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
    ) : JComponent(),
        SwingSmoothScroller {
        private var session: TerminalSession? = null
        private var settings: SwingSettings = settingsProvider.currentSettings()
        private var metrics: SwingMetrics = buildMetrics(settings)
        private var terminalFocused: Boolean = false
        internal var cursorBlinkVisible: Boolean = true
        internal val cursorPresentationEnabled: Boolean
            get() = terminalFocused
        private var lastResizedColumns: Int = NO_RESIZE_DIMENSION
        private var lastResizedRows: Int = NO_RESIZE_DIMENSION
        private val unbindRunnable = Runnable { unbindOnEdt() }
        private val reloadSettingsRunnable = Runnable { reloadSettingsOnEdt() }

        private val painter = GridPainter()
        private val viewportController = SwingViewportController(hostServices.viewportListener)
        private val renderCache = TerminalRenderCache(settings.columns, settings.rows)
        private val searchCache = TerminalRenderCache(settings.columns, settings.rows)
        private val shellIntegrationDecorations = TerminalShellIntegrationViewportDecorations()
        private val visualGeometry = TerminalVisualViewportGeometry()
        private val smoothScrollAnimator =
            SmoothScrollAnimator(
                object : SmoothScrollAnimationHost {
                    override fun smoothScrollOffset(): Double = viewportController.preciseOffset

                    override fun smoothScrollHistorySize(): Int = renderCache.historySize

                    override fun applySmoothScrollOffset(
                        offsetRows: Double,
                        animationComplete: Boolean,
                    ): Boolean = this@SwingTerminal.applyAnimatedScrollOffsetOnEdt(offsetRows, animationComplete)
                },
            )
        private var hoveredPromptMarkerRow: Int = NO_PROMPT_MARKER_ROW

        private val selectionController =
            TerminalSelectionController(
                object : TerminalSelectionHost {
                    override val settings: SwingSettings get() = this@SwingTerminal.settings
                    override val metrics: SwingMetrics get() = this@SwingTerminal.metrics
                    override val renderCache: TerminalRenderCache get() = this@SwingTerminal.renderCache
                    override val contentYOffset: Double get() = this@SwingTerminal.visualGeometry.contentOriginY
                    override val componentWidth: Int get() = this@SwingTerminal.width
                    override val componentHeight: Int get() = this@SwingTerminal.height

                    override fun cellAt(
                        x: Int,
                        y: Int,
                    ): Long = this@SwingTerminal.cellAt(x, y, this@SwingTerminal.renderCache)

                    override fun scrollViewportBy(delta: Double): Boolean = smoothScrollAnimator.scrollBy(delta)

                    override fun repaint() = this@SwingTerminal.repaint()

                    override fun requestFocusInWindow(): Boolean = this@SwingTerminal.requestFocusInWindow()
                },
            )

        private val commandInteractionController =
            TerminalCommandInteractionController(
                object : TerminalCommandInteractionHost {
                    override val session: TerminalSession? get() = this@SwingTerminal.session
                    override val renderCache: TerminalRenderCache get() = this@SwingTerminal.renderCache
                    override val searchCache: TerminalRenderCache get() = this@SwingTerminal.searchCache

                    override fun cellAt(
                        x: Int,
                        y: Int,
                        cache: TerminalRenderCache,
                    ): Long = this@SwingTerminal.cellAt(x, y, cache)

                    override fun visibleGridRows(): Int = this@SwingTerminal.visibleGridRows()

                    override fun commandNavigationAnchorRow(): Int = this@SwingTerminal.commandNavigationAnchorRow()

                    override fun refreshRenderCacheFromSession(session: TerminalSession) =
                        this@SwingTerminal.refreshRenderCacheFromSession(session)

                    override fun refreshShellIntegrationDecorations(session: TerminalSession): Boolean =
                        this@SwingTerminal.refreshShellIntegrationDecorations(session)

                    override fun selectAbsoluteRows(
                        startAbsoluteRow: Long,
                        endAbsoluteRow: Long,
                        columns: Int,
                    ) = this@SwingTerminal.selectionController.selectAbsoluteRows(startAbsoluteRow, endAbsoluteRow, columns)

                    override fun scrollViewportTo(
                        offsetLines: Double,
                        historySize: Int,
                        boundSession: TerminalSession,
                    ): Boolean = this@SwingTerminal.scrollViewportToOnEdt(offsetLines, historySize, boundSession)

                    override fun repaint() = this@SwingTerminal.repaint()
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
        private val searchController =
            TerminalSearchController(
                object : TerminalSearchHost {
                    override val session: TerminalSession? get() = this@SwingTerminal.session
                    override val renderCache: TerminalRenderCache get() = this@SwingTerminal.renderCache
                    override val searchCache: TerminalRenderCache get() = this@SwingTerminal.searchCache

                    override fun visibleGridRows(): Int = this@SwingTerminal.visibleGridRows()

                    override fun scrollViewportTo(
                        offsetLines: Double,
                        historySize: Int,
                        boundSession: TerminalSession,
                    ): Boolean = this@SwingTerminal.scrollViewportToOnEdt(offsetLines, historySize, boundSession)

                    override fun revalidate() = this@SwingTerminal.revalidate()

                    override fun repaint() = this@SwingTerminal.repaint()

                    override fun requestFocusInWindow(): Boolean = this@SwingTerminal.requestFocusInWindow()
                },
            )
        private val inputController =
            SwingTerminalInputController(
                object : SwingTerminalInputHost {
                    override val session: TerminalSession? get() = this@SwingTerminal.session
                    override val settings: SwingSettings get() = this@SwingTerminal.settings

                    override fun updateHyperlinkActivationHover(active: Boolean) {
                        hyperlinkController.updateHyperlinkActivationHover(active)
                    }

                    override fun resetCursorBlink(forceRepaint: Boolean) {
                        this@SwingTerminal.resetCursorBlinkOnEdt(forceRepaint)
                    }

                    override fun setTerminalFocused(focused: Boolean) {
                        this@SwingTerminal.terminalFocused = focused
                    }

                    override fun repaintCursorState() {
                        renderFrameController.repaintCursorState()
                    }

                    override fun openSearch() {
                        searchController.open()
                    }

                    override fun copySelectionToClipboard(): Boolean = this@SwingTerminal.copySelectionToClipboard()

                    override fun pasteClipboardText(): Boolean = this@SwingTerminal.pasteClipboardText()
                },
            )
        private val mouseController =
            SwingTerminalMouseController(
                object : SwingTerminalMouseHost {
                    override val settings: SwingSettings get() = this@SwingTerminal.settings
                    override val metrics: SwingMetrics get() = this@SwingTerminal.metrics
                    override val renderCache: TerminalRenderCache get() = this@SwingTerminal.renderCache

                    override fun mouseTrackingMode(): MouseTrackingMode =
                        this@SwingTerminal
                            .session
                            ?.terminal
                            ?.getModeSnapshot()
                            ?.mouseTrackingMode ?: MouseTrackingMode.OFF

                    override fun encodeMouse(event: TerminalMouseEvent) {
                        this@SwingTerminal.session?.encodeMouse(event)
                    }

                    override fun cellAt(
                        x: Int,
                        y: Int,
                        cache: TerminalRenderCache,
                    ): Long = this@SwingTerminal.cellAt(x, y, cache)

                    override fun terminalPixelYAt(
                        y: Int,
                        cache: TerminalRenderCache,
                    ): Int = this@SwingTerminal.terminalPixelYAt(y, cache)

                    override fun visibleGridRows(): Int = this@SwingTerminal.visibleGridRows()

                    override fun scrollViewportByRows(delta: Double): Boolean = smoothScrollAnimator.scrollBy(delta)

                    override fun finishSmoothScrollAnimation() {
                        smoothScrollAnimator.finish()
                    }

                    override fun pasteClipboardText(): Boolean = this@SwingTerminal.pasteClipboardText()

                    override fun handlePromptMarkerMousePressed(event: MouseEvent): Boolean =
                        this@SwingTerminal.handlePromptMarkerMousePressed(event)

                    override fun handlePromptMarkerMouseMoved(event: MouseEvent): Boolean =
                        this@SwingTerminal.handlePromptMarkerMouseMoved(event)

                    override fun handlePromptMarkerMouseExited() {
                        this@SwingTerminal.updateHoveredPromptMarker(NO_PROMPT_MARKER_ROW)
                    }

                    override fun handleHyperlinkMousePressed(event: MouseEvent): Boolean = hyperlinkController.handleMousePressed(event)

                    override fun handleHyperlinkMouseMoved(event: MouseEvent) {
                        hyperlinkController.handleMouseMoved(event)
                    }

                    override fun handleHyperlinkMouseExited() {
                        hyperlinkController.handleMouseExited()
                    }

                    override fun clearHyperlinkHover() {
                        hyperlinkController.clearHyperlinkHover()
                    }

                    override fun handleSelectionMousePressed(event: MouseEvent) {
                        selectionController.handleSelectionMousePressed(event)
                    }

                    override fun handleSelectionMouseReleased(event: MouseEvent) {
                        selectionController.handleSelectionMouseReleased(event)
                    }

                    override fun handleSelectionMouseDragged(event: MouseEvent) {
                        selectionController.handleSelectionMouseDragged(event)
                    }
                },
            )
        private val renderFrameController =
            SwingRenderFrameController(
                object : SwingRenderFrameHost {
                    override val session: TerminalSession? get() = this@SwingTerminal.session
                    override val renderCache: TerminalRenderCache get() = this@SwingTerminal.renderCache
                    override val settings: SwingSettings get() = this@SwingTerminal.settings
                    override val metrics: SwingMetrics get() = this@SwingTerminal.metrics
                    override val visualGeometry: TerminalVisualViewportGeometry
                        get() = this@SwingTerminal.visualGeometry
                    override val componentWidth: Int get() = this@SwingTerminal.width
                    override val componentHeight: Int get() = this@SwingTerminal.height
                    override val cursorPresentationEnabled: Boolean get() = this@SwingTerminal.cursorPresentationEnabled

                    override fun dispatch(action: Runnable) {
                        hostServices.uiDispatcher.dispatch(action)
                    }

                    override fun resetCursorBlinkForFrame() {
                        this@SwingTerminal.resetCursorBlinkOnEdt(forceRepaint = false)
                    }

                    override fun refreshRenderCacheFromSession(session: TerminalSession) {
                        this@SwingTerminal.refreshRenderCacheFromSession(session)
                    }

                    override fun clampViewport(historySize: Int): Boolean = viewportController.clamp(historySize)

                    override fun requestedViewportOffset(): Int = viewportController.requestedOffset

                    override fun refreshShellIntegrationDecorations(session: TerminalSession): Boolean =
                        this@SwingTerminal.refreshShellIntegrationDecorations(session)

                    override fun refreshSearchForFrame() {
                        this@SwingTerminal.searchController.refreshForFrame()
                    }

                    override fun publishViewportState(historySize: Int) {
                        this@SwingTerminal.publishViewportState(historySize)
                    }

                    override fun repaint() {
                        this@SwingTerminal.repaint()
                    }

                    override fun repaintRegion(
                        x: Int,
                        y: Int,
                        width: Int,
                        height: Int,
                    ) {
                        this@SwingTerminal.repaint(x, y, width, height)
                    }
                },
            )
        private val dirtyListener = { renderFrameController.schedulePublishedFrame() }

        internal val cursorTimer =
            Timer(cursorTimerDelay(settings)) {
                cursorBlinkVisible = !cursorBlinkVisible
                renderFrameController.repaintBlinkState()
            }

        private val resizeListener =
            object : ComponentAdapter() {
                override fun componentResized(event: ComponentEvent) {
                    resizeSessionToVisibleGridOnEdt()
                }
            }

        private var ancestorWindow: Window? = null

        private val windowStateListener =
            WindowStateListener { event ->
                val iconified = (event.newState and Frame.ICONIFIED) != 0
                session?.terminal?.setWindowMinimized(iconified)
            }

        private fun updateMinimizedStateFromAncestor() {
            val window = ancestorWindow ?: SwingUtilities.getWindowAncestor(this)
            if (window is Frame) {
                val iconified = (window.extendedState and Frame.ICONIFIED) != 0
                session?.terminal?.setWindowMinimized(iconified)
            }
        }

        private fun handlePromptMarkerMousePressed(event: MouseEvent): Boolean {
            if (!SwingUtilities.isLeftMouseButton(event)) return false
            val row = promptMarkerRowAt(event)
            if (row == NO_PROMPT_MARKER_ROW) return false
            val recordId = shellIntegrationDecorations.commandRecordIdAt(row)
            if (recordId == TerminalShellIntegrationCommandRecord.NONE) return false
            if (!commandInteractionController.selectCommandOutput(recordId)) return false
            event.consume()
            return true
        }

        private fun handlePromptMarkerMouseMoved(event: MouseEvent): Boolean {
            val row = promptMarkerRowAt(event)
            if (row != NO_PROMPT_MARKER_ROW) hyperlinkController.clearHyperlinkHover()
            updateHoveredPromptMarker(row)
            return row != NO_PROMPT_MARKER_ROW
        }

        private fun promptMarkerRowAt(event: MouseEvent): Int {
            val gutterWidth = settings.shellIntegrationDecorationGutterWidth.coerceAtMost(settings.padding.left)
            if (gutterWidth <= 0 || event.x !in (settings.padding.left - gutterWidth) until settings.padding.left) {
                return NO_PROMPT_MARKER_ROW
            }
            val row = cellAt(event.x, event.y, renderCache).toInt()
            return if (shellIntegrationDecorations.hasPromptStartAt(row)) row else NO_PROMPT_MARKER_ROW
        }

        private fun updateHoveredPromptMarker(row: Int) {
            val nextCursor = if (row == NO_PROMPT_MARKER_ROW) DEFAULT_CURSOR else HAND_CURSOR
            if (hoveredPromptMarkerRow == row) {
                if (cursor !== nextCursor) cursor = nextCursor
                return
            }
            hoveredPromptMarkerRow = row
            cursor = nextCursor
            repaint()
        }

        init {
            font = settings.font
            background = Color(settings.palette.defaultBackground, true)
            foreground = Color(settings.palette.defaultForeground, true)
            isOpaque = true
            isFocusable = true
            focusTraversalKeysEnabled = false
            addFocusListener(inputController.focusListener)
            addKeyListener(inputController.keyListener)
            addMouseListener(mouseController.mouseListener)
            addMouseMotionListener(mouseController.mouseMotionListener)
            addMouseWheelListener(mouseController.wheelListener)
            addComponentListener(resizeListener)
            add(searchController.overlay)
            searchController.overlay.isVisible = false
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
            if (!SwingUtilities.isEventDispatchThread()) return viewportController.visibleGridSizeSnapshot()
            return viewportController.visibleGridSizeOnEdt(settings, metrics, width, height)
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
            return viewportController.viewportStateSnapshot()
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
         * toward the live viewport. Fractional values use the shared smooth-scroll
         * animation and renderer overscan path; render-cache addressing remains
         * anchored to whole terminal rows.
         *
         * @param deltaLines signed row delta.
         */
        fun scrollViewportBy(deltaLines: Double) {
            require(!deltaLines.isNaN()) { "deltaLines must not be NaN" }
            runOnEdt(
                Runnable {
                    smoothScrollAnimator.scrollBy(deltaLines)
                },
            )
        }

        override fun smoothScrollToScrollbackOffset(scrollbackOffset: Int) {
            require(scrollbackOffset >= 0) { "scrollbackOffset must be >= 0, was $scrollbackOffset" }
            runOnEdt(
                Runnable {
                    smoothScrollAnimator.scrollTo(scrollbackOffset.toDouble())
                },
            )
        }

        /**
         * Scrolls to the nearest previous shell command.
         *
         * The component uses session-owned OSC 133 command metadata and reveals
         * the command's prompt-start line when present, otherwise its command
         * start line. This method may be called from any thread; component state
         * is updated asynchronously on the EDT.
         */
        fun scrollToPreviousCommand() {
            runOnEdt(
                Runnable {
                    commandInteractionController.scrollToCommand(previous = true)
                },
            )
        }

        /**
         * Scrolls to the nearest next shell command.
         *
         * The component uses session-owned OSC 133 command metadata and reveals
         * the command's prompt-start line when present, otherwise its command
         * start line. This method may be called from any thread; component state
         * is updated asynchronously on the EDT.
         */
        fun scrollToNextCommand() {
            runOnEdt(
                Runnable {
                    commandInteractionController.scrollToCommand(previous = false)
                },
            )
        }

        /**
         * Returns the command record at component coordinates [x], [y].
         *
         * Prompt-only rows and rows without command metadata return
         * [TerminalShellIntegrationCommandRecord.NONE]. This method is intended
         * for Swing event handlers and returns `0` when called off the EDT.
         *
         * @param x component x coordinate in pixels.
         * @param y component y coordinate in pixels.
         * @return command record id at the coordinate, or `0`.
         */
        fun commandRecordAt(
            x: Int,
            y: Int,
        ): Int {
            if (!SwingUtilities.isEventDispatchThread()) return TerminalShellIntegrationCommandRecord.NONE
            return commandInteractionController.commandRecordAt(x, y)
        }

        /**
         * Selects command output for the command under component coordinates [x], [y].
         *
         * The selection covers command output only, excluding prompt/input rows
         * for command records whose start marker was exclusive. This method is
         * intended for Swing event handlers and returns `false` off the EDT.
         *
         * @param x component x coordinate in pixels.
         * @param y component y coordinate in pixels.
         * @return true when command output was selected.
         */
        fun selectCommandOutputAt(
            x: Int,
            y: Int,
        ): Boolean {
            if (!SwingUtilities.isEventDispatchThread()) return false
            return commandInteractionController.selectCommandOutputAt(x, y)
        }

        /**
         * Selects command output for [recordId].
         *
         * The selection covers command output only, excluding prompt/input rows
         * for command records whose start marker was exclusive. This method is
         * intended for Swing event handlers and returns `false` off the EDT.
         *
         * @param recordId retained command record id.
         * @return true when command output was selected.
         */
        fun selectCommandOutput(recordId: Int): Boolean {
            if (!SwingUtilities.isEventDispatchThread()) return false
            return commandInteractionController.selectCommandOutput(recordId)
        }

        /**
         * Returns all currently retained output for [recordId].
         *
         * Soft-wrapped rows are joined while hard row boundaries become
         * newlines. This explicit EDT-only query may allocate in proportion to
         * the retained output and is never used while painting.
         *
         * @param recordId retained command record id.
         * @return command output, or `null` when unavailable or called off the EDT.
         */
        fun commandOutputText(recordId: Int): String? {
            if (!SwingUtilities.isEventDispatchThread()) return null
            return commandInteractionController.commandOutputText(recordId)
        }

        /**
         * Copies all retained output for [recordId] through the host clipboard service.
         *
         * @param recordId retained command record id.
         * @return `true` when retained output was copied; `false` when unavailable or called off the EDT.
         */
        fun copyCommandOutputToClipboard(recordId: Int): Boolean {
            if (!SwingUtilities.isEventDispatchThread()) return false
            val text = commandInteractionController.commandOutputText(recordId) ?: return false
            hostServices.clipboardHandler.copyText(text)
            return true
        }

        /**
         * Copies captured command text for [recordId] through the host clipboard service.
         *
         * @param recordId retained command record id.
         * @return `true` when command text was copied; `false` when unavailable or called off the EDT.
         */
        fun copyCommandTextToClipboard(recordId: Int): Boolean {
            if (!SwingUtilities.isEventDispatchThread()) return false
            val text = session?.shellIntegrationState?.commandText(recordId) ?: return false
            hostServices.clipboardHandler.copyText(text)
            return true
        }

        override fun addNotify() {
            super.addNotify()
            terminalFocused = isFocusOwner
            configureCursorTimerOnEdt()

            val window = SwingUtilities.getWindowAncestor(this)
            if (window != null) {
                ancestorWindow = window
                window.addWindowStateListener(windowStateListener)
                updateMinimizedStateFromAncestor()
            }
        }

        override fun removeNotify() {
            terminalFocused = false
            cursorTimer.stop()
            smoothScrollAnimator.finish()
            selectionController.stopSelectionDrag()

            ancestorWindow?.removeWindowStateListener(windowStateListener)
            ancestorWindow = null

            super.removeNotify()
        }

        override fun doLayout() {
            super.doLayout()
            val searchOverlay = searchController.overlay
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
                    visualGeometry = visualGeometry,
                    selection = selectionController.getViewportSelection(renderCache),
                    searchHighlights = searchController.viewportHighlights,
                    shellIntegrationDecorations = shellIntegrationDecorations,
                    hoveredPromptMarkerRow = hoveredPromptMarkerRow,
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
            updateMinimizedStateFromAncestor()
            applySettingsToSession(session, settings)
            session.addDirtyListener(dirtyListener)
            resetScrollbackState()
            selectionController.clearSelection()
            searchController.reset(renderCache.rows)
            shellIntegrationDecorations.reset()
            visualGeometry.reset()
            selectionController.stopSelectionDrag()
            lastResizedColumns = NO_RESIZE_DIMENSION
            lastResizedRows = NO_RESIZE_DIMENSION
            renderFrameController.reset()
            hyperlinkController.clearHyperlinkHover()
            resizeSessionToVisibleGridOnEdt()
            refreshRenderCacheFromSession(session)
            refreshShellIntegrationDecorations(session)
            publishViewportState(renderCache.historySize)
            repaint()
        }

        private fun unbindOnEdt() {
            session?.removeDirtyListener(dirtyListener)
            session = null
            resetScrollbackState()
            selectionController.clearSelection()
            searchController.reset(renderCache.rows)
            shellIntegrationDecorations.reset()
            visualGeometry.reset()
            selectionController.stopSelectionDrag()
            lastResizedColumns = NO_RESIZE_DIMENSION
            lastResizedRows = NO_RESIZE_DIMENSION
            renderFrameController.reset()
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
            session?.let {
                updateMinimizedStateFromAncestor()
                applySettingsToSession(it, settings)
            }
            selectionController.clearSelection()
            searchController.updateViewportHighlights()
            hyperlinkController.clearHyperlinkHover()
            resizeSessionToVisibleGridOnEdt()
            session?.let {
                refreshRenderCacheFromSession(it)
                refreshShellIntegrationDecorations(it)
            }
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
                    searchController.search(query)
                },
            )
        }

        /**
         * Returns the current search UI and result snapshot.
         *
         * @return current terminal search state.
         */
        fun currentSearchState(): TerminalSearchState = searchController.state()

        private fun applySettingsToSession(
            session: TerminalSession,
            settings: SwingSettings,
        ) {
            session.setTreatAmbiguousAsWide(settings.treatAmbiguousAsWide)
            session.setThemePalette(settings.palette)
            session.setCursorShape(settings.cursorShape)
        }

        /**
         * Copies the current terminal text selection to the host clipboard.
         *
         * @return `true` if selection was successfully copied to clipboard, `false` otherwise.
         */
        fun copySelectionToClipboard(): Boolean {
            if (session == null) return false
            val selectedText = selectionController.getSelectedText(renderCache) ?: return false
            hostServices.clipboardHandler.copyText(selectedText)
            return true
        }

        /**
         * Pastes text from the host clipboard into the active terminal session.
         *
         * @return `true` if clipboard text was read and sent to the session, `false` otherwise.
         */
        fun pasteClipboardText(): Boolean {
            val text = hostServices.clipboardHandler.readText() ?: return false
            if (text.isEmpty()) return false
            session?.encodePaste(TerminalPasteEvent(text))
            return true
        }

        private fun cellAt(
            x: Int,
            y: Int,
            cache: TerminalRenderCache,
        ): Long {
            val padding = settings.padding
            val column = ((x - padding.left) / metrics.cellWidth).coerceIn(0, cache.columns - 1)
            val row =
                if (cache === renderCache && visualGeometry.rowCount == cache.rows) {
                    visualGeometry.rowAtComponentY(y, padding.top)
                } else {
                    ((y - padding.top) / metrics.cellHeight).coerceIn(0, cache.rows - 1)
                }
            return packCell(column, row)
        }

        private fun terminalPixelYAt(
            y: Int,
            cache: TerminalRenderCache,
        ): Int {
            if (cache === renderCache && visualGeometry.rowCount == cache.rows) {
                return visualGeometry.terminalPixelYAtComponentY(y, settings.padding.top)
            }
            val localY = y - settings.padding.top
            return localY.coerceIn(0, maxOf(0, cache.rows * metrics.cellHeight - 1))
        }

        private fun scrollViewportToOnEdt(
            offsetLines: Double,
            historySize: Int = renderCache.historySize,
            boundSession: TerminalSession? = session,
        ): Boolean {
            smoothScrollAnimator.cancel()
            if (!viewportController.scrollTo(offsetLines, historySize)) return false
            if (boundSession != null) {
                refreshRenderCacheFromSession(boundSession)
                refreshShellIntegrationDecorations(boundSession)
            }
            searchController.updateViewportHighlights()
            publishViewportState(renderCache.historySize)
            repaint()
            return true
        }

        private fun applyAnimatedScrollOffsetOnEdt(
            offsetRows: Double,
            animationComplete: Boolean,
        ): Boolean {
            val changed = viewportController.scrollTo(offsetRows, renderCache.historySize)
            if (changed) {
                val boundSession = session
                if (boundSession != null) {
                    refreshRenderCacheFromSession(boundSession)
                    refreshShellIntegrationDecorations(boundSession)
                }
                searchController.updateViewportHighlights()
                repaint()
            }
            if (changed || animationComplete) {
                publishViewportState(renderCache.historySize, notifyListener = animationComplete)
            }
            return changed
        }

        private fun resetCursorBlinkOnEdt(forceRepaint: Boolean) {
            val wasVisible = cursorBlinkVisible
            cursorBlinkVisible = true
            if (settings.cursorBlinkMillis > 0) {
                cursorTimer.restart()
            }
            if (forceRepaint && !wasVisible) {
                renderFrameController.repaintBlinkState()
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
            smoothScrollAnimator.cancel()
            viewportController.reset()
        }

        private fun publishViewportState(
            historySize: Int,
            notifyListener: Boolean = true,
        ) {
            viewportController.publishViewportState(
                historySize = historySize,
                visibleRows = visibleGridRows(),
                renderRows = visibleRenderRows(),
                viewportHeightPixels = viewportController.viewportPixelHeight(settings, height),
                contentHeightPixels = visualContentHeightPixels(),
                notifyListener = notifyListener,
            )
        }

        fun preferredGridSize(
            columns: Int,
            rows: Int,
        ): Dimension {
            val padding = settings.padding
            return Dimension(
                columns * metrics.cellWidth + padding.left,
                rows * metrics.cellHeight + padding.top + padding.bottom,
            )
        }

        private fun resizeSessionToVisibleGridOnEdt() {
            smoothScrollAnimator.finish()
            val visibleGridSize = viewportController.visibleGridSizeOnEdt(settings, metrics, width, height)
            publishViewportState(renderCache.historySize)
            val boundSession = session ?: return
            if (width <= 0 || height <= 0) return

            val columns = visibleGridSize.width
            val rows = visibleGridSize.height
            if (columns == lastResizedColumns && rows == lastResizedRows) return

            lastResizedColumns = columns
            lastResizedRows = rows

            // Preserve both the render anchor and fractional visual position across reflow.
            val oldOffset = viewportController.resizeRequestedOffset()
            val oldFraction = viewportController.resizeFraction()

            val (newOffset, newHistorySize) = boundSession.resize(columns, rows, oldOffset)

            viewportController.anchorAfterResize(newOffset, newHistorySize, oldFraction)
        }

        private fun visibleGridRows(): Int = viewportController.visibleGridRows(settings, metrics, height)

        private fun visibleRenderRows(): Int = viewportController.visibleRenderRows(settings, metrics, height)

        private fun requestedRenderRows(): Int = viewportController.requestedRows(visibleRenderRows())

        private fun visualContentHeightPixels(): Int =
            if (visualGeometry.rowCount == renderCache.rows) {
                visualGeometry.visualHeight
            } else {
                renderCache.rows * metrics.cellHeight
            }

        private fun commandNavigationAnchorRow(): Int =
            if (visualGeometry.rowCount == renderCache.rows) {
                visualGeometry.firstFullyVisibleRow()
            } else {
                0
            }

        private fun refreshRenderCacheFromSession(session: TerminalSession) {
            renderCache.updateFrom(
                reader = session,
                scrollbackOffset = viewportController.requestedOffset,
                viewportRows = requestedRenderRows(),
            )
        }

        private fun refreshShellIntegrationDecorations(session: TerminalSession): Boolean {
            val decorationsChanged = shellIntegrationDecorations.updateFrom(session.shellIntegrationState, renderCache)
            val viewportPixelHeight = viewportController.viewportPixelHeight(settings, height)
            val layoutChanged =
                visualGeometry.updateLayout(
                    metrics = metrics,
                    rows = renderCache.rows,
                    viewportPixelHeight = viewportPixelHeight,
                )
            val visualMetricsChanged =
                viewportController.updateVisualMetrics(
                    historySize = renderCache.historySize,
                    cellHeight = metrics.cellHeight,
                    visualOverflowPixels = 0,
                )
            val originChanged =
                visualGeometry.updateContentOrigin(
                    viewportController.contentOriginY(
                        cacheScrollbackOffset = renderCache.scrollbackOffset,
                        cellHeight = metrics.cellHeight,
                    ),
                )
            return decorationsChanged or layoutChanged or visualMetricsChanged or originChanged
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
            private const val NO_PROMPT_MARKER_ROW = -1
            private const val NO_RESIZE_DIMENSION = -1
            private const val MIN_TIMER_DELAY_MILLIS = 1
            private val HAND_CURSOR: Cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            private val DEFAULT_CURSOR: Cursor = Cursor.getDefaultCursor()

            private fun cursorTimerDelay(settings: SwingSettings): Int = maxOf(MIN_TIMER_DELAY_MILLIS, settings.cursorBlinkMillis)

            private fun packCell(
                column: Int,
                row: Int,
            ): Long = (column.toLong() shl 32) or (row.toLong() and 0xffff_ffffL)
        }
    }
