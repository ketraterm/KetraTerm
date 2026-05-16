package com.gagik.core.state

import com.gagik.core.model.*

/**
 * The global hardware context of the terminal.
 *
 * Owns the global mode flags, pen, dimensions, and tab stops, and routes all
 * read/write operations to the correct [ScreenBuffer] via [activeBuffer].
 *
 * [primaryBuffer] and [altBuffer] each own their own memory arenas
 * ([ScreenBuffer.store] + [ScreenBuffer.ring]) so a resize of one buffer
 * can never corrupt the other.
 */
internal class TerminalState(
    initialWidth: Int,
    initialHeight: Int,
    maxHistory: Int,
) {
    // Global hardware state.

    val modes = TerminalModes()
    val tabStops = TabStops(initialWidth)
    val pen = Pen()
    val dimensions = GridDimensions(initialWidth, initialHeight)
    val hostResponses = HostResponseQueue()
    var windowPixelWidth: Int = 0
    var windowPixelHeight: Int = 0
    var windowTitle: String = ""
        set(value) {
            if (field != value) {
                field = value
                markVisualChanged()
            }
        }
    var iconTitle: String = ""
        set(value) {
            if (field != value) {
                field = value
                markVisualChanged()
            }
        }

    var frameGeneration: Long = 0L
        private set

    var structureGeneration: Long = 0L
        private set

    var cursorGeneration: Long = 0L
        private set

    var lastPrintableRow: Int = NO_PRINTABLE_CELL
        private set

    var lastPrintableCol: Int = NO_PRINTABLE_CELL
        private set

    // Physical screens.

    val primaryBuffer = ScreenBuffer(initialWidth, initialHeight, maxHistory)
        .apply { clearGrid(pen.currentAttr, pen.currentExtendedAttr, initialHeight) }

    /** Alternate buffer always has zero scrollback. */
    val altBuffer = ScreenBuffer(initialWidth, initialHeight, maxHistory = 0)
        .apply { clearGrid(pen.currentAttr, pen.currentExtendedAttr, initialHeight) }

    // Hot-swap pointer.

    var activeBuffer: ScreenBuffer = primaryBuffer
        private set

    val isAltScreenActive: Boolean
        get() = activeBuffer === altBuffer

    /**
     * Switches to the alternate screen.
     *
     * When [clearBeforeEnter] is true, the alternate grid, margins, cursor, and
     * saved-cursor slot are reset before activation. This is used by `1047` and
     * `1049`. Non-clearing `47` entries reuse the alternate buffer's existing
     * content and cursor state.
     *
     * No-op when already in the alternate screen.
     */
    fun enterAltScreen(clearBeforeEnter: Boolean) {
        if (isAltScreenActive) return
        if (clearBeforeEnter) {
            altBuffer.clearGrid(pen.blankAttr, pen.blankExtendedAttr, dimensions.height)
            altBuffer.resetScrollRegion(dimensions.height)
            altBuffer.resetLeftRightMargins(dimensions.width)
            altBuffer.cursor.col = 0
            altBuffer.cursor.row = 0
            altBuffer.cursor.pendingWrap = false
            altBuffer.savedCursor.clear()
        }
        activeBuffer = altBuffer
    }

    /**
     * Returns to the primary screen (`CSI ? 1049 l`).
     *
     * Callers must invoke `CursorEngine.restoreCursor()` after this call (DECRC).
     * Alternate content becomes invisible after the switch and will be discarded
     * on the next alternate entry or resize.
     */
    fun exitAltScreen() {
        if (!isAltScreenActive) return
        activeBuffer = primaryBuffer
    }

    // Transparent engine accessors.

    val ring
        get() = activeBuffer.ring
    val cursor: Cursor
        get() = activeBuffer.cursor
    val savedCursor
        get() = activeBuffer.savedCursor
    val scrollTop
        get() = activeBuffer.scrollTop
    val scrollBottom
        get() = activeBuffer.scrollBottom
    val effectiveLeftMargin: Int
        get() = if (modes.isLeftRightMarginMode) activeBuffer.leftMargin else 0
    val effectiveRightMargin: Int
        get() = if (modes.isLeftRightMarginMode) activeBuffer.rightMargin else dimensions.width - 1

    val isFullViewportScroll: Boolean
        get() = activeBuffer.isFullViewportScroll(dimensions.height)
    val historySize: Int
        get() = (activeBuffer.ring.size - dimensions.height).coerceAtLeast(0)

    fun resolveRingIndex(viewportRow: Int): Int =
        liveViewportTopIndex() + viewportRow

    fun clampScrollbackOffset(scrollbackOffset: Int): Int =
        scrollbackOffset.coerceIn(0, historySize)

    fun resolveScrollbackRingIndex(viewportRow: Int, scrollbackOffset: Int): Int =
        liveViewportTopIndex() - clampScrollbackOffset(scrollbackOffset) + viewportRow

    private fun liveViewportTopIndex(): Int =
        (activeBuffer.ring.size - dimensions.height).coerceAtLeast(0)

    // Convenience helpers.

    fun markVisualChanged() {
        frameGeneration++
    }

    fun markLineChanged(line: Line) {
        markVisualChanged()
        line.renderGeneration = frameGeneration
    }

    fun markVisibleLinesChanged() {
        for (row in 0 until dimensions.height) {
            markLineChanged(ring[resolveRingIndex(row)])
        }
    }

    fun markStructureChanged() {
        markVisualChanged()
        structureGeneration++
    }

    fun markCursorChanged() {
        markVisualChanged()
        cursorGeneration++
    }

    fun rememberPrintableCell(row: Int, col: Int) {
        lastPrintableRow = row
        lastPrintableCol = col
    }

    fun clearLastPrintableCell() {
        lastPrintableRow = NO_PRINTABLE_CELL
        lastPrintableCol = NO_PRINTABLE_CELL
    }

    /**
     * Clears the phantom-column pending-wrap flag and any pending printable
     * continuation target on the active cursor.
     */
    fun cancelPendingWrap() {
        activeBuffer.cursor.pendingWrap = false
        clearLastPrintableCell()
    }

    private companion object {
        private const val NO_PRINTABLE_CELL: Int = -1
    }
}
