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
package com.gagik.core.api

import com.gagik.terminal.protocol.MouseEncodingMode
import com.gagik.terminal.protocol.MouseTrackingMode
import com.gagik.terminal.render.api.TerminalRenderCursorShape

/**
 * Mode-control contract for the terminal buffer.
 *
 * These toggles affect how subsequent cursor motion and printable writes behave.
 * They do not expose the underlying storage model to the parser.
 */
interface TerminalModeController {
    /** Enables or disables Insert Replace Mode (IRM, `CSI 4 h` / `CSI 4 l`). */
    fun setInsertMode(enabled: Boolean)

    /** Enables or disables DECAWM auto-wrap (`CSI ? 7 h` / `CSI ? 7 l`). */
    fun setAutoWrap(enabled: Boolean)

    /**
     * Enables or disables Origin Mode (DECOM, `CSI ? 6 h` / `CSI ? 6 l`).
     *
     * When enabled, cursor-home semantics become relative to the active scroll
     * region rather than the full viewport.
     */
    fun setOriginMode(enabled: Boolean)

    /** Toggles application cursor key mode (DECCKM, `CSI ? 1 h` / `CSI ? 1 l`). */
    fun setApplicationCursorKeys(enabled: Boolean)

    /** Toggles application keypad mode (DECNKM). */
    fun setApplicationKeypad(enabled: Boolean)

    /**
     * Enables or disables left/right margin mode (DECLRMM, `CSI ? 69 h` / `CSI ? 69 l`).
     *
     * The mode flag is global, while the actual horizontal margins are stored
     * per screen buffer. Enabling or disabling the mode homes the cursor.
     */
    fun setLeftRightMarginMode(enabled: Boolean)

    /** Enables or disables New Line Mode (LNM, `CSI 20 h` / `CSI 20 l`). */
    fun setNewLineMode(enabled: Boolean)

    /** Sets the active mouse tracking mode used by terminal-to-host reporting. */
    fun setMouseTrackingMode(mode: MouseTrackingMode)

    /** Sets the active mouse report encoding mode used by terminal-to-host reporting. */
    fun setMouseEncodingMode(mode: MouseEncodingMode)

    /** Enables or disables bracketed paste reporting (`CSI ? 2004 h` / `CSI ? 2004 l`). */
    fun setBracketedPasteEnabled(enabled: Boolean)

    /** Enables or disables focus in/out reporting (`CSI ? 1004 h` / `CSI ? 1004 l`). */
    fun setFocusReportingEnabled(enabled: Boolean)

    /** Sets the modify-other-keys reporting level. */
    fun setModifyOtherKeysMode(mode: Int)

    /** Sets the format-other-keys wire format used when modify-other-keys applies. */
    fun setFormatOtherKeysMode(mode: Int)

    /**
     * Toggles reverse-video presentation state (DECSCNM, `CSI ? 5 h` / `CSI ? 5 l`).
     *
     * This is renderer-facing state stored in core because the host controls it.
     */
    fun setReverseVideo(enabled: Boolean)

    /**
     * Toggles cursor visibility presentation state (DECTCEM, `CSI ? 25 h` / `CSI ? 25 l`).
     *
     * This is renderer-facing state stored in core because the host controls it.
     */
    fun setCursorVisible(enabled: Boolean)

    /** Toggles cursor blink presentation state. */
    fun setCursorBlinking(enabled: Boolean)

    /** Sets the cursor shape/style. */
    fun setCursorShape(shape: TerminalRenderCursorShape)

    /**
     * Controls how East Asian Ambiguous codepoints are measured for future writes.
     *
     * Existing stored content is not reinterpreted when this flag changes.
     */
    fun setTreatAmbiguousAsWide(enabled: Boolean)

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
}
