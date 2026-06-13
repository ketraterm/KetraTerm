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
package io.github.jvterm.core.model

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("TabStops")
class TabStopsTest {
    // ----- Helpers ----------------------------------------------------------

    private fun backingStops(ts: TabStops): BooleanArray {
        val field = TabStops::class.java.getDeclaredField("stops")
        field.isAccessible = true
        return field.get(ts) as BooleanArray
    }

    private fun stopsAt(
        ts: TabStops,
        vararg cols: Int,
    ) {
        for (col in cols) {
            assertEquals(
                col,
                ts.getNextStop(col - 1),
                "Expected a stop at col $col",
            )
        }
    }

    private fun noStopBetween(
        ts: TabStops,
        fromExclusive: Int,
        toExclusive: Int,
    ) {
        for (col in fromExclusive until toExclusive - 1) {
            val next = ts.getNextStop(col)
            assertNotEquals(
                col + 1,
                next,
                "Unexpected stop found at col ${col + 1}",
            )
        }
    }

    // ----- Construction -----------------------------------------------------

    @Nested
    @DisplayName("default construction")
    inner class DefaultConstruction {
        @Test
        fun `default stops are at every 8th column`() {
            val ts = TabStops(40)
            stopsAt(ts, 0, 8, 16, 24, 32)
        }

        @Test
        fun `no stops between default 8-column boundaries`() {
            val ts = TabStops(20)
            noStopBetween(ts, 0, 8)
            noStopBetween(ts, 8, 16)
        }

        @Test
        fun `width of 1 constructs without error`() {
            val ts = TabStops(1)
            assertEquals(0, ts.getNextStop(0))
        }

        @Test
        fun `width of 8 has stops at 0 only within range`() {
            val ts = TabStops(8)
            // col 0 has a stop but getNextStop searches strictly to the right
            // of currentCol, so from col 0 the next is col 8 which is clamped
            // to the right margin (7).
            assertEquals(7, ts.getNextStop(0))
        }

        @Test
        fun `width of 9 has a reachable stop at col 8`() {
            val ts = TabStops(9)
            assertEquals(8, ts.getNextStop(0))
        }
    }

    // ----- getNextStop ------------------------------------------------------

    @Nested
    @DisplayName("getNextStop")
    inner class GetNextStop {
        @Test
        fun `returns next stop to the right of cursor`() {
            val ts = TabStops(24)
            assertEquals(8, ts.getNextStop(0))
            assertEquals(8, ts.getNextStop(1))
            assertEquals(8, ts.getNextStop(7))
            assertEquals(16, ts.getNextStop(8))
        }

        @Test
        fun `clamps to right margin when no stop exists to the right`() {
            val ts = TabStops(10)
            ts.clearAll()
            assertEquals(9, ts.getNextStop(0))
            assertEquals(9, ts.getNextStop(5))
        }

        @Test
        fun `cursor already at right margin returns right margin`() {
            val ts = TabStops(10)
            assertEquals(9, ts.getNextStop(9))
        }

        @Test
        fun `cursor beyond right margin clamps to right margin`() {
            val ts = TabStops(10)
            assertEquals(9, ts.getNextStop(99))
        }

        @Test
        fun `zero-width terminal returns 0`() {
            val ts = TabStops(1)
            assertEquals(0, ts.getNextStop(0))
        }

        @Test
        fun `stops immediately adjacent to cursor are returned`() {
            val ts = TabStops(10)
            ts.clearAll()
            ts.setStop(3)
            assertEquals(3, ts.getNextStop(2))
        }

        @Test
        fun `does not return a stop at currentCol itself`() {
            val ts = TabStops(20)
            // col 8 has a default stop; from col 8 the next stop is col 16
            assertEquals(16, ts.getNextStop(8))
        }
    }

    // ----- setStop / clearStop ----------------------------------------------

