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

import io.github.ketraterm.core.api.TerminalInputState
import io.github.ketraterm.input.api.TerminalInputEncoder
import io.github.ketraterm.input.event.TerminalKeyEvent
import io.github.ketraterm.input.event.TerminalModifiers
import io.github.ketraterm.input.event.TerminalMouseEvent
import io.github.ketraterm.input.event.TerminalPasteEvent
import io.github.ketraterm.protocol.MouseTrackingMode
import io.github.ketraterm.render.api.TerminalRenderBufferKind
import io.github.ketraterm.render.cache.TerminalRenderCache
import io.github.ketraterm.session.TerminalInputAdmission
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.session.TerminalSessionState
import io.github.ketraterm.session.TerminalShellIntegrationCommandRecord
import io.github.ketraterm.ui.swing.cleanupSwingResources
import io.github.ketraterm.ui.swing.input.*
import io.github.ketraterm.ui.swing.preserveSwingFailure
import io.github.ketraterm.ui.swing.render.*
import io.github.ketraterm.ui.swing.search.TerminalSearchController
import io.github.ketraterm.ui.swing.search.TerminalSearchHost
import io.github.ketraterm.ui.swing.search.TerminalSearchState
import io.github.ketraterm.ui.swing.settings.*
import io.github.ketraterm.ui.swing.suggestion.*
import io.github.ketraterm.ui.swing.viewport.SwingViewportController
import io.github.ketraterm.ui.swing.viewport.TerminalScrollbarOverlay
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.awt.*
import java.awt.event.*
import java.lang.Runnable
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JComponent
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.coroutines.CoroutineContext
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Reusable Swing terminal component.
 *
 * The component consumes published render-cache snapshots from a
 * [TerminalSession] and paints terminal rows without knowing which transport
 * produced the bytes. Hosts own session creation, process lifecycle, and
 * connector choice outside this component.
 * One controlling view per session is supported: settings, grid dimensions,
 * and the published viewport belong to the session. Binding multiple components
 * does not create independently configured or independently scrolling views.
 *
 * Component state belongs to the Swing Event Dispatch Thread (EDT). Access it
 * on the EDT unless a method explicitly documents another threading contract.
 * Snapshot access and dispatch to the EDT are specified by each public method.
 *
 * Component or font/settings changes that resize the bound session's grid clear
 * selection and stop selection dragging. Selection uses physical cell coordinates;
 * unchanged grid dimensions preserve it.
 *
 * After closure, the view preserves the final grid, active buffer, palette, and selection.
 * Width changes clip columns without reflow. Height changes show a bottom-anchored window
 * over retained rows. Scrolling includes grid rows hidden above a shorter view.
 * Font changes affect only presentation. No transport work or session restart occurs.
 *
 * A focused terminal paints the application's cursor shape and blink state.
 * Without keyboard focus blocks become steady outlines; bars and underlines stay steady, preserving application
 * cursor visibility and viewport clipping. Focus never changes the session's cursor style.
 *
 * @param settingsProvider provider for immutable settings snapshots.
 * @param hostServices host-provided non-render services.
 */
