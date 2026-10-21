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
import kotlin.test.*

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
        val second = store.image(store.retain(true, 2, 0, large))
        assertTrue(store.find(false, 1, 0) >= 0)
        assertTrue(store.find(true, 2, 0) >= 0)
        store.retain(false, 3, 0, image())
        assertEquals(-1, store.find(false, 1, 0))
        assertSame(second, store.image(store.find(true, 2, 0)))
    }

    @Test
    fun `removing the last anonymous placement releases its image record before further admission`() {
        val removals =
            listOf<Pair<String, (GraphicsStore) -> Unit>>(
                "lowercase deletion" to { store -> store.delete(false, 'a', 0, 0, 0, 0, 0, 0, 0, 0) },
                "viewport clear" to { store -> store.clear(false, history = false) },
                "history clear" to { store ->
                    store.scroll(false, 0, 5, 0, 9, -1, true, 1, wholeViewport = true)
                    store.clear(false, viewport = false)
                },
                "history eviction" to { store ->
                    repeat(2) { store.scroll(false, 0, 5, 0, 9, -1, true, 1, wholeViewport = true) }
                },
                "margin clipping" to { store -> store.scroll(false, 0, 3, 0, 9, -1, false, 0) },
                "primary resize" to { store -> store.resizePrimary(1, 0) },
            )
        for ((operation, remove) in removals) {
            val store = GraphicsStore().apply { geometryChanged(8, 16) }
            val oldest = store.image(store.retain(false, 1, 0, image()))
            for (id in 2L until TerminalGraphicsLimits.IMAGES.toLong()) store.retain(false, id, 0, image())
            val anonymous = store.retain(false, 0, 0, image())
            val resource = store.image(anonymous)
            store.put(anonymous, 0, placement())

            remove(store)
            assertFalse(resource.isRetainedBySource, operation)
            var visible = 0
            store.copy(false, 0, 6, 8, 16) { _, _, _, _, _, _, _, _, _, _, _, _ -> visible++ }
            assertEquals(0, visible, operation)
            store.retain(false, TerminalGraphicsLimits.IMAGES + 1L, 0, image())

            assertTrue(store.find(false, 1, 0) >= 0, "$operation must release the anonymous record without evicting a reusable image")
            assertSame(oldest, store.image(store.find(false, 1, 0)), operation)
        }
    }

    @Test
    fun `anonymous resources survive until their final placement is removed on either screen`() {
        for (alternate in listOf(false, true)) {
            val store = GraphicsStore().apply { geometryChanged(8, 16) }
            val slot = store.retain(alternate, 0, 0, image())
            val resource = store.image(slot)
            store.put(slot, 0, placement(row = 0))
            store.put(slot, 0, placement(row = 1))

            store.delete(alternate, 'y', 0, 0, 0, 0, 1, 0, 0, 0)
            var remaining = 0
            store.copy(alternate, 0, 6, 8, 16) { image, _, y, _, _, _, _, _, _, _, _, _ ->
                remaining++
                assertSame(resource, image)
                assertEquals(1.0, y)
            }
            assertEquals(1, remaining)
            assertTrue(resource.isRetainedBySource)

            store.delete(alternate, 'y', 0, 0, 0, 0, 2, 0, 0, 0)
            assertFalse(resource.isRetainedBySource)
            store.put(store.retain(alternate, 1, 0, image()), 1, placement())
            var replacement = 0
            store.copy(alternate, 0, 6, 8, 16) { _, _, _, _, _, _, _, _, _, _, _, _ -> replacement++ }
            assertEquals(1, replacement)
        }
    }

    @Test
    fun `removing the last named placement preserves its image for redisplay`() {
        val store = GraphicsStore().apply { geometryChanged(8, 16) }
        val slot = store.retain(false, 1, 0, image())
        val resource = store.image(slot)
        store.put(slot, 1, placement())

        store.delete(false, 'a', 0, 0, 0, 0, 0, 0, 0, 0)

        assertTrue(resource.isRetainedBySource)
        assertSame(resource, store.image(store.find(false, 1, 0)))
        store.put(store.find(false, 1, 0), 2, placement())
        var visible = 0
        store.copy(false, 0, 6, 8, 16) { image, _, _, _, _, _, _, _, _, _, _, _ ->
            visible++
            assertSame(resource, image)
        }
        assertEquals(1, visible)
    }

    @Test
    fun `replacement retires only the prior source lifetime and preserves pinned pixels`() {
        val store = GraphicsStore().apply { geometryChanged(8, 16) }
        val input = image(0x12345678)
        val original = store.image(store.retain(false, 1, 0, input))
        assertTrue(original.isRetainedBySource)

        val replacement = store.image(store.retain(false, 1, 0, input))

        assertFalse(original.isRetainedBySource)
        assertTrue(replacement.isRetainedBySource)
        val pixels = IntArray(1)
        original.copyArgb(pixels)
        assertContentEquals(intArrayOf(0x12345678), pixels)
        store.delete(false, 'I', 1, 0, 0, 0, 0, 0, 0, 0)
        assertFalse(replacement.isRetainedBySource)
        replacement.copyArgb(pixels)
        assertContentEquals(intArrayOf(0x12345678), pixels)
    }

    @Test
    fun `clearing one source screen retires its resources while preserving the other screen`() {
        val store = GraphicsStore().apply { geometryChanged(8, 16) }
        val primary = store.image(store.retain(false, 1, 0, image()))
        val alternate = store.image(store.retain(true, 1, 0, image()))

        store.clear(false, data = true)

        assertFalse(primary.isRetainedBySource)
        assertTrue(alternate.isRetainedBySource)
        assertSame(alternate, store.image(store.find(true, 1, 0)))
        store.resizePrimary(0, 0)
        assertFalse(alternate.isRetainedBySource)
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
        val shown = store.retain(false, 1, 0, image())
        val retained = store.image(shown)
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
        val firstSlot = store.retain(false, 99, 0, image(1))
        val secondSlot = store.retain(false, 1, 0, image(2))
        val first = store.image(firstSlot)
        val second = store.image(secondSlot)
        store.put(firstSlot, 9, placement())
        store.put(secondSlot, 1, placement())
        val resources = mutableListOf<TerminalRenderImage>()
        store.copy(false, 0, 6, 8, 16) { image, _, _, _, _, _, _, _, _, _, _, _ -> resources += image }
        assertEquals<List<TerminalRenderImage>>(listOf(first, second), resources)
    }
}
