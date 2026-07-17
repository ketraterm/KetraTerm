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
package io.github.ketraterm.completion.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BoundedStatsRowIndexTest {
    @Test
    fun `snapshot identity remains stable until rows change`() {
        val index = rowIndex(capacity = 2)
        index.replaceAll(listOf(Row(key = "a", value = "first", timestamp = 1)))

        val first = index.snapshot()

        assertTrue(first === index.snapshot())

        index.mutate(
            key = "a",
            initialRow = { Row(key = "a", value = "unused", timestamp = 0) },
            update = { it.copy(value = "second", timestamp = 2) },
        )

        assertTrue(first !== index.snapshot())
        assertEquals("first", first.single().value)
        assertEquals("second", index.snapshot().single().value)
    }

    @Test
    fun `replace all keeps newest duplicate and sorts retained rows`() {
        val index = rowIndex(capacity = 4)

        index.replaceAll(
            listOf(
                Row(key = "alpha", value = "old-alpha", timestamp = 10),
                Row(key = "beta", value = "beta", timestamp = 30),
                Row(key = "alpha", value = "new-alpha", timestamp = 20),
            ),
        )

        assertEquals(listOf("beta", "new-alpha"), index.snapshot().map { it.value })
    }

    @Test
    fun `replace all rejects older duplicate and lets equal timestamp later row win`() {
        val index = rowIndex(capacity = 4)

        index.replaceAll(
            listOf(
                Row(key = "alpha", value = "new-alpha", timestamp = 20),
                Row(key = "alpha", value = "old-alpha", timestamp = 10),
                Row(key = "beta", value = "first-beta", timestamp = 30),
                Row(key = "beta", value = "second-beta", timestamp = 30),
            ),
        )

        assertEquals(listOf("second-beta", "new-alpha"), index.snapshot().map { it.value })
    }

    @Test
    fun `replace all compacts before applying capacity`() {
        val index = rowIndex(capacity = 2)

        index.replaceAll(
            listOf(
                Row(key = "alpha", value = "old-alpha", timestamp = 1),
                Row(key = "alpha", value = "new-alpha", timestamp = 10),
                Row(key = "beta", value = "beta", timestamp = 9),
                Row(key = "gamma", value = "gamma", timestamp = 8),
            ),
        )

        assertEquals(listOf("new-alpha", "beta"), index.snapshot().map { it.value })
    }

    @Test
    fun `mutate updates existing row and evicts least relevant row on insert`() {
        val index = rowIndex(capacity = 2)

        index.mutate(
            key = "alpha",
            initialRow = { Row(key = "alpha", value = "alpha", timestamp = 1) },
            update = { it },
        )
        index.mutate(
            key = "beta",
            initialRow = { Row(key = "beta", value = "beta", timestamp = 2) },
            update = { it },
        )
        index.mutate(
            key = "alpha",
            initialRow = { Row(key = "alpha", value = "unused", timestamp = 0) },
            update = { it.copy(value = "updated-alpha", timestamp = 3) },
        )
        index.mutate(
            key = "gamma",
            initialRow = { Row(key = "gamma", value = "gamma", timestamp = 4) },
            update = { it },
        )

        assertEquals(listOf("gamma", "updated-alpha"), index.snapshot().map { it.value })
    }

    private fun rowIndex(capacity: Int): BoundedStatsRowIndex<Row, String> =
        BoundedStatsRowIndex(
            capacity = capacity,
            order = compareByDescending<Row> { it.timestamp }.thenBy { it.value },
            keySelector = Row::key,
            shouldReplace = { current, candidate -> candidate.timestamp >= current.timestamp },
        )

    private data class Row(
        val key: String,
        val value: String,
        val timestamp: Long,
    )
}
