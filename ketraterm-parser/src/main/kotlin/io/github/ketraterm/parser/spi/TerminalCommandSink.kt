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
package io.github.ketraterm.parser.spi

import io.github.ketraterm.protocol.AnsiMode
import io.github.ketraterm.protocol.DecPrivateMode
import io.github.ketraterm.protocol.NotificationLevel
import io.github.ketraterm.protocol.ShellIntegrationEvent

/**
 * Parser-facing terminal command sink.
 *
 * This is the narrow semantic handoff boundary from :terminal-parser to :terminal-core.
 *
 * Rules:
 * - The parser emits terminal operations.
 * - The sink/core owns grid physics, bounds clamping, wrapping, margins, storage, and mode persistence.
 * - The parser must not know terminal width, height, cursor bounds, or rendering details.
 */
interface TerminalCommandSink {
    // -------------------------------------------------------------------------
    // Printable ingress
    // -------------------------------------------------------------------------

    /**
     * Writes a single Unicode codepoint to the grid at the cursor position.
     *
     * @param codepoint The Unicode codepoint to write.
     */
    fun writeCodepoint(codepoint: Int)

    /**
     * Writes a pre-segmented multi-codepoint grapheme cluster to the grid.
     *
     * @param codepoints The array of Unicode codepoints forming the cluster.
     * @param length The number of valid codepoints in the array.
     */
    fun writeCluster(
        codepoints: IntArray,
        length: Int,
    )

    /**
     * Appends one grapheme-continuation codepoint to the most recently written
     * printable cell without moving the cursor.
     *
     * The parser uses this when a host read boundary forced an already complete
     * printable prefix to be published for interactive latency, and a later
     * byte proves that the grapheme continues with a combining mark, variation
     * selector, ZWJ sequence member, or similar continuation. The sink/core owns
     * locating and mutating the previous cell.
     *
     * @param codepoint Unicode codepoint to append to the previous grapheme.
     */
    fun appendToPreviousCluster(codepoint: Int)

    // -------------------------------------------------------------------------
    // C0 / ESC structural controls
    // -------------------------------------------------------------------------

    /**
     * Triggers the terminal bell/alert sound (BEL, `0x07`).
     */
    fun bell()

    /**
     * Moves the cursor one column to the left (BS, `0x08`).
     */
    fun backspace()

    /**
     * Advances the cursor to the next tab stop (HT, `0x09`).
     */
    fun tab()

    /**
     * Executes a line feed (LF, `0x0A`), moving the cursor down one row.
     */
    fun lineFeed()

    /**
     * Moves the cursor to the left margin on the current row (CR, `0x0D`).
     */
    fun carriageReturn()

    /**
     * Executes a reverse index (RI, `ESC M`), moving the cursor up one row.
     */
    fun reverseIndex()

    /**
     * Moves the cursor to the left margin on the next row (NEL, `ESC E`).
     */
    fun nextLine()

    /**
     * DECSTR soft terminal reset: CSI ! p.
     *
     * The parser identifies the sequence; the core owns the actual reset semantics.
     */
    fun softReset()

    /**
     * RIS full terminal reset: ESC c.
     *
     * The parser identifies the sequence; the core owns the actual reset semantics.
     */
    fun resetTerminal()

    /**
     * DEC Screen Alignment Test (DECALN): ESC # 8.
     *
     * The parser identifies the sequence; the core owns the actual alignment test semantics.
     */
    fun decaln()

    /**
     * Saves the current cursor position, SGR attributes, wrap state, and origin mode.
     */
    fun saveCursor()

    /**
     * Restores the cursor position, SGR attributes, wrap state, and origin mode.
     */
    fun restoreCursor()

    /**
     * Sets the shape/style of the cursor.
     *
     * @param style The shape/style code.
     */
    fun setCursorStyle(style: Int)

    // -------------------------------------------------------------------------
    // Cursor navigation
    // -------------------------------------------------------------------------

    /**
     * Moves the cursor up by [n] rows.
     *
     * @param n Number of rows to move up.
     */
    fun cursorUp(n: Int)

    /**
     * Moves the cursor down by [n] rows.
     *
     * @param n Number of rows to move down.
     */
    fun cursorDown(n: Int)

    /**
     * Moves the cursor forward (right) by [n] columns.
     *
     * @param n Number of columns to move forward.
     */
    fun cursorForward(n: Int)

