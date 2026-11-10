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
import io.github.ketraterm.testkit.MockConnector
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalCommandOutputTextTest {
    @Test
    fun `absent producer remains unavailable and validates limits first`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            TerminalSession
                .create(
                    TerminalBuffers.create(10, 3),
                    MockConnector(),
                    workerDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                    cleanupDispatcher = dispatcher,
                ).use { session ->
                    assertNull(session.commandOutputText(1))
                    for (limit in listOf(0, -1, Int.MIN_VALUE)) {
                        assertFailsWith<IllegalArgumentException> { session.commandOutputText(1, maxChars = limit) }
                        assertFailsWith<IllegalArgumentException> { session.commandOutputText(1, maxRows = limit) }
                    }
                    session.close()
                    assertFailsWith<IllegalArgumentException> { session.commandOutputText(1, maxRows = 0) }
                }
        }

    @Test
    fun `custom producer supplies bounded output and changed metadata rejects its snapshot`() =
        runTest {
            val state = TerminalShellIntegrationState()
            val host = TerminalShellIntegrationFactory.host(state)
            val dispatcher = StandardTestDispatcher(testScheduler)
            val result = TerminalCommandOutputText("tail", true, true)
            var changeMetadata = false
            val factory =
                TerminalShellIntegrationFactory { context ->
                    object : TerminalShellIntegration by host.create(context) {
                        override fun commandOutputText(
                            recordId: Int,
                            maxChars: Int,
                            maxRows: Int,
                        ): TerminalCommandOutputText {
                            assertEquals(42, recordId)
                            assertEquals(4, maxChars)
                            assertEquals(2, maxRows)
                            if (changeMetadata) state.recordCurrentWorkingDirectory("file:///changed")
                            return result
                        }
                    }
                }
            TerminalSession
                .create(
                    TerminalBuffers.create(10, 3),
                    MockConnector(),
                    shellIntegration = factory,
                    workerDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                    cleanupDispatcher = dispatcher,
                ).use { session ->
                    assertEquals(result, session.commandOutputText(42, 4, 2))
                    changeMetadata = true
                    assertNull(session.commandOutputText(42, 4, 2))
                }
        }

    @Test
    fun `host metadata alone does not fabricate command output`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            TerminalSession
                .create(
                    TerminalBuffers.create(10, 3),
                    MockConnector(),
                    shellIntegration = TerminalShellIntegrationFactory.host(TerminalShellIntegrationState()),
                    workerDispatcher = dispatcher,
                    ioDispatcher = dispatcher,
                    cleanupDispatcher = dispatcher,
                ).use { session -> assertNull(session.commandOutputText(1)) }
        }
}
