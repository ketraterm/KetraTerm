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

import kotlin.math.roundToInt

/** Allocation-free interpolation state whose destination is always a row. */
internal class SmoothRowScrollAnimation {
    var isActive = false
        private set

    var targetRow = 0
        private set

    private var startOffset = 0.0
    private var startNanos = 0L

    /** Retargets from [currentOffset] by a signed whole-row delta. */
    fun retargetBy(
        currentOffset: Double,
        deltaRows: Int,
        historySize: Int,
        nowNanos: Long,
    ): Boolean {
        require(currentOffset.isFinite()) { "currentOffset must be finite, was $currentOffset" }
        require(historySize >= 0) { "historySize must be >= 0, was $historySize" }
        if (deltaRows == 0) return false

        val baseRow = if (isActive) targetRow else currentOffset.roundToInt().coerceIn(0, historySize)
        val nextRow = (baseRow.toLong() + deltaRows).coerceIn(0L, historySize.toLong()).toInt()
        return retargetTo(currentOffset, nextRow, historySize, nowNanos)
    }

    /** Retargets from [currentOffset] to the clamped integer [targetRow]. */
    fun retargetTo(
        currentOffset: Double,
        targetRow: Int,
        historySize: Int,
        nowNanos: Long,
    ): Boolean {
        require(currentOffset.isFinite()) { "currentOffset must be finite, was $currentOffset" }
        require(historySize >= 0) { "historySize must be >= 0, was $historySize" }

        val nextRow = targetRow.coerceIn(0, historySize)
        if (isActive && nextRow == this.targetRow) return true
        if (nextRow.toDouble() == currentOffset) return false

        startOffset = currentOffset
        this.targetRow = nextRow
        startNanos = nowNanos
        isActive = true
        return true
    }

    /** Returns the eased visual offset, ending exactly on [targetRow]. */
    fun positionAt(nowNanos: Long): Double {
        if (!isActive) return targetRow.toDouble()
        val elapsed = (nowNanos - startNanos).coerceAtLeast(0L)
        if (elapsed >= DURATION_NANOS) {
            isActive = false
            return targetRow.toDouble()
        }

        val progress = elapsed.toDouble() / DURATION_NANOS
        val remaining = 1.0 - progress
        val easedProgress = 1.0 - remaining * remaining * remaining
        return startOffset + (targetRow - startOffset) * easedProgress
    }

    /** Cancels interpolation without changing the visual offset. */
    fun cancel() {
        isActive = false
    }

    private companion object {
        private const val DURATION_NANOS = 100_000_000L
    }
}
