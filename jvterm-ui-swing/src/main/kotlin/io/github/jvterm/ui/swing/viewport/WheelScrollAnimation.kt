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
package io.github.jvterm.ui.swing.viewport

import kotlin.math.roundToInt

/** Allocation-free primitive state for one row-targeted wheel animation. */
internal class WheelScrollAnimation {
    var isActive: Boolean = false
        private set

    var targetOffset: Double = 0.0
        private set

    private var startOffset: Double = 0.0
    private var startNanos: Long = 0L

    /**
     * Retargets the animation by a whole-row delta.
     *
     * Active animations accumulate from their integer destination. New
     * animations start from the nearest grid row, so wheel input never creates
     * a fractional destination.
     *
     * @return true when the event has an attainable destination or an existing
     * animation is still moving toward the same clamped boundary.
     */
    fun retarget(
        currentOffset: Double,
        deltaRows: Int,
        historySize: Int,
        nowNanos: Long,
    ): Boolean {
        require(currentOffset.isFinite()) { "currentOffset must be finite, was $currentOffset" }
        require(historySize >= 0) { "historySize must be >= 0, was $historySize" }
        if (deltaRows == 0) return false

        val baseTarget = if (isActive) targetOffset else currentOffset.roundToInt().toDouble()
        val nextTarget =
            (baseTarget + deltaRows.toLong())
                .coerceIn(0.0, historySize.toDouble())
        if (nextTarget == targetOffset && isActive) return true
        if (nextTarget == currentOffset) return false

        startOffset = currentOffset
        targetOffset = nextTarget
        startNanos = nowNanos
        isActive = true
        return true
    }

    /** Returns the eased position at [nowNanos], completing exactly on the target row. */
    fun positionAt(nowNanos: Long): Double {
        if (!isActive) return targetOffset
        val elapsed = (nowNanos - startNanos).coerceAtLeast(0L)
        if (elapsed >= DURATION_NANOS) {
            isActive = false
            return targetOffset
        }

        val progress = elapsed.toDouble() / DURATION_NANOS
        val remaining = 1.0 - progress
        val easedProgress = 1.0 - remaining * remaining * remaining
        return startOffset + (targetOffset - startOffset) * easedProgress
    }

    /** Cancels interpolation without changing the viewport position. */
    fun cancel() {
        isActive = false
    }

    private companion object {
        private const val DURATION_NANOS = 100_000_000L
    }
}
