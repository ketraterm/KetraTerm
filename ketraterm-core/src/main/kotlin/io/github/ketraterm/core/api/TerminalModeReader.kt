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
 * Immutable snapshot of the terminal's durable mode state.
 *
 * This is the handoff contract for parser, input, and UI layers that need to
 * read mode flags without mutating the core's internal `TerminalModes`
 * instance.
 *
 * @property isInsertMode `true` when insert mode (IRM) is active, `false` for replace mode.
 * @property isAutoWrap `true` when DECAWM auto-wrap mode is active.
 * @property isApplicationCursorKeys `true` when application cursor keys mode (DECCKM) is active.
 * @property isApplicationKeypad `true` when application keypad mode (DECNKM) is active.
 * @property isBackarrowKeyModeExplicit `true` after the host explicitly selected DECBKM.
 * @property isBackarrowKeySendsBackspace the explicit DECBKM byte selection: BS when true, DEL when false.
 * @property isOriginMode `true` when origin mode (DECOM) is active.
 * @property isNewLineMode `true` when new-line mode (LNM) is active.
 * @property isLeftRightMarginMode `true` when left/right margins (DECLRMM) are active.
 * @property isReverseVideo `true` when reverse-video presentation (DECSCNM) is active.
 * @property isCursorVisible `true` when the cursor presentation is visible (DECTCEM).
 * @property isCursorBlinking `true` when the cursor blink presentation is active.
 * @property isBracketedPasteEnabled `true` when bracketed paste mode (?2004) is enabled.
 * @property isFocusReportingEnabled `true` when focus in/out reporting (?1004) is enabled.
 * @property treatAmbiguousAsWide `true` when East Asian Ambiguous characters are measured as wide (2 cells).
 * @property mouseTrackingMode The active mouse tracking mode.
 * @property mouseEncodingMode The active mouse report encoding mode.
 * @property modifyOtherKeysMode The active modify-other-keys reporting level.
 * @property formatOtherKeysMode The active format-other-keys wire format.
 * @property kittyKeyboardFlags Active Kitty keyboard progressive-enhancement flags.
 * @property isSynchronizedOutput `true` when synchronized output mode (?2026) is active.
 * @property isBellIsUrgent `true` when urgent bell mode (?1042) is active.
 * @property isPopOnBell `true` when pop on bell mode (?1043) is active.
 */
data class TerminalModeSnapshot(
    val isInsertMode: Boolean,
    val isAutoWrap: Boolean,
    val isApplicationCursorKeys: Boolean,
    val isApplicationKeypad: Boolean,
    val isBackarrowKeyModeExplicit: Boolean,
    val isBackarrowKeySendsBackspace: Boolean,
    val isOriginMode: Boolean,
    val isNewLineMode: Boolean,
    val isLeftRightMarginMode: Boolean,
    val isReverseVideo: Boolean,
    val isCursorVisible: Boolean,
    val isCursorBlinking: Boolean,
    val isBracketedPasteEnabled: Boolean,
    val isFocusReportingEnabled: Boolean,
    val treatAmbiguousAsWide: Boolean,
    val mouseTrackingMode: io.github.ketraterm.protocol.MouseTrackingMode,
    val mouseEncodingMode: io.github.ketraterm.protocol.MouseEncodingMode,
    val modifyOtherKeysMode: Int,
    val formatOtherKeysMode: Int,
    val kittyKeyboardFlags: Int,
    val isSynchronizedOutput: Boolean,
    val isBellIsUrgent: Boolean,
    val isPopOnBell: Boolean,
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
     *
     * @return A packed 64-bit word containing a snapshot of all active modes.
     */
    fun getModeBitsSnapshot(): Long

    /**
     * Returns one atomic packed snapshot for input encoders.
     *
     * This is the same coherent mode word returned by [getModeBitsSnapshot],
     * exposed through the narrower [TerminalInputState] contract.
     *
     * @return A packed 64-bit word containing a snapshot of input modes.
     */
    override fun getInputModeBits(): Long = getModeBitsSnapshot()

    /**
     * Returns an immutable snapshot of the current durable mode flags.
     *
     * @return A detached [TerminalModeSnapshot] instance containing all active mode state.
     */
    fun getModeSnapshot(): TerminalModeSnapshot
}
