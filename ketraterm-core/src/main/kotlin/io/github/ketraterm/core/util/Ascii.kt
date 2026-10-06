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
package io.github.ketraterm.core.util

/** Checks the entire printable ASCII range before writing and returns its exclusive end. */
internal fun validateAsciiRange(
    bytes: ByteArray,
    offset: Int,
    length: Int,
): Int {
    require(offset >= 0 && length >= 0 && offset <= bytes.size && length <= bytes.size - offset) {
        "ASCII range must be within the input array"
    }
    val end = offset + length
    var index = offset
    while (index < end) {
        require(bytes[index].toInt() in 0x20..0x7E) { "ASCII range must contain only printable bytes" }
        index++
    }
    return end
}
