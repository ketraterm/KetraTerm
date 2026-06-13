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
package io.github.jvterm.parser.ansi

import io.github.jvterm.parser.ansi.ByteClass.UTF8_PAYLOAD
import io.github.jvterm.protocol.ControlCode

/**
 * Byte-domain ownership rules for the parser.
 *
 * This object is deliberately scoped to the ANSI single-byte control / ASCII domain.
 * It is NOT a universal classifier for all bytes in a UTF-8 terminal stream.
 *
 * Default ingress policy:
 * - 0x00..0x1F: ANSI/C0 control path
 * - 0x20..0x7E: ASCII path (printable in GROUND, structural in ESC/CSI/etc.)
 * - 0x7F: DEL path
 * - 0x80..0xFF: UTF-8 decoder path by default
 *
 * Consequence:
 * - This table is authoritative only for 7-bit control + ASCII handling.
 * Non-ASCII bytes still classify deterministically, but they classify to the top-level
 * ingress-routing class [UTF8_PAYLOAD] rather than an ASCII/control FSM column.
 * - 8-bit C1 control bytes are NOT treated as control by default, because doing so
 *   would steal bytes that normally belong to UTF-8 multibyte sequences.
 * - If the emulator later supports an explicit 8-bit C1 mode, that mode must opt in
 *   before routing 0x80..0x9F through any ANSI control classification.
 */
internal object ByteClass {
    // 0x00..0x1F control / execution classes
    const val EXECUTE: Int = 0 // C0 controls executed immediately
    const val CAN_SUB: Int = 1 // CAN / SUB abort current escape/control sequence
    const val ESC: Int = 2 // ESC introducer

    // 0x20..0x7E ASCII structural / printable classes
    const val INTERMEDIATE: Int = 3 // 0x20..0x2F
    const val PARAM_DIGIT: Int = 4 // 0x30..0x39
    const val COLON: Int = 5 // 0x3A
    const val PARAM_SEP: Int = 6 // 0x3B
    const val PRIVATE_MARKER: Int = 7 // 0x3C..0x3F
    const val DCS_INTRO: Int = 8 // 'P' 0x50
    const val CSI_INTRO: Int = 9 // '[' 0x5B
    const val ST_INTRO: Int = 10 // '\\' 0x5C (used in ESC \\ => ST)
    const val OSC_INTRO: Int = 11 // ']' 0x5D
    const val SOS_PM_APC_INTRO: Int = 12 // 'X', '^', '_'
    const val FINAL_BYTE: Int = 13 // 0x40..0x7E excluding structural introducers
    const val DEL: Int = 14 // 0x7F

    /** Total number of active classes in the ASCII/control domain. */
    const val COUNT: Int = 15

    /**
     * ASCII/control-domain lookup table.
     *
     * Only bytes 0x00..0x7F are semantically classified here.
     * Bytes above 0x7F are intentionally routed as [UTF8_PAYLOAD], because they belong
     * to the UTF-8 path by default.
     */
    val ASCII_MAP: ByteArray =
        ByteArray(128).also { map ->
            // --- C0 controls ----------------------------------------------------
            for (b in ControlCode.NUL..ControlCode.ETB) {
                map[b] = EXECUTE.toByte()
            }
            map[ControlCode.CAN] = CAN_SUB.toByte()
            map[ControlCode.EM] = EXECUTE.toByte()
            map[ControlCode.SUB] = CAN_SUB.toByte()
            map[ControlCode.ESC] = ESC.toByte()
            for (b in ControlCode.FS..ControlCode.US) {
                map[b] = EXECUTE.toByte()
            }

            // --- ASCII structural / printable domain ---------------------------
            for (b in 0x20..0x2F) {
                map[b] = INTERMEDIATE.toByte()
            }
            for (b in 0x30..0x39) {
                map[b] = PARAM_DIGIT.toByte()
            }
            map[0x3A] = COLON.toByte()
            map[0x3B] = PARAM_SEP.toByte()
            for (b in 0x3C..0x3F) {
                map[b] = PRIVATE_MARKER.toByte()
            }
            for (b in 0x40..0x7E) {
                map[b] = FINAL_BYTE.toByte()
            }
            map[ControlCode.DEL] = DEL.toByte()

            // --- Structural introducer overrides -------------------------------
            map['P'.code] = DCS_INTRO.toByte()
            map['X'.code] = SOS_PM_APC_INTRO.toByte()
            map['['.code] = CSI_INTRO.toByte()
            map['\\'.code] = ST_INTRO.toByte()
            map[']'.code] = OSC_INTRO.toByte()
            map['^'.code] = SOS_PM_APC_INTRO.toByte()
            map['_'.code] = SOS_PM_APC_INTRO.toByte()
        }

    /**
     * Sentinel for bytes that are outside the ASCII/control routing domain.
     * In the default parser configuration, callers must send these bytes to the UTF-8 decoder.
     */
    const val UTF8_PAYLOAD: Int = 15

    /** Total number of byte classes routable through the ANSI state matrix. */
    const val ROUTING_COUNT: Int = 16

    /**
     * Returns the byte class for ASCII/control-domain bytes, or [UTF8_PAYLOAD] for 0x80..0xFF.
     */
    @JvmStatic
    fun classify(byteValue: Int): Int {
        require(byteValue in 0..255) { "byteValue out of range: $byteValue" }
        return if (byteValue < 0x80) {
            ASCII_MAP[byteValue].toInt()
        } else {
            UTF8_PAYLOAD
        }
    }

    @JvmStatic
    fun isAsciiDomain(byteValue: Int): Boolean {
        require(byteValue in 0..255) { "byteValue out of range: $byteValue" }
        return byteValue < 0x80
    }

    @JvmStatic
    fun isUtf8DomainByDefault(byteValue: Int): Boolean {
        require(byteValue in 0..255) { "byteValue out of range: $byteValue" }
        return byteValue >= 0x80
    }
}
