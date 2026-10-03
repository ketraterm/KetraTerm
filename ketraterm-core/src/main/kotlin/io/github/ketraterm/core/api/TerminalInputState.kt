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
 * Primitive, read-only access to the published packed mode subset used by input encoders.
 * Factory-created buffers read this word atomically without constructing a snapshot object.
 * Implementations must return one coherent word per call; capture it once when decoding
 * multiple fields for one decision. This does not synchronize separate grid or cursor reads.
 */
public interface TerminalInputState {
    /**
     * Returns one coherent snapshot of the fields defined by [TerminalModeBits]
     * and this interface's decoding helpers. Existing numeric meanings are stable.
     * Ignore unassigned bits; future state families need not fit in this word.
     *
     * @return A packed 64-bit word containing the published input mode subset.
     */
    public fun getInputModeBits(): Long

    /**
     * Helper methods for decoding packed input mode snapshots.
     */
    public companion object {
        /**
         * Reads an XTMODKEYS resource from the same snapshot as all other input modes.
         * Returns -1 for explicit disable and -2 for an unsupported resource identifier.
         * Cursor/function resources default to 2; all other resources default to 0.
         */
        @JvmStatic
        public fun keyModifierOption(
            bits: Long,
            resource: Int,
        ): Int = XtermKeyResourceBits.modifier(bits, resource)

        /** Reads an XTFMTKEYS format (0 or 1), or -2 for an unsupported resource. */
        @JvmStatic
        public fun keyFormatOption(
            bits: Long,
            resource: Int,
        ): Int = XtermKeyResourceBits.format(bits, resource)

        /**
         * Resolves the unmodified legacy Backspace selection. An explicit DECBKM
         * override takes precedence over the host's current configured default.
         */
        @JvmStatic
        public fun backarrowSendsBackspace(
            bits: Long,
            defaultSendsBackspace: Boolean,
        ): Boolean = if (isBackarrowKeyModeExplicit(bits)) isBackarrowKeySendsBackspace(bits) else defaultSendsBackspace

        /**
         * Returns true when application cursor keys mode is enabled in [bits].
         *
         * @param bits The packed mode bits snapshot.
         * @return `true` if application cursor keys mode (DECCKM) is enabled, `false` otherwise.
         */
        @JvmStatic
        public fun isApplicationCursorKeys(bits: Long): Boolean = TerminalModeBits.hasFlag(bits, TerminalModeBits.APPLICATION_CURSOR_KEYS)

        /**
         * Returns true when application keypad mode is enabled in [bits].
         *
         * @param bits The packed mode bits snapshot.
         * @return `true` if application keypad mode (DECNKM) is enabled, `false` otherwise.
         */
        @JvmStatic
        public fun isApplicationKeypad(bits: Long): Boolean = TerminalModeBits.hasFlag(bits, TerminalModeBits.APPLICATION_KEYPAD)

        /**
         * Returns whether DECBKM has explicitly overridden the input profile's
         * initial Backspace policy in [bits].
         *
         * @param bits The packed mode bits snapshot.
         * @return `true` after DECSET or DECRST 67, until a terminal reset.
         */
        @JvmStatic
        public fun isBackarrowKeyModeExplicit(bits: Long): Boolean =
            TerminalModeBits.hasFlag(bits, TerminalModeBits.BACKARROW_KEY_MODE_EXPLICIT)

        /**
         * Returns the explicit DECBKM wire selection from [bits].
         *
         * @param bits The packed mode bits snapshot.
         * @return `true` for BS and `false` for DEL.
         */
        @JvmStatic
        public fun isBackarrowKeySendsBackspace(bits: Long): Boolean =
            TerminalModeBits.hasFlag(bits, TerminalModeBits.BACKARROW_KEY_SENDS_BACKSPACE)

        /**
         * Returns true when new-line mode is enabled in [bits].
         *
         * @param bits The packed mode bits snapshot.
         * @return `true` if new-line mode (LNM) is enabled, `false` otherwise.
         */
        @JvmStatic
        public fun isNewLineMode(bits: Long): Boolean = TerminalModeBits.hasFlag(bits, TerminalModeBits.NEW_LINE_MODE)

        /**
         * Returns true when bracketed paste mode is enabled in [bits].
         *
         * @param bits The packed mode bits snapshot.
         * @return `true` if bracketed paste mode (?2004) is enabled, `false` otherwise.
         */
        @JvmStatic
        public fun isBracketedPasteEnabled(bits: Long): Boolean = TerminalModeBits.hasFlag(bits, TerminalModeBits.BRACKETED_PASTE)

        /**
         * Returns true when focus reporting mode is enabled in [bits].
         *
         * @param bits The packed mode bits snapshot.
         * @return `true` if focus reporting mode (?1004) is enabled, `false` otherwise.
         */
        @JvmStatic
        public fun isFocusReportingEnabled(bits: Long): Boolean = TerminalModeBits.hasFlag(bits, TerminalModeBits.FOCUS_REPORTING)

        /**
         * Returns the packed mouse tracking mode ordinal from [bits].
         *
         * @param bits The packed mode bits snapshot.
         * @return The ordinal integer of the active [io.github.ketraterm.protocol.MouseTrackingMode].
         */
        @JvmStatic
        public fun mouseTrackingMode(bits: Long): Int =
            TerminalModeBits.packedValue(
                bits,
                TerminalModeBits.MOUSE_TRACKING_MASK,
                TerminalModeBits.MOUSE_TRACKING_SHIFT,
            )

        /**
         * Returns the packed mouse encoding mode ordinal from [bits].
         *
         * @param bits The packed mode bits snapshot.
         * @return The ordinal integer of the active [io.github.ketraterm.protocol.MouseEncodingMode].
         */
        @JvmStatic
        public fun mouseEncodingMode(bits: Long): Int =
            TerminalModeBits.packedValue(
                bits,
                TerminalModeBits.MOUSE_ENCODING_MASK,
                TerminalModeBits.MOUSE_ENCODING_SHIFT,
            )

        /**
         * Returns the packed modify-other-keys mode value from [bits].
         *
         * @param bits The packed mode bits snapshot.
         * @return The active modify-other-keys mode level (typically 0, 1, or 2).
         */
        @JvmStatic
        public fun modifyOtherKeysMode(bits: Long): Int {
            val packed =
                TerminalModeBits.packedValue(
                    bits,
                    TerminalModeBits.MODIFY_OTHER_KEYS_MASK,
                    TerminalModeBits.MODIFY_OTHER_KEYS_SHIFT,
                )
            return if (packed == TerminalModeBits.MODIFY_OTHER_KEYS_EXPLICITLY_DISABLED) -1 else packed
        }

        /**
         * Returns the packed format-other-keys mode value from [bits].
         *
         * @param bits The packed mode bits snapshot.
         * @return The active format-other-keys mode level.
         */
        @JvmStatic
        public fun formatOtherKeysMode(bits: Long): Int =
            TerminalModeBits.packedValue(
                bits,
                TerminalModeBits.FORMAT_OTHER_KEYS_MASK,
                TerminalModeBits.FORMAT_OTHER_KEYS_SHIFT,
            )

        /**
         * Returns the packed Kitty keyboard progressive-enhancement flags from [bits].
         *
         * @param bits The packed mode bits snapshot.
         * @return The active Kitty keyboard progressive-enhancement flag bitmask.
         */
        @JvmStatic
        public fun kittyKeyboardFlags(bits: Long): Int =
            TerminalModeBits.packedValue(
                bits,
                TerminalModeBits.KITTY_KEYBOARD_FLAGS_MASK,
                TerminalModeBits.KITTY_KEYBOARD_FLAGS_SHIFT,
            )
    }
}
