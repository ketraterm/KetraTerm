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

/** Allocation-free primitive state for one terminal viewport animation. */
internal class SmoothScrollAnimation {
    var isActive: Boolean = false
        private set

    var targetOffset: Double = 0.0
        private set

    private var startOffset = 0.0
    private var startNanos = 0L

    /**
     * Retargets the animation by [deltaRows].
     *
     * Relative input accumulates from the active destination. A new gesture
     * starts from the nearest row so whole-row devices always finish aligned.
     */
    fun retargetBy(
        currentOffset: Double,
        deltaRows: Double,
        historySize: Int,
        nowNanos: Long,
    ): Boolean {
        require(currentOffset.isFinite()) { "currentOffset must be finite, was $currentOffset" }
        require(deltaRows.isFinite()) { "deltaRows must be finite, was $deltaRows" }
        require(historySize >= 0) { "historySize must be >= 0, was $historySize" }
        if (deltaRows == 0.0) return false

        val baseTarget = if (isActive) targetOffset else currentOffset.roundToInt().toDouble()
        return retargetTo(currentOffset, baseTarget + deltaRows, historySize, nowNanos)
    }

    /** Retargets the animation to an absolute row offset. */
    fun retargetTo(
        currentOffset: Double,
        targetOffset: Double,
        historySize: Int,
        nowNanos: Long,
    ): Boolean {
        require(currentOffset.isFinite()) { "currentOffset must be finite, was $currentOffset" }
        require(targetOffset.isFinite()) { "targetOffset must be finite, was $targetOffset" }
        require(historySize >= 0) { "historySize must be >= 0, was $historySize" }

        val nextTarget = targetOffset.coerceIn(0.0, historySize.toDouble())
        if (nextTarget == this.targetOffset && isActive) return true
        if (nextTarget == currentOffset) return false

        startOffset = currentOffset
        this.targetOffset = nextTarget
        startNanos = nowNanos
        isActive = true
        return true
    }

    /** Returns the eased position, completing exactly on [targetOffset]. */
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
