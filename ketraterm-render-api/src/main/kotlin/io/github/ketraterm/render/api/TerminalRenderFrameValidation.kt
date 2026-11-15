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
package io.github.ketraterm.render.api

/**
 * Validates a borrowed frame's dimensions and retained-row geometry without copying cells.
 * The provider must keep the frame stable throughout validation and subsequent use.
 *
 * @throws IllegalArgumentException for nonpositive dimensions, negative history or
 * discarded counts, scrollback outside retained history, or an absolute row range
 * that cannot be represented by [Long].
 */
public fun TerminalRenderFrame.requireValidGeometry() {
    val columns = columns
    val rows = rows
    val history = historySize
    val offset = scrollbackOffset
    val discarded = discardedCount
    require(columns > 0) { "frame columns must be > 0, was $columns" }
    require(rows > 0) { "frame rows must be > 0, was $rows" }
    require(history >= 0) { "frame historySize must be >= 0, was $history" }
    require(offset in 0..history) { "frame scrollbackOffset must be within retained history" }
    require(discarded >= 0L) { "frame discardedCount must be >= 0, was $discarded" }
    require(discarded <= Long.MAX_VALUE - history) { "frame live row origin overflows Long" }
    val top = discarded + history - offset
    require(top <= Long.MAX_VALUE - (rows - 1L)) { "frame absolute row range overflows Long" }
}
