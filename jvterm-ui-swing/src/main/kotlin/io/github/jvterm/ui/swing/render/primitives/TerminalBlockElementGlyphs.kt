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
package io.github.jvterm.ui.swing.render.primitives

/**
 * Packed block-element metadata.
 */
internal object TerminalBlockElementGlyphs {
    const val SHADE_LIGHT: Int = 1
    const val SHADE_MEDIUM: Int = 2
    const val SHADE_DARK: Int = 3

    const val UPPER_LEFT: Int = 1
    const val UPPER_RIGHT: Int = 1 shl 1
    const val LOWER_LEFT: Int = 1 shl 2
    const val LOWER_RIGHT: Int = 1 shl 3

    fun canPaint(codePoint: Int): Boolean = codePoint in 0x2580..0x259F

    fun quadrantMask(codePoint: Int): Int =
        when (codePoint) {
            0x2596 -> LOWER_LEFT
            0x2597 -> LOWER_RIGHT
            0x2598 -> UPPER_LEFT
            0x2599 -> UPPER_LEFT or LOWER_LEFT or LOWER_RIGHT
            0x259A -> UPPER_LEFT or LOWER_RIGHT
            0x259B -> UPPER_LEFT or UPPER_RIGHT or LOWER_LEFT
            0x259C -> UPPER_LEFT or UPPER_RIGHT or LOWER_RIGHT
            0x259D -> UPPER_RIGHT
            0x259E -> UPPER_RIGHT or LOWER_LEFT
            0x259F -> UPPER_RIGHT or LOWER_LEFT or LOWER_RIGHT
            else -> 0
        }

    fun shadeKind(codePoint: Int): Int =
        when (codePoint) {
            0x2591 -> SHADE_LIGHT
            0x2592 -> SHADE_MEDIUM
            0x2593 -> SHADE_DARK
            else -> 0
        }
}
