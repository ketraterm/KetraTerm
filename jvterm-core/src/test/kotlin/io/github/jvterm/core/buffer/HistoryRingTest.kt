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
package io.github.jvterm.core.buffer

import io.github.jvterm.core.model.Line
import io.github.jvterm.core.store.ClusterStore
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

@DisplayName("HistoryRing Buffer Storage")
class HistoryRingTest {
    // Helper factory to create distinct lines for identity testing
    private fun createLineFactory(width: Int = 10): () -> Line = { Line(width, ClusterStore()) }

    @Nested
    @DisplayName("Initialization")
    inner class InitializationTests {
        @Test
        @DisplayName("Pre-allocates Line objects immediately")
        fun testPreAllocation() {
            assertDoesNotThrow {
                HistoryRing(10, createLineFactory())
            }
        }

        @Test
        @DisplayName("Starts empty with correct capacity")
        fun testInitialState() {
            val capacity = 5
            val ring = HistoryRing(capacity, createLineFactory())

            assertEquals(0, ring.size, "Should start empty")
            assertEquals(capacity, ring.capacity, "Capacity should match constructor")
        }
    }

    @Nested
    @DisplayName("Push & Fill Logic (Pre-Capacity)")
    inner class PushTests {
        @Test
        @DisplayName("Push increases size up to capacity")
        fun testPushIncreasesSize() {
            val capacity = 3
            val ring = HistoryRing(capacity, createLineFactory())

            ring.push()
            assertEquals(1, ring.size)

            ring.push()
            assertEquals(2, ring.size)

            ring.push()
            assertEquals(3, ring.size)

            // Next push should NOT increase size
            ring.push()
            assertEquals(3, ring.size, "Size should not exceed capacity")
        }

        @Test
        @DisplayName("Get returns lines in logical order (Oldest -> Newest)")
        fun testLogicalOrder() {
            val ring = HistoryRing(5, createLineFactory())

            // Push 3 lines and mark them
            val l1 = ring.push()
            l1.setCell(0, 'A'.code, 0)
            val l2 = ring.push()
            l2.setCell(0, 'B'.code, 0)
            val l3 = ring.push()
            l3.setCell(0, 'C'.code, 0)

            // Expect logical index 0 = Oldest ('A')
            assertSame(l1, ring[0], "Index 0 should be the first pushed line")
            assertSame(l2, ring[1], "Index 1 should be the second pushed line")
            assertSame(l3, ring[2], "Index 2 should be the third pushed line")
        }
    }

    @Nested
    @DisplayName("Ring Rollover & Recycling (Post-Capacity)")
    inner class RolloverTests {
        @Test
        @DisplayName("Pushing when full overwrites oldest (FIFO)")
        fun testOverwriteOldest() {
            val capacity = 3
            val ring = HistoryRing(capacity, createLineFactory())

            // Fill buffer: [A, B, C]
            val a = ring.push()
            a.setCell(0, 'A'.code, 0)
            val b = ring.push()
            b.setCell(0, 'B'.code, 0)
            val c = ring.push()
            c.setCell(0, 'C'.code, 0)

            // Push D. Should overwrite A.
            // New state logical: [B, C, D]
            val d = ring.push()
            d.setCell(0, 'D'.code, 0)

            assertEquals(3, ring.size, "Size should remain at capacity")

            // Check logical indices
            assertSame(b, ring[0], "Index 0 should now be B (oldest)")
            assertSame(c, ring[1], "Index 1 should now be C")
            assertSame(d, ring[2], "Index 2 should now be D (newest)")
        }

        @Test
        @DisplayName("Recycles existing object instances when full")
        fun testObjectRecycling() {
            val ring = HistoryRing(1, createLineFactory())

            val line1 = ring.push()
            val line2 = ring.push()

            // Since capacity is 1, the second push should return the EXACT same instance as line1
            assertSame(line1, line2, "Should reuse the Line object instance to avoid GC churn")
        }

        @Test
        @DisplayName("Complex wrapping scenario")
        fun testComplexWrap() {
            val ring = HistoryRing(3, createLineFactory())

            // Push 0, 1, 2. Ring: [0, 1, 2]. Head: 0.
            repeat(3) { i -> ring.push().setCell(0, i, 0) }

            // Push 3, 4. Ring logical: [2, 3, 4].
            ring.push().setCell(0, 3, 0)
            ring.push().setCell(0, 4, 0)

            assertEquals(2, ring[0].getCodepoint(0), "After wrap, index 0 should be the third pushed line (2)")
            assertEquals(3, ring[1].getCodepoint(0), "After wrap, index 1 should be the second pushed line (3)")
            assertEquals(4, ring[2].getCodepoint(0), "After multiple wraps, logical order should be maintained")
        }
    }

