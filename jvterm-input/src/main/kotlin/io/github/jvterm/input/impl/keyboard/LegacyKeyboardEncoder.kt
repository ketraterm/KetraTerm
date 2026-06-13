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
package io.github.jvterm.input.impl.keyboard

import io.github.jvterm.core.api.TerminalInputState
import io.github.jvterm.input.event.TerminalKey
import io.github.jvterm.input.event.TerminalKeyEvent
import io.github.jvterm.input.event.TerminalModifiers
import io.github.jvterm.input.impl.InputScratchBuffer
import io.github.jvterm.input.impl.TerminalSequences
import io.github.jvterm.input.policy.*
import io.github.jvterm.protocol.ControlCode
import io.github.jvterm.protocol.host.TerminalHostOutput
import io.github.jvterm.protocol.keyboard.FormatOtherKeysMode
import io.github.jvterm.protocol.keyboard.ModifyOtherKeysMode

/**
 * Encoder for legacy xterm-style keyboard sequences, keypad modes, and modifyOtherKeys modes.
 *
 * This class handles standard terminal keyboard encoding for legacy applications that do not
 * support modern progressive keyboard protocols like Kitty. It supports:
 * 1. Default ANSI/DEC cursor and functional key sequences (e.g., standard arrow keys, F1-F12, Page Up/Down).
 * 2. Application Cursor Mode (`DECCKM`) routing.
 * 3. Application Keypad Mode (`DECKPAM`) vs. Normal Keypad Mode (`DECKPNM`).
 * 4. Xterm `modifyOtherKeys` (Modes 1 and 2) and `formatOtherKeys` options to report modified keys
 *    unambiguously using custom CSI parameters or `CSI u` encodings.
 * 5. Configurable keyboard policies such as backspace flavor, meta/alt key escaping, and unsupported key actions.
 *
 * @property output the target byte stream sink where generated escape sequences are written.
 * @property scratch a shared, allocation-free scratch buffer reused to format escape sequences.
 * @property policy configuration settings governing fallback behavior for ambiguous or unsupported keys.
 */
