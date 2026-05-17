package com.gagik.terminal.ui.swing.api

import com.gagik.terminal.input.event.TerminalPasteEvent
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
    private var cursorBlinkVisible: Boolean = true
    private var lastResizedColumns: Int = NO_RESIZE_DIMENSION
    private var lastResizedRows: Int = NO_RESIZE_DIMENSION
    private var selection: CellSelection? = null
    private var selectionAnchorColumn: Int = 0
    private var selectionAnchorRow: Int = 0
    private val renderPending = AtomicBoolean(false)
    private val visibleGridSizeSnapshot = AtomicLong(packVisibleGridSize(1, 1))

    private val painter = TerminalGridPainter()
    private val repaintPlanner = TerminalSwingRepaintPlanner()
    private val scrollModel = TerminalSwingScrollModel()
    private val keyMapper = TerminalSwingKeyMapper()
    private val selectionTextExtractor = TerminalSelectionTextExtractor()
    private val repaintSink = object : TerminalRepaintSink {
        override fun requestFullRepaint() {
            repaint()
        }

        override fun requestRegionRepaint(x: Int, y: Int, width: Int, height: Int) {
            repaint(x, y, width, height)
        }
    }
    private val cursorTimer = Timer(settings.cursorBlinkMillis) {
        cursorBlinkVisible = !cursorBlinkVisible
        repaintBlinkingCursor()
    }

    private val inputKeyListener = object : KeyAdapter() {
        override fun keyPressed(event: KeyEvent) {
            if (handleClipboardShortcut(event)) return

            val keyEvent = keyMapper.keyPressed(event) ?: return
            session?.encodeKey(keyEvent)
            event.consume()
        }

        override fun keyTyped(event: KeyEvent) {
            val keyEvent = keyMapper.keyTyped(event) ?: return
            session?.encodeKey(keyEvent)
            event.consume()
        }
    }

    private val viewportWheelListener = MouseWheelListener { event ->
        handleMouseWheel(event)
    }

    private val selectionMouseListener = object : MouseAdapter() {
        override fun mousePressed(event: MouseEvent) {
            handleSelectionMousePressed(event)
        }
    }

    private val selectionMouseMotionListener = object : MouseMotionAdapter() {
        override fun mouseDragged(event: MouseEvent) {
            handleSelectionMouseDragged(event)
        }
    }

    private val resizeListener = object : ComponentAdapter() {
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
        val packed = if (SwingUtilities.isEventDispatchThread()) {
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

            val painted = publisher.readCurrent { cache ->
                painter.paint(
                    g = g,
                    cache = cache,
                    settings = settings,
                    metrics = metrics,
                    width = width,
                    height = height,
                    cursorBlinkVisible = cursorBlinkVisible,
                    contentYOffset = contentYOffset(cache),
                    selection = selection,
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
        lastResizedColumns = NO_RESIZE_DIMENSION
        lastResizedRows = NO_RESIZE_DIMENSION
        repaintPlanner.reset()
        renderPending.set(false)
        resizeSessionToVisibleGridOnEdt()
        repaint()
    }

    private fun unbindOnEdt() {
        session?.onDirty = null
        session = null
        resetScrollbackState()
        clearSelection()
        lastResizedColumns = NO_RESIZE_DIMENSION
        lastResizedRows = NO_RESIZE_DIMENSION
        repaintPlanner.reset()
        renderPending.set(false)
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
        if (SwingUtilities.isEventDispatchThread()) return selection

        val result = arrayOfNulls<CellSelection>(1)
        SwingUtilities.invokeAndWait {
            result[0] = selection
        }
        return result[0]
    }

    private fun applySettingsToSession(session: TerminalSession, settings: TerminalSwingSettings) {
        session.setTreatAmbiguousAsWide(settings.treatAmbiguousAsWide)
    }

    private fun handleMouseWheel(event: MouseWheelEvent) {
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

    private fun handleSelectionMousePressed(event: MouseEvent) {
        if (!SwingUtilities.isLeftMouseButton(event)) return
        requestFocusInWindow()

        val publisher = session?.publisher ?: return
        publisher.readCurrent { cache ->
            val cell = cellAt(event, cache)
            val column = unpackCellColumn(cell)
            val row = unpackCellRow(cell)

            when {
                event.clickCount >= 3 -> {
                    selection = CellSelection(0, row, cache.columns, row)
                }

                event.clickCount == 2 -> {
                    selection = selectionTextExtractor.wordSelectionAt(cache, row, column)
                }

                else -> {
                    selectionAnchorColumn = column
                    selectionAnchorRow = row
                    selection = null
                }
            }
        }
        repaint()
        event.consume()
    }

    private fun handleSelectionMouseDragged(event: MouseEvent) {
        if (event.modifiersEx and MouseEvent.BUTTON1_DOWN_MASK == 0) return

        val publisher = session?.publisher ?: return
        publisher.readCurrent { cache ->
            val cell = cellAt(event, cache)
            val column = unpackCellColumn(cell)
            val row = unpackCellRow(cell)
            val caretColumn = if (
                row < selectionAnchorRow ||
                row == selectionAnchorRow && column < selectionAnchorColumn
            ) {
                column
            } else {
                column + 1
            }
            selection = CellSelection(selectionAnchorColumn, selectionAnchorRow, caretColumn, row)
        }
        repaint()
        event.consume()
    }

    private fun handleClipboardShortcut(event: KeyEvent): Boolean {
        val handled = when (settings.clipboardShortcuts.actionFor(event.keyCode, event.modifiersEx)) {
            TerminalClipboardAction.COPY -> copySelectionToClipboard()
            TerminalClipboardAction.PASTE -> pasteClipboardText()
            TerminalClipboardAction.NONE -> false
        }

        if (!handled) return false
        event.consume()
        return true
    }

    private fun copySelectionToClipboard(): Boolean {
        val currentSelection = selection ?: return false
        val publisher = session?.publisher ?: return false
        val selectedText = publisher.readCurrent { cache ->
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
        selection = null
    }

    private fun cellAt(event: MouseEvent, cache: TerminalRenderCache): Long {
        val column = (event.x / metrics.cellWidth).coerceIn(0, cache.columns - 1)
        val row = ((event.y - contentYOffset(cache)) / metrics.cellHeight)
            .toInt()
            .coerceIn(0, cache.rows - 1)
        return packCell(column, row)
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

    private fun resetScrollbackState() {
        scrollModel.reset()
    }

    private fun preferredGridSize(columns: Int, rows: Int): Dimension {
        return Dimension(columns * metrics.cellWidth, rows * metrics.cellHeight)
    }

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

    private fun wheelScrollLines(event: MouseWheelEvent): Double {
        return when (event.scrollType) {
            MouseWheelEvent.WHEEL_UNIT_SCROLL -> -event.preciseWheelRotation * event.scrollAmount
            MouseWheelEvent.WHEEL_BLOCK_SCROLL -> -event.preciseWheelRotation * visibleGridRows()
            else -> -event.preciseWheelRotation
        }
    }

    private fun visibleGridRows(): Int {
        return maxOf(1, height / metrics.cellHeight)
    }

    private fun requestedRenderRows(): Int {
        return scrollModel.requestedRows(visibleGridRows())
    }

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

        private fun packVisibleGridSize(columns: Int, rows: Int): Long {
            return (columns.toLong() shl 32) or (rows.toLong() and 0xffff_ffffL)
        }

        private fun unpackVisibleColumns(packed: Long): Int {
            return (packed ushr 32).toInt()
        }

        private fun unpackVisibleRows(packed: Long): Int {
            return packed.toInt()
        }

        private fun packCell(column: Int, row: Int): Long {
            return (column.toLong() shl 32) or (row.toLong() and 0xffff_ffffL)
        }

        private fun unpackCellColumn(packed: Long): Int {
            return (packed ushr 32).toInt()
        }

        private fun unpackCellRow(packed: Long): Int {
            return packed.toInt()
        }
    }
}
