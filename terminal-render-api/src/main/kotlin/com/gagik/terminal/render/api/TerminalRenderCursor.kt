package com.gagik.terminal.render.api

/**
 * Stable public cursor overlay model for renderers.
 *
 * The terminal exposes whether blinking mode is enabled, but not the current
 * blink phase. UI modules own blink timers and repaint cadence.
 * Allocation-sensitive frame consumers should prefer
 * [TerminalRenderFrame.copyCursor] and copy primitive cursor fields directly.
 *
 * @property column zero-based visual cursor column.
 * @property row zero-based visual cursor row.
 * @property visible whether the cursor should be rendered.
 * @property blinking whether cursor blinking mode is enabled.
 * @property shape renderer-facing cursor shape.
 * @property generation generation that changes when position, visibility,
 * blinking mode, or shape changes.
 */
data class TerminalRenderCursor(
    val column: Int,
    val row: Int,
    val visible: Boolean,
    val blinking: Boolean,
    val shape: TerminalRenderCursorShape,
    val generation: Long,
)
