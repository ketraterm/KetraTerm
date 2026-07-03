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

import kotlin.test.*

class TerminalShellIntegrationStateTest {
    @Test
    fun `command metadata snapshots text directory exit status and timestamps`() {
        var now = 1_000L
        val state = TerminalShellIntegrationState(epochMillis = { now })

        state.recordPromptStart(10L)
        state.recordPromptEnd(10L)
        state.recordCommandStart(
            lineId = 11L,
            includeLine = true,
            commandText = "./gradlew test",
            workingDirectoryUri = "file:///workspace",
        )
        val recordId = state.latestCommandRecordId()
        now = 1_250L
        state.recordCommandFinished(12L, 0)

        assertEquals(
            TerminalShellIntegrationCommandMetadata(
                recordId = recordId,
                lifecycle = TerminalShellIntegrationCommandLifecycle.SUCCEEDED,
                commandText = "./gradlew test",
                workingDirectoryUri = "file:///workspace",
                exitCode = 0,
                startedAtEpochMillis = 1_000L,
                finishedAtEpochMillis = 1_250L,
            ),
            state.commandMetadata(recordId),
        )
    }

    @Test
    fun `command metadata reports running command without finish metadata`() {
        val state = TerminalShellIntegrationState(epochMillis = { 42L })

        state.recordCommandStart(7L, includeLine = false, commandText = null)
        val metadata = state.commandMetadata(state.latestCommandRecordId())

        assertEquals(TerminalShellIntegrationCommandLifecycle.RUNNING, metadata?.lifecycle)
        assertEquals(42L, metadata?.startedAtEpochMillis)
        assertNull(metadata?.finishedAtEpochMillis)
        assertNull(metadata?.exitCode)
    }

    @Test
    fun `running command query follows shell integration command lifecycle`() {
        val state = TerminalShellIntegrationState()

        assertFalse(state.hasRunningCommand())

        state.recordPromptStart(10)
        state.recordPromptEnd(10)
        assertFalse(state.hasRunningCommand())

        state.recordCommandStart(11, includeLine = true)
        assertTrue(state.hasRunningCommand())

        state.recordCommandFinished(12, exitCode = 0)
        assertFalse(state.hasRunningCommand())
    }

    @Test
    fun `new prompt clears running command query for abandoned command`() {
        val state = TerminalShellIntegrationState()

        state.recordCommandStart(10, includeLine = true)
        assertTrue(state.hasRunningCommand())

        state.recordPromptStart(20)
        assertFalse(state.hasRunningCommand())
    }

    @Test
    fun `prompt command lifecycle projects prompt starts and failed output into viewport`() {
        val state = TerminalShellIntegrationState()
        val promptStarts = BooleanArray(5)
        val failedCommandRails = BooleanArray(5)
        val commandStarts = BooleanArray(5)
        val commandEnds = BooleanArray(5)
        val commandRecordIds = IntArray(5)
        val commandLifecycleStates = IntArray(5)

        state.recordPromptStart(11)
        state.recordPromptEnd(11)
        state.recordCommandStart(12, includeLine = true)
        state.recordCommandFinished(14, exitCode = 7)
        state.copyViewport(
            lineIds = longArrayOf(10, 11, 12, 13, 14),
            rowCount = 5,
            promptStarts = promptStarts,
            commandStarts = commandStarts,
            commandEnds = commandEnds,
            commandRecordIds = commandRecordIds,
            commandLifecycleStates = commandLifecycleStates,
            failedCommandRails = failedCommandRails,
        )

        assertContentEquals(booleanArrayOf(false, true, false, false, false), promptStarts)
        assertContentEquals(booleanArrayOf(false, false, true, true, true), failedCommandRails)
        assertContentEquals(
            intArrayOf(
                TerminalShellIntegrationCommandRecord.NONE,
                commandRecordIds[1],
                commandRecordIds[1],
                commandRecordIds[1],
                commandRecordIds[1],
            ),
            commandRecordIds,
        )
        assertContentEquals(
            intArrayOf(
                TerminalShellIntegrationCommandLifecycle.NONE,
                TerminalShellIntegrationCommandLifecycle.FAILED,
                TerminalShellIntegrationCommandLifecycle.FAILED,
                TerminalShellIntegrationCommandLifecycle.FAILED,
                TerminalShellIntegrationCommandLifecycle.FAILED,
            ),
            commandLifecycleStates,
        )
    }

    @Test
    fun `viewport projection clips command ranges without allocating intermediate records`() {
        val state = TerminalShellIntegrationState()
        val promptStarts = BooleanArray(3)
        val commandStarts = BooleanArray(3)
        val commandEnds = BooleanArray(3)
        val commandRecordIds = IntArray(3)
        val commandLifecycleStates = IntArray(3)

        state.recordCommandStart(5, includeLine = true)
        state.recordCommandFinished(10, exitCode = 1)
        state.copyViewport(
            lineIds = longArrayOf(7, 8, 9),
            rowCount = 3,
            promptStarts = promptStarts,
            commandStarts = commandStarts,
            commandEnds = commandEnds,
            commandRecordIds = commandRecordIds,
            commandLifecycleStates = commandLifecycleStates,
        )

        assertContentEquals(booleanArrayOf(false, false, false), promptStarts)
    }

    @Test
    fun `one-line failed command does not project failed command rail on next prompt row`() {
        val state = TerminalShellIntegrationState()
        val promptStarts = BooleanArray(2)
        val failedCommandRails = BooleanArray(2)
        val commandStarts = BooleanArray(2)
        val commandEnds = BooleanArray(2)
        val commandRecordIds = IntArray(2)
        val commandLifecycleStates = IntArray(2)

        state.recordPromptStart(10)
        state.recordPromptEnd(10)
        state.recordCommandStart(10, includeLine = false)
        state.recordCommandFinished(11, exitCode = 1)
        state.recordPromptStart(11)

        state.copyViewport(
            lineIds = longArrayOf(10, 11),
            rowCount = 2,
            promptStarts = promptStarts,
            commandStarts = commandStarts,
            commandEnds = commandEnds,
            commandRecordIds = commandRecordIds,
            commandLifecycleStates = commandLifecycleStates,
            failedCommandRails = failedCommandRails,
        )

        assertContentEquals(booleanArrayOf(true, true), promptStarts)
        assertContentEquals(booleanArrayOf(false, false), failedCommandRails)
    }