    /**
     * Moves the cursor backward (left) by [n] columns.
     *
     * @param n Number of columns to move backward.
     */
    fun cursorBackward(n: Int)

    /**
     * Moves the cursor down by [n] lines and positions it at the beginning of the line.
     *
     * @param n Number of lines to move down.
     */
    fun cursorNextLine(n: Int)

    /**
     * Moves the cursor up by [n] lines and positions it at the beginning of the line.
     *
     * @param n Number of lines to move up.
     */
    fun cursorPreviousLine(n: Int)

    /**
     * Moves the cursor forward (right) by [n] tab stops.
     *
     * @param n Number of tab stops to move forward.
     */
    fun cursorForwardTabs(n: Int)

    /**
     * Moves the cursor backward (left) by [n] tab stops.
     *
     * @param n Number of tab stops to move backward.
     */
    fun cursorBackwardTabs(n: Int)

    /**
     * Column is parser-translated to zero-origin before handoff.
     * The core may clamp; the parser must not.
     *
     * @param col The zero-based column index.
     */
    fun setCursorColumn(col: Int)

    /**
     * Row is parser-translated to zero-origin before handoff.
     * The core may clamp; the parser must not.
     *
     * @param row The zero-based row index.
     */
    fun setCursorRow(row: Int)

    /**
     * Row and column are parser-translated to zero-origin before handoff.
     * The core may clamp; the parser must not.
     *
     * @param row The zero-based row index.
     * @param col The zero-based column index.
     */
    fun setCursorAbsolute(
        row: Int,
        col: Int,
    )

    /**
     * DECSTBM scroll region.
     *
     * Top and bottom are parser-translated to zero-origin before handoff.
     * A bottom value of -1 means the sequence omitted the bottom margin, so the
     * core should use the terminal's current last row.
     *
     * @param top The zero-based top row index.
     * @param bottom The zero-based bottom row index, or -1 to use the bottom of the terminal.
     */
    fun setScrollRegion(
        top: Int,
        bottom: Int,
    )

    /**
     * DECSLRM left/right margins.
     *
     * Left and right are parser-translated to zero-origin before handoff.
     * A right value of -1 means the sequence omitted the right margin, so the
     * core should use the terminal's current last column.
     *
     * @param left The zero-based left column index.
     * @param right The zero-based right column index, or -1 to use the right edge of the terminal.
     */
    fun setLeftRightMargins(
        left: Int,
        right: Int,
    )

    // -------------------------------------------------------------------------
    // Erase / edit / scroll
    // -------------------------------------------------------------------------

    /**
     * Erases cells in the viewport (ED / DECSED).
     *
     * @param mode The erase mode (0 = cursor to end, 1 = start to cursor, 2 = entire screen, 3 = screen and scrollback).
     * @param selective `true` if this is a selective erase (DECSED) that respects protection attributes.
     */
    fun eraseInDisplay(
        mode: Int,
        selective: Boolean,
    )

    /**
     * Erases cells in the active line (EL / DECSEL).
     *
     * @param mode The erase mode (0 = cursor to end, 1 = start to cursor, 2 = entire line).
     * @param selective `true` if this is a selective erase (DECSEL) that respects protection attributes.
     */
    fun eraseInLine(
        mode: Int,
        selective: Boolean,
    )

    /**
     * Erases a VT400 rectangular area (DECERA / DECSERA).
     *
     * Coordinates retain DEC's one-based inclusive representation so the core can apply its active
     * origin-mode policy. A value of `0` denotes an omitted parameter and is resolved by the core.
     *
     * @param top One-based top row.
     * @param left One-based left column.
     * @param bottom One-based bottom row.
     * @param right One-based right column.
     * @param selective `true` for DECSERA, which preserves selectively protected cells.
     */
    fun eraseRectangle(
        top: Int,
        left: Int,
        bottom: Int,
        right: Int,
        selective: Boolean,
    )

    /**
     * Fills a VT420 rectangular area (DECFRA) with [codepoint].
     *
     * Coordinates retain DEC's one-based inclusive representation so the core can apply its active
     * origin-mode policy. A value of `0` denotes an omitted parameter and is resolved by the core.
     *
     * @param codepoint Decimal fill character.
     * @param top One-based top row.
     * @param left One-based left column.
     * @param bottom One-based bottom row.
     * @param right One-based right column.
     */
    fun fillRectangle(
        codepoint: Int,
        top: Int,
        left: Int,
        bottom: Int,
        right: Int,
    )

