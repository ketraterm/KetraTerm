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
package io.github.jvterm.host

import io.github.jvterm.core.api.TerminalBuffer
import io.github.jvterm.core.model.CellColor
import io.github.jvterm.core.model.UnderlineStyle
import io.github.jvterm.parser.spi.TerminalCommandSink
import io.github.jvterm.protocol.AnsiMode
import io.github.jvterm.protocol.DecPrivateMode
import io.github.jvterm.protocol.MouseEncodingMode
import io.github.jvterm.protocol.MouseTrackingMode
import io.github.jvterm.protocol.keyboard.*
import io.github.jvterm.render.api.TerminalRenderCursorShape

/**
 * Production bridge from parser semantic commands to the terminal core.
 *
 * The parser owns byte/protocol decoding. The core owns grid mutation, mode state,
 * cursor physics, and width policy. This adapter is the narrow place where ANSI/DEC
 * mode ids become concrete core API calls.
 *
 * @param terminal public core buffer API mutated by parser semantic commands.
 * @param hostEvents optional metadata callback sink for BEL and title changes.
 * @param hostPolicy safety limits for host-owned metadata.
 */
class HostCommandAdapter(
    private val terminal: TerminalBuffer,
    private val hostEvents: HostEventSink = HostEventSink.NONE,
    private val hostPolicy: HostPolicy = HostPolicy(),
) : TerminalCommandSink {
    var windowTitle: String = ""
        private set
    var iconTitle: String = ""
        private set
    var activeHyperlinkUri: String? = null
        private set
    var activeHyperlinkId: String? = null
        private set

    private val windowTitleStack = ArrayDeque<String>()
    private val iconTitleStack = ArrayDeque<String>()

    private var foreground: CellColor = CellColor.DEFAULT
    private var background: CellColor = CellColor.DEFAULT
    private var underlineColor: CellColor = CellColor.DEFAULT
    private var bold: Boolean = false
    private var faint: Boolean = false
    private var italic: Boolean = false
    private var underlineStyle: UnderlineStyle = UnderlineStyle.NONE
    private var strikethrough: Boolean = false
    private var overline: Boolean = false
    private var blink: Boolean = false
    private var inverse: Boolean = false
    private var conceal: Boolean = false
    private var activeHyperlinkNumericId: Int = 0
    private var nextHyperlinkNumericId: Int = 1
    private val hyperlinkIds = LinkedHashMap<HyperlinkKey, Int>(256, 0.75f, true)
    private val hyperlinkKeysByNumericId = HashMap<Int, HyperlinkKey>(256)

    override fun writeCodepoint(codepoint: Int) {
        terminal.writeCodepoint(codepoint)
    }

    override fun writeCluster(
        codepoints: IntArray,
        length: Int,
    ) {
        terminal.writeCluster(codepoints, length)
    }

    override fun appendToPreviousCluster(codepoint: Int) {
        terminal.appendToPreviousCluster(codepoint)
    }

    override fun bell() {
        // TODO(core-gap): Add a core/UI bell hook. Do not fake this by mutating grid state.
        hostEvents.bell()
    }

    override fun backspace() {
        terminal.cursorLeft()
    }

    override fun tab() {
        terminal.horizontalTab()
    }

    override fun lineFeed() {
        terminal.newLine()
        if (terminal.getModeSnapshot().isNewLineMode) {
            terminal.carriageReturn()
        }
    }

    override fun carriageReturn() {
        terminal.carriageReturn()
    }

    override fun reverseIndex() {
        terminal.reverseLineFeed()
    }

    override fun nextLine() {
        terminal.newLine()
        terminal.carriageReturn()
    }

    override fun softReset() {
        terminal.softReset()
        resetPenMirror()
        activeHyperlinkUri = null
        activeHyperlinkId = null
        activeHyperlinkNumericId = 0
    }

    override fun resetTerminal() {
        terminal.reset()
        resetPenMirror()
        activeHyperlinkUri = null
        activeHyperlinkId = null
        activeHyperlinkNumericId = 0
        hyperlinkIds.clear()
        hyperlinkKeysByNumericId.clear()
        nextHyperlinkNumericId = 1
    }

    override fun decaln() {
        terminal.decaln()
    }

    override fun saveCursor() {
        terminal.saveCursor()
    }

    override fun restoreCursor() {
        terminal.restoreCursor()
    }

    override fun setCursorStyle(style: Int) {
        when (style) {
            0, 1 -> {
                terminal.setCursorBlinking(true)
                terminal.setCursorShape(TerminalRenderCursorShape.BLOCK)
            }
            2 -> {
                terminal.setCursorBlinking(false)
                terminal.setCursorShape(TerminalRenderCursorShape.BLOCK)
            }
            3 -> {
                terminal.setCursorBlinking(true)
                terminal.setCursorShape(TerminalRenderCursorShape.UNDERLINE)
            }
            4 -> {
                terminal.setCursorBlinking(false)
                terminal.setCursorShape(TerminalRenderCursorShape.UNDERLINE)
            }
            5 -> {
                terminal.setCursorBlinking(true)
                terminal.setCursorShape(TerminalRenderCursorShape.BAR)
            }
            6 -> {
                terminal.setCursorBlinking(false)
                terminal.setCursorShape(TerminalRenderCursorShape.BAR)
            }
        }
    }

    override fun cursorUp(n: Int) {
        terminal.cursorUp(n)
    }

    override fun cursorDown(n: Int) {
        terminal.cursorDown(n)
    }

    override fun cursorForward(n: Int) {
        terminal.cursorRight(n)
    }

    override fun cursorBackward(n: Int) {
        terminal.cursorLeft(n)
    }

    override fun cursorNextLine(n: Int) {
        terminal.cursorDown(n)
        terminal.carriageReturn()
    }

    override fun cursorPreviousLine(n: Int) {
        terminal.cursorUp(n)
        terminal.carriageReturn()
    }

    override fun cursorForwardTabs(n: Int) {
        terminal.cursorForwardTab(n)
    }

    override fun cursorBackwardTabs(n: Int) {
        terminal.cursorBackwardTab(n)
    }

    override fun setCursorColumn(col: Int) {
        terminal.positionCursor(col = col, row = terminal.cursorRow)
    }

    override fun setCursorRow(row: Int) {
        terminal.positionCursor(col = terminal.cursorCol, row = row)
    }

    override fun setCursorAbsolute(
        row: Int,
        col: Int,
    ) {
        terminal.positionCursor(col = col, row = row)
    }

    override fun setScrollRegion(
        top: Int,
        bottom: Int,
    ) {
        // Parser SPI passes zero-based inclusive margins; core TerminalWriter keeps DECSTBM's
        // one-based inclusive API. This conversion is intentional.
        terminal.setScrollRegion(
            top = top + 1,
            bottom = if (bottom < 0) terminal.height else bottom + 1,
        )
    }

    override fun setLeftRightMargins(
        left: Int,
        right: Int,
    ) {
        // Parser SPI passes zero-based inclusive margins; core TerminalWriter keeps DECSLRM's
        // one-based inclusive API. This conversion is intentional.
        terminal.setLeftRightMargins(
            left = left + 1,
            right = if (right < 0) terminal.width else right + 1,
        )
    }

    override fun eraseInDisplay(
        mode: Int,
        selective: Boolean,
    ) {
        when {
            selective && mode == 0 -> terminal.selectiveEraseScreenToEnd()
            selective && mode == 1 -> terminal.selectiveEraseScreenToCursor()
            selective && mode == 2 -> terminal.selectiveEraseEntireScreen()
            !selective && mode == 0 -> terminal.eraseScreenToEnd()
            !selective && mode == 1 -> terminal.eraseScreenToCursor()
            !selective && mode == 2 -> terminal.eraseEntireScreen()
            !selective && mode == 3 -> terminal.eraseScreenAndHistory()
        }
    }

    override fun eraseInLine(
        mode: Int,
        selective: Boolean,
    ) {
        when {
            selective && mode == 0 -> terminal.selectiveEraseLineToEnd()
            selective && mode == 1 -> terminal.selectiveEraseLineToCursor()
            selective && mode == 2 -> terminal.selectiveEraseCurrentLine()
            !selective && mode == 0 -> terminal.eraseLineToEnd()
            !selective && mode == 1 -> terminal.eraseLineToCursor()
            !selective && mode == 2 -> terminal.eraseCurrentLine()
        }
    }

    override fun insertLines(n: Int) {
        terminal.insertLines(n)
    }

    override fun deleteLines(n: Int) {
        terminal.deleteLines(n)
    }

    override fun insertCharacters(n: Int) {
        terminal.insertBlankCharacters(n)
    }

    override fun deleteCharacters(n: Int) {
        terminal.deleteCharacters(n)
    }

    override fun eraseCharacters(n: Int) {
        terminal.eraseCharacters(n)
    }

    override fun scrollUp(n: Int) {
        repeat(n.coerceAtLeast(0)) {
            terminal.scrollUp()
        }
    }

    override fun scrollDown(n: Int) {
        repeat(n.coerceAtLeast(0)) {
            terminal.scrollDown()
        }
    }

    override fun setTabStop() {
        terminal.setTabStop()
    }

    override fun clearTabStop() {
        terminal.clearTabStop()
    }

    override fun clearAllTabStops() {
        terminal.clearAllTabStops()
    }

    override fun setAnsiMode(
        mode: Int,
        enable: Boolean,
    ) {
        when (mode) {
            AnsiMode.INSERT -> terminal.setInsertMode(enable)
            AnsiMode.NEW_LINE -> terminal.setNewLineMode(enable)
        }
    }

    override fun setDecMode(
        mode: Int,
        enable: Boolean,
    ) {
        when (mode) {
            DecPrivateMode.APPLICATION_CURSOR_KEYS -> terminal.setApplicationCursorKeys(enable)
            DecPrivateMode.DECCOLM -> terminal.executeDeccolm(if (enable) 132 else 80)
            DecPrivateMode.REVERSE_VIDEO -> terminal.setReverseVideo(enable)
            DecPrivateMode.ORIGIN -> terminal.setOriginMode(enable)
            DecPrivateMode.AUTO_WRAP -> terminal.setAutoWrap(enable)
            DecPrivateMode.CURSOR_BLINK -> terminal.setCursorBlinking(enable)
            DecPrivateMode.CURSOR_VISIBLE -> terminal.setCursorVisible(enable)
            DecPrivateMode.APPLICATION_KEYPAD -> terminal.setApplicationKeypad(enable)
            DecPrivateMode.LEFT_RIGHT_MARGIN -> terminal.setLeftRightMarginMode(enable)
            DecPrivateMode.MOUSE_X10 -> setMouseTrackingMode(enable, MouseTrackingMode.X10)
            DecPrivateMode.MOUSE_NORMAL -> setMouseTrackingMode(enable, MouseTrackingMode.NORMAL)
            DecPrivateMode.MOUSE_BUTTON_EVENT -> setMouseTrackingMode(enable, MouseTrackingMode.BUTTON_EVENT)
            DecPrivateMode.MOUSE_ANY_EVENT -> setMouseTrackingMode(enable, MouseTrackingMode.ANY_EVENT)
            DecPrivateMode.FOCUS_REPORTING -> terminal.setFocusReportingEnabled(enable)
            DecPrivateMode.MOUSE_UTF8 ->
                terminal.setMouseEncodingMode(
                    if (enable) MouseEncodingMode.UTF8 else MouseEncodingMode.DEFAULT,
                )
            DecPrivateMode.MOUSE_SGR ->
                terminal.setMouseEncodingMode(
                    if (enable) MouseEncodingMode.SGR else MouseEncodingMode.DEFAULT,
                )
            DecPrivateMode.MOUSE_URXVT ->
                terminal.setMouseEncodingMode(
                    if (enable) MouseEncodingMode.URXVT else MouseEncodingMode.DEFAULT,
                )
            DecPrivateMode.ALT_SCREEN -> {
                if (enable) {
                    terminal.enterAltBufferWithoutCursorSave(clearBeforeEnter = false)
                } else {
                    terminal.exitAltBufferWithoutCursorRestore()
                }
            }
            DecPrivateMode.ALT_SCREEN_BUFFER -> {
                if (enable) {
                    terminal.enterAltBufferWithoutCursorSave(clearBeforeEnter = true)
                } else {
                    terminal.exitAltBufferWithoutCursorRestore()
                }
            }
            DecPrivateMode.SAVE_RESTORE_CURSOR -> {
                if (enable) terminal.saveCursor() else terminal.restoreCursor()
            }
            DecPrivateMode.ALT_SCREEN_SAVE_CURSOR -> {
                if (enable) terminal.enterAltBuffer() else terminal.exitAltBuffer()
            }
            DecPrivateMode.BRACKETED_PASTE -> terminal.setBracketedPasteEnabled(enable)
            DecPrivateMode.SYNCHRONIZED_OUTPUT -> terminal.setSynchronizedOutput(enable)
            DecPrivateMode.BELL_IS_URGENT -> terminal.setBellIsUrgent(enable)
            DecPrivateMode.POP_ON_BELL -> terminal.setPopOnBell(enable)
        }
    }

    override fun setKeyModifierOption(
        resource: Int,
        value: Int,
    ) {
        when (resource) {
            XtermKeyModifierResource.MODIFY_OTHER_KEYS -> {
                if (value in ModifyOtherKeysMode.DISABLED..ModifyOtherKeysMode.MODE_3) {
                    terminal.setModifyOtherKeysMode(value)
                }
            }
            else -> {
                // TODO(input): modifyKeyboard/cursor/function/keypad/special resources need separate mode state.
            }
        }
    }

    override fun resetKeyModifierOption(resource: Int) {
        when (resource) {
            XtermKeyModifierResource.MODIFY_OTHER_KEYS ->
                terminal.setModifyOtherKeysMode(ModifyOtherKeysMode.DISABLED)
            else -> {
                // TODO(input): reset supported xterm key modifier resources when their mode state exists.
            }
        }
    }

    override fun resetKeyModifierOptions() {
        terminal.setModifyOtherKeysMode(ModifyOtherKeysMode.DISABLED)
    }

    override fun setKeyFormatOption(
        resource: Int,
        value: Int,
    ) {
        when (resource) {
            XtermKeyFormatResource.FORMAT_OTHER_KEYS -> {
                if (value == FormatOtherKeysMode.DEFAULT || value == FormatOtherKeysMode.CSI_U) {
                    terminal.setFormatOtherKeysMode(value)
                }
            }
            else -> {
                // TODO(input): formatCursor/function/keypad/special resources need separate mode state.
            }
        }
    }

    override fun resetKeyFormatOption(resource: Int) {
        when (resource) {
            XtermKeyFormatResource.FORMAT_OTHER_KEYS ->
                terminal.setFormatOtherKeysMode(FormatOtherKeysMode.DEFAULT)
            else -> {
                // TODO(input): reset supported xterm key format resources when their mode state exists.
            }
        }
    }

    override fun resetKeyFormatOptions() {
        terminal.setFormatOtherKeysMode(FormatOtherKeysMode.DEFAULT)
    }

    override fun applyKittyKeyboardFlags(
        flags: Int,
        applicationMode: Int,
    ) {
        if (flags < 0) return
        val current = terminal.getModeSnapshot().kittyKeyboardFlags
        val next =
            when (applicationMode) {
                KittyKeyboardFlagApplicationMode.REPLACE -> flags
                KittyKeyboardFlagApplicationMode.SET -> current or flags
                KittyKeyboardFlagApplicationMode.CLEAR -> current and flags.inv()
                else -> return
            }
        terminal.setKittyKeyboardFlags(next)
    }

    override fun pushKittyKeyboardFlags(flags: Int) {
        terminal.pushKittyKeyboardFlags(flags)
    }

    override fun popKittyKeyboardFlags(count: Int) {
        terminal.popKittyKeyboardFlags(count)
    }

    override fun requestDeviceStatusReport(
        mode: Int,
        decPrivate: Boolean,
    ) {
        terminal.requestDeviceStatusReport(mode, decPrivate)
    }

    override fun requestDeviceAttributes(
        kind: Int,
        parameter: Int,
    ) {
        terminal.requestDeviceAttributes(kind, parameter)
    }

    override fun requestWindowReport(mode: Int) {
        terminal.requestWindowReport(mode)
    }

    override fun resizeWindow(
        rows: Int,
        columns: Int,
    ) {
        hostEvents.resizeWindow(rows, columns)
    }

    override fun pushTitleStack(scope: Int) {
        when (scope) {
            0 -> {
                pushTitle(windowTitleStack, windowTitle)
                pushTitle(iconTitleStack, iconTitle)
            }
            1 -> pushTitle(iconTitleStack, iconTitle)
            2 -> pushTitle(windowTitleStack, windowTitle)
        }
    }

    override fun popTitleStack(scope: Int) {
        when (scope) {
            0 -> {
                popTitle(windowTitleStack)?.let { updateWindowTitle(it) }
                popTitle(iconTitleStack)?.let { updateIconTitle(it) }
            }
            1 -> popTitle(iconTitleStack)?.let { updateIconTitle(it) }
            2 -> popTitle(windowTitleStack)?.let { updateWindowTitle(it) }
        }
    }

    override fun resetAttributes() {
        resetPenMirror()
        terminal.resetPen()
    }

    private fun resetPenMirror() {
        foreground = CellColor.DEFAULT
        background = CellColor.DEFAULT
        underlineColor = CellColor.DEFAULT
        bold = false
        faint = false
        italic = false
        underlineStyle = UnderlineStyle.NONE
        strikethrough = false
        overline = false
        blink = false
        inverse = false
        conceal = false
    }

    override fun setBold(enabled: Boolean) {
        bold = enabled
        applyPen()
    }

    override fun setFaint(enabled: Boolean) {
        faint = enabled
        applyPen()
    }

    override fun setItalic(enabled: Boolean) {
        italic = enabled
        applyPen()
    }

    override fun setUnderlineStyle(style: Int) {
        underlineStyle = UnderlineStyle.fromSgrCode(style) ?: return
        applyPen()
    }

    override fun setBlink(enabled: Boolean) {
        blink = enabled
        applyPen()
    }

    override fun setInverse(enabled: Boolean) {
        inverse = enabled
        applyPen()
    }

    override fun setConceal(enabled: Boolean) {
        conceal = enabled
        applyPen()
    }

    override fun setStrikethrough(enabled: Boolean) {
        strikethrough = enabled
        applyPen()
    }

    override fun setOverline(enabled: Boolean) {
        overline = enabled
        applyPen()
    }

    override fun setSelectiveEraseProtection(enabled: Boolean) {
        terminal.setSelectiveEraseProtection(enabled)
    }

    override fun setForegroundDefault() {
        foreground = CellColor.DEFAULT
        applyPen()
    }

    override fun setBackgroundDefault() {
        background = CellColor.DEFAULT
        applyPen()
    }

    override fun setUnderlineColorDefault() {
        underlineColor = CellColor.DEFAULT
        applyPen()
    }

    override fun setForegroundIndexed(index: Int) {
        if (index !in 0..255) return
        foreground = CellColor.indexed(index)
        applyPen()
    }

    override fun setBackgroundIndexed(index: Int) {
        if (index !in 0..255) return
        background = CellColor.indexed(index)
        applyPen()
    }

    override fun setUnderlineColorIndexed(index: Int) {
        if (index !in 0..255) return
        underlineColor = CellColor.indexed(index)
        applyPen()
    }

    override fun setForegroundRgb(
        red: Int,
        green: Int,
        blue: Int,
    ) {
        foreground = CellColor.rgb(red, green, blue)
        applyPen()
    }

    override fun setBackgroundRgb(
        red: Int,
        green: Int,
        blue: Int,
    ) {
        background = CellColor.rgb(red, green, blue)
        applyPen()
    }

    override fun setUnderlineColorRgb(
        red: Int,
        green: Int,
        blue: Int,
    ) {
        underlineColor = CellColor.rgb(red, green, blue)
        applyPen()
    }

    override fun setWindowTitle(title: String) {
        updateWindowTitle(title)
    }

    override fun setIconTitle(title: String) {
        updateIconTitle(title)
    }

    override fun setIconAndWindowTitle(title: String) {
        updateIconTitle(title)
        updateWindowTitle(title)
    }

    override fun startHyperlink(
        uri: String,
        id: String?,
    ) {
        if (!isHyperlinkAllowed(uri, id)) {
            activeHyperlinkUri = null
            activeHyperlinkId = null
            activeHyperlinkNumericId = NO_HYPERLINK_ID
            terminal.setHyperlinkId(NO_HYPERLINK_ID)
            return
        }

        activeHyperlinkUri = uri
        activeHyperlinkId = id
        activeHyperlinkNumericId = hyperlinkIdFor(uri, id)
        terminal.setHyperlinkId(activeHyperlinkNumericId)
    }

    override fun endHyperlink() {
        activeHyperlinkUri = null
        activeHyperlinkId = null
        activeHyperlinkNumericId = 0
        terminal.setHyperlinkId(0)
    }

    override fun setPaletteColor(
        index: Int,
        color: Int,
    ) {
        terminal.setPaletteColor(index, color)
    }

    override fun queryPaletteColor(index: Int) {
        terminal.queryPaletteColor(index)
    }

    override fun setDynamicColor(
        target: Int,
        color: Int,
    ) {
        terminal.setDynamicColor(target, color)
    }

    override fun queryDynamicColor(target: Int) {
        terminal.queryDynamicColor(target)
    }

    override fun queryStatusString(query: String) {
        terminal.queryStatusString(query)
    }

    override fun queryTerminfo(rawPayload: String) {
        terminal.queryTerminfo(rawPayload)
    }

    /**
     * Returns the OSC 8 URI associated with [hyperlinkId], or `null`.
     *
     * The renderer stores only primitive ids in cells. This adapter owns the
     * bounded metadata registry that maps those ids back to validated URIs for
     * explicit host/UI activation. Ids evicted by [hostPolicy] are intentionally
     * unresolved even if older cells still contain their primitive id.
     *
     * @param hyperlinkId render-cell hyperlink id.
     * @return target URI, or `null`.
     */
    fun hyperlinkUri(hyperlinkId: Int): String? = hyperlinkKeysByNumericId[hyperlinkId]?.uri

    private fun setMouseTrackingMode(
        enabled: Boolean,
        mode: MouseTrackingMode,
    ) {
        terminal.setMouseTrackingMode(if (enabled) mode else MouseTrackingMode.OFF)
    }

    private fun pushTitle(
        stack: ArrayDeque<String>,
        title: String,
    ) {
        if (stack.size == MAX_TITLE_STACK_DEPTH) {
            stack.removeFirst()
        }
        stack.addLast(title)
    }

    private fun popTitle(stack: ArrayDeque<String>): String? = if (stack.isEmpty()) null else stack.removeLast()

    private fun updateIconTitle(title: String) {
        iconTitle = title
        terminal.setIconTitle(title)
        hostEvents.iconTitleChanged(title)
    }

    private fun updateWindowTitle(title: String) {
        windowTitle = title
        terminal.setWindowTitle(title)
        hostEvents.windowTitleChanged(title)
    }

    private fun hyperlinkIdFor(
        uri: String,
        id: String?,
    ): Int {
        val key = HyperlinkKey(id.orEmpty(), uri)
        hyperlinkIds[key]?.let { return it }

        if (hyperlinkIds.size >= hostPolicy.maxHyperlinkEntries) {
            val eldest = hyperlinkIds.entries.iterator()
            if (eldest.hasNext()) {
                val entry = eldest.next()
                hyperlinkKeysByNumericId.remove(entry.value)
                eldest.remove()
            }
        }

        val numericId = nextHyperlinkNumericId
        hyperlinkKeysByNumericId[numericId]?.let { staleKey ->
            hyperlinkIds.remove(staleKey)
        }
        hyperlinkIds[key] = numericId
        hyperlinkKeysByNumericId[numericId] = key
        nextHyperlinkNumericId = nextHyperlinkIdAfter(numericId)
        return numericId
    }

    private fun isHyperlinkAllowed(
        uri: String,
        id: String?,
    ): Boolean =
        uri.length <= hostPolicy.maxHyperlinkUriLength &&
            (id?.length ?: 0) <= hostPolicy.maxHyperlinkIdLength

    private fun nextHyperlinkIdAfter(current: Int): Int = if (current == Int.MAX_VALUE) 1 else current + 1

    private fun applyPen() {
        terminal.setPenColors(
            foreground = foreground,
            background = background,
            underlineColor = underlineColor,
            bold = bold,
            faint = faint,
            italic = italic,
            underlineStyle = underlineStyle,
            strikethrough = strikethrough,
            overline = overline,
            blink = blink,
            inverse = inverse,
            conceal = conceal,
        )
    }

    private companion object {
        const val NO_HYPERLINK_ID: Int = 0
        const val MAX_TITLE_STACK_DEPTH: Int = 16
    }

    private data class HyperlinkKey(
        val id: String,
        val uri: String,
    )
}
