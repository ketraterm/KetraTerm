package com.gagik.terminal.ui.swing.api

import com.gagik.terminal.session.TerminalSession
import com.gagik.terminal.ui.swing.input.TerminalSwingKeyMapper
import com.gagik.terminal.ui.swing.render.TerminalGridPainter
import com.gagik.terminal.ui.swing.settings.TerminalSwingMetrics
import com.gagik.terminal.ui.swing.settings.TerminalSwingSettings
import com.gagik.terminal.ui.swing.settings.TerminalSwingSettingsProvider
import java.awt.*
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseWheelEvent
import java.awt.event.MouseWheelListener
import java.util.concurrent.atomic.AtomicBoolean
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
    private var scrollbackOffset: Int = 0
    private val renderPending = AtomicBoolean(false)

    private val painter = TerminalGridPainter()
    private val repaintPlanner = TerminalSwingRepaintPlanner()
    private val keyMapper = TerminalSwingKeyMapper()
    private val cursorTimer = Timer(settings.cursorBlinkMillis) {
        cursorBlinkVisible = !cursorBlinkVisible
        repaintBlinkingCursor()
    }

    private val inputKeyListener = object : KeyAdapter() {
        override fun keyPressed(event: KeyEvent) {
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

    init {
        font = settings.font
        background = Color(settings.palette.defaultBackground, true)
        foreground = Color(settings.palette.defaultForeground, true)
        isOpaque = true
        isFocusable = true
        focusTraversalKeysEnabled = false
        addKeyListener(inputKeyListener)
        addMouseWheelListener(viewportWheelListener)
        preferredSize = preferredGridSize(settings.columns, settings.rows)
        cursorTimer.isRepeats = true
    }

    /**
     * Binds this component to [session].
     *
     * The session remains host-owned; this component only observes dirty render
     * notifications and repaints itself on the EDT.
     *
     * @param session terminal session to display.
     */
    fun bind(session: TerminalSession) {
        this.session?.onDirty = null
        this.session = session
        session.onDirty = {
            schedulePublishedFrame()
        }
        scrollbackOffset = 0
        repaintPlanner.reset()
        renderPending.set(false)
        repaint()
    }

    /**
     * Removes the current session binding.
     */
    fun unbind() {
        session?.onDirty = null
        session = null
        scrollbackOffset = 0
        repaintPlanner.reset()
        renderPending.set(false)
        repaint()
    }

    /**
     * Rebuilds settings, metrics, preferred size, and repaint state.
     */
    fun reloadSettings() {
        settings = settingsProvider.currentSettings()
        font = settings.font
        background = Color(settings.palette.defaultBackground, true)
        foreground = Color(settings.palette.defaultForeground, true)
        isOpaque = true
        metrics = buildMetrics(settings)
        preferredSize = preferredGridSize(settings.columns, settings.rows)
        cursorTimer.delay = settings.cursorBlinkMillis
        revalidate()
        repaint()
    }

    /**
     * Returns the grid size that fits in this component's current bounds.
     *
     * @return dimension where width is columns and height is rows.
     */
    fun visibleGridSize(): Dimension {
        return Dimension(
            maxOf(1, width / metrics.cellWidth),
            maxOf(1, height / metrics.cellHeight),
        )
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
                )
            }
            if (painted == null) {
                painter.clear(g, settings.palette, width, height)
            }
        } finally {
            g.dispose()
        }
    }

    private fun handleMouseWheel(event: MouseWheelEvent) {
        val boundSession = session ?: return
        val cache = boundSession.publisher.current() ?: return
        val historySize = cache.historySize
        if (historySize == 0) return

        val delta = -event.unitsToScroll
        if (delta == 0) return

        val nextOffset = (scrollbackOffset + delta).coerceIn(0, historySize)
        if (nextOffset == scrollbackOffset) return

        scrollbackOffset = nextOffset
        boundSession.requestRender(scrollbackOffset)
        event.consume()
    }

    private fun schedulePublishedFrame() {
        if (!renderPending.compareAndSet(false, true)) return

        SwingUtilities.invokeLater {
            renderPending.set(false)
            handlePublishedFrame()
        }
    }

    private fun handlePublishedFrame() {
        val boundSession = session ?: return
        val cache = boundSession.publisher.current()
        if (cache == null) {
            repaint()
            return
        }

        val clampedOffset = scrollbackOffset.coerceIn(0, cache.historySize)
        if (clampedOffset != scrollbackOffset) {
            scrollbackOffset = clampedOffset
        }

        if (cache.scrollbackOffset != scrollbackOffset) {
            boundSession.requestRender(scrollbackOffset)
            return
        }

        repaintPlanner.requestFrameRepaint(
            cache = cache,
            metrics = metrics,
            componentWidth = width,
            componentHeight = height,
            repaintAll = { repaint() },
            repaintRegion = { x, y, regionWidth, regionHeight ->
                repaint(x, y, regionWidth, regionHeight)
            },
        )
    }

    private fun repaintBlinkingCursor() {
        val publisher = session?.publisher ?: return
        publisher.readCurrent { cache ->
            repaintPlanner.requestCursorBlinkRepaint(
                cache = cache,
                metrics = metrics,
                componentHeight = height,
                repaintRegion = { x, y, regionWidth, regionHeight ->
                    repaint(x, y, regionWidth, regionHeight)
                },
            )
        }
    }

    private fun preferredGridSize(columns: Int, rows: Int): Dimension {
        return Dimension(columns * metrics.cellWidth, rows * metrics.cellHeight)
    }

    private fun buildMetrics(settings: TerminalSwingSettings): TerminalSwingMetrics {
        val metricsSource: FontMetrics = getFontMetrics(settings.font)
        return TerminalSwingMetrics.from(metricsSource)
    }
}