    /**
     * Copies a VT400 rectangular area (DECCRA).
     *
     * Coordinates retain DEC's one-based inclusive representation so the core can apply active
     * origin-mode policy. Page numbers use DEC's one-based numbering; `0` denotes omission.
     */
    fun copyRectangle(
        sourceTop: Int,
        sourceLeft: Int,
        sourceBottom: Int,
        sourceRight: Int,
        sourcePage: Int,
        destinationTop: Int,
        destinationLeft: Int,
        destinationPage: Int,
    )

    /**
     * Selects the DECSACE extent used by subsequent DECCARA and DECRARA commands.
     *
     * `0` and `1` select the wrapped stream extent; `2` selects the exact rectangular extent.
     * Unsupported values must leave the current selection unchanged.
     */
    fun setAttributeChangeExtent(extent: Int)

    /**
     * Applies VT420 DECCARA visual-attribute changes without changing characters or the pen.
     *
     * Coordinates retain DEC's one-based inclusive representation. [setMask] and [clearMask]
     * use [io.github.ketraterm.protocol.DecRectangleAttribute] bits; the parser has already
     * collapsed ordered SGR-like parameters into their final operations.
     */
    fun changeRectangleAttributes(
        top: Int,
        left: Int,
        bottom: Int,
        right: Int,
        setMask: Int,
        clearMask: Int,
    )

    /**
     * Applies VT420 DECRARA visual-attribute reversals without changing characters or the pen.
     *
     * Coordinates retain DEC's one-based inclusive representation. [reverseMask] uses
     * [io.github.ketraterm.protocol.DecRectangleAttribute] bits.
     */
    fun reverseRectangleAttributes(
        top: Int,
        left: Int,
        bottom: Int,
        right: Int,
        reverseMask: Int,
    )

    /**
     * Inserts blank columns (DECIC) across every row of the active vertical scroll region.
     *
     * The core resolves the cursor and horizontal-margin applicability.
     *
     * @param count Number of columns to insert; parser defaults omitted or zero values to one.
     */
    fun insertColumns(count: Int)

    /**
     * Deletes columns (DECDC) across every row of the active vertical scroll region.
     *
     * The core resolves the cursor and horizontal-margin applicability.
     *
     * @param count Number of columns to delete; parser defaults omitted or zero values to one.
     */
    fun deleteColumns(count: Int)

    /**
     * Inserts [n] blank lines at the cursor row (IL).
     *
     * @param n Number of lines to insert.
     */
    fun insertLines(n: Int)

    /**
     * Deletes [n] lines starting at the cursor row (DL).
     *
     * @param n Number of lines to delete.
     */
    fun deleteLines(n: Int)

    /**
     * Inserts [n] blank characters at the cursor position (ICH).
     *
     * @param n Number of characters to insert.
     */
    fun insertCharacters(n: Int)

    /**
     * Deletes [n] characters starting at the cursor position (DCH).
     *
     * @param n Number of characters to delete.
     */
    fun deleteCharacters(n: Int)

    /**
     * Erases [n] characters starting at the cursor position (ECH).
     *
     * @param n Number of characters to erase.
     */
    fun eraseCharacters(n: Int)

    /**
     * Scrolls the active scroll region up by [n] lines (SU).
     *
     * @param n Number of lines to scroll up.
     */
    fun scrollUp(n: Int)

    /**
     * Scrolls the active scroll region down by [n] lines (SD).
     *
     * @param n Number of lines to scroll down.
     */
    fun scrollDown(n: Int)

    // -------------------------------------------------------------------------
    // Tab stops
    // -------------------------------------------------------------------------

    /**
     * Sets a tab stop at the current cursor column (HTS).
     */
    fun setTabStop()

    /**
     * Clears the tab stop at the current cursor column (TBC 0).
     */
    fun clearTabStop()

    /**
     * Clears all tab stops (TBC 3).
     */
    fun clearAllTabStops()

    // -------------------------------------------------------------------------
    // Modes
    // -------------------------------------------------------------------------

    /**
     * ANSI mode set/reset.
     *
     * Mode ids use the shared [AnsiMode] vocabulary.
     *
     * @param mode The ANSI mode identifier.
     * @param enable `true` to enable the mode, `false` to disable.
     */
    fun setAnsiMode(
        mode: Int,
        enable: Boolean,
    )

