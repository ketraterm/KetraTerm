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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalShellIntegrationObservationTest {
    @Test
    fun `directory observers capture every changed URI before producer closure`() {
        val state = TerminalShellIntegrationState()
        val directories = mutableListOf<String>()
        val producerThread = Thread.currentThread()
        val registration =
            state.addCurrentWorkingDirectoryListener { uri ->
                assertSame(producerThread, Thread.currentThread())
                assertEquals(uri, state.currentWorkingDirectoryUri())
                directories += uri
            }
        try {
            state.recordCurrentWorkingDirectory("file:///first")
            state.recordCurrentWorkingDirectory("file:///last")
            registration.close()

            assertEquals(listOf("file:///first", "file:///last"), directories)
            state.recordCurrentWorkingDirectory("file:///after-close")
            assertEquals(listOf("file:///first", "file:///last"), directories)
        } finally {
            registration.close()
        }
    }

    @Test
    fun `directory observation has no replay ignores equal values and survives history clear`() {
        val state = TerminalShellIntegrationState()
        state.recordCurrentWorkingDirectory("file:///existing")
        val directories = mutableListOf<String>()
        state.addCurrentWorkingDirectoryListener { directories += it }.use {
            assertTrue(directories.isEmpty())
            state.recordCurrentWorkingDirectory("file:///existing")
            state.recordPromptStart(1)
            state.clear()
            assertTrue(directories.isEmpty())
            assertEquals("file:///existing", state.currentWorkingDirectoryUri())

            state.recordCurrentWorkingDirectory("file:///changed")
            assertEquals(listOf("file:///changed"), directories)
        }
    }

    @Test
    fun `directory observer failures leave other directory and command observers usable`() {
        val state = TerminalShellIntegrationState()
        val directories = mutableListOf<String>()
        var completions = 0
        state.addCurrentWorkingDirectoryListener { error("Directory observer failure") }.use {
            state.addCurrentWorkingDirectoryListener { directories += it }.use {
                state.addCommandFinishedListener { completions++ }.use {
                    state.recordCurrentWorkingDirectory("file:///workspace")
                    state.recordCommandStart(1, true)
                    state.recordCommandFinished(2, 0)

                    assertEquals(listOf("file:///workspace"), directories)
                    assertEquals(1, completions)
                }
            }
        }
    }

    @Test
    fun `directory cancellation preserves the update and notifies remaining observers`() {
        val state = TerminalShellIntegrationState()
        val cancellation = CancellationException("Directory observer cancelled")
        var received: String? = null
        state.addCurrentWorkingDirectoryListener { throw cancellation }.use {
            state.addCurrentWorkingDirectoryListener { received = it }.use {
                val failure =
                    assertFailsWith<CancellationException> {
                        state.recordCurrentWorkingDirectory("file:///workspace")
                    }

                assertSame(cancellation, failure)
                assertEquals("file:///workspace", received)
                assertEquals("file:///workspace", state.currentWorkingDirectoryUri())
                assertTrue(state.revision.value > 0)
            }
        }
    }

    @Test
    fun `directory callbacks do not hold the model storage lock`() {
        val state = TerminalShellIntegrationState()
        var observed = false
        state
            .addCurrentWorkingDirectoryListener { uri ->
                SessionTestThread("shell-directory-reader") {
                    assertEquals(uri, state.currentWorkingDirectoryUri())
                }.use { reader -> reader.awaitCompletion() }
                observed = true
            }.use {
                state.recordCurrentWorkingDirectory("file:///workspace")
                assertTrue(observed)
            }
    }

    @Test
    fun `closing directory observation does not detach independent command observation`() {
        val state = TerminalShellIntegrationState()
        var directories = 0
        var completions = 0
        val directoryRegistration = state.addCurrentWorkingDirectoryListener { directories++ }
        try {
            state.addCommandFinishedListener { completions++ }.use {
                directoryRegistration.close()
                directoryRegistration.close()
                state.recordCurrentWorkingDirectory("file:///workspace")
                state.recordCommandStart(1, true)
                state.recordCommandFinished(2, 0)

                assertEquals(0, directories)
                assertEquals(1, completions)
            }
        } finally {
            directoryRegistration.close()
        }
    }

    @Test
    fun `metadata changes invalidate observers without terminal rendering`() {
        val state = TerminalShellIntegrationState()
        assertEquals(0L, state.revision.value)
        val mutations =
            listOf<() -> Unit>(
                { state.recordCurrentWorkingDirectory("file:///workspace") },
                { state.recordPromptStart(10) },
                { state.recordPromptEnd(11) },
                { state.reanchorActivePromptStart(11) },
                { state.recordCommandStart(12, includeLine = true) },
                { state.recordCommandFinished(13, 0) },
                { state.clear() },
            )

        for (mutate in mutations) {
            val before = state.revision.value
            mutate()
            assertTrue(state.revision.value > before)
        }
        assertEquals("file:///workspace", state.currentWorkingDirectoryUri())
        assertEquals(0, state.recordCount())
    }

    @Test
    fun `equal metadata and unavailable targets do not invalidate`() {
        val state = TerminalShellIntegrationState()
        state.recordPromptEnd(1)
        state.reanchorActivePromptStart(1)
        state.recordCommandFinished(1, 0)
        state.clear()
        assertEquals(0L, state.revision.value)

        state.recordCurrentWorkingDirectory("file:///workspace")
        state.recordPromptStart(10)
        state.recordPromptEnd(11)
        val revision = state.revision.value
        state.recordCurrentWorkingDirectory("file:///workspace")
        state.recordPromptEnd(11)
        state.reanchorActivePromptStart(10)
        state.recordCommandFinished(11, 0)
        assertEquals(revision, state.revision.value)
    }

    @Test
    fun `invalid anchors do not mutate or invalidate the current prompt`() {
        val state = TerminalShellIntegrationState()
        state.recordPromptStart(10)
        val revision = state.revision.value

        assertFailsWith<IllegalArgumentException> { state.reanchorActivePromptStart(0) }
        assertFailsWith<IllegalArgumentException> { state.reanchorActivePromptStart(-1) }

        assertEquals(revision, state.revision.value)
        assertTrue(state.hasPromptStartAtLine(10))
    }

    @Test
    fun `reanchoring updates projections and command navigation`() {
        val state = TerminalShellIntegrationState()
        state.recordPromptStart(10)
        state.recordPromptEnd(12)
        state.reanchorActivePromptStart(11)
        state.recordCommandStart(13, includeLine = true)

        assertFalse(state.hasPromptStartAtLine(10))
        assertTrue(state.hasPromptStartAtLine(11))
        assertEquals(11L, state.commandAnchorLineId(state.latestCommandRecordId()))
    }

    @Test
    fun `completion listeners see committed metadata synchronously in registration order`() {
        var now = 100L
        val state = TerminalShellIntegrationState(epochMillis = { now })
        val calls = mutableListOf<String>()
        val producerThread = Thread.currentThread()
        var completed: TerminalShellIntegrationCommandMetadata? = null
        state
            .addCommandFinishedListener { metadata ->
                assertSame(producerThread, Thread.currentThread())
                assertFalse(state.hasRunningCommand())
                assertEquals(metadata, state.commandMetadata(metadata.recordId))
                calls += "first"
                completed = metadata
            }.use {
                state.addCommandFinishedListener { calls += "second" }.use {
                    state.recordPromptStart(10)
                    state.recordPromptEnd(10)
                    state.recordCommandStart(11, true, "git status", "file:///workspace")
                    val recordId = state.latestCommandRecordId()
                    val runningRevision = state.revision.value
                    now = 150
                    state.recordCommandFinished(12, 3)

                    assertEquals(listOf("first", "second"), calls)
                    assertEquals(
                        TerminalShellIntegrationCommandMetadata(
                            recordId,
                            TerminalShellIntegrationCommandLifecycle.FAILED,
                            "git status",
                            "file:///workspace",
                            3,
                            100,
                            150,
                        ),
                        completed,
                    )
                    assertTrue(state.revision.value > runningRevision)
                }
            }
    }

    @Test
    fun `unmatched duplicate and abandoned finishes do not invent completion events`() {
        val state = TerminalShellIntegrationState()
        val completed = mutableListOf<TerminalShellIntegrationCommandMetadata>()
        state.addCommandFinishedListener { completed += it }.use {
            state.recordCommandFinished(1, 0)
            state.recordPromptStart(2)
            state.recordCommandFinished(2, 0)
            state.recordCommandStart(3, true)
            state.recordPromptStart(4)
            state.recordCommandFinished(4, 0)
            assertTrue(completed.isEmpty())

            state.recordCommandStart(5, true)
            state.recordCommandFinished(6, null)
            state.recordCommandFinished(6, 0)
            assertEquals(1, completed.size)
            assertEquals(TerminalShellIntegrationCommandLifecycle.FINISHED_UNKNOWN, completed.single().lifecycle)
            assertNull(completed.single().exitCode)
        }
    }

    @Test
    fun `subscriptions do not replay and can be independently closed more than once`() {
        val state = TerminalShellIntegrationState()
        state.recordCommandStart(1, true)
        state.recordCommandFinished(2, 0)
        var calls = 0
        val listener: (TerminalShellIntegrationCommandMetadata) -> Unit = { calls++ }
        val first = state.addCommandFinishedListener(listener)
        val second = state.addCommandFinishedListener(listener)
        try {
            assertEquals(0, calls)
            first.close()
            first.close()
            state.recordCommandStart(3, true)
            state.recordCommandFinished(4, 0)
            assertEquals(1, calls)

            second.close()
            state.recordCommandStart(5, true)
            state.recordCommandFinished(6, 0)
            assertEquals(1, calls)
        } finally {
            first.close()
            second.close()
        }
    }

    @Test
    fun `closing a listener during notification prevents its pending dispatch`() {
        val state = TerminalShellIntegrationState()
        val pending = AtomicReference<AutoCloseable>()
        var pendingCalls = 0
        state.addCommandFinishedListener { pending.get().close() }.use {
            state.addCommandFinishedListener { pendingCalls++ }.use { registration ->
                pending.set(registration)
                state.recordCommandStart(1, true)
                state.recordCommandFinished(2, 0)
                assertEquals(0, pendingCalls)
            }
        }
    }

    @Test
    fun `listener failures cannot discard the completed record or starve another listener`() {
        val state = TerminalShellIntegrationState()
        val completed = mutableListOf<TerminalShellIntegrationCommandMetadata>()
        state.addCommandFinishedListener { error("Observer failure") }.use {
            state.addCommandFinishedListener { completed += it }.use {
                state.recordCommandStart(1, true)
                state.recordCommandFinished(2, 0)

                assertEquals(1, completed.size)
                assertEquals(completed.single(), state.commandMetadata(state.latestCommandRecordId()))
                assertFalse(state.hasRunningCommand())
            }
        }
    }

    @Test
    fun `listener cancellation reaches the producer after remaining listeners are notified`() {
        val state = TerminalShellIntegrationState()
        val cancellation = CancellationException("Host observer disposed")
        var notified = false
        state.addCommandFinishedListener { throw cancellation }.use {
            state.addCommandFinishedListener { notified = true }.use {
                state.recordCommandStart(1, true)
                val failure = assertFailsWith<CancellationException> { state.recordCommandFinished(2, 0) }

                assertSame(cancellation, failure)
                assertTrue(notified)
                assertFalse(state.hasRunningCommand())
                assertEquals(
                    TerminalShellIntegrationCommandLifecycle.SUCCEEDED,
                    state.commandMetadata(state.latestCommandRecordId())?.lifecycle,
                )
            }
        }
    }

    @ParameterizedTest
    @CsvSource("completion,false", "completion,true", "directory,false", "directory,true")
    fun `listener cancellations preserve identity without starving remaining observers`(
        event: String,
        reuseException: Boolean,
    ) {
        val state = TerminalShellIntegrationState()
        val first = CancellationException("First observer cancelled")
        val second = if (reuseException) first else CancellationException("Second observer cancelled")
        val notified = mutableListOf<Int>()

        fun observe(callback: () -> Unit): AutoCloseable =
            when (event) {
                "completion" -> state.addCommandFinishedListener { callback() }
                "directory" -> state.addCurrentWorkingDirectoryListener { callback() }
                else -> error("Unknown event: $event")
            }

        observe {
            notified += 1
            throw first
        }.use {
            observe {
                notified += 2
                throw second
            }.use {
                observe { notified += 3 }.use {
                    state.recordCommandStart(1, true)
                    val failure =
                        assertFailsWith<CancellationException> {
                            when (event) {
                                "completion" -> state.recordCommandFinished(2, 0)
                                "directory" -> state.recordCurrentWorkingDirectory("file:///project")
                            }
                        }

                    assertSame(first, failure)
                    assertEquals(listOf(1, 2, 3), notified)
                    if (reuseException) {
                        assertTrue(failure.suppressed.isEmpty())
                    } else {
                        assertSame(second, failure.suppressed.single())
                    }
                    when (event) {
                        "completion" -> {
                            assertFalse(state.hasRunningCommand())
                            assertEquals(
                                TerminalShellIntegrationCommandLifecycle.SUCCEEDED,
                                state.commandMetadata(state.latestCommandRecordId())?.lifecycle,
                            )
                        }
                        "directory" -> assertEquals("file:///project", state.currentWorkingDirectoryUri())
                    }
                }
            }
        }
    }

    @Test
    fun `completion events remain complete when revisions conflate and records are evicted`() =
        runTest {
            val state = TerminalShellIntegrationState(capacity = 1)
            val revisions = mutableListOf<Long>()
            val completed = mutableListOf<TerminalShellIntegrationCommandMetadata>()
            backgroundScope.launch { state.revision.collect { revisions += it } }
            runCurrent()
            state.addCommandFinishedListener { completed += it }.use {
                repeat(3) { index ->
                    state.recordCommandStart(index * 2L + 1, true, "command $index")
                    state.recordCommandFinished(index * 2L + 2, 0)
                }
                runCurrent()

                assertEquals(listOf(0L, state.revision.value), revisions)
                assertEquals(listOf("command 0", "command 1", "command 2"), completed.map { it.commandText })
                assertEquals(1, state.recordCount())
                assertNull(state.commandMetadata(completed.first().recordId))
                state.clear()
                state.recordCommandStart(7, true, "after clear")
                state.recordCommandFinished(8, 0)
                assertEquals("after clear", completed.last().commandText)
                assertEquals("command 0", completed.first().commandText)
            }
        }

    @Test
    fun `completion callbacks do not hold the model storage lock`() {
        val state = TerminalShellIntegrationState()
        var observed = false
        state
            .addCommandFinishedListener { metadata ->
                SessionTestThread("shell-completion-reader") {
                    assertEquals(metadata, state.commandMetadata(metadata.recordId))
                }.use { reader -> reader.awaitCompletion() }
                observed = true
            }.use {
                state.recordCommandStart(1, true)
                state.recordCommandFinished(2, 0)
                assertTrue(observed)
            }
    }

    @Test
    fun `unconfined revision collectors do not inherit the model storage lock`() =
        runTest {
            val state = TerminalShellIntegrationState()
            var observed = false
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                state.revision.collect { revision ->
                    if (revision != 0L) {
                        SessionTestThread("shell-revision-reader") {
                            assertEquals("file:///workspace", state.currentWorkingDirectoryUri())
                        }.use { reader -> reader.awaitCompletion() }
                        observed = true
                    }
                }
            }

            state.recordCurrentWorkingDirectory("file:///workspace")

            assertTrue(observed)
        }
}
