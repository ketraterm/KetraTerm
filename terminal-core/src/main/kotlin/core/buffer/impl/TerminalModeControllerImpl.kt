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
package com.gagik.core.buffer.impl

import com.gagik.core.api.TerminalModeController
import com.gagik.core.engine.CursorEngine
import com.gagik.core.state.TerminalState
import com.gagik.terminal.protocol.MouseEncodingMode
import com.gagik.terminal.protocol.MouseTrackingMode
import com.gagik.terminal.render.api.TerminalRenderCursorShape

internal class TerminalModeControllerImpl(
    private val state: TerminalState,
    private val cursorEngine: CursorEngine,
) : TerminalModeController {
    private inline fun mutateMode(block: () -> Unit) {
        state.cancelPendingWrap()
        block()
    }

    override fun setInsertMode(enabled: Boolean) {
        mutateMode { state.modes.isInsertMode = enabled }
    }

    override fun setAutoWrap(enabled: Boolean) {
        mutateMode { state.modes.isAutoWrap = enabled }
    }

    override fun setOriginMode(enabled: Boolean) {
        mutateMode {
            state.modes.isOriginMode = enabled
            cursorEngine.homeCursor()
        }
    }

    override fun setApplicationCursorKeys(enabled: Boolean) {
        mutateMode { state.modes.isApplicationCursorKeys = enabled }
    }

    override fun setApplicationKeypad(enabled: Boolean) {
        mutateMode { state.modes.isApplicationKeypad = enabled }
    }

    override fun setLeftRightMarginMode(enabled: Boolean) {
        state.cancelPendingWrap()
        if (state.modes.isLeftRightMarginMode == enabled) return
        state.modes.isLeftRightMarginMode = enabled
        state.activeBuffer.resetLeftRightMargins(state.dimensions.width)
        cursorEngine.homeCursor()
    }

    override fun setNewLineMode(enabled: Boolean) {
        mutateMode { state.modes.isNewLineMode = enabled }
    }

    override fun setMouseTrackingMode(mode: MouseTrackingMode) {
        mutateMode { state.modes.mouseTrackingMode = mode }
    }

    override fun setMouseEncodingMode(mode: MouseEncodingMode) {
        mutateMode { state.modes.mouseEncodingMode = mode }
    }

    override fun setBracketedPasteEnabled(enabled: Boolean) {
        mutateMode { state.modes.isBracketedPasteEnabled = enabled }
    }

    override fun setFocusReportingEnabled(enabled: Boolean) {
        mutateMode { state.modes.isFocusReportingEnabled = enabled }
    }

    override fun setModifyOtherKeysMode(mode: Int) {
        mutateMode { state.modes.modifyOtherKeysMode = mode }
    }

    override fun setFormatOtherKeysMode(mode: Int) {
        mutateMode { state.modes.formatOtherKeysMode = mode }
    }

    override fun setReverseVideo(enabled: Boolean) {
        mutateMode {
            if (state.modes.isReverseVideo == enabled) return@mutateMode
            state.modes.isReverseVideo = enabled
            state.markVisibleLinesChanged()
        }
    }

    override fun setCursorVisible(enabled: Boolean) {
        mutateMode {
            if (state.modes.isCursorVisible == enabled) return@mutateMode
            state.modes.isCursorVisible = enabled
            state.markCursorChanged()
        }
    }

    override fun setCursorBlinking(enabled: Boolean) {
        mutateMode {
            if (state.modes.isCursorBlinking == enabled) return@mutateMode
            state.modes.isCursorBlinking = enabled
            state.markCursorChanged()
        }
    }

    override fun setCursorShape(shape: TerminalRenderCursorShape) {
        mutateMode {
            state.cursorShape = shape
        }
    }

    override fun setTreatAmbiguousAsWide(enabled: Boolean) {
        mutateMode { state.modes.treatAmbiguousAsWide = enabled }
    }

    override fun enterAltBufferWithoutCursorSave(clearBeforeEnter: Boolean) {
        if (state.isAltScreenActive) return

        state.enterAltScreen(clearBeforeEnter)
        state.markStructureChanged()
        state.markCursorChanged()
    }

    override fun exitAltBufferWithoutCursorRestore() {
        if (!state.isAltScreenActive) return

        state.exitAltScreen()
        state.markStructureChanged()
        state.markCursorChanged()
    }

    override fun enterAltBuffer() {
        if (state.isAltScreenActive) return

        cursorEngine.saveCursor()
        state.enterAltScreen(clearBeforeEnter = true)
        state.markStructureChanged()
        state.markCursorChanged()
    }

    override fun exitAltBuffer() {
        if (!state.isAltScreenActive) return

        state.exitAltScreen()
        cursorEngine.restoreCursor()
        state.markStructureChanged()
    }
}
