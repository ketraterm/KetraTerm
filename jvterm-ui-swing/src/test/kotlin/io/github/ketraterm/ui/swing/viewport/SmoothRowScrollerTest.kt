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

import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SmoothRowScrollerTest {
    @Test
    fun `precise input does not move viewport before a whole row is accumulated`() {
        SwingUtilities.invokeAndWait {
            val host = RecordingHost()
            val scroller = SmoothRowScroller(host)

            repeat(3) {
                assertTrue(scroller.scrollByPreciseRows(0.25))
            }
            assertEquals(0, host.applyCount)

            assertTrue(scroller.scrollByPreciseRows(0.25))
            scroller.finish()

            assertEquals(1.0, host.offset)
            assertTrue(host.lastScrollComplete)
        }
    }

    @Test
    fun `direct scrollbar row cancels animation and applies without lag`() {
        SwingUtilities.invokeAndWait {
            val host = RecordingHost()
            val scroller = SmoothRowScroller(host)
            assertTrue(scroller.scrollByRows(4))

            assertTrue(scroller.jumpToRow(2))

            assertEquals(2.0, host.offset)
            assertFalse(host.lastScrollComplete)
        }
    }

    private class RecordingHost : SmoothRowScrollHost {
        var offset = 0.0
        var applyCount = 0
        var lastScrollComplete = false

        override fun rowScrollOffset(): Double = offset

        override fun rowScrollHistorySize(): Int = 10

        override fun applyRowScrollOffset(
            offsetRows: Double,
            scrollComplete: Boolean,
        ): Boolean {
            val changed = offsetRows != offset
            offset = offsetRows
            applyCount++
            lastScrollComplete = scrollComplete
            return changed
        }
    }
}