    @Nested
    @DisplayName("Boundary Conditions & Validation")
    inner class BoundaryTests {
        @Test
        @DisplayName("Accessing empty ring throws exception")
        fun testEmptyAccess() {
            val ring = HistoryRing(5, createLineFactory())
            assertThrows<IndexOutOfBoundsException> {
                ring[0]
            }
        }

        @ParameterizedTest
        @ValueSource(ints = [-1, 3, 100])
        fun testOutOfBoundsAccess(index: Int) {
            val ring = HistoryRing(3, createLineFactory())
            ring.push()
            ring.push()
            ring.push()
            // Size is 3. Valid indices: 0, 1, 2.

            val ex =
                assertThrows<IndexOutOfBoundsException> {
                    ring[index]
                }
            assertTrue(ex.message!!.contains("out of bounds"), "Message should describe error")
        }

        @Test
        @DisplayName("Off-by-one check: ring[size] should throw")
        fun testStrictUpperBound() {
            val ring = HistoryRing(5, createLineFactory())
            ring.push() // Size 1. Index 0 is valid.

            assertThrows<IndexOutOfBoundsException>("Accessing index=size must throw") {
                ring[1] // Index 1 is out of bounds for size 1
            }
        }
    }

    @Nested
    @DisplayName("Clear Operation")
    inner class ClearTests {
        @Test
        @DisplayName("Clear resets size but keeps capacity")
        fun testClear() {
            val ring = HistoryRing(5, createLineFactory())
            repeat(5) { ring.push() }

            assertEquals(5, ring.size)

            ring.clear()

            assertEquals(0, ring.size, "Size should be 0 after clear")
            assertEquals(5, ring.capacity, "Capacity should persist")

            assertThrows<IndexOutOfBoundsException> { ring[0] }
        }

        @Test
        @DisplayName("Can push after clear")
        fun testPushAfterClear() {
            val ring = HistoryRing(2, createLineFactory())
            ring.push()
            ring.clear()

            val newLine = ring.push()
            newLine.setCell(0, 99, 0)

            assertEquals(1, ring.size, "Size should be 1 after pushing post-clear")
            assertEquals(99, ring[0].getCodepoint(0), "Should be able to push and access new line after clear")
        }
    }

    @Nested
    @DisplayName("rotateUp Operation")
    inner class RotateUpTests {
        @Test
        @DisplayName("rotateUp with single-line region")
        fun testRotateUpSingleLine() {
            val ring = HistoryRing(3, createLineFactory())
            val l0 = ring.push()
            l0.setCell(0, 'A'.code, 0)
            val l1 = ring.push()
            l1.setCell(0, 'B'.code, 0)
            val l2 = ring.push()
            l2.setCell(0, 'C'.code, 0)

            ring.rotateUp(1, 1)

            // After rotating single-line region [1,1], nothing should change
            assertSame(l0, ring[0])
            assertSame(l1, ring[1])
            assertSame(l2, ring[2])
        }

        @Test
        @DisplayName("rotateUp with two-line region")
        fun testRotateUpTwoLines() {
            val ring = HistoryRing(4, createLineFactory())
            val l0 = ring.push()
            l0.setCell(0, 'A'.code, 0)
            val l1 = ring.push()
            l1.setCell(0, 'B'.code, 0)
            val l2 = ring.push()
            l2.setCell(0, 'C'.code, 0)
            val l3 = ring.push()
            l3.setCell(0, 'D'.code, 0)

            // Rotate up [1, 2]: l2 moves to l1, l1 moves to position 2
            ring.rotateUp(1, 2)

            assertSame(l0, ring[0], "Line at 0 should remain unchanged")
            assertSame(l2, ring[1], "Line at 1 should be from old position 2")
            assertSame(l1, ring[2], "Line at 2 should be from old position 1")
            assertSame(l3, ring[3], "Line at 3 should remain unchanged")
        }

        @Test
        @DisplayName("rotateUp full range moves oldest to newest")
        fun testRotateUpFullRange() {
            val ring = HistoryRing(3, createLineFactory())
            val l0 = ring.push()
            l0.setCell(0, 'A'.code, 0)
            val l1 = ring.push()
            l1.setCell(0, 'B'.code, 0)
            val l2 = ring.push()
            l2.setCell(0, 'C'.code, 0)

            ring.rotateUp(0, 2)

            assertSame(l1, ring[0], "Index 0 should now have old l1")
            assertSame(l2, ring[1], "Index 1 should now have old l2")
            assertSame(l0, ring[2], "Index 2 should now have old l0")
        }

        @Test
        @DisplayName("rotateUp after ring wrap maintains order")
        fun testRotateUpAfterWrap() {
            val ring = HistoryRing(3, createLineFactory())
            repeat(3) { i -> ring.push().setCell(0, i, 0) }
            // Push one more to wrap: [1, 2, 3] logically
            ring.push().setCell(0, 3, 0)

            assertEquals(1, ring[0].getCodepoint(0))
            assertEquals(2, ring[1].getCodepoint(0))
            assertEquals(3, ring[2].getCodepoint(0))

            ring.rotateUp(0, 2)

            assertEquals(2, ring[0].getCodepoint(0))
            assertEquals(3, ring[1].getCodepoint(0))
            assertEquals(1, ring[2].getCodepoint(0))
        }

        @Test
        @DisplayName("rotateUp three times cycles full range")
        fun testRotateUpMultiple() {
            val ring = HistoryRing(3, createLineFactory())
            val l0 = ring.push()
            l0.setCell(0, 'A'.code, 0)
            val l1 = ring.push()
            l1.setCell(0, 'B'.code, 0)
            val l2 = ring.push()
            l2.setCell(0, 'C'.code, 0)

            ring.rotateUp(0, 2)
            ring.rotateUp(0, 2)
            ring.rotateUp(0, 2)

            // After 3 rotations on 3-element range, should return to original
            assertSame(l0, ring[0])
            assertSame(l1, ring[1])
            assertSame(l2, ring[2])
        }
    }

