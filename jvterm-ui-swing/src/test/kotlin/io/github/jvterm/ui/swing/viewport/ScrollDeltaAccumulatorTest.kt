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

class ScrollDeltaAccumulatorTest {
    @Test
    fun `sub-row input accumulates until one complete row`() {
        val accumulator = ScrollDeltaAccumulator()

        assertEquals(0, accumulator.accumulate(0.3))
        assertEquals(0, accumulator.accumulate(0.3))
        assertEquals(0, accumulator.accumulate(0.3))
        assertEquals(1, accumulator.accumulate(0.3))
        assertEquals(0, accumulator.accumulate(0.7))
        assertEquals(1, accumulator.accumulate(0.1))
    }

    @Test
    fun `opposite deltas cancel within the retained remainder`() {
        val accumulator = ScrollDeltaAccumulator()

        assertEquals(0, accumulator.accumulate(0.8))
        assertEquals(0, accumulator.accumulate(-0.4))
        assertEquals(0, accumulator.accumulate(-0.3))
        assertEquals(-1, accumulator.accumulate(-1.1))
    }

    @Test
    fun `reset discards retained input`() {
        val accumulator = ScrollDeltaAccumulator()

        accumulator.accumulate(0.8)
        accumulator.reset()

        assertEquals(0, accumulator.accumulate(0.3))
    }
}
