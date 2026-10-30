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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TerminalShellIntegrationDurationTest {
    @Test
    fun `elapsed duration uses monotonic time despite wall clock adjustments`() {
        val clock = Clock()
        val state = clock.state()
        state.recordCommandStart(1, true)
        val id = state.latestCommandRecordId()
        clock.epochMillis = -10_000
        clock.nanos += 10_000_000_000
        state.recordCommandFinished(2, 0)

        assertEquals(10_000L, state.commandDurationMillis(id))
        assertEquals(-10_000L, state.commandMetadata(id)?.finishedAtEpochMillis)
    }

    @Test
    fun `duration truncates submillisecond fractions and includes zero duration`() {
        val clock = Clock()
        val state = clock.state()
        for ((elapsedNanos, expectedMillis) in listOf(0L to 0L, 999_999L to 0L, 1_000_000L to 1L, 9_999_999_999L to 9_999L)) {
            state.recordCommandStart(1, true)
            clock.nanos += elapsedNanos
            state.recordCommandFinished(2, null)
            assertEquals(expectedMillis, state.commandDurationMillis(state.latestCommandRecordId()))
        }
    }

    @Test
    fun `monotonic subtraction handles signed nanosecond wraparound`() {
        val clock = Clock(nanos = Long.MAX_VALUE - 499_999)
        val state = clock.state()
        state.recordCommandStart(1, true)
        clock.nanos = Long.MIN_VALUE + 500_000
        state.recordCommandFinished(2, 7)

        assertEquals(1L, state.commandDurationMillis(state.latestCommandRecordId()))
    }

    @Test
    fun `backwards monotonic source or unrepresentable interval leaves duration unknown`() {
        val clock = Clock(nanos = 10)
        val state = clock.state()
        state.recordCommandStart(1, true)
        clock.nanos = 9
        state.recordCommandFinished(2, 0)
        assertNull(state.commandDurationMillis(state.latestCommandRecordId()))

        clock.nanos = 0
        state.recordCommandStart(3, true)
        clock.nanos = Long.MIN_VALUE
        state.recordCommandFinished(4, 0)
        assertNull(state.commandDurationMillis(state.latestCommandRecordId()))
    }

    @Test
    fun `duration is absent for unknown prompt running and abandoned records`() {
        val clock = Clock()
        val state = clock.state()
        assertNull(state.commandDurationMillis(0))
        assertNull(state.commandDurationMillis(-1))
        assertNull(state.commandDurationMillis(Int.MAX_VALUE))
        state.recordPromptStart(1)
        assertNull(state.commandDurationMillis(1))
        state.recordCommandStart(1, true)
        val abandonedId = state.latestCommandRecordId()
        assertNull(state.commandDurationMillis(abandonedId))
        clock.nanos += 10_000_000_000
        state.recordPromptStart(2)
        state.recordCommandFinished(2, 0)
        assertNull(state.commandDurationMillis(abandonedId))
        assertEquals(TerminalShellIntegrationCommandLifecycle.ABANDONED, state.commandMetadata(abandonedId)?.lifecycle)
    }

    @Test
    fun `orphan command has duration and duplicate finish preserves original measurement`() {
        val clock = Clock()
        val state = clock.state()
        state.recordCommandFinished(1, 0)
        state.recordCommandStart(2, true)
        val id = state.latestCommandRecordId()
        clock.nanos += 3_000_000
        state.recordCommandFinished(3, null)
        clock.nanos += 9_000_000
        state.recordCommandFinished(4, 0)

        assertEquals(3L, state.commandDurationMillis(id))
        assertEquals(TerminalShellIntegrationCommandLifecycle.FINISHED_UNKNOWN, state.commandMetadata(id)?.lifecycle)
    }

    @Test
    fun `eviction preserves aligned durations and clear cannot expose reused slot data`() {
        val clock = Clock()
        val state = clock.state(capacity = 2)
        val ids = mutableListOf<Int>()
        for (durationMillis in 1L..3L) {
            state.recordCommandStart(durationMillis * 2, true)
            ids += state.latestCommandRecordId()
            clock.nanos += durationMillis * 1_000_000
            state.recordCommandFinished(durationMillis * 2 + 1, 0)
        }
        assertNull(state.commandDurationMillis(ids[0]))
        assertEquals(2L, state.commandDurationMillis(ids[1]))
        assertEquals(3L, state.commandDurationMillis(ids[2]))

        state.clear()
        assertNull(state.commandDurationMillis(ids[1]))
        assertNull(state.commandDurationMillis(ids[2]))
        state.recordCommandStart(20, true)
        val newId = state.latestCommandRecordId()
        assertNull(state.commandDurationMillis(newId))
        clock.nanos += 4_000_000
        state.recordCommandFinished(21, 0)
        assertEquals(4L, state.commandDurationMillis(newId))
    }

    @Test
    fun `completed duration is available during synchronous completion callback`() {
        val clock = Clock()
        val state = clock.state()
        val durations = mutableListOf<Long?>()
        state.addCommandFinishedListener { durations += state.commandDurationMillis(it.recordId) }.use {
            state.recordCommandStart(1, true)
            clock.nanos += 10_000_000_000
            state.recordCommandFinished(2, 0)
        }
        assertEquals(listOf<Long?>(10_000L), durations)
    }

    private class Clock(
        var epochMillis: Long = 1_000,
        var nanos: Long = 0,
    ) {
        fun state(capacity: Int = 4): TerminalShellIntegrationState =
            TerminalShellIntegrationState(
                capacity = capacity,
                maxCommandTextLength = 4096,
                epochMillis = { epochMillis },
                nanoTime = { nanos },
            )
    }
}
