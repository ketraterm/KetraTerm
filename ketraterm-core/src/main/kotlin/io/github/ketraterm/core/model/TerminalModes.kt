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
package io.github.ketraterm.core.model

import io.github.ketraterm.core.api.TerminalInputState
import io.github.ketraterm.core.api.TerminalModeBits
import io.github.ketraterm.core.api.TerminalModeSnapshot
import java.util.concurrent.atomic.AtomicLong

/**
 * Atomic storage for durable terminal behavioral modes.
 *
 * The grid, cursor, and history remain single-writer core state. This packed
 * word exists so input/render decisions can take a coherent mode snapshot
 * without reading unrelated mutable core structures.
 */
internal class TerminalModes : TerminalInputState {
    private val modeBits = AtomicLong(DEFAULT_MODE_BITS)

    /** Mode 4: Insert/Replace Mode (IRM). False = replace (default), true = insert. */
    var isInsertMode: Boolean
        get() = TerminalModeBits.hasFlag(currentBits, TerminalModeBits.INSERT_MODE)
        set(value) = setFlag(TerminalModeBits.INSERT_MODE, value)

    /** Mode 7: Auto-Wrap Mode (DECAWM). True = wrap at right margin (default). */
    var isAutoWrap: Boolean
        get() = TerminalModeBits.hasFlag(currentBits, TerminalModeBits.AUTO_WRAP)
        set(value) = setFlag(TerminalModeBits.AUTO_WRAP, value)

    /** Mode 1: Application Cursor Keys (DECCKM). False = normal cursor keys (default). */
    var isApplicationCursorKeys: Boolean
        get() = TerminalModeBits.hasFlag(currentBits, TerminalModeBits.APPLICATION_CURSOR_KEYS)
        set(value) = setFlag(TerminalModeBits.APPLICATION_CURSOR_KEYS, value)

    /** DECNKM application keypad mode. False = numeric keypad (default). */
    var isApplicationKeypad: Boolean
        get() = TerminalModeBits.hasFlag(currentBits, TerminalModeBits.APPLICATION_KEYPAD)
        set(value) = setFlag(TerminalModeBits.APPLICATION_KEYPAD, value)

    /** Mode 6: Origin Mode (DECOM). False = absolute, true = relative to scroll region. */
    var isOriginMode: Boolean
        get() = TerminalModeBits.hasFlag(currentBits, TerminalModeBits.ORIGIN_MODE)
        set(value) = setFlag(TerminalModeBits.ORIGIN_MODE, value)

    /** Mode 20: New Line Mode (LNM). False = LF only, true = LF also performs CR. */
    var isNewLineMode: Boolean
        get() = TerminalModeBits.hasFlag(currentBits, TerminalModeBits.NEW_LINE_MODE)
        set(value) = setFlag(TerminalModeBits.NEW_LINE_MODE, value)

    /**
     * DEC left/right margin mode (DECLRMM, `CSI ? 69 h`).
     * False = full-width semantics (default).
     */
    var isLeftRightMarginMode: Boolean
        get() = TerminalModeBits.hasFlag(currentBits, TerminalModeBits.LEFT_RIGHT_MARGIN_MODE)
        set(value) = setFlag(TerminalModeBits.LEFT_RIGHT_MARGIN_MODE, value)

    /** Reverse-video presentation flag (DECSCNM). False = normal video (default). */
    var isReverseVideo: Boolean
        get() = TerminalModeBits.hasFlag(currentBits, TerminalModeBits.REVERSE_VIDEO)
        set(value) = setFlag(TerminalModeBits.REVERSE_VIDEO, value)

    /** Cursor visibility presentation flag. True = cursor visible (default). */
    var isCursorVisible: Boolean
        get() = TerminalModeBits.hasFlag(currentBits, TerminalModeBits.CURSOR_VISIBLE)
        set(value) = setFlag(TerminalModeBits.CURSOR_VISIBLE, value)

    /** Cursor blink presentation flag. True = blinking cursor (default). */
    var isCursorBlinking: Boolean
        get() = TerminalModeBits.hasFlag(currentBits, TerminalModeBits.CURSOR_BLINKING)
        set(value) = setFlag(TerminalModeBits.CURSOR_BLINKING, value)

    /** Bracketed paste reporting mode. False = disabled (default). */
    var isBracketedPasteEnabled: Boolean
        get() = TerminalModeBits.hasFlag(currentBits, TerminalModeBits.BRACKETED_PASTE)
        set(value) = setFlag(TerminalModeBits.BRACKETED_PASTE, value)

    /** Focus in/out reporting mode. False = disabled (default). */
    var isFocusReportingEnabled: Boolean
        get() = TerminalModeBits.hasFlag(currentBits, TerminalModeBits.FOCUS_REPORTING)
        set(value) = setFlag(TerminalModeBits.FOCUS_REPORTING, value)

