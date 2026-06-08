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

/**
 * Immutable snapshot of the terminal's durable mode state.
 *
 * This is the handoff contract for parser, input, and UI layers that need to
 * read mode flags without mutating the core's internal `TerminalModes`
 * instance.
 */
data class TerminalModeSnapshot(
    val isInsertMode: Boolean,
    val isAutoWrap: Boolean,
    val isApplicationCursorKeys: Boolean,
    val isApplicationKeypad: Boolean,
    val isOriginMode: Boolean,
    val isNewLineMode: Boolean,
    val isLeftRightMarginMode: Boolean,
    val isReverseVideo: Boolean,
    val isCursorVisible: Boolean,
    val isCursorBlinking: Boolean,
    val isBracketedPasteEnabled: Boolean,
    val isFocusReportingEnabled: Boolean,
    val treatAmbiguousAsWide: Boolean,
    val mouseTrackingMode: MouseTrackingMode,
    val mouseEncodingMode: MouseEncodingMode,
    val modifyOtherKeysMode: Int,
    val formatOtherKeysMode: Int,
    /** Active Kitty keyboard progressive-enhancement flags stored by core. */
    val kittyKeyboardFlags: Int,
    /** Synchronized output mode (?2026) enabled. */
    val isSynchronizedOutput: Boolean,
)

/**
 * Read-only public access to durable terminal mode state.
 *
 * Intended for parser, input, and UI handoff. The returned snapshot is
 * immutable and detached from internal storage, so callers cannot mutate core
 * state accidentally.
 */
interface TerminalModeReader : TerminalInputState {
    /**
     * Returns one atomic packed snapshot of durable mode state.
     *
     * The current bit layout is owned by core and should be treated as opaque
     * outside optimized input/render handoff code. General callers should
     * prefer [getModeSnapshot].
     */
    fun getModeBitsSnapshot(): Long

    /**
     * Returns one atomic packed snapshot for input encoders.
     *
     * This is the same coherent mode word returned by [getModeBitsSnapshot],
     * exposed through the narrower [TerminalInputState] contract.
     */
    override fun getInputModeBits(): Long = getModeBitsSnapshot()

    /** Returns an immutable snapshot of the current durable mode flags. */
    fun getModeSnapshot(): TerminalModeSnapshot
}
