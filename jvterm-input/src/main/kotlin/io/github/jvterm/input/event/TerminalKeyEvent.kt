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
package io.github.jvterm.input.event

import io.github.jvterm.input.event.TerminalKeyEvent.Companion.NO_CODEPOINT

/**
 * One keyboard event accepted by the terminal input encoder.
 *
 * Exactly one of [key] or [codepoint] must be provided. Non-printable keys use
 * [key]; printable text uses a Unicode scalar [codepoint].
 *
 * @property key non-printable key, or null when this is printable input.
 * @property codepoint Unicode scalar value for printable input, or
 * [NO_CODEPOINT] when this is a non-printable key.
 * @property modifiers active keyboard modifiers using [TerminalModifiers] bits.
 */
data class TerminalKeyEvent(
    val key: TerminalKey? = null,
    val codepoint: Int = NO_CODEPOINT,
    val modifiers: Int = TerminalModifiers.NONE,
) {
    init {
        require(TerminalModifiers.isValid(modifiers)) {
            "invalid modifier bitmask: $modifiers"
        }

        val hasKey = key != null
        val hasCodepoint = codepoint != NO_CODEPOINT

        require(hasKey xor hasCodepoint) {
            "TerminalKeyEvent must contain exactly one of key or codepoint"
        }

        if (hasCodepoint) {
            require(codepoint in 0..0x10ffff && codepoint !in 0xd800..0xdfff) {
                "invalid Unicode scalar: $codepoint"
            }
            require(codepoint !in 0x00..0x1f && codepoint != 0x7f) {
                "control codepoints must use TerminalKey events"
            }
        }
    }

    /**
     * Factory methods and sentinel values for [TerminalKeyEvent].
     */
    companion object {
        /** Sentinel used when a key event does not carry a printable codepoint. */
        const val NO_CODEPOINT: Int = -1

        /**
         * Creates a non-printable key event.
         *
         * @param key non-printable terminal key.
         * @param modifiers active keyboard modifiers.
         * @return a new [TerminalKeyEvent] instance.
         */
        fun key(
            key: TerminalKey,
            modifiers: Int = TerminalModifiers.NONE,
        ): TerminalKeyEvent =
            TerminalKeyEvent(
                key = key,
                modifiers = modifiers,
            )

        /**
         * Creates a printable Unicode scalar key event.
         *
         * @param codepoint Unicode scalar value to encode.
         * @param modifiers active keyboard modifiers.
         * @return a new [TerminalKeyEvent] instance.
         */
        fun codepoint(
            codepoint: Int,
            modifiers: Int = TerminalModifiers.NONE,
        ): TerminalKeyEvent =
            TerminalKeyEvent(
                codepoint = codepoint,
                modifiers = modifiers,
            )
    }
}