    /**
     * Whether ambiguous-width Unicode characters are treated as wide (2-cell).
     * False = treat as narrow (default).
     */
    var treatAmbiguousAsWide: Boolean
        get() = TerminalModeBits.hasFlag(currentBits, TerminalModeBits.AMBIGUOUS_WIDE)
        set(value) = setFlag(TerminalModeBits.AMBIGUOUS_WIDE, value)

    /** Mouse reporting selection. Defaults to [io.github.ketraterm.protocol.MouseTrackingMode.OFF]. */
    var mouseTrackingMode: io.github.ketraterm.protocol.MouseTrackingMode
        get() = decodeMouseTracking(currentBits)
        set(value) = setPacked(TerminalModeBits.MOUSE_TRACKING_MASK, TerminalModeBits.MOUSE_TRACKING_SHIFT, value.ordinal)

    /** Mouse encoding selection. Defaults to [io.github.ketraterm.protocol.MouseEncodingMode.DEFAULT]. */
    var mouseEncodingMode: io.github.ketraterm.protocol.MouseEncodingMode
        get() = decodeMouseEncoding(currentBits)
        set(value) = setPacked(TerminalModeBits.MOUSE_ENCODING_MASK, TerminalModeBits.MOUSE_ENCODING_SHIFT, value.ordinal)

    /**
     * Modify-other-keys level.
     *
     * `-1` means explicitly disabled by XTDISMODKEYS; `0` means the xterm
     * default/reset state. The all-ones packed value is reserved for `-1`,
     * preserving the zero-initialized reset state.
     */
    var modifyOtherKeysMode: Int
        get() = TerminalInputState.modifyOtherKeysMode(currentBits)
        set(value) =
            setPacked(
                TerminalModeBits.MODIFY_OTHER_KEYS_MASK,
                TerminalModeBits.MODIFY_OTHER_KEYS_SHIFT,
                if (value < 0) {
                    TerminalModeBits.MODIFY_OTHER_KEYS_EXPLICITLY_DISABLED
                } else {
                    value.coerceIn(0, TerminalModeBits.MODIFY_OTHER_KEYS_EXPLICITLY_DISABLED - 1)
                },
            )

    /**
     * Format-other-keys wire format.
     *
     * `0` means xterm's original modifyOtherKeys format. Values are clamped to
     * the packed 2-bit range until the protocol contract grows additional
     * supported formats.
     */
    var formatOtherKeysMode: Int
        get() = TerminalInputState.formatOtherKeysMode(currentBits)
        set(value) =
            setPacked(
                TerminalModeBits.FORMAT_OTHER_KEYS_MASK,
                TerminalModeBits.FORMAT_OTHER_KEYS_SHIFT,
                value.coerceIn(0, 3),
            )

    /**
     * Active Kitty keyboard progressive-enhancement flags.
     *
     * Bits outside the encoder's protocol implementation are masked here. The
     * parser-to-core host adapter applies its per-session host capability mask
     * before setting or pushing these flags.
     */
    var kittyKeyboardFlags: Int
        get() = TerminalInputState.kittyKeyboardFlags(currentBits)
        set(value) =
            setPacked(
                TerminalModeBits.KITTY_KEYBOARD_FLAGS_MASK,
                TerminalModeBits.KITTY_KEYBOARD_FLAGS_SHIFT,
                value and io.github.ketraterm.protocol.keyboard.KittyKeyboardProgressiveFlag.ENCODER_SUPPORTED_MASK,
            )

    /** Synchronized output mode (?2026). True = enabled, false = disabled. */
    var isSynchronizedOutput: Boolean
        get() = TerminalModeBits.hasFlag(currentBits, TerminalModeBits.SYNCHRONIZED_OUTPUT)
        set(value) = setFlag(TerminalModeBits.SYNCHRONIZED_OUTPUT, value)

    /** Urgent bell mode (?1042). True = enabled, false = disabled. */
    var isBellIsUrgent: Boolean
        get() = TerminalModeBits.hasFlag(currentBits, TerminalModeBits.BELL_IS_URGENT)
        set(value) = setFlag(TerminalModeBits.BELL_IS_URGENT, value)

    /** Pop on bell mode (?1043). True = enabled, false = disabled. */
    var isPopOnBell: Boolean
        get() = TerminalModeBits.hasFlag(currentBits, TerminalModeBits.POP_ON_BELL)
        set(value) = setFlag(TerminalModeBits.POP_ON_BELL, value)

    private val currentBits: Long
        get() = modeBits.get()

    /** Returns a coherent packed mode word for future input-side fast paths. */
    fun getModeBitsSnapshot(): Long = modeBits.get()

    /** Returns a coherent packed mode word for input encoders. */
    override fun getInputModeBits(): Long = modeBits.get()

