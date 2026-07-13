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
package io.github.ketraterm.input.event

import io.github.ketraterm.input.event.TerminalKeyEvent.Companion.NO_CODEPOINT

/**
 * One keyboard event accepted by the terminal input encoder.
 *
 * Exactly one of [key] or [codepoint] must be provided. Non-printable keys use
 * [key]; printable text uses a Unicode scalar [codepoint].
 *
 * @property key non-printable key, or null when this is printable input.
 * @property codepoint Unicode scalar value for printable input, or
 * [NO_CODEPOINT] when this is a non-printable key.
 * @property unshiftedCodepoint unshifted Unicode scalar identifying the physical
 * text-producing key for Kitty-compatible protocols, or [NO_CODEPOINT] when
 * the input source cannot provide that identity. This may differ from
 * [codepoint], such as `a` for a Shift+A event that produces `A`.
 * @property modifiers active keyboard modifiers using [TerminalModifiers] bits.
 * @property type physical lifecycle phase as reported by the host adapter.
 */
data class TerminalKeyEvent(
    val key: TerminalKey? = null,
    val codepoint: Int = NO_CODEPOINT,
    val unshiftedCodepoint: Int = NO_CODEPOINT,
    val modifiers: Int = TerminalModifiers.NONE,
    val type: TerminalKeyEventType = TerminalKeyEventType.PRESS,
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

        require(!hasKey || unshiftedCodepoint == NO_CODEPOINT) {
            "unshiftedCodepoint is valid only for printable input"
        }

        if (hasCodepoint) {
            require(isPrintableUnicodeScalar(codepoint)) {
                "invalid Unicode scalar: $codepoint"
            }
            if (unshiftedCodepoint != NO_CODEPOINT) {
                require(isPrintableUnicodeScalar(unshiftedCodepoint)) {
                    "invalid unshifted Unicode scalar: $unshiftedCodepoint"
                }
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
         * @param type physical lifecycle phase reported by the host.
         * @return a new [TerminalKeyEvent] instance.
         */
        fun key(
            key: TerminalKey,
            modifiers: Int = TerminalModifiers.NONE,
            type: TerminalKeyEventType = TerminalKeyEventType.PRESS,
        ): TerminalKeyEvent =
            TerminalKeyEvent(
                key = key,
                modifiers = modifiers,
                type = type,
            )

        /**
         * Creates a printable Unicode scalar key event.
         *
         * @param codepoint Unicode scalar value to encode.
         * @param modifiers active keyboard modifiers.
         * @param unshiftedCodepoint unshifted Unicode scalar identifying the
         * physical text-producing key for Kitty-compatible protocols. Defaults
         * to [NO_CODEPOINT] when the input source cannot provide that identity.
         * @param type physical lifecycle phase reported by the host.
         * @return a new [TerminalKeyEvent] instance.
         */
        fun codepoint(
            codepoint: Int,
            modifiers: Int = TerminalModifiers.NONE,
            unshiftedCodepoint: Int = NO_CODEPOINT,
            type: TerminalKeyEventType = TerminalKeyEventType.PRESS,
        ): TerminalKeyEvent =
            TerminalKeyEvent(
                codepoint = codepoint,
                unshiftedCodepoint = unshiftedCodepoint,
                modifiers = modifiers,
                type = type,
            )

        private fun isPrintableUnicodeScalar(codepoint: Int): Boolean =
            codepoint in 0..0x10ffff &&
                codepoint !in 0xd800..0xdfff &&
                codepoint !in 0x00..0x1f &&
                codepoint != 0x7f
    }
}
