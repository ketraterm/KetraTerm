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
public interface TerminalCommandSink {
    /**
     * Actual active screen, observed synchronously on the parser's serialized command thread.
     * Must reflect effective transitions before [setDecMode] returns, including rejected or
     * repeated requests. Parser uses it for screen-local charset saves; do not shadow host state.
     * A sink without an alternate screen returns false.
     */
    public val isAlternateScreenActive: Boolean

    /** Requests one ANSI or DEC private mode status; host policy controls replies. */
    public fun requestModeStatus(
        mode: Int,
        decPrivate: Boolean,
    )

    // -------------------------------------------------------------------------
    // Printable ingress
    // -------------------------------------------------------------------------

    /**
     * Writes a single Unicode codepoint to the grid at the cursor position.
     *
     * @param codepoint The Unicode codepoint to write.
     */
    public fun writeCodepoint(codepoint: Int)

    /**
     * Writes a pre-segmented multi-codepoint grapheme cluster to the grid.
     *
     * The array is borrowed for this call only; the sink must consume or copy its used
     * prefix synchronously. The parser reuses it for subsequent publications.
     *
     * @param codepoints The array of Unicode codepoints forming the cluster.
     * @param length The number of valid codepoints in the array.
     */
    public fun writeCluster(
        codepoints: IntArray,
        length: Int,
    )

    /**
     * Updates the most recently published grapheme with its complete retained sequence.
     *
     * The sequence includes the previously published prefix and newly retained continuations.
     * Updates are published at read boundaries or before the next grapheme or structural
     * command. The sink owns the target cell, original attributes, width, and cursor effects.
     * As with [writeCluster], the array is borrowed only for the duration of this call.
     *
     * @param codepoints Complete retained codepoints of the same grapheme.
     * @param length Number of valid codepoints in the array.
     */
    public fun updatePreviousCluster(
        codepoints: IntArray,
        length: Int,
    )

    // -------------------------------------------------------------------------
    // C0 / ESC structural controls
    // -------------------------------------------------------------------------

    /**
     * Triggers the terminal bell/alert sound (BEL, `0x07`).
     */
    public fun bell()

    /**
     * Moves the cursor one column to the left (BS, `0x08`).
     */
    public fun backspace()

    /**
     * Advances the cursor to the next tab stop (HT, `0x09`).
     */
    public fun tab()

    /**
     * Executes a line feed (LF, `0x0A`), moving the cursor down one row.
     */
    public fun lineFeed()

    /**
     * Moves the cursor to the left margin on the current row (CR, `0x0D`).
     */
    public fun carriageReturn()

    /**
     * Executes a reverse index (RI, `ESC M`), moving the cursor up one row.
     */
    public fun reverseIndex()

    /**
     * Moves the cursor to the left margin on the next row (NEL, `ESC E`).
     */
    public fun nextLine()

    /**
     * DECSTR soft terminal reset: CSI ! p.
     *
     * The parser identifies the sequence; the core owns the actual reset semantics.
     */
    public fun softReset()

    /**
     * RIS full terminal reset: ESC c.
     *
     * The parser identifies the sequence; the core owns the actual reset semantics.
     */
    public fun resetTerminal()

    /**
     * DEC Screen Alignment Test (DECALN): ESC # 8.
     *
     * The parser identifies the sequence; the core owns the actual alignment test semantics.
     */
    public fun decaln()

    /**
     * Saves the current cursor position, SGR attributes, wrap state, and origin mode.
     */
    public fun saveCursor()

    /**
     * Resolves parameterless CSI s against the current horizontal-margin mode (DECLRMM).
     *
     * With DECLRMM disabled, saves the same state as [saveCursor] and returns `true`.
     * Otherwise resets left/right margins to the full width, with normal DECSLRM cursor
     * homing, and returns `false`. The parser saves its charset state only on `true`.
     * Mode ownership stays with the sink; implementations must not cache a second mode flag.
     */
    public fun saveCursorOrResetMargins(): Boolean

    /**
     * Restores the cursor position, SGR attributes, wrap state, and origin mode.
     */
    public fun restoreCursor()