    /**
     * DEC private mode set/reset.
     *
     * Mode ids use the shared [DecPrivateMode] vocabulary.
     *
     * @param mode The DEC private mode identifier.
     * @param enable `true` to enable the mode, `false` to disable.
     */
    fun setDecMode(
        mode: Int,
        enable: Boolean,
    )

    /**
     * Xterm key modifier option set, `CSI > Pp ; Pv m`.
     *
     * The parser only identifies the resource id and value; the sink owns
     * deciding which resources are supported and how they affect input-facing
     * mode state.
     *
     * @param resource The resource/modifier identifier.
     * @param value The value to assign to the key modifier option.
     */
    fun setKeyModifierOption(
        resource: Int,
        value: Int,
    )

    /**
     * Resets one xterm key modifier option, `CSI > Pp m`.
     *
     * @param resource The resource/modifier identifier to reset.
     */
    fun resetKeyModifierOption(resource: Int)

    /**
     * Resets all supported xterm key modifier options, `CSI > m`.
     */
    fun resetKeyModifierOptions()

    /**
     * Disables one xterm key modifier option, `CSI > Ps n`.
     *
     * This is distinct from reset: xterm represents disable as resource value
     * `-1`, which must remain observable to a later query.
     *
     * @param resource The resource/modifier identifier to disable.
     */
    fun disableKeyModifierOption(resource: Int)

    /**
     * Requests one xterm key modifier option, `CSI ? Pp m`.
     *
     * @param resource The resource/modifier identifier to report.
     */
    fun requestKeyModifierOption(resource: Int)

    /**
     * Xterm key format option set, `CSI > Pp ; Pv f`.
     *
     * @param resource The resource/format identifier.
     * @param value The value to assign to the key format option.
     */
    fun setKeyFormatOption(
        resource: Int,
        value: Int,
    )

    /**
     * Resets one xterm key format option, `CSI > Pp f`.
     *
     * @param resource The resource/format identifier to reset.
     */
    fun resetKeyFormatOption(resource: Int)

    /**
     * Resets all supported xterm key format options, `CSI > f`.
     */
    fun resetKeyFormatOptions()

    /**
     * Kitty keyboard progressive-enhancement flag application,
     * `CSI = flags ; mode u`.
     *
     * The parser only identifies the flag word and application mode. Core and
     * host own durable state and unsupported-mode policy.
     *
     * @param flags Kitty keyboard progressive-enhancement flags.
     * @param applicationMode The application mode parameter (0 = replace, 1 = push, 2 = pop).
     */
    fun applyKittyKeyboardFlags(
        flags: Int,
        applicationMode: Int,
    )

    /**
     * Kitty keyboard stack push, `CSI > flags u`.
     *
     * The parser only identifies the optional flag word. Core owns stack depth,
     * screen separation, and flag application semantics.
     *
     * @param flags Kitty keyboard flags to push and activate.
     */
    fun pushKittyKeyboardFlags(flags: Int)

    /**
     * Kitty keyboard stack pop, `CSI < count u`.
     *
     * The parser normalizes omitted or zero counts to one before handoff.
     *
     * @param count Number of times to pop from the stack.
     */
    fun popKittyKeyboardFlags(count: Int)

    // -------------------------------------------------------------------------
    // Terminal-to-host responses
    // -------------------------------------------------------------------------

    /**
     * DSR/CPR request: CSI Ps n or CSI ? Ps n.
     *
     * @param mode The DSR mode parameter (e.g. 5 for status, 6 for cursor position).
     * @param decPrivate `true` if this is a DEC private DSR (? prefix), `false` for standard ANSI.
     */
    fun requestDeviceStatusReport(
        mode: Int,
        decPrivate: Boolean,
    )

    /**
     * DA request.
     *
     * Kind values:
     * - 0: primary DA, CSI Ps c
     * - 1: secondary DA, CSI > Ps c
     * - 2: tertiary DA, CSI = Ps c
     *
     * @param kind The device attributes query type (primary, secondary, or tertiary).
     * @param parameter The request parameter/subtype (usually 0).
     */
    fun requestDeviceAttributes(
        kind: Int,
        parameter: Int,
    )

    /**
     * Requests the active Kitty keyboard progressive-enhancement flag report
     * for parameterless `CSI ? u`.
     */
    fun requestKittyKeyboardFlags()

