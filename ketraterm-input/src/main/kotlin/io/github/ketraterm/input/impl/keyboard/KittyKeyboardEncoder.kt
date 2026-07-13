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
package io.github.ketraterm.input.impl.keyboard

import io.github.ketraterm.core.api.TerminalInputState
import io.github.ketraterm.input.event.TerminalKey
import io.github.ketraterm.input.event.TerminalKeyEvent
import io.github.ketraterm.input.event.TerminalKeyEventType
import io.github.ketraterm.input.event.TerminalModifiers
import io.github.ketraterm.input.impl.InputScratchBuffer
import io.github.ketraterm.input.impl.TerminalSequences
import io.github.ketraterm.input.policy.BackspacePolicy
import io.github.ketraterm.input.policy.EnterNewLineModePolicy
import io.github.ketraterm.input.policy.MetaKeyPolicy
import io.github.ketraterm.input.policy.TerminalInputPolicy
import io.github.ketraterm.protocol.ControlCode
import io.github.ketraterm.protocol.host.TerminalHostOutput
import io.github.ketraterm.protocol.keyboard.KittyKeyboardFunctionalKeyCode
import io.github.ketraterm.protocol.keyboard.KittyKeyboardProgressiveFlag

/**
 * Encoder for the Kitty keyboard protocol.
 *
 * This encoder converts key events and printable codepoints into escape sequences conforming
 * to the Kitty Keyboard Protocol specifications. It is activated when modern progressive
 * keyboard flags are enabled by the host shell/TUI.
 *
 * Features handled by this encoder:
 * 1. Keypad Keys mapping to Kitty Private Use Area (PUA) codes (e.g., [KittyKeyboardFunctionalKeyCode]).
 * 2. Ambiguity resolution for functional keys (Enter, Tab, Escape, Backspace) under
 *    [KittyKeyboardProgressiveFlag.DISAMBIGUATE_ESCAPE_CODES] or [KittyKeyboardProgressiveFlag.REPORT_ALL_KEYS_AS_ESCAPE_CODES].
 * 3. CSI u formatting for printable and special keys with modifiers.
 *
 * @property output the target byte stream sink where generated escape sequences are written.
 * @property scratch a shared, allocation-free scratch buffer reused to format escape sequences.
 * @property policy configuration settings governing fallback behavior for ambiguous or unsupported keys.
 */