    @Nested
    @DisplayName("rotateDown Operation")
    inner class RotateDownTests {
        @Test
        @DisplayName("rotateDown with single-line region")
        fun testRotateDownSingleLine() {
            val ring = HistoryRing(3, createLineFactory())
            val l0 = ring.push()
            l0.setCell(0, 'A'.code, 0)
            val l1 = ring.push()
            l1.setCell(0, 'B'.code, 0)
            val l2 = ring.push()
            l2.setCell(0, 'C'.code, 0)

            ring.rotateDown(1, 1)

            // After rotating single-line region [1,1], nothing should change
            assertSame(l0, ring[0])
            assertSame(l1, ring[1])
            assertSame(l2, ring[2])
        }

        @Test
        @DisplayName("rotateDown with two-line region")
        fun testRotateDownTwoLines() {
            val ring = HistoryRing(4, createLineFactory())
            val l0 = ring.push()
            l0.setCell(0, 'A'.code, 0)
            val l1 = ring.push()
            l1.setCell(0, 'B'.code, 0)
            val l2 = ring.push()
            l2.setCell(0, 'C'.code, 0)
            val l3 = ring.push()
            l3.setCell(0, 'D'.code, 0)

            // Rotate down [1, 2]: l1 moves to l2, l2 moves to position 1
            ring.rotateDown(1, 2)

            assertSame(l0, ring[0], "Line at 0 should remain unchanged")
            assertSame(l2, ring[1], "Line at 1 should be from old position 2")
            assertSame(l1, ring[2], "Line at 2 should be from old position 1")
            assertSame(l3, ring[3], "Line at 3 should remain unchanged")
        }

        @Test
        @DisplayName("rotateDown full range moves newest to oldest")
        fun testRotateDownFullRange() {
            val ring = HistoryRing(3, createLineFactory())
            val l0 = ring.push()
            l0.setCell(0, 'A'.code, 0)
            val l1 = ring.push()
            l1.setCell(0, 'B'.code, 0)
            val l2 = ring.push()
            l2.setCell(0, 'C'.code, 0)

            ring.rotateDown(0, 2)

            assertSame(l2, ring[0], "Index 0 should now have old l2")
            assertSame(l0, ring[1], "Index 1 should now have old l0")
            assertSame(l1, ring[2], "Index 2 should now have old l1")
        }

        @Test
        @DisplayName("rotateDown after ring wrap maintains order")
        fun testRotateDownAfterWrap() {
            val ring = HistoryRing(3, createLineFactory())
            repeat(3) { i -> ring.push().setCell(0, i, 0) }
            ring.push().setCell(0, 3, 0)

            assertEquals(1, ring[0].getCodepoint(0))
            assertEquals(2, ring[1].getCodepoint(0))
            assertEquals(3, ring[2].getCodepoint(0))

            ring.rotateDown(0, 2)

            assertEquals(3, ring[0].getCodepoint(0))
            assertEquals(1, ring[1].getCodepoint(0))
            assertEquals(2, ring[2].getCodepoint(0))
        }

        @Test
        @DisplayName("rotateDown multiple times cycles through")
        fun testRotateDownMultiple() {
            val ring = HistoryRing(3, createLineFactory())
            val l0 = ring.push()
            l0.setCell(0, 'A'.code, 0)
            val l1 = ring.push()
            l1.setCell(0, 'B'.code, 0)
            val l2 = ring.push()
            l2.setCell(0, 'C'.code, 0)

            ring.rotateDown(0, 2)
            ring.rotateDown(0, 2)

            assertSame(l1, ring[0])
            assertSame(l2, ring[1])
            assertSame(l0, ring[2])
        }
    }