    @Test
    fun `zero null and missing command starts do not create failed command ranges`() {
        val state = TerminalShellIntegrationState()

        state.recordCommandStart(1, includeLine = true)
        state.recordCommandFinished(3, exitCode = 0)
        state.recordCommandStart(4, includeLine = true)
        state.recordCommandFinished(5, exitCode = null)
        state.recordCommandFinished(8, exitCode = 2)

        assertFalse(state.hasFailedCommandOutputAtLine(1))
        assertFalse(state.hasFailedCommandOutputAtLine(4))
        assertFalse(state.hasFailedCommandOutputAtLine(8))
    }

    @Test
    fun `viewport projection exposes successful and unknown command lifecycle states`() {
        val state = TerminalShellIntegrationState()
        val promptStarts = BooleanArray(5)
        val commandStarts = BooleanArray(5)
        val commandEnds = BooleanArray(5)
        val commandRecordIds = IntArray(5)
        val commandLifecycleStates = IntArray(5)

        state.recordCommandStart(1, includeLine = true)
        state.recordCommandFinished(2, exitCode = 0)
        state.recordCommandStart(4, includeLine = true)
        state.recordCommandFinished(5, exitCode = null)
        state.copyViewport(
            lineIds = longArrayOf(1, 2, 3, 4, 5),
            rowCount = 5,
            promptStarts = promptStarts,
            commandStarts = commandStarts,
            commandEnds = commandEnds,
            commandRecordIds = commandRecordIds,
            commandLifecycleStates = commandLifecycleStates,
        )

        assertContentEquals(
            intArrayOf(commandRecordIds[0], commandRecordIds[0], 0, commandRecordIds[3], commandRecordIds[3]),
            commandRecordIds,
        )
        assertContentEquals(
            intArrayOf(
                TerminalShellIntegrationCommandLifecycle.SUCCEEDED,
                TerminalShellIntegrationCommandLifecycle.SUCCEEDED,
                TerminalShellIntegrationCommandLifecycle.NONE,
                TerminalShellIntegrationCommandLifecycle.FINISHED_UNKNOWN,
                TerminalShellIntegrationCommandLifecycle.FINISHED_UNKNOWN,
            ),
            commandLifecycleStates,
        )
    }

    @Test
    fun `viewport projection owns only the observed start row for a running command`() {
        val state = TerminalShellIntegrationState()
        state.recordCommandStart(10, includeLine = false)

        val projection = state.project(longArrayOf(10, 11, 12))

        assertContentEquals(booleanArrayOf(true, false, false), projection.commandStarts)
        assertContentEquals(
            intArrayOf(
                TerminalShellIntegrationCommandLifecycle.RUNNING,
                TerminalShellIntegrationCommandLifecycle.NONE,
                TerminalShellIntegrationCommandLifecycle.NONE,
            ),
            projection.commandLifecycleStates,
        )
    }

    @Test
    fun `copy records exposes retained command timeline in chronological primitive columns`() {
        val state = TerminalShellIntegrationState()
        val records = RecordColumns(capacity = 4)

        state.recordPromptStart(10)
        state.recordPromptEnd(11)
        state.recordCommandStart(12, includeLine = true)
        state.recordCommandFinished(14, exitCode = 7)
        state.recordCommandStart(20, includeLine = false)
        state.recordCommandFinished(21, exitCode = 0)

        val copied = records.copyFrom(state)

        assertEquals(2, state.recordCount())
        assertEquals(2, copied)
        assertTrue(records.recordIds[0] != TerminalShellIntegrationCommandRecord.NONE)
        assertTrue(records.recordIds[1] != TerminalShellIntegrationCommandRecord.NONE)
        assertTrue(records.recordIds[0] != records.recordIds[1])
        assertContentEquals(
            intArrayOf(
                TerminalShellIntegrationCommandLifecycle.FAILED,
                TerminalShellIntegrationCommandLifecycle.SUCCEEDED,
                TerminalShellIntegrationCommandLifecycle.NONE,
                TerminalShellIntegrationCommandLifecycle.NONE,
            ),
            records.lifecycleStates,
        )
        assertContentEquals(longArrayOf(10, 0, 0, 0), records.promptStartLineIds)
        assertContentEquals(longArrayOf(11, 0, 0, 0), records.promptEndLineIds)
        assertContentEquals(longArrayOf(12, 20, 0, 0), records.commandStartLineIds)
        assertContentEquals(longArrayOf(14, 21, 0, 0), records.commandEndLineIds)
        assertContentEquals(
            intArrayOf(
                7,
                0,
                TerminalShellIntegrationCommandRecord.UNKNOWN_EXIT_CODE,
                TerminalShellIntegrationCommandRecord.UNKNOWN_EXIT_CODE,
            ),
            records.exitCodes,
        )
    }

