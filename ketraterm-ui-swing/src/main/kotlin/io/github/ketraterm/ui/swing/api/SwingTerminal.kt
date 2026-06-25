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
package io.github.ketraterm.ui.swing.api

import io.github.ketraterm.input.event.TerminalMouseEvent
import io.github.ketraterm.input.event.TerminalPasteEvent
import io.github.ketraterm.protocol.MouseTrackingMode
import io.github.ketraterm.render.api.TerminalRenderBufferKind
import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.session.TerminalShellIntegrationCommandRecord
import io.github.ketraterm.ui.swing.input.SwingTerminalInputController
import io.github.ketraterm.ui.swing.input.SwingTerminalInputHost
import io.github.ketraterm.ui.swing.input.SwingTerminalMouseController
import io.github.ketraterm.ui.swing.input.SwingTerminalMouseHost
import io.github.ketraterm.ui.swing.render.*
import io.github.ketraterm.ui.swing.search.TerminalSearchController
import io.github.ketraterm.ui.swing.search.TerminalSearchHost
import io.github.ketraterm.ui.swing.search.TerminalSearchState
import io.github.ketraterm.ui.swing.settings.SwingMetrics
import io.github.ketraterm.ui.swing.settings.SwingSettings
import io.github.ketraterm.ui.swing.settings.SwingSettingsProvider
import io.github.ketraterm.ui.swing.settings.SwingTerminalChrome
import io.github.ketraterm.ui.swing.viewport.SmoothRowScrollHost
import io.github.ketraterm.ui.swing.viewport.SmoothRowScroller
import io.github.ketraterm.ui.swing.viewport.SwingViewportController
import java.awt.*
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseEvent
import java.awt.event.WindowStateListener
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
        SwingScrollbarScroller {
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
        private val visualBellController =
            TerminalVisualBellController {
                repaint()
            }
        private val viewportController = SwingViewportController(hostServices.viewportListener)
        private val renderCache =
            TerminalRenderCache(
                columns = settings.columns,
                rows = settings.rows,
                rowCapacityReserve = TRANSIENT_RENDER_ROW_RESERVE,
            )
        private val searchCache = TerminalRenderCache(settings.columns, settings.rows)
        private val shellIntegrationDecorations = TerminalShellIntegrationViewportDecorations()
        private val visualGeometry = TerminalVisualViewportGeometry()
        private val rowScroller =
            SmoothRowScroller(
                object : SmoothRowScrollHost {
                    override fun rowScrollOffset(): Double = viewportController.preciseOffset

                    override fun rowScrollHistorySize(): Int = renderCache.historySize

                    override fun applyRowScrollOffset(
                        offsetRows: Double,
                        scrollComplete: Boolean,
                    ): Boolean = this@SwingTerminal.applyRowScrollOffsetOnEdt(offsetRows, scrollComplete)
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

                    override fun scrollViewportByRows(deltaRows: Int): Boolean = rowScroller.scrollByRows(deltaRows)

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
                        offsetRows: Int,
                        historySize: Int,
                        boundSession: TerminalSession,
                    ): Boolean = this@SwingTerminal.scrollViewportToOnEdt(offsetRows, historySize, boundSession)

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
                        offsetRows: Int,
                        historySize: Int,
                        boundSession: TerminalSession,
                    ): Boolean = this@SwingTerminal.scrollViewportToOnEdt(offsetRows, historySize, boundSession)

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

                    override fun visibleGridRows(): Int = this@SwingTerminal.visibleGridRows()

                    override fun scrollViewportByRows(deltaRows: Int) {
                        rowScroller.scrollByRows(deltaRows)
                    }

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

                    override fun scrollViewportByPreciseRows(deltaRows: Double): Boolean = rowScroller.scrollByPreciseRows(deltaRows)

                    override fun finishViewportScroll() {
                        rowScroller.finish()
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

                    override fun syncTerminalGridToActiveChrome(): Boolean =
                        this@SwingTerminal.resizeSessionToVisibleGridOnEdt(publishWhenUnchanged = false)

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
            if (renderCache.activeBuffer == TerminalRenderBufferKind.ALTERNATE) return false
            val row = promptMarkerRowAt(event)
            if (row == NO_PROMPT_MARKER_ROW) return false
            val recordId = shellIntegrationDecorations.commandRecordIdAt(row)
            if (recordId == TerminalShellIntegrationCommandRecord.NONE) return false
            if (!commandInteractionController.selectCommandOutput(recordId)) return false
            event.consume()
            return true
        }

        private fun handlePromptMarkerMouseMoved(event: MouseEvent): Boolean {
            if (renderCache.activeBuffer == TerminalRenderBufferKind.ALTERNATE) {
                updateHoveredPromptMarker(NO_PROMPT_MARKER_ROW)
                return false
            }
            val row = promptMarkerRowAt(event)
            if (row != NO_PROMPT_MARKER_ROW) hyperlinkController.clearHyperlinkHover()
            updateHoveredPromptMarker(row)
            return row != NO_PROMPT_MARKER_ROW
        }

        private fun promptMarkerRowAt(event: MouseEvent): Int {
            val paddingLeft = SwingTerminalChrome.left(settings, renderCache.activeBuffer)
            val gutterWidth = SwingTerminalChrome.promptDecorationGutterWidth(settings, renderCache.activeBuffer)
            if (gutterWidth <= 0 || event.x !in (paddingLeft - gutterWidth) until paddingLeft) {
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
            configureVisualBellOnEdt()
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
         * Triggers this component's visual bell indicator.
         *
         * Hosts should call this when the bound terminal session emits BEL and
         * the current host settings allow visual bell presentation. The method
         * may be called from any thread; animation state is updated on the EDT.
         */
        fun showVisualBell() {
            runOnEdt(
                Runnable {
                    visualBellController.trigger()
                },
            )
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
            return viewportController.visibleGridSizeOnEdt(settings, metrics, width, height, renderCache.activeBuffer)
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
                    scrollViewportToOnEdt(scrollbackOffset)
                },
            )
        }

        /**
         * Applies a signed scrollback delta in terminal rows.
         *
         * Positive values move farther back into scrollback; negative values move
         * toward the live viewport. Fractional values accumulate until they
         * produce a whole-row destination. Animation may render between rows,
         * but always completes on the destination row.
         *
         * @param deltaLines signed row delta.
         */
        fun scrollViewportBy(deltaLines: Double) {
            require(deltaLines.isFinite()) { "deltaLines must be finite, was $deltaLines" }
            runOnEdt(
                Runnable {
                    rowScroller.scrollByPreciseRows(deltaLines)
                },
            )
        }

        override fun scrollFromScrollbar(
            scrollbackOffset: Int,
            valueIsAdjusting: Boolean,
        ) {
            require(scrollbackOffset >= 0) { "scrollbackOffset must be >= 0, was $scrollbackOffset" }
            if (SwingUtilities.isEventDispatchThread()) {
                scrollFromScrollbarOnEdt(scrollbackOffset, valueIsAdjusting)
                return
            }
            runOnEdt(
                Runnable {
                    scrollFromScrollbarOnEdt(scrollbackOffset, valueIsAdjusting)
                },
            )
        }

        private fun scrollFromScrollbarOnEdt(
            scrollbackOffset: Int,
            valueIsAdjusting: Boolean,
        ) {
            if (valueIsAdjusting) {
                rowScroller.jumpToRow(scrollbackOffset)
            } else {
                rowScroller.scrollToRow(scrollbackOffset)
            }
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
            visualBellController.stop()
            rowScroller.finish()
            selectionController.stopSelectionDrag()

            ancestorWindow?.removeWindowStateListener(windowStateListener)
            ancestorWindow = null

            super.removeNotify()
        }

        override fun doLayout() {
            super.doLayout()
            val searchOverlay = searchController.overlay
            val preferred = searchOverlay.preferredSize
            val paddingLeft = SwingTerminalChrome.left(settings, renderCache.activeBuffer)
            val paddingRight = SwingTerminalChrome.right(settings, renderCache.activeBuffer)
            val paddingTop = SwingTerminalChrome.top(settings)
            val availableWidth = width - paddingLeft - paddingRight
            val overlayWidth = minOf(availableWidth, preferred.width).coerceAtLeast(0)
            if (overlayWidth == 0) {
                searchOverlay.setBounds(0, 0, 0, 0)
                return
            }
            val x = maxOf(paddingLeft, width - paddingRight - overlayWidth)
            searchOverlay.setBounds(x, paddingTop, overlayWidth, preferred.height)
        }

        override fun paintComponent(graphics: Graphics) {
            super.paintComponent(graphics)

            val g = graphics.create() as Graphics2D
            try {
                if (session == null) {
                    painter.clear(g, settings.palette, width, height)
                    visualBellController.paint(g, width, height)
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
                visualBellController.paint(g, width, height)
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
            visualBellController.stop()
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
            configureVisualBellOnEdt()
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
            session.setPasteSanitizationPolicy(settings.pasteSanitizationPolicy)
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
         * Writes host-approved text to the configured clipboard service.
         *
         * This is intended for host-owned workflows where policy has already
         * allowed a non-selection clipboard write, such as OSC 52 handling.
         *
         * @param text text to place on the host clipboard.
         * @return `true` when a session is bound and the clipboard handler was invoked.
         */
        fun copyTextToClipboard(text: String): Boolean {
            if (session == null) return false
            hostServices.clipboardHandler.copyText(text)
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
            val paddingLeft = SwingTerminalChrome.left(settings, cache.activeBuffer)
            val paddingTop = SwingTerminalChrome.top(settings)
            val column = ((x - paddingLeft) / metrics.cellWidth).coerceIn(0, cache.columns - 1)
            val row =
                if (cache === renderCache && visualGeometry.rowCount == cache.rows) {
                    visualGeometry.rowAtComponentY(y, paddingTop)
                } else {
                    ((y - paddingTop) / metrics.cellHeight).coerceIn(0, cache.rows - 1)
                }
            return packCell(column, row)
        }

        private fun terminalPixelYAt(
            y: Int,
            cache: TerminalRenderCache,
        ): Int {
            val paddingTop = SwingTerminalChrome.top(settings)
            if (cache === renderCache && visualGeometry.rowCount == cache.rows) {
                return visualGeometry.terminalPixelYAtComponentY(y, paddingTop)
            }
            val localY = y - paddingTop
            return localY.coerceIn(0, maxOf(0, cache.rows * metrics.cellHeight - 1))
        }

        private fun scrollViewportToOnEdt(
            offsetRows: Int,
            historySize: Int = renderCache.historySize,
            boundSession: TerminalSession? = session,
        ): Boolean {
            rowScroller.cancel()
            val targetRow = offsetRows.coerceIn(0, historySize)
            if (!moveViewportToOnEdt(targetRow.toDouble(), historySize, boundSession)) return false
            publishViewportState(renderCache.historySize)
            return true
        }

        private fun applyRowScrollOffsetOnEdt(
            offsetRows: Double,
            scrollComplete: Boolean,
        ): Boolean {
            val changed = moveViewportToOnEdt(offsetRows, renderCache.historySize, session)
            if (changed || scrollComplete) {
                publishViewportState(
                    renderCache.historySize,
                    notifyListener = scrollComplete,
                    notifyPrimitiveListener = !scrollComplete,
                )
            }
            return changed
        }

        private fun moveViewportToOnEdt(
            offsetRows: Double,
            historySize: Int,
            boundSession: TerminalSession?,
        ): Boolean {
            val oldRenderOffset = viewportController.requestedOffset
            val oldRequestedRows = requestedRenderRows()
            if (!viewportController.scrollTo(offsetRows, historySize)) return false

            val renderMappingChanged =
                oldRenderOffset != viewportController.requestedOffset ||
                    oldRequestedRows != requestedRenderRows()
            if (boundSession != null && renderMappingChanged) {
                refreshRenderCacheFromSession(boundSession)
                refreshShellIntegrationDecorations(boundSession)
                searchController.updateViewportHighlights()
            } else {
                updateVisualViewportGeometry()
            }
            repaint()
            return true
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

        private fun configureVisualBellOnEdt() {
            visualBellController.configure(
                enabled = settings.visualBellEnabled,
                colorArgb = settings.visualBellColor,
                durationMillis = settings.visualBellDurationMillis,
                edgeThicknessPixels = settings.visualBellEdgeThicknessPixels,
            )
        }

        private fun resetScrollbackState() {
            rowScroller.cancel()
            viewportController.reset()
        }

        private fun publishViewportState(
            historySize: Int,
            notifyListener: Boolean = true,
            notifyPrimitiveListener: Boolean = false,
        ) {
            viewportController.publishViewportState(
                historySize = historySize,
                visibleRows = visibleGridRows(),
                renderRows = visibleRenderRows(),
                viewportHeightPixels = viewportController.viewportPixelHeight(settings, height, renderCache.activeBuffer),
                contentHeightPixels = visualContentHeightPixels(),
                notifyListener = notifyListener,
                notifyPrimitiveListener = notifyPrimitiveListener,
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

        private fun resizeSessionToVisibleGridOnEdt(publishWhenUnchanged: Boolean = true): Boolean {
            val visibleGridSize = viewportController.visibleGridSizeOnEdt(settings, metrics, width, height, renderCache.activeBuffer)
            val boundSession = session
            if (boundSession == null || width <= 0 || height <= 0) {
                if (publishWhenUnchanged) publishViewportState(renderCache.historySize)
                return false
            }

            val columns = visibleGridSize.width
            val rows = visibleGridSize.height
            if (columns == lastResizedColumns && rows == lastResizedRows) {
                if (publishWhenUnchanged) publishViewportState(renderCache.historySize)
                return false
            }

            rowScroller.finish()
            publishViewportState(renderCache.historySize)
            lastResizedColumns = columns
            lastResizedRows = rows

            // Animation is finished above, so resize anchoring is always row-exact.
            val oldOffset = viewportController.resizeRequestedOffset()

            val (newOffset, newHistorySize) = boundSession.resize(columns, rows, oldOffset)

            viewportController.anchorAfterResize(newOffset, newHistorySize)
            return true
        }

        private fun visibleGridRows(): Int = viewportController.visibleGridRows(settings, metrics, height, renderCache.activeBuffer)

        private fun visibleRenderRows(): Int = viewportController.visibleRenderRows(settings, metrics, height, renderCache.activeBuffer)

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
            return decorationsChanged or updateVisualViewportGeometry()
        }

        private fun updateVisualViewportGeometry(): Boolean {
            val viewportPixelHeight = viewportController.viewportPixelHeight(settings, height, renderCache.activeBuffer)
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
            return layoutChanged or visualMetricsChanged or originChanged
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

            // One row covers a fractional component height and one covers the
            // translated leading/trailing edge during smooth row animation.
            private const val TRANSIENT_RENDER_ROW_RESERVE = 2

            private val HAND_CURSOR: Cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            private val DEFAULT_CURSOR: Cursor = Cursor.getDefaultCursor()

            private fun cursorTimerDelay(settings: SwingSettings): Int = maxOf(MIN_TIMER_DELAY_MILLIS, settings.cursorBlinkMillis)

            private fun packCell(
                column: Int,
                row: Int,
            ): Long = (column.toLong() shl 32) or (row.toLong() and 0xffff_ffffL)
        }
    }