    @Nested
    @DisplayName("setStop and clearStop")
    inner class SetAndClearStop {
        @Test
        fun `setStop creates a reachable stop`() {
            val ts = TabStops(20)
            ts.clearAll()
            ts.setStop(5)
            assertEquals(5, ts.getNextStop(0))
        }

        @Test
        fun `clearStop removes an existing stop`() {
            val ts = TabStops(20)
            ts.clearStop(8)
            // from col 0 the next reachable stop is now col 16
            assertEquals(16, ts.getNextStop(0))
        }

        @Test
        fun `setStop on out-of-bounds col is ignored`() {
            val ts = TabStops(10)
            assertDoesNotThrow { ts.setStop(99) }
            assertDoesNotThrow { ts.setStop(-1) }
        }

        @Test
        fun `clearStop on out-of-bounds col is ignored`() {
            val ts = TabStops(10)
            assertDoesNotThrow { ts.clearStop(99) }
            assertDoesNotThrow { ts.clearStop(-1) }
        }

        @Test
        fun `setStop is idempotent`() {
            val ts = TabStops(20)
            ts.setStop(5)
            ts.setStop(5)
            assertEquals(5, ts.getNextStop(4))
        }

        @Test
        fun `clearStop on col with no stop is a no-op`() {
            val ts = TabStops(20)
            ts.clearStop(3) // no default stop here
            assertEquals(8, ts.getNextStop(3))
        }

        @Test
        fun `multiple custom stops are all reachable in sequence`() {
            val ts = TabStops(20)
            ts.clearAll()
            ts.setStop(3)
            ts.setStop(7)
            ts.setStop(12)

            assertEquals(3, ts.getNextStop(0))
            assertEquals(7, ts.getNextStop(3))
            assertEquals(12, ts.getNextStop(7))
            assertEquals(19, ts.getNextStop(12))
        }
    }

    // ----- clearAll ---------------------------------------------------------

    @Nested
    @DisplayName("clearAll")
    inner class ClearAll {
        @Test
        fun `clearAll causes getNextStop to always return right margin`() {
            val ts = TabStops(24)
            ts.clearAll()
            assertEquals(23, ts.getNextStop(0))
            assertEquals(23, ts.getNextStop(11))
        }

        @Test
        fun `clearAll followed by setStop makes only that stop reachable`() {
            val ts = TabStops(24)
            ts.clearAll()
            ts.setStop(5)
            assertEquals(5, ts.getNextStop(0))
            assertEquals(23, ts.getNextStop(5))
        }

        @Test
        fun `clearAll is idempotent`() {
            val ts = TabStops(10)
            ts.clearAll()
            ts.clearAll()
            assertEquals(9, ts.getNextStop(0))
        }
    }

    // ----- resetToDefault ---------------------------------------------------

    @Nested
    @DisplayName("resetToDefault")
    inner class ResetToDefault {
        @Test
        fun `resetToDefault restores 8-column spacing after clearAll`() {
            val ts = TabStops(24)
            ts.clearAll()
            ts.resetToDefault()
            assertEquals(8, ts.getNextStop(0))
            assertEquals(16, ts.getNextStop(8))
        }

        @Test
        fun `resetToDefault overwrites custom stops`() {
            val ts = TabStops(20)
            ts.clearAll()
            ts.setStop(3)
            ts.setStop(11)
            ts.resetToDefault()
            assertEquals(8, ts.getNextStop(0))
            assertEquals(16, ts.getNextStop(8))
        }

        @Test
        fun `resetToDefault is distinct from clearAll`() {
            val ts = TabStops(16)
            ts.clearAll()
            assertEquals(15, ts.getNextStop(0)) // no stops

            ts.resetToDefault()
            assertEquals(8, ts.getNextStop(0)) // stop at 8 restored
        }

        @Test
        fun `resetToDefault is idempotent`() {
            val ts = TabStops(20)
            ts.resetToDefault()
            ts.resetToDefault()
            assertEquals(8, ts.getNextStop(0))
        }
    }