    @Test
    fun `copy records honors destination offset max records and clears reusable buffers`() {
        val state = TerminalShellIntegrationState()
        val records = RecordColumns(capacity = 5)
        records.fillStaleValues()

        state.recordCommandStart(3, includeLine = true)
        state.recordCommandFinished(4, exitCode = null)
        state.recordCommandStart(6, includeLine = true)
        state.recordCommandFinished(7, exitCode = 2)

        val copied =
            state.copyRecords(
                recordIds = records.recordIds,
                lifecycleStates = records.lifecycleStates,
                promptStartLineIds = records.promptStartLineIds,
                promptEndLineIds = records.promptEndLineIds,
                commandStartLineIds = records.commandStartLineIds,
                commandEndLineIds = records.commandEndLineIds,
                exitCodes = records.exitCodes,
                destinationOffset = 1,
                maxRecords = 3,
            )

        assertEquals(2, copied)
        assertEquals(STALE_INT, records.recordIds[0])
        assertEquals(TerminalShellIntegrationCommandRecord.NONE, records.recordIds[3])
        assertEquals(STALE_INT, records.recordIds[4])
        assertContentEquals(
            intArrayOf(
                STALE_INT,
                TerminalShellIntegrationCommandLifecycle.FINISHED_UNKNOWN,
                TerminalShellIntegrationCommandLifecycle.FAILED,
                TerminalShellIntegrationCommandLifecycle.NONE,
                STALE_INT,
            ),
            records.lifecycleStates,
        )
        assertContentEquals(longArrayOf(STALE_LONG, 3, 6, 0, STALE_LONG), records.commandStartLineIds)
        assertContentEquals(longArrayOf(STALE_LONG, 4, 7, 0, STALE_LONG), records.commandEndLineIds)
    }

    @Test
    fun `copy records returns newest retained records after bounded eviction`() {
        val state = TerminalShellIntegrationState(capacity = 2)
        val records = RecordColumns(capacity = 3)

        state.recordCommandStart(1, includeLine = true)
        state.recordCommandFinished(2, exitCode = 1)
        state.recordCommandStart(3, includeLine = true)
        state.recordCommandFinished(4, exitCode = 0)
        state.recordPromptStart(5)

        val copied = records.copyFrom(state)

        assertEquals(2, copied)
        assertContentEquals(longArrayOf(0, 5, 0), records.promptStartLineIds)
        assertContentEquals(longArrayOf(3, 0, 0), records.commandStartLineIds)
        assertContentEquals(
            intArrayOf(
                TerminalShellIntegrationCommandLifecycle.SUCCEEDED,
                TerminalShellIntegrationCommandLifecycle.PROMPT_ONLY,
                TerminalShellIntegrationCommandLifecycle.NONE,
            ),
            records.lifecycleStates,
        )
    }

    @Test
    fun `command text metadata is queried by retained record id`() {
        val state = TerminalShellIntegrationState()
        val records = RecordColumns(capacity = 1)

        state.recordCommandStart(1, includeLine = true, commandText = "git status")

        records.copyFrom(state)

        assertEquals("git status", state.commandText(records.recordIds[0]))
    }

    @Test
    fun `current working directory is snapshotted by command records`() {
        val state = TerminalShellIntegrationState()
        val records = RecordColumns(capacity = 2)

        state.recordCurrentWorkingDirectory("file:///first")
        state.recordCommandStart(1, includeLine = true, workingDirectoryUri = state.currentWorkingDirectoryUri())
        state.recordCommandFinished(1, exitCode = 0)
        state.recordCurrentWorkingDirectory("file:///second")
        state.recordCommandStart(2, includeLine = true, workingDirectoryUri = state.currentWorkingDirectoryUri())
        records.copyFrom(state)

        assertEquals("file:///second", state.currentWorkingDirectoryUri())
        assertEquals("file:///first", state.commandWorkingDirectoryUri(records.recordIds[0]))
        assertEquals("file:///second", state.commandWorkingDirectoryUri(records.recordIds[1]))
    }

    @Test
    fun `command working directory is removed on eviction while live directory survives clear`() {
        val state = TerminalShellIntegrationState(capacity = 1)
        val records = RecordColumns(capacity = 1)
        state.recordCurrentWorkingDirectory("file:///workspace")
        state.recordCommandStart(1, includeLine = true, workingDirectoryUri = state.currentWorkingDirectoryUri())
        records.copyFrom(state)
        val evictedRecordId = records.recordIds[0]

        state.recordCommandStart(2, includeLine = true, workingDirectoryUri = state.currentWorkingDirectoryUri())
        records.copyFrom(state)
        val retainedRecordId = records.recordIds[0]

        assertNull(state.commandWorkingDirectoryUri(evictedRecordId))
        assertEquals("file:///workspace", state.commandWorkingDirectoryUri(retainedRecordId))

        state.clear()

        assertNull(state.commandWorkingDirectoryUri(retainedRecordId))
        assertEquals("file:///workspace", state.currentWorkingDirectoryUri())
    }

    @Test
    fun `oversized command text is stored as unknown`() {
        val state = TerminalShellIntegrationState(maxCommandTextLength = 4)
        val records = RecordColumns(capacity = 1)

        state.recordCommandStart(1, includeLine = true, commandText = "12345")

        records.copyFrom(state)

        assertNull(state.commandText(records.recordIds[0]))
    }

    @Test
    fun `command text metadata is removed on eviction and clear`() {
        val state = TerminalShellIntegrationState(capacity = 1)
        val records = RecordColumns(capacity = 1)

        state.recordCommandStart(1, includeLine = true, commandText = "old")
        records.copyFrom(state)
        val oldRecordId = records.recordIds[0]
        state.recordCommandStart(2, includeLine = true, commandText = "new")
        records.copyFrom(state)
        val newRecordId = records.recordIds[0]

        assertEquals(null, state.commandText(oldRecordId))
        assertEquals("new", state.commandText(newRecordId))

        state.clear()

        assertEquals(null, state.commandText(newRecordId))
    }

