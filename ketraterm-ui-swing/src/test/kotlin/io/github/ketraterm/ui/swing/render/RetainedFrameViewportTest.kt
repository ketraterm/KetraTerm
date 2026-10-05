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
package io.github.ketraterm.ui.swing.render

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.render.cache.TerminalRenderCache
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RetainedFrameViewportTest {
    @Test
    fun `presentation windows preserve cell planes identities cursor and absolute coordinates`() {
        val source = TerminalBuffers.create(10, 3, 2)
        source.writeText("aaaaaaaaaabbbbbbbbbbccccccccccddddddddddeeeeeeeeee")
        val full = TerminalRenderCache(10, 5)
        full.updateFrom(source, Int.MAX_VALUE, Int.MAX_VALUE)
        val viewport = RetainedFrameViewport(source)
        val cache = TerminalRenderCache(10, 5)
        for ((visible, offset, requested, first, count) in listOf(
            listOf(1, 0, 1, 4, 1),
            listOf(2, 1, 3, 2, 3),
            listOf(1, Int.MAX_VALUE, 1, 0, 1),
            listOf(8, Int.MAX_VALUE, Int.MAX_VALUE, 0, 5),
            listOf(2, Int.MIN_VALUE, 2, 3, 2),
        )) {
            viewport.visibleRows = visible
            cache.updateFrom(viewport, offset, requested)
            assertEquals(count, cache.rows)
            assertEquals(10, cache.columns)
            assertEquals(full.discardedCount + first, cache.discardedCount + cache.historySize - cache.scrollbackOffset)
            assertArrayEquals(full.codeWords.copyOfRange(first * 10, (first + count) * 10), cache.codeWords.copyOf(count * 10))
            assertArrayEquals(full.flags.copyOfRange(first * 10, (first + count) * 10), cache.flags.copyOf(count * 10))
            assertArrayEquals(full.attrWords.copyOfRange(first * 10, (first + count) * 10), cache.attrWords.copyOf(count * 10))
            assertArrayEquals(full.lineIds.copyOfRange(first, first + count), cache.lineIds.copyOf(count))
            assertEquals(full.frameGeneration, cache.frameGeneration)
            assertEquals(full.structureGeneration, cache.structureGeneration)
            assertSame(full.palette, cache.palette)
            assertEquals(full.cursorRow - first, cache.cursorRow)
            assertEquals(full.cursorRow - first in 0 until count, cache.cursorVisible)
        }
        val after = TerminalRenderCache(10, 5)
        after.updateFrom(source, Int.MAX_VALUE, Int.MAX_VALUE)
        assertArrayEquals(full.codeWords, after.codeWords)
        assertArrayEquals(full.lineIds, after.lineIds)
        assertEquals(full.frameGeneration, after.frameGeneration)
    }

    @Test
    fun `borrowed projection recovers after consumer failure and rejects nested reads`() {
        val source = TerminalBuffers.create(10, 3)
        source.writeText("界e\u0301")
        val viewport = RetainedFrameViewport(source)
        viewport.visibleRows = 3
        val failure = IllegalStateException("consumer failed")
        assertSame(
            failure,
            assertThrows(IllegalStateException::class.java) {
                viewport.readRenderFrame { throw failure }
            },
        )
        viewport.readRenderFrame { frame ->
            val identity = frame.lineId(0)
            assertThrows(IllegalStateException::class.java) { viewport.readRenderFrame {} }
            assertEquals(identity, frame.lineId(0))
            assertEquals(10, frame.columns)
        }
        val direct = TerminalRenderCache(10, 3).also { it.updateFrom(source) }
        val projected = TerminalRenderCache(10, 3).also { it.updateFrom(viewport) }
        assertArrayEquals(direct.codeWords, projected.codeWords)
        assertArrayEquals(direct.flags, projected.flags)
        viewport.visibleRows = 0
        assertThrows(IllegalArgumentException::class.java) { viewport.readRenderFrame {} }
    }
}
