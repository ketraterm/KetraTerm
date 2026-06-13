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
package io.github.jvterm.core.state

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ScreenBufferLifecycleTest {
    @Test
    fun `clearGrid_withHistoryBacklog_doesNotLeakClusterHandles`() {
        val buffer = ScreenBuffer(initialWidth = 4, initialHeight = 2, maxHistory = 3)
        buffer.clearGrid(penAttr = 0, viewportHeight = 2)
        repeat(3) { buffer.ring.push().clear(0) }

        val backlogLine = buffer.ring[4]
        backlogLine.setCluster(0, intArrayOf('A'.code, 0x0301), 2, 0)
        val originalHandle = backlogLine.rawCodepoint(0)

        buffer.clearGrid(penAttr = 0, viewportHeight = 2)
        buffer.ring[0].setCluster(0, intArrayOf('B'.code, 0x0301), 2, 0)

        assertEquals(
            originalHandle,
            buffer.ring[0].rawCodepoint(0),
            "Clearing the grid must release cluster slots from discarded history lines",
        )
    }
}