    /** Returns an immutable typed snapshot decoded from one atomic read. */
    fun getModeSnapshot(): TerminalModeSnapshot {
        val bits = modeBits.get()
        return TerminalModeSnapshot(
            isInsertMode = TerminalModeBits.hasFlag(bits, TerminalModeBits.INSERT_MODE),
            isAutoWrap = TerminalModeBits.hasFlag(bits, TerminalModeBits.AUTO_WRAP),
            isApplicationCursorKeys = TerminalModeBits.hasFlag(bits, TerminalModeBits.APPLICATION_CURSOR_KEYS),
            isApplicationKeypad = TerminalModeBits.hasFlag(bits, TerminalModeBits.APPLICATION_KEYPAD),
            isOriginMode = TerminalModeBits.hasFlag(bits, TerminalModeBits.ORIGIN_MODE),
            isNewLineMode = TerminalModeBits.hasFlag(bits, TerminalModeBits.NEW_LINE_MODE),
            isLeftRightMarginMode = TerminalModeBits.hasFlag(bits, TerminalModeBits.LEFT_RIGHT_MARGIN_MODE),
            isReverseVideo = TerminalModeBits.hasFlag(bits, TerminalModeBits.REVERSE_VIDEO),
            isCursorVisible = TerminalModeBits.hasFlag(bits, TerminalModeBits.CURSOR_VISIBLE),
            isCursorBlinking = TerminalModeBits.hasFlag(bits, TerminalModeBits.CURSOR_BLINKING),
            isBracketedPasteEnabled = TerminalModeBits.hasFlag(bits, TerminalModeBits.BRACKETED_PASTE),
            isFocusReportingEnabled = TerminalModeBits.hasFlag(bits, TerminalModeBits.FOCUS_REPORTING),
            treatAmbiguousAsWide = TerminalModeBits.hasFlag(bits, TerminalModeBits.AMBIGUOUS_WIDE),
            mouseTrackingMode = decodeMouseTracking(bits),
            mouseEncodingMode = decodeMouseEncoding(bits),
            modifyOtherKeysMode = TerminalInputState.modifyOtherKeysMode(bits),
            formatOtherKeysMode = TerminalInputState.formatOtherKeysMode(bits),
            kittyKeyboardFlags = TerminalInputState.kittyKeyboardFlags(bits),
            isSynchronizedOutput = TerminalModeBits.hasFlag(bits, TerminalModeBits.SYNCHRONIZED_OUTPUT),
            isBellIsUrgent = TerminalModeBits.hasFlag(bits, TerminalModeBits.BELL_IS_URGENT),
            isPopOnBell = TerminalModeBits.hasFlag(bits, TerminalModeBits.POP_ON_BELL),
        )
    }

    /**
     * Resets all modes to their VT/xterm-style defaults.
     * Called on hard reset.
     */
    fun reset() {
        modeBits.set(DEFAULT_MODE_BITS)
    }

    /**
     * Applies DECSTR soft-reset mode defaults.
     *
     * DECSTR is not a full terminal reset. It resets host-controlled soft modes
     * and input-facing protocol flags while preserving the core width policy.
     */
    fun softReset() {
        val preserved = modeBits.get() and SOFT_RESET_PRESERVE_MASK
        modeBits.set(preserved or SOFT_RESET_MODE_BITS)
    }

    private fun setFlag(
        flag: Long,
        enabled: Boolean,
    ) {
        while (true) {
            val old = modeBits.get()
            val new = if (enabled) old or flag else old and flag.inv()
            if (old == new || modeBits.compareAndSet(old, new)) return
        }
    }

    private fun setPacked(
        mask: Long,
        shift: Int,
        value: Int,
    ) {
        while (true) {
            val old = modeBits.get()
            val new = TerminalModeBits.withPackedValue(old, mask, shift, value)
            if (old == new || modeBits.compareAndSet(old, new)) return
        }
    }

    private companion object {
        private const val DEFAULT_MODE_BITS: Long =
            TerminalModeBits.AUTO_WRAP or TerminalModeBits.CURSOR_VISIBLE or TerminalModeBits.CURSOR_BLINKING
        private const val SOFT_RESET_PRESERVE_MASK: Long =
            TerminalModeBits.AMBIGUOUS_WIDE or TerminalModeBits.MOUSE_ENCODING_MASK
        private const val SOFT_RESET_MODE_BITS: Long = DEFAULT_MODE_BITS

        private fun decodeMouseTracking(bits: Long): io.github.ketraterm.protocol.MouseTrackingMode {
            val ordinal = TerminalInputState.mouseTrackingMode(bits)
            return io.github.ketraterm.protocol.MouseTrackingMode.entries.getOrElse(
                ordinal,
            ) { io.github.ketraterm.protocol.MouseTrackingMode.OFF }
        }

        private fun decodeMouseEncoding(bits: Long): io.github.ketraterm.protocol.MouseEncodingMode {
            val ordinal = TerminalInputState.mouseEncodingMode(bits)
            return io.github.ketraterm.protocol.MouseEncodingMode.entries.getOrElse(
                ordinal,
            ) { io.github.ketraterm.protocol.MouseEncodingMode.DEFAULT }
        }
    }
}
