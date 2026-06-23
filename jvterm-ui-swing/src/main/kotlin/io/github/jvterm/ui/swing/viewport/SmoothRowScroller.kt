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

import javax.swing.Timer
import kotlin.math.roundToInt

/**
 * EDT-owned scrolling coordinator shared by all interactive input sources.
 *
 * Precise device input is accumulated into rows. Animation may pass through
 * fractional visual offsets, but every target and every completed position is
 * an integer row. Direct scrollbar dragging uses [jumpToRow] and therefore has
 * no easing lag.
 */
internal class SmoothRowScroller(
    private val host: SmoothRowScrollHost,
) {
    private val accumulator = ScrollDeltaAccumulator()
    private val animation = SmoothRowScrollAnimation()
    private val timer =
        Timer(FRAME_DELAY_MILLIS) {
            advance(System.nanoTime())
        }.apply {
            isCoalesce = true
            isRepeats = true
            initialDelay = 0
        }

    /** Accumulates [deltaRows] and animates any emitted whole rows. */
    fun scrollByPreciseRows(deltaRows: Double): Boolean {
        require(deltaRows.isFinite()) { "deltaRows must be finite, was $deltaRows" }
        if (deltaRows == 0.0) return false

        val historySize = host.rowScrollHistorySize()
        val destination = if (animation.isActive) animation.targetRow else host.rowScrollOffset().roundToInt()
        if ((deltaRows < 0.0 && destination <= 0) || (deltaRows > 0.0 && destination >= historySize)) {
            accumulator.reset()
            return animation.isActive
        }

        val wholeRows = accumulator.accumulate(deltaRows)
        if (wholeRows == 0) return true
        val changed = retargetBy(wholeRows, historySize)
        if (!changed) accumulator.reset()
        return changed
    }

    /** Animates a signed whole-row delta. */
    fun scrollByRows(deltaRows: Int): Boolean {
        accumulator.reset()
        if (deltaRows == 0) return false
        return retargetBy(deltaRows, host.rowScrollHistorySize())
    }

    /** Animates to the absolute integer [targetRow]. */
    fun scrollToRow(targetRow: Int): Boolean {
        accumulator.reset()
        val now = System.nanoTime()
        applyCurrentPosition(now)
        val historySize = host.rowScrollHistorySize()
        val clampedTarget = targetRow.coerceIn(0, historySize)
        if (!animation.retargetTo(host.rowScrollOffset(), clampedTarget, historySize, now)) {
            host.applyRowScrollOffset(clampedTarget.toDouble(), scrollComplete = true)
            return false
        }
        startTimer()
        return true
    }

    /** Applies [targetRow] immediately for lag-free direct manipulation. */
    fun jumpToRow(targetRow: Int): Boolean {
        cancel()
        val clampedTarget = targetRow.coerceIn(0, host.rowScrollHistorySize())
        return host.applyRowScrollOffset(clampedTarget.toDouble(), scrollComplete = false)
    }

    /** Cancels animation and retained precise input. */
    fun cancel() {
        timer.stop()
        animation.cancel()
        accumulator.reset()
    }

    /** Completes an in-flight animation on its integer destination. */
    fun finish() {
        accumulator.reset()
        if (!animation.isActive) return
        timer.stop()
        host.applyRowScrollOffset(animation.targetRow.toDouble(), scrollComplete = true)
        animation.cancel()
    }

    internal fun advance(nowNanos: Long) {
        if (!animation.isActive) {
            timer.stop()
            return
        }
        val position = animation.positionAt(nowNanos)
        val complete = !animation.isActive
        host.applyRowScrollOffset(position, scrollComplete = complete)
        if (complete) timer.stop()
    }

    private fun retargetBy(
        deltaRows: Int,
        historySize: Int,
    ): Boolean {
        val now = System.nanoTime()
        applyCurrentPosition(now)
        if (!animation.retargetBy(host.rowScrollOffset(), deltaRows, historySize, now)) return false
        startTimer()
        return true
    }

    private fun applyCurrentPosition(nowNanos: Long) {
        if (animation.isActive) {
            host.applyRowScrollOffset(animation.positionAt(nowNanos), scrollComplete = false)
        }
    }

    private fun startTimer() {
        if (!timer.isRunning) timer.start()
    }

    private companion object {
        private const val FRAME_DELAY_MILLIS = 8
    }
}

/** EDT bridge from row-scroll coordination into component viewport state. */
internal interface SmoothRowScrollHost {
    fun rowScrollOffset(): Double

    fun rowScrollHistorySize(): Int

    fun applyRowScrollOffset(
        offsetRows: Double,
        scrollComplete: Boolean,
    ): Boolean
}
