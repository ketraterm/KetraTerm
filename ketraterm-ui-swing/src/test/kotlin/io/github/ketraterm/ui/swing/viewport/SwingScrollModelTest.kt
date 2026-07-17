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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SwingScrollModelTest {
    @Test
    fun `fractional animation position uses overscan and visual translation`() {
        val model = SwingScrollModel()

        assertTrue(model.scrollTo(0.4, historySize = 10))
        assertEquals(0, model.offset)
        assertEquals(1, model.requestedOffset)
        assertTrue(model.needsOverscan)
        assertEquals(-9.6, model.contentYOffset(cellHeight = 16), 1.0e-12)

        assertTrue(model.scrollTo(1.1, historySize = 10))
        assertEquals(1, model.offset)
        assertEquals(2, model.requestedOffset)
    }

    @Test
    fun `scroll offset clamps to available history`() {
        val model = SwingScrollModel()

        assertTrue(model.scrollTo(12.0, historySize = 5))

        assertEquals(5, model.offset)
        assertEquals(5, model.requestedOffset)
    }

    @Test
    fun `absolute fractional input preserves precise visual position`() {
        val model = SwingScrollModel()

        assertTrue(model.scrollTo(2.5, historySize = 10))

        assertEquals(2.5, model.preciseScrollbackOffset)
        assertEquals(2, model.offset)
        assertEquals(3, model.requestedOffset)
    }

    @Test
    fun `unchanged or clamped positions report no movement`() {
        val model = SwingScrollModel()

        assertFalse(model.scrollTo(0.0, historySize = 5))
        assertFalse(model.scrollTo(-1.0, historySize = 5))
        assertEquals(0, model.offset)
    }

    @Test
    fun `reset returns to live viewport`() {
        val model = SwingScrollModel()

        model.scrollTo(3.0, historySize = 5)
        model.reset()

        assertEquals(0, model.offset)
        assertEquals(0, model.requestedOffset)
    }

    @Test
    fun `fractional scroll requests overscan row and translated content`() {
        val model = SwingScrollModel()

        model.scrollTo(0.25, historySize = 10)

        assertEquals(4, model.requestedRows(renderRows = 3))
        assertEquals(-12.0, model.contentYOffset(cellHeight = 16))
    }

    @Test
    fun `scrollOnOutput = true snaps to bottom when history grows`() {
        val model = SwingScrollModel()
        model.scrollTo(2.0, historySize = 5)

        model.clamp(historySize = 6, discardedCount = 0L, scrollOnOutput = true)
        assertEquals(0.0, model.preciseScrollbackOffset)
        assertEquals(0, model.offset)
    }

    @Test
    fun `scrollOnOutput = false locks content when history grows`() {
        val model = SwingScrollModel()
        model.scrollTo(2.0, historySize = 5)
        model.clamp(historySize = 5, discardedCount = 0L, scrollOnOutput = false)

        model.clamp(historySize = 6, discardedCount = 0L, scrollOnOutput = false)
        assertEquals(3.0, model.preciseScrollbackOffset)
        assertEquals(3, model.offset)
    }

    @Test
    fun `scrollOnOutput = false locks content when history reaches capacity and discards lines`() {
        val model = SwingScrollModel()
        model.scrollTo(2.0, historySize = 10)
        model.clamp(historySize = 10, discardedCount = 0L, scrollOnOutput = false)

        model.clamp(historySize = 10, discardedCount = 1L, scrollOnOutput = false)
        assertEquals(3.0, model.preciseScrollbackOffset)
        assertEquals(3, model.offset)
    }

    @Test
    fun `scrollOnOutput = false does not snap if already at bottom`() {
        val model = SwingScrollModel()
        model.clamp(historySize = 5, discardedCount = 0L, scrollOnOutput = false)

        model.clamp(historySize = 6, discardedCount = 0L, scrollOnOutput = false)
        assertEquals(0.0, model.preciseScrollbackOffset)
    }

    @Test
    fun `alternate buffer transition clears an active fractional primary viewport and returns live`() {
        val model = SwingScrollModel()
        model.scrollTo(4.5, historySize = 10)
        model.clamp(historySize = 10, discardedCount = 7L, scrollOnOutput = false)

        model.clamp(historySize = 0, discardedCount = 0L, scrollOnOutput = false)

        assertEquals(0.0, model.preciseScrollbackOffset)
        assertEquals(0, model.offset)
        assertEquals(0, model.requestedOffset)
        assertFalse(model.needsOverscan)

        model.clamp(historySize = 10, discardedCount = 7L, scrollOnOutput = false)

        assertEquals(0.0, model.preciseScrollbackOffset)
    }
}
