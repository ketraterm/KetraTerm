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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SmoothScrollAnimationTest {
    @Test
    fun `whole-row destination is eased and finishes exactly on grid`() {
        val animation = SmoothScrollAnimation()

        assertTrue(animation.retargetBy(currentOffset = 0.0, deltaRows = 3.0, historySize = 10, nowNanos = 0L))

        val halfway = animation.positionAt(50_000_000L)
        assertTrue(halfway > 0.0 && halfway < 3.0)
        assertTrue(animation.isActive)
        assertEquals(3.0, animation.positionAt(100_000_000L))
        assertFalse(animation.isActive)
    }

    @Test
    fun `repeated clicks accumulate from in-flight destination without jumping`() {
        val animation = SmoothScrollAnimation()
        animation.retargetBy(currentOffset = 0.0, deltaRows = 3.0, historySize = 10, nowNanos = 0L)
        val inFlightOffset = animation.positionAt(50_000_000L)

        assertTrue(
            animation.retargetBy(
                currentOffset = inFlightOffset,
                deltaRows = 3.0,
                historySize = 10,
                nowNanos = 50_000_000L,
            ),
        )

        assertEquals(6.0, animation.targetOffset)
        assertEquals(inFlightOffset, animation.positionAt(50_000_000L))
        assertEquals(6.0, animation.positionAt(150_000_000L))
    }

    @Test
    fun `destination clamps to history boundary`() {
        val animation = SmoothScrollAnimation()

        assertTrue(animation.retargetBy(currentOffset = 4.0, deltaRows = 20.0, historySize = 5, nowNanos = 0L))
        assertEquals(5.0, animation.positionAt(100_000_000L))
        assertFalse(animation.retargetBy(currentOffset = 5.0, deltaRows = 1.0, historySize = 5, nowNanos = 200_000_000L))
    }

    @Test
    fun `absolute retargeting starts from current visual position`() {
        val animation = SmoothScrollAnimation()

        assertTrue(animation.retargetTo(currentOffset = 2.25, targetOffset = 8.0, historySize = 10, nowNanos = 0L))
        assertEquals(2.25, animation.positionAt(0L))
        assertEquals(8.0, animation.positionAt(100_000_000L))
    }
}
