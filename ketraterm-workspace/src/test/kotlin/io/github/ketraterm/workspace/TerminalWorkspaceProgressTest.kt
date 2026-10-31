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
package io.github.ketraterm.workspace

import io.github.ketraterm.core.TerminalBuffers
import io.github.ketraterm.protocol.TerminalProgressState
import io.github.ketraterm.session.TerminalProgress
import io.github.ketraterm.session.TerminalSession
import io.github.ketraterm.testkit.MockConnector
import io.github.ketraterm.transport.TerminalConnector
import io.github.ketraterm.transport.TerminalConnectorListener
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalWorkspaceProgressTest {
    @Test
    fun `first-output progress is retained after tab publication and slow observers get the latest state`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val recorded = MockConnector()
            val events = mutableListOf<Pair<String, TerminalProgress>>()
            var published = false
            val connector =
                object : TerminalConnector by recorded {
                    override fun start(listener: TerminalConnectorListener) {
                        assertTrue(published)
                        recorded.start(listener)
                        recorded.feedFromHost("\u001b]9;4;1;50\u0007".encodeToByteArray())
                    }
                }
            val workspace =
                TerminalWorkspace(
                    listener =
                        object : TerminalWorkspaceListener {
                            override fun tabOpened(tab: TerminalWorkspaceTab) {
                                published = true
                            }

                            override fun progressChanged(
                                tab: TerminalWorkspaceTab,
                                progress: TerminalProgress,
                            ) {
                                assertTrue(published)
                                events += tab.id to progress
                            }
                        },
                    sessionFactory = { _, options, _ ->
                        TerminalSession.create(
                            TerminalBuffers.create(options.columns, options.rows),
                            connector,
                            workerDispatcher = dispatcher,
                            ioDispatcher = dispatcher,
                            cleanupDispatcher = dispatcher,
                        )
                    },
                    workerDispatcher = dispatcher,
                )
            workspace.use {
                val tab =
                    workspace.openTab(
                        TerminalProfile(id = "test", displayName = "Test", command = listOf("test")),
                        TerminalWorkspaceOpenOptions.builder().build(),
                    )
                assertEquals(50, tab.session.progress.value.percentage)
                assertTrue(events.isEmpty())
                runCurrent()
                assertEquals(listOf(tab.id to TerminalProgress(TerminalProgressState.NORMAL, 50)), events)
                repeat(1_000) { recorded.feedFromHost("\u001b]9;4;4;${it % 101}\u0007".encodeToByteArray()) }
                runCurrent()
                assertEquals(tab.id to TerminalProgress(TerminalProgressState.WARNING, 90), events.last())
                assertEquals(2, events.size)
                recorded.simulateClosed(0)
                runCurrent()
                assertEquals(tab.id to TerminalProgress.HIDDEN, events.last())
                workspace.closeTab(tab.id)
                val closedEvents = events.toList()
                advanceTimeBy(300_000.milliseconds)
                runCurrent()
                assertEquals(closedEvents, events)
            }
            assertFalse(workspace.isCoroutineScopeActive)
        }
}
