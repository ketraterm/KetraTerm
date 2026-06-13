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
package io.github.jvterm.parser.ansi

import io.github.jvterm.parser.ansi.sgr.SgrDispatcher
import io.github.jvterm.parser.charset.CharsetMapper
import io.github.jvterm.parser.runtime.ParserState
import io.github.jvterm.parser.spi.TerminalCommandSink
import io.github.jvterm.protocol.ControlCode
import io.github.jvterm.protocol.keyboard.KittyKeyboardFlagApplicationMode

/**
 * Semantic dispatcher boundary used by ActionEngine.
 *
 * ESC/CSI/control meaning lives here, not in the matrix and not in ActionEngine.
 */
internal interface CommandDispatcher {
    fun executeControl(
        sink: TerminalCommandSink,
        state: ParserState,
        controlByte: Int,
    )

    fun dispatchEsc(
        sink: TerminalCommandSink,
        state: ParserState,
        finalByte: Int,
    )

    fun dispatchCsi(
        sink: TerminalCommandSink,
        state: ParserState,
        finalByte: Int,
    )
}

internal object AnsiCommandDispatcher : CommandDispatcher {
    override fun executeControl(
        sink: TerminalCommandSink,
        state: ParserState,
        controlByte: Int,
    ) {
        when (controlByte) {
            ControlCode.BEL -> sink.bell()
            ControlCode.BS -> sink.backspace()
            ControlCode.HT -> sink.tab()
            ControlCode.LF, ControlCode.VT, ControlCode.FF -> sink.lineFeed()
            ControlCode.CR -> sink.carriageReturn()
            ControlCode.SO -> CharsetMapper.lockingShiftG1(state)
            ControlCode.SI -> CharsetMapper.lockingShiftG0(state)
        }
    }

    override fun dispatchEsc(
        sink: TerminalCommandSink,
        state: ParserState,
        finalByte: Int,
    ) {
        if (dispatchCharsetDesignation(state, finalByte)) {
            return
        }

        if (state.intermediateCount == 1 && (state.intermediates and 0xff) == '#'.code) {
            if (finalByte == '8'.code) {
                sink.decaln()
            }
            return
        }

        if (state.intermediateCount != 0) {
            return
        }

        when (finalByte) {
            '7'.code -> {
                state.saveCursor()
                sink.saveCursor()
            }
            '8'.code -> {
                state.restoreCursor()
                sink.restoreCursor()
            }
            'c'.code -> sink.resetTerminal()
            'D'.code -> sink.lineFeed()
            'E'.code -> sink.nextLine()
            'H'.code -> sink.setTabStop()
            'M'.code -> sink.reverseIndex()
            'N'.code -> CharsetMapper.singleShiftG2(state)
            'O'.code -> CharsetMapper.singleShiftG3(state)
        }
    }

