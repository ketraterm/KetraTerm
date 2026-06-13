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
package io.github.jvterm.render.api

/**
 * Color encoding kind used by [TerminalRenderAttrs].
 */
object TerminalRenderColorKind {
    /**
     * Terminal default color. The associated value must be zero.
     */
    const val DEFAULT: Int = 0

    /**
     * Indexed palette color. The associated value is in `0..255`.
     */
    const val INDEXED: Int = 1

    /**
     * Direct RGB color. The associated value is encoded as `0xRRGGBB`.
     */
    const val RGB: Int = 2
}

internal fun requireColor(
    name: String,
    kind: Int,
    value: Int,
) {
    when (kind) {
        TerminalRenderColorKind.DEFAULT ->
            require(value == 0) {
                "$name default color value must be zero: $value"
            }
        TerminalRenderColorKind.INDEXED ->
            require(value in 0..255) {
                "$name indexed color value out of range: $value"
            }
        TerminalRenderColorKind.RGB ->
            require(value in 0..0xFF_FFFF) {
                "$name RGB color value out of range: $value"
            }
        else -> throw IllegalArgumentException("$name color kind out of range: $kind")
    }
}
