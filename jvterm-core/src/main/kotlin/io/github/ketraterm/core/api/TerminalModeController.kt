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
package io.github.ketraterm.core.api

/**
 * Mode-control contract for the terminal buffer.
 *
 * These toggles affect how subsequent cursor motion and printable writes behave.
 * They do not expose the underlying storage model to the parser.
 */
interface TerminalModeController {
    /**
     * Enables or disables Insert Replace Mode (IRM, `CSI 4 h` / `CSI 4 l`).
     *
     * @param enabled `true` to enable insert mode, `false` for replace mode.
     */
    fun setInsertMode(enabled: Boolean)

    /**
     * Enables or disables DECAWM auto-wrap (`CSI ? 7 h` / `CSI ? 7 l`).
     *
     * @param enabled `true` to enable auto-wrap, `false` to disable.
     */
    fun setAutoWrap(enabled: Boolean)

    /**
     * Enables or disables Origin Mode (DECOM, `CSI ? 6 h` / `CSI ? 6 l`).
     *
     * When enabled, cursor-home semantics become relative to the active scroll
     * region rather than the full viewport.
     *
     * @param enabled `true` to enable origin mode, `false` to disable.
     */
    fun setOriginMode(enabled: Boolean)

    /**
     * Toggles application cursor key mode (DECCKM, `CSI ? 1 h` / `CSI ? 1 l`).
     *
     * @param enabled `true` to enable application cursor keys, `false` to disable.
     */
    fun setApplicationCursorKeys(enabled: Boolean)

    /**
     * Toggles application keypad mode (DECNKM).
     *
     * @param enabled `true` to enable application keypad, `false` to disable.
     */
    fun setApplicationKeypad(enabled: Boolean)

    /**
     * Enables or disables left/right margin mode (DECLRMM, `CSI ? 69 h` / `CSI ? 69 l`).
     *
     * The mode flag is global, while the actual horizontal margins are stored
     * per screen buffer. Enabling or disabling the mode homes the cursor.
     *
     * @param enabled `true` to enable left/right margin mode, `false` to disable.
     */
    fun setLeftRightMarginMode(enabled: Boolean)

    /**
     * Enables or disables New Line Mode (LNM, `CSI 20 h` / `CSI 20 l`).
     *
     * @param enabled `true` to enable new-line mode, `false` to disable.
     */
    fun setNewLineMode(enabled: Boolean)

    /**
     * Sets the active mouse tracking mode used by terminal-to-host reporting.
     *
     * @param mode The mouse tracking mode to set.
     */
    fun setMouseTrackingMode(mode: io.github.ketraterm.protocol.MouseTrackingMode)

    /**
     * Sets the active mouse report encoding mode used by terminal-to-host reporting.
     *
     * @param mode The mouse encoding mode to set.
     */
    fun setMouseEncodingMode(mode: io.github.ketraterm.protocol.MouseEncodingMode)

    /**
     * Enables or disables bracketed paste reporting (`CSI ? 2004 h` / `CSI ? 2004 l`).
     *
     * @param enabled `true` to enable bracketed paste, `false` to disable.
     */
    fun setBracketedPasteEnabled(enabled: Boolean)

    /**
     * Enables or disables focus in/out reporting (`CSI ? 1004 h` / `CSI ? 1004 l`).
     *
     * @param enabled `true` to enable focus reporting, `false` to disable.
     */
    fun setFocusReportingEnabled(enabled: Boolean)

    /**
     * Sets the modify-other-keys reporting level.
     *
     * @param mode The modify-other-keys mode level (0, 1, or 2).
     */
    fun setModifyOtherKeysMode(mode: Int)

    /**
     * Sets the format-other-keys wire format used when modify-other-keys applies.
     *
     * @param mode The format-other-keys mode (typically 4 or 5).
     */
    fun setFormatOtherKeysMode(mode: Int)

    /**
     * Sets active Kitty keyboard progressive-enhancement flags.
     *
     * Core stores the supported bit subset in its packed input-mode word so the
     * input encoder can read one coherent snapshot without touching core
     * internals.
     *
     * @param flags Kitty keyboard progressive-enhancement flags.
     */
    fun setKittyKeyboardFlags(flags: Int)

    /**
     * Pushes the current Kitty keyboard progressive-enhancement flags to the stack,
     * and sets the new active flags.
     *
     * @param flags Kitty keyboard flags to push and activate.
     */
    fun pushKittyKeyboardFlags(flags: Int)

    /**
     * Pops the Kitty keyboard flags from the stack up to [count] times.
     *
     * @param count Number of times to pop from the stack.
     */
    fun popKittyKeyboardFlags(count: Int)