public class SwingTerminal
    internal constructor(
        private val settingsProvider: SwingSettingsProvider,
        private val hostServices: SwingHostServices,
        searchDispatcher: CoroutineDispatcher,
        hyperlinkDispatcher: CoroutineDispatcher = Dispatchers.Default,
        private val pointerPosition: (JComponent) -> Point? = {
            if (it.isShowing && !GraphicsEnvironment.isHeadless()) it.mousePosition else null
        },
        private val suggestionDispatcher: CoroutineDispatcher? = null,
    ) : JComponent(),
        SwingScrollbarScroller {
        @JvmOverloads
        public constructor(
            settingsProvider: SwingSettingsProvider = SwingSettingsProvider { SwingSettings() },
            hostServices: SwingHostServices = SwingHostServices(),
        ) : this(settingsProvider, hostServices, Dispatchers.Default)

        private val chrome = SwingTerminalChrome()
        private var session: TerminalSession? = null
        private var retainedViewport: RetainedFrameViewport? = null
        private var disposed: Boolean = false
        private var settings: SwingSettings = settingsProvider.currentSettings()
        private var metrics: SwingMetrics = buildMetrics(settings)
        private var terminalFocused: Boolean = false
        internal var cursorBlinkVisible: Boolean = true
        private var lastResizedColumns: Int = NO_RESIZE_DIMENSION
        private var lastResizedRows: Int = NO_RESIZE_DIMENSION
        private val unbindRunnable = Runnable { unbindOnEdt() }
        private val componentJob = SupervisorJob()
        private val uiCoroutineDispatcher =
            object : CoroutineDispatcher() {
                override fun isDispatchNeeded(context: CoroutineContext): Boolean = !SwingUtilities.isEventDispatchThread()

                override fun dispatch(
                    context: CoroutineContext,
                    block: Runnable,
                ) {
                    hostServices.uiDispatcher.dispatch(block)
                }
            }
        private val componentScope =
            CoroutineScope(componentJob + uiCoroutineDispatcher + CoroutineName("swing-terminal"))
        private var bindingJob: Job? = null
        private var suggestionScope: CoroutineScope? = null
        private var suggestionFailureHandler: SwingShellSuggestionFailureHandler = SwingShellSuggestionFailureHandler.LOGGING
        private var activeSuggestionInteraction: SwingShellSuggestionInteraction? = null
        private var suggestionRevision = 0L
        private var shellSuggestionProvider: SwingShellSuggestionProvider? = null
        private var shellSuggestionTarget: SwingShellSuggestionTarget? = null
        private var suggestionConfigurationRevision = 0L
        private var suggestionScheduler: SwingShellSuggestionScheduler? = null
        private var suggestionAnchorColumn = 0
        private var suggestionAnchorRow = 0
        private var activeSuggestionIsAutomatic: Boolean = false
        private var activeSuggestionTarget: SwingShellSuggestionTarget? = null
        private val suggestionInvalidationListeners = CopyOnWriteArraySet<SwingShellSuggestionInvalidationListener>()
        private val suggestionEligibilityListeners = CopyOnWriteArraySet<SwingShellSuggestionEligibilityListener>()
        private val automaticSuggestionEligible = AtomicBoolean(settings.smartSuggestionsEnabled && settings.shellSuggestionsEnabled)

        // Callbacks may reenter the component; the revision prevents obsolete notifications.
        private var automaticSuggestionEligibilityRevision = 0L

        internal val hasActiveRenderBinding: Boolean
            get() = bindingJob?.isActive == true

        internal val isCoroutineScopeActive: Boolean
            get() = componentJob.isActive

        private val visualGeometry = TerminalVisualViewportGeometry()
        private val suggestionAnchorBounds = Rectangle()
        private val painter = GridPainter(hostServices.fontResolver, visualGeometry.bidiLayout, chrome)
        private val visualBellController =
            TerminalVisualBellController {
                repaint()
            }
        private val viewportController =
            SwingViewportController(hostServices.viewportListener, chrome) { renderMappingChanged, scrollComplete ->
                handleViewportScrollOnEdt(renderMappingChanged, scrollComplete)
            }
        private val renderCache =
            TerminalRenderCache(
                columns = settings.columns,
                rows = settings.rows,
                rowCapacityReserve = TRANSIENT_RENDER_ROW_RESERVE,
            )
        private val searchCache = TerminalRenderCache(settings.columns, settings.rows)
        private val shellIntegrationDecorations = TerminalShellIntegrationViewportDecorations()
        private val scrollbarOverlay = TerminalScrollbarOverlay(chrome)
        private var hoveredPromptMarkerRow: Int = NO_PROMPT_MARKER_ROW
        private var hyperlinkCursor: Cursor = DEFAULT_CURSOR
        private val terminalMouseListener =
            object : MouseAdapter() {
                override fun mousePressed(event: MouseEvent) {
                    if (handleScrollbarOverlayPressed(event)) return
                    mouseController.mouseListener.mousePressed(event)
                }

                override fun mouseReleased(event: MouseEvent) {
                    if (handleScrollbarOverlayReleased(event)) return
                    if (isInScrollbarOverlayGutter(event) && !mouseController.hasPressedButtons) {
                        event.consume()
                        return
                    }
                    mouseController.mouseListener.mouseReleased(event)
                    refreshHyperlinkHover()
                }

                override fun mouseExited(event: MouseEvent) {
                    if (hostServices.scrollbarOverlayEnabled && scrollbarOverlay.handleExited()) repaint()
                    mouseController.mouseListener.mouseExited(event)
                }
            }
        private val terminalMouseMotionListener =
            object : MouseMotionAdapter() {
                override fun mouseDragged(event: MouseEvent) {
                    if (handleScrollbarOverlayDragged(event)) return
                    if (isInScrollbarOverlayGutter(event)) {
                        event.consume()
                        return
                    }
                    mouseController.mouseMotionListener.mouseDragged(event)
                }

                override fun mouseMoved(event: MouseEvent) {
                    val changed =
                        hostServices.scrollbarOverlayEnabled &&
                            scrollbarOverlay.handleMoved(
                                x = event.x,
                                y = event.y,
                                settings = settings,
                                activeBuffer = renderCache.activeBuffer,
                                componentWidth = width,
                                componentHeight = height,
                            )
                    if (changed) repaint()
                    if (hostServices.scrollbarOverlayEnabled && scrollbarOverlay.hovered) {
                        clearPointerHover()
                        return
                    }
                    mouseController.mouseMotionListener.mouseMoved(event)
                }
            }

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

                    override fun visualCellAt(
                        x: Int,
                        y: Int,
                    ): Long = this@SwingTerminal.visualCellAt(x, y, this@SwingTerminal.renderCache)

                    override fun scrollViewportByRows(deltaRows: Int): Boolean = viewportController.scrollByRows(deltaRows)

                    override fun repaint() = this@SwingTerminal.repaint()

                    override fun copySelection() {
                        copySelectionToClipboard()
                    }

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

                    override fun scrollToAbsoluteRow(
                        row: Long,
                        center: Boolean,
                    ): Boolean = this@SwingTerminal.scrollToAbsoluteRowOnEdt(row, center)

                    override fun repaint() = this@SwingTerminal.repaint()
                },
            )

        private val hyperlinkDiscoveryController: TerminalHyperlinkDiscoveryController =
            TerminalHyperlinkDiscoveryController(
                host =
                    object : TerminalHyperlinkDiscoveryHost {
                        override val renderCache: TerminalRenderCache get() = this@SwingTerminal.renderCache
                        override val hyperlinkDetector: SwingHyperlinkDetector
                            get() = this@SwingTerminal.hostServices.hyperlinkDetector
                        override val hyperlinkSource get() = this@SwingTerminal.session

                        override fun hyperlinksChanged() = refreshHyperlinkHover()

                        override fun repaintHyperlinkSpan(
                            startRow: Int,
                            startColumn: Int,
                            endRow: Int,
                            endColumn: Int,
                        ) = this@SwingTerminal.repaintHyperlinkSpan(startRow, startColumn, endRow, endColumn)
                    },
                scope = componentScope,
                analysisDispatcher = hyperlinkDispatcher,
            )
        private val hyperlinkController: TerminalHyperlinkController =
            TerminalHyperlinkController(
                object : TerminalHyperlinkHost {
                    override val renderCache: TerminalRenderCache get() = this@SwingTerminal.renderCache
                    override var cursor: Cursor
                        get() = hyperlinkCursor
                        set(value) {
                            hyperlinkCursor = value
                            updatePointerCursor()
                        }

                    override fun cellAt(
                        x: Int,
                        y: Int,
                    ): Long =
                        if (isPromptDividerAt(x, y)) packCell(0, -1) else this@SwingTerminal.cellAt(x, y, this@SwingTerminal.renderCache)

                    override fun repaintHyperlinkSpan(
                        startRow: Int,
                        startColumn: Int,
                        endRow: Int,
                        endColumn: Int,
                    ) = this@SwingTerminal.repaintHyperlinkSpan(startRow, startColumn, endRow, endColumn)

                    override fun hyperlinkIdAt(
                        row: Int,
                        column: Int,
                    ): Int = hyperlinkDiscoveryController.hyperlinkIdAt(row, column, this@SwingTerminal.renderCache)

                    override fun isHyperlinkResolvable(hyperlinkId: Int): Boolean = this@SwingTerminal.isHyperlinkResolvable(hyperlinkId)

                    override fun openHyperlink(hyperlinkId: Int): Boolean = this@SwingTerminal.openHyperlink(hyperlinkId)

                    override fun openHyperlink(
                        hyperlinkId: Int,
                        event: MouseEvent,
                    ): Boolean = this@SwingTerminal.openHyperlink(hyperlinkId, event)

                    override fun hyperlinkActivation(hyperlinkId: Int): SwingHyperlinkActivation =
                        if (hyperlinkId > 0) {
                            settings.osc8HyperlinkActivation
                        } else {
                            hyperlinkDiscoveryController.discoveredHyperlink(hyperlinkId, renderCache)?.activation
                                ?: SwingHyperlinkActivation.MODIFIER
                        }

                    override fun hyperlinkAction(hyperlinkId: Int): SwingHyperlinkAction? =
                        hyperlinkDiscoveryController.discoveredHyperlink(hyperlinkId, renderCache)?.action

                    override fun enterHyperlink(
                        action: SwingHyperlinkAction,
                        row: Int,
                        startColumn: Int,
                        endColumn: Int,
                    ) {
                        val bidi = visualGeometry.bidiLayout.row(renderCache, row)
                        var first = renderCache.columns
                        var last = 0
                        forEachVisualCellSpan(bidi, startColumn, endColumn) { start, end ->
                            first = minOf(first, start)
                            last = maxOf(last, end)
                        }
                        action.mouseEntered(
                            this@SwingTerminal,
                            chrome.left(settings, renderCache.activeBuffer) + first * metrics.cellWidth,
                            floor(
                                chrome.top(settings, renderCache.activeBuffer) + visualGeometry.contentOriginY +
                                    visualGeometry.rowTop(row),
                            ).toInt(),
                            (last - first) * metrics.cellWidth,
                            metrics.cellHeight,
                        )
                    }
                },
            )
        private val searchController: TerminalSearchController =
            TerminalSearchController(
                object : TerminalSearchHost {
                    override val session: TerminalSession? get() = this@SwingTerminal.session
                    override val renderCache: TerminalRenderCache get() = this@SwingTerminal.renderCache

                    override fun scrollToAbsoluteRow(
                        row: Long,
                        center: Boolean,
                    ): Boolean = this@SwingTerminal.scrollToAbsoluteRowOnEdt(row, center)

                    override fun repaint() = this@SwingTerminal.renderFrameController.repaintFrame()
                },
                scope = componentScope,
                analysisDispatcher = searchDispatcher,
            )
        private var shellSuggestionController: SwingShellSuggestionController? = null
        private val inputController =
            SwingTerminalInputController(
                object : SwingTerminalInputHost {
                    override val session: TerminalSession? get() = this@SwingTerminal.session

                    override fun updatePointerModifiers(modifiers: Int) {
                        val routingChanged = mouseController.updatePointerModifiers(modifiers)
                        hyperlinkController.updateHyperlinkActivationHover(modifiers and hyperlinkNavigationModifierMask != 0)
                        if (routingChanged) refreshHyperlinkHover()
                    }

                    override fun resetCursorBlink() {
                        if (resetCursorBlinkOnEdt()) renderFrameController.repaintBlinkState()
                    }

                    override fun setTerminalFocused(focused: Boolean) {
                        this@SwingTerminal.terminalFocused = focused
                        if (focused) suggestionScheduler?.onFocusGained() else suggestionScheduler?.onFocusLost()
                        if (focused) reconcileHyperlinksOnEdt() else clearPointerHover()
                    }

                    override fun repaintCursorState() {
                        renderFrameController.repaintCursorState()
                    }

                    override fun handleHostKeyPressed(event: KeyEvent): Boolean = hostServices.hostKeyHandler.handleKeyPressed(event)

                    override fun handleShellSuggestionKeyPressed(event: KeyEvent): Boolean =
                        shellSuggestionController?.handleKeyPressed(event) == true

                    override fun invalidateShellSuggestions() {
                        invalidateShellSuggestionsOnEdt()
                    }

                    override fun hideShellSuggestions() {
                        this@SwingTerminal.hideShellSuggestions()
                    }
                },
            )
        private val mouseController =
            SwingTerminalMouseController(
                object : SwingTerminalMouseHost {
                    override val settings: SwingSettings get() = this@SwingTerminal.settings
                    override val metrics: SwingMetrics get() = this@SwingTerminal.metrics
                    override val renderCache: TerminalRenderCache get() = this@SwingTerminal.renderCache
                    override val session: TerminalInputEncoder? get() = this@SwingTerminal.session?.takeUnless { it.isClosed }

                    override fun mouseTrackingMode(): MouseTrackingMode =
                        MouseTrackingMode.entries[
                            TerminalInputState.mouseTrackingMode(
                                this@SwingTerminal.session?.takeUnless { it.isClosed }?.getInputModeBits() ?: 0L,
                            ),
                        ]

                    override fun pasteClipboardText() {
                        this@SwingTerminal.pasteClipboardText()
                    }

                    override fun encodeMouse(event: TerminalMouseEvent) {
                        this@SwingTerminal.session?.encodeMouse(event)
                    }

                    override fun cellAt(
                        x: Int,
                        y: Int,
                        cache: TerminalRenderCache,
                    ): Long = this@SwingTerminal.cellAt(x, y, cache)

                    override fun isPromptDividerAt(
                        x: Int,
                        y: Int,
                    ): Boolean = this@SwingTerminal.isPromptDividerAt(x, y)

                    override fun terminalPixelYAt(
                        y: Int,
                        cache: TerminalRenderCache,
                    ): Int = this@SwingTerminal.terminalPixelYAt(y, cache)

                    override fun visibleGridRows(): Int = this@SwingTerminal.visibleGridRows()

                    override fun scrollViewportByPreciseRows(deltaRows: Double): Boolean = viewportController.scrollByPreciseRows(deltaRows)

                    override fun finishViewportScroll() {
                        viewportController.finishScroll()
                    }

                    override fun requestFocusInWindow(): Boolean = this@SwingTerminal.requestFocusInWindow()

                    override fun handlePromptMarkerMousePressed(event: MouseEvent): Boolean =
                        this@SwingTerminal.handlePromptMarkerMousePressed(event)

                    override fun handlePromptMarkerMouseMoved(event: MouseEvent): Boolean =
                        this@SwingTerminal.handlePromptMarkerMouseMoved(event)

                    override fun handlePromptMarkerMouseExited() {
                        this@SwingTerminal.updateHoveredPromptMarker(NO_PROMPT_MARKER_ROW)
                    }

                    override fun handleContextMenuMouseEvent(
                        event: MouseEvent,
                        forcedByShift: Boolean,
                    ): Boolean =
                        this@SwingTerminal.handleContextMenuMouseEvent(
                            event = event,
                            forcedByShift = forcedByShift,
                        )

                    override fun handleHyperlinkMousePressed(event: MouseEvent): Boolean = hyperlinkController.handleMousePressed(event)

                    override fun handleHyperlinkMouseReleased(event: MouseEvent): Boolean = hyperlinkController.handleMouseReleased(event)

                    override fun handleHyperlinkMouseDragged() = hyperlinkController.handleMouseDragged()

                    override fun handleHyperlinkMouseMoved(
                        event: MouseEvent,
                        enabled: Boolean,
                    ) {
                        hyperlinkController.handleMouseMoved(event, enabled)
                    }

                    override fun handleHyperlinkMouseExited() {
                        hyperlinkController.handleMouseExited()
                    }

                    override fun clearHyperlinkHover() {
                        clearPointerHover(forgetPointer = false)
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
                chrome,
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
                    override val searchHighlights get() = this@SwingTerminal.searchController.viewportHighlights
                    override val componentWidth: Int get() = this@SwingTerminal.width
                    override val componentHeight: Int get() = this@SwingTerminal.height
                    override val terminalFocused: Boolean get() = this@SwingTerminal.terminalFocused

                    override fun resetCursorBlinkForFrame(): Boolean = resetCursorBlinkOnEdt()

                    override fun refreshRenderCacheFromSession(session: TerminalSession) {
                        this@SwingTerminal.refreshRenderCacheFromSession(session)
                    }

                    override fun requestRender(session: TerminalSession) {
                        this@SwingTerminal.requestRenderFromSession(session)
                    }

                    override fun clampViewport(
                        historySize: Int,
                        discardedCount: Long,
                    ): Boolean = viewportController.clamp(historySize, discardedCount, settings.scrollOnOutput)

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
                chrome,
            )
        internal val cursorTimer =
            Timer(cursorTimerDelay(settings)) {
                cursorBlinkVisible = !cursorBlinkVisible
                renderFrameController.repaintBlinkState()
            }

        private val resizeListener =
            object : ComponentAdapter() {
                override fun componentResized(event: ComponentEvent) {
                    if (resizeSessionToVisibleGridOnEdt()) {
                        session?.let(::requestRenderFromSession)
                    }
                }
            }

        private var ancestorWindow: Window? = null

        private val windowFocusListener =
            object : WindowAdapter() {
                override fun windowGainedFocus(event: WindowEvent) = reconcileHyperlinksOnEdt()

                override fun windowLostFocus(event: WindowEvent) = clearPointerHover()
            }

        private val windowStateListener =
            WindowStateListener { event ->
                val iconified = (event.newState and Frame.ICONIFIED) != 0
                session?.setWindowMinimized(iconified)
            }

        private fun updateMinimizedStateFromAncestor() {
            val window = ancestorWindow ?: SwingUtilities.getWindowAncestor(this)
            if (window is Frame) {
                val iconified = (window.extendedState and Frame.ICONIFIED) != 0
                session?.setWindowMinimized(iconified)
            }
        }

        private fun handlePromptMarkerMousePressed(event: MouseEvent): Boolean {
            if (!SwingUtilities.isLeftMouseButton(event)) return false
            if (renderCache.activeBuffer == TerminalRenderBufferKind.ALTERNATE) return false
            val row = promptMarkerRowAt(event.x, event.y)
            if (row == NO_PROMPT_MARKER_ROW) return false
            val recordId = shellIntegrationDecorations.commandRecordIdAt(row)
            if (recordId == TerminalShellIntegrationCommandRecord.NONE) return false
            commandInteractionController.selectCommandBlock(recordId)
            event.consume()
            return true
        }

        private fun handlePromptMarkerMouseMoved(event: MouseEvent): Boolean {
            if (renderCache.activeBuffer == TerminalRenderBufferKind.ALTERNATE) {
                updateHoveredPromptMarker(NO_PROMPT_MARKER_ROW)
                return false
            }
            val row = promptMarkerRowAt(event.x, event.y)
            updateHoveredPromptMarker(row)
            if (row != NO_PROMPT_MARKER_ROW) hyperlinkController.clearHyperlinkHover()
            return row != NO_PROMPT_MARKER_ROW
        }

        private fun promptMarkerRowAt(
            x: Int,
            y: Int,
        ): Int {
            val paddingLeft = chrome.left(settings, renderCache.activeBuffer)
            if (settings.promptDecoration == SwingPromptDecoration.DIVIDER) {
                val right = width - chrome.right(settings, renderCache.activeBuffer)
                if (x !in paddingLeft until right) return NO_PROMPT_MARKER_ROW
                return visualGeometry.dividerRowAtComponentY(y, chrome.top(settings, renderCache.activeBuffer))
            }
            val gutterWidth = chrome.promptDecorationGutterWidth(settings, renderCache.activeBuffer)
            if (gutterWidth <= 0 || x !in (paddingLeft - gutterWidth) until paddingLeft) {
                return NO_PROMPT_MARKER_ROW
            }
            val row = cellAt(x, y, renderCache).toInt()
            return if (shellIntegrationDecorations.hasPromptStartAt(row)) row else NO_PROMPT_MARKER_ROW
        }

        private fun isPromptDividerAt(
            x: Int,
            y: Int,
        ): Boolean = settings.promptDecoration == SwingPromptDecoration.DIVIDER && promptMarkerRowAt(x, y) != NO_PROMPT_MARKER_ROW

        private fun updateHoveredPromptMarker(row: Int) {
            if (hoveredPromptMarkerRow != row) {
                hoveredPromptMarkerRow = row
                repaint()
            }
            updatePointerCursor()
        }

        private fun clearPointerHover(forgetPointer: Boolean = true) {
            if (forgetPointer) mouseController.updatePointerModifiers(0)
            updateHoveredPromptMarker(NO_PROMPT_MARKER_ROW)
            hyperlinkController.clearHyperlinkHover(forgetPointer)
        }

        private fun refreshHyperlinkHover() {
            if (mouseController.isMouseTrackingIntercepted()) {
                hyperlinkController.clearHyperlinkHover(forgetPointer = false)
            } else {
                hyperlinkController.refreshHyperlinkHover()
            }
        }

        private fun updatePointerCursor() {
            val next = if (hoveredPromptMarkerRow != NO_PROMPT_MARKER_ROW) HAND_CURSOR else hyperlinkCursor
            if (cursor !== next) cursor = next
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
            addMouseListener(terminalMouseListener)
            addMouseMotionListener(terminalMouseMotionListener)
            addMouseWheelListener(mouseController.wheelListener)
            addComponentListener(resizeListener)
            addHierarchyListener { event ->
                if (event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L) {
                    if (isShowing) reconcileHyperlinksOnEdt() else clearPointerHover()
                }
            }
            preferredSize = preferredGridSize(settings.columns, settings.rows)
            cursorTimer.isRepeats = true
            configureCursorTimerOnEdt()
            configureVisualBellOnEdt()
        }

        /**
         * Binds rendering and input to the host-owned [session].
         *
         * Binding applies the current ambiguous-width policy, palette, cursor shape,
         * and paste policy to the session. Positive component bounds also resize
         * its grid and connector. Scrolling controls the session's single published
         * viewport. Prior session settings are not restored when unbinding.
         *
         * Rebinding cancels the previous view's observation and suggestion work;
         * it does not close either session. Calls on the EDT take effect immediately;
         * calls from other threads dispatch asynchronously to the EDT.
         *
         * @param session terminal session to display.
         */
        public fun bind(session: TerminalSession) {
            runOnEdt(
                Runnable {
                    if (disposed) return@Runnable
                    bindOnEdt(session)
                },
            )
        }

        /**
         * Removes the current session binding.
         *
         * Cancels view observation and suggestion work without closing the session
         * or restoring its previous settings. Calls on the EDT take effect immediately;
         * calls from other threads dispatch asynchronously to the EDT.
         * Cleanup completes before a callback failure or cancellation propagates on
         * the EDT; later cleanup failures are suppressed on the first failure.
         */
        public fun unbind() {
            runOnEdt(unbindRunnable)
        }

        /**
         * Permanently releases UI-side resources owned by this terminal component.
         *
         * Hosts should call this when a terminal tab is closed. The bound session,
         * if any, is unbound but remains host-owned. A disposed component must not
         * be rebound to another session. Calls on the EDT take effect immediately;
         * calls from other threads dispatch asynchronously to the EDT.
         * Every owned cleanup is attempted before a failure or cancellation propagates
         * on the EDT; later cleanup failures are suppressed on the first failure.
         */
        public fun dispose() {
            runOnEdt {
                disposeOnEdt()
            }
        }

        /**
         * Applies changed settings, rebuilding geometry only when its inputs change.
         *
         * Changed session-affecting settings are applied to the bound session;
         * unchanged palette and cursor settings preserve application-controlled values.
         * Geometry changes may resize its grid and connector. Calls on the EDT take
         * effect immediately; other calls dispatch asynchronously to the EDT.
         */
        public fun reloadSettings() {
            runOnEdt(
                Runnable {
                    if (disposed) return@Runnable
                    reloadSettingsOnEdt()
                },
            )
        }

        /**
         * Triggers this component's visual bell indicator.
         *
         * Hosts should call this when the bound terminal session emits BEL and
         * the current host settings allow visual bell presentation. The method
         * may be called from any thread; animation state is updated on the EDT.
         */
        public fun showVisualBell() {
            runOnEdt {
                visualBellController.trigger()
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
        public fun visibleGridSize(): Dimension {
            if (!SwingUtilities.isEventDispatchThread()) return viewportController.visibleGridSizeSnapshot()
            return viewportController.visibleGridSizeOnEdt(
                settings,
                metrics,
                width,
                height,
                renderCache.activeBuffer,
            )
        }

        /**
         * Returns the latest presentation scrollback viewport snapshot.
         *
         * This method may be called from any thread. EDT callers refresh the
         * snapshot from live component and render-cache state before reading it;
         * off-EDT callers copy one complete EDT publication under the short monitor
         * shared with publication, without dispatching to the EDT.
         *
         * @return current scrollback viewport state.
         */
        public fun viewportState(): TerminalViewportState {
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
        public fun scrollToLiveViewport() {
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
        public fun scrollToScrollbackOffset(scrollbackOffset: Int) {
            runOnEdt {
                scrollViewportToOnEdt(scrollbackOffset)
            }
        }

        /**
         * Applies a signed scrollback delta in presentation slots.
         *
         * Positive values move farther back into scrollback; negative values move
         * toward the live viewport. Fractional values accumulate until they
         * produce a whole-row destination. Animation may render between rows,
         * but always completes on the destination row.
         *
         * Divider mode counts a prompt band as one slot, alongside text rows.
         * @param deltaLines signed slot delta.
         */
        public fun scrollViewportBy(deltaLines: Double) {
            require(deltaLines.isFinite()) { "deltaLines must be finite, was $deltaLines" }
            runOnEdt {
                viewportController.scrollByPreciseRows(deltaLines)
            }
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
            runOnEdt {
                scrollFromScrollbarOnEdt(scrollbackOffset, valueIsAdjusting)
            }
        }

        private fun scrollFromScrollbarOnEdt(
            scrollbackOffset: Int,
            valueIsAdjusting: Boolean,
        ) {
            if (valueIsAdjusting) {
                viewportController.jumpToRow(scrollbackOffset)
            } else {
                viewportController.scrollToRow(scrollbackOffset)
            }
        }

        /**
         * Scrolls to the nearest previous shell command.
         *
         * The component uses the selected integration's command metadata and reveals
         * the command's prompt-start line when present, otherwise its command
         * start line. This method may be called from any thread; component state
         * is updated asynchronously on the EDT.
         */
        public fun scrollToPreviousCommand() {
            runOnEdt {
                commandInteractionController.scrollToCommand(previous = true)
            }
        }

        /**
         * Scrolls to the nearest next shell command.
         *
         * The component uses the selected integration's command metadata and reveals
         * the command's prompt-start line when present, otherwise its command
         * start line. This method may be called from any thread; component state
         * is updated asynchronously on the EDT.
         */
        public fun scrollToNextCommand() {
            runOnEdt {
                commandInteractionController.scrollToCommand(previous = false)
            }
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
        public fun commandRecordAt(
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
        public fun selectCommandOutputAt(
            x: Int,
            y: Int,
        ): Boolean = SwingUtilities.isEventDispatchThread() && commandInteractionController.selectCommandOutputAt(x, y)

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
        public fun selectCommandOutput(recordId: Int): Boolean =
            SwingUtilities.isEventDispatchThread() && commandInteractionController.selectCommandOutput(recordId)

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
        public fun commandOutputText(recordId: Int): String? {
            if (!SwingUtilities.isEventDispatchThread()) return null
            return commandInteractionController.commandOutputText(recordId)
        }

        /**
         * Copies all retained output for [recordId] through the host clipboard service.
         *
         * @param recordId retained command record id.
         * @return `true` when retained output was copied; `false` when unavailable or called off the EDT.
         */
        public fun copyCommandOutputToClipboard(recordId: Int): Boolean {
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
        public fun copyCommandTextToClipboard(recordId: Int): Boolean {
            if (!SwingUtilities.isEventDispatchThread()) return false
            val text = session?.shellIntegrationState?.commandText(recordId) ?: return false
            hostServices.clipboardHandler.copyText(text)
            return true
        }

        override fun addNotify() {
            super.addNotify()
            if (disposed) return
            terminalFocused = isFocusOwner
            configureCursorTimerOnEdt()

            val window = SwingUtilities.getWindowAncestor(this)
            if (window != null) {
                ancestorWindow = window
                window.addWindowStateListener(windowStateListener)
                window.addWindowFocusListener(windowFocusListener)
                updateMinimizedStateFromAncestor()
            }
            reconcileHyperlinksOnEdt()
        }

        override fun removeNotify() {
            terminalFocused = false
            cleanupSwingResources(
                cursorTimer::stop,
                visualBellController::stop,
                viewportController::finishScroll,
                selectionController::stopSelectionDrag,
                mouseController::resetInput,
                { clearPointerHover() },
                ::detachAncestorWindow,
                { super.removeNotify() },
            )
        }

        override fun doLayout() {
            super.doLayout()
            layoutShellSuggestionPopup()
        }

        private fun layoutShellSuggestionPopup() {
            val controller = shellSuggestionController ?: return
            val popup = controller.popup
            val state = controller.state()
            if (!state.visible) {
                popup.setBounds(0, 0, 0, 0)
                return
            }

            val preferred = popup.preferredSize
            val activeBuffer = renderCache.activeBuffer
            val paddingLeft = chrome.left(settings, activeBuffer)
            val paddingRight = chrome.right(settings, activeBuffer)
            val paddingTop = chrome.top(settings, activeBuffer)
            val availableWidth = width - paddingLeft - paddingRight
            val popupWidth = minOf(availableWidth, preferred.width).coerceAtLeast(0)
            val paddingBottom = chrome.bottom(settings, activeBuffer)
            val availableHeight = (height - paddingTop - paddingBottom).coerceAtLeast(0)
            if (popupWidth == 0 || availableHeight == 0 || preferred.height <= 0) {
                popup.setBounds(0, 0, 0, 0)
                return
            }

            val contentOriginY = if (visualGeometry.rowCount == renderCache.rows) visualGeometry.contentOriginY else 0.0
            val anchorColumn =
                if (state.anchorColumn in 0 until renderCache.columns) {
                    visualGeometry.bidiLayout.row(renderCache, state.anchorRow)?.visualColumn(state.anchorColumn) ?: state.anchorColumn
                } else {
                    state.anchorColumn
                }
            val hasCellBounds = copyCellBounds(state.anchorColumn, state.anchorRow, suggestionAnchorBounds)
            // Explicit host context also works without a frame; keep its grid-coordinate fallback.
            val anchorX =
                if (hasCellBounds) {
                    suggestionAnchorBounds.x
                } else {
                    (paddingLeft.toLong() + anchorColumn.toLong() * metrics.cellWidth)
                        .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
                        .toInt()
                }
            val bottomLimit = height - paddingBottom
            val fallbackRowTop =
                if (visualGeometry.rowCount > 0) {
                    visualGeometry.rowTop(state.anchorRow).toDouble()
                } else {
                    state.anchorRow.toDouble() * metrics.cellHeight
                }
            val anchorTop =
                if (hasCellBounds) {
                    suggestionAnchorBounds.y
                } else {
                    floor(paddingTop + contentOriginY + fallbackRowTop).toInt().coerceIn(paddingTop, bottomLimit)
                }
            val anchorBottom =
                if (hasCellBounds) {
                    suggestionAnchorBounds.y + suggestionAnchorBounds.height
                } else {
                    ceil(paddingTop + contentOriginY + fallbackRowTop + metrics.cellHeight).toInt().coerceIn(paddingTop, bottomLimit)
                }
            val spaceAbove = anchorTop - paddingTop
            val spaceBelow = bottomLimit - anchorBottom
            val placeBelow = preferred.height <= spaceBelow || spaceBelow >= spaceAbove
            val popupHeight = minOf(preferred.height, if (placeBelow) spaceBelow else spaceAbove)
            val popupY = if (placeBelow) anchorBottom else anchorTop - popupHeight
            val popupX = anchorX.coerceIn(paddingLeft, maxOf(paddingLeft, width - paddingRight - popupWidth))
            popup.setBounds(popupX, popupY, popupWidth, popupHeight)
        }

        override fun paintComponent(graphics: Graphics) {
            super.paintComponent(graphics)

            val g = graphics.create() as Graphics2D
            try {
                if (!renderCache.hasFrame) {
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
                    terminalFocused = terminalFocused,
                    cursorBlinkVisible = cursorBlinkVisible,
                    textBlinkVisible = cursorBlinkVisible,
                    visualGeometry = visualGeometry,
                    selection = selectionController.getViewportSelection(renderCache),
                    searchHighlights = searchController.viewportHighlights,
                    shellIntegrationDecorations = shellIntegrationDecorations,
                    hoveredPromptMarkerRow = hoveredPromptMarkerRow,
                    hyperlinkIds = hyperlinkDiscoveryController.hyperlinkIdsFor(renderCache),
                    hyperlinkHover = hyperlinkController.hover,
                    hyperlinkPresentations = hyperlinkDiscoveryController.hyperlinkPresentationsFor(renderCache),
                    followedHyperlinkId = hyperlinkDiscoveryController.followedHyperlinkId,
                )
                if (hostServices.scrollbarOverlayEnabled) {
                    scrollbarOverlay.paint(
                        g = g,
                        settings = settings,
                        activeBuffer = renderCache.activeBuffer,
                        palette = renderCache.palette,
                        componentWidth = width,
                        componentHeight = height,
                        historySize = viewportController.historySize,
                        visualScrollOffsetPixels = viewportController.visualScrollOffsetPixels,
                        visualScrollRangePixels = viewportController.visualScrollRangePixels,
                        viewportHeightPixels = viewportController.viewportHeightPixels,
                    )
                }
                visualBellController.paint(g, width, height)
            } finally {
                g.dispose()
            }
        }

        private fun bindOnEdt(session: TerminalSession) =
            selectionController.deferChanges {
                suggestionScheduler?.stop()
                if (disposed) return@deferChanges
                bindingJob?.cancel(CancellationException("Terminal session binding replaced"))
                mouseController.resetInput()
                this.session = session
                chrome.promptDecorationsAvailable = session.promptMarkersExpected || session.shellIntegrationState.recordCount() > 0
                retainedViewport = null
                resetRenderCaches()
                updateMinimizedStateFromAncestor()
                applySettingsToSession(session, settings)
                resetScrollbackState()
                selectionController.resetBinding()
                searchController.reset(renderCache.rows)
                cancelAndHideShellSuggestionsOnEdt("Terminal session binding replaced")
                shellIntegrationDecorations.reset()
                if (hostServices.scrollbarOverlayEnabled) scrollbarOverlay.handleExited()
                visualGeometry.reset()
                selectionController.stopSelectionDrag()
                visualBellController.stop()
                lastResizedColumns = NO_RESIZE_DIMENSION
                lastResizedRows = NO_RESIZE_DIMENSION
                renderFrameController.reset()
                clearPointerHover()
                hyperlinkDiscoveryController.reset()
                resizeSessionToVisibleGridOnEdt()
                bindingJob =
                    componentScope.launch {
                        launch {
                            session.state.filterIsInstance<TerminalSessionState.Closed>().take(1).collect {
                                if (this@SwingTerminal.session === session) {
                                    suggestionScheduler?.stop()
                                    cancelAndHideShellSuggestionsOnEdt("Terminal session closed")
                                    mouseController.resetInput()
                                    renderFrameController.handlePublishedFrame()
                                }
                            }
                        }
                        launch {
                            session.renderGeneration
                                .filter { it >= 0L }
                                .collect {
                                    if (this@SwingTerminal.session === session) {
                                        renderFrameController.handlePublishedFrame()
                                    }
                                }
                        }
                        combine(session.shellIntegrationState.revision, session.state) { _, state -> state }
                            .takeWhile { it !is TerminalSessionState.Closed }
                            .collect {
                                // Host metadata can be published inside a borrowed frame callback.
                                // Defer source reads or gutter-triggered resize until the borrowed frame is released.
                                if (settings.promptDecoration == SwingPromptDecoration.DIVIDER ||
                                    !chrome.promptDecorationsAvailable &&
                                    session.shellIntegrationState.recordCount() > 0
                                ) {
                                    yield()
                                }
                                if (this@SwingTerminal.session !== session || session.isClosed || !renderCache.hasFrame) return@collect
                                if (refreshShellIntegrationDecorations(session)) {
                                    if (hoveredPromptMarkerRow != NO_PROMPT_MARKER_ROW &&
                                        !shellIntegrationDecorations.hasPromptStartAt(hoveredPromptMarkerRow)
                                    ) {
                                        updateHoveredPromptMarker(NO_PROMPT_MARKER_ROW)
                                    }
                                    if (renderCache.scrollbackOffset !=
                                        viewportController.requestedOffset
                                    ) {
                                        requestRenderFromSession(session)
                                    }
                                    publishViewportState(renderCache.historySize)
                                    renderFrameController.repaintFrame(forceFullRepaint = true)
                                }
                            }
                    }
                var publishedFrameAvailable = false
                session.readPublishedFrame {
                    publishedFrameAvailable = true
                }
                if (publishedFrameAvailable) {
                    renderFrameController.handlePublishedFrame()
                }
                requestRenderFromSession(session)
                reconcileHyperlinksOnEdt()
                publishViewportState(renderCache.historySize)
                reconcileSuggestionSchedulerOnEdt()
                repaint()
            }

        private fun unbindOnEdt() =
            selectionController.deferChanges {
                cleanupSwingResources(
                    { suggestionScheduler?.stop() },
                    {
                        val job = bindingJob
                        bindingJob = null
                        job?.cancel(CancellationException("Terminal session unbound"))
                    },
                    mouseController::resetInput,
                    { cancelAndHideShellSuggestionsOnEdt("Terminal session unbound") },
                    {
                        session = null
                        chrome.promptDecorationsAvailable = true
                        retainedViewport = null
                        resetRenderCaches()
                    },
                    ::resetScrollbackState,
                    selectionController::resetBinding,
                    { searchController.reset(renderCache.rows) },
                    shellIntegrationDecorations::reset,
                    { if (hostServices.scrollbarOverlayEnabled) scrollbarOverlay.handleExited() },
                    visualGeometry::reset,
                    selectionController::stopSelectionDrag,
                    {
                        lastResizedColumns = NO_RESIZE_DIMENSION
                        lastResizedRows = NO_RESIZE_DIMENSION
                        renderFrameController.reset()
                    },
                    { clearPointerHover() },
                    hyperlinkDiscoveryController::reset,
                    { publishViewportState(0) },
                    ::repaint,
                )
            }

        private fun disposeOnEdt() {
            if (disposed) return
            disposed = true
            automaticSuggestionEligibilityRevision++
            cleanupSwingResources(
                ::unbindOnEdt,
                ::detachAncestorWindow,
                cursorTimer::stop,
                visualBellController::stop,
                viewportController::finishScroll,
                selectionController::stopSelectionDrag,
                hyperlinkDiscoveryController::dispose,
                {
                    cancelAndHideShellSuggestionsOnEdt("Swing terminal disposed", SwingShellSuggestionCloseReason.DISPOSED)
                },
                selectionController::removeListeners,
                suggestionInvalidationListeners::clear,
                suggestionEligibilityListeners::clear,
                {
                    shellSuggestionProvider = null
                    shellSuggestionTarget = null
                    suggestionScheduler = null
                },
                { suggestionFailureHandler = SwingShellSuggestionFailureHandler.LOGGING },
                {
                    val controller = shellSuggestionController
                    shellSuggestionController = null
                    controller?.close()
                },
                { componentScope.cancel(CancellationException("Swing terminal disposed")) },
            )
        }

        private fun reloadSettingsOnEdt() {
            if (disposed) return
            val previous = settings
            val next = settingsProvider.currentSettings()
            if (next == previous) return

            val nextMetrics =
                if (next.font != previous.font || next.lineHeight != previous.lineHeight || next.columnSpacing != previous.columnSpacing) {
                    buildMetrics(next)
                } else {
                    metrics
                }
            if (nextMetrics != metrics) {
                gridPixelExtent(renderCache.columns, nextMetrics.cellWidth, 0)
            }
            val geometryChanged =
                nextMetrics != metrics ||
                    next.padding != previous.padding ||
                    next.alternateScreenPadding != previous.alternateScreenPadding ||
                    next.shellIntegrationDecorationGutterWidth != previous.shellIntegrationDecorationGutterWidth ||
                    next.promptDecoration != previous.promptDecoration
            if (geometryChanged) viewportController.finishScroll()
            settings = next
            metrics = nextMetrics
            if (next.font != previous.font) font = next.font
            if (next.palette != previous.palette) {
                background = Color(next.palette.defaultBackground, true)
                foreground = Color(next.palette.defaultForeground, true)
            }
            if (next.cursorBlinkMillis != previous.cursorBlinkMillis) configureCursorTimerOnEdt()
            if (next.visualBellEnabled != previous.visualBellEnabled ||
                next.visualBellColor != previous.visualBellColor ||
                next.visualBellDurationMillis != previous.visualBellDurationMillis ||
                next.visualBellEdgeThicknessPixels != previous.visualBellEdgeThicknessPixels
            ) {
                configureVisualBellOnEdt()
            }
            session?.let { applySettingsToSession(it, next, previous) }
            if (geometryChanged) {
                session?.let { refreshShellIntegrationDecorations(it) }
                resizeSessionToVisibleGridOnEdt()
                searchController.updateViewportHighlights()
                clearPointerHover()
                hyperlinkDiscoveryController.reconcile()
                session?.let { requestRenderFromSession(it) }
            }
            if (geometryChanged || next.columns != previous.columns || next.rows != previous.rows) {
                preferredSize = preferredGridSize(next.columns, next.rows)
                revalidate()
            }
            updateAutomaticSuggestionEligibilityOnEdt()
            reconcileSuggestionSchedulerOnEdt()
            if (next.osc8HyperlinkPresentation != previous.osc8HyperlinkPresentation ||
                next.mouseReportingEnabled != previous.mouseReportingEnabled ||
                next.osc8HyperlinkActivation != previous.osc8HyperlinkActivation
            ) {
                reconcileHyperlinksOnEdt()
            }
            repaint()
        }

        /**
         * Returns the current visible cell selection, or `null` when nothing is
         * selected. Linear selection columns are logical; block selection columns
         * are visual, as defined by [CellSelection].
         *
         * The returned snapshot is immutable and may be retained. Unchanged values
         * may share an instance; object identity is not part of this API's contract.
         *
         * This method may be called from any thread. Off-EDT callers wait for the
         * EDT to read the binding, selection, and viewport together. EDT callers
         * read directly.
         *
         * @return current selection, or `null`.
         */
        public fun currentSelection(): CellSelection? {
            if (!SwingUtilities.isEventDispatchThread()) {
                var selection: CellSelection? = null
                SwingUtilities.invokeAndWait { selection = currentSelection() }
                return selection
            }
            if (!renderCache.hasFrame) return null
            return selectionController.getViewportSelection(renderCache)
        }

        /**
         * Returns the complete retained selection on the EDT, or null.
         *
         * This describes the last applied frame and includes eviction observed there.
         * The immutable range includes offscreen rows and may be saved for restoration.
         * Unchanged ranges reuse their snapshot. Object identity is not an API guarantee.
         * Unbound and disposed components return null.
         *
         * @throws IllegalStateException outside the EDT.
         */
        public fun currentSelectionRange(): TerminalSelectionRange? {
            check(SwingUtilities.isEventDispatchThread()) { "selection access requires the EDT" }
            if (disposed || session == null) return null
            return selectionController.currentRange()
        }

        /**
         * Returns the complete retained selection's text on the EDT, or null when unavailable.
         *
         * Includes offscreen rows and closed-session output. Reads current session content,
         * joining soft-wrapped rows for linear selections and preserving row breaks for
         * block selections. Wide cells and grapheme clusters are copied completely.
         * A nonempty selection may return an empty string when only trimmed blanks remain.
         * Unbound and disposed components return null.
         *
         * Uses the same extraction as [copySelectionToClipboard] without accessing the
         * clipboard, scrolling, focusing, or sending input. Eviction may clip selection;
         * invalidated layouts or buffer changes clear it. Resulting selection changes
         * notify listeners outside the frame lease. The returned string may be retained.
         *
         * @throws IllegalStateException outside the EDT.
         */
        public fun selectedText(): String? {
            check(SwingUtilities.isEventDispatchThread()) { "selection access requires the EDT" }
            if (disposed) return null
            val boundSession = session ?: return null
            return selectionController.getSelectedText(boundSession)
        }

        /**
         * Creates a range in the current binding and layout without changing selection.
         *
         * Use absolute retained rows and half-open cell edges, as defined by
         * [TerminalSelectionRange]. Both endpoint rows must remain retained.
         * Returns null for unavailable bindings, pending layout changes, or coordinates beyond current bounds.
         * Empty ranges are valid and clear selection when applied.
         * This reads metadata only; it does not scroll, focus, or send input.
         *
         * @throws IllegalArgumentException for negative coordinates.
         * @throws IllegalStateException outside the EDT or after disposal.
         */
        @JvmOverloads
        public fun createSelectionRange(
            anchorColumn: Int,
            anchorAbsoluteRow: Long,
            caretColumn: Int,
            caretAbsoluteRow: Long,
            isBlock: Boolean = false,
        ): TerminalSelectionRange? {
            checkSelectionAccess()
            require(anchorColumn >= 0 && caretColumn >= 0) { "selection columns must be nonnegative" }
            require(anchorAbsoluteRow >= 0 && caretAbsoluteRow >= 0) { "selection rows must be nonnegative" }
            val boundSession = session ?: return null
            var range: TerminalSelectionRange? = null
            boundSession.readRenderFrame { frame ->
                range = selectionController.createRange(frame, anchorColumn, anchorAbsoluteRow, caretColumn, caretAbsoluteRow, isBlock)
            }
            return range
        }

        /**
         * Sets or restores a complete range on the EDT.
         *
         * Returns false for another binding, an invalidated layout, or evicted endpoints.
         * Validation and application share one frame lease. Rejection does not apply
         * the requested range or change the current selection.
         * Successful assignment ends mouse dragging without scrolling, focusing,
         * sending input, or copying text. Closed sessions support this operation.
         *
         * @throws IllegalStateException outside the EDT or after disposal.
         */
        public fun setSelection(range: TerminalSelectionRange): Boolean {
            checkSelectionAccess()
            val boundSession = session ?: return false
            var accepted = false
            boundSession.readRenderFrame { frame ->
                accepted = selectionController.setRange(range, frame)
            }
            selectionController.publishChange()
            return accepted
        }

        /**
         * Clears selection and stops mouse dragging on the EDT.
         *
         * This leaves terminal content, input, viewport, focus, and clipboard unchanged.
         * Repeated clearing and clearing an unbound component are harmless.
         *
         * @throws IllegalStateException outside the EDT or after disposal.
         */
        public fun clearSelection() {
            checkSelectionAccess()
            selectionController.clearSelection()
        }

        /**
         * Registers a synchronous EDT observer without an initial callback.
         *
         * Registering the same listener instance twice has no effect.
         * Listeners survive rebinding and are released on disposal, after the final
         * selection clear. See [TerminalSelectionListener] for delivery semantics.
         *
         * @throws IllegalStateException outside the EDT or after disposal.
         */
        public fun addSelectionListener(listener: TerminalSelectionListener) {
            checkSelectionAccess()
            selectionController.addListener(listener)
        }

        /**
         * Removes a selection observer on the EDT.
         *
         * Removal is harmless when absent or after disposal.
         * @throws IllegalStateException outside the EDT.
         */
        public fun removeSelectionListener(listener: TerminalSelectionListener) {
            check(SwingUtilities.isEventDispatchThread()) { "selection access requires the EDT" }
            selectionController.removeListener(listener)
        }

        private fun checkSelectionAccess() {
            check(SwingUtilities.isEventDispatchThread()) { "selection access requires the EDT" }
            check(!disposed) { "Swing terminal is disposed" }
        }

        /**
         * Copies a displayed cell's visible bounds into caller-owned storage on the EDT.
         *
         * Coordinates are zero-based logical columns and rows of the displayed frame,
         * before bidi permutation. The result uses component-local Swing pixels and
         * includes the active buffer's padding, prompt gutter, and fractional scrolling.
         * Partially visible cells return clipped bounds. Invalid coordinates, unavailable
         * frames, unbound/disposed views, and cells outside the content viewport return
         * `false` and clear [destination]. This performs no refresh or transport work.
         * Hosts convert these bounds to screen coordinates when positioning native popups.
         * Each leading/trailing half of wide text describes one physical grid cell.
         *
         * @throws IllegalStateException when called outside the EDT.
         */
        public fun copyCellBounds(
            column: Int,
            row: Int,
            destination: Rectangle,
        ): Boolean {
            check(SwingUtilities.isEventDispatchThread()) { "cell bounds must be read on the EDT" }
            if (disposed || session == null) {
                destination.setBounds(0, 0, 0, 0)
                return false
            }
            updateChromeLayout()
            val buffer = renderCache.activeBuffer
            return visualGeometry.copyCellBounds(
                renderCache,
                metrics,
                column,
                row,
                chrome.left(settings, buffer),
                chrome.top(settings, buffer),
                width - chrome.right(settings, buffer),
                height - chrome.bottom(settings, buffer),
                destination,
            )
        }

        /**
         * Copies the displayed cell under component-local Swing pixels [x], [y]
         * into caller-owned [destination] on the EDT.
         *
         * The point's x is a zero-based logical column, before bidi permutation;
         * its y is a zero-based row of the displayed frame, matching [copyCellBounds].
         * Rows are not absolute retained-history rows or live-screen coordinates.
         * The position describes the currently displayed frame and can change after
         * scrolling, frame publication, or layout changes.
         *
         * Active-buffer padding, prompt gutters/dividers, fractional scrolling and
         * clipping follow the shared display geometry. Wide leading and trailing
         * halves return their own physical grid cells; blank cells are valid hits.
         * Fractionally rounded adjacent cell bounds may share a boundary pixel;
         * row selection follows the same fractional mapping as pointer input.
         *
         * Returns `false` and sets [destination] to `(-1, -1)` outside displayed
         * cells, including decoration bands, or for unavailable frames and
         * unbound/disposed views. Points are never clamped to a nearby cell.
         * Reuse one [Point] across calls; no result object is allocated. Existing
         * bidi caches may rebuild for changed text. This performs no frame refresh
         * or transport work.
         *
         * @return `true` when [destination] contains a displayed physical cell.
         * @throws IllegalStateException when called outside the EDT.
         */
        public fun copyCellPositionAt(
            x: Int,
            y: Int,
            destination: Point,
        ): Boolean {
            check(SwingUtilities.isEventDispatchThread()) { "cell positions must be read on the EDT" }
            if (disposed || session == null) {
                destination.setLocation(-1, -1)
                return false
            }
            updateChromeLayout()
            val buffer = renderCache.activeBuffer
            return visualGeometry.copyCellPositionAt(
                renderCache,
                metrics,
                x,
                y,
                chrome.left(settings, buffer),
                chrome.top(settings, buffer),
                width - chrome.right(settings, buffer),
                height - chrome.bottom(settings, buffer),
                destination,
            )
        }

        /**
         * Selects provider diagnostics for this view on the EDT; null restores logging.
         *
         * Reports each current provider failure once, after cancelling its work and
         * hiding suggestions. Cancellation and obsolete requests are excluded.
         * The callback runs on the EDT and may start a new request. Disposal releases
         * it; rebinding retains the view-owned handler. Callback failures are logged,
         * while callback cancellation propagates.
         *
         * @throws IllegalStateException when called outside the EDT or after disposal.
         */
        public fun setShellSuggestionFailureHandler(handler: SwingShellSuggestionFailureHandler?) {
            check(SwingUtilities.isEventDispatchThread()) { "suggestion diagnostics must be configured on the EDT" }
            check(!disposed) { "Swing terminal is disposed" }
            suggestionFailureHandler = handler ?: SwingShellSuggestionFailureHandler.LOGGING
        }

        /**
         * Selects all retained terminal text.
         *
         * The selection spans available scrollback and the live render grid. It
         * cannot include rows already discarded by the configured scrollback
         * capacity.
         *
         * @return `true` when a bound session and non-empty render cache allowed
         * a selection to be created.
         */
        public fun selectAll(): Boolean {
            if (!SwingUtilities.isEventDispatchThread()) return false
            if (!renderCache.hasFrame) return false
            val firstAbsoluteRow = renderCache.discardedCount
            var lastAbsoluteRow = renderCache.discardedCount + renderCache.historySize + renderCache.rows - 1L
            if (session?.state?.value is TerminalSessionState.Closed) {
                session?.readRenderFrame {
                    lastAbsoluteRow = it.discardedCount + it.historySize + it.rows - 1L
                }
            }
            selectionController.selectAbsoluteRows(firstAbsoluteRow, lastAbsoluteRow, renderCache.columns)
            repaint()
            return true
        }

        /**
         * Schedules a cancellable background literal search. This does not wait for results.
         *
         * The search covers retained scrollback plus the live grid snapshot exposed
         * through the bound session's render-frame reader. Hosts own any visible
         * search UI and call this method when their query changes.
         * Observe [searchState] for completion or failure. Results are refreshed in
         * completed passes while output continues, not an atomic snapshot of all history.
         *
         * @param query literal text to find.
         */
        public fun search(query: String) {
            runOnEdt {
                searchController.search(query)
            }
        }

        /**
         * Clears the current terminal-buffer search query and highlights.
         */
        public fun clearSearch() {
            runOnEdt {
                searchController.clear()
            }
        }

        /**
         * Selects the next search result when a search query is active.
         * While searching, navigates the last completed pass; does nothing when no results are available.
         */
        public fun selectNextSearchResult() {
            runOnEdt {
                searchController.findNext()
            }
        }

        /**
         * Selects the previous search result when a search query is active.
         * While searching, navigates the last completed pass; does nothing when no results are available.
         */
        public fun selectPreviousSearchResult() {
            runOnEdt {
                searchController.findPrevious()
            }
        }

        /**
         * Configures whether terminal-buffer search is case-sensitive.
         *
         * @param caseSensitive `true` to match case exactly, `false` for
         * case-insensitive search.
         */
        public fun setSearchCaseSensitive(caseSensitive: Boolean) {
            runOnEdt {
                searchController.setIgnoreCase(!caseSensitive)
            }
        }

        /**
         * Returns the current search result snapshot.
         *
         * Must be called on the EDT: this reads live search-controller state
         * synchronously. The returned immutable value may be retained or passed
         * to another thread.
         *
         * @return current terminal search state.
         */
        public fun currentSearchState(): TerminalSearchState = searchController.state()

        /**
         * Immutable search state, safe to observe from any thread. UI collectors must
         * use the EDT and cancel collection when their view closes. Counts describe
         * the last completed pass; [TerminalSearchState.isSearching] marks pending work.
         * Clearing or unbinding cancels obsolete work; disposing cancels the worker.
         */
        public val searchState: StateFlow<TerminalSearchState> get() = searchController.states

        /** Whether a configured suggestion provider is available. Read on the EDT. */
        public val hasShellSuggestionProvider: Boolean
            get() {
                check(SwingUtilities.isEventDispatchThread()) { "suggestion configuration must be read on the EDT" }
                return shellSuggestionProvider != null
            }

        /**
         * Installs a host-owned provider or disables configured suggestions with null.
         * Call on the EDT, before or after binding. Replacement cancels existing requests
         * without closing either provider. The provider survives unbinding and rebinding;
         * host metadata must describe the current session when [SwingShellSuggestionProvider.open] captures it.
         * Automatic observation runs only while a session, provider or custom target, and
         * the automatic-suggestion setting are available. Explicit custom interactions remain independent.
         */
        public fun setShellSuggestionProvider(provider: SwingShellSuggestionProvider?) {
            check(SwingUtilities.isEventDispatchThread()) { "suggestion providers must be configured on the EDT" }
            if (disposed) return
            if (shellSuggestionProvider === provider) {
                reconcileSuggestionSchedulerOnEdt()
                return
            }
            shellSuggestionProvider = provider
            val revision = ++suggestionConfigurationRevision
            suggestionScheduler?.stop()
            cancelAndHideShellSuggestionsOnEdt("Shell suggestion provider replaced")
            if (!disposed && suggestionConfigurationRevision == revision) reconcileSuggestionSchedulerOnEdt()
        }

        /**
         * Routes automatic captured interactions to a host-owned surface; null selects embedded presentation.
         * Call on the EDT. Replacement cancels pending requests and hides the old surface.
         * The host owns target resources. Explicit interaction and presentation methods are unaffected.
         */
        public fun setShellSuggestionTarget(target: SwingShellSuggestionTarget?) {
            check(SwingUtilities.isEventDispatchThread()) { "suggestion targets must be configured on the EDT" }
            if (disposed || shellSuggestionTarget === target) return
            val previous = shellSuggestionTarget
            shellSuggestionTarget = target
            val revision = ++suggestionConfigurationRevision
            suggestionScheduler?.stop()
            val cancellationRevision = suggestionRevision + 1
            cleanupSwingResources(
                { cancelAndHideShellSuggestionsOnEdt("Shell suggestion target replaced") },
                { hideCapturedSuggestionTarget(previous, cancellationRevision) },
            )
            if (!disposed && suggestionConfigurationRevision == revision) reconcileSuggestionSchedulerOnEdt()
        }

        /**
         * Invalidates automatic request deduplication after host ranking context changes.
         * Call on the EDT. The next eligible automatic request runs after the normal debounce interval.
         */
        public fun refreshShellSuggestions() {
            check(SwingUtilities.isEventDispatchThread()) { "suggestions must refresh on the EDT" }
            if (!disposed) suggestionScheduler?.refresh()
        }

        private fun reconcileSuggestionSchedulerOnEdt() {
            val bound = session
            if (disposed ||
                bound == null ||
                bound.isClosed ||
                !settings.smartSuggestionsEnabled ||
                !settings.shellSuggestionsEnabled ||
                shellSuggestionProvider == null &&
                shellSuggestionTarget == null
            ) {
                suggestionScheduler?.stop()
                return
            }
            val scheduler =
                suggestionScheduler ?: SwingShellSuggestionScheduler(
                    observationScope = componentScope,
                    edtDispatcher = suggestionDispatcher ?: uiCoroutineDispatcher,
                    isFocused = { terminalFocused },
                    isEligible = { isAutomaticShellSuggestionEligible() },
                    requestSuggestions = { _, feedback ->
                        val provider = shellSuggestionProvider
                        val target = shellSuggestionTarget
                        val interaction = beginActiveShellSuggestionInteraction(SwingShellSuggestionTrigger.AUTOMATIC, feedback)
                        if (interaction != null) {
                            if (target != null) {
                                try {
                                    target.requestSuggestions(interaction)
                                } catch (failure: Throwable) {
                                    try {
                                        interaction.close()
                                    } catch (cleanupFailure: Throwable) {
                                        preserveSwingFailure(failure, cleanupFailure)
                                    }
                                    throw failure
                                }
                            } else if (provider != null) {
                                presentShellSuggestions(interaction)
                                requestShellSuggestions(interaction, provider)
                            }
                        }
                    },
                    hideSuggestions = {
                        if (activeSuggestionIsAutomatic) cancelAndHideShellSuggestionsOnEdt("Automatic shell suggestions hidden")
                    },
                ).also { suggestionScheduler = it }
            scheduler.start(bound)
        }

        /**
         * Captures one command-line editing capability before asynchronous provider work.
         *
         * Call on the EDT. The returned interaction is independent of popup mounting: hosts can
         * publish their own results or use [requestShellSuggestions] with any Swing/IntelliJ UI.
         * A new request supersedes the previous interaction. Input, session rebinding, disposal,
         * and ineligible viewport/settings close it. Null means the request or editing target is
         * unavailable. Source completion does not invalidate a displayed final publication.
         * By default editing is captured from the bound session; an unbound view is display-only.
         * Supply [editTarget] for a host editor, or [SwingShellSuggestionEditTarget.NONE] for display-only results.
         */
        @JvmOverloads
        public fun beginShellSuggestionInteraction(
            request: SwingShellSuggestionRequest,
            trigger: SwingShellSuggestionTrigger = SwingShellSuggestionTrigger.EXPLICIT,
            feedbackHandler: SwingShellSuggestionFeedbackHandler = SwingShellSuggestionFeedbackHandler.NONE,
            editTarget: SwingShellSuggestionEditTarget? = null,
        ): SwingShellSuggestionInteraction? {
            check(SwingUtilities.isEventDispatchThread()) { "suggestion capture must run on the EDT" }
            val automatic = trigger == SwingShellSuggestionTrigger.AUTOMATIC
            if (!prepareShellSuggestionRequestOnEdt(automatic)) return null
            val bound = session
            val observed =
                bound?.activeShellCommandLine()?.takeIf {
                    it.commandText == request.commandText && it.cursorOffset == request.cursorOffset
                }
            val revision = suggestionRevision + 1
            cancelAndHideShellSuggestionsOnEdt("Shell suggestion request replaced", SwingShellSuggestionCloseReason.SUPERSEDED)
            if (suggestionRevision != revision || session !== bound || !prepareShellSuggestionRequestOnEdt(automatic)) return null
            val captured =
                when {
                    editTarget != null -> editTarget.capture(request)
                    bound != null -> SwingShellSuggestionEditTarget.captureSessionEdit(bound, request)
                    else -> SwingShellSuggestionHandler.NONE
                } ?: return null
            if (disposed ||
                suggestionRevision != revision ||
                session !== bound ||
                (observed != null && bound.activeShellCommandLine() != observed)
            ) {
                captured.close()
                return null
            }
            val handler =
                object : SwingShellSuggestionHandler {
                    override fun tryAccept(acceptance: SwingShellSuggestionAcceptance): SwingShellSuggestionAcceptanceResult =
                        if (disposed || session !== bound || suggestionRevision != revision) {
                            SwingShellSuggestionAcceptanceResult.STALE_CONTEXT
                        } else {
                            captured.tryAccept(acceptance)
                        }

                    override fun close() = captured.close()
                }
            val interaction = SwingShellSuggestionInteraction(request, handler, feedbackHandler)
            if (bound != null && observed != null) {
                interaction.contextIsCurrent = { !bound.isClosed && bound.activeShellCommandLine() == observed }
            }
            val scope = CoroutineScope(componentScope.coroutineContext + Job(componentJob) + CoroutineName("shell-suggestions"))
            activeSuggestionInteraction = interaction
            activeSuggestionIsAutomatic = automatic
            val presentationTarget = shellSuggestionTarget.takeIf { automatic }
            activeSuggestionTarget = presentationTarget
            suggestionScope = scope
            suggestionAnchorColumn = 0
            suggestionAnchorRow = 0
            interaction.addChangeListener(
                object : SwingShellSuggestionInteractionListener {
                    override fun onInteractionChanged(interaction: SwingShellSuggestionInteraction) {
                        if (interaction.isActive) return
                        interaction.removeChangeListener(this)
                        if (activeSuggestionInteraction !== interaction) return
                        activeSuggestionInteraction = null
                        activeSuggestionIsAutomatic = false
                        activeSuggestionTarget = null
                        suggestionScope = null
                        scope.cancel(CancellationException("Shell suggestion interaction ended"))
                        cleanupSwingResources(
                            { shellSuggestionController?.hide() },
                            { hideCapturedSuggestionTarget(presentationTarget, revision) },
                            {
                                if (!interaction.isClosed || interaction.closeReason == SwingShellSuggestionCloseReason.DISMISSED) {
                                    if (suggestionRevision == revision) suggestionScheduler?.onInvalidated()
                                    for (listener in suggestionInvalidationListeners) {
                                        if (suggestionRevision != revision) break
                                        listener.onShellSuggestionsInvalidated()
                                    }
                                }
                            },
                        )
                    }
                },
            )
            if (bound != null && observed != null) {
                scope.launch {
                    combine(bound.activeShellCommandLineRevision, bound.state) { _, _ -> }
                        .collect {
                            if (activeSuggestionInteraction === interaction &&
                                (bound.isClosed || session !== bound || bound.activeShellCommandLine() != observed)
                            ) {
                                cancelAndHideShellSuggestionsOnEdt(
                                    "Active shell command changed",
                                    SwingShellSuggestionCloseReason.INVALIDATED,
                                )
                            }
                        }
                }
            }
            return interaction.takeIf { activeSuggestionInteraction === it && it.isActive }
        }

        /** Captures the bound session's authoritative command line on the EDT, without mounting a popup. */
        @JvmOverloads
        public fun beginActiveShellSuggestionInteraction(
            trigger: SwingShellSuggestionTrigger = SwingShellSuggestionTrigger.EXPLICIT,
            feedbackHandler: SwingShellSuggestionFeedbackHandler = SwingShellSuggestionFeedbackHandler.NONE,
            editTarget: SwingShellSuggestionEditTarget? = null,
        ): SwingShellSuggestionInteraction? {
            check(SwingUtilities.isEventDispatchThread()) { "suggestion capture must run on the EDT" }
            if (!prepareShellSuggestionRequestOnEdt(trigger == SwingShellSuggestionTrigger.AUTOMATIC)) return null
            val snapshot = session?.activeShellCommandLine()
            if (snapshot == null || session?.isClosed != false) {
                cancelAndHideShellSuggestionsOnEdt("Active shell command is unavailable", SwingShellSuggestionCloseReason.INVALIDATED)
                return null
            }
            val interaction =
                beginShellSuggestionInteraction(
                    SwingShellSuggestionRequest(snapshot.commandText, snapshot.cursorOffset),
                    trigger,
                    feedbackHandler,
                    editTarget,
                ) ?: return null
            suggestionAnchorColumn = snapshot.cursorColumn
            suggestionAnchorRow = snapshot.cursorRow
            return interaction
        }

        /**
         * Mounts the standard embedded presentation for an already captured interaction on the EDT.
         * Hosts using detached or native UI can consume the interaction directly instead.
         * Placement belongs to this adapter and is independent of provider request data.
         */
        @JvmOverloads
        public fun presentShellSuggestions(
            interaction: SwingShellSuggestionInteraction,
            anchorColumn: Int = suggestionAnchorColumn,
            anchorRow: Int = suggestionAnchorRow,
        ) {
            check(SwingUtilities.isEventDispatchThread()) { "suggestion presentation must run on the EDT" }
            require(anchorColumn >= 0 && anchorRow >= 0) { "suggestion anchor must be nonnegative" }
            if (activeSuggestionInteraction !== interaction || !interaction.isActive) return
            getOrCreateShellSuggestionController().present(interaction, anchorColumn, anchorRow)
            doLayout()
        }

        /**
         * Opens a source on the EDT and collects it off the EDT into an existing interaction.
         * This does not mount UI. Its request-specific observer is captured before collection.
         * Closing the interaction cancels collection; final visible results survive stream completion.
         * Each interaction accepts one provider collection. Combine independent sources in the provider.
         */
        public fun requestShellSuggestions(
            interaction: SwingShellSuggestionInteraction,
            provider: SwingShellSuggestionProvider,
        ) {
            check(SwingUtilities.isEventDispatchThread()) { "suggestion source capture must run on the EDT" }
            if (activeSuggestionInteraction !== interaction || !interaction.isActive) return
            val scope = suggestionScope ?: return
            interaction.beginSource()
            val source =
                try {
                    provider.open(interaction.request)
                } catch (failure: Exception) {
                    if (failure is CancellationException) {
                        if (activeSuggestionInteraction === interaction) {
                            cancelAndHideShellSuggestionsOnEdt("Shell suggestion source cancelled")
                        }
                        throw failure
                    }
                    reportSuggestionFailure(interaction, failure)
                    return
                }
            if (activeSuggestionInteraction !== interaction || !interaction.isActive) return
            interaction.attachFeedback(source.feedbackHandler)
            val publications = source.suggestions.flowOn(Dispatchers.Default).conflate()
            scope.launch {
                try {
                    publications.collect { suggestions ->
                        this@launch.ensureActive()
                        if (activeSuggestionInteraction === interaction && interaction.isActive) {
                            interaction.publish(suggestions)
                        }
                    }
                    if (activeSuggestionInteraction === interaction && interaction.snapshot.suggestions.isEmpty()) {
                        cancelAndHideShellSuggestionsOnEdt("Shell suggestion source completed empty")
                    }
                } catch (cancellation: CancellationException) {
                    if (activeSuggestionInteraction === interaction) {
                        cancelAndHideShellSuggestionsOnEdt("Shell suggestion provider cancelled")
                    }
                    throw cancellation
                } catch (failure: Exception) {
                    ensureActive()
                    reportSuggestionFailure(interaction, failure)
                }
            }
        }

        /**
         * Captures a host command line, mounts the standard popup, and queries the configured provider.
         * Calls on the EDT take effect immediately; other calls dispatch asynchronously.
         * Automatic requests respect the automatic-popup setting; explicit requests respect the master switch.
         */
        @JvmOverloads
        public fun requestShellSuggestions(
            commandText: String,
            cursorOffset: Int,
            anchorColumn: Int,
            anchorRow: Int,
            trigger: SwingShellSuggestionTrigger = SwingShellSuggestionTrigger.AUTOMATIC,
            editTarget: SwingShellSuggestionEditTarget? = null,
        ) {
            require(anchorColumn >= 0 && anchorRow >= 0) { "suggestion anchor must be nonnegative" }
            val request = SwingShellSuggestionRequest(commandText, cursorOffset)
            runOnEdt {
                val provider = shellSuggestionProvider ?: return@runOnEdt
                val interaction = beginShellSuggestionInteraction(request, trigger, editTarget = editTarget) ?: return@runOnEdt
                presentShellSuggestions(interaction, anchorColumn, anchorRow)
                requestShellSuggestions(interaction, provider)
            }
        }

        /** Queries and presents the bound session's active command line using the configured provider. */
        @JvmOverloads
        public fun requestActiveShellSuggestions(
            trigger: SwingShellSuggestionTrigger = SwingShellSuggestionTrigger.EXPLICIT,
            editTarget: SwingShellSuggestionEditTarget? = null,
        ) {
            runOnEdt {
                val provider = shellSuggestionProvider ?: return@runOnEdt
                val interaction = beginActiveShellSuggestionInteraction(trigger, editTarget = editTarget) ?: return@runOnEdt
                presentShellSuggestions(interaction)
                requestShellSuggestions(interaction, provider)
            }
        }

        /**
         * Hides the shell suggestion popup.
         *
         * This method may be called from any thread; component state is updated
         * asynchronously on the EDT.
         */
        public fun hideShellSuggestions() {
            runOnEdt {
                cancelAndHideShellSuggestionsOnEdt("Shell suggestions hidden")
            }
        }

        /** Registers an EDT callback for shell-bound input invalidation. */
        public fun addShellSuggestionInvalidationListener(listener: SwingShellSuggestionInvalidationListener) {
            suggestionInvalidationListeners += listener
        }

        /** Removes a callback previously registered with [addShellSuggestionInvalidationListener]. */
        public fun removeShellSuggestionInvalidationListener(listener: SwingShellSuggestionInvalidationListener) {
            suggestionInvalidationListeners -= listener
        }

        /** Registers an EDT callback for automatic-suggestion eligibility changes. */
        public fun addShellSuggestionEligibilityListener(listener: SwingShellSuggestionEligibilityListener) {
            suggestionEligibilityListeners += listener
        }

        /** Removes a callback previously registered with [addShellSuggestionEligibilityListener]. */
        public fun removeShellSuggestionEligibilityListener(listener: SwingShellSuggestionEligibilityListener) {
            suggestionEligibilityListeners -= listener
        }

        /**
         * Returns whether automatic suggestions are enabled at the live viewport.
         *
         * This method is safe to call from any thread and returns the last state
         * published by the EDT without blocking it.
         */
        public fun isAutomaticShellSuggestionEligible(): Boolean = automaticSuggestionEligible.get()

        /**
         * Returns the current shell suggestion popup state.
         *
         * This state is owned by Swing and must be read on the Event Dispatch
         * Thread, like the other synchronous component interaction methods.
         *
         * @return immutable shell suggestion state snapshot.
         */
        public fun currentShellSuggestionState(): SwingShellSuggestionState {
            check(SwingUtilities.isEventDispatchThread()) { "shell suggestion state must be read on the EDT" }
            return shellSuggestionController?.state() ?: SwingShellSuggestionState.EMPTY
        }

        private fun applySettingsToSession(
            session: TerminalSession,
            settings: SwingSettings,
            previous: SwingSettings? = null,
        ) {
            if (settings.treatAmbiguousAsWide != previous?.treatAmbiguousAsWide) {
                session.setTreatAmbiguousAsWide(settings.treatAmbiguousAsWide)
            }
            if (settings.palette != previous?.palette) session.setThemePalette(settings.palette)
            if (settings.cursorShape != previous?.cursorShape) session.setCursorShape(settings.cursorShape)
            if (settings.pasteControlPolicy != previous?.pasteControlPolicy) {
                session.setPasteControlPolicy(settings.pasteControlPolicy)
            }
        }

        private fun handleScrollbarOverlayPressed(event: MouseEvent): Boolean {
            if (!hostServices.scrollbarOverlayEnabled) return false
            if (!isInScrollbarOverlayGutter(event)) return false
            if (!SwingUtilities.isLeftMouseButton(event)) {
                event.consume()
                return true
            }
            val handled =
                scrollbarOverlay.handlePressed(
                    x = event.x,
                    y = event.y,
                    settings = settings,
                    activeBuffer = renderCache.activeBuffer,
                    componentWidth = width,
                    componentHeight = height,
                    state = viewportController.viewportStateSnapshot(),
                ) { scrollbackOffset, valueIsAdjusting ->
                    scrollFromScrollbar(scrollbackOffset, valueIsAdjusting)
                }
            if (!handled) return false
            requestFocusInWindow()
            event.consume()
            repaint()
            return true
        }

        private fun handleScrollbarOverlayDragged(event: MouseEvent): Boolean {
            if (!hostServices.scrollbarOverlayEnabled) return false
            val handled =
                scrollbarOverlay.handleDragged(
                    y = event.y,
                    settings = settings,
                    activeBuffer = renderCache.activeBuffer,
                    componentHeight = height,
                    state = viewportController.viewportStateSnapshot(),
                ) { scrollbackOffset, valueIsAdjusting ->
                    scrollFromScrollbar(scrollbackOffset, valueIsAdjusting)
                }
            if (!handled) return false
            event.consume()
            repaint()
            return true
        }

        private fun isInScrollbarOverlayGutter(event: MouseEvent): Boolean =
            hostServices.scrollbarOverlayEnabled &&
                scrollbarOverlay.containsGutter(
                    settings = settings,
                    activeBuffer = renderCache.activeBuffer,
                    componentWidth = width,
                    componentHeight = height,
                    x = event.x,
                    y = event.y,
                )

        private fun handleScrollbarOverlayReleased(event: MouseEvent): Boolean {
            if (!hostServices.scrollbarOverlayEnabled) return false
            val handled =
                scrollbarOverlay.handleReleased(
                    y = event.y,
                    settings = settings,
                    activeBuffer = renderCache.activeBuffer,
                    componentHeight = height,
                    state = viewportController.viewportStateSnapshot(),
                ) { scrollbackOffset, valueIsAdjusting ->
                    scrollFromScrollbar(scrollbackOffset, valueIsAdjusting)
                }
            if (!handled) return false
            event.consume()
            repaint()
            return true
        }

        /**
         * Copies the current terminal text selection to the host clipboard.
         *
         * Off-EDT callers wait for the EDT to read selection and copy text.
         *
         * @return `true` if selection was successfully copied to clipboard, `false` otherwise.
         */
        public fun copySelectionToClipboard(): Boolean {
            if (!SwingUtilities.isEventDispatchThread()) {
                var copied = false
                SwingUtilities.invokeAndWait { copied = copySelectionToClipboard() }
                return copied
            }
            val text = selectedText() ?: return false
            hostServices.clipboardHandler.copyText(text)
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
        public fun copyTextToClipboard(text: String): Boolean {
            if (session == null) return false
            hostServices.clipboardHandler.copyText(text)
            return true
        }

        /**
         * Pastes text from the host clipboard into the active terminal session.
         *
         * Call on the EDT. Clipboard callbacks run synchronously and propagate their failures;
         * hosts reading the clipboard asynchronously can complete through [pasteText].
         * Unbound and closed sessions do not read the clipboard.
         *
         * @return `true` when nonempty clipboard text was admitted by the bound session,
         *   `false` otherwise, including outside the EDT. Admission does not promise transport completion.
         */
        public fun pasteClipboardText(): Boolean {
            if (!SwingUtilities.isEventDispatchThread() || disposed) return false
            val boundSession = session?.takeUnless { it.isClosed } ?: return false
            val text = hostServices.clipboardHandler.readText() ?: return false
            return session === boundSession && pasteText(text)
        }

        /**
         * Pastes supplied [text] into the active terminal session without reading the clipboard.
         *
         * Call on the EDT. Hosts may read the clipboard off-thread, then verify their intended
         * session is still bound on the EDT before calling this method. The session's normal
         * paste policies, mode snapshot, ordering, and bounded admission apply.
         *
         * Nonempty input invalidates shell suggestions before admission. Invalidation callbacks
         * run synchronously and propagate their failures; if they change or remove the bound
         * session, or close it, no paste is submitted.
         *
         * @param text host-supplied paste text.
         * @return `true` when the bound session admits the paste, `false` for empty input,
         *   unavailable sessions, calls outside the EDT, or rejected admission.
         *   Admission does not promise transport completion.
         */
        public fun pasteText(text: String): Boolean {
            if (!SwingUtilities.isEventDispatchThread() || disposed || text.isEmpty()) return false
            val boundSession = session?.takeUnless { it.isClosed } ?: return false
            invalidateShellSuggestionsOnEdt()
            return !(disposed || session !== boundSession || boundSession.isClosed) &&
                boundSession.submitInput(TerminalPasteEvent(text)) == TerminalInputAdmission.ACCEPTED
        }

        private fun getOrCreateShellSuggestionController(): SwingShellSuggestionController =
            shellSuggestionController ?: SwingShellSuggestionController(
                object : SwingShellSuggestionHost {
                    override val settings: SwingSettings get() = this@SwingTerminal.settings
                    override val suggestionKeymap get() = hostServices.shellSuggestionKeymap

                    override fun revalidate() = this@SwingTerminal.revalidate()

                    override fun repaint() = this@SwingTerminal.repaint()

                    override fun requestFocusInWindow(): Boolean = this@SwingTerminal.requestFocusInWindow()
                },
                hostServices.shellSuggestionViewFactory,
            ).also {
                shellSuggestionController = it
                it.popup.isVisible = false
                add(it.popup)
            }

        private fun reportSuggestionFailure(
            interaction: SwingShellSuggestionInteraction,
            failure: Exception,
        ) {
            if (activeSuggestionInteraction !== interaction || !interaction.validateContext()) return
            cancelAndHideShellSuggestionsOnEdt("Shell suggestion provider failed", SwingShellSuggestionCloseReason.FAILED)
            try {
                suggestionFailureHandler.onSuggestionFailure(interaction.request, failure)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (callbackFailure: Exception) {
                System.getLogger(SwingTerminal::class.java.name).log(
                    System.Logger.Level.WARNING,
                    "Shell suggestion failure handler failed",
                    callbackFailure,
                )
            }
        }

        private fun prepareShellSuggestionRequestOnEdt(automatic: Boolean): Boolean {
            if (disposed || !settings.smartSuggestionsEnabled || !isLiveViewportOnEdt()) {
                cancelAndHideShellSuggestionsOnEdt("Shell suggestions are unavailable")
                return false
            }
            return !automatic || settings.shellSuggestionsEnabled
        }

        /**
         * Requests a foreground-program screen clear/redraw.
         *
         * This sends Ctrl+L through the terminal input encoder. It deliberately
         * does not mutate the render buffer directly, because clearing the
         * emulator display behind the PTY would desynchronize shells and TUIs
         * from their cursor and prompt model.
         *
         * @return `true` when a bound session accepted the input request.
         */
        public fun clearScreen(): Boolean {
            if (!SwingUtilities.isEventDispatchThread()) return false
            val boundSession = session ?: return false
            selectionController.clearSelection()
            searchController.clear()
            invalidateShellSuggestionsOnEdt()
            return boundSession.submitInput(CLEAR_SCREEN_KEY_EVENT) == TerminalInputAdmission.ACCEPTED
        }

        private fun invalidateShellSuggestionsOnEdt() {
            cancelAndHideShellSuggestionsOnEdt("Shell suggestions invalidated by input")
            suggestionScheduler?.onInvalidated()
            suggestionInvalidationListeners.forEach { listener ->
                listener.onShellSuggestionsInvalidated()
            }
        }

        private fun handleContextMenuMouseEvent(
            event: MouseEvent,
            forcedByShift: Boolean,
        ): Boolean {
            val request =
                SwingTerminalContextMenuRequest(
                    terminal = this,
                    x = event.x,
                    y = event.y,
                    forcedByShift = forcedByShift,
                    hyperlink = contextHyperlinkAt(event),
                    triggerEvent = event,
                )
            return hostServices.contextMenuHandler.handleContextMenu(request)
        }

        private fun contextHyperlinkAt(event: MouseEvent): SwingTerminalContextHyperlink? {
            val hyperlinkId = hyperlinkController.hyperlinkIdAt(event)
            if (hyperlinkId == NO_HYPERLINK_ID) return null
            val detected = hyperlinkDiscoveryController.discoveredHyperlink(hyperlinkId, renderCache)
            val uri = if (hyperlinkId > 0) session?.hyperlinkUri(hyperlinkId) else detected?.uri
            val openAction: () -> Boolean =
                if (hyperlinkId > 0) {
                    if (uri == null) return null
                    val handler = hostServices.hyperlinkHandler
                    { handler.openHyperlink(uri) }
                } else {
                    val action = detected?.action ?: return null
                    val activate: () -> Boolean = {
                        action.open(event).also { opened ->
                            if (opened) hyperlinkDiscoveryController.markFollowed(hyperlinkId, renderCache)
                        }
                    }
                    activate
                }
            return SwingTerminalContextHyperlink(
                uri = uri,
                openAction = openAction,
                copyUriAction = {
                    uri != null && copyTextToClipboard(uri)
                },
                providerAction = detected?.action,
            )
        }

        private fun isHyperlinkResolvable(hyperlinkId: Int): Boolean {
            if (hyperlinkId == NO_HYPERLINK_ID) return false
            if (hyperlinkId > 0) return session?.hyperlinkUri(hyperlinkId) != null
            return hyperlinkDiscoveryController.isDiscoveredHyperlinkResolvable(hyperlinkId, renderCache)
        }

        private fun openHyperlink(
            hyperlinkId: Int,
            event: MouseEvent? = null,
        ): Boolean {
            if (hyperlinkId == NO_HYPERLINK_ID) return false
            if (hyperlinkId > 0) {
                val uri = session?.hyperlinkUri(hyperlinkId) ?: return false
                return hostServices.hyperlinkHandler.openHyperlink(uri)
            }
            return hyperlinkDiscoveryController.openDiscoveredHyperlink(hyperlinkId, renderCache, event)
        }

        private fun cellAt(
            x: Int,
            y: Int,
            cache: TerminalRenderCache,
        ): Long {
            val visualCell = visualCellAt(x, y, cache)
            val column = (visualCell ushr 32).toInt()
            val row = visualCell.toInt()
            val logicalColumn = visualGeometry.bidiLayout.row(cache, row)?.logicalColumn(column) ?: column
            return packCell(logicalColumn, row)
        }

        private fun visualCellAt(
            x: Int,
            y: Int,
            cache: TerminalRenderCache,
        ): Long {
            updateChromeLayout()
            val paddingLeft = chrome.left(settings, cache.activeBuffer)
            val paddingTop = chrome.top(settings, cache.activeBuffer)
            val column = ((x - paddingLeft) / metrics.cellWidth).coerceIn(0, cache.columns - 1)
            val row =
                if (cache === renderCache && visualGeometry.rowCount == cache.rows) {
                    visualGeometry.rowAtComponentY(y, paddingTop)
                } else {
                    ((y - paddingTop) / metrics.cellHeight).coerceIn(0, cache.rows - 1)
                }
            return packCell(column, row)
        }

        private fun repaintHyperlinkSpan(
            startRow: Int,
            startColumn: Int,
            endRow: Int,
            endColumn: Int,
        ) {
            if (startRow !in 0..endRow || renderCache.rows <= 0 || renderCache.columns <= 0) return
            val firstRow = startRow.coerceAtLeast(0)
            val lastRow = endRow.coerceAtMost(renderCache.rows - 1)
            if (firstRow > lastRow) return

            val paddingLeft = chrome.left(settings, renderCache.activeBuffer)
            val paddingTop = chrome.top(settings, renderCache.activeBuffer)
            val contentOriginY = if (visualGeometry.rowCount == renderCache.rows) visualGeometry.contentOriginY else 0.0
            var row = firstRow
            while (row <= lastRow) {
                val rowStartColumn = if (row == startRow) startColumn else 0
                val rowEndColumn = if (row == endRow) endColumn else renderCache.columns
                val clampedStartColumn = rowStartColumn.coerceIn(0, renderCache.columns)
                val clampedEndColumn = rowEndColumn.coerceIn(0, renderCache.columns)
                if (clampedEndColumn > clampedStartColumn) {
                    val bidi = visualGeometry.bidiLayout.row(renderCache, row)
                    val yTop = paddingTop + contentOriginY + visualGeometry.rowTop(row)
                    val y = floor(yTop).toInt()
                    val repaintHeight = ceil(yTop + metrics.cellHeight).toInt() - y
                    forEachVisualCellSpan(bidi, clampedStartColumn, clampedEndColumn) { visualStart, visualEnd ->
                        repaint(
                            paddingLeft + visualStart * metrics.cellWidth,
                            y,
                            (visualEnd - visualStart) * metrics.cellWidth,
                            repaintHeight,
                        )
                    }
                }
                row++
            }
        }

        private fun terminalPixelYAt(
            y: Int,
            cache: TerminalRenderCache,
        ): Int {
            val paddingTop = chrome.top(settings, cache.activeBuffer)
            if (cache === renderCache && visualGeometry.rowCount == cache.rows) {
                return visualGeometry.terminalPixelYAtComponentY(y, paddingTop)
            }
            val localY = y - paddingTop
            return localY.coerceIn(0, maxOf(0, cache.rows * metrics.cellHeight - 1))
        }

        private fun scrollToAbsoluteRowOnEdt(
            row: Long,
            center: Boolean,
        ): Boolean {
            val dividers = viewportController.promptDividers
            val centerRows = if (center) visibleGridRows() / 2 else 0
            if (dividers != null) {
                val offset =
                    (dividers.liveOrigin - dividers.rowBoundary(row) + centerRows)
                        .coerceIn(0, dividers.scrollRange.toLong())
                return viewportController.scrollTo(offset.toDouble(), dividers.scrollRange)
            }
            val offset = renderCache.discardedCount + renderCache.historySize + centerRows - row
            return scrollViewportToOnEdt(offset.coerceIn(0, renderCache.historySize.toLong()).toInt())
        }

        private fun scrollViewportToOnEdt(
            offsetRows: Int,
            historySize: Int = renderCache.historySize,
        ): Boolean {
            val dividers = viewportController.promptDividers
            if (dividers != null) {
                val target =
                    if (offsetRows == 0) {
                        0
                    } else {
                        dividers.visualOffsetForRow(
                            renderCache.discardedCount + historySize - offsetRows.coerceIn(0, historySize),
                        )
                    }
                return viewportController.scrollTo(target.toDouble(), dividers.scrollRange)
            }
            val targetRow = offsetRows.coerceIn(0, historySize)
            return viewportController.scrollTo(targetRow.toDouble(), historySize)
        }

        private fun handleViewportScrollOnEdt(
            renderMappingChanged: Boolean,
            scrollComplete: Boolean,
        ) {
            val boundSession = session
            if (boundSession != null && renderMappingChanged) {
                requestRenderFromSession(boundSession)
            }
            updateVisualViewportGeometry()
            repaint()
            publishViewportState(
                renderCache.historySize,
                notifyListener = scrollComplete,
                notifyPrimitiveListener = !scrollComplete,
            )
        }

        /** Returns whether the shared blink visibility changed; callers own pixel invalidation. */
        private fun resetCursorBlinkOnEdt(): Boolean {
            val wasVisible = cursorBlinkVisible
            cursorBlinkVisible = true
            if (settings.cursorBlinkMillis > 0) {
                cursorTimer.restart()
            }
            return !wasVisible
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
            )
            val eligibilityChanged = applyAutomaticSuggestionEligibilityOnEdt()
            val eligibilityRevision = automaticSuggestionEligibilityRevision
            var failure: Throwable? = null
            try {
                viewportController.notifyViewportListener(notifyListener, notifyPrimitiveListener)
            } catch (next: Throwable) {
                failure = next
            }
            try {
                notifyAutomaticSuggestionEligibilityOnEdt(eligibilityChanged, eligibilityRevision)
            } catch (next: Throwable) {
                failure = preserveSwingFailure(failure, next)
            }
            failure?.let { throw it }
        }

        private fun updateAutomaticSuggestionEligibilityOnEdt() {
            val changed = applyAutomaticSuggestionEligibilityOnEdt()
            notifyAutomaticSuggestionEligibilityOnEdt(changed, automaticSuggestionEligibilityRevision)
        }

        private fun applyAutomaticSuggestionEligibilityOnEdt(): Boolean {
            val eligible = !disposed && settings.smartSuggestionsEnabled && isLiveViewportOnEdt() && settings.shellSuggestionsEnabled
            if (automaticSuggestionEligible.getAndSet(eligible) == eligible) return false
            automaticSuggestionEligibilityRevision++
            return true
        }

        private fun notifyAutomaticSuggestionEligibilityOnEdt(
            changed: Boolean,
            revision: Long,
        ) {
            if (revision != automaticSuggestionEligibilityRevision) return
            val liveViewport = isLiveViewportOnEdt()
            var failure: Throwable? = null
            if (!settings.smartSuggestionsEnabled || !liveViewport || !settings.shellSuggestionsEnabled && activeSuggestionIsAutomatic) {
                try {
                    cancelAndHideShellSuggestionsOnEdt(
                        if (liveViewport) "Automatic suggestions disabled by settings" else "Viewport left live output",
                    )
                } catch (next: Throwable) {
                    failure = next
                }
            }
            if (changed && revision == automaticSuggestionEligibilityRevision) {
                val eligible = automaticSuggestionEligible.get()
                try {
                    suggestionScheduler?.onEligibilityChanged(eligible)
                } catch (next: Throwable) {
                    failure = preserveSwingFailure(failure, next)
                }
                try {
                    for (listener in suggestionEligibilityListeners) {
                        if (revision != automaticSuggestionEligibilityRevision) break
                        listener.onAutomaticShellSuggestionEligibilityChanged(eligible)
                    }
                } catch (next: Throwable) {
                    failure = preserveSwingFailure(failure, next)
                }
            }
            failure?.let { throw it }
        }

        private fun isLiveViewportOnEdt(): Boolean = viewportController.preciseOffset == 0.0

        private fun cancelAndHideShellSuggestionsOnEdt(
            reason: String,
            closeReason: SwingShellSuggestionCloseReason = SwingShellSuggestionCloseReason.CANCELLED,
        ) {
            val revision = ++suggestionRevision
            val interaction = activeSuggestionInteraction
            val scope = suggestionScope
            val target = activeSuggestionTarget
            activeSuggestionInteraction = null
            suggestionScope = null
            activeSuggestionIsAutomatic = false
            activeSuggestionTarget = null
            scope?.cancel(CancellationException(reason))
            cleanupSwingResources(
                { interaction?.close(closeReason) },
                { if (suggestionRevision == revision) shellSuggestionController?.hide() },
                { hideCapturedSuggestionTarget(target, revision) },
            )
        }

        private fun hideCapturedSuggestionTarget(
            target: SwingShellSuggestionTarget?,
            revision: Long,
        ) {
            if (suggestionRevision == revision || activeSuggestionTarget !== target) target?.hideSuggestions()
        }

        /**
         * Returns the component size in pixels for the requested grid and screen.
         *
         * Must be called on the EDT because it reads the current settings and
         * font metrics. Primary-screen chrome is the default for initial sizing.
         *
         * Both dimensions must be positive. The complete size must fit the integer pixel range.
         *
         * @throws IllegalArgumentException if a dimension or the complete size is out of range.
         * @param columns requested number of terminal columns.
         * @param rows requested number of terminal rows.
         * @param activeBuffer screen whose chrome insets should be included.
         * @return a new caller-owned dimension containing pixel width and height.
         */
        @JvmOverloads
        public fun preferredGridSize(
            columns: Int,
            rows: Int,
            activeBuffer: TerminalRenderBufferKind = TerminalRenderBufferKind.PRIMARY,
        ): Dimension =
            Dimension(
                gridPixelExtent(columns, metrics.cellWidth, chrome.horizontalInset(settings, activeBuffer)),
                gridPixelExtent(rows, metrics.cellHeight, chrome.verticalInset(settings, activeBuffer)),
            )

        private fun resizeSessionToVisibleGridOnEdt(publishWhenUnchanged: Boolean = true): Boolean {
            val visibleGridSize =
                viewportController.visibleGridSizeOnEdt(
                    settings,
                    metrics,
                    width,
                    height,
                    renderCache.activeBuffer,
                )
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

            viewportController.finishScroll()
            publishViewportState(renderCache.historySize)
            lastResizedColumns = columns
            lastResizedRows = rows
            // Animation is finished above, so resize anchoring is always row-exact.
            val oldOffset = viewportController.requestedOffset
            val anchorRow = visualGeometry.firstFullyVisibleRow()
            val anchorLineId =
                if (viewportController.preciseOffset > 0.0 &&
                    anchorRow in 0 until renderCache.rows
                ) {
                    renderCache.lineIds[anchorRow]
                } else {
                    0L
                }

            val resizedViewport = boundSession.tryResizeViewport(columns, rows, oldOffset) ?: return true
            selectionController.clearSelection(notify = false)
            viewportController.anchorAfterResize(
                resizedViewport.scrollbackOffset,
                resizedViewport.historySize,
                resizedViewport.discardedCount,
                anchorLineId = anchorLineId,
            )
            selectionController.publishChange()
            return true
        }

        private fun visibleGridRows(): Int = viewportController.visibleGridRows(settings, metrics, height, renderCache.activeBuffer)

        private fun visibleRenderRows(): Int = viewportController.visibleRenderRows(settings, metrics, height, renderCache.activeBuffer)

        private fun requestedRenderRows(): Int = viewportController.requestedRows(visibleRenderRows())

        private fun visualContentHeightPixels(): Int =
            when {
                !renderCache.hasFrame -> 0
                visualGeometry.rowCount == renderCache.rows -> visualGeometry.visualHeight
                else -> renderCache.rows * metrics.cellHeight
            }

        private fun commandNavigationAnchorRow(): Int =
            if (visualGeometry.rowCount == renderCache.rows) {
                visualGeometry.firstFullyVisibleRow()
            } else {
                0
            }

        private fun resetRenderCaches() {
            renderCache.reset()
            searchCache.reset()
        }

        private fun refreshRenderCacheFromSession(session: TerminalSession) {
            val closed = session.state.value is TerminalSessionState.Closed
            scrollbarOverlay.retainedOutput = closed
            if (closed &&
                settings.promptDecoration == SwingPromptDecoration.DIVIDER &&
                renderCache.activeBuffer != TerminalRenderBufferKind.ALTERNATE
            ) {
                retainedViewport = null
                updatePromptDividers(session)
                renderCache.updateFrom(session, viewportController.requestedOffset, requestedRenderRows())
            } else if (closed) {
                val hadRetainedViewport = retainedViewport != null
                val viewport = retainedViewport ?: RetainedFrameViewport(session).also { retainedViewport = it }
                viewport.visibleRows = visibleGridRows()
                val previousHistory = renderCache.historySize
                renderCache.updateFrom(viewport, viewportController.requestedOffset, requestedRenderRows())
                if (hadRetainedViewport && previousHistory != renderCache.historySize) {
                    val offset =
                        if (viewportController.requestedOffset == 0) {
                            0
                        } else {
                            (viewportController.requestedOffset.toLong() + renderCache.historySize - previousHistory)
                                .coerceIn(0L, renderCache.historySize.toLong())
                                .toInt()
                        }
                    viewportController.anchorAfterResize(offset, renderCache.historySize, renderCache.discardedCount)
                    renderCache.updateFrom(viewport, offset, requestedRenderRows())
                }
            } else {
                session.readPublishedFrame { published ->
                    renderCache.updateFrom(published)
                } ?: return
            }
            selectionController.updateFrame(renderCache)
            hyperlinkDiscoveryController.scheduleForFrame()
            selectionController.publishChange()
        }

        private fun reconcileHyperlinksOnEdt() {
            if (disposed) return
            val boundSession = session ?: return
            refreshRenderCacheFromSession(boundSession)
            hyperlinkDiscoveryController.reconcile()
            val position = pointerPosition(this)
            if (position == null) {
                clearPointerHover()
            } else {
                val markerRow = promptMarkerRowAt(position.x, position.y)
                updateHoveredPromptMarker(markerRow)
                if (markerRow == NO_PROMPT_MARKER_ROW) {
                    hyperlinkController.updatePointerPosition(
                        position.x,
                        position.y,
                        enabled = !mouseController.isMouseTrackingIntercepted(),
                    )
                } else {
                    hyperlinkController.clearHyperlinkHover()
                }
            }
        }

        private fun detachAncestorWindow() {
            ancestorWindow?.removeWindowStateListener(windowStateListener)
            ancestorWindow?.removeWindowFocusListener(windowFocusListener)
            ancestorWindow = null
        }

        private fun requestRenderFromSession(session: TerminalSession) {
            if (session.state.value is TerminalSessionState.Closed) {
                refreshRenderCacheFromSession(session)
                refreshShellIntegrationDecorations(session)
                searchController.updateViewportHighlights()
                publishViewportState(renderCache.historySize)
                repaint()
                return
            }
            session.requestRender(
                scrollbackOffset = viewportController.requestedOffset,
                viewportRows = requestedRenderRows(),
            )
        }

        private fun refreshShellIntegrationDecorations(session: TerminalSession): Boolean {
            val decorationsChanged = shellIntegrationDecorations.updateFrom(session.shellIntegrationState, renderCache)
            val gutterActivated =
                !chrome.promptDecorationsAvailable && session.shellIntegrationState.recordCount() > 0 && canActivatePromptGutter()
            if (gutterActivated) {
                viewportController.finishScroll()
                chrome.promptDecorationsAvailable = true
                if (settings.promptDecoration == SwingPromptDecoration.GUTTER) {
                    resizeSessionToVisibleGridOnEdt()
                    preferredSize = preferredGridSize(settings.columns, settings.rows)
                    revalidate()
                    clearPointerHover()
                    requestRenderFromSession(session)
                }
            }
            val projectionChanged = updatePromptDividers(session)
            return gutterActivated or decorationsChanged or projectionChanged or updateVisualViewportGeometry()
        }

        private fun canActivatePromptGutter(): Boolean {
            if (settings.promptDecoration != SwingPromptDecoration.GUTTER ||
                renderCache.activeBuffer == TerminalRenderBufferKind.ALTERNATE
            ) {
                return true
            }
            val firstAbsoluteRow = renderCache.discardedCount + renderCache.historySize - renderCache.scrollbackOffset
            var row = 0
            while (row < renderCache.rows) {
                // A prompt-start marker can precede its text. Resizing now would discard
                // the untouched row's identity before the prompt becomes durable output.
                if (shellIntegrationDecorations.hasPromptStartAt(row) &&
                    firstAbsoluteRow + row >= renderCache.outputEndAbsoluteRow
                ) {
                    return false
                }
                row++
            }
            return true
        }

        private fun updatePromptDividers(session: TerminalSession): Boolean {
            val previousRange = viewportController.promptDividers?.scrollRange
            val previousOrigin = viewportController.promptDividers?.liveOrigin
            val previousOffset = viewportController.preciseOffset
            viewportController.updatePromptDividers(
                reader = session,
                state = session.shellIntegrationState,
                enabled =
                    settings.promptDecoration == SwingPromptDecoration.DIVIDER &&
                        renderCache.activeBuffer != TerminalRenderBufferKind.ALTERNATE,
                visibleRows = visibleGridRows(),
                sourceHistorySize = renderCache.historySize,
                sourceDiscardedCount = renderCache.discardedCount,
                scrollOnOutput = settings.scrollOnOutput,
                retainedOutput = session.state.value is TerminalSessionState.Closed,
            )
            return previousRange != viewportController.promptDividers?.scrollRange ||
                previousOrigin != viewportController.promptDividers?.liveOrigin ||
                previousOffset != viewportController.preciseOffset
        }

        private fun updateVisualViewportGeometry(): Boolean {
            val chromeChanged = updateChromeLayout()
            val viewportPixelHeight = viewportController.viewportPixelHeight(settings, height, renderCache.activeBuffer)
            val layoutChanged =
                visualGeometry.updateLayout(
                    metrics = metrics,
                    rows = renderCache.rows,
                    viewportPixelHeight = viewportPixelHeight,
                    dividers = viewportController.promptDividers,
                    firstAbsoluteRow = renderCache.discardedCount + renderCache.historySize - renderCache.scrollbackOffset,
                )
            viewportController.updateCellHeight(metrics.cellHeight)
            val originChanged =
                visualGeometry.updateContentOrigin(
                    viewportController.promptDividers?.let { dividers ->
                        val firstRow = renderCache.discardedCount + renderCache.historySize - renderCache.scrollbackOffset
                        val desired =
                            (dividers.rowBoundary(firstRow) - dividers.liveOrigin + viewportController.preciseOffset) * metrics.cellHeight
                        desired.coerceIn(minOf(0.0, viewportPixelHeight.toDouble() - visualGeometry.visualHeight), 0.0)
                    } ?: viewportController.contentOriginY(
                        cacheScrollbackOffset = renderCache.scrollbackOffset,
                        cacheRows = renderCache.rows,
                        cellHeight = metrics.cellHeight,
                        viewportHeightPixels = viewportPixelHeight,
                        visibleGridRows = visibleGridRows(),
                    ),
                )
            refreshHyperlinkHover()
            return chromeChanged or layoutChanged or originChanged
        }

        private fun updateChromeLayout(): Boolean =
            chrome.updateLayout(settings, metrics, width, height, renderCache.columns, renderCache.rows)

        private fun gridPixelExtent(
            cells: Int,
            cellSize: Int,
            inset: Int,
        ): Int {
            require(cells > 0) { "grid dimensions must be positive" }
            val extent = cells.toLong() * cellSize + inset
            require(extent in 1..Int.MAX_VALUE.toLong()) { "grid size exceeds the integer pixel range" }
            return extent.toInt()
        }

        private fun buildMetrics(settings: SwingSettings): SwingMetrics {
            val metricsSource: FontMetrics = getFontMetrics(settings.font)
            val result = SwingMetrics.from(metricsSource, settings.lineHeight, settings.columnSpacing)
            for (buffer in TerminalRenderBufferKind.entries) {
                gridPixelExtent(settings.columns, result.cellWidth, chrome.horizontalInset(settings, buffer))
                gridPixelExtent(settings.rows, result.cellHeight, chrome.verticalInset(settings, buffer))
            }
            return result
        }

        private fun runOnEdt(action: Runnable) {
            if (SwingUtilities.isEventDispatchThread()) {
                action.run()
            } else {
                hostServices.uiDispatcher.dispatch(action)
            }
        }

        private companion object {
            private const val NO_HYPERLINK_ID = 0
            private const val NO_PROMPT_MARKER_ROW = -1
            private const val NO_RESIZE_DIMENSION = -1
            private const val MIN_TIMER_DELAY_MILLIS = 1

            // One row covers a fractional component height and one covers the
            // translated leading/trailing edge during smooth row animation.
            private const val TRANSIENT_RENDER_ROW_RESERVE = 2

            private val HAND_CURSOR: Cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            private val DEFAULT_CURSOR: Cursor = Cursor.getDefaultCursor()
            private val CLEAR_SCREEN_KEY_EVENT = TerminalKeyEvent.codepoint('L'.code, TerminalModifiers.CTRL)

            private fun cursorTimerDelay(settings: SwingSettings): Int = maxOf(MIN_TIMER_DELAY_MILLIS, settings.cursorBlinkMillis)

            private fun packCell(
                column: Int,
                row: Int,
            ): Long = (column.toLong() shl 32) or (row.toLong() and 0xffff_ffffL)
        }
    }