    @Test
    fun `command record lookup by line returns owning command and skips prompt only records`() {
        val state = TerminalShellIntegrationState()
        val records = RecordColumns(capacity = 2)

        state.recordPromptStart(10)
        state.recordPromptEnd(10)
        state.recordCommandStart(11, includeLine = true)
        state.recordCommandFinished(12, exitCode = 0)
        state.recordPromptStart(20)

        records.copyFrom(state)
        val commandRecordId = records.recordIds[0]

        assertEquals(commandRecordId, state.commandRecordIdAtLine(10))
        assertEquals(commandRecordId, state.commandRecordIdAtLine(11))
        assertEquals(commandRecordId, state.commandRecordIdAtLine(12))
        assertEquals(TerminalShellIntegrationCommandRecord.NONE, state.commandRecordIdAtLine(13))
        assertEquals(TerminalShellIntegrationCommandRecord.NONE, state.commandRecordIdAtLine(20))
    }

    @Test
    fun `command anchor line prefers prompt start and falls back to orphan command start`() {
        val state = TerminalShellIntegrationState()
        val records = RecordColumns(capacity = 3)

        state.recordPromptStart(10)
        state.recordPromptEnd(10)
        state.recordCommandStart(11, includeLine = true)
        state.recordCommandFinished(12, exitCode = 0)
        state.recordCommandStart(20, includeLine = true)
        state.recordCommandFinished(21, exitCode = 0)
        state.recordPromptStart(30)

        records.copyFrom(state)

        assertEquals(10, state.commandAnchorLineId(records.recordIds[0]))
        assertEquals(20, state.commandAnchorLineId(records.recordIds[1]))
        assertEquals(0, state.commandAnchorLineId(records.recordIds[2]))
        assertEquals(0, state.commandAnchorLineId(999))
    }

    @Test
    fun `command anchor line forgets evicted records`() {
        val state = TerminalShellIntegrationState(capacity = 1)
        val records = RecordColumns(capacity = 1)

        state.recordCommandStart(10, includeLine = true)
        records.copyFrom(state)
        val evictedRecordId = records.recordIds[0]
        state.recordCommandStart(20, includeLine = true)
        records.copyFrom(state)

        assertEquals(0, state.commandAnchorLineId(evictedRecordId))
        assertEquals(20, state.commandAnchorLineId(records.recordIds[0]))
    }

    @Test
    fun `copy command output range exposes inclusive and exclusive output boundaries`() {
        val state = TerminalShellIntegrationState()
        val records = RecordColumns(capacity = 2)
        val range = LongArray(TerminalShellIntegrationCommandOutputRange.REQUIRED_LONGS)

        state.recordCommandStart(10, includeLine = false)
        state.recordCommandFinished(12, exitCode = 1)
        state.recordCommandStart(20, includeLine = true)
        state.recordCommandFinished(21, exitCode = 0)

        records.copyFrom(state)

        assertTrue(state.copyCommandOutputRange(records.recordIds[0], range))
        assertContentEquals(longArrayOf(10, 12, 0), range)

        assertTrue(state.copyCommandOutputRange(records.recordIds[1], range))
        assertContentEquals(longArrayOf(20, 21, 1), range)
    }

    @Test
    fun `copy command output range rejects prompt only running missing and empty exclusive output`() {
        val state = TerminalShellIntegrationState()
        val records = RecordColumns(capacity = 3)
        val range = LongArray(TerminalShellIntegrationCommandOutputRange.REQUIRED_LONGS) { STALE_LONG }

        state.recordPromptStart(1)
        state.recordPromptStart(2)
        state.recordCommandStart(10, includeLine = true)
        state.recordCommandStart(20, includeLine = false)
        state.recordCommandFinished(20, exitCode = 1)

        records.copyFrom(state)

        assertFalse(state.copyCommandOutputRange(records.recordIds[0], range))
        assertFalse(state.copyCommandOutputRange(records.recordIds[1], range))
        assertFalse(state.copyCommandOutputRange(records.recordIds[2], range))
        assertFalse(state.copyCommandOutputRange(999, range))
        assertContentEquals(longArrayOf(STALE_LONG, STALE_LONG, STALE_LONG), range)
    }

    @Test
    fun `copy command output range rejects undersized destination`() {
        val state = TerminalShellIntegrationState()

        val error =
            assertFailsWith<IllegalArgumentException> {
                state.copyCommandOutputRange(1, LongArray(2))
            }

        assertTrue(error.message!!.startsWith("destination is too small"))
    }

    @Test
    fun `previous and next command record ids skip prompt only records`() {
        val state = TerminalShellIntegrationState()
        val records = RecordColumns(capacity = 3)

        state.recordCommandStart(10, includeLine = true)
        state.recordCommandFinished(11, exitCode = 0)
        state.recordPromptStart(20)
        state.recordPromptStart(25)
        state.recordCommandStart(30, includeLine = true)
        state.recordCommandFinished(31, exitCode = 1)

        records.copyFrom(state)
        val firstCommandId = records.recordIds[0]
        val promptOnlyId = records.recordIds[1]
        val secondCommandId = records.recordIds[2]

        assertEquals(TerminalShellIntegrationCommandRecord.NONE, state.previousCommandRecordId(firstCommandId))
        assertEquals(secondCommandId, state.nextCommandRecordId(firstCommandId))
        assertEquals(firstCommandId, state.previousCommandRecordId(promptOnlyId))
        assertEquals(secondCommandId, state.nextCommandRecordId(promptOnlyId))
        assertEquals(firstCommandId, state.previousCommandRecordId(secondCommandId))
        assertEquals(TerminalShellIntegrationCommandRecord.NONE, state.nextCommandRecordId(secondCommandId))
        assertEquals(TerminalShellIntegrationCommandRecord.NONE, state.previousCommandRecordId(999))
        assertEquals(TerminalShellIntegrationCommandRecord.NONE, state.nextCommandRecordId(999))
    }

