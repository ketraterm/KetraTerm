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
package com.gagik.terminal.input.impl

import com.gagik.core.api.TerminalInputState
import com.gagik.terminal.input.event.TerminalKey
import com.gagik.terminal.input.event.TerminalKeyEvent
import com.gagik.terminal.input.event.TerminalModifiers
import com.gagik.terminal.input.policy.BackspacePolicy
import com.gagik.terminal.input.policy.MetaKeyPolicy
import com.gagik.terminal.input.policy.TerminalInputPolicy
import com.gagik.terminal.input.policy.UnsupportedModifiedKeyPolicy
import com.gagik.terminal.protocol.ControlCode
import com.gagik.terminal.protocol.FormatOtherKeysMode
import com.gagik.terminal.protocol.ModifyOtherKeysMode
import com.gagik.terminal.protocol.host.TerminalHostOutput

internal class KeyboardEncoder(
    private val output: TerminalHostOutput,
    private val scratch: InputScratchBuffer,
    private val policy: TerminalInputPolicy = TerminalInputPolicy(),
) {
    fun encode(
        event: TerminalKeyEvent,
        modeBits: Long,
    ) {
        val key = event.key
        if (key != null) {
            encodeSpecialKey(
                key = key,
                modifiers = event.modifiers,
                modeBits = modeBits,
            )
        } else {
            encodeCodepoint(
                codepoint = event.codepoint,
                modifiers = event.modifiers,
                modeBits = modeBits,
            )
        }
    }

    private fun encodeCodepoint(
        codepoint: Int,
        modifiers: Int,
        modeBits: Long,
    ) {
        if (shouldEncodeModifyOtherKey(codepoint, modifiers, modeBits)) {
            encodeModifyOtherKey(codepoint, modifiers, modeBits)
            return
        }

        if (shouldSuppressForMeta(modifiers)) {
            return
        }

        val ctrlRequested = TerminalModifiers.hasCtrl(modifiers)
        val ctrlCode = if (ctrlRequested) controlCodeFor(codepoint) else -1

        if (ctrlRequested && ctrlCode < 0) {
            when (policy.unsupportedModifiedKeyPolicy) {
                UnsupportedModifiedKeyPolicy.SUPPRESS -> return
                UnsupportedModifiedKeyPolicy.EMIT_UNMODIFIED -> {
                    if (shouldPrefixEscape(modifiers)) {
                        output.writeByte(ControlCode.ESC)
                    }
                    writeUtf8Codepoint(codepoint)
                    return
                }
            }
        }

        if (shouldPrefixEscape(modifiers)) {
            output.writeByte(ControlCode.ESC)
        }

        if (ctrlCode >= 0) {
            output.writeByte(ctrlCode)
            return
        }

        writeUtf8Codepoint(codepoint)
    }

    private fun encodeSpecialKey(
        key: TerminalKey,
        modifiers: Int,
        modeBits: Long,
    ) {
        when (key) {
            TerminalKey.ENTER -> encodeEnter(modifiers, modeBits)
            TerminalKey.NUMPAD_ENTER -> encodeKeypad(key, modifiers, modeBits)

            TerminalKey.TAB -> encodeTab(modifiers, modeBits)

            TerminalKey.BACKSPACE -> encodeBackspace(modifiers, modeBits)

            TerminalKey.ESCAPE -> encodeEscape(modifiers, modeBits)

            TerminalKey.UP ->
                encodeArrow(
                    modifiers = modifiers,
                    normalFinal = 'A'.code,
                    applicationFinal = 'A'.code,
                    modeBits = modeBits,
                )

            TerminalKey.DOWN ->
                encodeArrow(
                    modifiers = modifiers,
                    normalFinal = 'B'.code,
                    applicationFinal = 'B'.code,
                    modeBits = modeBits,
                )

            TerminalKey.RIGHT ->
                encodeArrow(
                    modifiers = modifiers,
                    normalFinal = 'C'.code,
                    applicationFinal = 'C'.code,
                    modeBits = modeBits,
                )

            TerminalKey.LEFT ->
                encodeArrow(
                    modifiers = modifiers,
                    normalFinal = 'D'.code,
                    applicationFinal = 'D'.code,
                    modeBits = modeBits,
                )

            TerminalKey.HOME ->
                encodeHomeEnd(
                    modifiers = modifiers,
                    normalFinal = 'H'.code,
                    applicationFinal = 'H'.code,
                    modeBits = modeBits,
                )

            TerminalKey.END ->
                encodeHomeEnd(
                    modifiers = modifiers,
                    normalFinal = 'F'.code,
                    applicationFinal = 'F'.code,
                    modeBits = modeBits,
                )

            TerminalKey.INSERT -> encodeTildeKey(2, modifiers)
            TerminalKey.DELETE -> encodeTildeKey(3, modifiers)
            TerminalKey.PAGE_UP -> encodeTildeKey(5, modifiers)
            TerminalKey.PAGE_DOWN -> encodeTildeKey(6, modifiers)

            TerminalKey.F1 -> encodeFunctionSs3OrModified('P'.code, modifiers)
            TerminalKey.F2 -> encodeFunctionSs3OrModified('Q'.code, modifiers)
            TerminalKey.F3 -> encodeFunctionSs3OrModified('R'.code, modifiers)
            TerminalKey.F4 -> encodeFunctionSs3OrModified('S'.code, modifiers)

            TerminalKey.F5 -> encodeTildeKey(15, modifiers)
            TerminalKey.F6 -> encodeTildeKey(17, modifiers)
            TerminalKey.F7 -> encodeTildeKey(18, modifiers)
            TerminalKey.F8 -> encodeTildeKey(19, modifiers)
            TerminalKey.F9 -> encodeTildeKey(20, modifiers)
            TerminalKey.F10 -> encodeTildeKey(21, modifiers)
            TerminalKey.F11 -> encodeTildeKey(23, modifiers)
            TerminalKey.F12 -> encodeTildeKey(24, modifiers)

            TerminalKey.PF1 -> encodeFunctionSs3OrModified('P'.code, modifiers)
            TerminalKey.PF2 -> encodeFunctionSs3OrModified('Q'.code, modifiers)
            TerminalKey.PF3 -> encodeFunctionSs3OrModified('R'.code, modifiers)
            TerminalKey.PF4 -> encodeFunctionSs3OrModified('S'.code, modifiers)

            TerminalKey.NUMPAD_SPACE,
            TerminalKey.NUMPAD_TAB,
            TerminalKey.NUMPAD_DIVIDE,
            TerminalKey.NUMPAD_MULTIPLY,
            TerminalKey.NUMPAD_SUBTRACT,
            TerminalKey.NUMPAD_ADD,
            TerminalKey.NUMPAD_COMMA,
            TerminalKey.NUMPAD_SEPARATOR,
            TerminalKey.NUMPAD_EQUALS,
            TerminalKey.NUMPAD_BEGIN,
            TerminalKey.NUMPAD_DECIMAL,
            TerminalKey.NUMPAD_0,
            TerminalKey.NUMPAD_1,
            TerminalKey.NUMPAD_2,
            TerminalKey.NUMPAD_3,
            TerminalKey.NUMPAD_4,
            TerminalKey.NUMPAD_5,
            TerminalKey.NUMPAD_6,
            TerminalKey.NUMPAD_7,
            TerminalKey.NUMPAD_8,
            TerminalKey.NUMPAD_9,
            -> encodeKeypad(key, modifiers, modeBits)
        }
    }

    private fun encodeBackspace(
        modifiers: Int,
        modeBits: Long,
    ) {
        if (shouldEncodeModifyOtherSpecial(BACKSPACE_CODEPOINT, modifiers, modeBits)) {
            encodeModifyOtherKey(BACKSPACE_CODEPOINT, modifiers, modeBits)
            return
        }

        val unsupportedModifier =
            TerminalModifiers.hasShift(modifiers) ||
                (
                    TerminalModifiers.hasCtrl(modifiers) &&
                        (
                            TerminalModifiers.hasShift(modifiers) ||
                                TerminalModifiers.hasAlt(modifiers) ||
                                TerminalModifiers.hasMeta(modifiers)
                        )
                )

        if (unsupportedModifier) {
            when (policy.unsupportedModifiedKeyPolicy) {
                UnsupportedModifiedKeyPolicy.SUPPRESS -> return
                UnsupportedModifiedKeyPolicy.EMIT_UNMODIFIED -> {
                    // Continue as the unmodified key except for Alt/Meta prefix policy.
                }
            }
        }

        if (!writeModifierPrefixOrSuppress(modifiers)) {
            return
        }

        val baseByte =
            when (policy.backspacePolicy) {
                BackspacePolicy.DELETE -> ControlCode.DEL
                BackspacePolicy.BACKSPACE -> BS
            }

        if (TerminalModifiers.hasCtrl(modifiers)) {
            output.writeByte(if (baseByte == ControlCode.DEL) BS else ControlCode.DEL)
        } else {
            output.writeByte(baseByte)
        }
    }

    private fun encodeEnter(
        modifiers: Int,
        modeBits: Long,
    ) {
        if (shouldEncodeModifyOtherSpecial(ENTER_CODEPOINT, modifiers, modeBits)) {
            encodeModifyOtherKey(ENTER_CODEPOINT, modifiers, modeBits)
            return
        }

        val unsupportedModifier =
            TerminalModifiers.hasShift(modifiers) || TerminalModifiers.hasCtrl(modifiers)

        if (unsupportedModifier) {
            when (policy.unsupportedModifiedKeyPolicy) {
                UnsupportedModifiedKeyPolicy.SUPPRESS -> return
                UnsupportedModifiedKeyPolicy.EMIT_UNMODIFIED -> {
                    // Continue as the unmodified key except for Alt/Meta prefix policy.
                }
            }
        }

        if (!writeModifierPrefixOrSuppress(modifiers)) {
            return
        }

        if (TerminalInputState.isNewLineMode(modeBits)) {
            output.writeByte(ControlCode.CR)
            output.writeByte(ControlCode.LF)
        } else {
            output.writeByte(ControlCode.CR)
        }
    }

    private fun encodeEscape(
        modifiers: Int,
        modeBits: Long,
    ) {
        if (shouldEncodeModifyOtherSpecial(ESCAPE_CODEPOINT, modifiers, modeBits)) {
            encodeModifyOtherKey(ESCAPE_CODEPOINT, modifiers, modeBits)
            return
        }

        val unsupportedModifier =
            TerminalModifiers.hasShift(modifiers) || TerminalModifiers.hasCtrl(modifiers)

        if (
            unsupportedModifier &&
            policy.unsupportedModifiedKeyPolicy == UnsupportedModifiedKeyPolicy.SUPPRESS
        ) {
            return
        }

        if (!writeModifierPrefixOrSuppress(modifiers)) {
            return
        }

        output.writeByte(ControlCode.ESC)
    }

    private fun encodeTab(modifiers: Int) {
        if (modifiers == TerminalModifiers.NONE) {
            output.writeByte(ControlCode.HT)
            return
        }

        if (modifiers == TerminalModifiers.SHIFT) {
            writeStatic(TerminalSequences.BACK_TAB)
            return
        }

        encodeCsiModifierFinal(
            prefixNumber = 1,
            modifiers = modifiers,
            finalByte = 'Z'.code,
        )
    }

    private fun encodeTab(
        modifiers: Int,
        modeBits: Long,
    ) {
        if (shouldEncodeModifyOtherSpecial(TAB_CODEPOINT, modifiers, modeBits)) {
            encodeModifyOtherKey(TAB_CODEPOINT, modifiers, modeBits)
            return
        }

        encodeTab(modifiers)
    }

    /**
     * Implements xterm modifyOtherKeys selection for ordinary printable keys.
     * The actual wire shape is selected by formatOtherKeys at emission time.
     */
    private fun shouldEncodeModifyOtherKey(
        codepoint: Int,
        modifiers: Int,
        modeBits: Long,
    ): Boolean =
        when (TerminalInputState.modifyOtherKeysMode(modeBits)) {
            ModifyOtherKeysMode.MODE_1 -> isLegacyAmbiguousOrMissing(codepoint, modifiers)
            ModifyOtherKeysMode.MODE_2 -> modifiers != TerminalModifiers.NONE
            ModifyOtherKeysMode.MODE_3 -> true
            else -> false
        }

    private fun shouldEncodeModifyOtherSpecial(
        codepoint: Int,
        modifiers: Int,
        modeBits: Long,
    ): Boolean =
        when (TerminalInputState.modifyOtherKeysMode(modeBits)) {
            ModifyOtherKeysMode.MODE_1 ->
                modifiers != TerminalModifiers.NONE &&
                    (TerminalModifiers.hasAlt(modifiers) || TerminalModifiers.hasMeta(modifiers))
            ModifyOtherKeysMode.MODE_2 -> modifiers != TerminalModifiers.NONE
            ModifyOtherKeysMode.MODE_3 -> true
            else -> false
        } &&
            (
                codepoint == TAB_CODEPOINT ||
                    codepoint == ENTER_CODEPOINT ||
                    codepoint == BACKSPACE_CODEPOINT ||
                    codepoint == ESCAPE_CODEPOINT
            )

    private fun isLegacyAmbiguousOrMissing(
        codepoint: Int,
        modifiers: Int,
    ): Boolean {
        if (TerminalModifiers.hasAlt(modifiers) || TerminalModifiers.hasMeta(modifiers)) {
            return true
        }

        if (TerminalModifiers.hasShift(modifiers) && modifiers != TerminalModifiers.SHIFT) {
            return true
        }

        if (TerminalModifiers.hasCtrl(modifiers) && controlCodeFor(codepoint) < 0) {
            return true
        }

        return false
    }

    private fun encodeModifyOtherKey(
        codepoint: Int,
        modifiers: Int,
        modeBits: Long,
    ) {
        if (TerminalInputState.formatOtherKeysMode(modeBits) == FormatOtherKeysMode.CSI_U) {
            encodeModifyOtherKeyCsiU(codepoint, modifiers)
        } else {
            encodeModifyOtherKeyLegacy(codepoint, modifiers)
        }
    }

    private fun encodeModifyOtherKeyLegacy(
        codepoint: Int,
        modifiers: Int,
    ) {
        scratch.clear()
        scratch.appendByte(ControlCode.ESC)
        scratch.appendByte('['.code)
        scratch.appendDecimal(MODIFY_OTHER_KEYS_PREFIX)
        scratch.appendByte(';'.code)
        scratch.appendDecimal(TerminalModifiers.toCsiModifierParam(modifiers))
        scratch.appendByte(';'.code)
        scratch.appendDecimal(codepoint)
        scratch.appendByte('~'.code)
        scratch.writeTo(output)
    }

    private fun encodeModifyOtherKeyCsiU(
        codepoint: Int,
        modifiers: Int,
    ) {
        scratch.clear()
        scratch.appendByte(ControlCode.ESC)
        scratch.appendByte('['.code)
        scratch.appendDecimal(codepoint)
        scratch.appendByte(';'.code)
        scratch.appendDecimal(TerminalModifiers.toCsiModifierParam(modifiers))
        scratch.appendByte('u'.code)
        scratch.writeTo(output)
    }

    private fun encodeArrow(
        modifiers: Int,
        normalFinal: Int,
        applicationFinal: Int,
        modeBits: Long,
    ) {
        if (modifiers != TerminalModifiers.NONE) {
            encodeCsiModifierFinal(
                prefixNumber = 1,
                modifiers = modifiers,
                finalByte = normalFinal,
            )
            return
        }

        if (TerminalInputState.isApplicationCursorKeys(modeBits)) {
            writeStatic(applicationCursorSequence(applicationFinal))
        } else {
            writeStatic(cursorSequence(normalFinal))
        }
    }

    private fun encodeHomeEnd(
        modifiers: Int,
        normalFinal: Int,
        applicationFinal: Int,
        modeBits: Long,
    ) {
        if (modifiers != TerminalModifiers.NONE) {
            encodeCsiModifierFinal(
                prefixNumber = 1,
                modifiers = modifiers,
                finalByte = normalFinal,
            )
            return
        }

        if (TerminalInputState.isApplicationCursorKeys(modeBits)) {
            writeStatic(homeEndSequence(applicationFinal, application = true))
        } else {
            writeStatic(homeEndSequence(normalFinal, application = false))
        }
    }

    private fun encodeFunctionSs3OrModified(
        unmodifiedFinal: Int,
        modifiers: Int,
    ) {
        if (modifiers == TerminalModifiers.NONE) {
            writeStatic(functionSs3Sequence(unmodifiedFinal))
            return
        }

        encodeCsiModifierFinal(
            prefixNumber = 1,
            modifiers = modifiers,
            finalByte = unmodifiedFinal,
        )
    }

    private fun encodeTildeKey(
        number: Int,
        modifiers: Int,
    ) {
        if (modifiers == TerminalModifiers.NONE) {
            writeStatic(tildeSequence(number))
            return
        }

        scratch.clear()
        scratch.appendByte(ControlCode.ESC)
        scratch.appendByte('['.code)
        scratch.appendDecimal(number)

        if (modifiers != TerminalModifiers.NONE) {
            scratch.appendByte(';'.code)
            scratch.appendDecimal(TerminalModifiers.toCsiModifierParam(modifiers))
        }

        scratch.appendByte('~'.code)
        scratch.writeTo(output)
    }

    private fun encodeCsiModifierFinal(
        prefixNumber: Int,
        modifiers: Int,
        finalByte: Int,
    ) {
        scratch.clear()
        scratch.appendByte(ControlCode.ESC)
        scratch.appendByte('['.code)
        scratch.appendDecimal(prefixNumber)
        scratch.appendByte(';'.code)
        scratch.appendDecimal(TerminalModifiers.toCsiModifierParam(modifiers))
        scratch.appendByte(finalByte)
        scratch.writeTo(output)
    }

    private fun encodeKeypad(
        key: TerminalKey,
        modifiers: Int,
        modeBits: Long,
    ) {
        if (modifiers != TerminalModifiers.NONE) {
            val unsupportedModifier =
                TerminalModifiers.hasShift(modifiers) || TerminalModifiers.hasCtrl(modifiers)

            if (unsupportedModifier) {
                when (policy.unsupportedModifiedKeyPolicy) {
                    UnsupportedModifiedKeyPolicy.SUPPRESS -> return
                    UnsupportedModifiedKeyPolicy.EMIT_UNMODIFIED -> {
                        // Continue as the unmodified keypad key except for Alt/Meta prefix policy.
                    }
                }
            }

            if (!writeModifierPrefixOrSuppress(modifiers)) {
                return
            }
        }

        if (TerminalInputState.isApplicationKeypad(modeBits)) {
            if (key == TerminalKey.NUMPAD_BEGIN) {
                writeCsiFinal('E'.code)
                return
            }

            val final = applicationKeypadFinal(key)
            if (final >= 0) {
                writeSs3(final)
            }
            return
        }

        if (key == TerminalKey.NUMPAD_ENTER) {
            encodeEnter(TerminalModifiers.NONE, modeBits)
            return
        }

        val ascii = normalKeypadAscii(key)
        if (ascii >= 0) {
            output.writeByte(ascii)
        }
    }

    private fun shouldSuppressForMeta(modifiers: Int): Boolean =
        TerminalModifiers.hasMeta(modifiers) &&
            !TerminalModifiers.hasAlt(modifiers) &&
            policy.metaKeyPolicy == MetaKeyPolicy.SUPPRESS_EVENT

    private fun shouldPrefixEscape(modifiers: Int): Boolean {
        if (policy.altSendsEscapePrefix && TerminalModifiers.hasAlt(modifiers)) {
            return true
        }

        if (TerminalModifiers.hasMeta(modifiers)) {
            return when (policy.metaKeyPolicy) {
                MetaKeyPolicy.ESC_PREFIX -> true
                MetaKeyPolicy.IGNORE_META -> false
                MetaKeyPolicy.SUPPRESS_EVENT -> false
            }
        }

        return false
    }

    private fun writeModifierPrefixOrSuppress(modifiers: Int): Boolean {
        if (shouldSuppressForMeta(modifiers)) {
            return false
        }
        if (shouldPrefixEscape(modifiers)) {
            output.writeByte(ControlCode.ESC)
        }
        return true
    }

    private fun applicationKeypadFinal(key: TerminalKey): Int =
        when (key) {
            TerminalKey.NUMPAD_SPACE -> ' '.code
            TerminalKey.NUMPAD_TAB -> 'I'.code
            TerminalKey.NUMPAD_ENTER -> 'M'.code
            TerminalKey.NUMPAD_0 -> 'p'.code
            TerminalKey.NUMPAD_1 -> 'q'.code
            TerminalKey.NUMPAD_2 -> 'r'.code
            TerminalKey.NUMPAD_3 -> 's'.code
            TerminalKey.NUMPAD_4 -> 't'.code
            TerminalKey.NUMPAD_5 -> 'u'.code
            TerminalKey.NUMPAD_6 -> 'v'.code
            TerminalKey.NUMPAD_7 -> 'w'.code
            TerminalKey.NUMPAD_8 -> 'x'.code
            TerminalKey.NUMPAD_9 -> 'y'.code
            TerminalKey.NUMPAD_DECIMAL -> 'n'.code
            TerminalKey.NUMPAD_DIVIDE -> 'o'.code
            TerminalKey.NUMPAD_MULTIPLY -> 'j'.code
            TerminalKey.NUMPAD_SUBTRACT -> 'm'.code
            TerminalKey.NUMPAD_ADD -> 'k'.code
            TerminalKey.NUMPAD_COMMA,
            TerminalKey.NUMPAD_SEPARATOR,
            -> 'l'.code
            TerminalKey.NUMPAD_EQUALS -> 'X'.code
            else -> -1
        }

    private fun normalKeypadAscii(key: TerminalKey): Int =
        when (key) {
            TerminalKey.NUMPAD_SPACE -> ' '.code
            TerminalKey.NUMPAD_TAB -> ControlCode.HT
            TerminalKey.NUMPAD_0 -> '0'.code
            TerminalKey.NUMPAD_1 -> '1'.code
            TerminalKey.NUMPAD_2 -> '2'.code
            TerminalKey.NUMPAD_3 -> '3'.code
            TerminalKey.NUMPAD_4 -> '4'.code
            TerminalKey.NUMPAD_5 -> '5'.code
            TerminalKey.NUMPAD_6 -> '6'.code
            TerminalKey.NUMPAD_7 -> '7'.code
            TerminalKey.NUMPAD_8 -> '8'.code
            TerminalKey.NUMPAD_9 -> '9'.code
            TerminalKey.NUMPAD_DECIMAL -> '.'.code
            TerminalKey.NUMPAD_DIVIDE -> '/'.code
            TerminalKey.NUMPAD_MULTIPLY -> '*'.code
            TerminalKey.NUMPAD_SUBTRACT -> '-'.code
            TerminalKey.NUMPAD_ADD -> '+'.code
            TerminalKey.NUMPAD_COMMA,
            TerminalKey.NUMPAD_SEPARATOR,
            -> ','.code
            TerminalKey.NUMPAD_EQUALS -> '='.code
            TerminalKey.NUMPAD_BEGIN -> '5'.code
            else -> -1
        }

    private fun controlCodeFor(codepoint: Int): Int {
        val lower =
            when (codepoint) {
                in 'A'.code..'Z'.code -> codepoint + 32
                else -> codepoint
            }

        return when (lower) {
            in 'a'.code..'z'.code -> lower - 'a'.code + 1
            '@'.code, ' '.code -> 0x00
            '['.code -> 0x1b
            '\\'.code -> 0x1c
            ']'.code -> 0x1d
            '^'.code -> 0x1e
            '_'.code -> 0x1f
            '?'.code -> 0x7f
            else -> -1
        }
    }

    private fun cursorSequence(finalByte: Int): ByteArray =
        when (finalByte) {
            'A'.code -> TerminalSequences.CURSOR_UP_NORMAL
            'B'.code -> TerminalSequences.CURSOR_DOWN_NORMAL
            'C'.code -> TerminalSequences.CURSOR_RIGHT_NORMAL
            'D'.code -> TerminalSequences.CURSOR_LEFT_NORMAL
            else -> error("unsupported cursor final: $finalByte")
        }

    private fun applicationCursorSequence(finalByte: Int): ByteArray =
        when (finalByte) {
            'A'.code -> TerminalSequences.CURSOR_UP_APPLICATION
            'B'.code -> TerminalSequences.CURSOR_DOWN_APPLICATION
            'C'.code -> TerminalSequences.CURSOR_RIGHT_APPLICATION
            'D'.code -> TerminalSequences.CURSOR_LEFT_APPLICATION
            else -> error("unsupported cursor final: $finalByte")
        }

    private fun homeEndSequence(
        finalByte: Int,
        application: Boolean,
    ): ByteArray =
        when (finalByte) {
            'H'.code -> if (application) TerminalSequences.HOME_APPLICATION else TerminalSequences.HOME_NORMAL
            'F'.code -> if (application) TerminalSequences.END_APPLICATION else TerminalSequences.END_NORMAL
            else -> error("unsupported home/end final: $finalByte")
        }

    private fun functionSs3Sequence(finalByte: Int): ByteArray =
        when (finalByte) {
            'P'.code -> TerminalSequences.F1
            'Q'.code -> TerminalSequences.F2
            'R'.code -> TerminalSequences.F3
            'S'.code -> TerminalSequences.F4
            else -> error("unsupported function final: $finalByte")
        }

    private fun tildeSequence(number: Int): ByteArray =
        when (number) {
            2 -> TerminalSequences.INSERT
            3 -> TerminalSequences.DELETE
            5 -> TerminalSequences.PAGE_UP
            6 -> TerminalSequences.PAGE_DOWN
            15 -> TerminalSequences.F5
            17 -> TerminalSequences.F6
            18 -> TerminalSequences.F7
            19 -> TerminalSequences.F8
            20 -> TerminalSequences.F9
            21 -> TerminalSequences.F10
            23 -> TerminalSequences.F11
            24 -> TerminalSequences.F12
            else -> error("unsupported tilde key number: $number")
        }

    private fun writeSs3(finalByte: Int) {
        scratch.clear()
        scratch.appendByte(ControlCode.ESC)
        scratch.appendByte('O'.code)
        scratch.appendByte(finalByte)
        scratch.writeTo(output)
    }

    private fun writeCsiFinal(finalByte: Int) {
        scratch.clear()
        scratch.appendByte(ControlCode.ESC)
        scratch.appendByte('['.code)
        scratch.appendByte(finalByte)
        scratch.writeTo(output)
    }

    private fun writeUtf8Codepoint(codepoint: Int) {
        when {
            codepoint <= 0x7f -> output.writeByte(codepoint)

            codepoint <= 0x7ff -> {
                scratch.clear()
                scratch.appendByte(0xc0 or (codepoint shr 6))
                scratch.appendByte(0x80 or (codepoint and 0x3f))
                scratch.writeTo(output)
            }

            codepoint <= 0xffff -> {
                scratch.clear()
                scratch.appendByte(0xe0 or (codepoint shr 12))
                scratch.appendByte(0x80 or ((codepoint shr 6) and 0x3f))
                scratch.appendByte(0x80 or (codepoint and 0x3f))
                scratch.writeTo(output)
            }

            else -> {
                scratch.clear()
                scratch.appendByte(0xf0 or (codepoint shr 18))
                scratch.appendByte(0x80 or ((codepoint shr 12) and 0x3f))
                scratch.appendByte(0x80 or ((codepoint shr 6) and 0x3f))
                scratch.appendByte(0x80 or (codepoint and 0x3f))
                scratch.writeTo(output)
            }
        }
    }

    private fun writeStatic(bytes: ByteArray) {
        output.writeBytes(bytes, 0, bytes.size)
    }

    private companion object {
        private const val BS: Int = 0x08
        private const val BACKSPACE_CODEPOINT: Int = 0x08
        private const val ESCAPE_CODEPOINT: Int = 0x1b
        private const val ENTER_CODEPOINT: Int = 0x0d
        private const val MODIFY_OTHER_KEYS_PREFIX: Int = 27
        private const val TAB_CODEPOINT: Int = 0x09
    }
}
