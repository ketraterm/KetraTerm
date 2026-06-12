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

/**
 * Packs CSI sequence structural metadata into a stable dispatch key.
 *
 * Layout:
 * - bits 0..7:   final byte
 * - bits 8..15:  CSI private marker, or 0 when absent
 * - bits 16..47: intermediate bytes packed low-to-high
 * - bits 48..51: intermediate count
 */
internal object CsiSignature {
    @JvmStatic
    fun encode(
        finalByte: Int,
        privateMarker: Int,
        intermediates: Int,
        intermediateCount: Int,
    ): Long =
        (finalByte.toLong() and 0xffL) or
            ((privateMarker.toLong() and 0xffL) shl 8) or
            ((intermediates.toLong() and 0xffffffffL) shl 16) or
            ((intermediateCount.toLong() and 0x0fL) shl 48)
}
