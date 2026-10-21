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

import io.github.ketraterm.render.api.TerminalRenderImage
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GraphicsScrollingTest {
    @Test
    fun `full viewport scrolling retains ordinary placements below the screen and brings them back`() {
        for (alternate in listOf(false, true)) {
            val store = store()
            val resource = image()
            val slot = store.retain(alternate, 1, 0, resource)
            store.put(slot, 1, placement(row = 5, columns = 1, rows = 1))
            val before = visible(store, alternate).single()

            store.scroll(alternate, 0, 5, 0, 9, 2, false, 0, wholeViewport = true)
            assertTrue(visible(store, alternate).isEmpty())
            store.scroll(alternate, 0, 5, 0, 9, -1, !alternate, 1, wholeViewport = true)
            assertTrue(visible(store, alternate).isEmpty())
            store.scroll(alternate, 0, 5, 0, 9, -1, !alternate, 2, wholeViewport = true)

            val after = visible(store, alternate).single()
            assertSame(resource, after.image)
            assertEquals(before, after)
        }
    }

    @Test
    fun `margin scrolling preserves ordinary history and clips at the top despite text history admission`() {
        val store = store()
        val historyImage = image()
        store.put(store.retain(false, 1, 0, historyImage), 1, placement(row = 0, columns = 1, rows = 1))
        store.scroll(false, 0, 5, 0, 9, -1, true, 1, wholeViewport = true)
        val history = visible(store, offset = 1).single()
        store.put(store.retain(false, 2, 0, image()), 1, placement(row = 0, columns = 1, rows = 1))

        store.scroll(false, 0, 3, 0, 9, -1, true, 2)

        assertTrue(visible(store).isEmpty())
        assertEquals(listOf(history), visible(store, offset = 1))
        assertSame(historyImage, visible(store, offset = 1).single().image)
    }

    @Test
    fun `margin crops retain exact source pixels through cell resizing for every scaling mode`() {
        data class Case(
            val columns: Int,
            val rows: Int,
            val effectiveRows: Int,
            val topSource: Double,
            val topHeight: Double,
            val topWidth: Double,
            val topDestinationHeight: Double,
            val bottomHeight: Double,
            val bottomWidth: Double,
            val bottomDestinationHeight: Double,
        )
        // Source crop is 10x30 pixels; initial cells are 8x16 with offsets X=2,Y=4.
        // Expected fractions follow the retained source pixels, independently of the
        // integer enclosing crop used to publish them to render consumers.
        val cases =
            listOf(
                Case(0, 0, 3, 28.0, 2.0, 5.0 / 6, 1.0 / 12, 12.0, 5.0 / 6, 0.5),
                Case(2, 0, 4, 20.0, 10.0, 11.0 / 6, 11.0 / 12, 20.0, 11.0 / 6, 11.0 / 6),
                Case(0, 3, 3, 210.0 / 11, 120.0 / 11, 11.0 / 6, 1.0, 90.0 / 11, 55.0 / 27, 5.0 / 6),
                Case(2, 3, 3, 210.0 / 11, 120.0 / 11, 11.0 / 6, 1.0, 90.0 / 11, 11.0 / 6, 5.0 / 6),
            )
        for (case in cases) {
            for (up in listOf(true, false)) {
                val store = store()
                val resource = image()
                val startRow = if (up) 1 else 7 - case.effectiveRows
                store.put(store.retain(false, 1, 0, resource), 1, placement(startRow, case.columns, case.rows, offsetX = 2, offsetY = 4))
                store.scroll(false, 1, 6, 0, 9, if (up) -2 else 2, false, 0)
                store.geometryChanged(12, 24)

                val actual = visible(store, cellWidth = 12, cellHeight = 24, viewportRows = 8).single()
                val context = "columns=${case.columns}, rows=${case.rows}, up=$up"
                assertSame(resource, actual.image, context)
                assertClose(if (up) 1.0 else startRow + 2 + 1.0 / 6, actual.top, context)
                assertClose(if (up) case.topDestinationHeight else case.bottomDestinationHeight, actual.height, context)
                assertClose(if (up) case.topWidth else case.bottomWidth, actual.width, context)
                assertClose(2 + if (up) case.topSource else 0.0, actual.sourceTop, context)
                assertClose(if (up) case.topHeight else case.bottomHeight, actual.sourceHeight, context)
            }
        }
    }

    @Test
    fun `successive fractional crops with a cell resize between them preserve source mapping`() {
        val store = store()
        store.put(store.retain(false, 1, 0, image()), 1, placement(1, 0, 3, offsetX = 2, offsetY = 4))
        store.scroll(false, 1, 6, 0, 9, -1, false, 0)
        store.geometryChanged(12, 24)
        store.scroll(false, 1, 6, 0, 9, -1, false, 0)
        store.geometryChanged(8, 16)

        val actual = visible(store).single()
        assertClose(1.0, actual.top)
        assertClose(1.0, actual.height)
        assertClose(11.0 / 6, actual.width)
        assertClose(2 + 210.0 / 11, actual.sourceTop)
        assertClose(120.0 / 11, actual.sourceHeight)
    }

    @Test
    fun `cropped records survive compaction and a replacement placement restores its requested source`() {
        val store = store()
        val slot = store.retain(false, 1, 0, image())
        store.put(slot, 1, placement(0, 1, 1))
        store.put(slot, 2, placement(1, 0, 3, offsetX = 2, offsetY = 4))
        store.scroll(false, 1, 6, 0, 9, -1, false, 0)
        val cropped = visible(store).last()

        store.delete(false, 'i', 1, 0, 1, 0, 0, 0, 0, 0)
        assertEquals(listOf(cropped), visible(store))
        store.put(slot, 2, placement(1, 0, 3, offsetX = 2, offsetY = 4))

        val replacement = visible(store).single()
        assertClose(2.0, replacement.sourceTop)
        assertClose(30.0, replacement.sourceHeight)
        assertClose(1.25, replacement.top)
        assertClose(2.75, replacement.height)
    }

    @Test
    fun `temporary unknown geometry preserves the last usable size for margin cropping`() {
        val store = store()
        store.put(store.retain(false, 1, 0, image()), 1, placement(1, 0, 0))
        assertTrue(store.geometryChanged(0, 0))
        assertFalse(store.geometryChanged(0, 0))
        assertTrue(visible(store, cellWidth = 0, cellHeight = 0).isEmpty())

        store.scroll(false, 1, 6, 0, 9, -1, false, 0)
        assertTrue(store.geometryChanged(8, 16))

        val actual = visible(store).single()
        assertClose(1.0, actual.top)
        assertClose(18.0, actual.sourceTop)
        assertClose(14.0, actual.sourceHeight)
        assertClose(14.0 / 16, actual.height)
    }

    @Test
    fun `ID and range deletion cannot select anonymous images`() {
        for (selector in listOf('i', 'I', 'r', 'R')) {
            for (lastId in listOf(0L, 1L, 4294967295L)) {
                val store = store()
                val anonymous = image()
                store.put(store.retain(false, 0, 0, anonymous), 1, placement(0, 1, 1))
                store.put(store.retain(false, 1, 0, image()), 1, placement(1, 1, 1))
                store.delete(false, selector, 0, 0, 0, 0, lastId, 0, 0, 0)

                val images = visible(store).map { it.image }
                assertSame(anonymous, images.first(), "$selector, upper=$lastId")
                assertEquals(if (selector.lowercaseChar() == 'r' && lastId > 0) 1 else 2, images.size)
            }
        }
    }

    private fun store() = GraphicsStore().apply { geometryChanged(8, 16) }

    private fun image() = ArgbImage(12, 34, IntArray(12 * 34))

    private fun placement(
        row: Int,
        columns: Int,
        rows: Int,
        offsetX: Int = 0,
        offsetY: Int = 0,
    ): IntArray =
        IntArray(GraphicsStore.STRIDE).apply {
            this[GraphicsStore.ROW] = row
            this[GraphicsStore.COLUMNS] = columns
            this[GraphicsStore.ROWS] = rows
            this[GraphicsStore.OFFSET_X] = offsetX
            this[GraphicsStore.OFFSET_Y] = offsetY
            this[GraphicsStore.SOURCE_X] = 1
            this[GraphicsStore.SOURCE_Y] = 2
            this[GraphicsStore.SOURCE_WIDTH] = 10
            this[GraphicsStore.SOURCE_HEIGHT] = 30
            this[GraphicsStore.CLIP_TOP] = Int.MIN_VALUE
            this[GraphicsStore.CLIP_BOTTOM] = Int.MAX_VALUE
            assertTrue(GraphicsStore.resolveSpan(this, 0, 8, 16))
        }

    private data class VisiblePlacement(
        val image: TerminalRenderImage,
        val top: Double,
        val height: Double,
        val width: Double,
        val sourceTop: Double,
        val sourceHeight: Double,
    )

    private fun visible(
        store: GraphicsStore,
        alternate: Boolean = false,
        offset: Int = 0,
        cellWidth: Int = 8,
        cellHeight: Int = 16,
        viewportRows: Int = 6,
    ): List<VisiblePlacement> =
        buildList {
            store.copy(
                alternate,
                offset,
                viewportRows,
                cellWidth,
                cellHeight,
            ) { image, _, row, width, height, _, sy, _, sh, top, bottom, _ ->
                val visibleTop = maxOf(row, top, 0.0)
                val visibleBottom = minOf(row + height, bottom, viewportRows.toDouble())
                if (visibleTop < visibleBottom) {
                    add(
                        VisiblePlacement(
                            image,
                            visibleTop,
                            visibleBottom - visibleTop,
                            width,
                            sy + (visibleTop - row) * sh / height,
                            (visibleBottom - visibleTop) * sh / height,
                        ),
                    )
                }
            }
        }

    private fun assertClose(
        expected: Double,
        actual: Double,
        context: String = "",
    ) = assertEquals(expected, actual, 1e-9, context)
}