    override fun dispatchCsi(
        sink: TerminalCommandSink,
        state: ParserState,
        finalByte: Int,
    ) {
        val signature =
            CsiSignature.encode(
                finalByte = finalByte,
                privateMarker = state.privateMarker,
                intermediates = state.intermediates,
                intermediateCount = state.intermediateCount,
            )

        when (GeneratedCsiDispatchTable.lookup(signature)) {
            CsiCommand.UNKNOWN -> Unit

            CsiCommand.CUU -> sink.cursorUp(countParam(state, 0))
            CsiCommand.CUD -> sink.cursorDown(countParam(state, 0))
            CsiCommand.CUF -> sink.cursorForward(countParam(state, 0))
            CsiCommand.CUB -> sink.cursorBackward(countParam(state, 0))
            CsiCommand.CNL -> sink.cursorNextLine(countParam(state, 0))
            CsiCommand.CPL -> sink.cursorPreviousLine(countParam(state, 0))
            CsiCommand.CHT -> sink.cursorForwardTabs(countParam(state, 0))
            CsiCommand.CBT -> sink.cursorBackwardTabs(countParam(state, 0))
            CsiCommand.DA_PRIMARY -> sink.requestDeviceAttributes(kind = 0, parameter = modeParam(state, 0))
            CsiCommand.DA_SECONDARY -> sink.requestDeviceAttributes(kind = 1, parameter = modeParam(state, 0))
            CsiCommand.DA_TERTIARY -> sink.requestDeviceAttributes(kind = 2, parameter = modeParam(state, 0))
            CsiCommand.CHA -> sink.setCursorColumn(oneBasedPositionParam(state, 0))
            CsiCommand.CUP ->
                sink.setCursorAbsolute(
                    row = oneBasedPositionParam(state, 0),
                    col = oneBasedPositionParam(state, 1),
                )
            CsiCommand.VPA -> sink.setCursorRow(oneBasedPositionParam(state, 0))

            CsiCommand.ED -> sink.eraseInDisplay(modeParam(state, 0), selective = false)
            CsiCommand.EL -> sink.eraseInLine(modeParam(state, 0), selective = false)
            CsiCommand.DECSED -> sink.eraseInDisplay(modeParam(state, 0), selective = true)
            CsiCommand.DECSEL -> sink.eraseInLine(modeParam(state, 0), selective = true)
            CsiCommand.IL -> sink.insertLines(countParam(state, 0))
            CsiCommand.DL -> sink.deleteLines(countParam(state, 0))
            CsiCommand.ICH -> sink.insertCharacters(countParam(state, 0))
            CsiCommand.DCH -> sink.deleteCharacters(countParam(state, 0))
            CsiCommand.ECH -> sink.eraseCharacters(countParam(state, 0))
            CsiCommand.SU -> sink.scrollUp(countParam(state, 0))
            CsiCommand.SD -> sink.scrollDown(countParam(state, 0))
            CsiCommand.DSR -> sink.requestDeviceStatusReport(modeParam(state, 0), decPrivate = false)
            CsiCommand.DSR_DEC -> sink.requestDeviceStatusReport(modeParam(state, 0), decPrivate = true)
            CsiCommand.TBC -> dispatchTabClear(sink, state)
            CsiCommand.WINDOW_OP -> dispatchWindowOperation(sink, state)
            CsiCommand.XTFMTKEYS -> dispatchKeyFormatOption(sink, state)
            CsiCommand.XTMODKEYS -> dispatchKeyModifierOption(sink, state)
            CsiCommand.KITTY_KEYBOARD_FLAGS -> dispatchKittyKeyboardFlags(sink, state)
            CsiCommand.KITTY_KEYBOARD_PUSH -> dispatchKittyKeyboardPush(sink, state)
            CsiCommand.KITTY_KEYBOARD_POP -> dispatchKittyKeyboardPop(sink, state)
            CsiCommand.DECSTBM ->
                sink.setScrollRegion(
                    top = scrollRegionTopParam(state, 0),
                    bottom = scrollRegionBottomParam(state, 1),
                )
            CsiCommand.DECSLRM ->
                sink.setLeftRightMargins(
                    left = leftRightMarginLeftParam(state, 0),
                    right = leftRightMarginRightParam(state, 1),
                )
            CsiCommand.DECSCA -> dispatchSelectiveEraseProtection(sink, state)

            CsiCommand.SM_ANSI -> dispatchAnsiMode(sink, state, enable = true)
            CsiCommand.RM_ANSI -> dispatchAnsiMode(sink, state, enable = false)
            CsiCommand.SM_DEC -> dispatchDecMode(sink, state, enable = true)
            CsiCommand.RM_DEC -> dispatchDecMode(sink, state, enable = false)

            CsiCommand.DECSTR -> sink.softReset()
            CsiCommand.DECSCUSR -> sink.setCursorStyle(modeParam(state, 0))
            CsiCommand.SGR -> SgrDispatcher.dispatch(sink, state)
        }
    }

    private fun countParam(
        state: ParserState,
        index: Int,
    ): Int {
        val value = paramOrMissing(state, index)
        return if (value <= 0) 1 else value
    }

    private fun modeParam(
        state: ParserState,
        index: Int,
    ): Int {
        val value = paramOrMissing(state, index)
        return if (value < 0) 0 else value
    }

    private fun oneBasedPositionParam(
        state: ParserState,
        index: Int,
    ): Int {
        val value = paramOrMissing(state, index)
        return if (value <= 0) 0 else value - 1
    }

    private fun scrollRegionTopParam(
        state: ParserState,
        index: Int,
    ): Int {
        val value = paramOrMissing(state, index)
        return if (value <= 0) 0 else value - 1
    }

    private fun scrollRegionBottomParam(
        state: ParserState,
        index: Int,
    ): Int {
        val value = paramOrMissing(state, index)
        return if (value <= 0) -1 else value - 1
    }

    private fun leftRightMarginLeftParam(
        state: ParserState,
        index: Int,
    ): Int {
        val value = paramOrMissing(state, index)
        return if (value <= 0) 0 else value - 1
    }

    private fun leftRightMarginRightParam(
        state: ParserState,
        index: Int,
    ): Int {
        val value = paramOrMissing(state, index)
        return if (value <= 0) -1 else value - 1
    }

    private fun paramOrMissing(
        state: ParserState,
        index: Int,
    ): Int = if (index < state.paramCount) state.params[index] else -1

    private fun dispatchAnsiMode(
        sink: TerminalCommandSink,
        state: ParserState,
        enable: Boolean,
    ) {
        forEachMaterializedMode(state) { mode ->
            sink.setAnsiMode(mode, enable)
        }
    }

    private fun dispatchDecMode(
        sink: TerminalCommandSink,
        state: ParserState,
        enable: Boolean,
    ) {
        forEachMaterializedMode(state) { mode ->
            when (mode) {
                1048, 1049 -> {
                    if (enable) state.saveCursor() else state.restoreCursor()
                }
            }
            sink.setDecMode(mode, enable)
        }
    }

    private fun dispatchTabClear(
        sink: TerminalCommandSink,
        state: ParserState,
    ) {
        when (modeParam(state, 0)) {
            0 -> sink.clearTabStop()
            3 -> sink.clearAllTabStops()
        }
    }