internal class KittyKeyboardEncoder(
    private val output: TerminalHostOutput,
    private val scratch: InputScratchBuffer,
    @Volatile internal var policy: TerminalInputPolicy,
) {
    /**
     * Encodes a keyboard event under the Kitty Keyboard Protocol rules.
     *
     * Depending on the active progressive flags, keys are either reported using standard
     * UTF-8 representations, legacy sequences, or formatted as `CSI u` style codes.
     *
     * @param event the keyboard event containing key definitions or Unicode codepoints.
     * @param kittyFlags active progressive flags representing which aspects of the Kitty protocol are enabled.
     * @param modeBits the active terminal modes pack representing current DEC/ANSI and Kitty mode state.
     */
    fun encode(
        event: TerminalKeyEvent,
        kittyFlags: Int,
        modeBits: Long,
    ) {
        val key = event.key
        val modifiers = event.modifiers
        val isDisambiguate = (kittyFlags and KittyKeyboardProgressiveFlag.DISAMBIGUATE_ESCAPE_CODES) != 0
        val isReportEventTypes = (kittyFlags and KittyKeyboardProgressiveFlag.REPORT_EVENT_TYPES) != 0
        val isReportAlternateKeys = (kittyFlags and KittyKeyboardProgressiveFlag.REPORT_ALTERNATE_KEYS) != 0
        val isReportAll = (kittyFlags and KittyKeyboardProgressiveFlag.REPORT_ALL_KEYS_AS_ESCAPE_CODES) != 0
        val isReportAssociatedText = (kittyFlags and KittyKeyboardProgressiveFlag.REPORT_ASSOCIATED_TEXT) != 0
        val eventType = if (isReportEventTypes) event.type.ordinal + 1 else NO_EVENT_TYPE

        if (
            event.codepoint == TerminalKeyEvent.TEXT_ONLY_CODEPOINT &&
            (!isReportAll || !isReportAssociatedText)
        ) {
            return
        }

        if (
            event.type == TerminalKeyEventType.RELEASE &&
            !isReportAll &&
            (event.key == TerminalKey.ENTER || event.key == TerminalKey.TAB || event.key == TerminalKey.BACKSPACE)
        ) {
            return
        }

        if (key != null) {
            val keyOrdinal = key.ordinal

            // 1. Keypad Keys (mapped to Kitty PUA)
            val puaCode = KeyMappingTable.KITTY_PUA_CODES[keyOrdinal]
            if (puaCode >= 0) {
                if (isModifierKey(key) && !isReportAll) return
                CsiWriter.writeCsiU(scratch, output, puaCode, modifiers, eventType = eventType, kittyModifiers = true)
                return
            }

            // 2. Core Functional Keys (Enter, Tab, Escape, Backspace)
            when (key) {
                TerminalKey.ENTER -> {
                    if (isReportAll ||
                        event.type != TerminalKeyEventType.PRESS ||
                        TerminalModifiers.hasMeta(modifiers) ||
                        (!isDisambiguate && modifiers != TerminalModifiers.NONE)
                    ) {
                        CsiWriter.writeCsiU(
                            scratch,
                            output,
                            KittyKeyboardFunctionalKeyCode.ENTER,
                            modifiers,
                            eventType = eventType,
                            kittyModifiers = true,
                        )
                    } else if (isDisambiguate) {
                        writeDisambiguatedEnter(modifiers, modeBits)
                    } else {
                        writeLegacyEnter(modeBits)
                    }
                    return
                }

                TerminalKey.TAB -> {
                    if (isReportAll ||
                        event.type != TerminalKeyEventType.PRESS ||
                        TerminalModifiers.hasMeta(modifiers) ||
                        (!isDisambiguate && modifiers != TerminalModifiers.NONE && modifiers != TerminalModifiers.SHIFT)
                    ) {
                        CsiWriter.writeCsiU(
                            scratch,
                            output,
                            KittyKeyboardFunctionalKeyCode.TAB,
                            modifiers,
                            eventType = eventType,
                            kittyModifiers = true,
                        )
                    } else if (isDisambiguate) {
                        writeDisambiguatedTab(modifiers)
                    } else {
                        if (modifiers == TerminalModifiers.SHIFT) {
                            output.writeBytes(TerminalSequences.BACK_TAB, 0, TerminalSequences.BACK_TAB.size)
                        } else {
                            output.writeByte(ControlCode.HT)
                        }
                    }
                    return
                }

                TerminalKey.ESCAPE -> {
                    if (isDisambiguate || isReportAll || event.type != TerminalKeyEventType.PRESS || modifiers != TerminalModifiers.NONE) {
                        CsiWriter.writeCsiU(
                            scratch,
                            output,
                            KittyKeyboardFunctionalKeyCode.ESCAPE,
                            modifiers,
                            eventType = eventType,
                            kittyModifiers = true,
                        )
                    } else {
                        output.writeByte(ControlCode.ESC)
                    }
                    return
                }

                TerminalKey.BACKSPACE -> {
                    if (isReportAll ||
                        event.type != TerminalKeyEventType.PRESS ||
                        TerminalModifiers.hasMeta(modifiers) ||
                        (!isDisambiguate && modifiers != TerminalModifiers.NONE)
                    ) {
                        CsiWriter.writeCsiU(
                            scratch,
                            output,
                            KittyKeyboardFunctionalKeyCode.BACKSPACE,
                            modifiers,
                            eventType = eventType,
                            kittyModifiers = true,
                        )
                    } else if (isDisambiguate) {
                        writeDisambiguatedBackspace(modifiers)
                    } else {
                        output.writeByte(configuredBackspaceByte())
                    }
                    return
                }
                else -> {}
            }

            // 3. CSI Letter Keys (Arrows, Home, End, F1-F4)
            val csiLetter = KeyMappingTable.CSI_LETTERS[keyOrdinal]
            if (csiLetter >= 0) {
                if (modifiers != TerminalModifiers.NONE || eventType != NO_EVENT_TYPE) {
                    CsiWriter.writeCsiModifierLetter(scratch, output, 1, modifiers, csiLetter, eventType, kittyModifiers = true)
                } else {
                    CsiWriter.writeCsiLetter(scratch, output, csiLetter)
                }
                return
            }

            // 4. Tilde Keys (Insert, Delete, PgUp, PgDn, F5-F12, F3)
            val tildeNumber = KeyMappingTable.TILDE_NUMBERS[keyOrdinal]
            if (tildeNumber >= 0) {
                CsiWriter.writeCsiTilde(scratch, output, tildeNumber, modifiers, eventType, kittyModifiers = true)
                return
            }
        } else {
            // Printable Codepoints
            val codepoint = event.codepoint
            val kittyCodepoint =
                if (event.unshiftedCodepoint != TerminalKeyEvent.NO_CODEPOINT) event.unshiftedCodepoint else codepoint
            val shiftedCodepoint = if (isReportAlternateKeys) event.shiftedCodepoint else TerminalKeyEvent.NO_CODEPOINT
            val baseLayoutCodepoint = if (isReportAlternateKeys) event.baseLayoutCodepoint else TerminalKeyEvent.NO_CODEPOINT
            val associatedText = if (isReportAssociatedText && isReportAll) event.associatedText else null
            if (isReportAll || (modifiers != TerminalModifiers.NONE && modifiers != TerminalModifiers.SHIFT)) {
                CsiWriter.writeCsiU(
                    scratch,
                    output,
                    kittyCodepoint,
                    modifiers,
                    eventType = eventType,
                    kittyModifiers = true,
                    shiftedCodepoint = shiftedCodepoint,
                    baseLayoutCodepoint = baseLayoutCodepoint,
                    associatedText = associatedText,
                )
            } else {
                if (shouldSuppressForMeta(modifiers)) {
                    return
                }
                if (shouldPrefixEscape(modifiers)) {
                    output.writeByte(ControlCode.ESC)
                }
                CsiWriter.writeUtf8Codepoint(scratch, output, codepoint)
            }
        }
    }

    /**
     * Writes the reset-safe legacy Enter representation required while Kitty
     * disambiguate-escape-codes mode is active. Alt retains the conventional
     * escape prefix; Shift and Control do not alter the return bytes.
     */
    private fun writeDisambiguatedEnter(
        modifiers: Int,
        modeBits: Long,
    ) {
        if (TerminalModifiers.hasAlt(modifiers)) {
            output.writeByte(ControlCode.ESC)
        }
        writeLegacyEnter(modeBits)
    }

    /**
     * Writes Enter according to the host's newline-mode policy.
     */
    private fun writeLegacyEnter(modeBits: Long) {
        output.writeByte(ControlCode.CR)
        if (
            TerminalInputState.isNewLineMode(modeBits) &&
            policy.enterNewLineModePolicy == EnterNewLineModePolicy.SEND_CR_LF
        ) {
            output.writeByte(ControlCode.LF)
        }
    }

    /**
     * Writes the reset-safe legacy Tab representation required while Kitty
     * disambiguate-escape-codes mode is active.
     */
    private fun writeDisambiguatedTab(modifiers: Int) {
        if (TerminalModifiers.hasAlt(modifiers)) {
            output.writeByte(ControlCode.ESC)
        }
        if (TerminalModifiers.hasShift(modifiers)) {
            output.writeBytes(TerminalSequences.BACK_TAB, 0, TerminalSequences.BACK_TAB.size)
        } else {
            output.writeByte(ControlCode.HT)
        }
    }

    /**
     * Writes the reset-safe legacy Backspace representation required while Kitty
     * disambiguate-escape-codes mode is active.
     */
    private fun writeDisambiguatedBackspace(modifiers: Int) {
        if (TerminalModifiers.hasAlt(modifiers)) {
            output.writeByte(ControlCode.ESC)
        }
        output.writeByte(backspaceByte(modifiers))
    }

    /**
     * Selects the configured Backspace byte, swapping it for Control+Backspace.
     */
    private fun backspaceByte(modifiers: Int): Int {
        val baseByte = configuredBackspaceByte()
        return if (TerminalModifiers.hasCtrl(modifiers)) invertBackspaceByte(baseByte) else baseByte
    }

    private fun configuredBackspaceByte(): Int =
        when (policy.backspacePolicy) {
            BackspacePolicy.DELETE -> ControlCode.DEL
            BackspacePolicy.BACKSPACE -> BS
        }

    private fun invertBackspaceByte(baseByte: Int): Int = if (baseByte == ControlCode.DEL) BS else ControlCode.DEL

    /**
     * Determines if a key event with a Meta modifier should be suppressed based on the Meta Key Policy.
     *
     * @param modifiers the bitmask of active modifiers.
     * @return true if Meta is active (and Alt is not) and the policy is [MetaKeyPolicy.SUPPRESS_EVENT].
     */
    private fun shouldSuppressForMeta(modifiers: Int): Boolean =
        TerminalModifiers.hasMeta(modifiers) &&
            !TerminalModifiers.hasAlt(modifiers) &&
            policy.metaKeyPolicy == MetaKeyPolicy.SUPPRESS_EVENT

    /**
     * Determines whether the ESC prefix (meta-prefix/alt-prefix) should be written before the character sequence.
     *
     * @param modifiers the bitmask of active modifiers.
     * @return true if Alt or Meta should send an ESC prefix under the configured policy.
     */
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

    private fun isModifierKey(key: TerminalKey): Boolean =
        key.ordinal in TerminalKey.LEFT_SHIFT.ordinal..TerminalKey.ISO_LEVEL5_SHIFT.ordinal

    private companion object {
        private const val BS: Int = 0x08
        private const val NO_EVENT_TYPE: Int = 0
    }
}