    /**
     * Sets the shape/style of the cursor.
     *
     * @param style The shape/style code.
     */
    public fun setCursorStyle(style: Int)

    // -------------------------------------------------------------------------
    // Cursor navigation
    // -------------------------------------------------------------------------

    /**
     * Moves the cursor up by [n] rows.
     *
     * @param n Number of rows to move up.
     */
    public fun cursorUp(n: Int)

    /**
     * Moves the cursor down by [n] rows.
     *
     * @param n Number of rows to move down.
     */
    public fun cursorDown(n: Int)

    /**
     * Moves the cursor forward (right) by [n] columns.
     *
     * @param n Number of columns to move forward.
     */
    public fun cursorForward(n: Int)

    /**
     * Moves the cursor backward (left) by [n] columns.
     *
     * @param n Number of columns to move backward.
     */
    public fun cursorBackward(n: Int)

    /**
     * Moves the cursor down by [n] lines and positions it at the beginning of the line.
     *
     * @param n Number of lines to move down.
     */
    public fun cursorNextLine(n: Int)

    /**
     * Moves the cursor up by [n] lines and positions it at the beginning of the line.
     *
     * @param n Number of lines to move up.
     */
    public fun cursorPreviousLine(n: Int)

    /**
     * Moves the cursor forward (right) by [n] tab stops.
     *
     * @param n Number of tab stops to move forward.
     */
    public fun cursorForwardTabs(n: Int)

    /**
     * Moves the cursor backward (left) by [n] tab stops.
     *
     * @param n Number of tab stops to move backward.
     */
    public fun cursorBackwardTabs(n: Int)

    /**
     * Column is parser-translated to zero-origin before handoff.
     * The core may clamp; the parser must not.
     *
     * @param col The zero-based column index.
     */
    public fun setCursorColumn(col: Int)

    /**
     * Row is parser-translated to zero-origin before handoff.
     * The core may clamp; the parser must not.
     *
     * @param row The zero-based row index.
     */
    public fun setCursorRow(row: Int)