    @Test
    fun `line anchored command navigation handles before inside between and after commands`() {
        val state = TerminalShellIntegrationState()
        val records = RecordColumns(capacity = 2)

        state.recordCommandStart(10, includeLine = true)
        state.recordCommandFinished(12, exitCode = 0)
        state.recordCommandStart(20, includeLine = true)
        state.recordCommandFinished(22, exitCode = 1)

        records.copyFrom(state)
        val firstCommandId = records.recordIds[0]
        val secondCommandId = records.recordIds[1]

        assertEquals(TerminalShellIntegrationCommandRecord.NONE, state.previousCommandRecordIdBeforeLine(9))
        assertEquals(firstCommandId, state.nextCommandRecordIdAfterLine(9))
        assertEquals(TerminalShellIntegrationCommandRecord.NONE, state.previousCommandRecordIdBeforeLine(11))
        assertEquals(secondCommandId, state.nextCommandRecordIdAfterLine(11))
        assertEquals(firstCommandId, state.previousCommandRecordIdBeforeLine(15))
        assertEquals(secondCommandId, state.nextCommandRecordIdAfterLine(15))
        assertEquals(firstCommandId, state.previousCommandRecordIdBeforeLine(21))
        assertEquals(TerminalShellIntegrationCommandRecord.NONE, state.nextCommandRecordIdAfterLine(21))
        assertEquals(secondCommandId, state.previousCommandRecordIdBeforeLine(30))
        assertEquals(TerminalShellIntegrationCommandRecord.NONE, state.nextCommandRecordIdAfterLine(30))
    }

    @Test
    fun `command navigation forgets evicted command records`() {
        val state = TerminalShellIntegrationState(capacity = 2)
        val records = RecordColumns(capacity = 2)

        state.recordCommandStart(10, includeLine = true)
        state.recordCommandFinished(11, exitCode = 0)
        state.recordCommandStart(20, includeLine = true)
        state.recordCommandFinished(21, exitCode = 0)
        state.recordCommandStart(30, includeLine = true)
        state.recordCommandFinished(31, exitCode = 0)

        records.copyFrom(state)
        val secondCommandId = records.recordIds[0]
        val thirdCommandId = records.recordIds[1]

        assertEquals(TerminalShellIntegrationCommandRecord.NONE, state.commandRecordIdAtLine(10))
        assertEquals(secondCommandId, state.nextCommandRecordIdAfterLine(10))
        assertEquals(TerminalShellIntegrationCommandRecord.NONE, state.previousCommandRecordIdBeforeLine(20))
        assertEquals(thirdCommandId, state.nextCommandRecordId(secondCommandId))
    }

    @Test
    fun `copy records rejects undersized destination columns`() {
        val state = TerminalShellIntegrationState()
        val records = RecordColumns(capacity = 1)

        val error =
            assertFailsWith<IllegalArgumentException> {
                state.copyRecords(
                    recordIds = records.recordIds,
                    lifecycleStates = records.lifecycleStates,
                    promptStartLineIds = records.promptStartLineIds,
                    promptEndLineIds = records.promptEndLineIds,
                    commandStartLineIds = records.commandStartLineIds,
                    commandEndLineIds = records.commandEndLineIds,
                    exitCodes = records.exitCodes,
                    destinationOffset = 0,
                    maxRecords = 2,
                )
            }

        assertTrue(error.message!!.startsWith("recordIds is too small"))
    }

    @Test
    fun `new prompt marks unfinished command as abandoned`() {
        val state = TerminalShellIntegrationState()
        val promptStarts = BooleanArray(2)
        val commandStarts = BooleanArray(2)
        val commandEnds = BooleanArray(2)
        val commandRecordIds = IntArray(2)
        val commandLifecycleStates = IntArray(2)

        state.recordCommandStart(10, includeLine = true)
        state.recordPromptStart(20)
        state.copyViewport(
            lineIds = longArrayOf(10, 20),
            rowCount = 2,
            promptStarts = promptStarts,
            commandStarts = commandStarts,
            commandEnds = commandEnds,
            commandRecordIds = commandRecordIds,
            commandLifecycleStates = commandLifecycleStates,
        )

        assertContentEquals(booleanArrayOf(false, true), promptStarts)
        assertTrue(commandRecordIds[0] != TerminalShellIntegrationCommandRecord.NONE)
        assertTrue(commandRecordIds[1] != TerminalShellIntegrationCommandRecord.NONE)
        assertTrue(commandRecordIds[0] != commandRecordIds[1])
        assertContentEquals(
            intArrayOf(
                TerminalShellIntegrationCommandLifecycle.ABANDONED,
                TerminalShellIntegrationCommandLifecycle.PROMPT_ONLY,
            ),
            commandLifecycleStates,
        )
    }

    @Test
    fun `duplicate command starts close over the newest active command only`() {
        val state = TerminalShellIntegrationState()

        state.recordCommandStart(1, includeLine = true)
        state.recordCommandStart(3, includeLine = true)
        state.recordCommandFinished(4, exitCode = 2)

        assertFalse(state.hasFailedCommandOutputAtLine(1))
        assertTrue(state.hasFailedCommandOutputAtLine(3))
        assertTrue(state.hasFailedCommandOutputAtLine(4))
    }

    @Test
    fun `duplicate command start marks previous command as abandoned and keeps newest command active`() {
        val state = TerminalShellIntegrationState()

        state.recordCommandStart(1, includeLine = true)
        state.recordCommandStart(3, includeLine = true)
        state.recordCommandFinished(4, exitCode = 2)

        val projection = state.project(longArrayOf(1, 2, 3, 4))

        assertTrue(projection.commandRecordIds[0] != TerminalShellIntegrationCommandRecord.NONE)
        assertEquals(TerminalShellIntegrationCommandRecord.NONE, projection.commandRecordIds[1])
        assertTrue(projection.commandRecordIds[2] != TerminalShellIntegrationCommandRecord.NONE)
        assertEquals(projection.commandRecordIds[2], projection.commandRecordIds[3])
        assertTrue(projection.commandRecordIds[0] != projection.commandRecordIds[2])
        assertContentEquals(
            intArrayOf(
                TerminalShellIntegrationCommandLifecycle.ABANDONED,
                TerminalShellIntegrationCommandLifecycle.NONE,
                TerminalShellIntegrationCommandLifecycle.FAILED,
                TerminalShellIntegrationCommandLifecycle.FAILED,
            ),
            projection.commandLifecycleStates,
        )
    }

