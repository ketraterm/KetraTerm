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
    /** Null (NUL). */
    const val NUL: Int = 0x00

    /** Start of Heading (SOH). */
    const val SOH: Int = 0x01

    /** Start of Text (STX). */
    const val STX: Int = 0x02

    /** End of Text (ETX). */
    const val ETX: Int = 0x03

    /** End of Transmission (EOT). */
    const val EOT: Int = 0x04

    /** Enquiry (ENQ). */
    const val ENQ: Int = 0x05

    /** Acknowledge (ACK). */
    const val ACK: Int = 0x06

    /** Bell (BEL). */
    const val BEL: Int = 0x07

    /** Backspace (BS). */
    const val BS: Int = 0x08

    /** Horizontal Tab (HT). */
    const val HT: Int = 0x09

    /** Line Feed (LF). */
    const val LF: Int = 0x0A

    /** Vertical Tab (VT). */
    const val VT: Int = 0x0B

    /** Form Feed (FF). */
    const val FF: Int = 0x0C

    /** Carriage Return (CR). */
    const val CR: Int = 0x0D

    /** Shift Out (SO). */
    const val SO: Int = 0x0E

    /** Shift In (SI). */
    const val SI: Int = 0x0F

    /** Data Link Escape (DLE). */
    const val DLE: Int = 0x10

    /** Device Control 1 / XON (DC1). */
    const val DC1: Int = 0x11

    /** Device Control 2 (DC2). */
    const val DC2: Int = 0x12

    /** Device Control 3 / XOFF (DC3). */
    const val DC3: Int = 0x13

    /** Device Control 4 (DC4). */
    const val DC4: Int = 0x14

    /** Negative Acknowledge (NAK). */
    const val NAK: Int = 0x15

    /** Synchronous Idle (SYN). */
    const val SYN: Int = 0x16

    /** End of Transmission Block (ETB). */
    const val ETB: Int = 0x17

    /** Cancel (CAN). */
    const val CAN: Int = 0x18

    /** End of Medium (EM). */
    const val EM: Int = 0x19

    /** Substitute (SUB). */
    const val SUB: Int = 0x1A

    /** Escape (ESC). */
    const val ESC: Int = 0x1B

    /** File Separator (FS). */
    const val FS: Int = 0x1C

    /** Group Separator (GS). */
    const val GS: Int = 0x1D

    /** Record Separator (RS). */
    const val RS: Int = 0x1E

    /** Unit Separator (US). */
    const val US: Int = 0x1F

    /** Delete (DEL). */
    const val DEL: Int = 0x7F

    /** Padding Character (PAD). */
    const val PAD: Int = 0x80

    /** High Octet Preset (HOP). */
    const val HOP: Int = 0x81

    /** Break Permitted Here (BPH). */
    const val BPH: Int = 0x82

    /** No Break Here (NBH). */
    const val NBH: Int = 0x83

    /** Index (IND). */
    const val IND: Int = 0x84

    /** Next Line (NEL). */
    const val NEL: Int = 0x85

    /** Start of Selected Area (SSA). */
    const val SSA: Int = 0x86

    /** End of Selected Area (ESA). */
    const val ESA: Int = 0x87

    /** Horizontal Tab Set (HTS). */
    const val HTS: Int = 0x88

    /** Horizontal Tab Justified (HTJ). */
    const val HTJ: Int = 0x89

    /** Vertical Tab Set (VTS). */
    const val VTS: Int = 0x8A

    /** Partial Line Down (PLD). */
    const val PLD: Int = 0x8B

    /** Partial Line Up (PLU). */
    const val PLU: Int = 0x8C

    /** Reverse Index (RI). */
    const val RI: Int = 0x8D

    /** Single Shift Two (SS2). */
    const val SS2: Int = 0x8E

    /** Single Shift Three (SS3). */
    const val SS3: Int = 0x8F

    /** Device Control String (DCS). */
    const val DCS: Int = 0x90

    /** Private Use One (PU1). */
    const val PU1: Int = 0x91

    /** Private Use Two (PU2). */
    const val PU2: Int = 0x92

    /** Set Transmit State (STS). */
    const val STS: Int = 0x93

    /** Cancel Character (CCH). */
    const val CCH: Int = 0x94

    /** Message Waiting (MW). */
    const val MW: Int = 0x95

    /** Start of Protected Area (SPA). */
    const val SPA: Int = 0x96

    /** End of Protected Area (EPA). */
    const val EPA: Int = 0x97

    /** Start of String (SOS). */
    const val SOS: Int = 0x98

    /** Single Graphic Character Introducer (SGCI). */
    const val SGCI: Int = 0x99

    /** Single Character Introducer (SCI). */
    const val SCI: Int = 0x9A

    /** Control Sequence Introducer (CSI). */
    const val CSI: Int = 0x9B

    /** String Terminator (ST). */
    const val ST: Int = 0x9C

    /** Operating System Command (OSC). */
    const val OSC: Int = 0x9D

    /** Privacy Message (PM). */
    const val PM: Int = 0x9E

    /** Application Program Command (APC). */
    const val APC: Int = 0x9F
}
