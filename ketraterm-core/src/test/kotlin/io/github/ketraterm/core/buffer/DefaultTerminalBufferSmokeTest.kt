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
import io.github.ketraterm.render.api.TerminalRenderFrameReader
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DefaultTerminalBufferSmokeTest {
    @Test
    fun `factory exposes a working buffer api`() {
        val buffer = TerminalBuffers.create(4, 3, 2)

        buffer.writeText("AB")
        buffer.resize(2, 2)
        buffer.reset()

        assertAll(
            { assertEquals(2, buffer.width) },
            { assertEquals(2, buffer.height) },
            { assertEquals(List(buffer.height) { "" }.joinToString("\n"), buffer.getScreenAsString()) },
        )
    }

    @Test
    fun `constructor and resize validation still reject non-positive dimensions`() {
        assertThrows<IllegalArgumentException> { DefaultTerminalBuffer(0, 1) }
        assertThrows<IllegalArgumentException> { TerminalBuffers.create(1, 0) }
    }

    @Test
    fun `factory rejects negative and overflowing retained line capacities before allocation`() {
        for (history in listOf(-1, Int.MIN_VALUE)) {
            assertThrows<IllegalArgumentException> { TerminalBuffers.create(1, 4, history) }
        }
        for ((height, history) in listOf(1 to Int.MAX_VALUE, Int.MAX_VALUE to 1, Int.MAX_VALUE to Int.MAX_VALUE)) {
            assertThrows<IllegalArgumentException> { TerminalBuffers.create(1, height, history) }
        }
    }

    @Test
    fun `factory permits zero history and exposes primitive render frames`() {
        val buffer = TerminalBuffers.create(2, 1, 0)
        buffer.writeText("A")
        buffer.newLine()

        assertEquals(0, buffer.historySize)
        assertEquals("", buffer.getScreenAsString())
        (buffer as TerminalRenderFrameReader).readRenderFrame { frame ->
            assertEquals(2, frame.columns)
            assertEquals(1, frame.rows)
            assertEquals(0, frame.historySize)
        }
    }
}
