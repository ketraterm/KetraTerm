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
package io.github.jvterm.protocol

/**
 * ANSI/ECMA-48 C0, DEL, and C1 control-code byte values.
 *
 * These constants are terminal wire vocabulary only. Parsing, state transitions,
 * input encoding, and response generation belong to their owning modules.
 */
object ControlCode {
    const val NUL: Int = 0x00
    const val SOH: Int = 0x01
    const val STX: Int = 0x02
    const val ETX: Int = 0x03
    const val EOT: Int = 0x04
    const val ENQ: Int = 0x05
    const val ACK: Int = 0x06
    const val BEL: Int = 0x07
    const val BS: Int = 0x08
    const val HT: Int = 0x09
    const val LF: Int = 0x0A
    const val VT: Int = 0x0B
    const val FF: Int = 0x0C
    const val CR: Int = 0x0D
    const val SO: Int = 0x0E
    const val SI: Int = 0x0F
    const val DLE: Int = 0x10
    const val DC1: Int = 0x11
    const val DC2: Int = 0x12
    const val DC3: Int = 0x13
    const val DC4: Int = 0x14
    const val NAK: Int = 0x15
    const val SYN: Int = 0x16
    const val ETB: Int = 0x17
    const val CAN: Int = 0x18
    const val EM: Int = 0x19
    const val SUB: Int = 0x1A
    const val ESC: Int = 0x1B
    const val FS: Int = 0x1C
    const val GS: Int = 0x1D
    const val RS: Int = 0x1E
    const val US: Int = 0x1F
    const val DEL: Int = 0x7F

    const val PAD: Int = 0x80
    const val HOP: Int = 0x81
    const val BPH: Int = 0x82
    const val NBH: Int = 0x83
    const val IND: Int = 0x84
    const val NEL: Int = 0x85
    const val SSA: Int = 0x86
    const val ESA: Int = 0x87
    const val HTS: Int = 0x88
    const val HTJ: Int = 0x89
    const val VTS: Int = 0x8A
    const val PLD: Int = 0x8B
    const val PLU: Int = 0x8C
    const val RI: Int = 0x8D
    const val SS2: Int = 0x8E
    const val SS3: Int = 0x8F
    const val DCS: Int = 0x90
    const val PU1: Int = 0x91
    const val PU2: Int = 0x92
    const val STS: Int = 0x93
    const val CCH: Int = 0x94
    const val MW: Int = 0x95
    const val SPA: Int = 0x96
    const val EPA: Int = 0x97
    const val SOS: Int = 0x98
    const val SGCI: Int = 0x99
    const val SCI: Int = 0x9A
    const val CSI: Int = 0x9B
    const val ST: Int = 0x9C
    const val OSC: Int = 0x9D
    const val PM: Int = 0x9E
    const val APC: Int = 0x9F
}
