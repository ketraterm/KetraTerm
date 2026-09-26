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

import io.github.ketraterm.protocol.keyboard.XtermKeyResource

/** Packed xterm resources in the existing atomic mode word; zero encodes defaults. */
internal object XtermKeyResourceBits {
    private const val MODIFIER_SHIFT = 37
    private const val FORMAT_SHIFT = 57
    private const val EXPLICITLY_DISABLED = 7
    private const val KEYBOARD_DISABLED = 31
    private const val UNSUPPORTED = -2
    const val MODIFIER_MASK: Long = (0xfffffL shl MODIFIER_SHIFT) or TerminalModeBits.MODIFY_OTHER_KEYS_MASK
    const val FORMAT_MASK: Long = (0x3fL shl FORMAT_SHIFT) or TerminalModeBits.FORMAT_OTHER_KEYS_MASK

    private fun slot(resource: Int): Int =
        when (resource) {
            XtermKeyResource.CURSOR_KEYS -> 0
            XtermKeyResource.FUNCTION_KEYS -> 1
            XtermKeyResource.KEYPAD_KEYS -> 2
            XtermKeyResource.MODIFIER_KEYS -> 3
            XtermKeyResource.SPECIAL_KEYS -> 4
            XtermKeyResource.KEYBOARD -> 5
            else -> -1
        }

    fun defaultModifier(resource: Int): Int =
        when (resource) {
            XtermKeyResource.CURSOR_KEYS, XtermKeyResource.FUNCTION_KEYS -> 2
            else -> 0
        }

    fun modifier(
        bits: Long,
        resource: Int,
    ): Int {
        if (resource == XtermKeyResource.OTHER_KEYS) return TerminalInputState.modifyOtherKeysMode(bits)
        val slot = slot(resource)
        if (slot < 0) return UNSUPPORTED
        val sentinel = if (resource == XtermKeyResource.KEYBOARD) KEYBOARD_DISABLED else EXPLICITLY_DISABLED
        val value = ((bits ushr (MODIFIER_SHIFT + slot * 3)).toInt() and sentinel) xor defaultModifier(resource)
        return if (value == sentinel) -1 else value
    }

    fun format(
        bits: Long,
        resource: Int,
    ): Int {
        if (resource == XtermKeyResource.OTHER_KEYS) return TerminalInputState.formatOtherKeysMode(bits)
        val slot = slot(resource)
        return if (slot < 0) UNSUPPORTED else ((bits ushr (FORMAT_SHIFT + slot)).toInt() and 1)
    }

    fun withModifier(
        bits: Long,
        resource: Int,
        value: Int,
    ): Long {
        require(
            XtermKeyResource.isValidModifierValue(resource, value),
        ) { "Unsupported xterm key modifier resource/value: $resource/$value" }
        val other = resource == XtermKeyResource.OTHER_KEYS
        val shift = if (other) TerminalModeBits.MODIFY_OTHER_KEYS_SHIFT else MODIFIER_SHIFT + slot(resource) * 3
        val sentinel = if (resource == XtermKeyResource.KEYBOARD) KEYBOARD_DISABLED else EXPLICITLY_DISABLED
        val packed = (if (value < 0) sentinel else value) xor defaultModifier(resource)
        return TerminalModeBits.withPackedValue(bits, sentinel.toLong() shl shift, shift, packed)
    }

    fun withFormat(
        bits: Long,
        resource: Int,
        value: Int,
    ): Long {
        require(XtermKeyResource.isValidFormatValue(resource, value)) {
            "Unsupported xterm key format resource/value: $resource/$value"
        }
        val other = resource == XtermKeyResource.OTHER_KEYS
        val shift = if (other) TerminalModeBits.FORMAT_OTHER_KEYS_SHIFT else FORMAT_SHIFT + slot(resource)
        val mask = if (other) TerminalModeBits.FORMAT_OTHER_KEYS_MASK else 1L shl shift
        return TerminalModeBits.withPackedValue(bits, mask, shift, value)
    }
}