    @Test
    fun `prompt end without prompt start is ignored`() {
        val state = TerminalShellIntegrationState()
        val promptStarts = BooleanArray(2)
        val commandStarts = BooleanArray(2)
        val commandEnds = BooleanArray(2)
        val commandRecordIds = IntArray(2)
        val commandLifecycleStates = IntArray(2)

        state.recordPromptEnd(1)
        state.copyViewport(
            lineIds = longArrayOf(1, 2),
            rowCount = 2,
            promptStarts = promptStarts,
            commandStarts = commandStarts,
            commandEnds = commandEnds,
            commandRecordIds = commandRecordIds,
            commandLifecycleStates = commandLifecycleStates,
        )

        assertContentEquals(booleanArrayOf(false, false), promptStarts)
    }

    @Test
    fun `new prompt abandons unfinished command so stale finish marker cannot create a failed range`() {
        val state = TerminalShellIntegrationState()

        state.recordCommandStart(10, includeLine = true)
        state.recordPromptStart(20)
        state.recordCommandFinished(19, exitCode = 1)

        assertFalse(state.hasFailedCommandOutputAtLine(10))
        assertTrue(state.hasPromptStartAtLine(20))
    }

    @Test
    fun `repeated command finish closes only the active command once`() {
        val state = TerminalShellIntegrationState()

        state.recordCommandStart(10, includeLine = true)
        state.recordCommandFinished(11, exitCode = 1)
        state.recordCommandFinished(20, exitCode = 1)

        assertTrue(state.hasFailedCommandOutputAtLine(10))
        assertTrue(state.hasFailedCommandOutputAtLine(11))
        assertFalse(state.hasFailedCommandOutputAtLine(20))
    }

    @Test
    fun `prompt end applies only to newest prompt after duplicate prompt starts`() {
        val state = TerminalShellIntegrationState()

        state.recordPromptStart(1)
        state.recordPromptStart(2)
        state.recordPromptEnd(3)
        state.recordCommandStart(4, includeLine = true)
        state.recordCommandFinished(5, exitCode = 1)

        assertTrue(state.hasPromptStartAtLine(1))
        assertTrue(state.hasPromptStartAtLine(2))
        assertFalse(state.hasFailedCommandOutputAtLine(3))
        assertTrue(state.hasFailedCommandOutputAtLine(4))
        assertTrue(state.hasFailedCommandOutputAtLine(5))
    }

    @Test
    fun `destructive row rewind does not clear identity anchored records`() {
        val state = TerminalShellIntegrationState()

        state.observeLiveBottomRow(100)
        state.recordPromptStart(90)
        state.recordCommandStart(91, includeLine = true)
        state.recordCommandFinished(95, exitCode = 1)
        state.observeLiveBottomRow(10)

        assertTrue(state.hasPromptStartAtLine(90))
        assertTrue(state.hasFailedCommandOutputAtLine(91))
    }

    @Test
    fun `bounded command timeline evicts oldest records`() {
        val state = TerminalShellIntegrationState(capacity = 2)

        state.recordPromptStart(1)
        state.recordPromptStart(2)
        state.recordPromptStart(3)

        assertFalse(state.hasPromptStartAtLine(1))
        assertTrue(state.hasPromptStartAtLine(2))
        assertTrue(state.hasPromptStartAtLine(3))
    }

    @Test
    fun `bounded command timeline evicts old record ids from viewport projection`() {
        val state = TerminalShellIntegrationState(capacity = 2)

        state.recordCommandStart(1, includeLine = true)
        state.recordCommandFinished(2, exitCode = 1)
        state.recordCommandStart(3, includeLine = true)
        state.recordCommandFinished(4, exitCode = 0)
        state.recordPromptStart(5)

        val projection = state.project(longArrayOf(1, 2, 3, 4, 5))

        assertContentEquals(
            intArrayOf(
                TerminalShellIntegrationCommandRecord.NONE,
                TerminalShellIntegrationCommandRecord.NONE,
                projection.commandRecordIds[2],
                projection.commandRecordIds[2],
                projection.commandRecordIds[4],
            ),
            projection.commandRecordIds,
        )
        assertContentEquals(
            intArrayOf(
                TerminalShellIntegrationCommandLifecycle.NONE,
                TerminalShellIntegrationCommandLifecycle.NONE,
                TerminalShellIntegrationCommandLifecycle.SUCCEEDED,
                TerminalShellIntegrationCommandLifecycle.SUCCEEDED,
                TerminalShellIntegrationCommandLifecycle.PROMPT_ONLY,
            ),
            projection.commandLifecycleStates,
        )
    }

    @Test
    fun `evicting active prompt prevents command start from attaching to removed record`() {
        val state = TerminalShellIntegrationState(capacity = 1)

        state.recordPromptStart(1)
        state.recordPromptStart(2)
        state.recordCommandStart(3, includeLine = true)
        state.recordCommandFinished(4, exitCode = 1)

        assertFalse(state.hasFailedCommandOutputAtLine(1))
        assertTrue(state.hasPromptStartAtLine(2))
        assertTrue(state.hasFailedCommandOutputAtLine(3))
        assertTrue(state.hasFailedCommandOutputAtLine(4))
    }

