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
package io.github.ketraterm.ui.swing.viewport

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Allocation-free accumulator that converts precise device input into whole steps.
 *
 * Fractional input is retained until the signed sum crosses a whole-step boundary.
 * A step may represent a viewport row, an arrow key, or a terminal wheel report.
 */
internal class ScrollDeltaAccumulator {
    private var remainder = 0.0

    /** Adds [deltaSteps] and returns the signed whole-step portion. */
    fun accumulate(deltaSteps: Double): Int {
        require(deltaSteps.isFinite()) { "deltaSteps must be finite, was $deltaSteps" }
        if (deltaSteps == 0.0) return 0

        val total = (remainder + deltaSteps).coerceIn(Int.MIN_VALUE.toDouble(), Int.MAX_VALUE.toDouble())
        val tolerance = Math.ulp(total) * ULP_TOLERANCE
        val adjusted = if (total > 0.0) total + tolerance else total - tolerance
        val wholeSteps = (if (adjusted > 0.0) floor(adjusted) else ceil(adjusted)).toInt()
        remainder = total - wholeSteps
        if (abs(remainder) <= tolerance) remainder = 0.0
        return wholeSteps
    }

    /** Discards retained fractional input. */
    fun reset() {
        remainder = 0.0
    }

    private companion object {
        private const val ULP_TOLERANCE = 4.0
    }
}