    /**
     * Row and column are parser-translated to zero-origin before handoff.
     * The core may clamp; the parser must not.
     *
     * @param row The zero-based row index.
     * @param col The zero-based column index.
     */
    public fun setCursorAbsolute(
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
    public fun setScrollRegion(
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
    public fun setLeftRightMargins(
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
    public fun eraseInDisplay(
        mode: Int,
        selective: Boolean,
    )

    /**
     * Erases cells in the active line (EL / DECSEL).
     *
     * @param mode The erase mode (0 = cursor to end, 1 = start to cursor, 2 = entire line).
     * @param selective `true` if this is a selective erase (DECSEL) that respects protection attributes.
     */
    public fun eraseInLine(
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
    public fun eraseRectangle(
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
    public fun fillRectangle(
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
    public fun copyRectangle(
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
     * Requests a VT420 rectangular-area checksum (DECRQCRA).
     *
     * Coordinates retain DEC's one-based inclusive representation so core can
     * apply the active origin-mode policy. [page] uses DEC's one-based page
     * numbering; `0` denotes omission. The host/core response path owns page
     * capability policy and emits no bytes for unsupported requests.
     */
    public fun requestRectangleChecksum(
        requestId: Int,
        page: Int,
        top: Int,
        left: Int,
        bottom: Int,
        right: Int,
    )

    /**
     * Selects the DECSACE extent used by subsequent DECCARA and DECRARA commands.
     *
     * `0` and `1` select the wrapped stream extent; `2` selects the exact rectangular extent.
     * Unsupported values must leave the current selection unchanged.
     */
    public fun setAttributeChangeExtent(extent: Int)

    /**
     * Applies VT420 DECCARA visual-attribute changes without changing characters or the pen.
     *
     * Coordinates retain DEC's one-based inclusive representation. [setMask] and [clearMask]
     * use [io.github.ketraterm.protocol.DecRectangleAttribute] bits; the parser has already
     * collapsed ordered SGR-like parameters into their final operations.
     */
    public fun changeRectangleAttributes(
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
    public fun reverseRectangleAttributes(
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
    public fun insertColumns(count: Int)

    /**
     * Deletes columns (DECDC) across every row of the active vertical scroll region.
     *
     * The core resolves the cursor and horizontal-margin applicability.
     *
     * @param count Number of columns to delete; parser defaults omitted or zero values to one.
     */
    public fun deleteColumns(count: Int)

    /**
     * Inserts [n] blank lines at the cursor row (IL).
     *
     * @param n Number of lines to insert.
     */
    public fun insertLines(n: Int)

    /**
     * Deletes [n] lines starting at the cursor row (DL).
     *
     * @param n Number of lines to delete.
     */
    public fun deleteLines(n: Int)

    /**
     * Inserts [n] blank characters at the cursor position (ICH).
     *
     * @param n Number of characters to insert.
     */
    public fun insertCharacters(n: Int)

    /**
     * Deletes [n] characters starting at the cursor position (DCH).
     *
     * @param n Number of characters to delete.
     */
    public fun deleteCharacters(n: Int)

    /**
     * Erases [n] characters starting at the cursor position (ECH).
     *
     * @param n Number of characters to erase.
     */
    public fun eraseCharacters(n: Int)

    /**
     * Scrolls the active scroll region up by [n] lines (SU).
     *
     * @param n Number of lines to scroll up.
     */
    public fun scrollUp(n: Int)

    /**
     * Scrolls the active scroll region down by [n] lines (SD).
     *
     * @param n Number of lines to scroll down.
     */
    public fun scrollDown(n: Int)

    // -------------------------------------------------------------------------
    // Tab stops
    // -------------------------------------------------------------------------

    /**
     * Sets a tab stop at the current cursor column (HTS).
     */
    public fun setTabStop()

    /**
     * Clears the tab stop at the current cursor column (TBC 0).
     */
    public fun clearTabStop()

    /**
     * Clears all tab stops (TBC 3).
     */
    public fun clearAllTabStops()

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
    public fun setAnsiMode(
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
    public fun setDecMode(
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
    public fun setKeyModifierOption(
        resource: Int,
        value: Int,
    )

    /**
     * Resets one xterm key modifier option, `CSI > Pp m`.
     *
     * @param resource The resource/modifier identifier to reset.
     */
    public fun resetKeyModifierOption(resource: Int)

    /**
     * Resets all supported xterm key modifier options, `CSI > m`.
     */
    public fun resetKeyModifierOptions()

    /**
     * Disables one xterm key modifier option, `CSI > Ps n`.
     *
     * This is distinct from reset: xterm represents disable as resource value
     * `-1`, which must remain observable to a later query.
     *
     * @param resource The resource/modifier identifier to disable.
     */
    public fun disableKeyModifierOption(resource: Int)

    /**
     * Requests one xterm key modifier option, `CSI ? Pp m`.
     *
     * @param resource The resource/modifier identifier to report.
     */
    public fun requestKeyModifierOption(resource: Int)

    /** Requests an xterm key-format resource (XTQFMTKEYS); the host owns response permission. */
    public fun requestKeyFormatOption(resource: Int)

    /**
     * Xterm key format option set, `CSI > Pp ; Pv f`.
     *
     * @param resource The resource/format identifier.
     * @param value The value to assign to the key format option.
     */
    public fun setKeyFormatOption(
        resource: Int,
        value: Int,
    )

    /**
     * Resets one xterm key format option, `CSI > Pp f`.
     *
     * @param resource The resource/format identifier to reset.
     */
    public fun resetKeyFormatOption(resource: Int)

    /**
     * Resets all supported xterm key format options, `CSI > f`.
     */
    public fun resetKeyFormatOptions()

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
    public fun applyKittyKeyboardFlags(
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
    public fun pushKittyKeyboardFlags(flags: Int)

    /**
     * Kitty keyboard stack pop, `CSI < count u`.
     *
     * The parser normalizes omitted or zero counts to one before handoff.
     *
     * @param count Number of times to pop from the stack.
     */
    public fun popKittyKeyboardFlags(count: Int)

    // -------------------------------------------------------------------------
    // Terminal-to-host responses
    // -------------------------------------------------------------------------

    /**
     * DSR/CPR request: CSI Ps n or CSI ? Ps n.
     *
     * @param mode The DSR mode parameter (e.g. 5 for status, 6 for cursor position).
     * @param decPrivate `true` if this is a DEC private DSR (? prefix), `false` for standard ANSI.
     */
    public fun requestDeviceStatusReport(
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
    public fun requestDeviceAttributes(
        kind: Int,
        parameter: Int,
    )

    /**
     * Requests the active Kitty keyboard progressive-enhancement flag report
     * for parameterless `CSI ? u`.
     */
    public fun requestKittyKeyboardFlags()

    /**
     * Safe xterm window report request.
     *
     * Supported modes are owned by the sink/core. Window manipulation requests
     * must not be represented here.
     *
     * @param mode The window report mode parameter (e.g. 14 for pixels, 18 for grid cells).
     */
    public fun requestWindowReport(mode: Int)

    /**
     * Requests that the host resize the terminal window to the specified grid dimensions.
     *
     * @param rows target row count.
     * @param columns target column count.
     */
    public fun resizeWindow(
        rows: Int,
        columns: Int,
    )

    /**
     * Moves the terminal window to the specified screen coordinates in pixels.
     *
     * @param x The target x-coordinate on the screen.
     * @param y The target y-coordinate on the screen.
     */
    public fun moveWindow(
        x: Int,
        y: Int,
    )

    /**
     * Minimizes (iconifies) the terminal window.
     */
    public fun minimizeWindow()

    /**
     * De-minimizes (restores/de-iconifies) the terminal window.
     */
    public fun deminimizeWindow()

    /**
     * Raises the terminal window to the front of the window stack.
     */
    public fun raiseWindow()

    /**
     * Lowers the terminal window to the bottom of the window stack.
     */
    public fun lowerWindow()

    /**
     * Maximizes or restores the terminal window.
     *
     * @param maximize true to maximize, false to restore.
     */
    public fun setMaximized(maximize: Boolean)

    /**
     * Xterm title stack push/pop scopes:
     * - 0: icon and window title
     * - 1: icon title
     * - 2: window title
     *
     * @param scope The title stack target scope (0, 1, or 2).
     */
    public fun pushTitleStack(scope: Int)

    /**
     * Pops the xterm title stack for the given scope.
     *
     * @param scope The title stack target scope (0, 1, or 2).
     */
    public fun popTitleStack(scope: Int)

    // -------------------------------------------------------------------------
    // SGR / pen attributes
    // -------------------------------------------------------------------------

    /**
     * Resets all active pen attributes to defaults (SGR 0).
     */
    public fun resetAttributes()

    /**
     * Sets bold weight.
     *
     * @param enabled `true` to enable bold, `false` to disable.
     */
    public fun setBold(enabled: Boolean)

    /**
     * Sets faint (dim) weight.
     *
     * @param enabled `true` to enable faint, `false` to disable.
     */
    public fun setFaint(enabled: Boolean)

    /**
     * Sets italic style.
     *
     * @param enabled `true` to enable italic, `false` to disable.
     */
    public fun setItalic(enabled: Boolean)

    /**
     * Sets underline style.
     *
     * @param style The underline style code (0 = none, 1 = single, 2 = double, etc.).
     */
    public fun setUnderlineStyle(style: Int)

    /**
     * Sets blinking style.
     *
     * @param enabled `true` to enable blinking, `false` to disable.
     */
    public fun setBlink(enabled: Boolean)

    /**
     * Sets inverse (reverse-video) style.
     *
     * @param enabled `true` to enable inverse, `false` to disable.
     */
    public fun setInverse(enabled: Boolean)

    /**
     * Sets conceal style.
     *
     * @param enabled `true` to enable conceal, `false` to disable.
     */
    public fun setConceal(enabled: Boolean)

    /**
     * Sets strikethrough decoration.
     *
     * @param enabled `true` to enable strikethrough, `false` to disable.
     */
    public fun setStrikethrough(enabled: Boolean)

    /**
     * Sets overline decoration.
     *
     * @param enabled `true` to enable overline, `false` to disable.
     */
    public fun setOverline(enabled: Boolean)

    /**
     * Sets selective erase protection (DECSCA).
     *
     * @param enabled `true` to protect cells from erasure, `false` to disable protection.
     */
    public fun setSelectiveEraseProtection(enabled: Boolean)

    /**
     * Resets foreground color to the default.
     */
    public fun setForegroundDefault()

    /**
     * Resets background color to the default.
     */
    public fun setBackgroundDefault()

    /**
     * Resets underline color to the default.
     */
    public fun setUnderlineColorDefault()

    /**
     * Sets foreground indexed color.
     *
     * @param index Palette index (0..255).
     */
    public fun setForegroundIndexed(index: Int)

    /**
     * Sets background indexed color.
     *
     * @param index Palette index (0..255).
     */
    public fun setBackgroundIndexed(index: Int)

    /**
     * Sets underline indexed color.
     *
     * @param index Palette index (0..255).
     */
    public fun setUnderlineColorIndexed(index: Int)

    /**
     * Sets foreground RGB color.
     *
     * @param red Red component (0..255).
     * @param green Green component (0..255).
     * @param blue Blue component (0..255).
     */
    public fun setForegroundRgb(
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
    public fun setBackgroundRgb(
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
    public fun setUnderlineColorRgb(
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
    public fun setWindowTitle(title: String)

    /**
     * Sets the icon title.
     *
     * @param title The new icon title.
     */
    public fun setIconTitle(title: String)

    /**
     * Sets both icon and window titles.
     *
     * @param title The new title.
     */
    public fun setIconAndWindowTitle(title: String)

    /**
     * Reports the shell's current working directory as an OSC 7 file URI.
     *
     * The parser only recognizes and decodes the protocol payload. Host layers
     * own URI validation, retention limits, and application-facing policy.
     *
     * @param uri raw current-working-directory URI from terminal output.
     */
    public fun setCurrentWorkingDirectoryUri(uri: String)

    /**
     * Starts an OSC 8 hyperlink context.
     *
     * @param uri Target URI.
     * @param id Optional hyperlink identifier.
     */
    public fun startHyperlink(
        uri: String,
        id: String?,
    )

    /**
     * Ends the active OSC 8 hyperlink context.
     */
    public fun endHyperlink()

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
    public fun requestClipboard(
        selection: String,
        encodedData: String,
    ): Unit = Unit

    /**
     * Sets a specific ANSI indexed color.
     *
     * @param index Color index (0..255).
     * @param color The packed ARGB color value.
     */
    public fun setPaletteColor(
        index: Int,
        color: Int,
    )

    /**
     * Queries an individual color in the active 256-color palette.
     *
     * @param index Color index to query.
     */
    public fun queryPaletteColor(index: Int)

    /**
     * Sets a dynamic color (foreground, background, or cursor color).
     *
     * @param target Target color identifier (10 for foreground, 11 for background, 12 for cursor).
     * @param color The packed ARGB color value.
     */
    public fun setDynamicColor(
        target: Int,
        color: Int,
    )

    /**
     * Queries a dynamic color.
     *
     * @param target Target color identifier to query (10 for foreground, 11 for background, 12 for cursor).
     */
    public fun queryDynamicColor(target: Int)

    /**
     * Queries a status string (DECRQSS).
     *
     * @param query The status parameter query string.
     */
    public fun queryStatusString(query: String)

    /**
     * Queries terminfo capabilities (XTGETTCAP).
     *
     * @param rawPayload Semicolon-separated capability names payload.
     */
    public fun queryTerminfo(rawPayload: String)

    /**
     * Emits a FinalTerm-style OSC 133 shell integration marker.
     *
     * The parser recognizes the marker syntax only. Host/workspace layers own
     * any command metadata storage, navigation, decorations, or UI policy.
     *
     * @param event typed shell integration marker event.
     */
    public fun shellIntegrationMarker(event: ShellIntegrationEvent)

    /**
     * Requests a desktop notification.
     *
     * @param title notification title.
     * @param body notification body.
     * @param level notification severity level.
     */
    public fun showNotification(
        title: String,
        body: String,
        level: NotificationLevel,
    )
}
