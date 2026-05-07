package com.gagik.terminal.ui.swing

import com.gagik.terminal.render.api.TerminalRenderAttrs
import com.gagik.terminal.render.api.TerminalRenderCellFlags
import com.gagik.terminal.render.api.TerminalRenderCursorShape
import com.gagik.terminal.render.api.TerminalRenderUnderline
import com.gagik.terminal.render.cache.TerminalRenderCache
import com.gagik.terminal.session.TerminalSession
import java.awt.*
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

    private val cursorTimer = Timer(settings.cursorBlinkMillis) {
        cursorBlinkVisible = !cursorBlinkVisible
        repaint()
    }

    init {
        font = settings.font
        background = Color(settings.palette.defaultBackground, true)
        foreground = Color(settings.palette.defaultForeground, true)
        isFocusable = true
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
            g.font = settings.font
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, settings.textAntialiasing)

            val cache = session?.publisher?.current()
            if (cache == null) {
                fill(g, 0, 0, width, height, settings.palette.defaultBackground)
                return
            }

            paintCache(g, cache)
        } finally {
            g.dispose()
        }
    }

    private fun paintCache(g: Graphics2D, cache: TerminalRenderCache) {
        val rows = minOf(cache.rows, height / metrics.cellHeight + 1)
        fill(g, 0, 0, width, height, settings.palette.defaultBackground)

        var row = 0
        while (row < rows) {
            paintRowBackgrounds(g, cache, row)
            paintRowText(g, cache, row)
            row++
        }

        paintCursor(g, cache)
    }

    private fun paintRowBackgrounds(g: Graphics2D, cache: TerminalRenderCache, row: Int) {
        val y = row * metrics.cellHeight
        var column = 0
        while (column < cache.columns) {
            val attr = cache.attrWords[row][column]
            val background = settings.palette.background(attr)
            val start = column

            column++
            while (column < cache.columns && settings.palette.background(cache.attrWords[row][column]) == background) {
                column++
            }

            fill(
                g = g,
                x = start * metrics.cellWidth,
                y = y,
                width = (column - start) * metrics.cellWidth,
                height = metrics.cellHeight,
                argb = background,
            )
        }
    }

    private fun paintRowText(g: Graphics2D, cache: TerminalRenderCache, row: Int) {
        val baselineY = row * metrics.cellHeight + metrics.baseline
        var column = 0
        val builder = StringBuilder(cache.columns)

        while (column < cache.columns) {
            val flags = cache.flags[row][column]
            if (!hasDrawableText(flags)) {
                column++
                continue
            }

            val attr = cache.attrWords[row][column]
            val foreground = settings.palette.foreground(attr)
            val fontStyle = fontStyle(attr)
            val start = column
            builder.setLength(0)

            while (column < cache.columns) {
                val currentFlags = cache.flags[row][column]
                val currentAttr = cache.attrWords[row][column]
                if (
                    !hasDrawableText(currentFlags) ||
                    settings.palette.foreground(currentAttr) != foreground ||
                    fontStyle(currentAttr) != fontStyle ||
                    decorationKey(currentAttr) != decorationKey(attr)
                ) {
                    break
                }

                appendCellText(builder, cache, row, column, currentFlags)
                column += if (currentFlags and TerminalRenderCellFlags.WIDE_LEADING != 0) 2 else 1
            }

            g.font = styleFont(settings.font, fontStyle)
            g.color = Color(foreground, true)
            g.drawString(builder.toString(), start * metrics.cellWidth, baselineY)
            paintDecorations(g, attr, foreground, start, column, row)
        }
    }

    private fun hasDrawableText(flags: Int): Boolean {
        return flags and TerminalRenderCellFlags.CODEPOINT != 0 ||
            flags and TerminalRenderCellFlags.CLUSTER != 0
    }

    private fun appendCellText(
        builder: StringBuilder,
        cache: TerminalRenderCache,
        row: Int,
        column: Int,
        flags: Int,
    ) {
        if (flags and TerminalRenderCellFlags.CLUSTER != 0) {
            builder.append(cache.clusters[row][column].orEmpty())
        } else {
            builder.appendCodePoint(cache.codeWords[row][column])
        }
    }

    private fun paintDecorations(
        g: Graphics2D,
        attr: Long,
        foreground: Int,
        startColumn: Int,
        endColumn: Int,
        row: Int,
    ) {
        val underline = TerminalRenderAttrs.underlineStyle(attr)
        val strikethrough = TerminalRenderAttrs.isStrikethrough(attr)
        if (underline == TerminalRenderUnderline.NONE && !strikethrough) return

        g.color = Color(foreground, true)
        val x = startColumn * metrics.cellWidth
        val width = (endColumn - startColumn) * metrics.cellWidth
        val rowY = row * metrics.cellHeight

        if (underline != TerminalRenderUnderline.NONE) {
            val y = rowY + metrics.underlineY
            g.drawLine(x, y, x + width, y)
            if (underline == TerminalRenderUnderline.DOUBLE) {
                val secondY = minOf(rowY + metrics.cellHeight - 1, y + 2)
                g.drawLine(x, secondY, x + width, secondY)
            }
        }

        if (strikethrough) {
            val y = rowY + metrics.strikethroughY
            g.drawLine(x, y, x + width, y)
        }
    }

    private fun paintCursor(g: Graphics2D, cache: TerminalRenderCache) {
        val cursor = cache.cursor ?: return
        if (!cursor.visible || (cursor.blinking && !cursorBlinkVisible)) return
        if (cursor.column !in 0 until cache.columns || cursor.row !in 0 until cache.rows) return

        val x = cursor.column * metrics.cellWidth
        val y = cursor.row * metrics.cellHeight
        g.color = Color(settings.palette.cursorBackground, true)

        when (cursor.shape) {
            TerminalRenderCursorShape.BLOCK -> g.fillRect(x, y, metrics.cellWidth, metrics.cellHeight)
            TerminalRenderCursorShape.UNDERLINE -> {
                g.fillRect(
                    x,
                    y + metrics.cellHeight - metrics.cursorStrokeWidth,
                    metrics.cellWidth,
                    metrics.cursorStrokeWidth,
                )
            }
            TerminalRenderCursorShape.BAR -> {
                g.fillRect(x, y, metrics.cursorStrokeWidth, metrics.cellHeight)
            }
        }
    }

    private fun fontStyle(attr: Long): Int {
        var style = Font.PLAIN
        if (TerminalRenderAttrs.isBold(attr)) style = style or Font.BOLD
        if (TerminalRenderAttrs.isItalic(attr)) style = style or Font.ITALIC
        return style
    }

    private fun styleFont(base: Font, style: Int): Font {
        return if (style == base.style) base else base.deriveFont(style)
    }

    private fun decorationKey(attr: Long): Int {
        return TerminalRenderAttrs.underlineStyle(attr) or
            (if (TerminalRenderAttrs.isStrikethrough(attr)) STRIKETHROUGH_KEY else 0)
    }

    private fun preferredGridSize(columns: Int, rows: Int): Dimension {
        return Dimension(columns * metrics.cellWidth, rows * metrics.cellHeight)
    }

    private fun buildMetrics(settings: TerminalSwingSettings): TerminalSwingMetrics {
        val metricsSource: FontMetrics = getFontMetrics(settings.font)
        return TerminalSwingMetrics.from(metricsSource)
    }

    private fun fill(g: Graphics2D, x: Int, y: Int, width: Int, height: Int, argb: Int) {
        g.color = Color(argb, true)
        g.fillRect(x, y, width, height)
    }

    private companion object {
        private const val STRIKETHROUGH_KEY = 1 shl 8
    }
}