    @Nested
    @DisplayName("rotateUp and rotateDown symmetry")
    inner class RotateSymmetryTests {
        @Test
        @DisplayName("rotateUp then rotateDown returns to original")
        fun testRotateUpThenDown() {
            val ring = HistoryRing(4, createLineFactory())
            val l0 = ring.push()
            val l1 = ring.push()
            val l2 = ring.push()
            val l3 = ring.push()

            ring.rotateUp(0, 3)
            ring.rotateDown(0, 3)

            assertSame(l0, ring[0])
            assertSame(l1, ring[1])
            assertSame(l2, ring[2])
            assertSame(l3, ring[3])
        }

        @Test
        @DisplayName("rotateDown then rotateUp returns to original")
        fun testRotateDownThenUp() {
            val ring = HistoryRing(4, createLineFactory())
            val l0 = ring.push()
            val l1 = ring.push()
            val l2 = ring.push()
            val l3 = ring.push()

            ring.rotateDown(0, 3)
            ring.rotateUp(0, 3)

            assertSame(l0, ring[0])
            assertSame(l1, ring[1])
            assertSame(l2, ring[2])
            assertSame(l3, ring[3])
        }
    }

    @Nested
    @DisplayName("Complex scenarios")
    inner class ComplexScenarios {
        @Test
        @DisplayName("Multiple operations interleaved")
        fun testMultipleOperationsInterleaved() {
            val ring = HistoryRing(5, createLineFactory())

            // Build [0, 1, 2, 3, 4]
            repeat(5) { i -> ring.push().setCell(0, i, 0) }

            // Rotate up [1, 3]
            ring.rotateUp(1, 3)
            // Expected: [0, 2, 3, 1, 4]
            assertEquals(0, ring[0].getCodepoint(0))
            assertEquals(2, ring[1].getCodepoint(0))
            assertEquals(3, ring[2].getCodepoint(0))
            assertEquals(1, ring[3].getCodepoint(0))
            assertEquals(4, ring[4].getCodepoint(0))

            // Rotate down [0, 2]
            ring.rotateDown(0, 2)
            // Expected: [3, 0, 2, 1, 4]
            assertEquals(3, ring[0].getCodepoint(0))
            assertEquals(0, ring[1].getCodepoint(0))
            assertEquals(2, ring[2].getCodepoint(0))
            assertEquals(1, ring[3].getCodepoint(0))
            assertEquals(4, ring[4].getCodepoint(0))
        }

        @Test
        @DisplayName("Push after rotate maintains consistency")
        fun testPushAfterRotate() {
            val ring = HistoryRing(3, createLineFactory())
            val l0 = ring.push()
            l0.setCell(0, 'A'.code, 0)
            val l1 = ring.push()
            l1.setCell(0, 'B'.code, 0)
            val l2 = ring.push()
            l2.setCell(0, 'C'.code, 0)

            // After rotateUp(0,2): [l1, l2, l0]
            ring.rotateUp(0, 2)
            val l3 = ring.push()
            l3.setCell(0, 'D'.code, 0)

            // Push when full: l1 is recycled, becomes l3
            // Result: [l2, l0, l3]
            assertSame(l2, ring[0])
            assertSame(l0, ring[1])
            assertSame(l3, ring[2])
            assertEquals(3, ring.size)
        }

        @Test
        @DisplayName("Clear after rotate resets state correctly")
        fun testClearAfterRotate() {
            val ring = HistoryRing(3, createLineFactory())
            repeat(3) { i -> ring.push().setCell(0, i, 0) }

            ring.rotateUp(0, 2)
            ring.clear()

            assertEquals(0, ring.size)
            assertThrows<IndexOutOfBoundsException> { ring[0] }
        }

        @Test
        @DisplayName("Capacity 1 edge case with rotations")
        fun testCapacityOneRotation() {
            val ring = HistoryRing(1, createLineFactory())
            val l0 = ring.push()

            // Rotating a single-element ring should be safe
            ring.rotateUp(0, 0)
            ring.rotateDown(0, 0)

            assertEquals(1, ring.size)
            assertSame(l0, ring[0])
        }
    }
}
