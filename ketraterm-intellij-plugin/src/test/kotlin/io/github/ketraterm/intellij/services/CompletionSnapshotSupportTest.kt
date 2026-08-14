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
package io.github.ketraterm.intellij.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.file.Path
import java.util.concurrent.CancellationException

/** Pure tests for bounded and lexical IntelliJ snapshot support. */
class CompletionSnapshotSupportTest {
    @Test
    fun `bounded collector retains the best deterministic values`() {
        val collector = BoundedSnapshotCollector(capacity = 3, order = compareBy<String> { it })

        listOf("delta", "alpha", "charlie", "bravo", "echo").forEach(collector::add)

        assertEquals(listOf("alpha", "bravo", "charlie"), collector.toSortedList())
    }

    @Test
    fun `bounded collector rejects a nonpositive capacity`() {
        assertThrows(IllegalArgumentException::class.java) {
            BoundedSnapshotCollector(capacity = 0, order = compareBy<String> { it })
        }
    }

    @Test
    fun `Git reference visit budget never requests a value beyond its limit`() {
        val values = CountingIterable()
        val visited = ArrayList<Int>()
        val budget = BoundedVisitBudget(limit = 4, cancellationCheckpoint = {})

        budget.visit(values, visited::add)

        assertEquals(listOf(0, 1, 2, 3), visited)
        assertEquals(4, values.nextCalls)
    }

    @Test
    fun `Git status visit budget is shared across changed and unversioned inputs`() {
        val changed = CountingIterable(size = 3)
        val unversioned = CountingIterable()
        val visited = ArrayList<Int>()
        val budget = BoundedVisitBudget(limit = 5, cancellationCheckpoint = {})

        budget.visit(changed, visited::add)
        budget.visit(unversioned, visited::add)

        assertEquals(5, visited.size)
        assertEquals(3, changed.nextCalls)
        assertEquals(2, unversioned.nextCalls)
    }

    @Test
    fun `Git loader visit budget observes cancellation before requesting another value`() {
        val values = CountingIterable()
        var checkpoints = 0
        val budget =
            BoundedVisitBudget(limit = 100) {
                if (++checkpoints == 4) throw CancellationException("cancelled")
            }

        assertThrows(CancellationException::class.java) {
            budget.visit(values) {}
        }

        assertEquals(3, values.nextCalls)
    }

    @Test
    fun `Gradle model traversal is depth first and exact at a hostile visit bound`() {
        val children = CountingNodeIterable()
        val root = TestNode(0, children)
        val visited = ArrayList<Int>()

        val visitCount =
            visitBoundedDepthFirst(
                roots = listOf(root),
                maxVisited = 6,
                cancellationCheckpoint = {},
                children = TestNode::children,
            ) { node ->
                visited.add(node.id)
                true
            }

        assertEquals(6, visitCount)
        assertEquals(listOf(0, 1, 2, 3, 4, 5), visited)
        assertEquals(5, children.nextCalls)
    }

    @Test
    fun `Gradle model traversal propagates cancellation without further discovery`() {
        val children = CountingNodeIterable()
        var checkpoints = 0

        assertThrows(CancellationException::class.java) {
            visitBoundedDepthFirst(
                roots = listOf(TestNode(0, children)),
                maxVisited = 100,
                cancellationCheckpoint = {
                    if (++checkpoints == 5) throw CancellationException("cancelled")
                },
                children = TestNode::children,
            ) { true }
        }

        assertEquals(2, children.nextCalls)
    }

    @Test
    fun `relative completion paths use shell separators and preserve parent navigation`() {
        val base = Path.of("project", "module").toAbsolutePath()

        assertEquals("../shared/File.kt", toRelativeCompletionPath(base, base.resolve("../shared/File.kt")))
        assertEquals("", toRelativeCompletionPath(base, base))
    }

    private class CountingIterable(
        private val size: Int? = null,
    ) : Iterable<Int> {
        var nextCalls: Int = 0
            private set

        override fun iterator(): Iterator<Int> =
            object : Iterator<Int> {
                override fun hasNext(): Boolean = size == null || nextCalls < size

                override fun next(): Int {
                    check(hasNext())
                    return nextCalls++
                }
            }
    }

    private class CountingNodeIterable : Iterable<TestNode> {
        var nextCalls: Int = 0
            private set

        override fun iterator(): Iterator<TestNode> =
            object : Iterator<TestNode> {
                override fun hasNext(): Boolean = true

                override fun next(): TestNode = TestNode(++nextCalls, emptyList())
            }
    }

    private data class TestNode(
        val id: Int,
        val children: Iterable<TestNode>,
    )
}
