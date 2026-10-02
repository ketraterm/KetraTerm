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
package io.github.ketraterm.core.buffer

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.render.api.TerminalRenderCellFlags
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TerminalClusterCopyContractTest {
    @Test
    fun `frame sink supplies complete directly written clusters without a capacity guess`() {
        val buffer = TerminalBuffers.create(2, 1, 0)
        for (length in intArrayOf(33, 4097, 16385)) {
            val expected = IntArray(length) { if (it == 0) 'e'.code else 0x0301 }
            buffer.reset()
            buffer.writeCluster(expected, expected.size)
            var copied: IntArray? = null
            val codes = IntArray(2)
            val attributes = LongArray(2)
            val flags = IntArray(2)
            buffer.readRenderFrame { frame ->
                frame.copyLine(0, codes, attrWords = attributes, flags = flags, clusterDataSink = { col, data, offset, size ->
                    assertEquals(0, col)
                    // The sink receives the truthful length before allocating its owned copy.
                    copied = data.copyOfRange(offset, offset + size)
                })
            }
            assertContentEquals(expected, assertNotNull(copied))
            assertTrue(flags[0] and TerminalRenderCellFlags.CLUSTER != 0)
            assertEquals('e'.code, buffer.getLine(0).getCodepoint(0))
            assertEquals(0, buffer.getLine(0).readCluster(1, IntArray(0)))
        }
    }
}
