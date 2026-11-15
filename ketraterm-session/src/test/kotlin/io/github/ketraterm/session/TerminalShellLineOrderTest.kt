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
package io.github.ketraterm.session

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.render.api.TerminalRenderFrame
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TerminalShellLineOrderTest {
    @Test
    fun `incremental positions match full reads across replacement wrapping and eviction`() {
        val terminal = TerminalBuffers.create(12, 8)
        val order = TerminalShellLineOrder()
        val reference = TerminalShellLineOrder()
        val random = Random(817)
        val retained = mutableListOf(1L, 3L, 2L, 4L)
        var discarded = 0L
        var nextId = 5L

        fun frame(
            first: Int,
            action: (TerminalRenderFrame) -> Unit,
        ) {
            terminal.readRenderFrame { base ->
                action(
                    object : TerminalRenderFrame by base {
                        override val rows = retained.size - first
                        override val discardedCount = discarded
                        override val historySize = first
                        override val scrollbackOffset = 0

                        override fun lineId(row: Int) = retained[first + row]
                    },
                )
            }
        }
        frame(0) { order.rebuild(it) }
        repeat(2_000) {
            val removed = ArrayList<Long>()
            val drop = random.nextInt(retained.size.coerceAtMost(8))
            repeat(drop) {
                removed.add(retained.removeAt(0))
                discarded++
            }
            val first = (retained.size - random.nextInt(1, retained.size.coerceAtMost(8) + 1)).coerceAtLeast(0)
            while (retained.size > first) removed.add(retained.removeAt(retained.lastIndex))
            repeat(random.nextInt(1, 12)) {
                val id = nextId++
                retained.add(id)
                if (random.nextBoolean()) retained.add(id)
            }
            frame(first) { assertTrue(order.update(it)) }
            frame(0) { reference.rebuild(it) }
            for (id in retained + removed) assertEquals(reference.position(id), order.position(id), "id=$id step=$it")
            for (row in retained.indices) assertEquals(retained[row], order.lineId(discarded + row))
        }
    }

    @Test
    fun `nonadjacent duplicate identities remain unavailable`() {
        val terminal = TerminalBuffers.create(12, 4)
        val order = TerminalShellLineOrder()
        terminal.readRenderFrame { base ->
            order.rebuild(
                object : TerminalRenderFrame by base {
                    override fun lineId(row: Int) = longArrayOf(1, 2, 1, 3)[row]
                },
            )
        }
        assertEquals(-1, order.position(1))
        assertEquals(0, order.lineId(0))
    }
}