    @Test
    fun `viewport projection follows line ids when visible row positions change`() {
        val state = TerminalShellIntegrationState()
        val promptStarts = BooleanArray(4)
        val commandStarts = BooleanArray(4)
        val commandEnds = BooleanArray(4)
        val commandRecordIds = IntArray(4)
        val commandLifecycleStates = IntArray(4)

        state.recordPromptStart(40)
        state.recordCommandStart(41, includeLine = true)
        state.recordCommandFinished(42, exitCode = 1)
        state.copyViewport(
            lineIds = longArrayOf(70, 42, 40, 41),
            rowCount = 4,
            promptStarts = promptStarts,
            commandStarts = commandStarts,
            commandEnds = commandEnds,
            commandRecordIds = commandRecordIds,
            commandLifecycleStates = commandLifecycleStates,
        )

        assertContentEquals(booleanArrayOf(false, false, true, false), promptStarts)
    }

    @Test
    fun `exclusive command start excludes prompt input line from failed rail`() {
        val state = TerminalShellIntegrationState()
        val promptStarts = BooleanArray(4)
        val commandStarts = BooleanArray(4)
        val commandEnds = BooleanArray(4)
        val commandRecordIds = IntArray(4)
        val commandLifecycleStates = IntArray(4)

        state.recordCommandStart(10, includeLine = false)
        state.recordCommandFinished(12, exitCode = 1)
        state.copyViewport(
            lineIds = longArrayOf(10, 11, 12, 13),
            rowCount = 4,
            promptStarts = promptStarts,
            commandStarts = commandStarts,
            commandEnds = commandEnds,
            commandRecordIds = commandRecordIds,
            commandLifecycleStates = commandLifecycleStates,
        )

        assertContentEquals(booleanArrayOf(false, false, false, false), promptStarts)
        assertContentEquals(booleanArrayOf(true, false, false, false), commandStarts)
        assertContentEquals(booleanArrayOf(false, false, true, false), commandEnds)
        assertContentEquals(
            intArrayOf(
                commandRecordIds[0],
                commandRecordIds[0],
                commandRecordIds[0],
                TerminalShellIntegrationCommandRecord.NONE,
            ),
            commandRecordIds,
        )
    }

    @Test
    fun `inclusive command start includes same line output in failed rail`() {
        val state = TerminalShellIntegrationState()
        val promptStarts = BooleanArray(3)
        val commandStarts = BooleanArray(3)
        val commandEnds = BooleanArray(3)
        val commandRecordIds = IntArray(3)
        val commandLifecycleStates = IntArray(3)

        state.recordCommandStart(10, includeLine = true)
        state.recordCommandFinished(11, exitCode = 1)
        state.copyViewport(
            lineIds = longArrayOf(10, 11, 12),
            rowCount = 3,
            promptStarts = promptStarts,
            commandStarts = commandStarts,
            commandEnds = commandEnds,
            commandRecordIds = commandRecordIds,
            commandLifecycleStates = commandLifecycleStates,
        )

        assertContentEquals(booleanArrayOf(false, false, false), promptStarts)
    }

    @Test
    fun `failed rail covers duplicate physical rows from command output reflow`() {
        val state = TerminalShellIntegrationState()
        val promptStarts = BooleanArray(5)
        val commandStarts = BooleanArray(5)
        val commandEnds = BooleanArray(5)
        val commandRecordIds = IntArray(5)
        val commandLifecycleStates = IntArray(5)

        state.recordCommandStart(10, includeLine = false)
        state.recordCommandFinished(12, exitCode = 1)
        state.copyViewport(
            lineIds = longArrayOf(10, 11, 11, 12, 12),
            rowCount = 5,
            promptStarts = promptStarts,
            commandStarts = commandStarts,
            commandEnds = commandEnds,
            commandRecordIds = commandRecordIds,
            commandLifecycleStates = commandLifecycleStates,
        )

        assertContentEquals(booleanArrayOf(false, false, false, false, false), promptStarts)
        assertContentEquals(booleanArrayOf(true, false, false, false, false), commandStarts)
        assertContentEquals(booleanArrayOf(false, false, false, true, false), commandEnds)
        assertContentEquals(
            intArrayOf(
                commandRecordIds[0],
                commandRecordIds[0],
                commandRecordIds[0],
                commandRecordIds[0],
                commandRecordIds[0],
            ),
            commandRecordIds,
        )
    }

    @Test
    fun `exclusive command with no output does not draw a failed rail`() {
        val state = TerminalShellIntegrationState()
        val promptStarts = BooleanArray(2)
        val commandStarts = BooleanArray(2)
        val commandEnds = BooleanArray(2)
        val commandRecordIds = IntArray(2)
        val commandLifecycleStates = IntArray(2)

        state.recordCommandStart(10, includeLine = false)
        state.recordCommandFinished(10, exitCode = 1)
        state.copyViewport(
            lineIds = longArrayOf(10, 11),
            rowCount = 2,
            promptStarts = promptStarts,
            commandStarts = commandStarts,
            commandEnds = commandEnds,
            commandRecordIds = commandRecordIds,
            commandLifecycleStates = commandLifecycleStates,
        )

        assertContentEquals(booleanArrayOf(false, false), promptStarts)
    }

    @Test
    fun `exclusive command with no output still projects failed command boundary metadata`() {
        val state = TerminalShellIntegrationState()

        state.recordCommandStart(10, includeLine = false)
        state.recordCommandFinished(10, exitCode = 1)

        val projection = state.project(longArrayOf(10, 11))

        assertContentEquals(booleanArrayOf(true, false), projection.commandStarts)
        assertContentEquals(booleanArrayOf(true, false), projection.commandEnds)
        assertTrue(projection.commandRecordIds[0] != TerminalShellIntegrationCommandRecord.NONE)
        assertEquals(TerminalShellIntegrationCommandRecord.NONE, projection.commandRecordIds[1])
        assertContentEquals(
            intArrayOf(
                TerminalShellIntegrationCommandLifecycle.FAILED,
                TerminalShellIntegrationCommandLifecycle.NONE,
            ),
            projection.commandLifecycleStates,
        )
    }

