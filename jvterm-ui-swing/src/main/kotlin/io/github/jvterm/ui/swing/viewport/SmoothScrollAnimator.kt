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

/** EDT-owned animation driver shared by every interactive scroll source. */
internal class SmoothScrollAnimator(
    private val host: SmoothScrollAnimationHost,
) {
    private val animation = SmoothScrollAnimation()
    private val timer =
        Timer(FRAME_DELAY_MILLIS) {
            advance(System.nanoTime())
        }.apply {
            isCoalesce = true
            isRepeats = true
            initialDelay = 0
        }

    /** Smoothly retargets the viewport by [deltaRows]. */
    fun scrollBy(deltaRows: Double): Boolean =
        retarget { currentOffset, historySize, nowNanos ->
            animation.retargetBy(currentOffset, deltaRows, historySize, nowNanos)
        }

    /** Smoothly retargets the viewport to [offsetRows]. */
    fun scrollTo(offsetRows: Double): Boolean =
        retarget { currentOffset, historySize, nowNanos ->
            animation.retargetTo(currentOffset, offsetRows, historySize, nowNanos)
        }

    /** Cancels interpolation because an immediate viewport operation took ownership. */
    fun cancel() {
        timer.stop()
        animation.cancel()
    }

    /** Completes an in-flight animation on its exact destination. */
    fun finish() {
        if (!animation.isActive) return
        timer.stop()
        host.applySmoothScrollOffset(animation.targetOffset, animationComplete = true)
        animation.cancel()
    }

    internal fun advance(nowNanos: Long) {
        if (!animation.isActive) {
            timer.stop()
            return
        }
        val position = animation.positionAt(nowNanos)
        val complete = !animation.isActive
        host.applySmoothScrollOffset(position, complete)
        if (complete) timer.stop()
    }

    private inline fun retarget(operation: (Double, Int, Long) -> Boolean): Boolean {
        val now = System.nanoTime()
        if (animation.isActive) host.applySmoothScrollOffset(animation.positionAt(now), animationComplete = false)
        if (!operation(host.smoothScrollOffset(), host.smoothScrollHistorySize(), now)) return false
        if (!timer.isRunning) timer.start()
        return true
    }

    private companion object {
        private const val FRAME_DELAY_MILLIS = 8
    }
}

/** Narrow EDT bridge from animation state into component-owned viewport state. */
internal interface SmoothScrollAnimationHost {
    fun smoothScrollOffset(): Double

    fun smoothScrollHistorySize(): Int

    fun applySmoothScrollOffset(
        offsetRows: Double,
        animationComplete: Boolean,
    ): Boolean
}
