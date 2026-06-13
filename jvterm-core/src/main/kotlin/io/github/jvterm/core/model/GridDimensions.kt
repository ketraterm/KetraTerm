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
package io.github.jvterm.core.model

/**
 * The strict mathematical boundary of the terminal.
 * Provides pure validation and clamping logic for the Engine.
 */
internal class GridDimensions(
    var width: Int,
    var height: Int,
) {
    init {
        require(width > 0) { "Terminal width must be strictly positive, got $width" }
        require(height > 0) { "Terminal height must be strictly positive, got $height" }
    }

    /**
     * Checks if a column index is within the terminal's strict bounds.
     * @param col The column index to check
     * @return true if the column index is within the terminal's bounds, false otherwise
     */
    fun isValidCol(col: Int): Boolean = col in 0 until width

    /**
     * Checks if a row index is within the terminal's strict bounds.
     * @param row The row index to check
     * @return true if the row index is within the terminal's bounds, false otherwise
     */
    fun isValidRow(row: Int): Boolean = row in 0 until height

    /**
     * Safely restricts a column index to the terminal's bounds.
     * @param col The column index to clamp
     * @return The clamped column index within the terminal's bounds
     */
    fun clampCol(col: Int): Int = col.coerceIn(0, width - 1)

    /**
     * Safely restricts a row index to the terminal's bounds.
     * @param row The row index to clamp
     * @return The clamped row index within the terminal's bounds
     */
    fun clampRow(row: Int): Int = row.coerceIn(0, height - 1)
}
