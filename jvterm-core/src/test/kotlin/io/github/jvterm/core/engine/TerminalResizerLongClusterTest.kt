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
package io.github.jvterm.core.engine

import io.github.jvterm.core.state.TerminalState
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TerminalResizerLongClusterTest {
    @Test
    fun `resize_longCluster_over16Codepoints_followsDefinedPolicy`() {
        val state = TerminalState(initialWidth = 8, initialHeight = 2, maxHistory = 0)
        val cluster = IntArray(17) { 0x1000 + it }
        val line = state.primaryBuffer.ring[state.resolveRingIndex(1)]
        line.setCluster(0, cluster, cluster.size, 7)
        state.cursor.row = 1

        assertDoesNotThrow {
            TerminalResizer.resizeBuffer(
                buffer = state.primaryBuffer,
                oldWidth = 8,
                oldHeight = 2,
                newWidth = 6,
                newHeight = 2,
            )
        }

        val visibleTop = (state.primaryBuffer.ring.size - 2).coerceAtLeast(0)
        val dest = IntArray(17)
        var found = false
        var written = 0

        for (row in visibleTop until state.primaryBuffer.ring.size) {
            val candidate = state.primaryBuffer.ring[row]
            if (candidate.isCluster(0)) {
                written = candidate.readCluster(0, dest)
                found = true
                break
            }
        }

        assertAll(
            { assertTrue(found, "The long cluster must survive resize somewhere in the visible viewport") },
            { assertEquals(17, written) },
            { assertEquals(cluster.toList(), dest.take(written)) },
        )
    }
}
