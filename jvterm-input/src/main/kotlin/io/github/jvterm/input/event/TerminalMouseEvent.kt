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
package io.github.jvterm.input.event

/**
 * One platform-neutral mouse event accepted by the terminal input encoder.
 *
 * Coordinates are zero-based grid cell coordinates. The encoder converts them
 * to the terminal protocol's one-based coordinates at the wire boundary.
 *
 * @property column zero-based cell column.
 * @property row zero-based cell row.
 * @property button button or wheel direction associated with this event.
 * @property type mouse event family.
 * @property modifiers active keyboard modifiers using [TerminalModifiers] bits.
 * @property pixelX optional pixel-level column coordinate (zero-based).
 * @property pixelY optional pixel-level row coordinate (zero-based).
 */
data class TerminalMouseEvent(
    val column: Int,
    val row: Int,
    val button: TerminalMouseButton,
    val type: TerminalMouseEventType,
    val modifiers: Int = TerminalModifiers.NONE,
    val pixelX: Int = -1,
    val pixelY: Int = -1,
) {
    init {
        require(column >= 0) { "column must be non-negative: $column" }
        require(row >= 0) { "row must be non-negative: $row" }
        require(pixelX >= -1) { "pixelX must be non-negative or -1: $pixelX" }
        require(pixelY >= -1) { "pixelY must be non-negative or -1: $pixelY" }
        require(TerminalModifiers.isValid(modifiers)) {
            "invalid modifier bitmask: $modifiers"
        }

        when (type) {
            TerminalMouseEventType.PRESS -> {
                require(button != TerminalMouseButton.NONE) {
                    "PRESS requires a concrete button"
                }
                require(!button.isWheel()) {
                    "wheel buttons must use WHEEL event type"
                }
            }

            TerminalMouseEventType.RELEASE -> {
                require(!button.isWheel()) {
                    "wheel buttons must use WHEEL event type"
                }
            }

            TerminalMouseEventType.MOTION -> {
                require(!button.isWheel()) {
                    "wheel buttons must use WHEEL event type"
                }
            }

            TerminalMouseEventType.WHEEL -> {
                require(button.isWheel()) {
                    "WHEEL requires a wheel button"
                }
            }
        }
    }
}