    private fun dispatchSelectiveEraseProtection(
        sink: TerminalCommandSink,
        state: ParserState,
    ) {
        when (modeParam(state, 0)) {
            0, 2 -> sink.setSelectiveEraseProtection(false)
            1 -> sink.setSelectiveEraseProtection(true)
        }
    }

    private fun dispatchWindowOperation(
        sink: TerminalCommandSink,
        state: ParserState,
    ) {
        when (modeParam(state, 0)) {
            8 -> {
                val rows = modeParam(state, 1)
                val cols = modeParam(state, 2)
                sink.resizeWindow(rows = rows, columns = cols)
            }
            14, 18 -> sink.requestWindowReport(modeParam(state, 0))
            22 -> {
                val scope = titleStackScopeParam(state)
                if (scope in 0..2) sink.pushTitleStack(scope)
            }
            23 -> {
                val scope = titleStackScopeParam(state)
                if (scope in 0..2) sink.popTitleStack(scope)
            }
        }
    }

    private fun dispatchKeyModifierOption(
        sink: TerminalCommandSink,
        state: ParserState,
    ) {
        dispatchKeyOption(
            state = state,
            resetAll = { sink.resetKeyModifierOptions() },
            resetOne = { resource -> sink.resetKeyModifierOption(resource) },
            set = { resource, value -> sink.setKeyModifierOption(resource, value) },
        )
    }

    private fun dispatchKeyFormatOption(
        sink: TerminalCommandSink,
        state: ParserState,
    ) {
        dispatchKeyOption(
            state = state,
            resetAll = { sink.resetKeyFormatOptions() },
            resetOne = { resource -> sink.resetKeyFormatOption(resource) },
            set = { resource, value -> sink.setKeyFormatOption(resource, value) },
        )
    }

    private fun dispatchKittyKeyboardFlags(
        sink: TerminalCommandSink,
        state: ParserState,
    ) {
        if (state.paramCount > 2) return
        val flags = paramOrMissing(state, 0)
        if (flags < 0 || isSubParameter(state, 0) || isSubParameter(state, 1)) return

        val applicationMode =
            when (val mode = paramOrMissing(state, 1)) {
                -1 -> KittyKeyboardFlagApplicationMode.REPLACE
                KittyKeyboardFlagApplicationMode.REPLACE,
                KittyKeyboardFlagApplicationMode.SET,
                KittyKeyboardFlagApplicationMode.CLEAR,
                -> mode
                else -> return
            }

        sink.applyKittyKeyboardFlags(flags, applicationMode)
    }

    private fun dispatchKittyKeyboardPush(
        sink: TerminalCommandSink,
        state: ParserState,
    ) {
        if (state.paramCount > 1) return
        if (isSubParameter(state, 0)) return
        val flags = paramOrMissing(state, 0)
        sink.pushKittyKeyboardFlags(if (flags < 0) 0 else flags)
    }

    private fun dispatchKittyKeyboardPop(
        sink: TerminalCommandSink,
        state: ParserState,
    ) {
        if (state.paramCount > 1) return
        if (isSubParameter(state, 0)) return
        sink.popKittyKeyboardFlags(countParam(state, 0))
    }

    private inline fun dispatchKeyOption(
        state: ParserState,
        resetAll: () -> Unit,
        resetOne: (Int) -> Unit,
        set: (Int, Int) -> Unit,
    ) {
        when (state.paramCount) {
            0 -> resetAll()
            1 -> {
                val resource = paramOrMissing(state, 0)
                if (resource >= 0) resetOne(resource)
            }
            else -> {
                val resource = paramOrMissing(state, 0)
                val value = paramOrMissing(state, 1)
                if (resource >= 0 && value >= 0 && !isSubParameter(state, 1)) {
                    set(resource, value)
                }
            }
        }
    }

    private fun titleStackScopeParam(state: ParserState): Int = modeParam(state, 1)

    private fun isSubParameter(
        state: ParserState,
        index: Int,
    ): Boolean = index in 0..31 && ((state.subParameterMask ushr index) and 1) != 0

    private inline fun forEachMaterializedMode(
        state: ParserState,
        block: (Int) -> Unit,
    ) {
        var i = 0
        while (i < state.paramCount) {
            val mode = state.params[i]
            if (mode >= 0) {
                block(mode)
            }
            i++
        }
    }

    private fun dispatchCharsetDesignation(
        state: ParserState,
        finalByte: Int,
    ): Boolean {
        if (state.intermediateCount != 1) {
            return false
        }

        val slot =
            when (state.intermediates and 0xff) {
                '('.code -> 0
                ')'.code -> 1
                '*'.code -> 2
                '+'.code -> 3
                else -> return false
            }

        when (finalByte) {
            'B'.code -> CharsetMapper.designateAscii(state, slot)
            '0'.code -> CharsetMapper.designateDecSpecialGraphics(state, slot)
            else -> return true // Recognized designation shape, unsupported charset final ignored.
        }

        return true
    }
}
