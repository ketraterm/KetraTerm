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
package io.github.jvterm.transport

/**
 * Validates array bounds checks for buffer offset and length parameters.
 * Marked as inline so that validation compiles directly into call sites, leaving
 * zero runtime method frame overhead.
 *
 * @param offset the starting index in the byte array.
 * @param length the number of bytes to validate.
 */
@Suppress("NOTHING_TO_INLINE")
inline fun ByteArray.checkBounds(
    offset: Int,
    length: Int,
) {
    require(offset >= 0) { "offset must be non-negative, got $offset" }
    require(length >= 0) { "length must be non-negative, got $length" }
    require(offset <= size) { "offset $offset exceeds size $size" }
    require(length <= size - offset) {
        "offset + length exceeds size: offset=$offset length=$length size=$size"
    }
}
