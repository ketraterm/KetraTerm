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
import io.github.jvterm.input.policy.BackspacePolicy
import io.github.jvterm.input.policy.EnterNewLineModePolicy
import io.github.jvterm.input.policy.MetaKeyPolicy
import io.github.jvterm.input.policy.TerminalInputPolicy
import io.github.jvterm.protocol.ControlCode
import io.github.jvterm.protocol.host.TerminalHostOutput
import io.github.jvterm.protocol.keyboard.KittyKeyboardFunctionalKeyCode
import io.github.jvterm.protocol.keyboard.KittyKeyboardProgressiveFlag

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
    private val policy: TerminalInputPolicy,
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
        val isReportAll = (kittyFlags and KittyKeyboardProgressiveFlag.REPORT_ALL_KEYS_AS_ESCAPE_CODES) != 0

        if (key != null) {
            val keyOrdinal = key.ordinal

            // 1. Keypad Keys (mapped to Kitty PUA)
            val puaCode = KeyMappingTable.KITTY_PUA_CODES[keyOrdinal]
            if (puaCode >= 0) {
                CsiWriter.writeCsiU(scratch, output, puaCode, modifiers)
                return
            }

            // 2. Core Functional Keys (Enter, Tab, Escape, Backspace)
            when (key) {
                TerminalKey.ENTER -> {
                    if (isDisambiguate || isReportAll || modifiers != TerminalModifiers.NONE) {
                        CsiWriter.writeCsiU(scratch, output, KittyKeyboardFunctionalKeyCode.ENTER, modifiers)
                    } else {
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
                    return
                }

                TerminalKey.TAB -> {
                    if (isDisambiguate || isReportAll || (modifiers != TerminalModifiers.NONE && modifiers != TerminalModifiers.SHIFT)) {
                        CsiWriter.writeCsiU(scratch, output, KittyKeyboardFunctionalKeyCode.TAB, modifiers)
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
                    if (isDisambiguate || isReportAll || modifiers != TerminalModifiers.NONE) {
                        CsiWriter.writeCsiU(scratch, output, KittyKeyboardFunctionalKeyCode.ESCAPE, modifiers)
                    } else {
                        output.writeByte(ControlCode.ESC)
                    }
                    return
                }

                TerminalKey.BACKSPACE -> {
                    if (isDisambiguate || isReportAll || modifiers != TerminalModifiers.NONE) {
                        CsiWriter.writeCsiU(scratch, output, KittyKeyboardFunctionalKeyCode.BACKSPACE, modifiers)
                    } else {
                        val baseByte =
                            when (policy.backspacePolicy) {
                                BackspacePolicy.DELETE -> ControlCode.DEL
                                BackspacePolicy.BACKSPACE -> BS
                            }
                        output.writeByte(baseByte)
                    }
                    return
                }
                else -> {}
            }

            // 3. CSI Letter Keys (Arrows, Home, End, F1-F4)
            val csiLetter = KeyMappingTable.CSI_LETTERS[keyOrdinal]
            if (csiLetter >= 0) {
                if (modifiers != TerminalModifiers.NONE) {
                    CsiWriter.writeCsiModifierLetter(scratch, output, 1, modifiers, csiLetter)
                } else {
                    CsiWriter.writeCsiLetter(scratch, output, csiLetter)
                }
                return
            }

            // 4. Tilde Keys (Insert, Delete, PgUp, PgDn, F5-F12, F3)
            val tildeNumber = KeyMappingTable.TILDE_NUMBERS[keyOrdinal]
            if (tildeNumber >= 0) {
                CsiWriter.writeCsiTilde(scratch, output, tildeNumber, modifiers)
                return
            }
        } else {
            // Printable Codepoints
            val codepoint = event.codepoint
            if (isReportAll || (modifiers != TerminalModifiers.NONE && modifiers != TerminalModifiers.SHIFT)) {
                CsiWriter.writeCsiU(scratch, output, codepoint, modifiers)
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

    private companion object {
        private const val BS: Int = 0x08
    }
}
