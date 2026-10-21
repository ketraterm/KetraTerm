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
package io.github.ketraterm.core.buffer.impl

import io.github.ketraterm.core.api.TerminalModeController
import io.github.ketraterm.core.api.XtermKeyResourceBits
import io.github.ketraterm.core.engine.CursorEngine
import io.github.ketraterm.core.state.TerminalState
import io.github.ketraterm.protocol.TerminalColorRequest
import io.github.ketraterm.protocol.keyboard.XtermKeyResource
import io.github.ketraterm.render.api.TerminalColorBehavior
import io.github.ketraterm.render.api.TerminalColorPalette
import io.github.ketraterm.render.api.TerminalRenderCursorShape

internal class TerminalModeControllerImpl(
    private val state: TerminalState,
    private val cursorEngine: CursorEngine,
    private val responseChannel: BufferResponseChannel,
) : TerminalModeController {
    private inline fun mutateMode(block: () -> Unit) {
        block()
    }

    override fun setInsertMode(enabled: Boolean) {
        mutateMode { state.modes.isInsertMode = enabled }
    }

    override fun setAutoWrap(enabled: Boolean) {
        state.cancelPendingWrap()
        state.modes.isAutoWrap = enabled
    }

    override fun setReverseWrap(enabled: Boolean) {
        state.modes.isReverseWrap = enabled
    }

    override fun setReverseWrapExtended(enabled: Boolean) {
        state.modes.isReverseWrapExtended = enabled
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

    override fun setBackarrowKeyMode(sendsBackspace: Boolean) {
        mutateMode { state.modes.setBackarrowKeyMode(sendsBackspace) }
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

    override fun setMouseTrackingMode(mode: io.github.ketraterm.protocol.MouseTrackingMode) {
        mutateMode { state.modes.mouseTrackingMode = mode }
    }

    override fun setMouseEncodingMode(mode: io.github.ketraterm.protocol.MouseEncodingMode) {
        mutateMode { state.modes.mouseEncodingMode = mode }
    }

    override fun setBracketedPasteEnabled(enabled: Boolean) {
        mutateMode { state.modes.isBracketedPasteEnabled = enabled }
    }

    override fun setFocusReportingEnabled(enabled: Boolean) {
        mutateMode { state.modes.isFocusReportingEnabled = enabled }
    }

    override fun setAlternateScrollEnabled(enabled: Boolean) {
        state.modes.isAlternateScrollEnabled = enabled
    }

    override fun setShiftMouseCaptureEnabled(enabled: Boolean) {
        state.modes.isShiftMouseCaptureEnabled = enabled
    }

    override fun setModifyOtherKeysMode(mode: Int) {
        mutateMode { state.modes.modifyOtherKeysMode = mode }
    }

    override fun setFormatOtherKeysMode(mode: Int) {
        mutateMode { state.modes.formatOtherKeysMode = mode }
    }

    override fun setKeyModifierOption(
        resource: Int,
        value: Int,
    ) = state.modes.setKeyModifierOption(resource, value)

    override fun resetKeyModifierOption(resource: Int) {
        if (XtermKeyResource.isSupported(resource)) {
            state.modes.setKeyModifierOption(resource, XtermKeyResourceBits.defaultModifier(resource))
        }
    }

    override fun resetKeyModifierOptions() = state.modes.resetKeyModifierOptions()

    override fun setKeyFormatOption(
        resource: Int,
        value: Int,
    ) = state.modes.setKeyFormatOption(resource, value)

    override fun resetKeyFormatOption(resource: Int) {
        if (XtermKeyResource.isSupported(resource)) state.modes.setKeyFormatOption(resource, 0)
    }

    override fun resetKeyFormatOptions() = state.modes.resetKeyFormatOptions()

    override fun setKittyKeyboardFlags(flags: Int) {
        mutateMode {
            state.modes.kittyKeyboardFlags = flags
            state.activeBuffer.kittyKeyboardFlags = flags
        }
    }

    override fun pushKittyKeyboardFlags(flags: Int) {
        mutateMode {
            state.modes.kittyKeyboardFlags = state.activeBuffer.pushKittyKeyboardFlags(flags, state.modes.kittyKeyboardFlags)
        }
    }

    override fun popKittyKeyboardFlags(count: Int) {
        mutateMode {
            state.modes.kittyKeyboardFlags = state.activeBuffer.popKittyKeyboardFlags(count, state.modes.kittyKeyboardFlags)
        }
    }

    override fun setReverseVideo(enabled: Boolean) {
        mutateMode {
            if (state.modes.isReverseVideo == enabled) return@mutateMode
            state.modes.isReverseVideo = enabled
            state.markGlobalAttributesChanged()
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

    override fun setDefaultCursorShape(shape: TerminalRenderCursorShape) {
        mutateMode {
            state.defaultCursorShape = shape
        }
    }

    override fun resetCursorStyle() {
        setCursorShape(state.defaultCursorShape)
        setCursorBlinking(true)
    }

    override fun setTreatAmbiguousAsWide(enabled: Boolean) {
        mutateMode { state.modes.treatAmbiguousAsWide = enabled }
    }

    override fun setSynchronizedOutput(enabled: Boolean) {
        mutateMode { state.modes.isSynchronizedOutput = enabled }
    }

    override fun setColorSchemeReportingEnabled(enabled: Boolean) {
        state.modes.isColorSchemeReportingEnabled = enabled
    }

    override fun setInBandResizeReportingEnabled(enabled: Boolean) {
        state.modes.isInBandResizeReportingEnabled = enabled
        if (enabled) responseChannel.notifySizeChanged()
    }

    override fun setBellIsUrgent(enabled: Boolean) {
        mutateMode { state.modes.isBellIsUrgent = enabled }
    }

    override fun setPopOnBell(enabled: Boolean) {
        mutateMode { state.modes.isPopOnBell = enabled }
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

    override fun setThemePalette(palette: TerminalColorPalette) {
        mutateMode {
            val themeChanged = state.themePalette != palette
            state.themePalette = palette
            if (state.palette != palette) {
                state.palette = palette
                state.markVisibleLinesChanged()
            }
            if (themeChanged) responseChannel.notifyColorSchemeChanged()
        }
    }

    override fun setPaletteColor(
        index: Int,
        color: Int,
    ) {
        if (index !in 0..255 || state.palette.indexedColor(index) == color) return
        mutateMode {
            val newIndexed = state.palette.toIndexedColorsArray()
            newIndexed[index] = color
            state.palette = state.palette.copy(indexedColors = newIndexed)
            state.markVisibleLinesChanged()
        }
    }

    override fun setDynamicColor(
        target: Int,
        color: Int,
    ) {
        val role =
            when (target) {
                10 -> TerminalColorRequest.FOREGROUND
                11 -> TerminalColorRequest.BACKGROUND
                12 -> TerminalColorRequest.CURSOR
                else -> return
            }
        setRoleColor(role, color, state.palette.colorBehavior and roleBehaviorMask(role).inv())
    }

    override fun resetPaletteColors(
        indices: IntArray,
        count: Int,
    ) {
        require(count in 0..indices.size) { "count must be in 0..indices.size" }
        val current = state.palette
        val theme = state.themePalette
        var indexed: IntArray? = null
        val limit = if (count == 0) 256 else count
        for (i in 0 until limit) {
            val index = if (count == 0) i else indices[i]
            if (index !in 0..255) continue
            val color = theme.indexedColor(index)
            if (current.indexedColor(index) == color) continue
            val colors = indexed ?: current.toIndexedColorsArray().also { indexed = it }
            colors[index] = color
        }
        val colors = indexed ?: return
        state.palette = current.copy(indexedColors = colors)
        state.markVisibleLinesChanged()
    }

    override fun resetDynamicColor(target: Int) {
        val role =
            when (target) {
                10 -> TerminalColorRequest.FOREGROUND
                11 -> TerminalColorRequest.BACKGROUND
                12 -> TerminalColorRequest.CURSOR
                else -> return
            }
        resetRoleColor(role)
    }

    override fun applyColorRequest(
        request: TerminalColorRequest,
        allowResponses: Boolean,
    ) {
        var responseStarted = false
        for (index in 0 until request.count) {
            val target = request.target(index)
            val operation = request.operation(index)
            if (target == TerminalColorRequest.UNKNOWN || operation == TerminalColorRequest.QUERY) {
                if (allowResponses) {
                    if (!responseStarted) {
                        responseChannel.beginColorReply()
                        responseStarted = true
                    }
                    responseChannel.appendColorReply(request, index)
                }
                continue
            }
            if (target in 0..255) {
                when (operation) {
                    TerminalColorRequest.SET -> setPaletteColor(target, request.color(index) or OPAQUE_ALPHA)
                    TerminalColorRequest.RESET -> setPaletteColor(target, state.themePalette.indexedColor(target))
                }
                continue
            }
            when (operation) {
                TerminalColorRequest.SET -> {
                    val behavior =
                        (state.palette.colorBehavior and roleBehaviorMask(target).inv()) or selectionOverrideFlag(target)
                    setRoleColor(target, request.color(index) or OPAQUE_ALPHA, behavior)
                }
                TerminalColorRequest.RESET -> resetRoleColor(target)
                TerminalColorRequest.DYNAMIC -> {
                    val dynamicFlag = roleDynamicFlag(target)
                    if (dynamicFlag != 0) {
                        val behavior = state.palette.colorBehavior or dynamicFlag or selectionOverrideFlag(target)
                        setRoleColor(target, roleColor(state.palette, target), behavior)
                    }
                }
            }
        }
        if (responseStarted) responseChannel.endColorReply()
    }

    private fun resetRoleColor(target: Int) {
        val mask = roleBehaviorMask(target)
        val behavior = (state.palette.colorBehavior and mask.inv()) or (state.themePalette.colorBehavior and mask)
        setRoleColor(target, roleColor(state.themePalette, target), behavior)
    }

    private fun setRoleColor(
        target: Int,
        color: Int,
        behavior: Int,
    ) {
        val palette = state.palette
        if (roleColor(palette, target) == color && palette.colorBehavior == behavior) return
        state.palette =
            when (target) {
                TerminalColorRequest.FOREGROUND -> palette.copyWithColorBehavior(colorBehavior = behavior, defaultForeground = color)
                TerminalColorRequest.BACKGROUND -> palette.copyWithColorBehavior(colorBehavior = behavior, defaultBackground = color)
                TerminalColorRequest.SELECTION_FOREGROUND ->
                    palette.copyWithColorBehavior(
                        colorBehavior = behavior,
                        selectionForeground = color,
                    )
                TerminalColorRequest.SELECTION_BACKGROUND ->
                    palette.copyWithColorBehavior(
                        colorBehavior = behavior,
                        selectionBackground = color,
                    )
                TerminalColorRequest.CURSOR -> palette.copyWithColorBehavior(colorBehavior = behavior, cursorBackground = color)
                TerminalColorRequest.CURSOR_TEXT -> palette.copyWithColorBehavior(colorBehavior = behavior, cursorForeground = color)
                else -> return
            }
        state.markVisibleLinesChanged()
    }

    private fun roleColor(
        palette: TerminalColorPalette,
        target: Int,
    ): Int =
        when (target) {
            TerminalColorRequest.FOREGROUND -> palette.defaultForeground
            TerminalColorRequest.BACKGROUND -> palette.defaultBackground
            TerminalColorRequest.SELECTION_FOREGROUND -> palette.selectionForeground
            TerminalColorRequest.SELECTION_BACKGROUND -> palette.selectionBackground
            TerminalColorRequest.CURSOR -> palette.cursorBackground
            TerminalColorRequest.CURSOR_TEXT -> palette.cursorForeground
            else -> 0
        }

    private fun roleBehaviorMask(target: Int): Int = roleDynamicFlag(target) or selectionOverrideFlag(target)

    private fun roleDynamicFlag(target: Int): Int =
        when (target) {
            TerminalColorRequest.SELECTION_FOREGROUND -> TerminalColorBehavior.DYNAMIC_SELECTION_FOREGROUND
            TerminalColorRequest.SELECTION_BACKGROUND -> TerminalColorBehavior.DYNAMIC_SELECTION_BACKGROUND
            TerminalColorRequest.CURSOR -> TerminalColorBehavior.DYNAMIC_CURSOR_BACKGROUND
            TerminalColorRequest.CURSOR_TEXT -> TerminalColorBehavior.DYNAMIC_CURSOR_FOREGROUND
            else -> 0
        }

    private fun selectionOverrideFlag(target: Int): Int =
        when (target) {
            TerminalColorRequest.SELECTION_FOREGROUND -> TerminalColorBehavior.OVERRIDE_SELECTION_FOREGROUND
            TerminalColorRequest.SELECTION_BACKGROUND -> TerminalColorBehavior.OVERRIDE_SELECTION_BACKGROUND
            else -> 0
        }

    private companion object {
        const val OPAQUE_ALPHA = -0x1000000
    }
}