    /**
     * Safe xterm window report request.
     *
     * Supported modes are owned by the sink/core. Window manipulation requests
     * must not be represented here.
     *
     * @param mode The window report mode parameter (e.g. 14 for pixels, 18 for grid cells).
     */
    fun requestWindowReport(mode: Int)

    /**
     * Requests that the host resize the terminal window to the specified grid dimensions.
     *
     * @param rows target row count.
     * @param columns target column count.
     */
    fun resizeWindow(
        rows: Int,
        columns: Int,
    )

    /**
     * Moves the terminal window to the specified screen coordinates in pixels.
     *
     * @param x The target x-coordinate on the screen.
     * @param y The target y-coordinate on the screen.
     */
    fun moveWindow(
        x: Int,
        y: Int,
    )

    /**
     * Minimizes (iconifies) the terminal window.
     */
    fun minimizeWindow()

    /**
     * De-minimizes (restores/de-iconifies) the terminal window.
     */
    fun deminimizeWindow()

    /**
     * Raises the terminal window to the front of the window stack.
     */
    fun raiseWindow()

    /**
     * Lowers the terminal window to the bottom of the window stack.
     */
    fun lowerWindow()

    /**
     * Maximizes or restores the terminal window.
     *
     * @param maximize true to maximize, false to restore.
     */
    fun setMaximized(maximize: Boolean)

    /**
     * Xterm title stack push/pop scopes:
     * - 0: icon and window title
     * - 1: icon title
     * - 2: window title
     *
     * @param scope The title stack target scope (0, 1, or 2).
     */
    fun pushTitleStack(scope: Int)

    /**
     * Pops the xterm title stack for the given scope.
     *
     * @param scope The title stack target scope (0, 1, or 2).
     */
    fun popTitleStack(scope: Int)

    // -------------------------------------------------------------------------
    // SGR / pen attributes
    // -------------------------------------------------------------------------

    /**
     * Resets all active pen attributes to defaults (SGR 0).
     */
    fun resetAttributes()

    /**
     * Sets bold weight.
     *
     * @param enabled `true` to enable bold, `false` to disable.
     */
    fun setBold(enabled: Boolean)

    /**
     * Sets faint (dim) weight.
     *
     * @param enabled `true` to enable faint, `false` to disable.
     */
    fun setFaint(enabled: Boolean)

    /**
     * Sets italic style.
     *
     * @param enabled `true` to enable italic, `false` to disable.
     */
    fun setItalic(enabled: Boolean)

    /**
     * Sets underline style.
     *
     * @param style The underline style code (0 = none, 1 = single, 2 = double, etc.).
     */
    fun setUnderlineStyle(style: Int)

    /**
     * Sets blinking style.
     *
     * @param enabled `true` to enable blinking, `false` to disable.
     */
    fun setBlink(enabled: Boolean)

    /**
     * Sets inverse (reverse-video) style.
     *
     * @param enabled `true` to enable inverse, `false` to disable.
     */
    fun setInverse(enabled: Boolean)

    /**
     * Sets conceal style.
     *
     * @param enabled `true` to enable conceal, `false` to disable.
     */
    fun setConceal(enabled: Boolean)

    /**
     * Sets strikethrough decoration.
     *
     * @param enabled `true` to enable strikethrough, `false` to disable.
     */
    fun setStrikethrough(enabled: Boolean)

    /**
     * Sets overline decoration.
     *
     * @param enabled `true` to enable overline, `false` to disable.
     */
    fun setOverline(enabled: Boolean)

    /**
     * Sets selective erase protection (DECSCA).
     *
     * @param enabled `true` to protect cells from erasure, `false` to disable protection.
     */
    fun setSelectiveEraseProtection(enabled: Boolean)

    /**
     * Resets foreground color to the default.
     */
    fun setForegroundDefault()

    /**
     * Resets background color to the default.
     */
    fun setBackgroundDefault()

    /**
     * Resets underline color to the default.
     */
    fun setUnderlineColorDefault()

    /**
     * Sets foreground indexed color.
     *
     * @param index Palette index (0..255).
     */
    fun setForegroundIndexed(index: Int)

    /**
     * Sets background indexed color.
     *
     * @param index Palette index (0..255).
     */
    fun setBackgroundIndexed(index: Int)

    /**
     * Sets underline indexed color.
     *
     * @param index Palette index (0..255).
     */
    fun setUnderlineColorIndexed(index: Int)

