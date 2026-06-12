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
package com.gagik.terminal.input.impl.keyboard

import com.gagik.terminal.input.event.TerminalModifiers
import com.gagik.terminal.input.impl.InputScratchBuffer
import com.gagik.terminal.protocol.ControlCode
import com.gagik.terminal.protocol.host.TerminalHostOutput

/**
 * Shared zero-allocation helper for formatting CSI and SS3 terminal sequences.
 *
 * All sequence-building operations are written directly into a pre-allocated [InputScratchBuffer]
 * to prevent heap allocations on the critical hot path of terminal input handling, and then
 * flushed to the [TerminalHostOutput] byte sink.
 */
internal object CsiWriter {
    /**
     * Writes a simple unmodified CSI sequence of the format: `ESC [ <finalByte>`.
     *
     * Example sequence generated: `\u001b[A` for Cursor Up.
     *
     * @param scratch the reusable buffer to assemble the bytes.
     * @param output the terminal host output stream where the sequence is written.
     * @param finalByte the final ASCII character of the sequence (e.g. 'A', 'B', etc.).
     */
    fun writeCsiLetter(
        scratch: InputScratchBuffer,
        output: TerminalHostOutput,
        finalByte: Int,
    ) {
        scratch.clear()
        scratch.appendByte(ControlCode.ESC)
        scratch.appendByte('['.code)
        scratch.appendByte(finalByte)
        scratch.writeTo(output)
    }

    /**
     * Writes a modified CSI sequence of the format: `ESC [ <prefixNumber> ; <modifierParam> <finalByte>`.
     *
     * Example sequence generated: `\u001b[1;5A` for Ctrl + Up.
     *
     * @param scratch the reusable buffer to assemble the bytes.
     * @param output the terminal host output stream where the sequence is written.
     * @param prefixNumber the initial numeric parameter, typically 1 for modified keys.
     * @param modifiers the bitmask of active modifiers (Shift, Alt, Ctrl, Meta).
     * @param finalByte the final ASCII character terminating the sequence (e.g., 'A', 'B', 'Z').
     */
    fun writeCsiModifierLetter(
        scratch: InputScratchBuffer,
        output: TerminalHostOutput,
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

    /**
     * Writes a CSI tilde sequence of the format: `ESC [ <number> ; <modifierParam> ~` (modifiers optional).
     *
     * If modifiers are empty, writes: `ESC [ <number> ~`.
     *
     * Example sequence generated: `\u001b[3;5~` for Ctrl + Delete.
     *
     * @param scratch the reusable buffer to assemble the bytes.
     * @param output the terminal host output stream where the sequence is written.
     * @param number the numeric parameter representing the function or editing key (e.g., 3 for Delete).
     * @param modifiers the bitmask of active modifiers.
     */
    fun writeCsiTilde(
        scratch: InputScratchBuffer,
        output: TerminalHostOutput,
        number: Int,
        modifiers: Int,
    ) {
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

    /**
     * Writes a CSI u sequence (used in Kitty and modern xterm protocols) of the format:
     * `ESC [ <code> ; <modifierParam> u` (modifiers optional unless [forceModifier] is true).
     *
     * If modifiers are empty and [forceModifier] is false, writes: `ESC [ <code> u`.
     *
     * Example sequence generated: `\u001b[97;5u` for Ctrl + A.
     *
     * @param scratch the reusable buffer to assemble the bytes.
     * @param output the terminal host output stream where the sequence is written.
     * @param code the Unicode codepoint or Kitty functional key code to report.
     * @param modifiers the bitmask of active modifiers.
     * @param forceModifier if true, forces inclusion of the modifier parameter even if modifiers are empty.
     */
    fun writeCsiU(
        scratch: InputScratchBuffer,
        output: TerminalHostOutput,
        code: Int,
        modifiers: Int,
        forceModifier: Boolean = false,
    ) {
        scratch.clear()
        scratch.appendByte(ControlCode.ESC)
        scratch.appendByte('['.code)
        scratch.appendDecimal(code)

        if (modifiers != TerminalModifiers.NONE || forceModifier) {
            scratch.appendByte(';'.code)
            scratch.appendDecimal(TerminalModifiers.toCsiModifierParam(modifiers))
        }

        scratch.appendByte('u'.code)
        scratch.writeTo(output)
    }

    /**
     * Writes a Single Shift Select (SS3) sequence of the format: `ESC O <finalByte>`.
     *
     * Typically used for legacy application keypad keys (e.g. `\u001bOp` for Numpad 0 in application mode).
     *
     * @param scratch the reusable buffer to assemble the bytes.
     * @param output the terminal host output stream where the sequence is written.
     * @param finalByte the final character of the SS3 sequence (e.g., 'p', 'q').
     */
    fun writeSs3(
        scratch: InputScratchBuffer,
        output: TerminalHostOutput,
        finalByte: Int,
    ) {
        scratch.clear()
        scratch.appendByte(ControlCode.ESC)
        scratch.appendByte('O'.code)
        scratch.appendByte(finalByte)
        scratch.writeTo(output)
    }

    /**
     * Encodes a Unicode scalar value into UTF-8 bytes and writes them to the output stream.
     *
     * Standard ASCII characters (<= 0x7f) bypass the scratch buffer and are written directly
     * to save clock cycles. Multi-byte codepoints are encoded into the scratch buffer without allocating arrays.
     *
     * @param scratch the reusable buffer to assemble multi-byte sequences.
     * @param output the terminal host output stream.
     * @param codepoint the Unicode scalar value to encode.
     */
    fun writeUtf8Codepoint(
        scratch: InputScratchBuffer,
        output: TerminalHostOutput,
        codepoint: Int,
    ) {
        when {
            codepoint <= 0x7f -> {
                output.writeByte(codepoint)
            }
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
}
