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
package io.github.jvterm.parser.utf8

import io.github.jvterm.parser.utf8.Utf8DecodeResult.EMIT
import io.github.jvterm.parser.utf8.Utf8DecodeResult.NONE
import io.github.jvterm.parser.utf8.Utf8DecodeResult.REPROCESS_CURRENT_BYTE
import io.github.jvterm.parser.utf8.Utf8DecodeResult.hasOutput

/**
 * Packed, allocation-free UTF-8 decode result.
 *
 * Encoding:
 * - [EMIT] bit set: low bits contain a Unicode scalar value.
 * - [REPROCESS_CURRENT_BYTE] bit set: caller must feed the same byte into the decoder again.
 * - result == [NONE]: no scalar is ready yet.
 *
 * A codepoint can legitimately be 0, so output must be checked with [hasOutput], not by comparing
 * the packed value with zero.
 */
internal object Utf8DecodeResult {
    const val NONE: Int = 0

    private const val CODEPOINT_MASK: Int = 0x001f_ffff
    private const val EMIT: Int = 1 shl 31
    private const val REPROCESS_CURRENT_BYTE: Int = 1 shl 30

    @JvmStatic
    fun emit(codepoint: Int): Int {
        require(codepoint in 0..0x10ffff) { "invalid codepoint: $codepoint" }
        return EMIT or codepoint
    }

    @JvmStatic
    fun emitAndReprocess(codepoint: Int): Int {
        require(codepoint in 0..0x10ffff) { "invalid codepoint: $codepoint" }
        return EMIT or REPROCESS_CURRENT_BYTE or codepoint
    }

    @JvmStatic
    fun hasOutput(result: Int): Boolean = (result and EMIT) != 0

    @JvmStatic
    fun shouldReprocessCurrentByte(result: Int): Boolean = (result and REPROCESS_CURRENT_BYTE) != 0

    @JvmStatic
    fun codepoint(result: Int): Int = result and CODEPOINT_MASK
}

/**
 * Streaming UTF-8 decoder for terminal host output.
 *
 * Properties:
 * - byte-at-a-time
 * - chunk-safe
 * - allocation-free
 * - rejects overlong encodings
 * - rejects surrogate scalars
 * - rejects values above U+10FFFF
 * - emits U+FFFD for malformed subsequences
 *
 * Malformed-byte policy:
 * - If a pending multibyte sequence receives a non-continuation byte, the decoder emits U+FFFD
 *   and asks the caller to reprocess the current byte. This preserves valid following ASCII,
 *   control, or lead bytes.
 * - If a pending multibyte sequence receives a continuation-range byte that violates the current
 *   lower/upper bound, the byte is consumed as part of the malformed subsequence and U+FFFD is emitted.
 * - Invalid leading bytes are consumed and emit U+FFFD.
 *
 * Replay contract:
 * - [Utf8DecodeResult.shouldReprocessCurrentByte] is emitted only after the decoder resets itself
 *   to the accept state.
 * - Feeding the same byte once more must not produce another reprocess request for the same byte.
 * - Callers should still enforce bounded replay defensively.
 */
internal class Utf8Decoder(
    private val replacementCodepoint: Int = REPLACEMENT_CODEPOINT,
) {
    private var codepoint: Int = 0
    private var continuationNeeded: Int = 0
    private var continuationSeen: Int = 0
    private var lowerBound: Int = 0x80
    private var upperBound: Int = 0xbf

    fun accept(byteValue: Int): Int {
        require(byteValue in 0..255) { "byteValue out of range: $byteValue" }

        if (continuationNeeded == 0) {
            return startOrEmitAscii(byteValue)
        }

        if (byteValue !in lowerBound..upperBound) {
            val shouldReprocess = byteValue !in 0x80..0xbf
            reset()
            return if (shouldReprocess) {
                Utf8DecodeResult.emitAndReprocess(replacementCodepoint)
            } else {
                Utf8DecodeResult.emit(replacementCodepoint)
            }
        }

        codepoint = (codepoint shl 6) or (byteValue and 0x3f)
        continuationSeen++

        // After the first continuation byte, all remaining continuation bytes use the normal range.
        lowerBound = 0x80
        upperBound = 0xbf

        if (continuationSeen != continuationNeeded) {
            return NONE
        }

        val scalar = codepoint
        reset()

        // Leading-byte range constraints already reject overlongs, surrogates, and > U+10FFFF.
        // Keep this final guard as defensive protection against future edits.
        return if (isUnicodeScalar(scalar)) {
            Utf8DecodeResult.emit(scalar)
        } else {
            Utf8DecodeResult.emit(replacementCodepoint)
        }
    }

    fun flushEndOfInput(): Int {
        if (continuationNeeded == 0) {
            return NONE
        }
        reset()
        return Utf8DecodeResult.emit(replacementCodepoint)
    }

    fun hasPendingSequence(): Boolean = continuationNeeded != 0

    fun reset() {
        codepoint = 0
        continuationNeeded = 0
        continuationSeen = 0
        lowerBound = 0x80
        upperBound = 0xbf
    }

    private fun startOrEmitAscii(byteValue: Int): Int =
        when {
            byteValue <= 0x7f -> Utf8DecodeResult.emit(byteValue)

            byteValue in 0xc2..0xdf -> {
                start(
                    prefix = byteValue and 0x1f,
                    needed = 1,
                    lower = 0x80,
                    upper = 0xbf,
                )
                NONE
            }

            byteValue == 0xe0 -> {
                start(0, needed = 2, lower = 0xa0, upper = 0xbf)
                NONE
            }

            byteValue in 0xe1..0xec -> {
                start(byteValue and 0x0f, needed = 2, lower = 0x80, upper = 0xbf)
                NONE
            }

            byteValue == 0xed -> {
                // ED A0..BF would encode surrogate scalars. Reject by constraining first continuation.
                start(byteValue and 0x0f, needed = 2, lower = 0x80, upper = 0x9f)
                NONE
            }

            byteValue in 0xee..0xef -> {
                start(byteValue and 0x0f, needed = 2, lower = 0x80, upper = 0xbf)
                NONE
            }

            byteValue == 0xf0 -> {
                start(0, needed = 3, lower = 0x90, upper = 0xbf)
                NONE
            }

            byteValue in 0xf1..0xf3 -> {
                start(byteValue and 0x07, needed = 3, lower = 0x80, upper = 0xbf)
                NONE
            }

            byteValue == 0xf4 -> {
                // F4 90..BF would exceed U+10FFFF. Reject by constraining first continuation.
                start(byteValue and 0x07, needed = 3, lower = 0x80, upper = 0x8f)
                NONE
            }

            else -> Utf8DecodeResult.emit(replacementCodepoint)
        }

    private fun start(
        prefix: Int,
        needed: Int,
        lower: Int,
        upper: Int,
    ) {
        codepoint = prefix
        continuationNeeded = needed
        continuationSeen = 0
        lowerBound = lower
        upperBound = upper
    }

    private fun isUnicodeScalar(value: Int): Boolean = value in 0..0x10ffff && value !in 0xd800..0xdfff

    companion object {
        const val REPLACEMENT_CODEPOINT: Int = 0xfffd
    }
}