    /**
     * Sets foreground RGB color.
     *
     * @param red Red component (0..255).
     * @param green Green component (0..255).
     * @param blue Blue component (0..255).
     */
    fun setForegroundRgb(
        red: Int,
        green: Int,
        blue: Int,
    )

    /**
     * Sets background RGB color.
     *
     * @param red Red component (0..255).
     * @param green Green component (0..255).
     * @param blue Blue component (0..255).
     */
    fun setBackgroundRgb(
        red: Int,
        green: Int,
        blue: Int,
    )

    /**
     * Sets underline RGB color.
     *
     * @param red Red component (0..255).
     * @param green Green component (0..255).
     * @param blue Blue component (0..255).
     */
    fun setUnderlineColorRgb(
        red: Int,
        green: Int,
        blue: Int,
    )

    // -------------------------------------------------------------------------
    // OSC
    // -------------------------------------------------------------------------

    /**
     * Sets the window title.
     *
     * @param title The new window title.
     */
    fun setWindowTitle(title: String)

    /**
     * Sets the icon title.
     *
     * @param title The new icon title.
     */
    fun setIconTitle(title: String)

    /**
     * Sets both icon and window titles.
     *
     * @param title The new title.
     */
    fun setIconAndWindowTitle(title: String)

    /**
     * Reports the shell's current working directory as an OSC 7 file URI.
     *
     * The parser only recognizes and decodes the protocol payload. Host layers
     * own URI validation, retention limits, and application-facing policy.
     *
     * @param uri raw current-working-directory URI from terminal output.
     */
    fun setCurrentWorkingDirectoryUri(uri: String)

    /**
     * Starts an OSC 8 hyperlink context.
     *
     * @param uri Target URI.
     * @param id Optional hyperlink identifier.
     */
    fun startHyperlink(
        uri: String,
        id: String?,
    )

    /**
     * Ends the active OSC 8 hyperlink context.
     */
    fun endHyperlink()

    /**
     * Reports an OSC 52 terminal clipboard request.
     *
     * The parser only recognizes the sequence shape and passes bounded payload
     * fields through. Hosts own permission prompts, local-vs-remote policy,
     * payload decoding, clipboard writes, query responses, and audit behavior.
     *
     * @param selection clipboard selection designator such as `c`, `p`, or
     * multiple xterm selection letters.
     * @param encodedData base64 clipboard payload, `?` for read/query requests,
     * or an empty payload for clear/write-style requests.
     */
    fun requestClipboard(
        selection: String,
        encodedData: String,
    ) = Unit

    /**
     * Sets a specific ANSI indexed color.
     *
     * @param index Color index (0..255).
     * @param color The packed ARGB color value.
     */
    fun setPaletteColor(
        index: Int,
        color: Int,
    )

    /**
     * Queries an individual color in the active 256-color palette.
     *
     * @param index Color index to query.
     */
    fun queryPaletteColor(index: Int)

    /**
     * Sets a dynamic color (foreground, background, or cursor color).
     *
     * @param target Target color identifier (10 for foreground, 11 for background, 12 for cursor).
     * @param color The packed ARGB color value.
     */
    fun setDynamicColor(
        target: Int,
        color: Int,
    )

    /**
     * Queries a dynamic color.
     *
     * @param target Target color identifier to query (10 for foreground, 11 for background, 12 for cursor).
     */
    fun queryDynamicColor(target: Int)

    /**
     * Queries a status string (DECRQSS).
     *
     * @param query The status parameter query string.
     */
    fun queryStatusString(query: String)

    /**
     * Queries terminfo capabilities (XTGETTCAP).
     *
     * @param rawPayload Semicolon-separated capability names payload.
     */
    fun queryTerminfo(rawPayload: String)

    /**
     * Emits a FinalTerm-style OSC 133 shell integration marker.
     *
     * The parser recognizes the marker syntax only. Host/workspace layers own
     * any command metadata storage, navigation, decorations, or UI policy.
     *
     * @param event typed shell integration marker event.
     */
    fun shellIntegrationMarker(event: ShellIntegrationEvent)

    /**
     * Requests a desktop notification.
     *
     * @param title notification title.
     * @param body notification body.
     * @param level notification severity level.
     */
    fun showNotification(
        title: String,
        body: String,
        level: NotificationLevel,
    )
}
