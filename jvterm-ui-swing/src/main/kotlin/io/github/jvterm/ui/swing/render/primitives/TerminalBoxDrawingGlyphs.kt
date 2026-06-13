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
 * Packed, allocation-free box-drawing glyph metadata.
 */
internal object TerminalBoxDrawingGlyphs {
    const val NONE: Int = 0
    const val LIGHT: Int = 1
    const val HEAVY: Int = 2
    const val DOUBLE: Int = 3

    const val LEFT_SHIFT: Int = 0
    const val RIGHT_SHIFT: Int = 2
    const val UP_SHIFT: Int = 4
    const val DOWN_SHIFT: Int = 6
    const val STYLE_MASK: Int = 0x3

    const val DIAGONAL_RISING: Int = 1
    const val DIAGONAL_FALLING: Int = 1 shl 1

    fun canPaint(codePoint: Int): Boolean = codePoint in 0x2500..0x257F

    fun pack(
        left: Int,
        right: Int,
        up: Int,
        down: Int,
    ): Int = left or (right shl RIGHT_SHIFT) or (up shl UP_SHIFT) or (down shl DOWN_SHIFT)

    fun edge(
        packed: Int,
        shift: Int,
    ): Int = packed ushr shift and STYLE_MASK

    fun horizontalDashStyle(codePoint: Int): Int =
        when (codePoint) {
            0x2504, 0x2508, 0x254C -> LIGHT
            0x2505, 0x2509, 0x254D -> HEAVY
            else -> NONE
        }

    fun verticalDashStyle(codePoint: Int): Int =
        when (codePoint) {
            0x2506, 0x250A, 0x254E -> LIGHT
            0x2507, 0x250B, 0x254F -> HEAVY
            else -> NONE
        }

    fun dashCount(codePoint: Int): Int =
        when (codePoint) {
            0x2504, 0x2505, 0x2506, 0x2507 -> 3
            0x2508, 0x2509, 0x250A, 0x250B -> 4
            0x254C, 0x254D, 0x254E, 0x254F -> 2
            else -> 0
        }

    fun diagonalMask(codePoint: Int): Int =
        when (codePoint) {
            0x2571 -> DIAGONAL_RISING
            0x2572 -> DIAGONAL_FALLING
            0x2573 -> DIAGONAL_RISING or DIAGONAL_FALLING
            else -> NONE
        }

    fun roundedFallbackEdges(codePoint: Int): Int =
        when (codePoint) {
            0x256D -> pack(NONE, LIGHT, NONE, LIGHT)
            0x256E -> pack(LIGHT, NONE, NONE, LIGHT)
            0x2570 -> pack(NONE, LIGHT, LIGHT, NONE)
            0x256F -> pack(LIGHT, NONE, LIGHT, NONE)
            else -> NONE
        }