    /**
     * Toggles reverse-video presentation state (DECSCNM, `CSI ? 5 h` / `CSI ? 5 l`).
     *
     * This is renderer-facing state stored in core because the host controls it.
     *
     * @param enabled `true` to enable reverse-video mode, `false` to disable.
     */
    fun setReverseVideo(enabled: Boolean)

    /**
     * Toggles cursor visibility presentation state (DECTCEM, `CSI ? 25 h` / `CSI ? 25 l`).
     *
     * This is renderer-facing state stored in core because the host controls it.
     *
     * @param enabled `true` to make the cursor visible, `false` to hide it.
     */
    fun setCursorVisible(enabled: Boolean)

    /**
     * Toggles cursor blink presentation state.
     *
     * @param enabled `true` to make the cursor blink, `false` to disable blinking.
     */
    fun setCursorBlinking(enabled: Boolean)

    /**
     * Sets the cursor shape/style.
     *
     * @param shape The cursor shape to set.
     */
    fun setCursorShape(shape: io.github.ketraterm.render.api.TerminalRenderCursorShape)

    /**
     * Sets the default cursor shape/style restored on reset.
     *
     * @param shape The default cursor shape to restore.
     */
    fun setDefaultCursorShape(shape: io.github.ketraterm.render.api.TerminalRenderCursorShape)

    /**
     * Controls how East Asian Ambiguous codepoints are measured for future writes.
     *
     * Existing stored content is not reinterpreted when this flag changes.
     *
     * @param enabled `true` to treat East Asian ambiguous-width characters as double-width.
     */
    fun setTreatAmbiguousAsWide(enabled: Boolean)

    /**
     * Toggles synchronized output mode (DECSET/DECRST `?2026`).
     *
     * @param enabled `true` to enable synchronized output, `false` to disable.
     */
    fun setSynchronizedOutput(enabled: Boolean)

    /**
     * Switches to the alternate screen buffer without saving the primary
     * cursor state.
     *
     * This supports the older xterm alternate-screen modes:
     * - `CSI ? 47 h` switches to the alternate buffer without clearing it.
     * - `CSI ? 1047 h` switches to the alternate buffer and clears it first.
     *
     * The alternate buffer has no scrollback history. Re-entering while already
     * active is a no-op.
     *
     * @param clearBeforeEnter whether to clear the alternate grid, reset its
     * margins, home its cursor, and clear its saved-cursor slot before entry.
     */
    fun enterAltBufferWithoutCursorSave(clearBeforeEnter: Boolean)

    /**
     * Returns to the primary screen buffer without restoring a saved cursor.
     *
     * This supports `CSI ? 47 l` and `CSI ? 1047 l`. Alternate buffer content
     * remains stored and may be reused by a later non-clearing entry.
     */
    fun exitAltBufferWithoutCursorRestore()

    /**
     * Switches to the alternate screen buffer with cursor save (`CSI ? 1049 h`).
     *
     * Saves the primary cursor position, pen attributes, and origin mode, clears
     * the alternate grid, resets its scroll margins, homes its cursor, and
     * activates it. The alternate buffer has no scrollback history.
     *
     * Re-entering the alternate buffer discards any previous alternate content.
     */
    fun enterAltBuffer()

    /**
     * Returns to the primary screen buffer with cursor restore (`CSI ? 1049 l`).
     *
     * Restores the primary cursor position, pen attributes, and origin mode
     * saved when [enterAltBuffer] was called. Alternate buffer content is not
     * visible after the switch. Primary scrollback history is unaffected.
     */
    fun exitAltBuffer()

    /**
     * Sets the theme-configured color palette for the terminal session.
     *
     * This updates both the default theme palette and the active palette.
     *
     * @param palette the theme color palette configuration.
     */
    fun setThemePalette(palette: io.github.ketraterm.render.api.TerminalColorPalette)

    /**
     * Updates an individual color index in the active 256-color palette.
     *
     * @param index the color index in range `0..255`.
     * @param color the packed ARGB color value.
     */
    fun setPaletteColor(
        index: Int,
        color: Int,
    )

    /**
     * Updates a dynamic target color (foreground, background, or cursor color).
     *
     * @param target the target code (10 for foreground, 11 for background, 12 for cursor).
     * @param color the packed ARGB color value.
     */
    fun setDynamicColor(
        target: Int,
        color: Int,
    )

    /**
     * Toggles urgent bell mode (?1042).
     *
     * @param enabled `true` to enable urgent bell mode, `false` to disable.
     */
    fun setBellIsUrgent(enabled: Boolean)

    /**
     * Toggles pop on bell mode (?1043).
     *
     * @param enabled `true` to enable pop on bell mode, `false` to disable.
     */
    fun setPopOnBell(enabled: Boolean)
}
