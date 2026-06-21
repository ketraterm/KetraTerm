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

/** EDT-owned timer driver for row-targeted terminal wheel animation. */
internal class WheelScrollAnimator(
    private val host: WheelScrollAnimationHost,
) {
    private val animation = WheelScrollAnimation()
    private val timer =
        Timer(FRAME_DELAY_MILLIS) {
            advance(System.nanoTime())
        }.apply {
            isCoalesce = true
            isRepeats = true
            initialDelay = 0
        }

    /** Retargets visual scrolling by [deltaRows] whole terminal rows. */
    fun scrollByRows(deltaRows: Int): Boolean {
        val now = System.nanoTime()
        if (animation.isActive) applyPosition(animation.positionAt(now))
        if (!animation.retarget(host.wheelScrollOffset(), deltaRows, host.wheelScrollHistorySize(), now)) return false
        if (!timer.isRunning) timer.start()
        return true
    }

    /** Cancels interpolation because another viewport owner is taking control. */
    fun cancel() {
        timer.stop()
        animation.cancel()
    }

    /** Completes an in-flight animation on its exact destination row. */
    fun finish() {
        if (!animation.isActive) return
        timer.stop()
        host.applyWheelScrollOffset(animation.targetOffset, animationComplete = true)
        animation.cancel()
    }

    internal fun advance(nowNanos: Long) {
        if (!animation.isActive) {
            timer.stop()
            return
        }
        val position = animation.positionAt(nowNanos)
        val complete = !animation.isActive
        applyPosition(position, complete)
        if (complete) timer.stop()
    }

    private fun applyPosition(
        position: Double,
        complete: Boolean = !animation.isActive,
    ) {
        host.applyWheelScrollOffset(position, complete)
    }

    private companion object {
        private const val FRAME_DELAY_MILLIS = 8
    }
}

/** Narrow EDT bridge from wheel animation into component-owned viewport state. */
internal interface WheelScrollAnimationHost {
    fun wheelScrollOffset(): Double

    fun wheelScrollHistorySize(): Int

    fun applyWheelScrollOffset(
        offsetRows: Double,
        animationComplete: Boolean,
    ): Boolean
}