    fun edges(codePoint: Int): Int =
        when (codePoint) {
            0x2500 -> pack(LIGHT, LIGHT, NONE, NONE)
            0x2501 -> pack(HEAVY, HEAVY, NONE, NONE)
            0x2502 -> pack(NONE, NONE, LIGHT, LIGHT)
            0x2503 -> pack(NONE, NONE, HEAVY, HEAVY)
            0x250C -> pack(NONE, LIGHT, NONE, LIGHT)
            0x250D -> pack(NONE, HEAVY, NONE, LIGHT)
            0x250E -> pack(NONE, LIGHT, NONE, HEAVY)
            0x250F -> pack(NONE, HEAVY, NONE, HEAVY)
            0x2510 -> pack(LIGHT, NONE, NONE, LIGHT)
            0x2511 -> pack(HEAVY, NONE, NONE, LIGHT)
            0x2512 -> pack(LIGHT, NONE, NONE, HEAVY)
            0x2513 -> pack(HEAVY, NONE, NONE, HEAVY)
            0x2514 -> pack(NONE, LIGHT, LIGHT, NONE)
            0x2515 -> pack(NONE, HEAVY, LIGHT, NONE)
            0x2516 -> pack(NONE, LIGHT, HEAVY, NONE)
            0x2517 -> pack(NONE, HEAVY, HEAVY, NONE)
            0x2518 -> pack(LIGHT, NONE, LIGHT, NONE)
            0x2519 -> pack(HEAVY, NONE, LIGHT, NONE)
            0x251A -> pack(LIGHT, NONE, HEAVY, NONE)
            0x251B -> pack(HEAVY, NONE, HEAVY, NONE)
            0x251C -> pack(NONE, LIGHT, LIGHT, LIGHT)
            0x251D -> pack(NONE, HEAVY, LIGHT, LIGHT)
            0x251E -> pack(NONE, LIGHT, HEAVY, LIGHT)
            0x251F -> pack(NONE, LIGHT, LIGHT, HEAVY)
            0x2520 -> pack(NONE, LIGHT, HEAVY, HEAVY)
            0x2521 -> pack(NONE, HEAVY, HEAVY, LIGHT)
            0x2522 -> pack(NONE, HEAVY, LIGHT, HEAVY)
            0x2523 -> pack(NONE, HEAVY, HEAVY, HEAVY)
            0x2524 -> pack(LIGHT, NONE, LIGHT, LIGHT)
            0x2525 -> pack(HEAVY, NONE, LIGHT, LIGHT)
            0x2526 -> pack(LIGHT, NONE, HEAVY, LIGHT)
            0x2527 -> pack(LIGHT, NONE, LIGHT, HEAVY)
            0x2528 -> pack(LIGHT, NONE, HEAVY, HEAVY)
            0x2529 -> pack(HEAVY, NONE, HEAVY, LIGHT)
            0x252A -> pack(HEAVY, NONE, LIGHT, HEAVY)
            0x252B -> pack(HEAVY, NONE, HEAVY, HEAVY)
            0x252C -> pack(LIGHT, LIGHT, NONE, LIGHT)
            0x252D -> pack(HEAVY, LIGHT, NONE, LIGHT)
            0x252E -> pack(LIGHT, HEAVY, NONE, LIGHT)
            0x252F -> pack(HEAVY, HEAVY, NONE, LIGHT)
            0x2530 -> pack(LIGHT, LIGHT, NONE, HEAVY)
            0x2531 -> pack(HEAVY, LIGHT, NONE, HEAVY)
            0x2532 -> pack(LIGHT, HEAVY, NONE, HEAVY)
            0x2533 -> pack(HEAVY, HEAVY, NONE, HEAVY)
            0x2534 -> pack(LIGHT, LIGHT, LIGHT, NONE)
            0x2535 -> pack(HEAVY, LIGHT, LIGHT, NONE)
            0x2536 -> pack(LIGHT, HEAVY, LIGHT, NONE)
            0x2537 -> pack(HEAVY, HEAVY, LIGHT, NONE)
            0x2538 -> pack(LIGHT, LIGHT, HEAVY, NONE)
            0x2539 -> pack(HEAVY, LIGHT, HEAVY, NONE)
            0x253A -> pack(LIGHT, HEAVY, HEAVY, NONE)
            0x253B -> pack(HEAVY, HEAVY, HEAVY, NONE)
            0x253C -> pack(LIGHT, LIGHT, LIGHT, LIGHT)
            0x253D -> pack(HEAVY, LIGHT, LIGHT, LIGHT)
            0x253E -> pack(LIGHT, HEAVY, LIGHT, LIGHT)
            0x253F -> pack(HEAVY, HEAVY, LIGHT, LIGHT)
            0x2540 -> pack(LIGHT, LIGHT, HEAVY, LIGHT)
            0x2541 -> pack(LIGHT, LIGHT, LIGHT, HEAVY)
            0x2542 -> pack(LIGHT, LIGHT, HEAVY, HEAVY)
            0x2543 -> pack(HEAVY, LIGHT, HEAVY, LIGHT)
            0x2544 -> pack(LIGHT, HEAVY, HEAVY, LIGHT)
            0x2545 -> pack(HEAVY, LIGHT, LIGHT, HEAVY)
            0x2546 -> pack(LIGHT, HEAVY, LIGHT, HEAVY)
            0x2547 -> pack(HEAVY, HEAVY, HEAVY, LIGHT)
            0x2548 -> pack(HEAVY, HEAVY, LIGHT, HEAVY)
            0x2549 -> pack(HEAVY, LIGHT, HEAVY, HEAVY)
            0x254A -> pack(LIGHT, HEAVY, HEAVY, HEAVY)
            0x254B -> pack(HEAVY, HEAVY, HEAVY, HEAVY)
            0x2550 -> pack(DOUBLE, DOUBLE, NONE, NONE)
            0x2551 -> pack(NONE, NONE, DOUBLE, DOUBLE)
            0x2552 -> pack(NONE, DOUBLE, NONE, LIGHT)
            0x2553 -> pack(NONE, LIGHT, NONE, DOUBLE)
            0x2554 -> pack(NONE, DOUBLE, NONE, DOUBLE)
            0x2555 -> pack(DOUBLE, NONE, NONE, LIGHT)
            0x2556 -> pack(LIGHT, NONE, NONE, DOUBLE)
            0x2557 -> pack(DOUBLE, NONE, NONE, DOUBLE)
            0x2558 -> pack(NONE, DOUBLE, LIGHT, NONE)
            0x2559 -> pack(NONE, LIGHT, DOUBLE, NONE)
            0x255A -> pack(NONE, DOUBLE, DOUBLE, NONE)
            0x255B -> pack(DOUBLE, NONE, LIGHT, NONE)
            0x255C -> pack(LIGHT, NONE, DOUBLE, NONE)
            0x255D -> pack(DOUBLE, NONE, DOUBLE, NONE)
            0x255E -> pack(NONE, DOUBLE, LIGHT, LIGHT)
            0x255F -> pack(NONE, LIGHT, DOUBLE, DOUBLE)
            0x2560 -> pack(NONE, DOUBLE, DOUBLE, DOUBLE)
            0x2561 -> pack(DOUBLE, NONE, LIGHT, LIGHT)
            0x2562 -> pack(LIGHT, NONE, DOUBLE, DOUBLE)
            0x2563 -> pack(DOUBLE, NONE, DOUBLE, DOUBLE)
            0x2564 -> pack(DOUBLE, DOUBLE, NONE, LIGHT)
            0x2565 -> pack(LIGHT, LIGHT, NONE, DOUBLE)
            0x2566 -> pack(DOUBLE, DOUBLE, NONE, DOUBLE)
            0x2567 -> pack(DOUBLE, DOUBLE, LIGHT, NONE)
            0x2568 -> pack(LIGHT, LIGHT, DOUBLE, NONE)
            0x2569 -> pack(DOUBLE, DOUBLE, DOUBLE, NONE)
            0x256A -> pack(DOUBLE, DOUBLE, LIGHT, LIGHT)
            0x256B -> pack(LIGHT, LIGHT, DOUBLE, DOUBLE)
            0x256C -> pack(DOUBLE, DOUBLE, DOUBLE, DOUBLE)
            0x2574 -> pack(LIGHT, NONE, NONE, NONE)
            0x2575 -> pack(NONE, NONE, LIGHT, NONE)
            0x2576 -> pack(NONE, LIGHT, NONE, NONE)
            0x2577 -> pack(NONE, NONE, NONE, LIGHT)
            0x2578 -> pack(HEAVY, NONE, NONE, NONE)
            0x2579 -> pack(NONE, NONE, HEAVY, NONE)
            0x257A -> pack(NONE, HEAVY, NONE, NONE)
            0x257B -> pack(NONE, NONE, NONE, HEAVY)
            0x257C -> pack(HEAVY, LIGHT, NONE, NONE)
            0x257D -> pack(NONE, NONE, HEAVY, LIGHT)
            0x257E -> pack(LIGHT, HEAVY, NONE, NONE)
            0x257F -> pack(NONE, NONE, LIGHT, HEAVY)
            else -> NONE
        }
}