internal class LegacyKeyboardEncoder(
    private val output: TerminalHostOutput,
    private val scratch: InputScratchBuffer,
    private val policy: TerminalInputPolicy,
) {
    /**
     * Encodes a keyboard event under legacy xterm and DEC terminal rules.
     *
     * Processes special key events or raw Unicode codepoints by mapping them to appropriate
     * escape sequences, control bytes, or raw UTF-8 streams based on active terminal modes
     * and keyboard policies.
     *
     * @param event the keyboard event containing key definitions or Unicode codepoints.
     * @param modeBits the active terminal modes pack representing current DEC/ANSI mode state.
     */
    fun encode(
        event: TerminalKeyEvent,
        modeBits: Long,
    ) {
        val key = event.key
        if (key != null) {
            encodeSpecialKey(key, event.modifiers, modeBits)
        } else {
            encodeCodepoint(event.codepoint, event.modifiers, modeBits)
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
                    CsiWriter.writeUtf8Codepoint(scratch, output, codepoint)
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

        CsiWriter.writeUtf8Codepoint(scratch, output, codepoint)
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
            else -> encodeMappedKey(key, modifiers, modeBits)
        }
    }

    private fun encodeMappedKey(
        key: TerminalKey,
        modifiers: Int,
        modeBits: Long,
    ) {
        val keyOrdinal = key.ordinal

        // 0. Static Sequences (Unmodified)
        if (modifiers == TerminalModifiers.NONE) {
            val seq =
                if (TerminalInputState.isApplicationCursorKeys(modeBits)) {
                    KeyMappingTable.STATIC_SEQUENCES_APP[keyOrdinal] ?: KeyMappingTable.STATIC_SEQUENCES[keyOrdinal]
                } else {
                    KeyMappingTable.STATIC_SEQUENCES[keyOrdinal]
                }
            if (seq != null) {
                output.writeBytes(seq, 0, seq.size)
                return
            }
        }

        // 1. Application / Cursor Keys (Arrows, Home, End)
        val isCursorKey =
            key == TerminalKey.UP ||
                key == TerminalKey.DOWN ||
                key == TerminalKey.LEFT ||
                key == TerminalKey.RIGHT ||
                key == TerminalKey.HOME ||
                key == TerminalKey.END
        if (isCursorKey) {
            val csiLetter = KeyMappingTable.CSI_LETTERS[keyOrdinal]
            CsiWriter.writeCsiModifierLetter(scratch, output, 1, modifiers, csiLetter)
            return
        }

        // 2. Function F1-F4
        val csiLetter = KeyMappingTable.CSI_LETTERS[keyOrdinal]
        if (csiLetter >= 0) {
            CsiWriter.writeCsiModifierLetter(scratch, output, 1, modifiers, csiLetter)
            return
        }

        // 3. Tilde Keys (Insert, Delete, PgUp, PgDn, F5-F12, F3)
        val tildeNumber = KeyMappingTable.TILDE_NUMBERS[keyOrdinal]
        if (tildeNumber >= 0) {
            CsiWriter.writeCsiTilde(scratch, output, tildeNumber, modifiers)
            return
        }

        // 4. Keypad Keys
        val hasNormalKeypad = KeyMappingTable.NORMAL_KEYPAD_ASCII[keyOrdinal] >= 0
        val hasAppKeypad = KeyMappingTable.APPLICATION_KEYPAD_FINALS[keyOrdinal] >= 0
        if (hasNormalKeypad || hasAppKeypad) {
            encodeKeypad(key, modifiers, modeBits)
            return
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
                UnsupportedModifiedKeyPolicy.EMIT_UNMODIFIED -> {}
            }
        }

        if (!writeModifierPrefixOrSuppress(modifiers)) return

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

        val unsupportedModifier = TerminalModifiers.hasShift(modifiers) || TerminalModifiers.hasCtrl(modifiers)

        if (unsupportedModifier) {
            when (policy.unsupportedModifiedKeyPolicy) {
                UnsupportedModifiedKeyPolicy.SUPPRESS -> return
                UnsupportedModifiedKeyPolicy.EMIT_UNMODIFIED -> {}
            }
        }

        if (!writeModifierPrefixOrSuppress(modifiers)) return

        if (
            TerminalInputState.isNewLineMode(modeBits) &&
            policy.enterNewLineModePolicy == EnterNewLineModePolicy.SEND_CR_LF
        ) {
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

        val unsupportedModifier = TerminalModifiers.hasShift(modifiers) || TerminalModifiers.hasCtrl(modifiers)

        if (unsupportedModifier && policy.unsupportedModifiedKeyPolicy == UnsupportedModifiedKeyPolicy.SUPPRESS) {
            return
        }

        if (!writeModifierPrefixOrSuppress(modifiers)) return

        output.writeByte(ControlCode.ESC)
    }

    private fun encodeTab(
        modifiers: Int,
        modeBits: Long,
    ) {
        if (shouldEncodeModifyOtherSpecial(TAB_CODEPOINT, modifiers, modeBits)) {
            encodeModifyOtherKey(TAB_CODEPOINT, modifiers, modeBits)
            return
        }

        if (modifiers == TerminalModifiers.NONE) {
            output.writeByte(ControlCode.HT)
            return
        }

        if (modifiers == TerminalModifiers.SHIFT) {
            output.writeBytes(TerminalSequences.BACK_TAB, 0, TerminalSequences.BACK_TAB.size)
            return
        }

        CsiWriter.writeCsiModifierLetter(scratch, output, 1, modifiers, 'Z'.code)
    }

    private fun encodeKeypad(
        key: TerminalKey,
        modifiers: Int,
        modeBits: Long,
    ) {
        val keyOrdinal = key.ordinal

        if (modifiers != TerminalModifiers.NONE) {
            val unsupportedModifier = TerminalModifiers.hasShift(modifiers) || TerminalModifiers.hasCtrl(modifiers)
            if (unsupportedModifier) {
                when (policy.unsupportedModifiedKeyPolicy) {
                    UnsupportedModifiedKeyPolicy.SUPPRESS -> return
                    UnsupportedModifiedKeyPolicy.EMIT_UNMODIFIED -> {}
                }
            }
            if (!writeModifierPrefixOrSuppress(modifiers)) return
        }

        if (TerminalInputState.isApplicationKeypad(modeBits)) {
            if (key == TerminalKey.NUMPAD_BEGIN) {
                CsiWriter.writeCsiLetter(scratch, output, 'E'.code)
                return
            }

            val final = KeyMappingTable.APPLICATION_KEYPAD_FINALS[keyOrdinal]
            if (final >= 0) {
                CsiWriter.writeSs3(scratch, output, final)
            }
            return
        }

        if (key == TerminalKey.NUMPAD_ENTER) {
            encodeEnter(TerminalModifiers.NONE, modeBits)
            return
        }

        val ascii = KeyMappingTable.NORMAL_KEYPAD_ASCII[keyOrdinal]
        if (ascii >= 0) {
            output.writeByte(ascii)
        }
    }

    // --- modifyOtherKeys support ---

    /**
     * Determines whether the given codepoint with modifiers should be encoded using
     * the xterm modifyOtherKeys protocol.
     *
     * In Mode 1, only keys that would otherwise be ambiguous or have no standard control representation
     * (e.g. Ctrl+Shift+A) are encoded under modifyOtherKeys.
     * In Mode 2, any key pressed with any modifier is encoded under modifyOtherKeys.
     * In Mode 3, all keys (including unmodified) are encoded.
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

    /**
     * Determines whether special functional keys (Backspace, Tab, Enter, Escape) should be encoded
     * using the modifyOtherKeys protocol. Special keys have legacy fallback representations, so they are
     * only encoded under modifyOtherKeys if explicitly configured by the mode settings.
     */
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
        if (TerminalModifiers.hasAlt(modifiers) || TerminalModifiers.hasMeta(modifiers)) return true
        if (TerminalModifiers.hasShift(modifiers) && modifiers != TerminalModifiers.SHIFT) return true
        if (TerminalModifiers.hasCtrl(modifiers) && controlCodeFor(codepoint) < 0) return true
        return false
    }

    /**
     * Encodes a key using the modifyOtherKeys protocol format.
     *
     * Depending on formatOtherKeys mode, this outputs either the modern `CSI u` sequence
     * or the legacy xterm format: `ESC [ 27 ; <modifier> ; <codepoint> ~`.
     */
    private fun encodeModifyOtherKey(
        codepoint: Int,
        modifiers: Int,
        modeBits: Long,
    ) {
        if (TerminalInputState.formatOtherKeysMode(modeBits) == FormatOtherKeysMode.CSI_U) {
            CsiWriter.writeCsiU(scratch, output, codepoint, modifiers, forceModifier = true)
        } else {
            // Legacy ESC [ 27 ; mod ; codepoint ~
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
    }

    // --- Modifier & Control Code Helpers ---

    private fun shouldSuppressForMeta(modifiers: Int): Boolean =
        TerminalModifiers.hasMeta(modifiers) &&
            !TerminalModifiers.hasAlt(modifiers) &&
            policy.metaKeyPolicy == MetaKeyPolicy.SUPPRESS_EVENT

    private fun shouldPrefixEscape(modifiers: Int): Boolean {
        if (policy.altSendsEscapePrefix && TerminalModifiers.hasAlt(modifiers)) return true
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
        if (shouldSuppressForMeta(modifiers)) return false
        if (shouldPrefixEscape(modifiers)) output.writeByte(ControlCode.ESC)
        return true
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

    private companion object {
        private const val BS: Int = 0x08
        private const val BACKSPACE_CODEPOINT: Int = 0x08
        private const val ESCAPE_CODEPOINT: Int = 0x1b
        private const val ENTER_CODEPOINT: Int = 0x0d
        private const val MODIFY_OTHER_KEYS_PREFIX: Int = 27
        private const val TAB_CODEPOINT: Int = 0x09
    }
}
