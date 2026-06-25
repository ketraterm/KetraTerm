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
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TerminalEraseTest {
    @Test
    fun `ed3_clearsScrollbackButPreservesViewport_ifTargetingXtermSemantics`() {
        val buffer = TerminalBuffers.create(width = 3, height = 2, maxHistory = 4)
        buffer.writeText("abc")
        buffer.newLine()
        buffer.writeText("def")
        buffer.newLine()
        buffer.writeText("ghi")

        val screenBefore = buffer.getScreenAsString()

        buffer.eraseScreenAndHistory()

        assertAll(
            { assertEquals(screenBefore, buffer.getScreenAsString(), "ED 3 should preserve the visible viewport") },
            { assertEquals(0, buffer.historySize, "ED 3 should clear scrollback history") },
        )
    }

    @Test
    fun `ed3 preserves positive visible line ids while clearing history`() {
        val buffer = TerminalBuffers.create(width = 3, height = 2, maxHistory = 4)
        val reader = buffer as TerminalRenderFrameReader
        buffer.writeText("abc")
        buffer.newLine()
        buffer.writeText("def")
        buffer.newLine()
        buffer.writeText("ghi")

        val before = lineIds(reader)

        buffer.eraseScreenAndHistory()

        val after = lineIds(reader)
        assertAll(
            { assertEquals(before.asList(), after.asList()) },
            { assertTrue(after.all { it > 0L }, "ED 3 must not expose unassigned render line ids") },
        )
    }

    @Test
    fun `ed2 followed by ed3 leaves fresh positive visible line ids`() {
        val buffer = TerminalBuffers.create(width = 3, height = 2, maxHistory = 4)
        val reader = buffer as TerminalRenderFrameReader
        buffer.writeText("abc")
        buffer.newLine()
        buffer.writeText("def")

        val before = lineIds(reader)

        buffer.eraseEntireScreen()
        buffer.eraseScreenAndHistory()

        val after = lineIds(reader)
        assertAll(
            { assertTrue(after.all { it > 0L }, "clear paths must leave every render row with a positive line id") },
            { assertNotEquals(before.asList(), after.asList(), "ED 2 must replace the visible logical lines before ED 3 preserves them") },
        )
    }

    private fun lineIds(reader: TerminalRenderFrameReader): LongArray {
        var ids = LongArray(0)
        reader.readRenderFrame { frame ->
            ids = LongArray(frame.rows)
            var row = 0
            while (row < frame.rows) {
                ids[row] = frame.lineId(row)
                row++
            }
        }
        return ids
    }
}
