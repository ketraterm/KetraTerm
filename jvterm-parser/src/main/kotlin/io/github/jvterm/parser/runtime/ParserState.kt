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
package io.github.jvterm.parser.runtime

import io.github.jvterm.parser.ansi.AnsiState

/**
 * Physically flat, logically partitioned parser runtime state.
 *
 * Design:
 * - One object to preserve locality on the JVM.
 * - Primitive fields and primitive arrays only.
 * - Logical partitions are enforced by naming, reset functions, and invariants.
 * - No nested state objects in the hot path.
 */
internal class ParserState(
    maxParams: Int = DEFAULT_MAX_PARAMS,
    maxCluster: Int = DEFAULT_MAX_CLUSTER_CODEPOINTS,
    maxPayload: Int = DEFAULT_MAX_PAYLOAD_BYTES,
) {
    init {
        require(maxParams in 1..32) { "maxParams must be in 1..32, got $maxParams" }
        require(maxCluster > 0) { "maxCluster must be positive, got $maxCluster" }
        require(maxPayload > 0) { "maxPayload must be positive, got $maxPayload" }
    }

    // -------------------------------------------------------------------------
    // PARTITION 1: ANSI FSM router state
    // -------------------------------------------------------------------------

    var fsmState: Int = AnsiState.GROUND

    // -------------------------------------------------------------------------
    // PARTITION 2: Sequence accumulator state
    // -------------------------------------------------------------------------
    //
    // Params invariant:
    // - params[0 until paramCount] are valid fields.
    // - params[paramCount until end] are garbage and must never be read.
    // - -1 means an omitted/missing parameter.
    // - currentParamStarted means the active field has been materialized.
    // - O(1) reset is valid because every new field explicitly writes its slot.
    //
    // Colon invariant:
    // - subParameterMask bit i means params[i] was opened by a ':' separator.
    // - Bit 0 is normally zero because the first field has no preceding separator.
    //
    // Overflow:
    // - If paramCount == params.size, incoming param digits/separators are ignored.

    val params: IntArray = IntArray(maxParams)
    var paramCount: Int = 0
    var currentParamStarted: Boolean = false
    var subParameterMask: Int = 0

    // Intermediates invariant:
    // - packed low-to-high into a 32-bit integer.
    // - first byte is bits 0..7, second is bits 8..15, etc.
    // - maximum 4 intermediate bytes.
    // - overflow bytes are silently dropped.

    var intermediates: Int = 0
    var intermediateCount: Int = 0

    // CSI private marker, e.g. '?', '>', '=', '!'.
    // 0 means absent.
    var privateMarker: Int = 0

    // -------------------------------------------------------------------------
    // PARTITION 3: UTF-8 decoder state
    // -------------------------------------------------------------------------

    var utf8State: Int = 0
    var utf8Codepoint: Int = 0

    // -------------------------------------------------------------------------
    // PARTITION 4: Grapheme assembly state
    // -------------------------------------------------------------------------

    val clusterBuffer: IntArray = IntArray(maxCluster)
    var clusterLength: Int = 0
    var clusterEmittedLength: Int = 0

    var prevGraphemeBreakClass: Int = 0
    var prevWasZwj: Boolean = false
    var regionalIndicatorParity: Int = 0

    // Extended pictographic / Emoji ZWJ sequence tracking
    var zwjBeforeExtendedPictographic: Boolean = false
    var lastNonExtendWasExtendedPictographic: Boolean = false

    // -------------------------------------------------------------------------
    // PARTITION 5: Charset designation / shift state
    // -------------------------------------------------------------------------

    val charsets: IntArray = IntArray(4) { CHARSET_ASCII }

    var glSlot: Int = 0
    var grSlot: Int = 2
    var singleShiftSlot: Int = -1

    val savedCharsets: IntArray = IntArray(4) { CHARSET_ASCII }
    var savedGlSlot: Int = 0
    var savedGrSlot: Int = 2
    var isCharsetSaved: Boolean = false

    fun saveCursor() {
        System.arraycopy(charsets, 0, savedCharsets, 0, 4)
        savedGlSlot = glSlot
        savedGrSlot = grSlot
        isCharsetSaved = true
    }

    fun restoreCursor() {
        if (isCharsetSaved) {
            System.arraycopy(savedCharsets, 0, charsets, 0, 4)
            glSlot = savedGlSlot
            grSlot = savedGrSlot
        }
    }

    // -------------------------------------------------------------------------
    // PARTITION 6: OSC/DCS payload scratch state
    // -------------------------------------------------------------------------
    //
    // Payload invariant:
    // - payloadBuffer is parser-owned scratch storage.
    // - payloadLength bytes are valid.
    // - bytes beyond payloadLength are garbage.
    // - overflowed means additional payload bytes were dropped.
    // - payloadCode is OSC command code when parsed, -1 if unknown/unparsed.

    val payloadBuffer: ByteArray = ByteArray(maxPayload)
    var payloadLength: Int = 0
    var payloadCode: Int = -1
    var payloadOverflowed: Boolean = false

    // -------------------------------------------------------------------------
    // O(1) reset helpers
    // -------------------------------------------------------------------------

    fun clearSequenceState() {
        paramCount = 0
        currentParamStarted = false
        subParameterMask = 0
        intermediates = 0
        intermediateCount = 0
        privateMarker = 0
    }

    fun clearPayloadState() {
        payloadLength = 0
        payloadCode = -1
        payloadOverflowed = false
    }

    fun clearActiveClusterAfterFlush() {
        clusterLength = 0
        clusterEmittedLength = 0
        prevGraphemeBreakClass = 0
        prevWasZwj = false
        zwjBeforeExtendedPictographic = false
        lastNonExtendWasExtendedPictographic = false
        regionalIndicatorParity = 0
    }

    fun resetUtf8State() {
        utf8State = 0
        utf8Codepoint = 0
    }

    fun resetCharsetState() {
        charsets[0] = CHARSET_ASCII
        charsets[1] = CHARSET_ASCII
        charsets[2] = CHARSET_ASCII
        charsets[3] = CHARSET_ASCII
        glSlot = 0
        grSlot = 2
        singleShiftSlot = -1
        savedCharsets[0] = CHARSET_ASCII
        savedCharsets[1] = CHARSET_ASCII
        savedCharsets[2] = CHARSET_ASCII
        savedCharsets[3] = CHARSET_ASCII
        savedGlSlot = 0
        savedGrSlot = 2
        isCharsetSaved = false
    }

    fun resetAll() {
        fsmState = AnsiState.GROUND
        clearSequenceState()
        clearPayloadState()
        clearActiveClusterAfterFlush()
        resetUtf8State()
        resetCharsetState()
    }

    companion object {
        const val DEFAULT_MAX_PARAMS: Int = 16
        const val DEFAULT_MAX_CLUSTER_CODEPOINTS: Int = 16
        const val DEFAULT_MAX_PAYLOAD_BYTES: Int = 4096

        const val CHARSET_ASCII: Int = 0
        const val CHARSET_DEC_SPECIAL_GRAPHICS: Int = 1
    }
}