    @Test
    fun `prompt-start marker is projected once when resize reflow exposes duplicate physical rows`() {
        val state = TerminalShellIntegrationState()
        val promptStarts = BooleanArray(3)
        val commandStarts = BooleanArray(3)
        val commandEnds = BooleanArray(3)
        val commandRecordIds = IntArray(3)
        val commandLifecycleStates = IntArray(3)

        state.recordPromptStart(9)
        state.copyViewport(
            lineIds = longArrayOf(9, 9, 9),
            rowCount = 3,
            promptStarts = promptStarts,
            commandStarts = commandStarts,
            commandEnds = commandEnds,
            commandRecordIds = commandRecordIds,
            commandLifecycleStates = commandLifecycleStates,
        )

        assertContentEquals(booleanArrayOf(true, false, false), promptStarts)
    }

    @Test
    fun `multiline prompt projects one prompt record over the whole prompt range`() {
        val state = TerminalShellIntegrationState()

        state.recordPromptStart(10)
        state.recordPromptEnd(12)

        val projection = state.project(longArrayOf(10, 11, 12, 13))

        assertContentEquals(booleanArrayOf(true, false, false, false), projection.promptStarts)
        assertContentEquals(
            intArrayOf(
                projection.commandRecordIds[0],
                projection.commandRecordIds[0],
                projection.commandRecordIds[0],
                TerminalShellIntegrationCommandRecord.NONE,
            ),
            projection.commandRecordIds,
        )
        assertContentEquals(
            intArrayOf(
                TerminalShellIntegrationCommandLifecycle.PROMPT_ONLY,
                TerminalShellIntegrationCommandLifecycle.PROMPT_ONLY,
                TerminalShellIntegrationCommandLifecycle.PROMPT_ONLY,
                TerminalShellIntegrationCommandLifecycle.NONE,
            ),
            projection.commandLifecycleStates,
        )
    }

    @Test
    fun `clear removes projected command ids lifecycles and decoration flags`() {
        val state = TerminalShellIntegrationState()

        state.recordPromptStart(1)
        state.recordCommandStart(2, includeLine = true)
        state.recordCommandFinished(3, exitCode = 1)
        state.clear()

        val projection = state.project(longArrayOf(1, 2, 3))

        assertContentEquals(booleanArrayOf(false, false, false), projection.promptStarts)
        assertContentEquals(booleanArrayOf(false, false, false), projection.commandStarts)
        assertContentEquals(booleanArrayOf(false, false, false), projection.commandEnds)
        assertContentEquals(
            intArrayOf(
                TerminalShellIntegrationCommandRecord.NONE,
                TerminalShellIntegrationCommandRecord.NONE,
                TerminalShellIntegrationCommandRecord.NONE,
            ),
            projection.commandRecordIds,
        )
        assertContentEquals(
            intArrayOf(
                TerminalShellIntegrationCommandLifecycle.NONE,
                TerminalShellIntegrationCommandLifecycle.NONE,
                TerminalShellIntegrationCommandLifecycle.NONE,
            ),
            projection.commandLifecycleStates,
        )
    }

    private fun TerminalShellIntegrationState.project(lineIds: LongArray): Projection {
        val rowCount = lineIds.size
        val promptStarts = BooleanArray(rowCount)
        val commandStarts = BooleanArray(rowCount)
        val commandEnds = BooleanArray(rowCount)
        val commandRecordIds = IntArray(rowCount)
        val commandLifecycleStates = IntArray(rowCount)

        copyViewport(
            lineIds = lineIds,
            rowCount = rowCount,
            promptStarts = promptStarts,
            commandStarts = commandStarts,
            commandEnds = commandEnds,
            commandRecordIds = commandRecordIds,
            commandLifecycleStates = commandLifecycleStates,
        )

        return Projection(
            promptStarts = promptStarts,
            commandStarts = commandStarts,
            commandEnds = commandEnds,
            commandRecordIds = commandRecordIds,
            commandLifecycleStates = commandLifecycleStates,
        )
    }

    private class Projection(
        val promptStarts: BooleanArray,
        val commandStarts: BooleanArray,
        val commandEnds: BooleanArray,
        val commandRecordIds: IntArray,
        val commandLifecycleStates: IntArray,
    )

    private class RecordColumns(
        capacity: Int,
    ) {
        val recordIds = IntArray(capacity)
        val lifecycleStates = IntArray(capacity)
        val promptStartLineIds = LongArray(capacity)
        val promptEndLineIds = LongArray(capacity)
        val commandStartLineIds = LongArray(capacity)
        val commandEndLineIds = LongArray(capacity)
        val exitCodes = IntArray(capacity) { TerminalShellIntegrationCommandRecord.UNKNOWN_EXIT_CODE }

        fun copyFrom(state: TerminalShellIntegrationState): Int =
            state.copyRecords(
                recordIds = recordIds,
                lifecycleStates = lifecycleStates,
                promptStartLineIds = promptStartLineIds,
                promptEndLineIds = promptEndLineIds,
                commandStartLineIds = commandStartLineIds,
                commandEndLineIds = commandEndLineIds,
                exitCodes = exitCodes,
                destinationOffset = 0,
                maxRecords = recordIds.size,
            )

        fun fillStaleValues() {
            recordIds.fill(STALE_INT)
            lifecycleStates.fill(STALE_INT)
            promptStartLineIds.fill(STALE_LONG)
            promptEndLineIds.fill(STALE_LONG)
            commandStartLineIds.fill(STALE_LONG)
            commandEndLineIds.fill(STALE_LONG)
            exitCodes.fill(STALE_INT)
        }
    }

    private companion object {
        private const val STALE_INT = -7
        private const val STALE_LONG = -7L
    }
}
