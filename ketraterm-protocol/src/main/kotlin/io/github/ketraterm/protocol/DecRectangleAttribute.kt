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
package io.github.ketraterm.protocol

/**
 * Packed visual-attribute selection bits used by DEC rectangular attribute operations.
 *
 * These bits represent the VT420 subset shared by DECCARA and DECRARA: bold, underline,
 * blink, and negative image. They deliberately exclude color, protection, hyperlinks, and
 * modern SGR extensions because the DEC controls do not modify those properties.
 */
object DecRectangleAttribute {
    /** Selects bold (SGR `1`). */
    const val BOLD: Int = 1

    /** Selects underline (SGR `4`). */
    const val UNDERLINE: Int = 1 shl 1

    /** Selects blink (SGR `5`). */
    const val BLINK: Int = 1 shl 2

    /** Selects negative image / inverse video (SGR `7`). */
    const val INVERSE: Int = 1 shl 3

    /** Selects every VT420 visual attribute handled by these operations. */
    const val ALL: Int = BOLD or UNDERLINE or BLINK or INVERSE
}
