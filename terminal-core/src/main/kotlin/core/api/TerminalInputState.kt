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

/**
 * Zero-allocation, read-only terminal behavior state required by input encoders.
 */
interface TerminalInputState {
    /**
     * Returns one coherent snapshot of all input-readable terminal mode bits.
     */
    fun getInputModeBits(): Long

    /**
     * Helper methods for decoding packed input mode snapshots.
     */
    companion object {
        /**
         * Returns true when application cursor keys mode is enabled in [bits].
         */
        @JvmStatic
        fun isApplicationCursorKeys(bits: Long): Boolean = TerminalModeBits.hasFlag(bits, TerminalModeBits.APPLICATION_CURSOR_KEYS)

        /**
         * Returns true when application keypad mode is enabled in [bits].
         */
        @JvmStatic
        fun isApplicationKeypad(bits: Long): Boolean = TerminalModeBits.hasFlag(bits, TerminalModeBits.APPLICATION_KEYPAD)

        /**
         * Returns true when new-line mode is enabled in [bits].
         */
        @JvmStatic
        fun isNewLineMode(bits: Long): Boolean = TerminalModeBits.hasFlag(bits, TerminalModeBits.NEW_LINE_MODE)

        /**
         * Returns true when bracketed paste mode is enabled in [bits].
         */
        @JvmStatic
        fun isBracketedPasteEnabled(bits: Long): Boolean = TerminalModeBits.hasFlag(bits, TerminalModeBits.BRACKETED_PASTE)

        /**
         * Returns true when focus reporting mode is enabled in [bits].
         */
        @JvmStatic
        fun isFocusReportingEnabled(bits: Long): Boolean = TerminalModeBits.hasFlag(bits, TerminalModeBits.FOCUS_REPORTING)

        /**
         * Returns the packed mouse tracking mode ordinal from [bits].
         */
        @JvmStatic
        fun mouseTrackingMode(bits: Long): Int =
            TerminalModeBits.packedValue(
                bits,
                TerminalModeBits.MOUSE_TRACKING_MASK,
                TerminalModeBits.MOUSE_TRACKING_SHIFT,
            )

        /**
         * Returns the packed mouse encoding mode ordinal from [bits].
         */
        @JvmStatic
        fun mouseEncodingMode(bits: Long): Int =
            TerminalModeBits.packedValue(
                bits,
                TerminalModeBits.MOUSE_ENCODING_MASK,
                TerminalModeBits.MOUSE_ENCODING_SHIFT,
            )

        /**
         * Returns the packed modify-other-keys mode value from [bits].
         */
        @JvmStatic
        fun modifyOtherKeysMode(bits: Long): Int =
            TerminalModeBits.packedValue(
                bits,
                TerminalModeBits.MODIFY_OTHER_KEYS_MASK,
                TerminalModeBits.MODIFY_OTHER_KEYS_SHIFT,
            )

        /**
         * Returns the packed format-other-keys mode value from [bits].
         */
        @JvmStatic
        fun formatOtherKeysMode(bits: Long): Int =
            TerminalModeBits.packedValue(
                bits,
                TerminalModeBits.FORMAT_OTHER_KEYS_MASK,
                TerminalModeBits.FORMAT_OTHER_KEYS_SHIFT,
            )
    }
}
