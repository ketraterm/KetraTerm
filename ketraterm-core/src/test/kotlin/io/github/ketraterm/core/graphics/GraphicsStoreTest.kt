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
package io.github.ketraterm.core.graphics

import io.github.ketraterm.protocol.TerminalGraphicsLimits
import io.github.ketraterm.render.api.TerminalRenderImage
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GraphicsStoreTest {
    @Test
    fun `unchanged cell geometry does not invalidate published graphics`() {
        val store = GraphicsStore().apply { geometryChanged(8, 16) }
        store.geometryChanged(8, 16)
        store.put(store.retain(false, 1, 0, image()), 1, placement())
        val before = store.generation
        assertFalse(store.geometryChanged(8, 16))
        assertEquals(before, store.generation)
        assertTrue(store.geometryChanged(16, 32))
        assertTrue(store.generation > before)
    }

    @Test
    fun `pixel budget is shared across screens and preserves a referenced immutable resource`() {
        val store = GraphicsStore().apply { geometryChanged(8, 16) }
        val large =
            object : TerminalRenderImage {
                override val width = 4096
                override val height = 2048

                override fun copyArgb(
                    destination: IntArray,
                    offset: Int,
                ) = error("Budget accounting does not copy pixels")
            }
        store.retain(false, 1, 0, large)
        store.retain(true, 2, 0, large)
        assertTrue(store.find(false, 1, 0) >= 0)
        assertTrue(store.find(true, 2, 0) >= 0)
        store.retain(false, 3, 0, image())
        assertEquals(-1, store.find(false, 1, 0))
        assertSame(large, store.image(store.find(true, 2, 0)))
    }

    @Test
    fun `placement limit still allows replacement and deleting a moved record releases capacity`() {
        val store = GraphicsStore().apply { geometryChanged(8, 16) }
        val slot = store.retain(false, 1, 0, image())
        val values = placement()
        for (id in 1L..TerminalGraphicsLimits.PLACEMENTS) store.put(slot, id, values)
        assertFalse(store.canPlace(slot, 0, false))
        assertFalse(store.canPlace(slot, 65537, false))
        assertTrue(store.canPlace(slot, 65536, false))
        store.put(slot, 65536, values)
        store.delete(false, 'i', 1, 0, 1, 0, 0, 0, 0, 0)
        store.delete(false, 'i', 1, 0, 65536, 0, 0, 0, 0, 0)
        assertTrue(store.canPlace(slot, 65537, false))
        var count = 0
        store.copy(false, 0, 6, 8, 16) { _, _, _, _, _, _, _, _, _, _, _, _ -> count++ }
        assertEquals(TerminalGraphicsLimits.PLACEMENTS - 2, count)
    }

    @Test
    fun `primary resize drops evicted history placements and clears alternate resources`() {
        val store = GraphicsStore().apply { geometryChanged(8, 16) }
        val slot = store.retain(false, 1, 0, image())
        store.put(slot, 1, placement(row = 0))
        store.put(slot, 2, placement(row = 3))
        store.retain(true, 1, 0, image())
        store.resizePrimary(2, 0)
        val rows = mutableListOf<Double>()
        store.copy(false, 0, 6, 8, 16) { _, _, y, _, _, _, _, _, _, _, _, _ -> rows += y }
        assertEquals(listOf(1.0), rows)
        assertEquals(-1, store.find(true, 1, 0))
    }

    private fun image(color: Int = -1) = ArgbImage(1, 1, intArrayOf(color))

    private fun placement(
        column: Int = 0,
        row: Int = 0,
        columns: Int = 1,
        rows: Int = 1,
    ): IntArray =
        IntArray(GraphicsStore.STRIDE).apply {
            this[GraphicsStore.COLUMN] = column
            this[GraphicsStore.ROW] = row
            this[GraphicsStore.COLUMNS] = columns
            this[GraphicsStore.ROWS] = rows
            this[GraphicsStore.SOURCE_WIDTH] = 1
            this[GraphicsStore.SOURCE_HEIGHT] = 1
            this[GraphicsStore.CLIP_TOP] = Int.MIN_VALUE
            this[GraphicsStore.CLIP_BOTTOM] = Int.MAX_VALUE
            assertTrue(GraphicsStore.resolveSpan(this, 0, 8, 16))
        }

    @Test
    fun `successive margin scrolls remove the clipped remainder and preserve outside placements`() {
        val store = GraphicsStore().apply { geometryChanged(8, 16) }
        val slot = store.retain(false, 1, 0, image())
        store.put(slot, 1, placement(2, 1, 2, 2))
        store.put(slot, 2, placement(0, 1, 1, 1))
        repeat(3) { store.scroll(false, 1, 4, 2, 7, -1, false, 0) }
        val columns = mutableListOf<Double>()
        store.copy(false, 0, 6, 8, 16) { _, x, _, _, _, _, _, _, _, _, _, _ -> columns += x }
        assertEquals(listOf(0.0), columns)
    }

    @Test
    fun `whole viewport scrolling moves oversized images and retains their lower pixels`() {
        val store = GraphicsStore().apply { geometryChanged(8, 16) }
        val slot = store.retain(false, 1, 0, image())
        store.put(slot, 1, placement(0, 4, 20, 4))
        repeat(2) { store.scroll(false, 0, 5, 0, 9, -1, true, it + 1, wholeViewport = true) }
        var row = -99.0
        var height = 0.0
        store.copy(false, 0, 6, 8, 16) { _, _, y, _, h, _, _, _, _, _, _, _ ->
            row = y
            height = h
        }
        assertEquals(2.0, row)
        assertEquals(4.0, height)
    }

    @Test fun `unplaced resources are evicted before displayed resources at the shared count limit`() {
        val store = GraphicsStore().apply { geometryChanged(8, 16) }
        val retained = image()
        val shown = store.retain(false, 1, 0, retained)
        store.put(shown, 1, placement())
        for (id in 2L..TerminalGraphicsLimits.IMAGES + 1L) store.retain(id % 2L == 0L, id, 0, image())
        assertSame(retained, store.image(store.find(false, 1, 0)))
        assertEquals(-1, store.find(true, 2, 0))
        assertTrue(store.find(false, 257, 0) >= 0)
    }

    @Test fun `margin scroll clips contained images while preserving outside placements`() {
        val store = GraphicsStore().apply { geometryChanged(8, 16) }
        val slot = store.retain(false, 1, 0, image())
        store.put(slot, 1, placement(2, 1, 2, 2))
        store.put(slot, 2, placement(0, 1, 1, 1))
        store.scroll(false, 1, 4, 2, 7, -1, false, 0)
        val positions = mutableListOf<Triple<Double, Double, Double>>()
        store.copy(false, 0, 6, 8, 16) { _, x, y, _, _, _, _, _, _, top, _, _ -> positions += Triple(x, y, top) }
        assertEquals(listOf(Triple(2.0, 0.0, 1.0), Triple(0.0, 1.0, 1.0)), positions)
    }

    @Test fun `oldest history placement is released exactly when it leaves retention`() {
        val store = GraphicsStore().apply { geometryChanged(8, 16) }
        val slot = store.retain(false, 1, 0, image())
        store.put(slot, 1, placement())
        repeat(2) { store.scroll(false, 0, 5, 0, 9, -1, true, it + 1, wholeViewport = true) }
        var retained = 0
        store.copy(false, 2, 8, 8, 16) { _, _, _, _, _, _, _, _, _, _, _, _ -> retained++ }
        assertEquals(1, retained)
        store.scroll(false, 0, 5, 0, 9, -1, true, 2, wholeViewport = true)
        var count = 0
        store.copy(false, 2, 8, 8, 16) { _, _, _, _, _, _, _, _, _, _, _, _ -> count++ }
        assertEquals(0, count)
    }

    @Test fun `equal z order uses immutable image creation then placement creation`() {
        val store = GraphicsStore().apply { geometryChanged(8, 16) }
        val first = image(1)
        val second = image(2)
        store.put(store.retain(false, 99, 0, first), 9, placement())
        store.put(store.retain(false, 1, 0, second), 1, placement())
        val resources = mutableListOf<TerminalRenderImage>()
        store.copy(false, 0, 6, 8, 16) { image, _, _, _, _, _, _, _, _, _, _, _ -> resources += image }
        assertEquals<List<TerminalRenderImage>>(listOf(first, second), resources)
    }
}
