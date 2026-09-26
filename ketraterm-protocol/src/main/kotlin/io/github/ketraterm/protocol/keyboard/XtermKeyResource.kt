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
package io.github.ketraterm.protocol.keyboard

/**
 * Shared key-family IDs for xterm modifier and format set/reset/query controls.
 * The command selects the operation; both families use the same resource IDs.
 * Resource 5 is reserved for string actions and is excluded.
 * See <https://invisible-island.net/xterm/ctlseqs/ctlseqs.html>.
 */
object XtermKeyResource {
    /** Modifier admission mask for legacy/VT220 keyboard profiles. */
    const val KEYBOARD: Int = 0

    /** Cursor and editing keypad keys. */
    const val CURSOR_KEYS: Int = 1

    /** Numbered and miscellaneous function keys. */
    const val FUNCTION_KEYS: Int = 2

    /** Numeric keypad keys. */
    const val KEYPAD_KEYS: Int = 3

    /** Ordinary keys, controlled by modifyOtherKeys and formatOtherKeys. */
    const val OTHER_KEYS: Int = 4

    /** Physical modifier keys, including left/right variants. */
    const val MODIFIER_KEYS: Int = 6

    /** Remaining predefined keys such as Escape and Enter. */
    const val SPECIAL_KEYS: Int = 7

    /** Whether this is a documented key resource; string-action resource 5 is excluded. */
    fun isSupported(resource: Int): Boolean =
        resource in KEYBOARD..OTHER_KEYS ||
            resource == MODIFIER_KEYS ||
            resource == SPECIAL_KEYS

    /** Whether a semantic resource value is implemented, including explicit disable (-1). */
    fun isValidModifierValue(
        resource: Int,
        value: Int,
    ): Boolean =
        when (resource) {
            KEYBOARD -> value in -1..15
            OTHER_KEYS -> value in -1..3
            else -> isSupported(resource) && value in -1..4
        }

    /** Whether the resource and extended-report format (0 or 1) are supported. */
    fun isValidFormatValue(
        resource: Int,
        value: Int,
    ): Boolean = isSupported(resource) && value in 0..1
}
