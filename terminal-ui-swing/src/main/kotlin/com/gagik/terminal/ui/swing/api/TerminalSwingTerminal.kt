package com.gagik.terminal.ui.swing.api

import com.gagik.terminal.render.cache.TerminalRenderCache
import com.gagik.terminal.session.TerminalSession
import com.gagik.terminal.ui.swing.input.TerminalSwingKeyMapper
import com.gagik.terminal.ui.swing.render.TerminalGridPainter
import com.gagik.terminal.ui.swing.settings.TerminalSwingMetrics
import com.gagik.terminal.ui.swing.settings.TerminalSwingSettings
import com.gagik.terminal.ui.swing.settings.TerminalSwingSettingsProvider
import java.awt.*
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
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

    private val painter = TerminalGridPainter()
    private val cursorTimer = Timer(settings.cursorBlinkMillis) {
        cursorBlinkVisible = !cursorBlinkVisible
        repaint()
    }

    private val inputKeyListener = object : KeyAdapter() {
        override fun keyPressed(event: KeyEvent) {
            val keyEvent = TerminalSwingKeyMapper.keyPressed(event) ?: return
            session?.encodeKey(keyEvent)
            event.consume()
        }

        override fun keyTyped(event: KeyEvent) {
            val keyEvent = TerminalSwingKeyMapper.keyTyped(event) ?: return
            session?.encodeKey(keyEvent)
            event.consume()
        }
    }

    init {
        font = settings.font
        background = Color(settings.palette.defaultBackground, true)
        foreground = Color(settings.palette.defaultForeground, true)
        isFocusable = true
        focusTraversalKeysEnabled = false
        addKeyListener(inputKeyListener)
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
            SwingUtilities.invokeLater {
                repaint()
            }
        }
        repaint()
    }

    /**
     * Removes the current session binding.
     */
    fun unbind() {
        session?.onDirty = null
        session = null
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
            val cache: TerminalRenderCache? = session?.publisher?.current()
            if (cache == null) {
                painter.clear(g, settings.palette, width, height)
                return
            }

            painter.paint(
                g = g,
                cache = cache,
                settings = settings,
                metrics = metrics,
                width = width,
                height = height,
                cursorBlinkVisible = cursorBlinkVisible,
            )
        } finally {
            g.dispose()
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
