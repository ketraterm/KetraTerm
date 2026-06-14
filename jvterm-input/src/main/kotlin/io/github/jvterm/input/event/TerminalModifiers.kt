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

/**
 * Bit mask vocabulary for UI keyboard modifiers accepted by terminal input.
 *
 * These are internal encoder bits, not CSI modifier parameter values. CSI
 * encoding applies the `1 + modifiers` translation only at the wire boundary.
 */
object TerminalModifiers {
    /** No keyboard modifiers are active. */
    const val NONE: Int = 0

    /** Shift is active. */
    const val SHIFT: Int = 1 shl 0

    /** Alt is active. */
    const val ALT: Int = 1 shl 1

    /** Control is active. */
    const val CTRL: Int = 1 shl 2

    /** Meta or command is active. */
    const val META: Int = 1 shl 3

    /** Mask containing every supported modifier bit. */
    const val VALID_MASK: Int = SHIFT or ALT or CTRL or META

    /**
     * Returns true when Shift is present in [modifiers].
     *
     * @param modifiers active modifier bitmask.
     * @return true if Shift is active, false otherwise.
     */
    fun hasShift(modifiers: Int): Boolean = (modifiers and SHIFT) != 0

    /**
     * Returns true when Alt is present in [modifiers].
     *
     * @param modifiers active modifier bitmask.
     * @return true if Alt is active, false otherwise.
     */
    fun hasAlt(modifiers: Int): Boolean = (modifiers and ALT) != 0

    /**
     * Returns true when Control is present in [modifiers].
     *
     * @param modifiers active modifier bitmask.
     * @return true if Control is active, false otherwise.
     */
    fun hasCtrl(modifiers: Int): Boolean = (modifiers and CTRL) != 0

    /**
     * Returns true when Meta is present in [modifiers].
     *
     * @param modifiers active modifier bitmask.
     * @return true if Meta is active, false otherwise.
     */
    fun hasMeta(modifiers: Int): Boolean = (modifiers and META) != 0

    /**
     * Returns true when [modifiers] contains only supported modifier bits.
     *
     * @param modifiers active modifier bitmask.
     * @return true if valid, false otherwise.
     */
    fun isValid(modifiers: Int): Boolean = (modifiers and VALID_MASK.inv()) == 0

    /**
     * Converts internal modifier bits to an xterm-style CSI modifier parameter.
     *
     * @param modifiers active modifier bitmask.
     * @return 1-based xterm CSI modifier parameter value.
     * @throws IllegalArgumentException when [modifiers] contains unsupported
     * bits.
     */
    fun toCsiModifierParam(modifiers: Int): Int {
        require(isValid(modifiers)) { "invalid modifier bitmask: $modifiers" }
        return 1 + modifiers
    }
}
