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

    /** Super is active, such as Windows or Command. */
    const val SUPER: Int = 1 shl 3

    /** Hyper is active. */
    const val HYPER: Int = 1 shl 4

    /** Meta is active. */
    const val META: Int = 1 shl 5

    /** Caps Lock is enabled. */
    const val CAPS_LOCK: Int = 1 shl 6

    /** Num Lock is enabled. */
    const val NUM_LOCK: Int = 1 shl 7

    /** Mask containing every supported modifier bit. */
    const val VALID_MASK: Int = SHIFT or ALT or CTRL or SUPER or HYPER or META or CAPS_LOCK or NUM_LOCK

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

    /** Returns true when Super is present in [modifiers]. */
    fun hasSuper(modifiers: Int): Boolean = (modifiers and SUPER) != 0

    /** Returns true when Hyper is present in [modifiers]. */
    fun hasHyper(modifiers: Int): Boolean = (modifiers and HYPER) != 0

    /** Returns true when Caps Lock is present in [modifiers]. */
    fun hasCapsLock(modifiers: Int): Boolean = (modifiers and CAPS_LOCK) != 0

    /** Returns true when Num Lock is present in [modifiers]. */
    fun hasNumLock(modifiers: Int): Boolean = (modifiers and NUM_LOCK) != 0

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
     * Lock modifiers are not representable in xterm CSI parameters. Super,
     * Hyper, and Meta are merged into xterm's legacy fourth modifier bit.
     * @return 1-based xterm CSI modifier parameter value.
     * @throws IllegalArgumentException when [modifiers] contains unsupported
     * bits.
     */
    fun toCsiModifierParam(modifiers: Int): Int {
        require(isValid(modifiers)) { "invalid modifier bitmask: $modifiers" }
        var legacyModifiers = modifiers and (SHIFT or ALT or CTRL)
        if ((modifiers and (SUPER or HYPER or META)) != 0) {
            legacyModifiers = legacyModifiers or SUPER
        }
        return 1 + legacyModifiers
    }

    /**
     * Converts internal modifier bits to Kitty's CSI-u modifier parameter.
     *
     * @param modifiers active modifier bitmask.
     * @return Kitty's one-based modifier parameter.
     */
    fun toKittyCsiModifierParam(modifiers: Int): Int {
        require(isValid(modifiers)) { "invalid modifier bitmask: $modifiers" }
        return 1 + modifiers
    }
}
