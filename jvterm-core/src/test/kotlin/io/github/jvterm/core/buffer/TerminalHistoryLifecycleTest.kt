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

import io.github.jvterm.core.engine.MutationEngine
import io.github.jvterm.core.state.TerminalState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TerminalHistoryLifecycleTest {
    @Test
    fun `clearAllHistory_releasesClusterStoreSlots`() {
        val state = TerminalState(initialWidth = 4, initialHeight = 2, maxHistory = 3)
        val mutation = MutationEngine(state)

        repeat(3) { state.ring.push().clear(state.pen.currentAttr) }
        val leakedLine = state.ring[4]
        leakedLine.setCluster(0, intArrayOf('A'.code, 0x0301), 2, 0)
        val originalHandle = leakedLine.rawCodepoint(0)

        mutation.clearAllHistory()
        state.ring[0].setCluster(0, intArrayOf('B'.code, 0x0301), 2, 0)

        assertEquals(
            originalHandle,
            state.ring[0].rawCodepoint(0),
            "Clearing history must release all cluster slots so the next allocation can reuse them",
        )
    }

    @Test
    fun `reset_releasesAllPrimaryHistoryClusterSlots`() {
        val buffer = DefaultTerminalBuffer(initialWidth = 4, initialHeight = 2, maxHistory = 3)
        val state =
            DefaultTerminalBufferResizeTest().run {
                val componentsField = DefaultTerminalBuffer::class.java.getDeclaredField("components")
                componentsField.isAccessible = true
                val components = componentsField.get(buffer)
                val stateField = components.javaClass.getDeclaredField("state")
                stateField.isAccessible = true
                stateField.get(components) as TerminalState
            }

        repeat(3) {
            state.primaryBuffer.ring
                .push()
                .clear(state.pen.currentAttr)
        }
        val leakedLine = state.primaryBuffer.ring[4]
        leakedLine.setCluster(0, intArrayOf('C'.code, 0x0301), 2, 0)
        val originalHandle = leakedLine.rawCodepoint(0)

        buffer.reset()
        state.primaryBuffer.ring[0].setCluster(0, intArrayOf('D'.code, 0x0301), 2, 0)

        assertEquals(
            originalHandle,
            state.primaryBuffer.ring[0].rawCodepoint(0),
            "Reset must release cluster slots retained in primary scrollback",
        )
    }
}
