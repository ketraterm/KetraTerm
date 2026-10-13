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
package io.github.ketraterm.core.model

import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.*

class LineClusterStorageTest {
    @Test
    fun `scalar rows allocate no cluster arrays and full clears reuse warmed storage`() {
        val line = Line(8)
        line.fill('x'.code, 1L)
        assertNull(storage(line, "clusterData"))
        assertNull(storage(line, "clusterCompactionData"))
        val payload = IntArray(32) { if (it == 0) 'e'.code else 0x0301 }
        line.setCluster(2, payload, payload.size, 7L)
        val data = storage(line, "clusterData")
        repeat(100) {
            line.clear(0L)
            line.setCluster(5, payload, payload.size, 9L)
            assertSame(data, storage(line, "clusterData"))
            assertEquals(32, line.getClusterLength(5))
            assertFalse(line.isCluster(2))
        }
    }

    @Test
    fun `sustained overwrites compact live clusters and reuse both arrays after warmup`() {
        val line = Line(64)
        val payload = IntArray(32) { if (it == 0) 'e'.code else 0x0301 }
        repeat(line.width) { line.setCluster(it, payload, payload.size, it.toLong()) }

        fun overwrite(round: Int) {
            val col = (round * 17) % line.width
            line.setCell(col, 0, 0L)
            line.setCluster(col, payload, payload.size, col.toLong())
        }
        repeat(1000, ::overwrite)
        val first = assertNotNull(storage(line, "clusterData"))
        val second = assertNotNull(storage(line, "clusterCompactionData"))
        assertNotSame(first, second)
        assertTrue(first.size <= line.width * 32 * 2)
        assertTrue(second.size <= line.width * 32 * 2)
        repeat(2000, ::overwrite)
        val active = storage(line, "clusterData")
        val spare = storage(line, "clusterCompactionData")
        assertTrue(active === first || active === second)
        assertTrue(spare === first || spare === second)
        assertNotSame(active, spare)
        val copied = IntArray(32)
        repeat(line.width) { col ->
            assertEquals(32, line.readCluster(col, copied))
            assertContentEquals(payload, copied)
            assertEquals(col.toLong(), line.getPackedAttr(col))
        }
    }

    @Test
    fun `mixed edits and compaction match independent cell sequence model`() {
        val random = Random(0x51A7)
        val line = Line(23)
        val expected = MutableList<IntArray?>(line.width) { null }
        val copied = IntArray(32)
        repeat(10_000) { step ->
            val col = random.nextInt(line.width)
            when (random.nextInt(8)) {
                0, 1, 2 -> {
                    val length = random.nextInt(2, 33)
                    val value = IntArray(length) { if (it == 0) 'a'.code + col else 0x0300 + it }
                    expected[col] = value
                    line.setCluster(col, value, length, 0L)
                }
                3 -> {
                    expected[col] = intArrayOf('X'.code)
                    line.setCell(col, 'X'.code, 0L)
                }
                4 -> {
                    expected.add(col, null)
                    expected.removeAt(line.width)
                    line.insertCells(col, 1, 0L)
                }
                5 -> {
                    expected.removeAt(col)
                    expected.add(null)
                    line.deleteCells(col, 1, 0L)
                }
                6 -> {
                    for (i in col until line.width) expected[i] = null
                    line.clearFromColumn(col, 0L)
                }
                else -> {
                    for (i in 0..col) expected[i] = null
                    line.clearToColumn(col, 0L)
                }
            }
            var live = 0
            expected.forEachIndexed { index, value ->
                val length = if (value != null && value.size > 1) value.size else 0
                assertEquals(value?.first() ?: 0, line.getCodepoint(index), "step=$step col=$index")
                assertEquals(length, line.getClusterLength(index), "step=$step col=$index")
                if (length > 0) {
                    assertEquals(length, line.readCluster(index, copied))
                    assertContentEquals(value, copied.copyOf(length), "step=$step col=$index")
                    live += length
                }
            }
            assertEquals(live, intField(line, "liveClusterCodepoints"), "step=$step")
            if (live == 0) assertEquals(0, intField(line, "clusterDataSize"))
        }
    }

    @Test
    fun `cluster copy validates destination slice before writing`() {
        val line = Line(3)
        line.setCluster(1, intArrayOf('e'.code, 0x0301), 2, 0L)
        for (offset in intArrayOf(-1, 3, Int.MAX_VALUE)) {
            val dest = IntArray(4) { 777 }
            assertFailsWith<IndexOutOfBoundsException> { line.readCluster(1, dest, offset) }
            assertContentEquals(IntArray(4) { 777 }, dest)
        }
        val dest = IntArray(4) { 777 }
        assertEquals(2, line.readCluster(1, dest, 1))
        assertContentEquals(intArrayOf(777, 'e'.code, 0x0301, 777), dest)
    }

    private fun storage(
        line: Line,
        name: String,
    ): IntArray? =
        Line::class.java
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .get(line) as IntArray?

    private fun intField(
        line: Line,
        name: String,
    ): Int =
        Line::class.java
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .getInt(line)
}
