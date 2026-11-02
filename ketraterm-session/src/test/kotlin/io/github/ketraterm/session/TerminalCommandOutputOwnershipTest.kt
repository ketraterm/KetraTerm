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
package io.github.ketraterm.session

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.render.cache.TerminalRenderRowSnapshot
import kotlin.test.*

class TerminalCommandOutputOwnershipTest {
    @Test
    fun `prompt retention has an independent byte bound and releases snapshots on clear and eviction`() {
        var prompt: TerminalRenderRowSnapshot? = null
        TerminalBuffers.create(4096, 1).readRenderFrame { prompt = TerminalRenderRowSnapshot.copyOf(it, 0) }
        val captured = requireNotNull(prompt)
        val state = TerminalShellIntegrationState(capacity = 128)
        state.recordCommandStart(1, true, "first", null, captured)
        val first = state.latestCommandRecordId()
        repeat(100) { index -> state.recordCommandStart(index + 2L, true, "next", null, captured) }
        assertNull(state.commandPrompt(first))
        val latest = state.latestCommandRecordId()
        assertSame(captured, state.commandPrompt(latest))
        state.clear()
        assertNull(state.commandPrompt(latest))
        state.recordCommandStart(200, true, "new", null, captured)
        assertSame(captured, state.commandPrompt(state.latestCommandRecordId()))
        val bounded = TerminalShellIntegrationState(capacity = 1)
        bounded.recordCommandStart(1, true, "old", null, captured)
        val evicted = bounded.latestCommandRecordId()
        bounded.recordCommandStart(2, true, "new", null, captured)
        assertNull(bounded.commandPrompt(evicted))
        assertSame(captured, bounded.commandPrompt(bounded.latestCommandRecordId()))
    }

    @Test
    fun `running output excludes input and does not change selection or viewport ownership`() {
        val state = TerminalShellIntegrationState()
        state.recordPromptStart(10)
        state.recordPromptEnd(11)
        state.recordCommandStart(12, includeLine = false, commandText = "build")
        val id = state.latestCommandRecordId()
        for (line in longArrayOf(Long.MIN_VALUE, -1, 0, 10, 11, 12)) {
            assertEquals(0, state.commandOutputRecordIdAtLine(line))
        }
        assertEquals(id, state.commandOutputRecordIdAtLine(13))
        assertEquals(id, state.commandOutputRecordIdAtLine(Long.MAX_VALUE))
        assertEquals(0, state.commandRecordIdAtLine(13))
        assertFalse(state.copyCommandOutputRange(id, LongArray(TerminalShellIntegrationCommandOutputRange.REQUIRED_LONGS)))
        state.recordCommandFinished(14, 0)
        assertEquals(id, state.commandOutputRecordIdAtLine(13))
        assertEquals(id, state.commandOutputRecordIdAtLine(14))
        assertEquals(0, state.commandOutputRecordIdAtLine(15))
        state.recordPromptStart(14)
        assertEquals(0, state.commandOutputRecordIdAtLine(14))
    }

    @Test
    fun `inclusive orphan output owns its start and duplicate physical line identities`() {
        val state = TerminalShellIntegrationState()
        state.recordCommandStart(20, includeLine = true, commandText = "build")
        val id = state.latestCommandRecordId()
        assertContentEquals(intArrayOf(id, id, id), longArrayOf(20, 20, 21).map { state.commandOutputRecordIdAtLine(it) }.toIntArray())
        state.recordCommandFinished(22, null)
        assertEquals(id, state.commandOutputRecordIdAtLine(20))
        assertEquals(id, state.commandOutputRecordIdAtLine(22))
    }

    @Test
    fun `new prompt and replacement command abandon open output`() {
        val state = TerminalShellIntegrationState()
        state.recordCommandStart(10, true)
        state.recordPromptStart(20)
        assertEquals(0, state.commandOutputRecordIdAtLine(11))
        assertEquals(0, state.commandOutputRecordIdAtLine(20))
        state.recordCommandStart(21, true)
        val abandoned = state.latestCommandRecordId()
        state.recordCommandStart(30, false)
        assertEquals(0, state.commandOutputRecordIdAtLine(22))
        val active = state.commandOutputRecordIdAtLine(31)
        assertNotEquals(0, active)
        assertNotEquals(abandoned, active)
        state.clear()
        assertEquals(0, state.commandOutputRecordIdAtLine(31))
    }

    @Test
    fun `silent exclusive reversed and evicted ranges own no output`() {
        val state = TerminalShellIntegrationState(capacity = 1)
        state.recordCommandStart(10, false)
        state.recordCommandFinished(10, 0)
        assertEquals(0, state.commandOutputRecordIdAtLine(10))
        state.recordCommandStart(20, true)
        state.recordCommandFinished(19, 1)
        assertEquals(0, state.commandOutputRecordIdAtLine(20))
        state.recordPromptStart(30)
        assertEquals(0, state.commandOutputRecordIdAtLine(10))
        assertEquals(0, state.commandOutputRecordIdAtLine(30))
    }
}