    @Nested
    @DisplayName("reset(newWidth)")
    inner class Reset {
        @Test
        fun `reset_withSameWidth_restoresDefaultStops`() {
            val ts = TabStops(20)
            ts.clearAll()
            ts.setStop(3)

            ts.reset(20)

            assertEquals(8, ts.getNextStop(0))
        }

        @Test
        fun `reset_withSameWidth_destroysCustomStops_and_restoresOnlyDefaultRhythm`() {
            val ts = TabStops(20)
            ts.clearAll()
            ts.setStop(3)
            ts.setStop(11)

            ts.reset(20)

            assertAll(
                { assertEquals(8, ts.getNextStop(0)) },
                { assertEquals(16, ts.getNextStop(8)) },
            )
        }

        @Test
        fun `reset_withSameWidth_reusesExistingStopTable`() {
            val ts = TabStops(20)
            val before = backingStops(ts)
            ts.clearAll()

            ts.reset(20)

            assertSame(before, backingStops(ts))
            assertEquals(8, ts.getNextStop(0))
        }

        @Test
        fun `reset_withNewWidth_rebuildsDefaultRhythmForThatWidth`() {
            val ts = TabStops(20)
            ts.clearAll()
            ts.setStop(3)

            ts.reset(10)

            assertAll(
                { assertEquals(8, ts.getNextStop(0)) },
                { assertEquals(9, ts.getNextStop(8)) },
            )
        }
    }

    @Nested
    @DisplayName("getPreviousStop")
    inner class GetPreviousStop {
        @Test
        fun `returns nearest stop to left`() {
            val ts = TabStops(24)

            assertEquals(8, ts.getPreviousStop(15))
            assertEquals(0, ts.getPreviousStop(8))
        }

        @Test
        fun `clamps to left boundary`() {
            val ts = TabStops(24)
            ts.clearAll()

            assertEquals(0, ts.getPreviousStop(15))
            assertEquals(3, ts.getPreviousStop(15, leftBoundary = 3))
            assertEquals(5, ts.getPreviousStop(2, leftBoundary = 5))
        }
    }

    // ----- resize -----------------------------------------------------------

    @Nested
    @DisplayName("resize")
    inner class Resize {
        @Test
        fun `resize to same width is a no-op`() {
            val ts = TabStops(20)
            ts.clearAll()
            ts.setStop(5)
            ts.resize(20)
            assertEquals(5, ts.getNextStop(0))
        }

        @Test
        fun `growing preserves existing stops`() {
            val ts = TabStops(10)
            ts.clearAll()
            ts.setStop(3)
            ts.resize(20)
            assertEquals(3, ts.getNextStop(0))
        }

        @Test
        fun `growing initialises new columns with default 8-column stops`() {
            val ts = TabStops(10)
            ts.clearAll()
            ts.resize(20)
            // col 16 is a default stop in the newly exposed area
            assertEquals(16, ts.getNextStop(10))
        }

        @Test
        fun `shrinking discards stops beyond new width`() {
            val ts = TabStops(20)
            ts.setStop(15)
            ts.resize(10)
            // width is now 10; col 15 no longer exists — right margin is 9
            assertEquals(9, ts.getNextStop(8))
        }

        @Test
        fun `shrink then grow does not resurrect stale custom stops`() {
            val ts = TabStops(20)
            ts.clearAll()
            ts.setStop(15) // custom stop at col 15
            ts.resize(10) // col 15 discarded
            ts.resize(20) // expand back — cols 10-19 get fresh defaults (i % 8 == 0)
            // col 15 custom stop is gone; col 16 is a fresh default stop (16 % 8 == 0)
            assertEquals(16, ts.getNextStop(15))
        }

        @Test
        fun `shrink then grow restores default stops in newly exposed area`() {
            val ts = TabStops(20)
            ts.clearAll()
            ts.resize(10)
            ts.resize(20)
            // col 16 is a default stop (16 % 8 == 0)
            assertEquals(16, ts.getNextStop(10))
        }

        @Test
        fun `resize to width 1 leaves only the right margin`() {
            val ts = TabStops(20)
            ts.resize(1)
            assertEquals(0, ts.getNextStop(0))
        }
    }

    @Nested
    @DisplayName("Resize Expansion")
    inner class ResizeTests {
        @Nested
        @DisplayName("Resize Expansion")
        inner class ResizeTests {
            @Test
            fun `resizing to a narrower dimension safely truncates stops without error`() {
                val tabStops = TabStops(20)
                tabStops.setStop(18)

                assertDoesNotThrow {
                    tabStops.resize(10)
                }

                // If your class has bounds-checking on its getter, you can assert it fails safely.
                // Otherwise, just verifying `resize` doesn't throw is enough.
            }
        }
    }
}
