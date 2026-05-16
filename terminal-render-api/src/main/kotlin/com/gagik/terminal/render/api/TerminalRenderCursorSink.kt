package com.gagik.terminal.render.api

/**
 * Receives primitive cursor overlay state from a short-lived render frame.
 *
 * This sink lets render caches copy cursor data without requiring a
 * [TerminalRenderCursor] object allocation on every published frame.
 */
fun interface TerminalRenderCursorSink {
    /**
     * Copies one cursor snapshot.
     *
     * @param column zero-based visual cursor column.
     * @param row zero-based visual cursor row.
     * @param visible whether the cursor should be rendered.
     * @param blinking whether cursor blinking mode is enabled.
     * @param shape renderer-facing cursor shape.
     * @param generation generation that changes when cursor presentation changes.
     */
    fun onCursor(
        column: Int,
        row: Int,
        visible: Boolean,
        blinking: Boolean,
        shape: TerminalRenderCursorShape,
        generation: Long,
    )
}
